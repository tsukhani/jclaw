## ADDED Requirements

### Requirement: Per-conversation message queue
The system SHALL maintain a per-conversation queue that serializes message processing to prevent state corruption from concurrent messages.

#### Scenario: Single message processing
- **WHEN** a message arrives for a conversation with no pending processing
- **THEN** the system SHALL process it immediately via AgentRunner

#### Scenario: Concurrent message arrives
- **WHEN** a second message arrives for a conversation while the first is still being processed
- **THEN** the system SHALL enqueue the second message instead of processing it concurrently
- **AND** the system SHALL process the queued message after the first completes

#### Scenario: Queue overflow
- **WHEN** the queue for a conversation reaches the cap (20 messages)
- **THEN** the system SHALL drop the oldest message to make room for the new one

### Requirement: Queue mode — queue (FIFO)
In queue mode, messages SHALL be processed one at a time in the order they were received.

#### Scenario: Three messages arrive in sequence while agent is busy
- **WHEN** messages A, B, C arrive while the agent is processing a prior message
- **THEN** the system SHALL queue them and process A, then B, then C — each after the previous completes

### Requirement: Queue mode — collect (batch)
In collect mode, all pending messages SHALL be combined into a single prompt when the agent becomes available.

#### Scenario: Three messages batched
- **WHEN** messages A, B, C are queued while the agent is busy
- **AND** the agent finishes processing
- **THEN** the system SHALL combine A, B, C into a single prompt formatted as:
  ```
  [Queued messages while agent was busy]
  ---
  Message 1: {A}
  ---
  Message 2: {B}
  ---
  Message 3: {C}
  ```
- **AND** process the combined prompt as a single agent run

### Requirement: Queue mode — interrupt
In interrupt mode, a new message SHALL cancel the in-flight response and be processed immediately.

#### Scenario: New message interrupts processing
- **WHEN** the agent is processing message A and message B arrives
- **THEN** the system SHALL cancel the in-flight LLM call for message A
- **AND** process message B immediately

#### Scenario: Interrupted response handling
- **WHEN** an in-flight response is cancelled
- **THEN** the partial response (if any) SHALL be persisted with a "[interrupted]" marker
- **AND** the conversation history SHALL reflect the interruption

### Requirement: Queue integration with all channels
All message entry points SHALL route through the conversation queue.

#### Scenario: Web chat message while busy
- **WHEN** a web chat SSE message arrives while the agent is processing
- **THEN** the system SHALL return an SSE event `{"type": "queued", "position": N}` indicating the queue position

#### Scenario: Telegram message while busy
- **WHEN** a Telegram webhook delivers a message while the agent is processing for that conversation
- **THEN** the system SHALL enqueue the message and optionally send a "message received" acknowledgement

#### Scenario: Queue drain after completion
- **WHEN** the agent finishes processing a message
- **THEN** the system SHALL drain the queue and process the next message (or batch) if present
- **AND** queue draining SHALL be protected by try/finally to prevent deadlock on error

### Requirement: Queue configuration per agent
Each agent SHALL have a configurable queue mode.

#### Scenario: Default queue mode
- **WHEN** no queue mode is configured for an agent
- **THEN** the system SHALL use "queue" (FIFO) mode

#### Scenario: Agent-specific queue mode
- **WHEN** an agent's queue mode is set to "collect" via configuration
- **THEN** all conversations for that agent SHALL use collect mode for queuing
