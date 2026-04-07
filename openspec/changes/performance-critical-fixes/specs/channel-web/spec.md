## CHANGED Requirements

### Requirement: SSE client disconnect must cancel the streaming virtual thread
When a web SSE client disconnects during streaming, the system SHALL propagate the disconnect signal to `AgentRunner.runStreaming()` so it stops processing as soon as possible.

#### Scenario: Client closes the browser tab during streaming
- **WHEN** the browser tab is closed and `res.writeChunk()` throws in `onToken`
- **THEN** the `cancelled` flag SHALL be set to `true`
- **AND** the latch SHALL be counted down immediately
- **AND** the virtual thread SHALL check `cancelled` before the next LLM call or tool round and exit without further work

#### Scenario: Client disconnects after onInit but before any tokens
- **WHEN** the browser disconnects after the `init` event is written but before any token events
- **THEN** `cancelled` SHALL be set to `true` in the `onInit` catch block
- **AND** the virtual thread SHALL exit before assembling the prompt or calling the LLM

#### Scenario: Virtual thread exits cleanly after cancellation
- **WHEN** the virtual thread detects `cancelled == true` and exits early
- **THEN** the `finally` block in `runStreaming()` SHALL still call `ConversationQueue.drain()` to release the queue slot
- **AND** no partial assistant message SHALL be persisted if cancellation happened before the LLM responded

### Requirement: SSE latch timeout must be 180 seconds
The `latch.await()` call in `ApiChatController.streamChat()` SHALL use a 180-second timeout instead of 600 seconds.

#### Scenario: Stream completes normally within 60 seconds
- **WHEN** the agent responds within 60 seconds
- **THEN** the latch is counted down by `onComplete` and the controller thread returns normally

#### Scenario: Stream exceeds 180 seconds
- **WHEN** neither `onComplete` nor `onError` counts down the latch within 180 seconds
- **THEN** the controller thread SHALL write a timeout error SSE event and return
- **AND** the 600-second timeout SHALL no longer be used

### Requirement: SSE connection must send keep-alive comments every 30 seconds
The streaming response SHALL emit `: keep-alive\n\n` SSE comments periodically to prevent proxy and browser connection timeouts during long tool chains.

#### Scenario: Agent executes a 45-second tool chain
- **WHEN** a tool chain runs for 45 seconds with no token events
- **THEN** the system SHALL send at least one keep-alive SSE comment during that period
- **AND** the browser and any intermediate proxies SHALL keep the connection open

#### Scenario: Response completes before first heartbeat interval
- **WHEN** the full response arrives within 30 seconds
- **THEN** no keep-alive comment is required and no spurious events SHALL be written after `onComplete`

### Requirement: Connection pool must be explicitly configured
`conf/application.conf` SHALL declare explicit `db.pool.*` settings so the Play 1.x connection pool does not default to an uncontrolled small size.

#### Scenario: Application starts in development mode
- **WHEN** the application starts with default configuration
- **THEN** the JDBC pool SHALL have `minSize=5`, `maxSize=20`, and `timeout=10000` ms

#### Scenario: Application starts in production mode
- **WHEN** the application starts with `%prod` configuration active
- **THEN** the JDBC pool SHALL have `maxSize=30` and `timeout=5000` ms to handle higher concurrency
