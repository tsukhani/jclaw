## CHANGED Requirements

### Requirement: Synchronous agent pipeline must not hold a DB connection during LLM calls
The synchronous `AgentRunner.run()` method SHALL scope all JPA operations to short `Tx.run()` blocks and SHALL NOT hold an open JDBC connection across `callWithToolLoop()`.

#### Scenario: User sends a synchronous chat message
- **WHEN** `AgentRunner.run()` is invoked for a synchronous chat request
- **THEN** the system SHALL load conversation history, assemble the system prompt, and resolve the provider inside one short `Tx.run()` block at the top of the method
- **AND** `callWithToolLoop()` SHALL execute with no open transaction

#### Scenario: Tool call executed during synchronous run
- **WHEN** the LLM returns a tool call during a synchronous run
- **THEN** the tool result SHALL be persisted (`appendAssistantMessage` + `appendToolResult`) inside its own dedicated `Tx.run()` block within the loop
- **AND** no JDBC connection SHALL be held during tool execution itself

#### Scenario: Final assistant response persisted after synchronous run
- **WHEN** `callWithToolLoop()` returns the final response text
- **THEN** `appendAssistantMessage` SHALL be called inside a `Tx.run()` block
- **AND** the method SHALL return without any open transaction

#### Scenario: Detached entity error eliminated
- **WHEN** `appendMessage` is called inside a `Tx.run()` block that opened a fresh EntityManager
- **THEN** the system SHALL reload the `Conversation` by `conversationId` (Long) within that `Tx.run()` rather than passing the detached entity across transaction boundaries
- **AND** the `JPA.em().contains()` / `merge()` workaround in `ConversationService.appendMessage()` SHALL be removed

### Requirement: Streaming pipeline must propagate client disconnect to cancel agent work
`AgentRunner.runStreaming()` SHALL detect SSE client disconnection and abort remaining LLM calls, tool executions, and persistence steps.

#### Scenario: Client disconnects during token streaming
- **WHEN** `res.writeChunk()` throws an exception inside the `onToken` callback
- **THEN** the system SHALL set a shared `cancelled` flag to `true` and count down the latch
- **AND** the virtual thread SHALL check `cancelled` before each major phase (prompt assembly, LLM call, tool execution, persistence) and exit early if set

#### Scenario: Client disconnects during onInit
- **WHEN** `res.writeChunk()` throws inside the `onInit` callback (conversation ID event)
- **THEN** the system SHALL set `cancelled = true`, count down the latch, and the virtual thread SHALL exit before making any LLM call

#### Scenario: Client disconnects during onComplete
- **WHEN** `res.writeChunk()` throws inside the `onComplete` callback
- **THEN** the latch SHALL still be counted down and the virtual thread SHALL be considered finished

#### Scenario: Streaming pipeline cancelled mid-tool-chain
- **WHEN** `cancelled` is `true` when the virtual thread checks before a tool execution round
- **THEN** the system SHALL skip all remaining tool rounds and persistence steps, and the finally block SHALL still call `ConversationQueue.drain()`

#### Scenario: Latch timeout reduced to match LLM timeout
- **WHEN** the virtual thread does not complete within 180 seconds
- **THEN** the `latch.await()` SHALL time out and the controller thread SHALL write a timeout error SSE event and return
- **AND** the previous 600-second timeout SHALL no longer be used

### Requirement: SSE connections must send periodic heartbeats during long tool chains
The streaming SSE response SHALL emit keep-alive comments at a regular interval to prevent proxy and browser connection timeouts.

#### Scenario: Tool chain takes longer than 30 seconds
- **WHEN** more than 30 seconds elapse between SSE data events during a tool chain
- **THEN** the system SHALL write a `: keep-alive\n\n` SSE comment to the response
- **AND** the heartbeat SHALL not interfere with token or event delivery
