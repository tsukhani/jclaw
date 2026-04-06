## ADDED Requirements

### Requirement: Task entity
The system SHALL store tasks in a database table with fields: id, agent_id, name, description, type (IMMEDIATE/SCHEDULED/CRON), cron_expression, scheduled_at, status (PENDING/RUNNING/COMPLETED/FAILED/CANCELLED), retry_count, max_retries (default 3), last_error, next_run_at, created_at, updated_at.

#### Scenario: Task created with correct defaults
- **WHEN** a task is created
- **THEN** the system SHALL set status=PENDING, retry_count=0, max_retries=3, and compute next_run_at based on task type

### Requirement: Task poller
The system SHALL run a Play `@Every("30s")` background job that polls the tasks table for executable tasks.

#### Scenario: Pick up pending tasks
- **WHEN** the poller runs
- **THEN** the system SHALL query for tasks WHERE `status=PENDING AND next_run_at <= now()`, ordered by next_run_at ASC

#### Scenario: Execute task
- **WHEN** the poller picks up a task
- **THEN** the system SHALL set status=RUNNING, invoke the associated agent with the task description as the user message, and update status to COMPLETED on success

#### Scenario: Task execution in virtual thread
- **WHEN** the poller picks up multiple tasks
- **THEN** each task SHALL be executed in its own virtual thread to avoid blocking the poller

### Requirement: Task retry with exponential backoff
The system SHALL retry failed tasks with exponential backoff up to the configured max_retries.

#### Scenario: Task fails, retries remaining
- **WHEN** a task execution fails and retry_count < max_retries
- **THEN** the system SHALL increment retry_count, set last_error to the exception message, compute next_run_at with exponential backoff (30s * 2^retry_count), and set status=PENDING

#### Scenario: Task fails, no retries remaining
- **WHEN** a task execution fails and retry_count >= max_retries
- **THEN** the system SHALL set status=FAILED, set last_error, and log an ERROR event

### Requirement: CRON recurring tasks
The system SHALL support recurring tasks that re-schedule themselves based on a CRON expression after each execution.

#### Scenario: CRON task completes
- **WHEN** a CRON task completes successfully
- **THEN** the system SHALL compute the next execution time from the cron_expression, insert a new PENDING task with that next_run_at, and mark the current task as COMPLETED

#### Scenario: CRON task cancelled
- **WHEN** a CRON task is cancelled via the admin UI or TaskTool
- **THEN** the system SHALL set status=CANCELLED and NOT create subsequent tasks

### Requirement: Task management API
The system SHALL expose CRUD API endpoints for tasks, allowing the admin UI to view, cancel, and retry tasks.

#### Scenario: List tasks
- **WHEN** `GET /api/tasks` is requested with optional filters (status, type, agent_id)
- **THEN** the system SHALL return paginated task records

#### Scenario: Cancel task
- **WHEN** `POST /api/tasks/{id}/cancel` is requested
- **THEN** the system SHALL set the task status to CANCELLED if it is PENDING

#### Scenario: Retry failed task
- **WHEN** `POST /api/tasks/{id}/retry` is requested on a FAILED task
- **THEN** the system SHALL reset retry_count to 0, set status=PENDING, and set next_run_at=now
