## ADDED Requirements

### Requirement: Web chat message endpoint
The system SHALL accept chat messages from the Nuxt frontend via `POST /api/chat/send` with a JSON body containing conversation_id (optional), agent_id, and message text.

#### Scenario: New web chat conversation
- **WHEN** a POST is received with an agent_id and message text but no conversation_id
- **THEN** the system SHALL create a new conversation for the web channel and specified agent, store the user message, invoke the agent, and return the conversation_id

#### Scenario: Continue web chat conversation
- **WHEN** a POST is received with an existing conversation_id and message text
- **THEN** the system SHALL append the message to the existing conversation and invoke the agent

### Requirement: SSE streaming response
The system SHALL stream LLM responses to the Nuxt frontend via Server-Sent Events on `GET /api/chat/stream/{conversationId}`.

#### Scenario: Stream tokens as they arrive
- **WHEN** the LLM provider streams response tokens
- **THEN** the system SHALL forward each token as an SSE `data` event to the connected client

#### Scenario: Stream completion
- **WHEN** the LLM response is fully received
- **THEN** the system SHALL send a final SSE event indicating completion and close the stream

#### Scenario: Stream error
- **WHEN** the LLM call fails during streaming
- **THEN** the system SHALL send an SSE error event with a human-readable message and close the stream

#### Scenario: Tool call during stream
- **WHEN** the LLM response includes tool calls
- **THEN** the system SHALL send an SSE event indicating tool execution, execute the tools, send the results back to the LLM, and continue streaming the LLM's follow-up response

### Requirement: Agent selection in web chat
The system SHALL allow the frontend to specify which agent and model to use for web chat conversations.

#### Scenario: Select agent for chat
- **WHEN** the frontend sends a chat message with an explicit agent_id
- **THEN** the system SHALL use that agent's configuration (system prompt, skills, tools) for the LLM call

#### Scenario: List available agents and models
- **WHEN** the frontend requests `GET /api/agents`
- **THEN** the system SHALL return all enabled agents with their configured models, allowing the frontend to populate a selector dropdown

### Requirement: Conversation history in web chat
The system SHALL provide conversation history for the web chat interface.

#### Scenario: Load conversation messages
- **WHEN** the frontend requests `GET /api/conversations/{id}/messages`
- **THEN** the system SHALL return all messages in the conversation ordered chronologically

#### Scenario: List web chat conversations
- **WHEN** the frontend requests `GET /api/conversations?channel=web`
- **THEN** the system SHALL return all web chat conversations with metadata (agent name, last message timestamp, message count)
