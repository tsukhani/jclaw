## Context

JClaw is a greenfield Play Framework 1.x (Java) + Nuxt 3 (Vue/TypeScript) application that ports the core capabilities of OpenClaw (TypeScript agent orchestration) and JavaClaw (Spring Boot agent + job scheduling) into a single, dependency-minimal Java platform.

**Current state:** The repository contains a scaffolded Play 1.x app with one API endpoint (`GET /api/status`), empty models directory, and a minimal Nuxt 3 frontend with a hello-world page and an API proxy to the backend.

**Source systems:**
- **OpenClaw** (~/openclaw, adabot branch): TypeScript/Node.js. 25+ channels, WebSocket gateway, plugin system, Neo4j/LanceDB memory, JSONL session transcripts, 100+ LLM providers.
- **JavaClaw** (~/Programming/javaclaw): Spring Boot 4 + Spring AI. JobRunr scheduling, Telegram/Discord channels, web chat, file-based persistence, 4 LLM providers, modular plugin architecture.

**Constraints:**
- Play Framework 1.x conventions (static controller methods, JPA/Hibernate, `application.conf`, Play Jobs)
- JDK 25+ — aggressively leverage modern Java for performance and code quality
- Minimal third-party dependencies — prefer JDK built-ins and Play bundled libs
- Custom Play 1.11.5 fork (github.com/tsukhani/play1) allows framework-level modifications

**JDK 25 capabilities to exploit throughout the implementation:**
- **Virtual threads** (`Thread.ofVirtual()`): Use for all blocking I/O — LLM HTTP calls, channel webhook delivery, embedding API calls, database queries in async contexts. Eliminates thread pool exhaustion under concurrent load without platform thread overhead.
- **`java.net.http.HttpClient`**: Built-in async HTTP/2 client with native SSE support. Replaces any need for OkHttp or Apache HttpClient.
- **Records**: Use for all immutable value types — API request/response payloads, tool call parameters, routing results, config DTOs, event log entries.
- **Sealed interfaces**: Use for closed type hierarchies — `MemoryStore` backends, channel types, tool result types, task status transitions.
- **Pattern matching** (`switch` expressions with patterns, `instanceof` patterns): Use for type-safe dispatching in tool execution, channel message routing, and memory backend selection.
- **Text blocks** (`"""`): Use for multi-line SQL queries, system prompt templates, JSON templates.
- **`StructuredTaskScope`** (structured concurrency): Use for fan-out operations like recalling memories + loading skills + assembling system prompt concurrently before an LLM call.
- **`ScopedValue`** (scoped values): Use for request-scoped context (current agent, current channel, conversation ID) without ThreadLocal pollution across virtual threads.
- **`Stream.gather()`** and enhanced Stream API: Use for message processing pipelines, log filtering, search result merging.
- **`java.util.concurrent.Flow`**: Use for reactive streaming of SSE tokens from LLM provider to web chat frontend.

**Performance philosophy:** Virtual threads + structured concurrency mean we can write straightforward blocking code that scales like async code. No callback hell, no CompletableFuture chains, no reactive frameworks. Simple imperative Java that happens to handle thousands of concurrent conversations efficiently.

## Goals / Non-Goals

**Goals:**
- Fully functional multi-agent AI platform on Play 1.x + Nuxt 3
- Connect to Telegram, Slack, and WhatsApp via raw HTTP (no SDKs)
- Web chat interface with SSE streaming in Nuxt frontend
- Pluggable memory system (JPA default, Neo4j opt-in, pgvector opt-in)
- Admin UI for managing agents, channels, conversations, tasks, skills, config, and logs
- Database-backed task scheduling with CRON support
- LLM provider failover (Ollama Cloud ↔ OpenRouter)

**Non-Goals:**
- Socket Mode for Slack (webhook-only for v1)
- Playwright browser automation (deferred)
- Brave web search tool (deferred)
- MCP protocol support (deferred)
- Multi-user / role-based access control (single admin user for v1)
- Distributed / multi-JVM execution
- Mobile app or native desktop client
- Media generation (image, video, music)
- Sub-agent spawning
- Session compaction / auto-summarization (deferred — relies on context window being large enough)

## Decisions

### D1: OpenAI-Compatible HTTP Client (hand-rolled)

**Decision:** Build a single `OpenAiCompatibleClient` class using `java.net.http.HttpClient` and Play's bundled Gson for JSON serialization. Both Ollama Cloud and OpenRouter use the OpenAI completions API shape.

**Alternatives considered:**
- *OpenAI Java SDK (com.openai:openai-java)*: Pulls in OkHttp, Jackson, and Kotlin stdlib. We would use ~5% of its API surface (only `POST /v1/chat/completions`). The dependency weight and Jackson-vs-Gson classpath friction don't justify the convenience.
- *LangChain4j*: Heavy framework, Spring-oriented, overkill for two OpenAI-compatible endpoints.

**Rationale:** ~400 lines of focused code. Full control over streaming SSE parsing, tool call serialization, and retry logic. Zero classpath conflicts. Both providers are well-behaved OpenAI-compatible endpoints.

**Key implementation details:**
- SSE stream parsing for `text/event-stream` responses
- Tool/function calling via the standard `tools` parameter in chat completions
- Provider failover: on final failure from primary provider, retry the full request against secondary provider
- Embedding support (`POST /v1/embeddings`) for pgvector memory backend, same client class

### D2: Normalized JPA Schema

**Decision:** Fully normalized relational schema using Play's JPA/Hibernate. H2 for dev/test, PostgreSQL for production.

**Alternatives considered:**
- *File-based persistence (OpenClaw pattern)*: JSONL transcripts, YAML conversations. Natural for append-only data but not queryable, harder to paginate, and Play's JPA is already wired up.
- *Hybrid file+DB (JavaClaw pattern)*: Files for transcripts, DB for structured data. Two persistence mechanisms to maintain.

**Rationale:** Play 1.x's JPA integration handles transactions, connection pooling, and DDL generation automatically. A single persistence layer is simpler to operate. Messages as rows enable search, pagination, and analytics.

**Schema entities:**

```
Agent (id, name, model_provider, model_id, enabled, is_default, created_at, updated_at)
  │
  ├── AgentBinding (id, agent_id, channel_type, peer_id nullable, priority)
  │
  ├── Conversation (id, agent_id, channel_type, peer_id, created_at, updated_at)
  │     │
  │     └── Message (id, conversation_id, role, content, tool_calls, tool_results, created_at)
  │
  └── Task (id, agent_id, name, description, type, cron_expression, scheduled_at,
            status, retry_count, max_retries, last_error, next_run_at, created_at, updated_at)

ChannelConfig (id, channel_type, config_json, enabled, created_at, updated_at)

Config (id, key, value, updated_at)

EventLog (id, timestamp, level, category, agent_id, channel, message, details, created_at)

Memory → handled by MemoryStore interface (not a JPA entity when using Neo4j backend)
  JPA backend: Memory (id, agent_id, text, category, embedding, created_at, updated_at)
```

### D3: Pluggable Memory with Backend-Specific Config

**Decision:** `MemoryStore` interface with minimal contract (store, search, delete, list). Each backend has its own configuration section in `application.conf`.

**Alternatives considered:**
- *Single JPA-only memory*: Simpler but locks out graph-based memory and limits future extensibility.
- *Abstract factory with discovery*: Over-engineered for two backends.

**Rationale:** The interface is deliberately minimal. Backends add richness internally (Neo4j adds entity extraction, graph traversal; JPA+pgvector adds hybrid search). The calling code doesn't need to know which backend is active for basic operations.

**Configuration:**
```properties
# Select backend
memory.backend=jpa

# JPA backend options
memory.jpa.vector.enabled=false
memory.jpa.vector.provider=openai
memory.jpa.vector.model=text-embedding-3-small
memory.jpa.vector.dimensions=1536

# Neo4j backend options (when memory.backend=neo4j)
memory.neo4j.uri=bolt://localhost:7687
memory.neo4j.user=neo4j
memory.neo4j.password=secret
```

**Search behavior:**
- H2 (dev/test): `LIKE` text matching
- PostgreSQL (vector disabled): PostgreSQL full-text search (`to_tsvector`/`to_tsquery`)
- PostgreSQL (vector enabled): Hybrid — PG FTS results + pgvector cosine similarity, merged and ranked
- Neo4j: Fully delegated to Neo4j backend's own implementation

### D4: Raw HTTP for All Three Messaging Channels

**Decision:** Telegram Bot API, Slack Web API + Events API, and WhatsApp Cloud API all accessed via `java.net.http.HttpClient`. No Telegram SDK, no Slack SDK, no WhatsApp SDK.

**Alternatives considered:**
- *telegrambots 9.4.0 (Java)*: Brings its own embedded HTTP server (redundant in Play), 200+ POJO model classes we'd use ~15 of. Main value is type-safe models.
- *Slack SDK (bolt-java)*: Pulls in OkHttp, provides Socket Mode. We only need Events API webhooks. SDK's value is primarily typed models (331 request + 332 response classes).
- *No official Java WhatsApp SDK exists*: Raw HTTP is the standard approach in Java.

**Rationale:** All three APIs are simple REST/JSON. Sending a message is one HTTP POST. Receiving is a webhook POST that Play handles natively. Total raw HTTP implementation: ~500 lines for all three channels combined. The SDKs would each add significant dependency weight for marginal benefit.

**Per-channel implementation:**
- **Telegram**: Webhook inbound (`POST /api/webhooks/telegram/{secret}`). Outbound via `POST https://api.telegram.org/bot{token}/sendMessage`. Secret token header verification.
- **Slack**: Webhook inbound (`POST /api/webhooks/slack`). HMAC-SHA256 request signing verification (~15 lines). Outbound via `POST https://slack.com/api/chat.postMessage`. One-time URL verification challenge.
- **WhatsApp**: Webhook inbound (`POST /api/webhooks/whatsapp`). HMAC-SHA256 signature verification. Hub challenge-response for registration. Outbound via `POST https://graph.facebook.com/v21.0/{phone_id}/messages`.

### D5: SSE for Web Chat Streaming

**Decision:** Server-Sent Events (SSE) for streaming LLM responses to the Nuxt frontend. Browser sends messages via HTTP POST, receives streamed responses via SSE.

**Alternatives considered:**
- *WebSocket*: Bidirectional, but web chat is a self-contained request/response cycle. WebSocket adds connection management complexity (ping/pong, reconnect). External channel messages don't need to push to the web UI in real-time.

**Rationale:** SSE is HTTP-native, simpler than WebSocket, and `EventSource` in the browser handles reconnection automatically. The flow is: `POST /api/chat/send` → triggers LLM call → response streams back as SSE on `GET /api/chat/stream/{conversationId}`. Perfect fit for a chat-with-LLM interface.

### D6: Multi-Agent Routing (3-Tier)

**Decision:** Simplified routing with three priority tiers: exact peer match → channel-wide match → default agent. Bindings stored in the `AgentBinding` database table.

**Alternatives considered:**
- *OpenClaw's 8-tier system*: Peer, parent peer, wildcard peer, guild+roles, guild, team, account, channel. Most tiers are Discord/Teams-specific and unnecessary for Telegram/Slack/WhatsApp.
- *Config-file bindings*: Harder to edit from admin UI, requires restart.

**Rationale:** Three tiers cover 90% of real use cases. The bindings table is queryable in specificity order: first check for a binding matching (channel_type + peer_id), then (channel_type + peer_id IS NULL), then fall back to the default agent. Adding guild/role tiers later is just adding columns.

**Web chat is special:** Users explicitly select which agent to talk to via a dropdown in the Nuxt frontend. No routing needed — the agent_id is passed directly in the API call.

### D7: Task Scheduling via DB + Play Poller

**Decision:** Tasks stored in a database table. A Play `@Every("30s")` job polls for tasks where `next_run_at <= now AND status = PENDING`, executes them, and updates status.

**Alternatives considered:**
- *JobRunr*: Persistent, dashboard, retry — but requires custom integration layer for Play 1.x (DataSource extraction, JobActivator, classloader issues in DEV mode). 2-3 days of integration work.
- *Play Jobs only*: No persistence, no dynamic runtime scheduling of recurring tasks, no retry.

**Rationale:** ~150 lines of custom code gives us persistence, dynamic CRON scheduling, and retry — everything JobRunr provides for our use case, with zero integration friction. The Nuxt admin Tasks page serves as the dashboard.

**Task lifecycle:**
```
PENDING → (poller picks up) → RUNNING → COMPLETED
                                      → FAILED (retry_count < max_retries → PENDING with backoff)
                                      → FAILED (retry_count >= max_retries → permanently FAILED)
```

CRON tasks: on completion, compute `next_run_at` from cron expression and insert a new PENDING task.

### D8: LLM-Driven Skill Matching

**Decision:** All skill names and descriptions are injected into the system prompt as XML. The LLM reads descriptions and decides which skill (if any) to load. No embedding-based matching, no keyword search, no scoring algorithm.

**Alternatives considered:**
- *Embedding-based matching*: Compute similarity between user message and skill descriptions. Adds embedding infrastructure and latency for marginal improvement over LLM native understanding.
- *Keyword matching*: Brittle, misses semantic intent.

**Rationale:** This is exactly what both OpenClaw and JavaClaw do. The LLM is the matching algorithm. It works because skill descriptions are short (one paragraph each) and the LLM has excellent semantic understanding. Token budget guard: cap at 150 skills or 30,000 characters in the skills block.

### D9: Hybrid Configuration (application.conf + Database)

**Decision:** Infrastructure config in `application.conf` (read at boot, requires restart). Runtime config in `Config` database table (editable from admin UI, no restart needed).

**Alternatives considered:**
- *application.conf only*: Can't edit from admin UI without restart.
- *Database only*: Can't configure database connection from database.
- *Separate JSON file (OpenClaw pattern)*: Extra file to manage, doesn't integrate with Play's config system.

**Rationale:** Natural split — things that must exist before the app boots (DB URL, ports, memory backend, admin credentials) go in `application.conf`. Things the admin should be able to change at runtime (LLM providers, channel tokens, agent settings, bindings) go in the database.

### D10: 4-Layer Error Handling + EventLog

**Decision:** Errors handled at four layers — LLM calls (retry + provider failover), channel delivery (retry + persist), tool execution (catch + return string), system (EventLog table). All errors written to both the EventLog DB table and SLF4J.

**Alternatives considered:**
- *SLF4J only*: Not queryable from admin UI. Requires server access to read logs.
- *In-memory event stream (OpenClaw pattern)*: Lost on restart.

**Rationale:** The EventLog table makes the /logs admin page a simple database query with filters. Dual output to SLF4J preserves traditional log files for ops teams. Provider failover (Ollama Cloud ↔ OpenRouter) is cheap to implement and significantly improves availability.

### D11: Single Admin Auth via application.conf

**Decision:** Username and password stored in `application.conf`. Session-based authentication using Play's built-in cookie mechanism.

**Alternatives considered:**
- *Database-stored users*: Over-engineered for single admin.
- *API key auth*: Less natural for a web UI.
- *No auth*: Unsafe — admin UI can modify agent configs and view conversations.

**Rationale:** Simplest possible auth that provides protection. A login page in Nuxt, a `POST /api/auth/login` endpoint that validates credentials and sets a session cookie, and a `@Before` interceptor on all admin API routes. Improved in future versions (multi-user, roles, OAuth).

**Configuration:**
```properties
jclaw.admin.username=admin
jclaw.admin.password=changeme
```

## Risks / Trade-offs

**[Risk] SSE parsing edge cases across providers** → Test SSE streaming against both Ollama Cloud and OpenRouter specifically. Different providers may send heartbeats, empty lines, or `[DONE]` markers differently. Mitigation: build a robust SSE parser with provider-specific tests.

**[Risk] Play 1.x WebSocket limitations if SSE proves insufficient** → SSE is one-directional. If future features require server-push to the web UI (e.g., real-time notifications from external channels), we may need to add WebSocket support. Mitigation: SSE covers the current requirements; WebSocket can be added as a separate channel later.

**[Risk] H2 vs PostgreSQL behavioral differences** → H2 in dev mode doesn't support `to_tsvector`, `pgvector`, or PostgreSQL-specific syntax. Mitigation: the `JpaMemoryStore.search()` method checks the database type and uses appropriate query syntax. Integration tests run against both H2 and PostgreSQL.

**[Risk] Webhook reliability for external channels** → If the Play server is down, Telegram/Slack/WhatsApp webhook deliveries will fail. Telegram retries for a few hours. Slack retries with backoff. WhatsApp retries a configurable number of times. Mitigation: standard operational concern — run behind a process manager, monitor uptime.

**[Risk] Single-process architecture limits throughput** → One Play JVM handles all API requests, webhooks, LLM calls, and task polling. Mitigation: Virtual threads (JDK 25) fundamentally change the concurrency model — each LLM call, webhook delivery, and database query runs on a lightweight virtual thread that doesn't block platform threads. A single JVM can handle thousands of concurrent conversations without thread pool exhaustion. Structured concurrency (`StructuredTaskScope`) enables efficient fan-out for parallel operations (e.g., recall memories + load skills simultaneously). For v1, single-process with virtual threads is more than sufficient.

**[Risk] No session compaction** → Long conversations will grow the messages table and the context window sent to the LLM. Mitigation: deferred to post-MVP. For now, rely on large context windows (100k+ tokens on most models). Add compaction (auto-summarize older messages) in a future version.

**[Risk] Admin credentials in plain text in application.conf** → Not ideal for production. Mitigation: use environment variable substitution (`${ADMIN_PASSWORD}`) in application.conf. Improved in future versions with hashed passwords or external auth.

**[Trade-off] No SDKs means maintaining channel HTTP clients** → When Telegram/Slack/WhatsApp change their APIs, we update our raw HTTP calls instead of bumping an SDK version. Accepted trade-off for zero dependency weight and full control.

**[Trade-off] JPA memory table with optional pgvector vs always-on vector search** → Users who don't enable pgvector get only text search for memory recall. This is intentional — pgvector requires PostgreSQL setup and an embedding provider API key. The default should work with zero configuration.

## Migration Plan

Not applicable — this is a greenfield implementation on a scaffold. No existing data or functionality to migrate.

**Deployment prerequisites for production:**
1. PostgreSQL database created and accessible
2. Nginx configured for SSL termination and routing (`/api/**` → Play :9000, else → Nuxt :3000)
3. Webhook URLs registered with Telegram, Slack, and/or WhatsApp (requires HTTPS)
4. API keys configured for LLM providers (Ollama Cloud, OpenRouter)
5. `application.conf` configured with production database, admin credentials, memory backend

**Rollback:** Not needed for v1 (no prior version). For future releases, database migrations should be forward-compatible and Play's JPA DDL mode should be set to `update` (additive only) in production.

## Open Questions

1. **Embedding provider for pgvector**: Should the embedding API call go through one of the configured LLM providers (Ollama Cloud / OpenRouter), or should it be a separately configured endpoint? Current design assumes reusing the OpenAI-compatible client with a configurable embedding model.

2. **Conversation context window management**: Without session compaction, how many messages should be sent to the LLM per turn? A configurable `max_context_messages` (default: 50 most recent) would prevent runaway token usage while keeping conversations coherent.

3. **Task execution context**: When the task poller executes a task, it needs to call the agent. Should task execution create a new conversation, or append to an existing one? JavaClaw creates a new task-specific conversation.

4. **Skill file watching**: Should the backend watch workspace skill directories for changes (like OpenClaw does with chokidar), or reload on every request? Play 1.x DEV mode already reloads classes — skill files could follow the same pattern with a simple cache-with-TTL.
