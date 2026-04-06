## ADDED Requirements

### Requirement: Tool interface
The system SHALL define a `Tool` interface that all tools implement, providing a name, description, parameter schema (JSON), and an execute method.

#### Scenario: Tool registered with agent
- **WHEN** an agent is configured with a set of tools
- **THEN** the system SHALL serialize tool definitions (name, description, parameters) in OpenAI function-calling format and include them in LLM requests

#### Scenario: Tool execution
- **WHEN** the LLM returns a tool call in its response
- **THEN** the system SHALL look up the tool by name, deserialize the arguments, execute the tool, and return the result to the LLM for continued processing

#### Scenario: Tool execution failure
- **WHEN** a tool's execute method throws an exception
- **THEN** the system SHALL catch the exception and return an error string to the LLM (e.g., "Error: {message}") without crashing the agent pipeline

#### Scenario: Unknown tool called
- **WHEN** the LLM calls a tool name that is not registered
- **THEN** the system SHALL return an error string to the LLM indicating the tool does not exist

### Requirement: TaskTool
The system SHALL provide a TaskTool that allows the LLM to create, schedule, and manage background tasks.

#### Scenario: Create immediate task
- **WHEN** the LLM calls `createTask(name, description)`
- **THEN** the system SHALL insert a task row with type=IMMEDIATE, status=PENDING, next_run_at=now

#### Scenario: Schedule delayed task
- **WHEN** the LLM calls `scheduleTask(executionTime, name, description)` with an ISO datetime
- **THEN** the system SHALL insert a task row with type=SCHEDULED, status=PENDING, scheduled_at and next_run_at set to the specified time

#### Scenario: Schedule recurring task
- **WHEN** the LLM calls `scheduleRecurringTask(cronExpression, name, description)`
- **THEN** the system SHALL insert a task row with type=CRON, cron_expression set, and next_run_at computed from the cron expression

#### Scenario: Delete recurring task
- **WHEN** the LLM calls `deleteRecurringTask(name)`
- **THEN** the system SHALL mark the matching recurring task as CANCELLED

#### Scenario: List recurring tasks
- **WHEN** the LLM calls `listRecurringTasks()`
- **THEN** the system SHALL return all active recurring tasks with their name, description, and cron expression

### Requirement: CheckListTool
The system SHALL provide a CheckListTool that allows the LLM to create and manage structured checklists for tracking multi-step work.

#### Scenario: Create checklist
- **WHEN** the LLM calls the CheckListTool with a list of items, each having content, status, and activeForm
- **THEN** the system SHALL validate that exactly one item is `in_progress` and return success

#### Scenario: Invalid checklist state
- **WHEN** the LLM submits a checklist with zero or more than one `in_progress` item
- **THEN** the system SHALL return an error string describing the validation failure

### Requirement: FileSystemTools
The system SHALL provide file system tools scoped to the agent's workspace directory, allowing the LLM to read, write, list, and edit files.

#### Scenario: Read file
- **WHEN** the LLM calls `readFile(path)` with a path relative to the workspace
- **THEN** the system SHALL return the file contents

#### Scenario: Write file
- **WHEN** the LLM calls `writeFile(path, content)` with a workspace-relative path
- **THEN** the system SHALL write the content to the file, creating directories as needed

#### Scenario: List directory
- **WHEN** the LLM calls `listFiles(path)` with a workspace-relative directory path
- **THEN** the system SHALL return a list of file and directory names in that directory

#### Scenario: Path traversal prevention
- **WHEN** the LLM calls any file tool with a path that escapes the workspace (e.g., `../../etc/passwd`)
- **THEN** the system SHALL reject the request and return an error string

### Requirement: WebFetchTool
The system SHALL provide a WebFetchTool that allows the LLM to fetch the content of a URL.

#### Scenario: Fetch URL
- **WHEN** the LLM calls `fetchUrl(url)`
- **THEN** the system SHALL make an HTTP GET request to the URL and return the response body as text, truncated to a configurable maximum length (default 50,000 characters)

#### Scenario: Fetch timeout
- **WHEN** the HTTP request does not complete within 30 seconds
- **THEN** the system SHALL return an error string indicating timeout

#### Scenario: Non-text response
- **WHEN** the response content type is not text-based (e.g., binary, image)
- **THEN** the system SHALL return a message indicating the content type and size without the body

### Requirement: SkillsTool
The system SHALL provide a SkillsTool that enables the LLM to discover and read skill files from the agent's workspace.

#### Scenario: List available skills
- **WHEN** the LLM calls `listSkills()`
- **THEN** the system SHALL return all skill names and descriptions from the agent's `workspace/{agentId}/skills/` directory

#### Scenario: Read skill
- **WHEN** the LLM calls `readSkill(name)`
- **THEN** the system SHALL return the full contents of the corresponding SKILL.md file
