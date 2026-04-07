## MODIFIED Requirements

### Requirement: JDBC connections must be closed after use
All `DB.getConnection()` calls in `JpaMemoryStore` SHALL be wrapped in try-with-resources to ensure connections are returned to the pool.

#### Scenario: fullTextSearch completes successfully
- **WHEN** a full-text search is executed against the memory store
- **THEN** the JDBC connection, PreparedStatement, and ResultSet SHALL all be closed after the query completes

#### Scenario: fullTextSearch throws an exception
- **WHEN** a full-text search throws a SQL exception during execution
- **THEN** the JDBC connection SHALL still be closed via the try-with-resources finally block

#### Scenario: hybridSearch completes
- **WHEN** a hybrid (text + vector) search is executed
- **THEN** the JDBC connection SHALL be closed regardless of success or failure

#### Scenario: generateAndStoreEmbedding completes
- **WHEN** an embedding is generated and stored via raw SQL
- **THEN** the JDBC connection SHALL be closed regardless of success or failure

### Requirement: Webhook controllers must not hold DB connections during LLM calls
All webhook controllers (Slack, Telegram, WhatsApp) SHALL break their transaction boundaries into discrete short-lived transactions that do not span LLM HTTP calls.

#### Scenario: Slack webhook processes a message
- **WHEN** a Slack message webhook is received
- **THEN** the system SHALL open a transaction to find/create the conversation and persist the user message, close it, execute the LLM call with no open transaction, then open a new transaction to persist the response

#### Scenario: LLM call fails after conversation created
- **WHEN** the conversation and user message are persisted but the LLM call fails
- **THEN** the conversation and user message SHALL remain persisted (not rolled back) and an error SHALL be logged

### Requirement: Virtual threads must have JPA transaction context
All JPA operations executed on virtual threads (outside Play's request lifecycle) SHALL be wrapped in `Tx.run()`.

#### Scenario: TaskPollerJob executes a task
- **WHEN** a task is dispatched to a virtual thread for execution
- **THEN** all `task.save()` calls within that thread SHALL be wrapped in `Tx.run()` to provide EntityManager context

#### Scenario: Virtual thread JPA operation without Tx.run
- **WHEN** a JPA operation is attempted on a virtual thread without `Tx.run()`
- **THEN** the system SHALL NOT allow this — all virtual thread JPA paths must use `Tx.run()`
