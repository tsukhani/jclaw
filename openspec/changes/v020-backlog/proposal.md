## Why

JClaw v0.1.0 and v0.2.0 high-priority features are complete. This proposal captures the remaining gaps identified during the OpenClaw/JavaClaw audit that are needed for full production parity.

## Remaining Capabilities

### Medium Priority
- `media-messages`: Handle non-text messages (images, files, audio, video) from channels. Currently silently ignored. OpenClaw has a full media pipeline; JavaClaw supports Telegram photos.
- `onboarding-wizard`: Guided first-run experience in the admin UI — step-by-step provider setup, API key entry, agent creation. JavaClaw has a multi-step onboarding flow. Currently JClaw seeds defaults but requires manual navigation through Settings and Agents pages.
- `multi-account-channels`: Support multiple accounts per channel type (e.g., multiple Telegram bots, multiple Slack workspaces). OpenClaw supports this via account IDs in bindings. Currently JClaw has one ChannelConfig per channel type.

### Deferred
- `memory-auto-capture`: Automatic extraction of facts from conversations after each turn. OpenClaw uses an LLM-based pipeline during session compaction to decompose conversations into atomic memories. Without this, the agent only remembers what it's explicitly told to save via the memory_save tool.
- `channel-dm-policy`: Access control for who can message the bot via external channels (Telegram, Slack, WhatsApp). OpenClaw supports pairing (verification code), allowlist (specific user IDs), and open modes. JavaClaw uses username whitelists. Currently JClaw responds to any sender.
- `session-compaction`: Auto-summarize older messages when context window fills up
- `sub-agents`: Spawn child agents for delegated tasks
- `tool-brave-search`: Web search via Brave API
- `tool-mcp`: Model Context Protocol server integration
- `multi-user-auth`: Multiple users with role-based access control
- `slack-socket-mode`: WebSocket-based Slack events (no public URL needed)
- `media-generation`: Image, video, and music generation tools

## Implementation Lessons (from v0.1.0 and v0.2.0)
- Virtual threads require explicit JPA transaction context (`Tx.run` helper) — Play's thread-local JPA is not inherited
- Play 1.x ECJ compiler requires `java.source=25` in `application.conf` for JDK 25 features
- Play's `unauthorized()` sends 401 with WWW-Authenticate header triggering browser basic auth — use 403 with JSON for API endpoints
- Sealed interfaces with nested records don't work in ECJ — use separate files for sealed type hierarchies
- Large tool results (50KB+) can stall LLM processing for minutes — extract text or auto-save to workspace instead of passing through context
- Playwright driver-bundle is 191MB — manage via Ivy (dependencies.yml), gitignore lib/
- Per-conversation queue locks must use try/finally to prevent deadlock on error
