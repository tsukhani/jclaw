## MODIFIED Requirements

### Requirement: SSE streaming must have a timeout guard
The `streamChat` endpoint SHALL enforce a maximum wall-clock timeout on the streaming response to prevent request threads from blocking indefinitely.

#### Scenario: Stream completes within timeout
- **WHEN** a streaming chat request completes within 120 seconds
- **THEN** the response SHALL be delivered normally with a complete event

#### Scenario: Stream exceeds timeout
- **WHEN** a streaming chat request does not complete within 120 seconds
- **THEN** the system SHALL write a timeout error SSE event and release the request thread

#### Scenario: Streaming virtual thread dies unexpectedly
- **WHEN** the virtual thread processing a streaming request throws an uncaught Error or the onError callback itself throws
- **THEN** the latch SHALL still be counted down (via finally block) and the request thread SHALL be released

### Requirement: SSE callbacks must be exception-safe
All SSE callback invocations (`onInit`, `onToken`, `onComplete`, `onError`) SHALL be wrapped in try/catch to prevent a single callback failure from hanging the request.

#### Scenario: onToken callback throws (client disconnected)
- **WHEN** `res.writeChunk()` inside the `onToken` callback throws an IOException because the client has disconnected
- **THEN** the system SHALL catch the exception, stop streaming, and count down the latch

#### Scenario: onError callback throws
- **WHEN** the `onError` callback itself throws while writing the error SSE event
- **THEN** the latch SHALL still be counted down via a finally block, preventing a thread hang

### Requirement: Streaming tool calls must have a round limit
The streaming path's `handleToolCallsStreaming` SHALL enforce the same `MAX_TOOL_ROUNDS` limit as the synchronous `callWithToolLoop`.

#### Scenario: Tool calls within round limit
- **WHEN** the LLM returns tool calls during streaming and the round count is below MAX_TOOL_ROUNDS
- **THEN** the system SHALL execute the tools and continue the conversation

#### Scenario: Tool calls exceed round limit
- **WHEN** the round count reaches MAX_TOOL_ROUNDS during streaming
- **THEN** the system SHALL stop executing tools and return the accumulated content to the user

### Requirement: maxTokens must be set on LLM requests
The system SHALL pass `ModelInfo.maxTokens` to `ChatRequest` to prevent unbounded response sizes.

#### Scenario: ChatRequest with maxTokens
- **WHEN** a chat request is constructed for any provider
- **THEN** the `maxTokens` field SHALL be populated from the model's configured maximum

### Requirement: Token counting before LLM dispatch
The system SHALL estimate the token count of the assembled prompt and trim history to fit within `ModelInfo.contextWindow`.

#### Scenario: Prompt fits within context window
- **WHEN** the estimated token count is within the model's context window
- **THEN** the full message history SHALL be sent unmodified

#### Scenario: Prompt exceeds context window
- **WHEN** the estimated token count exceeds the model's context window
- **THEN** the system SHALL trim the oldest non-system messages until the prompt fits, and log a warning

### Requirement: Streaming path should retry transient failures
The streaming path SHALL retry on transient 5xx errors before invoking `onError`, matching the sync path's retry behavior.

#### Scenario: First streaming attempt returns 503
- **WHEN** the LLM provider returns a 503 on the first streaming attempt
- **THEN** the system SHALL retry with backoff before failing
