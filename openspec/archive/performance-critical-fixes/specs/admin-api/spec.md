## CHANGED Requirements

### Requirement: Bindings list endpoint must not fire N+1 agent queries
`ApiBindingsController.list()` SHALL load all `AgentBinding` rows and their associated `Agent` rows in a single query using JOIN FETCH.

#### Scenario: List bindings with 50 entries
- **WHEN** `GET /api/bindings` is called and 50 `AgentBinding` rows exist
- **THEN** the system SHALL issue exactly one database query via `JPA.em().createQuery("SELECT b FROM AgentBinding b JOIN FETCH b.agent", AgentBinding.class)`
- **AND** `b.agent.id` and `b.agent.name` SHALL be populated from the join, not from lazy-loaded secondary selects

### Requirement: Conversations list endpoint must not fire N+1 agent queries
`ApiChatController.listConversations()` SHALL load `Conversation` rows with their associated `Agent` rows in a single query using JOIN FETCH.

#### Scenario: List 20 conversations with no filters
- **WHEN** `GET /api/conversations` is called with default parameters
- **THEN** the system SHALL issue one database query via `JPA.em().createQuery()`: `SELECT c FROM Conversation c JOIN FETCH c.agent ORDER BY c.updatedAt DESC`
- **AND** `c.agent.id` and `c.agent.name` SHALL be populated from the join

#### Scenario: List conversations filtered by channel and agent
- **WHEN** `GET /api/conversations?channel=web&agentId=3` is called
- **THEN** the system SHALL issue one query with JOIN FETCH and the WHERE clause applied, with no per-row agent selects

### Requirement: Tasks list endpoint must not fire N+1 agent queries
`ApiTasksController.list()` SHALL load `Task` rows with their associated `Agent` rows using LEFT JOIN FETCH (tasks may have a null agent).

#### Scenario: List 50 tasks with mixed agent assignments
- **WHEN** `GET /api/tasks` is called and 50 tasks exist, some with agents and some without
- **THEN** the system SHALL issue one database query via `JPA.em().createQuery()`: `SELECT t FROM Task t LEFT JOIN FETCH t.agent ORDER BY t.createdAt DESC`
- **AND** tasks with no agent SHALL have null `agentId`/`agentName` fields in the response
- **AND** no per-row `SELECT agent` queries SHALL be executed

### Requirement: Memory full-text search must batch-load results
`JpaMemoryStore.fullTextSearch()` and `hybridSearch()` SHALL replace the per-ID `Memory.findById(id)` loop with a single batch query.

#### Scenario: Full-text search returns 10 IDs
- **WHEN** the PostgreSQL FTS query returns 10 memory IDs
- **THEN** the system SHALL load all 10 `Memory` entities with one query: `Memory.find("id IN (?1)", ids).fetch()`
- **AND** the previous loop issuing 10 individual `SELECT memory WHERE id = ?` queries SHALL be removed

#### Scenario: Hybrid search returns results
- **WHEN** the pgvector hybrid search returns up to `limit` IDs
- **THEN** all matching `Memory` entities SHALL be loaded in one batch query, not one query per ID

### Requirement: JpaMemoryStore must not acquire a second connection while JPA holds one
`JpaMemoryStore` SQL operations SHALL obtain their JDBC connection from the active JPA `EntityManager` rather than from `DB.getConnection()`.

#### Scenario: Full-text search executes while JPA EntityManager is active
- **WHEN** `fullTextSearch()` runs within a JPA transaction context
- **THEN** the connection SHALL be obtained via `JPA.em().unwrap(java.sql.Connection.class)` rather than `DB.getConnection()`
- **AND** only one pool connection SHALL be held for the duration of the operation

#### Scenario: generateAndStoreEmbedding executes after Memory.save()
- **WHEN** `generateAndStoreEmbedding()` runs immediately after saving a new `Memory` entity
- **THEN** the embedding UPDATE SQL SHALL use the same JPA-managed connection, not a separately acquired pool connection

### Requirement: AgentBinding must have a database index on agent_id
The `agent_binding` table SHALL have an index on the `agent_id` foreign key column.

#### Scenario: Bindings loaded for a specific agent
- **WHEN** any query filters or joins `AgentBinding` on `agent.id`
- **THEN** the database SHALL use the `agent_id` index rather than a full table scan
