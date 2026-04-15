# Source Tree Analysis

Annotated directory tree of the jclaw repository. Paths that generate output (logs, dist, tmp, node_modules) are omitted.

```
jclaw/
├── app/                          # Play 1.x backend (entry point: conf/routes)
│   ├── controllers/              # HTTP endpoints. Static-method Play controllers.
│   │   ├── Api*Controller.java   # JSON API (all under /api/*). Guarded by @With(AuthCheck.class).
│   │   ├── Webhook*Controller.java  # Inbound webhooks — exempted from auth, verified per-channel.
│   │   ├── AuthCheck.java        # Play @Before interceptor — checks session('authenticated').
│   │   ├── JsonBodyReader.java   # Gson-backed JSON body parser.
│   │   └── Application.java      # SPA fallback (serves public/spa/index.html for unknown routes).
│   ├── models/                   # JPA entities extending play.db.jpa.Model.
│   │   ├── Agent, AgentBinding   # Agent config + channel→agent routing bindings.
│   │   ├── Conversation, Message # Chat history (indexed by agent+channel+peer).
│   │   ├── Task                  # Scheduled/cron/immediate tasks + retry bookkeeping.
│   │   ├── Memory                # Free-form per-agent memory rows (LIKE-searched).
│   │   ├── Config                # Key/value application config (overrides application.conf).
│   │   ├── EventLog              # Structured event audit log (category + level + details).
│   │   ├── ChannelConfig, ChannelType  # Per-channel JSON config + enum with outbound resolver.
│   │   ├── AgentToolConfig       # Per-agent tool enablement.
│   │   ├── AgentSkillConfig      # Per-agent skill enablement.
│   │   ├── AgentSkillAllowedTool # Per-(agent, skill, tool) shell-exec allowlist (DB-authoritative).
│   │   ├── SkillRegistryTool     # Per-(skill, tool) registry snapshot from promotion.
│   │   └── MessageRole           # user/assistant/tool/system (string-backed enum).
│   ├── agents/                   # Agent orchestration layer.
│   │   ├── AgentRouter.java      # 3-tier routing: peer → channel → main.
│   │   ├── AgentRunner.java      # Prompt assembly + LLM call + tool loop (DEFAULT_MAX_TOOL_ROUNDS=10).
│   │   ├── SystemPromptAssembler.java  # Builds the final system prompt.
│   │   ├── SkillLoader.java, SkillVersionManager.java  # File-system skill loading.
│   │   ├── ToolCatalog.java, ToolRegistry.java         # Discovers/registers Tool impls.
│   ├── llm/                      # LLM provider integrations (sealed hierarchy).
│   │   ├── LlmProvider.java      # Abstract base: retries, streaming, OpenAI-compatible HTTP.
│   │   ├── OpenAiProvider, OllamaProvider, OpenRouterProvider  # Concrete providers.
│   │   ├── ProviderRegistry.java # Atomic swap of active provider set.
│   │   └── LlmTypes.java         # ChatMessage, ToolDef, ModelInfo records.
│   ├── memory/                   # Pluggable memory store.
│   │   ├── MemoryStore.java (iface), JpaMemoryStore.java, MemoryStoreFactory.java
│   │   └── Neo4jMemoryStore.java.disabled  # Future; not compiled.
│   ├── tools/                    # Tool implementations exposed to agents.
│   │   ├── ShellExecTool.java    # Shell command exec (consults AgentSkillAllowedTool at call time).
│   │   ├── FileSystemTools.java, WebFetchTool.java, WebSearchTool.java
│   │   ├── PlaywrightBrowserTool.java  # Headless Chromium via Playwright.
│   │   ├── DocumentsTool.java    # Tika-backed document ingest.
│   │   ├── TaskTool.java, CheckListTool.java, DateTimeTool.java, SkillsTool.java
│   │   └── LoadTestSleepTool.java  # Deterministic sleep for load-testing.
│   ├── jobs/                     # Play background jobs.
│   │   ├── TaskPollerJob @Every("30s")  # Drains pending tasks on virtual threads.
│   │   ├── BrowserCleanupJob     # Reaps Playwright browser contexts.
│   │   ├── EventLogCleanupJob    # Trims EventLog by TTL.
│   │   ├── ShutdownJob           # Graceful shutdown: drains TaskPoller, closes browsers.
│   │   ├── DefaultConfigJob      # Seeds required Config rows on boot.
│   │   ├── ToolRegistrationJob   # Registers tools post-boot.
│   │   └── CronParser.java       # CRON expression → next Instant.
│   ├── channels/                 # Outbound messaging adapters.
│   │   ├── Channel.java (iface), SlackChannel, TelegramChannel, WhatsAppChannel
│   ├── services/                 # Business-logic services (Play convention: static methods).
│   │   ├── AgentService, ConfigService, ConversationService, ConversationQueue (per-convo FIFO),
│   │   ├── ModelDiscoveryService, NotificationBus (SSE fan-out), EventLogger,
│   │   ├── DocumentWriter, SkillPromotionService, SkillBinaryScanner,
│   │   ├── LoadTestHarness, LoadTestRunner,
│   │   ├── Tx.java               # Tiny transactional wrapper.
│   │   └── scanners/             # Pluggable file-scan integrations (VirusTotal, MetaDefender, MalwareBazaar).
│   ├── utils/                    # Cross-cutting utilities.
│   │   ├── HttpClients, GsonHolder, JpqlFilter, LatencyStats, LatencyTrace,
│   │   ├── VirtualThreads, WebhookUtil
│   └── views/                    # Groovy templates (only Application, errors — SPA handles UI).
│
├── conf/                         # Play configuration.
│   ├── application.conf          # Main config (ports, DB, JPA, pools, %prod/%test overrides).
│   ├── application.prod.example.conf  # Production template.
│   ├── routes                    # URL table — ~70 /api/* endpoints + SPA catch-all.
│   ├── dependencies.yml          # Play module deps: playwright, tika, flexmark, flying-saucer, etc.
│   ├── log4j2.xml / log4j2-prod.xml  # Logging.
│   ├── messages                  # i18n (default locale).
│   └── nginx.example.conf        # Reverse-proxy template.
│
├── frontend/                     # Nuxt 3 SPA.
│   ├── app.vue                   # Root: <NuxtLayout>/<NuxtPage/> + global ConfirmDialog.
│   ├── layouts/default.vue       # The only layout: sidebar + topbar + content slot.
│   ├── pages/                    # File-routed pages (11).
│   │   ├── index.vue             # Dashboard.
│   │   ├── chat.vue, conversations.vue  # Chat + history (chat is largest page, ~1k LOC).
│   │   ├── agents.vue, channels.vue, skills.vue, tools.vue, tasks.vue, logs.vue
│   │   ├── settings.vue          # Largest page (~1.5k LOC).
│   │   └── login.vue             # Only page that bypasses auth.global.ts.
│   ├── components/
│   │   ├── ConfirmDialog.vue     # Globally mounted, driven by useConfirm.
│   │   └── SkillFileTree.vue     # Used on skills.vue.
│   ├── composables/
│   │   ├── useAuth.ts            # Session state + login/logout/checkAuth (module-level lock).
│   │   ├── useApiMutation.ts     # $fetch wrapper with loading/error state.
│   │   ├── useEventBus.ts        # Singleton EventSource → /api/events fan-out.
│   │   ├── useProviders.ts, useToolMeta.ts, useTheme.ts, useConfirm.ts
│   ├── middleware/auth.global.ts # Redirects to /login when checkAuth() fails.
│   ├── types/api.ts              # API wire types (Agent, Conversation, Message, …).
│   ├── utils/
│   │   ├── format.ts             # Date/number formatters.
│   │   └── usage-cost.ts         # LLM cost calculator from token usage.
│   ├── test/                     # Vitest unit tests (7 files — auth, chat, composables, markdown, pages, skills, usage-cost).
│   ├── tests/, playwright.config.ts, playwright-report/  # Playwright E2E harness.
│   ├── vitest.config.ts          # happy-dom env.
│   ├── tailwind.config.js, nuxt.config.ts
│   └── package.json              # pnpm; pinned 10.33.0.
│
├── test/                         # Play backend tests (44 JUnit classes + Application.test.html).
│                                 # Run with `play auto-test` (non-interactive) — not `play test`.
├── public/                       # Play static assets (+ spa/ injected by dist).
├── skills/                       # File-system skill definitions (global, promoted-skill source of truth).
├── workspace/                    # Per-agent workspace (filesystem-tool scoped).
├── data/                         # H2 DB file + attachments/ blob storage (dev default).
├── logs/                         # Runtime logs.
├── lib/, modules/, tmp/, precompiled/, dist/  # Play runtime/build directories.
├── openspec/                     # OpenSpec change proposals.
│   ├── changes/v020-backlog/     # Active proposal.
│   ├── specs/shell-exec/         # Ratified spec.
│   └── archive/
├── docs/                         # <-- this documentation suite.
│   └── perf/                     # Performance baseline JSON (load-test output).
├── documentation/                # Play default welcome page (ignorable).
├── _bmad/, _bmad-output/         # BMAD/BMM workflow artifacts.
├── jclaw.sh                      # One-stop dev/deploy launcher (backend + frontend).
├── Dockerfile                    # 3-stage build → GHCR image.
├── docker-compose.yml            # Consumer-facing single-container deploy.
├── Jenkinsfile                   # CI (build, test, dist, release to GitHub + GHCR).
├── README.md, CLAUDE.md, LICENSE # Root docs.
└── *.iml / *.ipr / *.iws         # IntelliJ project files (checked in).
```

## Integration points (multi-part)

- **Frontend → Backend (dev):** `frontend/nuxt.config.ts` → `nitro.devProxy['/api']` → `http://localhost:9000/api` — no CORS config needed.
- **Frontend → Backend (prod):** the SPA is `nuxi generate`-d and copied into `public/spa/`. Play serves `/_nuxt/*` as static dir and catches unknown paths via `Application.spa` (renders `public/spa/index.html`), so the frontend and API share origin.
- **Session coupling:** auth is cookie-backed (Play `session.get("authenticated")`); `useAuth.checkAuth` pings `/api/config` to detect session validity — no token exchange.
- **SSE channel:** `/api/events` (`ApiEventsController`) + `NotificationBus` backend → `useEventBus` singleton `EventSource` frontend.
