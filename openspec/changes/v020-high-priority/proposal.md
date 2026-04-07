## Why

JClaw v0.1.0 is functionally complete for static web content, basic agent workflows, and channel integrations. Two gaps block production readiness:

1. **JS-heavy pages are invisible to agents.** The `web_fetch` tool uses JDK HttpClient which only retrieves static HTML. Single-page applications (React, Vue, Angular), dynamically loaded content, and pages requiring authentication are inaccessible. JavaClaw solves this with Playwright browser automation; OpenClaw falls back to Firecrawl. Without this, agents can't interact with modern web applications.

2. **Concurrent messages corrupt conversation state.** When two messages arrive simultaneously for the same conversation (e.g., user sends a follow-up before the first response completes), both virtual threads append messages and call the LLM concurrently against the same conversation history. This can interleave messages, produce duplicate responses, or corrupt the message ordering. OpenClaw handles this with a sophisticated queue system supporting 7 modes; JavaClaw uses Spring's request serialization.

## What Changes

### tool-playwright: Browser Automation Tool

A new `PlaywrightBrowserTool` registered alongside existing tools, providing headless Chromium browser access to agents. Modeled after JavaClaw's implementation (`com.microsoft.playwright:playwright:1.52.0`).

**Actions:**
- `navigate(url)` — Navigate to URL, wait for load, return page title + extracted text
- `click(selector)` — Click element by CSS selector
- `fill(selector, value)` — Fill input/textarea fields
- `getText(selector)` — Extract text content from elements (use `body` for full page)
- `screenshot()` — Capture full-page PNG, save to workspace
- `evaluate(expression)` — Execute JavaScript in page context
- `close()` — Close browser session

**Browser lifecycle:**
- Lazy initialization on first use per agent
- Single page instance per agent session, reused across actions
- Headless Chromium by default (configurable via `jclaw.tools.playwright.headless`)
- Auto-install Chromium binary on first run via Playwright CLI
- Graceful shutdown when agent session ends or explicit `close()` action

**Security:**
- Browser tool disabled by default for non-main agents (same pattern as existing tool scoping via `AgentToolConfig`)
- Each agent gets an isolated browser context (no cookie/session sharing between agents)
- Configurable via `jclaw.tools.playwright.enabled=true` in application.conf

**Text extraction:** After navigation, extract text using Playwright's built-in `page.textContent("body")` with truncation at 50K chars — same limit as `web_fetch` text mode. This gives agents a consistent content size regardless of whether they use `web_fetch` or `browser`.

**Integration with web_fetch:** The LLM chooses which tool to use based on the URL and task:
- Static content, APIs, documents → `web_fetch` (faster, no browser overhead)
- SPAs, dynamic content, forms, login flows → `browser` (full JS rendering)

### concurrent-message-handling: Message Queue System

A per-conversation queue that serializes message processing, preventing state corruption when multiple messages arrive simultaneously.

**Modes (v0.2.0 scope — simplified from OpenClaw's 7 modes):**
- `queue` (default) — FIFO queue. Second message waits until first completes, then processes in order.
- `collect` — Batch. Accumulate messages while agent is busy, combine into single prompt when ready: `"[Queued messages while agent was busy]\n---\nQueued #1: ...\n---\nQueued #2: ..."`
- `interrupt` — Cancel in-flight response, start processing new message immediately.

**Implementation:**
- Global `ConcurrentHashMap<Long, ConversationQueue>` keyed by conversation ID
- `ConversationQueue` holds a `ReentrantLock`, queue of pending messages, and current mode
- Before `AgentRunner.run/runStreaming`, acquire the conversation lock
- If locked (agent already processing), enqueue the message based on mode
- After agent completes, drain the queue (process next or batch)
- Queue cap of 20 messages with drop-oldest policy
- Configurable per-agent via application.conf or database config

**Channels affected:** All channels (web chat, Telegram, Slack, WhatsApp) route through the queue before reaching `AgentRunner`. Web chat already has implicit serialization (user can't send while streaming), but external channels can deliver multiple messages rapidly.

**UI indicator:** Show a "processing..." badge in the chat sidebar when the agent is busy, and a queue count if messages are waiting.

## Capabilities

### New Capabilities
- `tool-playwright`: Headless Chromium browser automation for agents — navigate, click, fill, extract, screenshot, evaluate JS
- `concurrent-message-handling`: Per-conversation message queue with queue/collect/interrupt modes

### Modified Capabilities
- `agent-system`: Agents gain browser tool access (per-agent toggle). Queue mode configurable per agent.
- `channel-web`: Web chat shows processing/queue indicator
- `channel-telegram`: Messages queued when agent is busy
- `channel-slack`: Messages queued when agent is busy
- `channel-whatsapp`: Messages queued when agent is busy
- `admin-ui`: Browser tool toggle on agent edit page. Queue mode selector on agent config.

## Impact

- **Dependencies**: Add `com.microsoft.playwright:playwright` JAR to `lib/`. Chromium binary (~200MB) auto-installed on first use.
- **Backend**: New `PlaywrightBrowserTool` in `app/tools/`, new `ConversationQueue` in `app/services/`, modifications to `AgentRunner` and all webhook controllers.
- **Frontend**: Queue indicator in chat UI, browser tool toggle on agents page.
- **Configuration**: New `jclaw.tools.playwright.*` and `jclaw.queue.*` properties in application.conf.
- **Database**: No schema changes — tool scoping uses existing `AgentToolConfig`, queue mode uses existing `Config` table.
- **Risk**: Playwright binary download requires internet access on first run. Queue system changes the concurrency model for all channels — requires thorough testing with simultaneous messages.
