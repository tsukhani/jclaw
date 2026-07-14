# Backend Architecture — Play 1.x / Java 25

## Executive summary

The backend is a single Play 1.x Java process serving:
1. A JSON API for the Nuxt SPA (`/api/*`).
2. Webhook endpoints for Slack/Telegram/WhatsApp.
3. A catch-all route that serves the pre-built SPA from `public/spa/`.
4. Background jobs for task scheduling, browser reaping, event-log cleanup, MCP startup, health probes, and graceful shutdown.

The architectural style is **service-oriented with a static-method convention** dictated by Play 1.x — controllers, services, and jobs are static methods on classes (no DI container, no Spring). Cross-cutting concerns (auth, JSON body parsing, transactions) are implemented as small utilities (`AuthCheck @Before`, JSON body reading, `services.Tx`) rather than AOP.

## Technology stack

| Category | Choice | Version | Rationale |
|---|---|---|---|
| Framework | Play Framework | 1.13.x (custom fork `tsukhani/play1`, pinned `.play-version` = `1.13.45`) | Established convention in the org; fast hot-reload; no Spring weight. Fork adds JDK 25, Jakarta Persistence, HTTP/2+HTTP/3. |
| Language | Java | 25 (`java.source=25`) | Virtual threads used across task execution and LLM streaming. |
| ORM | JPA via Hibernate | bundled | `play.db.jpa.Model` superclass; `jpa.ddl=update` in both dev and prod; Caffeine L2 + query cache (JCLAW-205). |
| DB (dev) | H2 file | `jdbc:h2:file:./data/jclaw;MODE=MYSQL;AUTO_SERVER=TRUE` | Persists across restarts; MySQL mode for fewer surprises when switching to Postgres. |
| DB (prod) | PostgreSQL (template) | — | Production migration path. Template commented in `application.conf`. |
| Pool | HikariCP (Play-bundled) | dev 5–20, prod 5–64 | Prod pool raised to 64 (chat streams hold a JPA connection for the SSE wait). |
| Outbound HTTP | **OkHttp 5** | `okhttp-jvm` 5.4.0 + `okhttp-sse` | Single outbound stack via `utils.HttpFactories`; virtual-thread dispatcher; pluggable DNS for SSRF. No `java.net.http.HttpClient` in `app/`. |
| LLM | OpenAI/Ollama/OpenRouter/TogetherAI | — | Sealed `LlmProvider` hierarchy; OpenAI-compatible wire format. |
| Full-text search | Apache Lucene | 10.5.0 | `services.search.LuceneIndexer` owns per-scope `FSDirectory` under `data/jclaw-lucene/`. |
| Scheduling | db-scheduler | 16.x | Persistent task scheduling (`scheduled_tasks` table) with atomic row-claim, retries, heartbeat recovery. |
| Browser automation | Playwright for Java | 1.61.0 | Chromium installed at `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers` in Docker. |
| Document parsing | Apache Tika | 3.3.1 | Tika parsers package with several excludes (lucene, cxf, mail) to slim the deploy; Tesseract OCR subprocess. |
| Markdown | flexmark | 0.64.8 | Plus extensions: tables, strikethrough, tasklist, autolink, typographic. |
| PDF | flying-saucer | 9.4 | PDF output for exports. |
| Tokenization | JTokkit | 1.1.0 | `llm.TokenUsageEstimator`, Caffeine-memoized prompt measurement. |
| Histograms | HdrHistogram | 2.2 | In-memory latency metrics (`/api/metrics/latency`). |
| JSON | Gson | bundled | Single `GsonHolder.INSTANCE` used everywhere for stable serialization. |
| Testing | JUnit 6 (Jupiter 6.x) | bundled by the fork (`framework/lib`) | Play `UnitTest`/`FunctionalTest`; runs headless via `play autotest`. |

## Architecture pattern

**Layered request-pipeline** with explicit integration points. The natural flow for a chat request:

```
HTTP request ─► AuthCheck @Before ─► ApiChatController.send|streamChat
                                         │
                                         ▼
                        ConversationService.findOrCreate (Tx)
                                         │
                                         ▼
                               AgentRunner.run(streaming)
                                 │              │
                                 ▼              ▼
                      LlmProvider.chat /      ToolCallLoopRunner
                      streamChat (OkHttp 5,     (per tool call)
                       retry, SSE parse)
                                 │              │
                                 └──────┬───────┘
                                        ▼
                            Tool-loop until stop (bounded)
                                        │
                                        ▼
                        Persist Message rows, return response
```

For channel webhooks, the prelude changes: `WebhookXController.webhook` → verify signature → `AgentRouter.resolve(channelType, peerId)` → `AgentRunner.run` → matching `channels.Channel` adapter for outbound push.

## Key subsystems

### Agent pipeline (`app/agents/`)

- **`AgentRouter`** — 3-tier routing: exact peer match (channel + peer) → channel-wide binding → main-agent fallback.
- **`AgentRunner`** — Prompt assembly + LLM call + tool loop. Config-driven round cap (`chat.maxToolRounds`). Streaming mode surfaces callbacks (`onInit`, `onToken`, `onReasoning`, `onStatus`, `onComplete`, `onError`). Client-disconnect cancellation via `CancellationManager` checked between rounds and inside providers.
- **`SystemPromptAssembler`** — Builds the composite system prompt (agent config + tools + skills + memory + workspace Standing Orders).
- **`ToolCatalog` / `ToolRegistry`** — Discovers `tools.*` implementations + MCP tools and exposes their declarative schema to the LLM; `ParallelToolExecutor` runs independent calls concurrently.
- **`SkillLoader` / `SkillVersionManager`** — File-system skill loading with version bookkeeping.
- **Context management** — `ContextWindowManager`, `CompactionGate`, and `SessionCompactor` keep prompts within the model's window; `CompressionPipeline` compresses oversized tool output.
- **Subagents** — `SubagentSpawnTool`/`SubagentYieldTool` + `SubagentRegistry` spawn child agent runs (recorded as `SubagentRun` rows).

### LLM layer (`app/llm/`)

- `sealed abstract class LlmProvider permits OpenAiProvider, OllamaProvider, OpenRouterProvider, TogetherAiProvider` — shared retry policy (max 3, backoff 1s/2s/4s), OpenAI-compatible JSON, SSE parsing.
- `OkHttpLlmHttpDriver` — the single HTTP driver: single-shot via `Call.execute()`, streaming via `okhttp-sse` `EventSource`. Clients come from `HttpFactories.llmStreaming()` / `llmSingleShot()`.
- `ProviderRegistry` — loads provider configs from the `Config` table, caches with a 60s refresh, resolves by name (substring match), excludes image-only providers.
- `LlmTypes` — `ChatMessage`, `ToolDef`, `ChatRequest`/`Response`, reasoning/thinking metadata, `Usage`.
- `TokenUsageEstimator` — JTokkit-backed counting with a Caffeine memo cache (was the #1 CPU hotspot before memoization).

### Tool system (`app/tools/`)

~22 built-in tool classes: shell exec, filesystem (Read/Write/List/Delete), web fetch (SSRF-guarded) + web search, Playwright browser, documents (Tika), image/video generation, conversation history/list/send, tasks, subagent spawn/yield, message/reminder dispatch, checklist, datetime, the internal `jclaw_api` tool, plus a load-test sleep tool. Per-agent enablement is controlled by `AgentToolConfig`. `ShellExecTool` consults `AgentSkillAllowedTool` at call time — not the skill's `SKILL.md` file — to prevent allowlist expansion via filesystem-write.

**`ShellExecTool` security posture (JCLAW-146):** the allowlist validates only the first token of the command string; the rest is handed to `/bin/sh -c`, which means shell composition (`;`, `&&`, `||`, `|`, `$(...)`, redirects) is fully available. This is intentional — the agent runs with the same OS privileges as the Play process, so per-token metacharacter-level gating would only move the goalposts while breaking legitimate composition (`cd build && make`, `git log | head`). Sandboxing lives at the `resolveWorkdir` containment check and the env-variable filter, not at the shell-syntax layer. Operators who need hard isolation wrap the Play process itself (firejail, Docker).

### MCP client (`app/mcp/`)

`McpClient` is a JSON-RPC state machine (DISCONNECTED → INITIALIZING → READY) over a pluggable `McpTransport` (`McpStdioTransport` for child processes, `McpStreamableHttpTransport` for SSE). Discovered tools are bridged into `agents.ToolRegistry` via `McpToolAdapter`. Servers are configured as `McpServer` rows and reconciled by `McpConnectionManager`. Protocol version `2025-06-18`.

### Memory (`app/memory/`)

`MemoryStore` interface with a single implementation, `JpaMemoryStore` (the `memory` table) — the former pluggable `Neo4jMemoryStore` was dropped (`MemoryStoreFactory.create()`), so vector similarity and graph/ontology now live in Postgres. Memory rows are agent-scoped. Recall is hybrid — keyword plus vector similarity, blended by reciprocal-rank fusion (`ReciprocalRankFusion`) — and the vector backend is dialect-driven: `pgvector` on Postgres (provisioned by `PgVectorProvisioner`), an embedded Lucene HNSW index on H2. Supporting pieces: `MemoryAutoCapture`, `MemoryReranker`, `MemoryDecay`, `MemoryAttentionGate`, `MemoryCategory`, `MemorySafety`.

### Background jobs (`app/jobs/`)

Play `@Every`/`@OnApplicationStart` jobs (task *execution* is owned by db-scheduler, not a custom poller):
- **Scheduling bootstrap** — `DbSchedulerBootstrapJob` / `DbSchedulerSchemaInitJob` wire db-scheduler; `LostTaskScanJob` / `OrphanReArmJob` recover orphaned runs.
- **Search** — `FullTextSearchInitJob` opens the Lucene indices at boot; `ShutdownJob` commits + closes them on stop.
- **Cleanup** — `TaskCleanupJob`, `EventLogCleanupJob` (+`EventLogFlushJob`), `LatencyMetric*Job`, `ConversationQueueEvictionJob`, `BrowserCleanupJob`, `SubagentOrphanRecoveryJob`.
- **Startup/probes** — `DefaultConfigJob` (seeds `Config` + main agent), `ToolRegistrationJob`, `McpStartupJob`, `TokenizerCalibrationJob`, plus reachability probes (`OllamaLocalProbeJob`, `LmStudioProbeJob`, `TesseractProbeJob`) and price refresh jobs.

### Channel adapters (`app/channels/`)

`Channel` interface + `ChannelRegistry` dispatch, with adapters for **Web**, **Slack** (HTTP webhook + Socket Mode), **Telegram** (long-polling + webhook), and **WhatsApp** (Cloud API *and* WhatsApp-Web via the Cobalt library). The `WEB` channel persists replies to `message` and streams them to the frontend over SSE rather than pushing outbound. Per-channel access policies, approval callbacks, inbound parsers, markdown formatters, and streaming sinks live alongside each adapter.

### SSE notification bus (`services.NotificationBus` + `ApiEventsController`)

Single server-push pipe multiplexed over `/api/events`. Controllers publish typed events (e.g. `skill.promoted`, streaming tokens); the frontend's `useEventBus` singleton dispatches to subscribers.

### Per-conversation queue (`services.ConversationQueue`)

Serializes message processing per conversation to prevent state corruption under concurrent inbound messages, with eviction of stale entries (`ConversationQueueEvictionJob`). Modes: FIFO `queue` (default), `collect` (batch pending into the next prompt), and `interrupt` (cancel in-flight generation, queue the new message for drain).

## Cross-cutting concerns

- **Auth** — Cookie-session via `AuthCheck @Before` is the primary gate; the in-process `jclaw_api` tool authenticates with a Bearer `ApiToken`. Webhooks exempt themselves by path prefix. Missing/invalid session returns **401**; genuine authorization failures return **403**.
- **Transactions** — `services.Tx.run(...)` wraps `JPA.withTransaction` and no-ops if already inside a transaction. Always re-fetch entities after a nested `Tx.run` to avoid detached instances.
- **Logging** — `log4j2.xml` (dev) / `log4j2-prod.xml` (prod) / `log4j2-test.xml` (test). Structured application events written to the `event_log` table via `EventLogger`.
- **Metrics** — JVM-local `HdrHistogram` buckets keyed by segment (reset on JVM restart, by design); durable cost aggregated from persisted `Message.usageJson`.
- **JSON** — single `GsonHolder.INSTANCE` with `serializeNulls` and a custom `Instant` adapter (avoids JDK 25 `setAccessible` refusal).
- **Outbound HTTP** — single **OkHttp 5** stack via `utils.HttpFactories` (`llmStreaming` / `llmSingleShot` / `general`), sharing two connection pools (LLM 64-slot, general 32-slot). `utils.SsrfGuard` adds a per-request DNS allow-list for tool-fetched URLs.
- **MIME types** — `mimetype.*` keys in `application.conf` merge into `play.libs.MimeTypes` at startup.

## Testing strategy

- JUnit 6 under `test/` — a mix of Play `UnitTest` and `FunctionalTest`. Test DB: H2 in-memory (`%test.db.url`).
- `play autotest` (NOT `play test` — the latter is interactive and requires a browser) runs tests headless; the play1 fork's TestEngine runs unit + functional concurrently.
- Lucene-touching tests serialize through `LuceneTestSync` (the index is JVM-global).
- `Jenkinsfile` parallelizes backend tests with frontend Vitest in the `Test` stage; JaCoCo coverage feeds SonarQube.

## Known constraints & caveats

- `jpa.ddl=update` is used in production (explicit tradeoff — pre-1.0, H2 file storage). Column-type changes and renames still need manual intervention.
- The Lucene index is derived from DB rows (re-backfilled on boot); a hard crash can lose at most one commit-interval (`jclaw.search.commitIntervalSeconds`, default 30s) of unsynced segments.
- `ConversationQueue` state is in-process only — multi-instance deployment would need external coordination.
- In-memory latency metrics reset on JVM restart — intentional; not a Prometheus backend.
- Caffeine L2 cache is in-process — fine for the single-process Personal Edition; multi-pod would need a distributed JCache provider.
