## Why

JClaw v0.1.0 (core platform) and v0.2.0 high-priority features (Playwright browser tool, conversation message queue) are complete. This proposal captures all remaining gaps identified through a comprehensive delta analysis against OpenClaw and JavaClaw, prioritized for production parity.

## High Priority — Production Blockers

### Security
- `ssrf-protection`: WebFetchTool has no SSRF filtering — agents can be directed to internal services (169.254.169.254, localhost, etc.). OpenClaw blocks private IP ranges by default. **Small complexity.**
- `webhook-signature-audit`: Verify all webhook controllers (Telegram, Slack, WhatsApp) properly validate platform-specific HMAC signatures. AuthCheck bypasses auth for webhook paths. **Small complexity.**

### Infrastructure
- `github-actions-ci`: No CI/CD pipeline. JavaClaw has GitHub Actions CI. Need build + test + lint workflow. **Small complexity.**
- `dockerfile`: No Docker support. OpenClaw has multi-platform Docker release workflow (amd64 + arm64). Need Dockerfile for Play 1.x + JDK 25 + pnpm frontend build. **Medium complexity.**
- `onboarding-wizard`: No guided first-run experience. JavaClaw has a 6-step wizard (welcome, provider, credentials, agent editor, MCP, plugins). JClaw requires manual Settings navigation. **Medium complexity.**

### Agent Intelligence
- `session-compaction`: Context trimming currently drops oldest messages silently. OpenClaw uses chunked LLM summarization and writes SESSION_CONTEXT.md. Without this, agents lose important context mid-conversation. **Medium complexity.**
- `memory-auto-capture`: Memories only accumulate when agents explicitly store them. OpenClaw runs an async auto-capture pipeline after every turn with heuristic attention gating and circuit breaker. This is what makes memory actually work organically. **Medium complexity.**
- `core-memory-importance`: No importance scoring or core-memory auto-load. OpenClaw has memory categories (core, fact, preference, decision, entity, lesson) with importance (0.0–1.0). Core memories are injected into every session automatically. **Medium complexity.**

### Tools
- `shell-exec-tool`: Agents have no shell access — severely limits automation. JavaClaw has ShellTools, OpenClaw has sandboxed openshell. Needs safe-binary allow-list. **Medium complexity.**
- `mcp-client`: No Model Context Protocol support. Both JavaClaw and OpenClaw have MCP clients for connecting external tool servers. MCP is becoming the industry standard for extending agent capabilities. **Medium complexity.**

### Channels
- `discord-channel`: Discord is widely used but JClaw only has Telegram, Slack, WhatsApp. JavaClaw has a Discord plugin via JDA. **Small complexity.**

## Medium Priority — Feature Parity

### LLM
- `token-usage-tracking`: No token counting or cost tracking. OpenClaw tracks input/output/cache tokens and estimated USD cost per call with daily charts and CSV export. **Medium complexity.**
- `model-override-per-session`: Can't switch models mid-conversation. OpenClaw supports runtime model overrides via slash commands. **Small complexity.**
- `vision-multimodal`: ChatMessage only supports text content. OpenClaw supports images alongside text for vision-capable models. **Large complexity.**
- `native-providers-gemini-ollama`: Only OpenAI-compatible providers. JavaClaw has native Google Gemini and Ollama provider modules. **Medium complexity per provider.**

### Channels
- `group-dm-policies`: No access control for who can message bots. OpenClaw has dmPolicy (allowlist, pairing, open) and groupPolicy per channel. **Small complexity.**
- `multi-account-channels`: Single ChannelConfig per channel type. OpenClaw supports multiple bot accounts per channel type. **Medium complexity.**
- `media-attachments`: Channels are text-only. OpenClaw has a full media pipeline for images, audio, files. **Large complexity.**

### UI
- `usage-analytics-dashboard`: No token/cost analytics. OpenClaw has mosaic charts, cost breakdown by provider/model, session-level detail. Requires token tracking first. **Medium complexity.**
- `conversation-search`: Conversations page has no search or filtering. **Small complexity.**
- `chat-slash-commands`: No slash commands in web chat (/new, /reset, /model). OpenClaw has a full slash-command executor. **Small complexity.**

### Security
- `security-audit-system`: No startup security audit. OpenClaw checks for dangerous config flags, filesystem permissions, weak auth, SSRF exposure. **Medium complexity.**
- `tool-execution-approvals`: All tool calls execute immediately. OpenClaw has a UI panel for reviewing and approving/denying pending executions (critical for shell exec). **Large complexity.**
- `rate-limiting`: No HTTP rate limiting. **Small complexity.**
- `secrets-management`: API keys stored in DB. OpenClaw resolves secrets from env vars/keychain at runtime, never stored in config file. **Medium complexity.**

### Agent
- `agent-heartbeat`: No periodic self-directed agent activity. OpenClaw supports cron-scheduled heartbeats that inject events into sessions for autonomous background work. **Medium complexity.**
- `sub-agent-spawning`: No sub-agent concept. OpenClaw has full sub-agent lifecycle (spawn, steer, kill, status tracking) via ACP protocol. **Large complexity.**
- `brave-web-search`: No general web search API. Both JavaClaw and OpenClaw have Brave Search integration. **Small complexity.**

### Infrastructure
- `health-check-enriched`: /api/status is minimal. Should include DB connectivity, provider reachability, memory store status. **Small complexity.**
- `opentelemetry-export`: No metrics/traces export. OpenClaw exports OTLP/Protobuf to any OTel-compatible backend. **Medium complexity.**

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

## Implementation Lessons (from v0.1.0 and v0.2.0)
- Virtual threads require explicit JPA transaction context (`Tx.run` helper) — Play's thread-local JPA is not inherited
- Play 1.x ECJ compiler requires `java.source=25` in `application.conf` for JDK 25 features
- Play's `unauthorized()` sends 401 with WWW-Authenticate header triggering browser basic auth — use 403 with JSON for API endpoints
- Sealed interfaces with nested records don't work in ECJ — use separate files for sealed type hierarchies
- Large tool results (50KB+) can stall LLM processing for minutes — extract text or auto-save to workspace instead of passing through context
- Playwright driver-bundle is 191MB — manage via Ivy (dependencies.yml), gitignore lib/
- Per-conversation queue locks must use try/finally to prevent deadlock on error
- GitHub rejects files >100MB — use Ivy dependency management, not committed JARs
