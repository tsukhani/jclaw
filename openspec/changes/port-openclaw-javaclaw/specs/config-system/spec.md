## ADDED Requirements

### Requirement: Boot-time configuration via application.conf
The system SHALL read infrastructure configuration from Play's `application.conf` at startup. These settings require an application restart to take effect.

#### Scenario: Database configuration
- **WHEN** the application starts
- **THEN** the system SHALL read database connection settings (`db.url`, `db.user`, `db.pass`) and configure JPA/Hibernate accordingly

#### Scenario: Memory backend configuration
- **WHEN** the application starts
- **THEN** the system SHALL read `memory.backend`, `memory.jpa.vector.*`, and `memory.neo4j.*` settings and instantiate the appropriate MemoryStore backend

#### Scenario: Admin credentials
- **WHEN** the application starts
- **THEN** the system SHALL read `jclaw.admin.username` and `jclaw.admin.password` for authentication

#### Scenario: Environment-specific overrides
- **WHEN** the application runs in production mode
- **THEN** settings prefixed with `%prod.` SHALL override defaults (e.g., `%prod.db.url` overrides `db.url`)

### Requirement: Runtime configuration via database
The system SHALL store runtime configuration in a `Config` database table (id, key, value, updated_at) that is editable from the admin UI without requiring a restart.

#### Scenario: Read runtime config
- **WHEN** the system needs a runtime configuration value (e.g., LLM provider API key)
- **THEN** the system SHALL query the Config table by key

#### Scenario: Update runtime config via API
- **WHEN** `POST /api/config` is received with a key-value pair
- **THEN** the system SHALL upsert the value in the Config table and return success

#### Scenario: Config cache
- **WHEN** runtime config values are read frequently
- **THEN** the system SHALL cache config values in memory with a TTL (default 60 seconds) to avoid repeated database queries

### Requirement: Runtime config API
The system SHALL expose API endpoints for managing runtime configuration.

#### Scenario: List all config entries
- **WHEN** `GET /api/config` is requested
- **THEN** the system SHALL return all Config table entries as key-value pairs, with sensitive values (containing "key", "secret", "password", "token") masked in the response

#### Scenario: Get single config value
- **WHEN** `GET /api/config/{key}` is requested
- **THEN** the system SHALL return the value for that key, or HTTP 404 if not found

#### Scenario: Delete config entry
- **WHEN** `DELETE /api/config/{key}` is requested
- **THEN** the system SHALL remove the entry from the Config table

### Requirement: Provider configuration in database
LLM provider configurations (base URL, API key, model list) SHALL be stored as runtime config in the database.

#### Scenario: Provider config structure
- **WHEN** a provider is configured
- **THEN** the system SHALL store config entries with keys like `provider.{name}.baseUrl`, `provider.{name}.apiKey`, `provider.{name}.models` (JSON array)

#### Scenario: Add or modify provider via admin UI
- **WHEN** the admin updates a provider configuration via the Settings page
- **THEN** the changes SHALL take effect immediately without restart

### Requirement: Channel configuration in database
Channel configurations (tokens, secrets, webhook URLs) SHALL be stored in the ChannelConfig database table.

#### Scenario: Channel config structure
- **WHEN** a channel is configured
- **THEN** the system SHALL store a ChannelConfig record with channel_type, config_json (containing all channel-specific fields), and enabled flag
