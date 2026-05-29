# Backend Architecture — Play 1.x / Java 25

## Executive summary

The backend is a single Play 1.x Java process serving:
1. A JSON API for the Nuxt SPA (`/api/*`).
2. Webhook endpoints for Slack/Telegram/WhatsApp.
3. A catch-all route that serves the pre-built SPA from `public/spa/`.
4. Background jobs for task execution, browser reaping, event-log cleanup, and graceful shutdown.

The architectural style is **service-oriented with a static-method convention** dictated by Play 1.x — controllers, services, and jobs are static methods on classes (no DI container, no Spring). Cross-cutting concerns (auth, JSON body parsing, transactions) are implemented as small utilities (`AuthCheck @Before`, `JsonBodyReader`, `services.Tx`) rather than AOP.

## Technology stack

| Category | Choice | Version | Rationale |
|---|---|---|---|
| Framework | Play Framework | 1.11.x (custom fork `tsukhani/play1`) | Established convention in the org; fast hot-reload; no Spring weight. Fork adds JDK 25 + Jakarta Persistence. |
| Language | Java | 25 (`java.source=25`) | Virtual threads (`newVirtualThreadPerTaskExecutor`) used in TaskPoller and streaming. |
| ORM | JPA via Hibernate | bundled | `play.db.jpa.Model` superclass; `jpa.ddl=update` in both dev and prod. |
| DB (dev) | H2 file | `jdbc:h2:file:./data/jclaw;MODE=MYSQL;AUTO_SERVER=TRUE` | Persists across restarts; MySQL mode for fewer surprises when switching to Postgres. |
| DB (prod) | PostgreSQL (template) | — | Production migration path. Template commented in `application.conf`. |
| Pool | Play's bundled | dev 5–20, prod 5–30 | |
| LLM | OpenAI/OpenRouter/Ollama | — | Sealed `LlmProvider` hierarchy; OpenAI-compatible wire format. |
| Browser automation | Playwright for Java | 1.52 | Chromium installed at `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers` in Docker. |
| Document parsing | Apache Tika | 3.2 | Tika parsers package with several excludes (lucene, cxf, mail) to slim the deploy. |
| Markdown | flexmark | 0.64 | Plus extensions: tables, strikethrough, tasklist, autolink. |
| PDF | flying-saucer | 9.4 | PDF output for exports. |
| Histograms | HdrHistogram | 2.2 | In-memory latency metrics (`/api/metrics/latency`). |
| JSON | Gson | bundled | Single `GsonHolder.INSTANCE` used everywhere for stable serialization. |
| Testing | JUnit 5 platform 1.13.4 | pinned | `junit-vintage-engine` pinned to 5.13.4 because a transitive `pdf-test` dep pulled 5.9.3, causing `ClassFilter NoClassDefFoundError` that killed the TestRunner before any test ran. |

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
                      LlmProvider.chat /      ToolCatalog.invoke
                      streamChat (retry,        (per tool call)
                       SSE parse)
                                 │              │
                                 └──────┬───────┘
                                        ▼
                                  Tool-loop until stop
                                        │
                                        ▼
                        Persist Message rows, return response
```

For channel webhooks, the prelude changes: `WebhookXController.webhook` → verify signature → `AgentRouter.resolve(channelType, peerId)` → `AgentRunner.run` → `ChannelType.resolve().send(...)` for outbound push.

## Key subsystems

### Agent pipeline (`app/agents/`)

- **`AgentRouter`** — 3-tier routing: exact peer match, channel-wide, main-agent fallback.
- **`AgentRunner`** — Prompt assembly + LLM call + tool loop. Config-driven `chat.maxToolRounds` (default 10). Streaming mode surfaces six callbacks: `onInit`, `onToken`, `onReasoning`, `onStatus`, `onComplete`, `onError`. Client-disconnect cancellation via `AtomicBoolean` checked between rounds and inside providers.
- **`SystemPromptAssembler`** — Builds the composite system prompt (agent config + tools + skills + memory).
- **`ToolCatalog` / `ToolRegistry`** — Discovers `tools.*` implementations and exposes their declarative schema to the LLM.
- **`SkillLoader` / `SkillVersionManager`** — File-system skill loading with version bookkeeping.

### LLM layer (`app/llm/`)

- `sealed class LlmProvider permits OpenAiProvider, OllamaProvider, OpenRouterProvider` — shared retry policy (max 3, backoff 1s/2s/4s), OpenAI-compatible JSON, SSE parsing.
- `ProviderRegistry` — atomic swap of active provider set (see `test/ProviderRegistryAtomicTest.java`).
- `LlmTypes` — `ChatMessage`, `ToolDef`, `ModelInfo`, reasoning/thinking metadata.
- **Adding a provider** is a one-line entry in the declarative `PROVIDER_FACTORIES` map.

### Tool system (`app/tools/`)

11 built-in tools: shell, filesystem, web fetch/search, Playwright browser, documents (Tika), tasks, checklists, datetime, skills, plus a load-test sleep tool. Per-agent enablement is controlled by `AgentToolConfig`. `ShellExecTool` consults `AgentSkillAllowedTool` at call time — not the skill's `SKILL.md` file — to prevent allowlist expansion via filesystem-write.

**`ShellExecTool` security posture (JCLAW-146):** the allowlist validates only the first token of the command string; the rest is handed to `/bin/sh -c`, which means shell composition (`;`, `&&`, `||`, `|`, `$(...)`, redirects) is fully available. This is intentional — the agent runs with the same OS privileges as the Play process, so per-token metacharacter-level gating would only move the goalposts while breaking legitimate composition workflows (`cd build && make`, `git log | head`). Sandboxing lives at the `resolveWorkdir` containment check and the env-variable filter, not at the shell-syntax layer. Operators who need hard isolation wrap the Play process itself (firejail, Docker). See the class-level JavaDoc on `ShellExecTool` and the pin tests in `ShellExecToolTest` (section "Shell-composition posture").

### Background jobs (`app/jobs/`)

Play's `@Every`/`@On` jobs:
- `TaskPollerJob @Every("30s")` — claims due `PENDING` tasks, virtual-thread per task.
- `BrowserCleanupJob` — reaps stale Playwright contexts.
- `EventLogCleanupJob` — trims `event_log` by TTL.
- `ShutdownJob` — drains `TaskPoller.activeExecutor` (30s grace), closes browsers.
- `DefaultConfigJob` — seeds required `Config` rows on boot.
- `ToolRegistrationJob` — registers tools post-boot (after DI-equivalent init is safe).

### Channel adapters (`app/channels/`)

`Channel` interface with three outbound implementations: `SlackChannel`, `TelegramChannel`, `WhatsAppChannel`. `WEB` channel is intentionally `null`-returning from `ChannelType.resolve()` — web replies are persisted to `message` and fetched by the frontend on refresh or via SSE.

### SSE notification bus (`services.NotificationBus` + `ApiEventsController`)

Single server-push pipe multiplexed over `/api/events`. Controllers publish typed events (e.g. `skill.promoted`); the frontend's `useEventBus` singleton dispatches to subscribers. See `test/SseStreamTest.java` + `test/NotificationBusTest.java`.

### Per-conversation queue (`services.ConversationQueue`)

Serializes message processing for a conversation to prevent state corruption under concurrent inbound messages. Three modes:
- `queue` (default): FIFO.
- `collect`: Batch pending messages into the next prompt.
- `interrupt`: Cancel in-flight generation via `AtomicBoolean`, queue new message for drain.

Max queue size 20 per conversation. See `test/ConversationQueueTest.java`.

## Cross-cutting concerns

- **Auth** — Cookie-session. `AuthCheck @Before` is the sole gate. Webhooks whitelist themselves. Missing/invalid session returns **401**; genuine authorization failures (controller-level) return **403**.
- **Transactions** — `services.Tx.run(Runnable/Callable)` is a tiny wrapper around `JPA.withTransaction`. Pattern: always re-fetch entities after a nested `Tx.run` to avoid detached instances (see `TaskPollerJob.onSuccess`).
- **Logging** — `log4j2.xml` (dev) / `log4j2-prod.xml` (prod). Structured application events written to the `event_log` table via `EventLogger.info|warn|error(category, agentId?, channel?, message, details?)`.
- **Metrics** — JVM-local `HdrHistogram` buckets keyed by segment. Reset on JVM restart (by design; this is not Prometheus-scraped yet).
- **JSON** — single `GsonHolder.INSTANCE` + Gson's `LOWER_CASE_WITH_UNDERSCORES` policy inside `LlmProvider` for wire-format stability.
- **HTTP clients** — shared `HttpClients` (JDK `HttpClient`) with tuned timeouts; virtual threads for streaming.
- **MIME types** — `mimetype.*` keys in `application.conf` merge into `play.libs.MimeTypes` at startup. Per feedback, the framework default is trusted — don't add controller-level switches.

## Testing strategy

- JUnit 5 under `test/` (44 test classes). Mix of Play `UnitTest` and `FunctionalTest`.
- `play auto-test` (not `play test` — the latter is interactive and requires a browser) runs tests headless.
- Test DB: H2 in-memory (`%test.db.url`).
- Integration flavors present: `AgentSystemTest`, `ApiConversationsControllerTest`, `WebChatTest`, `SseStreamTest`, `PlaywrightToolTest`, `TaskSchedulingTest`, `StreamingToolRoundTest`, `SkillAllowlistTest`.
- `Jenkinsfile` parallelizes backend tests with frontend Vitest in the `Test` stage.

## Known constraints & caveats

- `jpa.ddl=update` is used in production (explicit tradeoff — pre-1.0, H2 file storage). Column-type changes and renames still need manual intervention.
- No full-text index on `memory.text` — LIKE + regex is the chosen approach until scale hurts (then: Postgres `tsvector`, not H2 `FT_*`).
- `ConversationQueue` state is in-process only — multi-instance deployment would need external coordination.
- `in-memory` metrics reset on JVM restart — intentional; not a Prometheus backend.
