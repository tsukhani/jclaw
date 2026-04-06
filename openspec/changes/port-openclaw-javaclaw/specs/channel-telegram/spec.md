## ADDED Requirements

### Requirement: Telegram webhook receiver
The system SHALL receive inbound Telegram messages via a webhook endpoint at `POST /api/webhooks/telegram/{secret}`.

#### Scenario: Valid message received
- **WHEN** Telegram sends an Update JSON payload to the webhook endpoint with the correct secret path
- **THEN** the system SHALL parse the message (chat_id, text, from user), route it to the appropriate agent, and respond with HTTP 200

#### Scenario: Invalid secret
- **WHEN** a request is received at the webhook with an incorrect secret path segment
- **THEN** the system SHALL respond with HTTP 403 and not process the message

#### Scenario: Secret token header verification
- **WHEN** a webhook request includes the `X-Telegram-Bot-Api-Secret-Token` header
- **THEN** the system SHALL verify it matches the configured secret token

### Requirement: Telegram message sending
The system SHALL send messages to Telegram chats via `POST https://api.telegram.org/bot{token}/sendMessage` using `java.net.http.HttpClient`.

#### Scenario: Send text message
- **WHEN** the agent produces a response for a Telegram conversation
- **THEN** the system SHALL POST to the Telegram API with the chat_id and text, and log the result

#### Scenario: Send failure with retry
- **WHEN** a Telegram send request fails with a network or 5xx error
- **THEN** the system SHALL retry once after 1 second, and on final failure log an ERROR event and persist the failed message

### Requirement: Telegram webhook registration
The system SHALL support registering the webhook URL with Telegram via `POST https://api.telegram.org/bot{token}/setWebhook`.

#### Scenario: Webhook setup on startup or via admin UI
- **WHEN** the admin triggers webhook registration (via admin UI or application startup config)
- **THEN** the system SHALL call `setWebhook` with the configured public URL and secret token

### Requirement: Telegram channel configuration
The system SHALL store Telegram channel config in the database (bot token, webhook secret, webhook URL) editable from the admin UI.

#### Scenario: Configure Telegram channel
- **WHEN** the admin saves Telegram config via the admin UI with bot token and webhook URL
- **THEN** the system SHALL persist the config and make it available to the webhook receiver and message sender
