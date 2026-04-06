## ADDED Requirements

### Requirement: Development environment
The system SHALL support a development workflow with two separate processes.

#### Scenario: Start development
- **WHEN** the developer runs `play run` in the project root and `cd frontend && pnpm dev` in a separate terminal
- **THEN** the Play backend SHALL start on port 9000 and the Nuxt dev server SHALL start on port 3000 with API proxy forwarding `/api/**` to the backend

#### Scenario: H2 database in development
- **WHEN** the application runs in development mode
- **THEN** the system SHALL use H2 in-memory database with auto-DDL generation

### Requirement: Production build packaging
The `play dist` command SHALL produce a zip archive containing both the Play backend and the Nuxt frontend source.

#### Scenario: Distribution created
- **WHEN** `play dist` is run
- **THEN** the system SHALL produce a zip at `dist/jclaw.zip` containing the Play app (app/, conf/, lib/, modules/) and the frontend/ directory

### Requirement: Production deployment
The system SHALL run as two separate processes in production, fronted by an Nginx reverse proxy.

#### Scenario: Start production backend
- **WHEN** the operator runs `play start` with production configuration
- **THEN** the Play backend SHALL start on the configured port (default 9000) with PostgreSQL as the database

#### Scenario: Start production frontend
- **WHEN** the operator builds and starts Nuxt (`cd frontend && pnpm install && pnpm build && node .output/server/index.mjs`)
- **THEN** the Nuxt server SHALL start on port 3000

#### Scenario: Nginx routing
- **WHEN** Nginx is configured as the reverse proxy
- **THEN** requests to `/api/**` SHALL be proxied to the Play backend and all other requests SHALL be proxied to the Nuxt frontend

#### Scenario: SSL termination
- **WHEN** Nginx is configured with SSL certificates
- **THEN** all external traffic SHALL be served over HTTPS, which is required for Telegram, Slack, and WhatsApp webhooks

### Requirement: Production configuration
The system SHALL support production-specific configuration via Play's `%prod.` prefix in `application.conf`.

#### Scenario: Production database
- **WHEN** the application runs in production mode
- **THEN** the system SHALL use `%prod.db.url`, `%prod.db.user`, `%prod.db.pass` for PostgreSQL connection

#### Scenario: Production JPA mode
- **WHEN** the application runs in production mode
- **THEN** `%prod.jpa.ddl` SHALL be set to `update` (additive schema changes only, no destructive operations)

### Requirement: Webhook URL registration
Each messaging channel SHALL document the webhook URLs that must be registered with the respective platform.

#### Scenario: Telegram webhook URL
- **WHEN** Telegram is configured
- **THEN** the webhook URL SHALL be `https://{domain}/api/webhooks/telegram/{secret}`

#### Scenario: Slack webhook URL
- **WHEN** Slack is configured
- **THEN** the Events API URL SHALL be `https://{domain}/api/webhooks/slack`

#### Scenario: WhatsApp webhook URL
- **WHEN** WhatsApp is configured
- **THEN** the webhook URL SHALL be `https://{domain}/api/webhooks/whatsapp`
