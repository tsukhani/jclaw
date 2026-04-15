## Why

JClaw v0.1.0 (core platform) and v0.2.0 high-priority features (Playwright browser tool, conversation message queue) are complete. This proposal captures all remaining gaps identified through a comprehensive delta analysis against OpenClaw and JavaClaw, prioritized for production parity.

**Re-audit 2026-04-16 (current v0.7.27):** Items shipped since this proposal was authored are marked ~~strikethrough with "[shipped vX.Y.Z]"~~. Remaining items form the v1.0-GA scope consumed by the PRD capability contract.

## High Priority — Production Blockers

### Security
- `ssrf-protection`: WebFetchTool has no SSRF filtering — agents can be directed to internal services (169.254.169.254, localhost, etc.). OpenClaw blocks private IP ranges by default. **Small complexity.**
- `webhook-signature-audit`: Verify all webhook controllers (Telegram, Slack, WhatsApp) properly validate platform-specific HMAC signatures. AuthCheck bypasses auth for webhook paths. **Small complexity.**

### Infrastructure
- `github-actions-ci`: No CI/CD pipeline. JavaClaw has GitHub Actions CI. Need build + test + lint workflow. **Small complexity.**
- ~~`dockerfile`: Multi-stage Dockerfile with Node 22 + JDK 25 stages~~ **[shipped; see `Dockerfile` + GHCR publish in Jenkins]**
- `onboarding-wizard`: No guided first-run experience. JavaClaw has a 6-step wizard (welcome, provider, credentials, agent editor, MCP, plugins). JClaw requires manual Settings navigation. **Medium complexity.**

### Agent Intelligence
- `session-compaction`: Context trimming currently drops oldest messages silently. OpenClaw uses chunked LLM summarization and writes SESSION_CONTEXT.md. Without this, agents lose important context mid-conversation. **Medium complexity.**
- `memory-auto-capture`: Memories only accumulate when agents explicitly store them. OpenClaw runs an async auto-capture pipeline after every turn with heuristic attention gating and circuit breaker. This is what makes memory actually work organically. **Medium complexity.**
- `core-memory-importance`: No importance scoring or core-memory auto-load. OpenClaw has memory categories (core, fact, preference, decision, entity, lesson) with importance (0.0–1.0). Core memories are injected into every session automatically. **Medium complexity.**

### Tools
- ~~`shell-exec-tool`: ShellExecTool with allowlist, workspace isolation, output limits, timeout, env sanitization~~ **[shipped 2026-04-08; see `app/tools/ShellExecTool.java` + `openspec/archive/2026-04-08-exec-tool`]**
- `mcp-client`: No Model Context Protocol support. Both JavaClaw and OpenClaw have MCP clients for connecting external tool servers. MCP is becoming the industry standard for extending agent capabilities. **Medium complexity.**

### Channels
- `discord-channel`: Discord is widely used but JClaw only has Telegram, Slack, WhatsApp. JavaClaw has a Discord plugin via JDA. **Small complexity.**

## Medium Priority — Feature Parity

### LLM
- `token-usage-tracking`: No token counting or cost tracking. OpenClaw tracks input/output/cache tokens and estimated USD cost per call with daily charts and CSV export. **Partially implemented** as of v0.5.42 — per-turn prompt/completion/reasoning/cached token counts are surfaced in the chat UI usage footer and logged by `AgentRunner`, but there is no aggregation, persistence, historical chart, or CSV export. **Medium complexity remaining.**
- `model-override-per-session`: Can't switch models mid-conversation. OpenClaw supports runtime model overrides via slash commands. **Small complexity.**
- `vision-multimodal`: ChatMessage only supports text content. OpenClaw supports images alongside text for vision-capable models. **Large complexity.**
- `native-providers-gemini-ollama`: Only OpenAI-compatible providers. JavaClaw has native Google Gemini and Ollama provider modules. **Medium complexity per provider.**
- `prompt-caching-follow-ups`: Deferred items from the v0.5.41–v0.5.42 prompt-caching work. The base functionality (prefix stability, cached-token extraction, per-provider `cache_control` / `keep_alive` directives) shipped in those versions; the items below are optimizations and observability extensions. See commits `fbc4cab` (Tier 1 prefix stability) and `1d42741` (Tier 2 per-provider directives).
    - `cache-hit-aggregation`: Conversation-level rolling "N of M turns hit cache (X%)" statistic, either as an in-memory aggregation over a conversation's recent usage blobs or as a new column on the `Message` / `Conversation` record for historical retention across restarts. Requires deciding whether usage is persisted per-message (currently only emitted to the SSE stream) or computed on demand. **Small complexity if in-memory, Medium if persisted.**
    - `openrouter-cache-discount`: OpenRouter returns a `usage.cache_discount` field (a monetary delta) alongside `cached_tokens`. Parsing it gives exact per-turn dollars saved without needing to multiply by per-million pricing. Requires another `Usage` field (ripples through all constructor sites) and UI surfacing. Deferred in Tier 2 because per-token pricing is already tracked separately and the discount is derivable. **Small complexity.**
    - `openai-prompt-cache-key`: OpenAI's `prompt_cache_key` is a routing hint that tells the load balancer to colocate requests with the same key on the same backend, measurably improving cache hit rates for high-concurrency workloads. Setting it to `conversation.id` is the canonical pattern. Deferred because it requires threading conversation ID through `LlmProvider.chat()` / `chatStream()` — a signature change that ripples through `AgentRunner` and its callers. **Small–Medium complexity.**
    - `anthropic-multi-turn-breakpoint-progression`: Per-block `cache_control` injection shipped in v0.5.44 attaches a single breakpoint to the system message, which caches the stable system prefix across every turn of a conversation. Multi-turn *history* caching (so turn N can reuse turns 1..N-1 from cache, not just the system message) requires a second breakpoint on the last user or assistant message that auto-advances as the conversation grows. Anthropic's docs recommend this pattern for long conversations. Would need the provider to walk the messages array and also stamp `cache_control` on the last user turn. Note: the original Tier 2 plan attempted this via OpenRouter's top-level shortcut, which returns HTTP 404 for most Anthropic routes — the per-block pattern is the only reliable path. **Small–Medium complexity.**
    - `memories-as-user-preamble`: `SystemPromptAssembler` currently places recalled memories at the tail of the system prompt, which is a compromise for cache stability — the system message still varies per turn whenever memory recall returns different results, forcing Anthropic's cache to invalidate at the breakpoint. The optimal design splits `AssembledPrompt` into `(stableSystemPrompt, userPreamble)` and injects the preamble into the latest user message, so the system message becomes byte-identical across all turns of a conversation. Trade-off: user message becomes longer and the LLM sees memories in a slightly different position, which may affect behavior. Should be A/B tested, not a pure refactor. **Medium complexity.**
    - `ollama-keepalive-settings-ui`: The `ollama.keepAlive` Config DB row exists (seeded via `DefaultConfigJob`) and `OllamaProvider` reads it on every request, but there is no Settings UI surface to edit it — an operator must currently go through `POST /api/config` directly. One input field under the Ollama provider section in `frontend/pages/settings.vue`. **Small complexity.**

### Channels
- `group-dm-policies`: No access control for who can message bots. OpenClaw has dmPolicy (allowlist, pairing, open) and groupPolicy per channel. **Small complexity.**
- `multi-account-channels`: Single ChannelConfig per channel type. OpenClaw supports multiple bot accounts per channel type. **Medium complexity.**
- `media-attachments`: Channels are text-only. OpenClaw has a full media pipeline for images, audio, files. **Large complexity.**

### UI
- `usage-analytics-dashboard`: No token/cost analytics. OpenClaw has mosaic charts, cost breakdown by provider/model, session-level detail. Requires token tracking first. **Medium complexity.**
- `conversation-search`: **Partially shipped** — list-page filtering by name/channel/agent/peer is live on `frontend/pages/conversations.vue`. Full-text search via Postgres `tsvector` still pending. **Small complexity remaining.**
- `chat-slash-commands`: No slash commands in web chat (/new, /reset, /model). OpenClaw has a full slash-command executor. **Small complexity.**

### Security
- `security-audit-system`: No startup security audit. OpenClaw checks for dangerous config flags, filesystem permissions, weak auth, SSRF exposure. **Medium complexity.**
- `tool-execution-approvals`: All tool calls execute immediately. OpenClaw has a UI panel for reviewing and approving/denying pending executions (critical for shell exec). **Large complexity.**
- `rate-limiting`: No HTTP rate limiting. **Small complexity.**
- `secrets-management`: API keys stored in DB. OpenClaw resolves secrets from env vars/keychain at runtime, never stored in config file. **Medium complexity.**

### Agent
- `agent-heartbeat`: No periodic self-directed agent activity. OpenClaw supports cron-scheduled heartbeats that inject events into sessions for autonomous background work. **Medium complexity.**
- `sub-agent-spawning`: No sub-agent concept. OpenClaw has full sub-agent lifecycle (spawn, steer, kill, status tracking) via ACP protocol. When this lands, the system-prompt assembler must grow a `promptMode` parameter (`full` | `minimal` | `none`) so sub-agents can receive a lean prompt — OpenClaw's assembler drops most sections in `minimal` mode and emits only identity + tools in `none` mode. See `SystemPromptAssembler.assemble` — currently always builds the full prompt. **Large complexity.**
- `channel-aware-prompt-sections`: System prompt has no per-channel stanzas (voice/TTS, reactions, messaging routing). OpenClaw conditionally injects `## Voice`, `## Reactions`, and `## Messaging` sections based on the active channel so agent behavior adapts to the medium (e.g., short bullet-free responses for Telegram, TTS-friendly phrasing for voice). JClaw's current `SystemPromptAssembler` is channel-agnostic. Low priority until JClaw grows voice or richer reaction UX, but worth adding the `channelType` parameter to `assemble()` so future channels can plug in cleanly. **Small complexity.**
- ~~`brave-web-search`: Brave + Tavily + Exa + Perplexity + Ollama + Felo search providers~~ **[shipped; see `app/tools/WebSearchTool.java` — 6 providers via the `SearchProvider` list]**

### Infrastructure
- `health-check-enriched`: /api/status is minimal. Should include DB connectivity, provider reachability, memory store status. **Small complexity.**
- `opentelemetry-export`: No metrics/traces export. OpenClaw exports OTLP/Protobuf to any OTel-compatible backend. **Medium complexity.**

## Tool Gap Analysis — OpenClaw (29 tools) vs JClaw (8 tools)

| Category | OpenClaw | JClaw |
|---|---|---|
| File operations | read, write, edit, apply_patch | filesystem (read/write/list) |
| Shell execution | exec, process (background) | exec |
| Web | web_search, web_fetch | web_search, web_fetch |
| Browser | *(none — uses exec + puppeteer)* | browser (Playwright) |
| Tasks/scheduling | cron | task_manager |
| Skills/workflow | checklist-like via update_plan | checklist, skills |
| Session management | sessions_list, sessions_history, sessions_send, sessions_spawn, sessions_yield, session_status | *(none)* |
| Agent management | agents_list, subagents, gateway | *(none)* |
| Media | image, image_generate, video_generate, music_generate, tts, pdf | *(none)* |
| Communication | canvas, message, nodes | *(none)* |

**Biggest gaps by impact:**
1. Session/sub-agent tools (6 tools) — multi-agent orchestration with spawn/yield/announce
2. Media pipeline (6 tools) — image/video/audio generation + PDF extraction
3. File edit tool — line-range editing vs full-file write-only
4. Background process management — OpenClaw can track/query running processes
5. Message tool — proactive cross-channel messaging to arbitrary targets
6. Canvas — desktop-app-specific UI panel, not applicable to JClaw's web architecture
7. Nodes — companion device control (iOS/Android/macOS), not applicable to single-server

## Deferred

- `memory-sleep-cycle`: 10-phase memory consolidation (dedup, entity extraction, decay, graph linking). Requires Neo4j. **Large.**
- `lancedb-memory`: Embedded vector DB alternative to pgvector. **Medium.**
- `image-generation`: Provider registry (fal.ai, Comfy). **Large.**
- `video-generation`: Runway, fal.ai support. **Large.**
- `tts-stt`: ElevenLabs, Deepgram speech pipeline. **Large.**
- `voice-calls`: Twilio webhook integration. **Large.**
- `signal-channel`: Signal messenger via signal-cli. **Medium.**
- `msteams-channel`: Microsoft Teams via Azure Bot Framework. **Medium.**
- `matrix-channel`: Matrix protocol with thread binding. **Medium.**
- `imessage-channel`: macOS-only via BlueBubbles. **Large.**
- `nostr-channel`: Decentralized social protocol. **Medium.**
- `horizontal-scaling`: Requires PostgreSQL + session externalization. **Large.**
- `plugin-module-system`: Formal plugin SDK with lifecycle hooks. **Large.**
- `i18n`: Multi-language UI (10+ locales in OpenClaw). **Medium.**
- `codeql-static-analysis`: GitHub CodeQL integration. **Small.**
- `openapi-docs`: Auto-generated API documentation. **Small.**
- `additional-search-apis`: Tavily, Exa, DuckDuckGo, SearXNG, Perplexity, Firecrawl. **Small each.**

## Bug Fixes & Performance — Audit Findings

Identified via full-codebase performance audit (2026-04-07) covering database/JPA, memory/caching, concurrency, HTTP/API, and frontend.

### Critical — Fix Immediately

- ~~`fix-sync-transaction-scope`~~ **[done v0.1.0]** — Sync path uses scoped `Tx.run()` calls.
- ~~`fix-db-pool-config`~~ **[done v0.1.0]** — Pool sizing in `application.conf`.
- ~~`fix-queue-thread-safety`~~ **[done; verified 2026-04-16]** — `ConversationQueue` now uses `ConcurrentHashMap<Long, QueueState>` + `synchronized(state)` on all mutation paths; `tryStartProcessing`, `finishProcessing`, `isProcessing` all synchronized.
- ~~`fix-tailwind-purge`~~ **[done; verified 2026-04-16]** — `tailwind.config.js` has correct content paths covering Vue/TS sources.

### High — Fix Soon (correctness or significant performance)

- ~~`fix-sse-disconnect-propagation`~~ **[done; verified 2026-04-16]** — `ApiChatController.stream` uses `AtomicBoolean cancelled` with propagation on write failures to stop the virtual thread.
- ~~`fix-task-poller-detached-entity`~~ **[done; verified 2026-04-16]** — `Task.findPendingDue()` uses a native query returning materialized entities, eliminating the lazy-proxy path.
- ~~`fix-streaming-detached-agent`~~ **[done per git commit cadd543 "Fix detached entity bug in AgentRunner.run()"]** — verify fix still holds at next code review pass.
- ~~`fix-n-plus-one-queries`~~ **[done v0.6.5 "N+1 bulk delete" + v0.7.2 "Paginate getMessages, lock-free ConfigService, bulk memory delete"]** — full sweep completed across the named sites.
- `fix-queue-mode-race`: `ConversationQueue.tryAcquire()` writes `state.mode` unsynchronized; concurrent calls for the same conversation overwrite each other's mode setting. Read mode once into a local variable; stop mutating `state.mode` from `tryAcquire()`. `ConversationQueue.java:52-83`. **Small complexity.**
- `fix-playwright-session-race`: `PlaywrightBrowserTool.getOrCreatePage()` is synchronized on `this`, but `closeSession()` and `cleanupIdleSessions()` are not synchronized at all. The cleanup job can close a `Page` mid-use. Synchronize all session lifecycle methods on the same monitor. `PlaywrightBrowserTool.java:157-196`. **Small complexity.**
- `fix-task-double-execution`: `TaskPollerJob` has a TOCTOU race — two consecutive poller runs can both see a task as `PENDING` and execute it twice. No optimistic locking. Use `UPDATE task SET status='RUNNING' WHERE id=? AND status='PENDING'` CAS pattern. `TaskPollerJob.java:21-27`. **Small complexity.**
- `fix-orphaned-sse-streams`: Frontend `chat.vue` assigns a new `AbortController` without aborting the previous one on rapid re-send. Old fetch stream continues mutating stale array indices. Add `abortController.value?.abort()` before creating a new controller. `frontend/pages/chat.vue:89-90`. **Small complexity.**
- `fix-agents-fetch-waterfall`: Two sequential `await useFetch` calls on the agents page create a network waterfall. Use `Promise.all`. `frontend/pages/agents.vue:2-3`. **Small complexity.**
- `fix-auth-double-fetch`: Auth middleware reuses `/api/config` for the auth check, causing a double-fetch on agents/settings pages. Use a dedicated lightweight auth endpoint. `frontend/middleware/auth.global.ts`. **Small complexity.**

### Medium — Performance Under Load

- ~~`fix-eventlogger-transaction-spam`~~ **[done v0.6.5 "EventLogger batching"]** — buffer + batch-flush implemented.
- `fix-conversation-merge-overhead`: `ConversationService.appendMessage()` re-merges a detached conversation on every call in the streaming tool loop (SELECT + INSERT + UPDATE instead of INSERT + UPDATE). Pass `conversationId` across boundaries; reload inside `Tx.run()`. `ConversationService.java:34-37`. **Small complexity.**
- `fix-cache-eviction`: `ConfigService`, `AgentService.fileCache`, and `SkillLoader.skillCache` all use TTL caches that never evict expired entries — zombie entries accumulate indefinitely. Add periodic sweep or use `computeIfAbsent` with expiry. `ConfigService.java:19`, `AgentService.java:14`, `SkillLoader.java:26`. **Small complexity.**
- `fix-unbounded-fetches`: Several queries lack pagination: `Conversation.findByChannel()` loads all rows, `Memory.findByAgent()` caps at 1000 with TEXT content, `ApiChatController.getMessages()` loads every message for a conversation. Add limit/offset to all. `Conversation.java:63`, `Memory.java:43`, `ApiChatController.java:194`. **Small complexity.**
- `fix-per-token-serialization`: Per-token `Map.of()` + `gson.toJson()` allocation in the SSE hot path (dozens per second). Replace with a pre-built string template with manual JSON escaping. `ApiChatController.java:101-107`. **Small complexity.**
- `fix-streaming-retry-duplication`: Streaming retry re-emits already-sent tokens to the SSE client. Client receives duplicate content with no dedup mechanism. Remove streaming retry or emit an error event for client-side retry. `AgentRunner.java:171-178`. **Small complexity.**
- `fix-channel-config-caching`: `SlackChannel.load()`, `TelegramChannel.load()`, `WhatsAppChannel.load()` each query the DB on every webhook request with no cache. Add volatile + TTL cache. `SlackChannel.java:29-37`, `TelegramChannel.java:26-34`, `WhatsAppChannel.java:28-38`. **Small complexity.**
- ~~`fix-provider-registry-volatile`~~ **[done v0.6.5 "ProviderRegistry lock-free IO + deterministic order"]**.
- `fix-cache-stampede`: TTL caches in `AgentService`, `ConfigService`, and `SkillLoader` use check-then-act without atomicity. Under concurrent load, N threads miss cache simultaneously and do redundant work. Use `ConcurrentHashMap.compute()`. `AgentService.java:161`, `ConfigService.java:23`, `SkillLoader.java:42`. **Small complexity.**
- `fix-missing-indexes`: Add database indexes for frequently-queried FK columns: `agent_id` on `agent_binding` and `task`; `agent_id`+`channel` compound index on `event_log`; explicit `@Index` on `channel_config.channel_type` and `config.config_key`. **Small complexity.**
- `fix-chat-streaming-reactivity`: Per-token mutation of the reactive messages array triggers O(n) re-render of `displayMessages` computed on every token. Keep streaming content in a separate `shallowRef`; only push to messages on `complete`. `frontend/pages/chat.vue:124-127`. **Small complexity.**
- `fix-markdown-memoization`: `renderMarkdown` (marked.parse + DOMPurify.sanitize) runs on every render for every message, including during streaming (20-50x/sec). Memoize or only render on `complete`. `frontend/pages/chat.vue:215`. **Small complexity.**
- `fix-edit-agent-serial-fetches`: Four serial `$fetch` calls in `editAgent` should be parallelized with `Promise.all`. `frontend/pages/agents.vue:96-130`. **Small complexity.**

## Re-audit Status (2026-04-16 against v0.7.27)

**Items shipped since this proposal was authored (removed from scope):**
`dockerfile`, `shell-exec-tool`, `brave-web-search`, `runtime-tool-reregistration`, `fix-sync-transaction-scope`, `fix-db-pool-config`, `fix-queue-thread-safety`, `fix-tailwind-purge`, `fix-sse-disconnect-propagation`, `fix-task-poller-detached-entity`, `fix-streaming-detached-agent`, `fix-n-plus-one-queries`, `fix-eventlogger-transaction-spam`, `fix-provider-registry-volatile`.

**Items partially shipped (remaining scope trimmed):**
- `conversation-search` — list-page filtering live; full-text via tsvector pending.
- `token-usage-tracking` — per-turn counts surfaced in UI; persistence + aggregation + CSV export pending.

**Items not individually re-verified this pass (check at epic-breakdown time):**
`fix-queue-mode-race`, `fix-playwright-session-race`, `fix-task-double-execution`, `fix-orphaned-sse-streams`, `fix-agents-fetch-waterfall`, `fix-auth-double-fetch`, all remaining Medium-tier performance fixes.

## Implementation Lessons (from v0.1.0 and v0.2.0)
- Virtual threads require explicit JPA transaction context (`Tx.run` helper) — Play's thread-local JPA is not inherited
- Play 1.x ECJ compiler requires `java.source=25` in `application.conf` for JDK 25 features
- Play's `unauthorized()` sends 401 with WWW-Authenticate header triggering browser basic auth — use 403 with JSON for API endpoints
- Sealed interfaces with nested records don't work in ECJ — use separate files for sealed type hierarchies
- Large tool results (50KB+) can stall LLM processing for minutes — extract text or auto-save to workspace instead of passing through context
- Playwright driver-bundle is 191MB — manage via Ivy (dependencies.yml), gitignore lib/
- Per-conversation queue locks must use try/finally to prevent deadlock on error
- GitHub rejects files >100MB — use Ivy dependency management, not committed JARs
