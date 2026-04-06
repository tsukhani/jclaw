## ADDED Requirements

### Requirement: Agent entity
The system SHALL store agent configurations in the database with fields: id, name, model_provider, model_id, enabled, is_default, created_at, updated_at.

#### Scenario: Create agent
- **WHEN** an agent is created with a name, model provider, and model ID
- **THEN** the system SHALL persist the agent to the database and create a workspace directory at `workspace/{agentId}/`

#### Scenario: Default agent
- **WHEN** multiple agents exist and one is marked as `is_default=true`
- **THEN** the system SHALL use that agent as the fallback for unrouted messages

#### Scenario: Disable agent
- **WHEN** an agent is set to `enabled=false`
- **THEN** the system SHALL NOT route new messages to that agent, but existing conversations SHALL remain accessible

### Requirement: Workspace directory structure
Each agent SHALL have a filesystem workspace containing markdown files that define the agent's identity, behavior, and skills.

#### Scenario: Workspace files loaded into system prompt
- **WHEN** an agent processes a message
- **THEN** the system SHALL read `AGENT.md`, `IDENTITY.md` (if exists), and `USER.md` (if exists) from `workspace/{agentId}/` and include their contents in the system prompt

#### Scenario: Missing optional workspace files
- **WHEN** `IDENTITY.md` or `USER.md` does not exist in the workspace
- **THEN** the system SHALL skip those files without error and assemble the prompt from available files only

### Requirement: System prompt assembly
The system SHALL assemble the system prompt in a defined order before each LLM call.

#### Scenario: Full prompt assembly
- **WHEN** an agent receives a message to process
- **THEN** the system SHALL assemble the system prompt in this order: (1) AGENT.md content, (2) IDENTITY.md content, (3) USER.md content, (4) matched skill names and descriptions as XML, (5) recalled memories from MemoryStore, (6) environment info (time, timezone, platform), (7) tool definitions

### Requirement: Skill loading and matching
The system SHALL scan `workspace/{agentId}/skills/*/SKILL.md` files, extract YAML frontmatter (name, description), and inject all skill names and descriptions into the system prompt for LLM-driven matching.

#### Scenario: Skills injected into prompt
- **WHEN** an agent has skills in its workspace
- **THEN** the system SHALL include all skill names and descriptions in the system prompt as `<available_skills>` XML with instructions for the LLM to select and read the most relevant skill

#### Scenario: LLM reads selected skill
- **WHEN** the LLM determines a skill matches the user's message
- **THEN** the LLM SHALL use the FileSystemTools to read the full SKILL.md content and follow its instructions

#### Scenario: Token budget exceeded
- **WHEN** the total skills block exceeds 30,000 characters or 150 skills
- **THEN** the system SHALL switch to compact format (name and location only, no descriptions) and truncate if still over budget

### Requirement: Conversation management
The system SHALL maintain conversations as database entities linked to an agent, channel, and peer, with messages stored as individual rows.

#### Scenario: New conversation created
- **WHEN** a message arrives from a channel+peer combination that has no existing conversation for the routed agent
- **THEN** the system SHALL create a new Conversation record and store the message as the first Message row

#### Scenario: Existing conversation continued
- **WHEN** a message arrives from a channel+peer combination that has an existing conversation
- **THEN** the system SHALL append the new message to the existing conversation

#### Scenario: Context window assembly
- **WHEN** the system prepares messages for an LLM call
- **THEN** the system SHALL load the most recent N messages (configurable, default 50) from the conversation, ordered chronologically
