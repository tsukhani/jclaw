## MODIFIED Requirements

### Requirement: Database indexes on hot-path query columns
All JPA entities queried on hot paths SHALL declare `@Table(indexes=...)` annotations for columns used in WHERE, ORDER BY, and JOIN clauses.

#### Scenario: Message queries by conversation
- **GIVEN** the `Message` entity is queried by `conversation_id` on every message load and conversation list
- **THEN** an index on `(conversation_id)` and a composite index on `(conversation_id, created_at)` SHALL exist

#### Scenario: Task polling query
- **GIVEN** `TaskPollerJob` queries tasks by `(status, next_run_at)` every 30 seconds
- **THEN** a composite index on `(status, next_run_at)` SHALL exist

#### Scenario: EventLog queries
- **GIVEN** the logs API queries by timestamp, category, and level
- **THEN** indexes on `(timestamp)` and `(category, level)` SHALL exist

#### Scenario: AgentBinding routing lookup
- **GIVEN** `AgentRouter` queries bindings by `(channel_type, peer_id)` on every inbound message
- **THEN** a composite index on `(channel_type, peer_id)` SHALL exist

#### Scenario: Memory recall by agent
- **GIVEN** memory search queries by `agent_id`
- **THEN** an index on `(agent_id)` SHALL exist

#### Scenario: Conversation lookup by agent, channel, peer
- **GIVEN** `ConversationService.findOrCreate` queries by `(agent_id, channel_type, peer_id)`
- **THEN** a composite index on `(agent_id, channel_type, peer_id)` SHALL exist

### Requirement: Conversation list must not use N+1 queries
The `listConversations` endpoint SHALL NOT execute per-conversation subqueries for message count and preview.

#### Scenario: List 20 conversations
- **WHEN** the API lists 20 conversations
- **THEN** the system SHALL use at most 2-3 database queries (not 41) by using denormalized columns or batch queries

### Requirement: Memory queries must be bounded
`Memory.findByAgent` SHALL enforce a maximum result limit to prevent loading unbounded data into memory.

#### Scenario: Agent has 5000 memories
- **WHEN** `JpaMemoryStore.list()` is called for an agent with 5000 memories
- **THEN** at most 1000 (configurable) memories SHALL be returned
