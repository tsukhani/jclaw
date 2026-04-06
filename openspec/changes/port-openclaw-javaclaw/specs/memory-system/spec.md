## ADDED Requirements

### Requirement: MemoryStore interface
The system SHALL define a `MemoryStore` interface with minimal operations: store, search, delete, list. All memory backends MUST implement this interface.

#### Scenario: Store a memory
- **WHEN** `store(agentId, text, category)` is called
- **THEN** the backend SHALL persist the memory and return a unique identifier

#### Scenario: Search memories
- **WHEN** `search(agentId, query, limit)` is called
- **THEN** the backend SHALL return up to `limit` memories relevant to the query, ordered by relevance

#### Scenario: Delete a memory
- **WHEN** `delete(id)` is called
- **THEN** the backend SHALL remove the memory with the given ID

#### Scenario: List memories by agent
- **WHEN** `list(agentId)` is called
- **THEN** the backend SHALL return all memories for the specified agent

### Requirement: JPA memory backend (default)
The system SHALL provide a JPA-based `MemoryStore` implementation that stores memories in a `memory` table with fields: id, agent_id, text, category, embedding (nullable), created_at, updated_at.

#### Scenario: Text search on H2 (dev/test)
- **WHEN** `search()` is called and the database is H2
- **THEN** the system SHALL use case-insensitive `LIKE` matching on the text field

#### Scenario: Full-text search on PostgreSQL (vector disabled)
- **WHEN** `search()` is called, the database is PostgreSQL, and `memory.jpa.vector.enabled=false`
- **THEN** the system SHALL use PostgreSQL full-text search (`to_tsvector`/`to_tsquery`) on the text field

#### Scenario: Hybrid search on PostgreSQL (vector enabled)
- **WHEN** `search()` is called, the database is PostgreSQL, and `memory.jpa.vector.enabled=true`
- **THEN** the system SHALL perform both PostgreSQL full-text search AND pgvector cosine similarity search, merge the results, and return them ranked by combined relevance

#### Scenario: Embedding generated on store (vector enabled)
- **WHEN** `store()` is called and `memory.jpa.vector.enabled=true`
- **THEN** the system SHALL call the configured embedding provider to generate a vector embedding and store it in the `embedding` column alongside the text

#### Scenario: Embedding not generated (vector disabled)
- **WHEN** `store()` is called and `memory.jpa.vector.enabled=false`
- **THEN** the system SHALL store the memory with a null embedding column

### Requirement: Neo4j memory backend (opt-in)
The system SHALL provide a Neo4j-based `MemoryStore` implementation that connects to a Neo4j instance via the official `neo4j-java-driver`.

#### Scenario: Neo4j backend selected
- **WHEN** `memory.backend=neo4j` is configured in `application.conf`
- **THEN** the system SHALL instantiate the Neo4j `MemoryStore` using connection details from `memory.neo4j.uri`, `memory.neo4j.user`, `memory.neo4j.password`

#### Scenario: Neo4j store and search
- **WHEN** `store()` and `search()` are called on the Neo4j backend
- **THEN** the Neo4j backend SHALL implement its own storage and search logic (nodes, relationships, graph traversal) while fulfilling the base `MemoryStore` contract

#### Scenario: Neo4j extends base functionality
- **WHEN** the Neo4j backend is active
- **THEN** it MAY provide additional capabilities beyond the base interface (entity extraction, graph queries, decay, forget) accessible through backend-specific APIs

### Requirement: Backend selection via configuration
The system SHALL select the active memory backend based on the `memory.backend` property in `application.conf`.

#### Scenario: Default backend
- **WHEN** `memory.backend` is not specified or set to `jpa`
- **THEN** the system SHALL use the JPA memory backend

#### Scenario: Neo4j backend
- **WHEN** `memory.backend=neo4j` is specified
- **THEN** the system SHALL use the Neo4j memory backend

#### Scenario: Invalid backend
- **WHEN** `memory.backend` is set to an unrecognized value
- **THEN** the system SHALL fail to start with a clear error message

### Requirement: Memory recall during prompt assembly
The system SHALL recall relevant memories during system prompt assembly and include them in the LLM context.

#### Scenario: Memories included in prompt
- **WHEN** an agent assembles a system prompt for an LLM call
- **THEN** the system SHALL call `MemoryStore.search()` with the user's latest message as the query and include the top results in the system prompt

#### Scenario: No memories found
- **WHEN** memory search returns no results
- **THEN** the system SHALL proceed with prompt assembly without a memories section

### Requirement: pgvector configuration
The JPA memory backend SHALL support optional pgvector integration configured via `application.conf`.

#### Scenario: pgvector configuration properties
- **WHEN** `memory.jpa.vector.enabled=true` is set
- **THEN** the system SHALL require `memory.jpa.vector.provider`, `memory.jpa.vector.model`, and `memory.jpa.vector.dimensions` to be configured

#### Scenario: pgvector not available
- **WHEN** `memory.jpa.vector.enabled=true` but the PostgreSQL instance does not have the pgvector extension installed
- **THEN** the system SHALL log an ERROR on startup and fall back to text-only search
