## ADDED Requirements

### Requirement: Dashboard page
The Nuxt frontend SHALL provide a dashboard page at `/` showing system overview.

#### Scenario: Dashboard displays status
- **WHEN** the admin navigates to the dashboard
- **THEN** the page SHALL display agent status (count, enabled/disabled), recent activity (last 10 events), channel health (connected/disconnected per channel), and active task count

### Requirement: Chat page
The Nuxt frontend SHALL provide a chat page at `/chat` for direct LLM conversations.

#### Scenario: Start new chat
- **WHEN** the admin selects an agent and model from dropdown selectors and types a message
- **THEN** the frontend SHALL send the message via `POST /api/chat/send` and stream the response via SSE, rendering tokens as they arrive

#### Scenario: Continue existing chat
- **WHEN** the admin selects a previous conversation from the sidebar
- **THEN** the frontend SHALL load the conversation history and allow continuing the chat

#### Scenario: Model/provider selector
- **WHEN** the chat page loads
- **THEN** the frontend SHALL display a dropdown populated from the configured providers and models, allowing the admin to choose which model to use

### Requirement: Channels page
The Nuxt frontend SHALL provide a channels page at `/channels` for managing messaging platform integrations.

#### Scenario: Configure channel
- **WHEN** the admin selects a channel type (Telegram, Slack, WhatsApp)
- **THEN** the page SHALL display a configuration form with the required fields for that channel (tokens, secrets, URLs) and allow saving

#### Scenario: Channel status indicators
- **WHEN** the channels page loads
- **THEN** each configured channel SHALL display its current status (enabled/disabled, webhook registered/unregistered)

### Requirement: Conversations page
The Nuxt frontend SHALL provide a conversations page at `/conversations` for browsing all conversations across channels.

#### Scenario: List conversations
- **WHEN** the admin navigates to the conversations page
- **THEN** the page SHALL display a filterable, paginated list of conversations with channel type, agent name, peer identifier, message count, and last activity timestamp

#### Scenario: View conversation messages
- **WHEN** the admin clicks on a conversation
- **THEN** the page SHALL display all messages in that conversation in chronological order with role indicators (user/assistant) and timestamps

### Requirement: Tasks page
The Nuxt frontend SHALL provide a tasks page at `/tasks` for managing scheduled and recurring tasks.

#### Scenario: List tasks with filters
- **WHEN** the admin navigates to the tasks page
- **THEN** the page SHALL display a filterable list of tasks with columns for name, type, status, agent, next run time, and retry count

#### Scenario: Cancel or retry task
- **WHEN** the admin clicks cancel on a PENDING task or retry on a FAILED task
- **THEN** the frontend SHALL call the appropriate API endpoint and update the task status in the UI

### Requirement: Agents page
The Nuxt frontend SHALL provide an agents page at `/agents` for managing agent configurations.

#### Scenario: List and edit agents
- **WHEN** the admin navigates to the agents page
- **THEN** the page SHALL display all agents with their name, model, enabled status, and allow creating, editing, and deleting agents

#### Scenario: Edit agent workspace files
- **WHEN** the admin clicks on an agent
- **THEN** the page SHALL display the agent's AGENT.md, IDENTITY.md, and USER.md content in editable text areas, with a save button that writes changes to the filesystem

### Requirement: Skills page
The Nuxt frontend SHALL provide a skills page at `/skills` for viewing and managing agent skills.

#### Scenario: List skills per agent
- **WHEN** the admin navigates to the skills page and selects an agent
- **THEN** the page SHALL display all skills in that agent's workspace with their name and description

#### Scenario: View and edit skill content
- **WHEN** the admin clicks on a skill
- **THEN** the page SHALL display the full SKILL.md content in an editable text area with save functionality

### Requirement: Settings page
The Nuxt frontend SHALL provide a settings page at `/settings` for managing runtime configuration.

#### Scenario: Edit runtime config
- **WHEN** the admin navigates to the settings page
- **THEN** the page SHALL display all runtime configuration entries (LLM providers, API keys, feature flags) in an editable form, loaded from the Config database table

#### Scenario: Save config changes
- **WHEN** the admin modifies a config value and clicks save
- **THEN** the frontend SHALL POST the change to `POST /api/config` and the backend SHALL update the Config table without requiring a restart

### Requirement: Logs page
The Nuxt frontend SHALL provide a logs page at `/logs` for viewing system events.

#### Scenario: View and filter logs
- **WHEN** the admin navigates to the logs page
- **THEN** the page SHALL display EventLog entries with filters for category (llm, channel, tool, task, agent, auth, system), level (INFO, WARN, ERROR), time range, and free-text search

#### Scenario: Auto-refresh
- **WHEN** the admin is viewing the logs page
- **THEN** the page SHALL poll for new events every 5 seconds and prepend them to the list

### Requirement: Navigation layout
The Nuxt frontend SHALL provide a consistent navigation layout across all pages.

#### Scenario: Sidebar navigation
- **WHEN** any admin page is loaded
- **THEN** the layout SHALL include a sidebar with links to all 9 pages, grouped logically, with the current page highlighted

#### Scenario: Responsive design
- **WHEN** the admin accesses the UI from different screen sizes
- **THEN** the layout SHALL adapt appropriately (collapsible sidebar on mobile)
