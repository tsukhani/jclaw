## ADDED Requirements

### Requirement: EventLog entity
The system SHALL store system events in an `event_log` database table with fields: id, timestamp, level (INFO/WARN/ERROR), category (llm/channel/tool/task/agent/auth/system), agent_id (nullable), channel (nullable), message, details (JSON text, nullable), created_at.

#### Scenario: Log an event
- **WHEN** `EventLog.record(level, category, message, details)` is called
- **THEN** the system SHALL insert a row into the event_log table with the current timestamp

### Requirement: Dual output logging
Every event written to the EventLog table SHALL also be logged via SLF4J at the corresponding level.

#### Scenario: ERROR event
- **WHEN** an ERROR event is recorded
- **THEN** the system SHALL write to the event_log table AND call `logger.error()` with the message

#### Scenario: WARN event
- **WHEN** a WARN event is recorded
- **THEN** the system SHALL write to the event_log table AND call `logger.warn()` with the message

#### Scenario: INFO event
- **WHEN** an INFO event is recorded
- **THEN** the system SHALL write to the event_log table AND call `logger.info()` with the message

### Requirement: Event categories
The system SHALL log events across the following categories with specific triggers.

#### Scenario: LLM events
- **WHEN** an LLM request is sent, response received, retry triggered, failover activated, or final failure occurs
- **THEN** the system SHALL log events with category=llm and appropriate level

#### Scenario: Channel events
- **WHEN** a message is received via webhook, sent to a channel, delivery fails, or webhook is verified
- **THEN** the system SHALL log events with category=channel

#### Scenario: Tool events
- **WHEN** a tool is invoked or tool execution fails
- **THEN** the system SHALL log events with category=tool

#### Scenario: Task events
- **WHEN** a task is created, scheduled, completed, retried, or fails permanently
- **THEN** the system SHALL log events with category=task

#### Scenario: Agent events
- **WHEN** a conversation is created or memory is stored/recalled
- **THEN** the system SHALL log events with category=agent

#### Scenario: Auth events
- **WHEN** an admin login succeeds or fails
- **THEN** the system SHALL log events with category=auth

#### Scenario: System events
- **WHEN** the application starts, stops, config changes, or an unhandled exception is caught
- **THEN** the system SHALL log events with category=system

### Requirement: Event log API
The system SHALL expose API endpoints for querying events from the admin UI.

#### Scenario: Query events with filters
- **WHEN** `GET /api/logs` is requested with optional query parameters (category, level, agent_id, channel, since, until, search, limit, offset)
- **THEN** the system SHALL return matching EventLog entries ordered by timestamp descending

#### Scenario: Default query limit
- **WHEN** no limit is specified
- **THEN** the system SHALL return at most 100 events

### Requirement: Event log retention
The system SHALL automatically prune old events to prevent unbounded table growth.

#### Scenario: Daily cleanup job
- **WHEN** the daily cleanup Play `@Every("24h")` job runs
- **THEN** the system SHALL delete EventLog entries older than the configured retention period (default 30 days, configurable via `jclaw.logs.retention.days` in application.conf)
