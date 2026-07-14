# Source Tree Analysis

Annotated directory tree of the jclaw repository. Paths that generate output (logs, dist, tmp, node_modules, .nuxt) are omitted.

```
jclaw/
├── app/                          # Play 1.x backend (entry point: conf/routes)
│   ├── controllers/              # HTTP endpoints. Static-method Play controllers (~34).
│   │   ├── Api*Controller.java   # JSON API (all under /api/*). Guarded by @With(AuthCheck.class).
│   │   ├── Webhook*Controller.java  # Inbound webhooks — exempted from auth, verified per-channel.
│   │   ├── AuthCheck.java        # Play @Before interceptor — session cookie + Bearer ApiToken.
│   │   └── Application.java      # SPA fallback (serves public/spa/index.html for unknown routes).
│   ├── models/                   # JPA entities extending play.db.jpa.Model (~29 entities).
│   │   ├── Agent, AgentBinding   # Agent config + channel→agent routing bindings.
│   │   ├── Conversation, Message, MessageAttachment, SessionCompaction  # Chat history + media + summaries.
│   │   ├── Task, TaskRun, TaskRunMessage  # Scheduling + run history + transcripts.
│   │   ├── SlackBinding, TelegramBinding, TelegramTopicBinding, WhatsAppBinding, ChannelConfig  # Channel bindings.
│   │   ├── McpServer, AgentToolConfig, AgentSkillConfig, AgentSkillAllowedTool, SkillRegistryTool  # Tools/skills/MCP.
│   │   ├── Memory                # Per-agent memory rows (Lucene-indexed).
│   │   ├── Config, ApiToken      # Key/value config + Bearer-token credential.
│   │   ├── Notification, SubagentRun, VideoGenerationJob, ToolApprovalGrant  # Reminders, subagents, async jobs, grants.
│   │   ├── EventLog, LatencyMetric, CompressionMetric  # Audit log + metrics.
│   │   └── MessageRole, ChannelType, …  # String-backed enums.
│   ├── agents/                   # Agent orchestration layer (~29 classes).
│   │   ├── AgentRouter.java      # 3-tier routing: peer → channel → main.
│   │   ├── AgentRunner.java      # Prompt assembly + LLM call + tool loop (cap via chat.maxToolRounds).
│   │   ├── SystemPromptAssembler.java  # Builds the final system prompt.
│   │   ├── ToolRegistry/ToolCatalog/ToolCallLoopRunner/ParallelToolExecutor  # Tool discovery + execution.
│   │   ├── ContextWindowManager, CompactionGate, CompressionPipeline  # Context budget management.
│   │   └── SkillLoader, SkillVersionManager, CancellationManager, SubagentRegistry
│   ├── llm/                      # LLM provider integrations (sealed hierarchy, OkHttp 5).
│   │   ├── LlmProvider.java      # Sealed base: retries, OpenAI-compatible HTTP, SSE parsing.
│   │   ├── OpenAiProvider, OllamaProvider, OpenRouterProvider, TogetherAiProvider  # Concrete providers.
│   │   ├── OkHttpLlmHttpDriver.java  # The single HTTP driver (OkHttp 5 + okhttp-sse).
│   │   ├── ProviderRegistry.java # Config-backed provider set (60s refresh).
│   │   ├── TokenUsageEstimator.java  # JTokkit counting, Caffeine-memoized.
│   │   └── LlmTypes.java         # ChatMessage, ToolDef, ChatRequest/Response, Usage.
│   ├── mcp/                      # Model Context Protocol client (~12 classes).
│   │   ├── McpClient.java        # JSON-RPC state machine.
│   │   ├── McpStdioTransport, McpStreamableHttpTransport, McpConnectionManager
│   │   └── McpToolAdapter.java   # Bridges discovered MCP tools into agents.ToolRegistry.
│   ├── memory/                   # Memory store (single JPA-backed impl; Neo4j dropped).
│   │   ├── MemoryStore.java (iface), JpaMemoryStore.java, MemoryStoreFactory.java
│   │   ├── PgVectorProvisioner.java, ReciprocalRankFusion.java   # hybrid vector recall (pgvector / Lucene-HNSW)
│   │   └── MemoryAutoCapture.java, MemoryReranker.java, MemoryDecay.java, MemoryAttentionGate.java, MemoryCategory.java, MemorySafety.java
│   ├── tools/                    # Tool implementations exposed to agents (~22).
│   │   ├── ShellExecTool.java    # Shell exec (consults AgentSkillAllowedTool at call time).
│   │   ├── FileSystemTools, WebFetchTool (SSRF-guarded), WebSearchTool
│   │   ├── PlaywrightBrowserTool, DocumentsTool (Tika), GenerateImageTool, GenerateVideoTool
│   │   ├── TaskTool, SubagentSpawnTool, SubagentYieldTool, MessageTool, ConversationHistoryTool
│   │   └── JClawApiTool, CheckListTool, DateTimeTool, LoadTestSleepTool
│   ├── jobs/                     # Play @Every / @OnApplicationStart jobs (~32).
│   │   ├── DbSchedulerBootstrapJob, DbSchedulerSchemaInitJob  # db-scheduler wiring (task execution).
│   │   ├── FullTextSearchInitJob # Opens Lucene indices at boot.
│   │   ├── DefaultConfigJob, ToolRegistrationJob, McpStartupJob, TokenizerCalibrationJob
│   │   ├── *CleanupJob, *FlushJob # event-log / latency / browser / queue cleanup.
│   │   ├── OllamaLocalProbeJob, LmStudioProbeJob, TesseractProbeJob  # Reachability probes.
│   │   └── ShutdownJob           # Commits + closes Lucene, drains executors on stop.
│   ├── channels/                 # Messaging adapters (~57 classes).
│   │   ├── Channel.java (iface), ChannelRegistry
│   │   ├── Slack* (HTTP webhook + Socket Mode), Telegram* (polling + webhook)
│   │   ├── WhatsApp* (Cloud API) + WhatsAppCobalt* (WhatsApp-Web via it.auties.whatsapp)
│   │   └── WebChannel             # WEB replies persist to message + stream over SSE.
│   ├── services/                 # Business-logic services (Play convention: static methods).
│   │   ├── Tx.java               # Transactional wrapper (no-ops if already inside a tx).
│   │   ├── ConfigService, ConversationService, ConversationQueue, SessionCompactor
│   │   ├── search/               # LuceneIndexer + DirectLuceneMessageSearchRepository (Lucene 10).
│   │   ├── transcription/, caption/, imagegen/, videogen/, video/  # Media subsystems + sidecar managers.
│   │   ├── compression/, catalog/, scanners/  # Compression, tool/task catalogs, malware scanners.
│   │   ├── TaskSchedulingService, TaskExecutionHandler, ReminderDispatcher  # db-scheduler integration.
│   │   ├── NotificationBus (SSE fan-out), EventLogger, LatencyMetricRecorder
│   │   └── LoadTestRunner, LoadTestHarness
│   ├── utils/                    # Cross-cutting utilities (~20).
│   │   ├── HttpFactories         # Single OkHttp 5 provisioning (llmStreaming/llmSingleShot/general).
│   │   ├── SsrfGuard, GsonHolder, JpqlFilter, LatencyStats, LatencyTrace
│   │   └── VirtualThreads, WebhookUtil, TokenCoalescer
│   ├── com/aspose/words/         # Tiny PDFBox-backed shim satisfying Cobalt's Aspose static link (JCLAW-451).
│   ├── slash/                    # Slash-command dispatcher (Commands.java: /new, /reset, /compact, /model, …).
│   ├── plugins/                  # (empty)
│   └── views/                    # Groovy templates (only Application + error pages — SPA handles UI).
│
├── conf/                         # Play configuration.
│   ├── application.conf          # The only config file. %prod. / %test. prefixes; secrets via ${VARNAME}.
│   ├── routes                    # URL table — ~80 /api/* endpoints + SPA catch-all.
│   ├── log4j2.xml / log4j2-prod.xml / log4j2-test.xml  # Logging per mode.
│   └── messages                  # i18n (default locale).
│
├── frontend/                     # Nuxt 4 SPA.
│   ├── app.vue                   # Root: <NuxtLayout>/<NuxtPage/> + global dialogs.
│   ├── layouts/default.vue       # The only layout: sidebar + topbar + content slot.
│   ├── pages/                    # File-routed pages (20, incl. conversations/ + channels/).
│   ├── components/
│   │   ├── ui/                   # 74 shadcn-nuxt / Reka UI primitives.
│   │   └── *.vue, guide/         # 17 feature components (DataTable, ChatContextMeter, GuideRenderer, …).
│   ├── composables/              # ~17 (useAuth, useEventBus, useApiParsed, useApiMutation, useTheme, …).
│   ├── plugins/                  # theme.client.ts (pre-paint theme), axe.client.ts (dev a11y).
│   ├── middleware/auth.global.ts # Routes to /login or /setup-password based on auth state.
│   ├── types/                    # api.ts (wire types) + schemas.ts (Zod) + ambient .d.ts.
│   ├── utils/                    # Pure helpers (format, usage-cost, tool-calls, schedule, …).
│   ├── test/                     # Vitest unit tests (~69 files).
│   ├── tests/e2e/, playwright.config.ts  # Playwright E2E harness.
│   ├── vitest.config.ts          # jsdom env.
│   ├── nuxt.config.ts, components.json, eslint.config.mjs, stylelint.config.mjs
│   └── package.json              # pnpm; pinned 11.11.0 (+sha512).
│
├── sidecar/                      # Python local-generation daemons (uv-run).
│   ├── image/                    # Local diffusion image model (FLUX.2 klein).
│   └── video/                    # Local video model (LTX / WAN).
├── test/                         # Play backend tests (JUnit 6). Run with `play autotest` — not `play test`.
├── public/                       # Play static assets; spa/ staged here at start/deploy (gitignored).
├── skills/                       # File-system skill definitions (global, promoted-skill source of truth).
├── workspace/                    # Per-agent workspace (filesystem-tool scoped; main/ holds Standing Orders).
├── data/                         # H2 DB file + attachments/ + jclaw-lucene/ index (dev default).
├── .githooks/                    # pre-commit (lint-staged) + pre-push (full suite) hooks.
├── .devcontainer/                # Dev Container (Ubuntu 26.04, Zulu 25, Node 24, Play fork).
├── openspec/                     # OpenSpec proposals.
│   ├── specs/shell-exec/         # Ratified spec.
│   └── archive/                  # Completed proposals (v020-backlog, performance-critical-fixes).
├── docs/                         # <-- this documentation suite (architecture/ + user-guide/).
├── _bmad/, _bmad-output/         # BMAD/BMM workflow artifacts.
├── jclaw.sh                      # One-stop dev/deploy launcher (backend + frontend).
├── install.sh / install.ps1      # One-line bundle installers (macOS/Linux/Windows).
├── Dockerfile                    # Multi-stage build → GHCR image.
├── docker-compose.yml            # Consumer-facing single-container deploy.
├── Jenkinsfile                   # CI (build, test, dist/bundle, release to GitHub + GHCR).
├── .play-version                 # Pinned play1 fork version (1.13.45).
└── README.md, CLAUDE.md, AGENTS.md, LICENSE  # Root docs.
```

## Integration points (multi-part)

- **Frontend → Backend (dev):** `frontend/nuxt.config.ts` → `nitro.devProxy['/api']` → `http://localhost:9000/api` — no CORS config needed.
- **Frontend → Backend (prod):** the SPA is `nuxi generate`-d and copied into `public/spa/`. Play serves `/_nuxt/*` as a static dir and catches unknown paths via `Application.spa` (renders `public/spa/index.html`), so the frontend and API share origin.
- **Session coupling:** auth is cookie-backed; `useAuth.checkAuth` pings the auth-status endpoint to detect session validity — no token exchange (the in-process `jclaw_api` tool uses a Bearer `ApiToken` separately).
- **SSE channel:** `/api/events` (`ApiEventsController`) + `NotificationBus` backend → `useEventBus` singleton `EventSource` frontend.
