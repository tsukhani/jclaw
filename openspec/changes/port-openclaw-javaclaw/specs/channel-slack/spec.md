## ADDED Requirements

### Requirement: Slack webhook receiver
The system SHALL receive inbound Slack events via a webhook endpoint at `POST /api/webhooks/slack`.

#### Scenario: URL verification challenge
- **WHEN** Slack sends a `url_verification` event with a `challenge` field
- **THEN** the system SHALL respond with the challenge value as the response body and HTTP 200

#### Scenario: Message event received
- **WHEN** Slack sends an `event_callback` with a `message` event (channel, user, text)
- **THEN** the system SHALL parse the message, route it to the appropriate agent, and respond with HTTP 200 within 3 seconds

#### Scenario: Bot message ignored
- **WHEN** Slack sends a message event where the sender is the bot itself (bot_id matches)
- **THEN** the system SHALL ignore the message to prevent echo loops

### Requirement: Slack request signature verification
The system SHALL verify the HMAC-SHA256 signature on every inbound Slack webhook request.

#### Scenario: Valid signature
- **WHEN** a Slack webhook request includes `X-Slack-Signature` and `X-Slack-Request-Timestamp` headers AND the computed HMAC of `v0:{timestamp}:{body}` using the signing secret matches
- **THEN** the system SHALL accept and process the request

#### Scenario: Invalid signature
- **WHEN** the computed HMAC does not match the `X-Slack-Signature` header
- **THEN** the system SHALL respond with HTTP 401 and log a WARN event

#### Scenario: Replay attack prevention
- **WHEN** the `X-Slack-Request-Timestamp` is more than 5 minutes old
- **THEN** the system SHALL reject the request with HTTP 401

### Requirement: Slack message sending
The system SHALL send messages to Slack channels via `POST https://slack.com/api/chat.postMessage` using `java.net.http.HttpClient`.

#### Scenario: Send text message
- **WHEN** the agent produces a response for a Slack conversation
- **THEN** the system SHALL POST to the Slack Web API with the channel ID, text, and Bearer token authorization

#### Scenario: Send failure with retry
- **WHEN** a Slack send request fails or returns `ok: false`
- **THEN** the system SHALL retry once after 1 second, and on final failure log an ERROR event

### Requirement: Slack channel configuration
The system SHALL store Slack channel config in the database (bot token, signing secret, app-level config) editable from the admin UI.

#### Scenario: Configure Slack channel
- **WHEN** the admin saves Slack config via the admin UI with bot token and signing secret
- **THEN** the system SHALL persist the config and make it available to the webhook receiver and message sender
