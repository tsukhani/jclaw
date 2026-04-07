## 1. tool-playwright: Browser Automation

### Setup
- [ ] 1.1 Download Playwright JAR (com.microsoft.playwright:playwright:1.52.0) and transitive dependencies to lib/
- [ ] 1.2 Add `jclaw.tools.playwright.enabled` and `jclaw.tools.playwright.headless` properties to application.conf
- [ ] 1.3 Create `PlaywrightBrowserTool` implementing `ToolRegistry.Tool` with action dispatch

### Browser Lifecycle
- [ ] 1.4 Implement lazy browser initialization with `Playwright.create()` → `chromium().launch()` → `newPage()`
- [ ] 1.5 Implement `BrowserSession` storage in `ConcurrentHashMap<String, BrowserSession>` keyed by agent name
- [ ] 1.6 Implement auto-install of Chromium binary via `Playwright.CLI.main(["install", "chromium"])` on first access
- [ ] 1.7 Implement idle timeout cleanup (5-minute inactivity → close session)

### Actions
- [ ] 1.8 Implement `navigate(url)` — navigate + waitForLoadState + return title + extracted text (50K limit)
- [ ] 1.9 Implement `click(selector)` — click element + waitForLoadState + return updated page state
- [ ] 1.10 Implement `fill(selector, value)` — fill input field + return confirmation
- [ ] 1.11 Implement `getText(selector)` — extract text from element or full page body
- [ ] 1.12 Implement `screenshot()` — full page PNG saved to workspace/{agent}/screenshot.png
- [ ] 1.13 Implement `evaluate(expression)` — execute JS in page context, return result as string
- [ ] 1.14 Implement `close()` — graceful browser shutdown

### Registration & Security
- [ ] 1.15 Register `PlaywrightBrowserTool` conditionally in `ToolRegistrationJob` based on config property
- [ ] 1.16 Disable browser tool by default for non-main agents via `AgentToolConfig`
- [ ] 1.17 Ensure isolated browser contexts per agent (no cookie/session sharing)

### Testing
- [ ] 1.18 Write unit tests for action parameter parsing and dispatch
- [ ] 1.19 Write integration test: navigate to local test page, extract text
- [ ] 1.20 Write test: browser session cleanup on idle timeout

## 2. concurrent-message-handling: Message Queue

### Core Queue System
- [ ] 2.1 Create `ConversationQueue` service with `ConcurrentHashMap<Long, QueueState>`
- [ ] 2.2 Implement `tryAcquire(conversationId, message)` — lock or enqueue
- [ ] 2.3 Implement `drain(conversationId)` — return next message(s) after processing completes
- [ ] 2.4 Implement queue cap (20 messages) with drop-oldest policy
- [ ] 2.5 Implement queue cleanup for empty/stale queues

### Queue Modes
- [ ] 2.6 Implement `queue` mode — FIFO, process one at a time
- [ ] 2.7 Implement `collect` mode — batch pending messages into single prompt
- [ ] 2.8 Implement `interrupt` mode — cancel in-flight, process new message
- [ ] 2.9 Add per-agent queue mode config via Config table (`agent.{name}.queue.mode`)

### Integration
- [ ] 2.10 Integrate queue into `ApiChatController.streamChat()` — return SSE queued event if busy
- [ ] 2.11 Integrate queue into `WebhookTelegramController` — queue message if agent busy
- [ ] 2.12 Integrate queue into `WebhookSlackController` — queue message if agent busy
- [ ] 2.13 Integrate queue into `WebhookWhatsAppController` — queue message if agent busy
- [ ] 2.14 Add drain call after `AgentRunner` completion in all paths (with try/finally)

### Frontend
- [ ] 2.15 Add "processing..." indicator in chat UI when agent is busy
- [ ] 2.16 Show queue count badge when messages are waiting
- [ ] 2.17 Add queue mode selector to agent configuration page

### Testing
- [ ] 2.18 Write unit tests for queue acquire/drain/interrupt mechanics
- [ ] 2.19 Write concurrency test: 10 virtual threads, same conversation, verify serialization
- [ ] 2.20 Write test: collect mode batches multiple messages correctly
- [ ] 2.21 Write test: interrupt mode cancels in-flight and processes new
