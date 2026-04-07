## Why

JClaw aims to be a Java-first AI automation platform that combines OpenClaw's agent orchestration and JavaClaw's job scheduling into a single Play Framework 1.x application with a Nuxt 3 admin frontend. The existing OpenClaw (TypeScript/Node.js) and JavaClaw (Spring Boot) implementations prove the concept works — but require heavy runtimes and complex dependency stacks. JClaw ports these capabilities to Play 1.x with minimal dependencies, leveraging JDK 25's built-in HTTP client and Play's native JPA/Job infrastructure. The immediate goal is a fully functional platform that connects to Telegram, Slack, and WhatsApp with a web-based admin UI for direct LLM chat and system management.

## What Changes

- **New LLM client**: Hand-rolled OpenAI-compatible HTTP client using `java.net.http.HttpClient` (JDK 25) and Gson. Supports streaming SSE, tool/function calling. Two providers: Ollama Cloud and OpenRouter (both OpenAI-compatible, one implementation class).
- **New agent system**: Multi-agent support with database-stored agent configurations and filesystem-based workspace (AGENT.md, IDENTITY.md, USER.md, skills/). 3-tier routing (peer → channel → default) via bindings table.
- **New channel integrations**: Telegram, Slack, WhatsApp — all via raw HTTP (no SDKs). Webhook-based inbound via Play controllers. HMAC signature verification for Slack and WhatsApp.
- **New web chat**: Nuxt 3 frontend with SSE streaming for direct LLM conversations. Separate from external channel conversations.
- **New admin UI**: 9-page Nuxt 3 SPA — Dashboard, Chat, Channels, Conversations, Tasks, Agents, Skills, Settings, Logs.
- **New persistence layer**: Fully normalized JPA schema (Agents, Conversations, Messages, ChannelConfigs, Tasks, Config, EventLog). H2 for dev/test, PostgreSQL for production.
- **New memory system**: Pluggable `MemoryStore` interface. JPA backend (default) with text search and optional pgvector for hybrid semantic+text search in PostgreSQL. Neo4j backend as opt-in alternative with its own rich feature set.
- **New tool system**: 5 core tools — TaskTool (DB-backed scheduling), CheckListTool (structured progress), FileSystemTools (workspace read/write), WebFetchTool (HTTP fetch), SkillsTool (SKILL.md loading). LLM-driven skill matching.
- **New task scheduling**: Tasks table with Play `@Every` poller. Supports immediate, scheduled (datetime), and recurring (CRON) tasks. Retry with exponential backoff. No JobRunr dependency.
- **New configuration**: Hybrid — `application.conf` for infrastructure (DB, memory backend, admin credentials), database for runtime config (providers, channels, agents, bindings) editable from admin UI.
- **New auth**: Single admin user with username/password in `application.conf`, session-based.
- **New error handling**: 4 layers — LLM retry with provider failover, channel delivery retry with failed message persistence, tool execution catch-and-return, EventLog table for all events.
- **New logging**: EventLog table (queryable from /logs page) + SLF4J dual output. 30-day retention with daily cleanup job.
- **New deployment model**: Play and Nuxt as separate processes. Nginx reverse proxy for SSL and routing. `play dist` packages both frontend and backend.

## Capabilities

### New Capabilities
- `llm-client`: OpenAI-compatible HTTP client with streaming, tool calling, provider failover (Ollama Cloud + OpenRouter)
- `agent-system`: Multi-agent configuration, workspace management, system prompt assembly from markdown files, LLM-driven skill matching
- `agent-routing`: 3-tier message routing (peer → channel → default) with database-stored bindings
- `channel-telegram`: Telegram Bot API integration via raw HTTP — webhook inbound, message sending, secret token verification
- `channel-slack`: Slack Events API + Web API integration via raw HTTP — webhook inbound, HMAC-SHA256 verification, message sending
- `channel-whatsapp`: WhatsApp Cloud API (Meta) integration via raw HTTP — webhook inbound, HMAC-SHA256 verification, challenge-response, message sending
- `channel-web`: Web chat via Nuxt 3 frontend with SSE streaming for LLM responses
- `memory-system`: Pluggable MemoryStore interface with store/search/delete/list operations. JPA backend (text search, pgvector opt-in) and Neo4j backend (opt-in, own config)
- `tool-system`: Tool interface and execution framework. Core tools: TaskTool, CheckListTool, FileSystemTools, WebFetchTool, SkillsTool. Tools return error strings on failure (never crash the agent)
- `task-scheduling`: Database-backed task system with Play @Every poller. Immediate, scheduled, and CRON recurring tasks. Retry with exponential backoff
- `admin-ui`: Nuxt 3 SPA with 9 pages — Dashboard, Chat, Channels, Conversations, Tasks, Agents, Skills, Settings, Logs. Tailwind CSS styling
- `admin-auth`: Single admin user authentication with session-based login
- `config-system`: Hybrid configuration — application.conf for boot-time infrastructure, database for runtime config editable from admin UI
- `event-logging`: EventLog table with category/level filtering, dual output to DB and SLF4J, 30-day retention, daily cleanup
- `deployment`: Play + Nuxt as separate processes, Nginx reverse proxy, play dist packaging

### Modified Capabilities
<!-- No existing specs to modify — this is a greenfield implementation -->

## Future Capabilities (v0.2.0+)

Gaps identified from OpenClaw/JavaClaw audit — not in v0.1.0 scope but needed for production parity:

### High Priority
- `tool-playwright`: Browser automation via Playwright for JS-heavy pages. JavaClaw uses `PlaywrightBrowserTool` with navigate, click, fill, extract actions. Needed because `web_fetch` can only handle static HTML — SPAs and dynamic content require a real browser engine.
- `concurrent-message-handling`: Queue or interrupt modes for handling multiple simultaneous messages to the same conversation. OpenClaw supports queue mode (buffer and process in order) and interrupt mode (cancel in-flight, process new). Currently JClaw has no concurrency control — two simultaneous messages could interleave and corrupt conversation state.

### Medium Priority
- `media-messages`: Handle non-text messages (images, files, audio, video) from channels. Currently silently ignored. OpenClaw has a full media pipeline; JavaClaw supports Telegram photos.
- `onboarding-wizard`: Guided first-run experience in the admin UI — step-by-step provider setup, API key entry, agent creation. JavaClaw has a multi-step onboarding flow. Currently JClaw seeds defaults but requires manual navigation through Settings and Agents pages.
- `multi-account-channels`: Support multiple accounts per channel type (e.g., multiple Telegram bots, multiple Slack workspaces). OpenClaw supports this via account IDs in bindings. Currently JClaw has one ChannelConfig per channel type.

### Deferred (captured in design Non-Goals)
- `memory-auto-capture`: Automatic extraction of facts from conversations after each turn. OpenClaw uses an LLM-based pipeline during session compaction to decompose conversations into atomic memories. Without this, the agent only remembers what it's explicitly told to save via the memory_save tool.
- `channel-dm-policy`: Access control for who can message the bot via external channels (Telegram, Slack, WhatsApp). OpenClaw supports pairing (verification code), allowlist (specific user IDs), and open modes. JavaClaw uses username whitelists. Currently JClaw responds to any sender.
- `session-compaction`: Auto-summarize older messages when context window fills up
- `sub-agents`: Spawn child agents for delegated tasks
- `tool-brave-search`: Web search via Brave API
- `tool-mcp`: Model Context Protocol server integration
- `multi-user-auth`: Multiple users with role-based access control
- `slack-socket-mode`: WebSocket-based Slack events (no public URL needed)
- `media-generation`: Image, video, and music generation tools

### Implementation Lessons (discovered during v0.1.0)
- Virtual threads require explicit JPA transaction context (`Tx.run` helper) — Play's thread-local JPA is not inherited
- Play 1.x ECJ compiler requires `java.source=25` in `application.conf` for JDK 25 features
- Play's `unauthorized()` sends 401 with WWW-Authenticate header triggering browser basic auth — use 403 with JSON for API endpoints
- Sealed interfaces with nested records don't work in ECJ — use separate files for sealed type hierarchies

## Impact

- **Backend**: All new Java code in `app/controllers/`, `app/models/`, `app/services/`, `app/agents/`, `app/jobs/`, `app/channels/`, `app/tools/`
- **Frontend**: All new pages, components, composables, and layouts in `frontend/`
- **Configuration**: Significant additions to `conf/application.conf` and `conf/routes`
- **Dependencies**: `conf/dependencies.yml` gains PostgreSQL JDBC driver and Neo4j Java driver (opt-in). No other third-party libraries.
- **Database**: New JPA entities create tables automatically via Hibernate DDL. Production PostgreSQL requires manual setup (database creation, optional pgvector extension).
- **Filesystem**: New `workspace/` directory structure for agent workspaces, skills, and identity files.
- **Deployment**: Nginx configuration required for production. Webhook URLs must be registered with each messaging platform.
