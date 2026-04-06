## ADDED Requirements

### Requirement: WhatsApp webhook receiver
The system SHALL receive inbound WhatsApp messages via a webhook endpoint at `POST /api/webhooks/whatsapp`.

#### Scenario: Message received
- **WHEN** Meta sends a webhook payload with `object=whatsapp_business_account` containing a message in `entry[].changes[].value.messages[]`
- **THEN** the system SHALL parse the message (from, text, type), route it to the appropriate agent, and respond with HTTP 200

#### Scenario: Status update received
- **WHEN** Meta sends a webhook payload containing message status updates (sent, delivered, read) instead of messages
- **THEN** the system SHALL acknowledge with HTTP 200 and optionally log the status

### Requirement: WhatsApp webhook verification
The system SHALL handle Meta's one-time URL verification challenge via `GET /api/webhooks/whatsapp`.

#### Scenario: Verification challenge
- **WHEN** Meta sends a GET request with `hub.mode=subscribe`, `hub.verify_token`, and `hub.challenge`
- **THEN** the system SHALL verify the token matches the configured verify token and respond with the `hub.challenge` value as plain text

#### Scenario: Invalid verify token
- **WHEN** the `hub.verify_token` does not match the configured token
- **THEN** the system SHALL respond with HTTP 403

### Requirement: WhatsApp signature verification
The system SHALL verify the HMAC-SHA256 signature on every inbound WhatsApp webhook POST request.

#### Scenario: Valid signature
- **WHEN** a webhook POST includes the `X-Hub-Signature-256` header AND the computed HMAC of the raw body using the app secret matches
- **THEN** the system SHALL accept and process the request

#### Scenario: Invalid signature
- **WHEN** the computed HMAC does not match the `X-Hub-Signature-256` header
- **THEN** the system SHALL respond with HTTP 401 and log a WARN event

### Requirement: WhatsApp message sending
The system SHALL send messages via `POST https://graph.facebook.com/v21.0/{phone_number_id}/messages` using `java.net.http.HttpClient`.

#### Scenario: Send text message
- **WHEN** the agent produces a response for a WhatsApp conversation
- **THEN** the system SHALL POST a JSON payload with `messaging_product=whatsapp`, recipient `to` number, `type=text`, and the message body

#### Scenario: Mark message as read
- **WHEN** the system receives and processes an inbound message
- **THEN** the system SHALL send a read receipt by POSTing `status=read` with the message ID

#### Scenario: Send failure with retry
- **WHEN** a WhatsApp send request fails or returns an error response
- **THEN** the system SHALL retry once after 1 second, and on final failure log an ERROR event

### Requirement: WhatsApp channel configuration
The system SHALL store WhatsApp channel config in the database (phone number ID, access token, app secret, verify token) editable from the admin UI.

#### Scenario: Configure WhatsApp channel
- **WHEN** the admin saves WhatsApp config via the admin UI
- **THEN** the system SHALL persist the config and make it available to the webhook receiver and message sender
