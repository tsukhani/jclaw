## 1. Foundation — Database Schema & JPA Models

- [x] 1.1 Configure PostgreSQL JDBC driver in `conf/dependencies.yml` and add `%prod.db.*` settings to `application.conf`
- [x] 1.2 Create `Agent` JPA model (id, name, model_provider, model_id, enabled, is_default, created_at, updated_at)
- [x] 1.3 Create `AgentBinding` JPA model (id, agent_id FK, channel_type, peer_id nullable, priority)
- [x] 1.4 Create `Conversation` JPA model (id, agent_id FK, channel_type, peer_id, created_at, updated_at)
- [x] 1.5 Create `Message` JPA model (id, conversation_id FK, role, content, tool_calls TEXT, tool_results TEXT, created_at)
- [x] 1.6 Create `ChannelConfig` JPA model (id, channel_type, config_json TEXT, enabled, created_at, updated_at)
- [x] 1.7 Create `Task` JPA model (id, agent_id FK, name, description TEXT, type, cron_expression, scheduled_at, status, retry_count, max_retries, last_error TEXT, next_run_at, created_at, updated_at)
- [x] 1.8 Create `Config` JPA model (id, key, value TEXT, updated_at)
- [x] 1.9 Create `EventLog` JPA model (id, timestamp, level, category, agent_id, channel, message, details TEXT, created_at)
- [x] 1.10 Create `Memory` JPA model (id, agent_id, text TEXT, category, embedding nullable, created_at, updated_at) — used by JPA memory backend only
- [x] 1.11 Write unit tests verifying all models can be persisted and queried in H2

## 2. Event Logging

- [x] 2.1 Create `EventLogger` service class with `record(level, category, message, details)` that writes to both EventLog table and SLF4J
- [x] 2.2 Add convenience methods: `info()`, `warn()`, `error()` with category, agent_id, and channel overloads
- [x] 2.3 Create `EventLogCleanupJob` extending Play `Job` with `@Every("24h")` — deletes events older than `jclaw.logs.retention.days` (default 30)
- [x] 2.4 Create `GET /api/logs` endpoint with query params (category, level, agent_id, channel, since, until, search, limit, offset)
- [x] 2.5 Write tests for EventLogger and cleanup job

## 3. Configuration System

- [x] 3.1 Add all `jclaw.*` properties to `application.conf` with commented defaults (admin credentials, memory backend, workspace path, poller interval, log retention)
- [x] 3.2 Create `ConfigService` that reads from Config table with in-memory cache (60s TTL)
- [x] 3.3 Create `GET /api/config` endpoint — returns all config entries with sensitive values masked
- [x] 3.4 Create `GET /api/config/{key}` endpoint
- [x] 3.5 Create `POST /api/config` endpoint — upserts key-value pair
- [x] 3.6 Create `DELETE /api/config/{key}` endpoint
- [x] 3.7 Write tests for ConfigService and API endpoints

## 4. Authentication

- [x] 4.1 Create `POST /api/auth/login` endpoint — validates username/password against `application.conf`, sets Play session cookie, logs auth event
- [x] 4.2 Create `POST /api/auth/logout` endpoint — clears session
- [x] 4.3 Create `AuthInterceptor` using Play's `@Before` annotation — checks session on all `/api/*` endpoints except `/api/auth/login`, `/api/status`, and `/api/webhooks/*`
- [x] 4.4 Write tests for login, logout, and interceptor (authenticated vs unauthenticated requests)

## 5. LLM Client

- [x] 5.1 Create `OpenAiCompatibleClient` class using `java.net.http.HttpClient` — synchronous `chat(messages, tools, config)` method returning a parsed response record
- [x] 5.2 Implement SSE stream parsing for `text/event-stream` responses — `chatStream()` method returning a `Flow.Publisher<ChatCompletionChunk>` or equivalent stream
- [x] 5.3 Implement tool/function call serialization in request and parsing in response (including streaming accumulation of chunked tool calls)
- [x] 5.4 Implement retry with exponential backoff (3 attempts, 1s/2s/4s) and Retry-After header handling for 429s
- [x] 5.5 Implement provider failover — on final failure from primary provider, retry full request against secondary
- [x] 5.6 Implement `embeddings(input, model)` method for `POST /v1/embeddings` endpoint
- [x] 5.7 Create `ProviderConfig` record (name, baseUrl, apiKey, models list) and `ProviderRegistry` that loads configs from Config DB table
- [x] 5.8 Write tests: mock HTTP server for sync completion, streaming, tool calls, retry, failover, embeddings

## 6. Memory System

- [x] 6.1 Define `MemoryStore` sealed interface with `store()`, `search()`, `delete()`, `list()` methods
- [x] 6.2 Implement `JpaMemoryStore` — text search via LIKE for H2, PG full-text search for PostgreSQL
- [x] 6.3 Implement pgvector hybrid search in `JpaMemoryStore` — when `memory.jpa.vector.enabled=true`, generate embedding on store and combine PG FTS + cosine similarity on search
- [x] 6.4 Implement `Neo4jMemoryStore` using `neo4j-java-driver` — store as nodes, search delegated to Neo4j, fulfills base interface contract
- [x] 6.5 Add Neo4j Java driver to `conf/dependencies.yml` as optional dependency
- [x] 6.6 Create `MemoryStoreFactory` that reads `memory.backend` from `application.conf` and instantiates the correct backend
- [x] 6.7 Write tests for JpaMemoryStore (H2 text search), and Neo4jMemoryStore (if Neo4j available, else skip)

## 7. Agent System

- [x] 7.1 Create `AgentService` — CRUD operations for Agent entity, workspace directory creation/management
- [x] 7.2 Create workspace directory structure: `workspace/{agentId}/` with AGENT.md, IDENTITY.md, USER.md templates on agent creation
- [x] 7.3 Create `SkillLoader` — scans `workspace/{agentId}/skills/*/SKILL.md`, parses YAML frontmatter, returns list of (name, description, location) records
- [x] 7.4 Create `SystemPromptAssembler` — reads workspace files, formats skills as XML, recalls memories from MemoryStore, adds environment info and tool definitions, returns assembled system prompt
- [x] 7.5 Create `ConversationService` — find or create conversation by (agent_id, channel_type, peer_id), append messages, load recent N messages for context
- [x] 7.6 Create `AgentRunner` — the core pipeline: receive message → route to agent → load conversation → assemble prompt → call LLM → handle tool calls (loop) → persist response → return. Use virtual threads and StructuredTaskScope for parallel prompt assembly
- [x] 7.7 Create CRUD API endpoints: `GET/POST/PUT/DELETE /api/agents`, `GET /api/agents/{id}`
- [x] 7.8 Write tests for AgentService, SkillLoader, SystemPromptAssembler, ConversationService, and AgentRunner (with mocked LLM client)

## 8. Agent Routing

- [x] 8.1 Create `AgentRouter` — implements 3-tier resolution: query bindings for (channel_type, peer_id) exact match → (channel_type, null) channel match → default agent
- [x] 8.2 Create CRUD API endpoints: `GET/POST/PUT/DELETE /api/bindings`
- [x] 8.3 Write tests for all three routing tiers and edge cases (no bindings, no default agent)

## 9. Tool System

- [x] 9.1 Define `Tool` interface with `name()`, `description()`, `parameters()` (JSON schema), `execute(args)` returning String
- [x] 9.2 Create `ToolExecutor` — looks up tool by name, deserializes args, executes in try-catch, returns result or error string. Never throws.
- [x] 9.3 Implement `TaskTool` — createTask, scheduleTask, scheduleRecurringTask, deleteRecurringTask, listRecurringTasks. Each method inserts/queries the Task table
- [x] 9.4 Implement `CheckListTool` — validates exactly one in_progress item, returns success or validation error string
- [x] 9.5 Implement `FileSystemTools` — readFile, writeFile, listFiles scoped to agent workspace. Path traversal prevention via canonical path check
- [x] 9.6 Implement `WebFetchTool` — HTTP GET via HttpClient, 30s timeout, truncate response to 50k chars, handle non-text content types
- [x] 9.7 Implement `SkillsTool` — listSkills and readSkill delegating to SkillLoader and FileSystemTools
- [x] 9.8 Integrate tool definitions into LLM request serialization and tool call response handling in AgentRunner
- [x] 9.9 Write tests for each tool and for ToolExecutor error handling

## 10. Task Scheduling

- [x] 10.1 Create `TaskPollerJob` extending Play `Job` with `@Every("30s")` — queries PENDING tasks with next_run_at <= now, executes each in a virtual thread via AgentRunner
- [x] 10.2 Implement retry with exponential backoff — on failure: increment retry_count, compute next_run_at = now + 30s * 2^retry_count, set PENDING. On max retries exceeded: set FAILED
- [x] 10.3 Implement CRON task re-scheduling — on completion: parse cron_expression, compute next execution time, insert new PENDING task
- [x] 10.4 Create task management API: `GET /api/tasks` (with filters), `POST /api/tasks/{id}/cancel`, `POST /api/tasks/{id}/retry`
- [x] 10.5 Write tests for poller, retry logic, CRON re-scheduling, and API endpoints

## 11. Channel — Telegram

- [x] 11.1 Create `TelegramChannel` service — `sendMessage(chatId, text)` via HTTP POST to Telegram Bot API, with retry on failure
- [x] 11.2 Create `POST /api/webhooks/telegram/{secret}` controller action — parse Update JSON, extract message, verify secret, route to AgentRunner, respond 200
- [x] 11.3 Create webhook registration method — calls `setWebhook` on Telegram API with configured URL and secret token
- [x] 11.4 Create ChannelConfig CRUD: `GET/POST/PUT /api/channels/telegram`
- [x] 11.5 Write tests: mock Telegram API, test webhook parsing, secret verification, send with retry

## 12. Channel — Slack

- [x] 12.1 Create `SlackChannel` service — `sendMessage(channelId, text)` via HTTP POST to `chat.postMessage` with Bearer token auth, retry on failure
- [x] 12.2 Create `POST /api/webhooks/slack` controller action — handle url_verification challenge, verify HMAC-SHA256 signature and timestamp, parse event_callback message events, ignore bot messages, route to AgentRunner, respond 200
- [x] 12.3 Implement HMAC-SHA256 request signature verification (compute `v0:{timestamp}:{body}` hash, compare to X-Slack-Signature header, reject if timestamp > 5 min old)
- [x] 12.4 Create ChannelConfig CRUD: `GET/POST/PUT /api/channels/slack`
- [x] 12.5 Write tests: signature verification (valid, invalid, replay), url_verification, message routing, bot echo prevention

## 13. Channel — WhatsApp

- [x] 13.1 Create `WhatsAppChannel` service — `sendMessage(phoneNumberId, to, text)` via HTTP POST to Meta Graph API, `markAsRead(phoneNumberId, messageId)`, retry on failure
- [x] 13.2 Create `GET /api/webhooks/whatsapp` controller action — handle hub.mode=subscribe verification challenge, validate verify_token
- [x] 13.3 Create `POST /api/webhooks/whatsapp` controller action — verify X-Hub-Signature-256 HMAC, parse message from entry[].changes[].value.messages[], route to AgentRunner, respond 200
- [x] 13.4 Create ChannelConfig CRUD: `GET/POST/PUT /api/channels/whatsapp`
- [x] 13.5 Write tests: challenge verification, HMAC verification, message parsing, send with retry

## 14. Channel — Web Chat (Backend)

- [x] 14.1 Create `POST /api/chat/send` endpoint — accepts {agent_id, conversation_id (optional), message}, creates/continues conversation, invokes AgentRunner
- [x] 14.2 Create `GET /api/chat/stream/{conversationId}` SSE endpoint — streams LLM response tokens as SSE events, handles tool call events, sends completion/error events
- [x] 14.3 Create `GET /api/conversations` endpoint — list conversations with filters (channel, agent_id), pagination
- [x] 14.4 Create `GET /api/conversations/{id}/messages` endpoint — return messages in chronological order
- [x] 14.5 Write tests: chat send, SSE streaming (mock LLM), conversation listing and message retrieval

## 15. Frontend — Layout & Auth

- [x] 15.1 Create sidebar navigation layout component with links to all 9 pages, grouped logically (Dashboard, Chat | Channels, Conversations | Tasks, Agents, Skills | Settings, Logs), responsive with collapsible sidebar
- [x] 15.2 Create login page at `/login` with username/password form
- [x] 15.3 Create `useAuth` composable — login/logout API calls, session state management, redirect to login on 401
- [x] 15.4 Create auth middleware — redirect unauthenticated users to `/login` on all pages except login

## 16. Frontend — Dashboard

- [x] 16.1 Create dashboard page at `/` — fetch and display agent count (enabled/disabled), channel health, active task count, recent 10 events from `/api/logs`

## 17. Frontend — Chat

- [x] 17.1 Create chat page at `/chat` with agent/model selector dropdowns populated from `/api/agents` and provider configs
- [x] 17.2 Create message input component with send button
- [x] 17.3 Implement SSE streaming display — connect to `/api/chat/stream/{id}` via EventSource or fetch ReadableStream, render tokens incrementally
- [x] 17.4 Create conversation sidebar — list previous web chat conversations, allow selecting to continue
- [x] 17.5 Handle tool call events in stream — display tool execution indicator, show result, continue rendering

## 18. Frontend — Channels

- [x] 18.1 Create channels page at `/channels` — show Telegram, Slack, WhatsApp cards with enabled/disabled status
- [x] 18.2 Create per-channel config forms: Telegram (bot token, webhook URL, secret), Slack (bot token, signing secret), WhatsApp (phone number ID, access token, app secret, verify token)
- [x] 18.3 Implement save/update via channel config API endpoints

## 19. Frontend — Conversations

- [x] 19.1 Create conversations page at `/conversations` — paginated table with channel type, agent name, peer, message count, last activity
- [x] 19.2 Create conversation detail view — display messages with role indicators (user/assistant), timestamps, tool call content

## 20. Frontend — Tasks

- [x] 20.1 Create tasks page at `/tasks` — filterable table (status, type, agent) with columns for name, type, status, next run, retry count
- [x] 20.2 Implement cancel and retry actions calling task management API endpoints

## 21. Frontend — Agents

- [x] 21.1 Create agents page at `/agents` — list agents with name, model, enabled status, default flag
- [x] 21.2 Create agent edit form — name, model provider/model selector, enabled toggle, is_default toggle
- [x] 21.3 Create agent workspace editor — tabs for AGENT.md, IDENTITY.md, USER.md with editable text areas and save (calls FileSystemTools API or dedicated endpoint)

## 22. Frontend — Skills

- [x] 22.1 Create skills page at `/skills` — agent selector dropdown, list of skills for selected agent with name and description
- [x] 22.2 Create skill detail/edit view — display full SKILL.md in editable text area with save

## 23. Frontend — Settings

- [x] 23.1 Create settings page at `/settings` — load all config entries from `/api/config`, display in editable key-value form with sensitive value masking
- [x] 23.2 Create provider config section — forms for Ollama Cloud and OpenRouter (base URL, API key, model list)
- [x] 23.3 Implement save/delete for config entries

## 24. Frontend — Logs

- [x] 24.1 Create logs page at `/logs` — filter bar (category dropdown, level dropdown, time range, search input)
- [x] 24.2 Display EventLog entries in a table/list with timestamp, level badge, category, message, expandable details
- [x] 24.3 Implement 5-second auto-refresh polling

## 25. Integration & Deployment

- [x] 25.1 Add `workspace/` directory to project with a default agent workspace (AGENT.md with sensible defaults, example SKILL.md)
- [x] 25.2 Configure `conf/routes` with all new API routes (auth, agents, bindings, channels, chat, config, conversations, logs, tasks, webhooks)
- [x] 25.3 Add `%prod.*` configuration block to `application.conf` for PostgreSQL, production mode, and JPA DDL=update
- [x] 25.4 Write integration test: full pipeline from inbound webhook → routing → agent → LLM (mocked) → response → outbound channel (mocked)
- [x] 25.5 Write integration test: web chat send → SSE stream → tool call → tool result → continued stream
- [x] 25.6 Verify `play dist` produces a complete zip with frontend included
- [x] 25.7 Document Nginx configuration example for production (SSL, /api proxy to Play, default proxy to Nuxt)
- [x] 25.8 Create example `application.conf` for production deployment with all required settings documented
