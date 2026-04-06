## ADDED Requirements

### Requirement: Admin login
The system SHALL authenticate the admin user via credentials stored in `application.conf` (`jclaw.admin.username`, `jclaw.admin.password`).

#### Scenario: Successful login
- **WHEN** a `POST /api/auth/login` request is received with matching username and password
- **THEN** the system SHALL create a session (via Play's session cookie), log an INFO auth event, and return success

#### Scenario: Failed login
- **WHEN** a `POST /api/auth/login` request is received with incorrect credentials
- **THEN** the system SHALL return HTTP 401, log a WARN auth event, and NOT create a session

#### Scenario: Login page in frontend
- **WHEN** an unauthenticated user navigates to any admin page
- **THEN** the Nuxt frontend SHALL redirect to a login page

### Requirement: Session-based authentication
The system SHALL use Play's built-in session cookie mechanism to maintain authenticated state.

#### Scenario: Authenticated request
- **WHEN** an API request includes a valid session cookie
- **THEN** the system SHALL allow the request to proceed

#### Scenario: Unauthenticated API request
- **WHEN** an API request to any `/api/` endpoint (except `/api/auth/login`, `/api/status`, and webhook endpoints) does not include a valid session cookie
- **THEN** the system SHALL return HTTP 401

#### Scenario: Webhook endpoints exempt
- **WHEN** Telegram, Slack, or WhatsApp sends a webhook POST to `/api/webhooks/*`
- **THEN** the system SHALL NOT require session authentication (webhooks are verified by their own signature mechanisms)

### Requirement: Logout
The system SHALL support admin logout.

#### Scenario: Logout
- **WHEN** a `POST /api/auth/logout` request is received
- **THEN** the system SHALL invalidate the session and return success

### Requirement: Admin credentials configuration
The system SHALL read admin credentials from `application.conf`.

#### Scenario: Credentials from config
- **WHEN** the application starts
- **THEN** the system SHALL read `jclaw.admin.username` and `jclaw.admin.password` from `application.conf`

#### Scenario: Environment variable substitution
- **WHEN** credentials use environment variable syntax (e.g., `${ADMIN_PASSWORD}`)
- **THEN** Play's config system SHALL resolve them from environment variables
