## ADDED Requirements

### Requirement: OpenAI-compatible chat completions
The system SHALL provide an `OpenAiCompatibleClient` class that communicates with any OpenAI-compatible API endpoint using JDK 25's `java.net.http.HttpClient` and Gson for JSON serialization.

#### Scenario: Synchronous chat completion
- **WHEN** a chat completion request is sent with a list of messages, model ID, and provider config (baseUrl, apiKey)
- **THEN** the system SHALL return a complete response containing the assistant's message content, finish reason, and token usage

#### Scenario: Tool/function calling in request
- **WHEN** a chat completion request includes tool definitions (name, description, JSON schema parameters)
- **THEN** the system SHALL serialize them in the OpenAI `tools` format and the response SHALL parse any `tool_calls` returned by the model

### Requirement: SSE streaming support
The system SHALL support streaming chat completions via Server-Sent Events (SSE), parsing `text/event-stream` responses from the provider.

#### Scenario: Stream tokens from provider
- **WHEN** a streaming chat completion request is made
- **THEN** the system SHALL emit individual `ChatCompletionChunk` objects as they arrive, each containing a delta of the assistant's response

#### Scenario: Stream termination
- **WHEN** the provider sends a `data: [DONE]` event or closes the stream
- **THEN** the system SHALL close the stream cleanly and signal completion to the caller

#### Scenario: Tool calls during streaming
- **WHEN** the provider streams a response that includes tool calls
- **THEN** the system SHALL accumulate tool call chunks across multiple SSE events and emit complete tool call objects when fully received

### Requirement: Provider configuration
The system SHALL support multiple provider configurations, each defined by a base URL, API key, and a list of available models with metadata (id, name, context window, max tokens).

#### Scenario: Ollama Cloud provider
- **WHEN** the provider is configured with `baseUrl=https://ollama.com/v1` and an Ollama Cloud API key
- **THEN** the system SHALL successfully send requests to the Ollama Cloud endpoint

#### Scenario: OpenRouter provider
- **WHEN** the provider is configured with `baseUrl=https://openrouter.ai/api/v1` and an OpenRouter API key
- **THEN** the system SHALL successfully send requests to the OpenRouter endpoint

### Requirement: Provider failover
The system SHALL support automatic failover between providers when the primary provider fails after exhausting retries.

#### Scenario: Primary provider fails, secondary succeeds
- **WHEN** a chat completion request to the primary provider fails after all retry attempts AND a secondary provider is configured
- **THEN** the system SHALL retry the full request against the secondary provider

#### Scenario: Both providers fail
- **WHEN** both primary and secondary providers fail after all retry attempts
- **THEN** the system SHALL return an error to the caller and log an ERROR event

### Requirement: Retry with exponential backoff
The system SHALL retry failed LLM requests with exponential backoff before triggering provider failover.

#### Scenario: Transient failure with recovery
- **WHEN** an LLM request fails with a retryable error (5xx, timeout, connection error)
- **THEN** the system SHALL retry up to 3 times with delays of 1s, 2s, 4s before failing over

#### Scenario: Rate limit (429)
- **WHEN** an LLM request returns HTTP 429 with a `Retry-After` header
- **THEN** the system SHALL wait for the duration specified in the header before retrying

#### Scenario: Non-retryable error
- **WHEN** an LLM request fails with a 4xx error (other than 429)
- **THEN** the system SHALL NOT retry and SHALL return the error immediately

### Requirement: Embedding support
The system SHALL support generating text embeddings via the OpenAI-compatible `POST /v1/embeddings` endpoint, using the same HTTP client infrastructure.

#### Scenario: Generate embedding for memory storage
- **WHEN** an embedding request is sent with input text and a model ID
- **THEN** the system SHALL return a float array of the configured dimensionality

#### Scenario: Embedding provider not configured
- **WHEN** an embedding is requested but no embedding provider/model is configured
- **THEN** the system SHALL throw a clear configuration error
