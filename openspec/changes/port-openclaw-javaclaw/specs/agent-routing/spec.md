## ADDED Requirements

### Requirement: Agent binding entity
The system SHALL store agent bindings in the database with fields: id, agent_id (FK), channel_type, peer_id (nullable), priority.

#### Scenario: Peer-specific binding
- **WHEN** a binding is created with agent_id, channel_type, and a non-null peer_id
- **THEN** the system SHALL route messages from that specific peer on that channel to the specified agent

#### Scenario: Channel-wide binding
- **WHEN** a binding is created with agent_id, channel_type, and peer_id=null
- **THEN** the system SHALL route all messages on that channel (without a more specific binding) to the specified agent

### Requirement: 3-tier routing resolution
The system SHALL resolve which agent handles an inbound message using three priority tiers: (1) exact peer match, (2) channel-wide match, (3) default agent.

#### Scenario: Tier 1 — exact peer match
- **WHEN** a message arrives from channel_type=telegram, peer_id=12345 AND a binding exists for (telegram, 12345) pointing to agent "support"
- **THEN** the system SHALL route the message to agent "support"

#### Scenario: Tier 2 — channel-wide match
- **WHEN** a message arrives from channel_type=slack, peer_id=U999 AND no peer-specific binding exists BUT a channel-wide binding (slack, null) points to agent "main"
- **THEN** the system SHALL route the message to agent "main"

#### Scenario: Tier 3 — default agent fallback
- **WHEN** a message arrives and no peer-specific or channel-wide binding matches
- **THEN** the system SHALL route the message to the agent marked as `is_default=true`

#### Scenario: No default agent configured
- **WHEN** a message arrives, no binding matches, and no agent is marked as default
- **THEN** the system SHALL log an ERROR event and discard the message

### Requirement: Web chat bypasses routing
The system SHALL allow the Nuxt web chat frontend to specify the target agent directly, bypassing the routing system.

#### Scenario: Direct agent selection in web chat
- **WHEN** a web chat message includes an explicit `agent_id` parameter
- **THEN** the system SHALL route the message to that agent without consulting bindings

### Requirement: Bindings manageable from admin UI
The system SHALL expose CRUD API endpoints for agent bindings, allowing the admin to create, read, update, and delete bindings from the Nuxt frontend.

#### Scenario: Create binding via API
- **WHEN** a `POST /api/bindings` request is received with agent_id, channel_type, and optional peer_id
- **THEN** the system SHALL create the binding and return the created record
