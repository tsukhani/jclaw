## Context

JClaw v0.1.0 has 5 registered tools (task_manager, filesystem, web_fetch, checklist, skills) and processes messages sequentially per request thread. Two gaps need addressing for v0.2.0: JS-heavy page access and concurrent message safety.

**Source implementations:**
- **JavaClaw** (`~/Programming/javaclaw`): `PlaywrightBrowserTool` with 7 synchronized actions, lazy browser lifecycle, Spring auto-configuration
- **OpenClaw** (`~/Programming/openclaw-project`): 7 queue modes (steer, followup, collect, steer-backlog, queue, interrupt), per-session drain locks, deduplication cache, cross-channel batching

**Constraints:**
- Play 1.x conventions (static controller methods, `lib/` for JARs)
- Virtual threads for agent execution (need lock-compatible queue)
- Per-agent tool scoping via existing `AgentToolConfig` table
- Chromium binary must be downloadable at runtime (no build-time bundling)

## Goals / Non-Goals

**Goals:**
- Agents can navigate, interact with, and extract content from JS-heavy web pages
- Multiple messages to the same conversation are serialized safely
- Three queue modes: queue (FIFO), collect (batch), interrupt (cancel-and-replace)
- Browser tool disabled by default, opt-in per agent

**Non-Goals (v0.2.0):**
- Full OpenClaw queue feature parity (steer modes, cross-channel batching, debounce — defer to v0.3.0)
- Playwright screenshots as LLM input (vision/multimodal — defer to media-messages)
- Browser session persistence across conversations (stateless per agent run)
- Proxy/stealth mode for bot detection avoidance
- Browser pool for concurrent page access (one page per agent)

## Design

### tool-playwright

#### Architecture

```
Agent receives "log into dashboard and check stats"
  → LLM calls browser(action: "navigate", url: "https://app.example.com/login")
  → PlaywrightBrowserTool.execute() → lazy-init Chromium → page.navigate()
  → Returns: "Page title: Login | Text: Username [input] Password [input] Sign In [button]"
  → LLM calls browser(action: "fill", selector: "#username", value: "admin")
  → LLM calls browser(action: "fill", selector: "#password", value: "***")
  → LLM calls browser(action: "click", selector: "button[type=submit]")
  → Returns: "Page title: Dashboard | Text: Welcome back, admin. Active users: 142..."
  → LLM calls browser(action: "getText", selector: ".stats-panel")
  → Returns extracted stats
  → LLM responds to user with the dashboard data
```

#### Implementation

**New file:** `app/tools/PlaywrightBrowserTool.java`

- Implements `ToolRegistry.Tool`
- Single `execute(argsJson, agent)` method dispatching on `action` parameter
- Lazy browser initialization: `Playwright.create()` → `playwright.chromium().launch()` → `browser.newPage()`
- Browser instances stored in `ConcurrentHashMap<String, BrowserSession>` keyed by agent name
- `BrowserSession` record holds `Playwright`, `Browser`, `Page` references
- Session cleanup on agent inactivity (5-minute idle timeout via scheduled check)
- Text extraction after navigate: `page.textContent("body")` truncated to 50K chars
- Screenshot saved to `workspace/{agent}/screenshot.png` via `page.screenshot()`

**Registration:** Add to `ToolRegistrationJob.doJob()` conditionally:
```java
if ("true".equals(Play.configuration.getProperty("jclaw.tools.playwright.enabled", "false"))) {
    ToolRegistry.register(new PlaywrightBrowserTool());
}
```

**Dependency:** Download `com.microsoft.playwright:playwright:1.52.0` JAR + transitive dependencies to `lib/`. Chromium binary auto-installed via `Playwright.CLI.main(["install", "chromium"])` on first access.

#### Tool Parameters

```json
{
  "type": "object",
  "properties": {
    "action": {"type": "string", "enum": ["navigate", "click", "fill", "getText", "screenshot", "evaluate", "close"]},
    "url": {"type": "string", "description": "URL to navigate to (for navigate action)"},
    "selector": {"type": "string", "description": "CSS selector (for click, fill, getText actions)"},
    "value": {"type": "string", "description": "Value to fill (for fill action)"},
    "expression": {"type": "string", "description": "JavaScript expression (for evaluate action)"}
  },
  "required": ["action"]
}
```

### concurrent-message-handling

#### Architecture

```
Message 1 arrives for conversation 42
  → ConversationQueue.acquire(42) → lock acquired → process with AgentRunner
Message 2 arrives for conversation 42 (while Message 1 is processing)
  → ConversationQueue.acquire(42) → lock busy → enqueue Message 2
Message 1 completes
  → ConversationQueue.drain(42) → dequeue Message 2 → process with AgentRunner
Message 2 completes
  → ConversationQueue.drain(42) → queue empty → release
```

#### Implementation

**New file:** `app/services/ConversationQueue.java`

```java
public class ConversationQueue {

    private static final ConcurrentHashMap<Long, QueueState> queues = new ConcurrentHashMap<>();
    private static final int MAX_QUEUE_SIZE = 20;

    public record QueuedMessage(String text, String channelType, String peerId, Agent agent) {}

    private static class QueueState {
        final ReentrantLock lock = new ReentrantLock();
        final ArrayDeque<QueuedMessage> pending = new ArrayDeque<>();
        volatile String mode = "queue"; // queue, collect, interrupt
    }

    /**
     * Try to acquire the conversation for processing.
     * Returns true if acquired (caller should process), false if queued.
     */
    public static boolean tryAcquire(Long conversationId, QueuedMessage message) { ... }

    /**
     * Called after processing completes. Returns the next message(s) to process,
     * or null if queue is empty.
     */
    public static List<QueuedMessage> drain(Long conversationId) { ... }

    /**
     * For interrupt mode: cancel current processing and return the interrupting message.
     */
    public static QueuedMessage interrupt(Long conversationId, QueuedMessage newMessage) { ... }
}
```

**Integration points:**

1. **ApiChatController.streamChat()** — Before launching the streaming virtual thread, call `ConversationQueue.tryAcquire()`. If false, return an SSE event `{"type": "queued", "position": N}`.

2. **Webhook controllers** — Before calling `AgentRunner.run()`, call `tryAcquire()`. If false, send a "message received, processing shortly" reply.

3. **AgentRunner completion** — After `onComplete` or `onError`, call `ConversationQueue.drain()` and process next message if present.

**Collect mode batching:** When draining in collect mode, combine all pending messages:
```
[Queued messages while agent was busy]

---
Message 1 (from admin at 14:32:01):
What's the status?

---
Message 2 (from admin at 14:32:15):
Also check the error logs
```

**Configuration:** Queue mode per agent stored in `Config` table as `agent.{name}.queue.mode`. Default: `queue`.

## Risks

- **Playwright binary size**: ~200MB Chromium download on first use. Must handle download failures gracefully.
- **Browser memory**: Each active browser session uses ~100-200MB RAM. Need idle timeout cleanup.
- **Queue deadlock**: If `AgentRunner` throws without calling drain, the queue blocks forever. Must use try/finally pattern.
- **Interrupt mode data loss**: Cancelling an in-flight LLM call may leave partial messages in the conversation. Need to mark incomplete responses.

## Testing Strategy

**tool-playwright:**
- Unit test: Tool parameter parsing, action dispatch
- Integration test: Navigate to a local test page, extract text, click element
- Manual test: Navigate to a JS-heavy SPA, verify content extraction

**concurrent-message-handling:**
- Unit test: Queue acquire/drain/interrupt mechanics
- Concurrency test: 10 virtual threads sending messages to same conversation, verify serialization
- Integration test: Two rapid messages via web chat, verify ordered processing
- Manual test: Send 3 messages quickly in Telegram, verify queue behavior
