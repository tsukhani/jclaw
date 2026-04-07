## Context

JClaw v0.1.0-alpha. Play 1.x runs each HTTP request inside a JPA-managed transaction on the request thread. A virtual-thread pool (via `Thread.ofVirtual()`) handles streaming agent runs, using `services.Tx.run()` to scope individual JPA operations. A connection pool backs all JPA access but has no explicit configuration.

**Source implementations referenced:**

- `app/agents/AgentRunner.java` — `run()` lines 30–83: executes synchronously on the Play request thread inside Play's open JPA transaction; `callWithToolLoop()` lines 217–277 holds the transaction (and JDBC connection) throughout all LLM HTTP calls and tool persistence; `handleToolCallsStreaming()` lines 279–335 already uses `services.Tx.run()` per tool interaction (correct pattern)
- `app/services/ConversationService.java` — `appendMessage()` lines 32–59: contains a `JPA.em().merge()` workaround at lines 35–37 to re-attach detached entities when called across `Tx.run()` boundaries on virtual threads
- `app/services/ConversationQueue.java` — `QueueState` inner class lines 27–46: contains a dead `ReentrantLock lock` field (line 28) never used; `tryAcquire()` lines 52–84: writes `state.mode` at line 56 without synchronization, calls `state.pending.clear()` at line 65 without `synchronized(state)`; `drain()` lines 93–116: calls `state.finishProcessing()` at line 97 outside the `synchronized(state)` block that begins at line 99; `getQueueSize()` lines 134–137: reads `state.pending.size()` with no synchronization
- `app/controllers/ApiChatController.java` — `streamChat()` lines 65–143: `onInit` catch at lines 95–97 and `onToken` catch at lines 104–106 silently swallow `writeChunk()` exceptions with no cancellation signal; `latch.await(600, ...)` at line 132
- `app/controllers/ApiBindingsController.java` — `list()` lines 21–27: calls `AgentBinding.findAll()` then accesses `b.agent.id` and `b.agent.name` in `bindingToMap()` at lines 77–78
- `app/controllers/ApiTasksController.java` — `list()` lines 18–46: calls `Task.find(...)` then accesses `t.agent.id` and `t.agent.name` in `taskToMap()` at lines 87–89; `Task.agent` is nullable (`@ManyToOne` without `optional = false`, line 18 of `Task.java`)
- `app/controllers/ApiChatController.java` — `listConversations()` lines 148–185: calls `Conversation.find(...)` then accesses `c.agent.id` and `c.agent.name` at lines 172–173
- `app/memory/JpaMemoryStore.java` — `fullTextSearch()` lines 87–115: opens `DB.getConnection()` at line 96 (second pool connection while JPA already holds one), then loops `Memory.findById(id)` per ID at lines 107–110; `hybridSearch()` lines 118–162: same `DB.getConnection()` at line 139, same per-ID loop at lines 148–152
- `app/models/AgentBinding.java` — `@Table` annotation lines 7–9: index on `channel_type,peer_id` only; `agent_id` column (line 14) has no standalone index
- `conf/application.conf` — no `db.pool.*` settings anywhere; Play 1.x defaults to a small pool (~5–10 connections)
- `frontend/tailwind.config.js` — `content: []` at line 3; with `@nuxtjs/tailwindcss`, an explicit empty array overrides the module's automatic path injection, disabling purge entirely

**Constraints:**

- Play 1.x static controller methods; `services.Tx.run()` is the established pattern for JPA on virtual threads
- `SystemPromptAssembler.assemble()` calls `MemoryStoreFactory.get()` → `store.search()` in `appendMemories()`, which hits JPA, and must remain inside a `Tx.run()` call
- `llm.ProviderRegistry.get()` and `getPrimary()` call `refreshIfNeeded()` → `refresh()` → `ConfigService.listAll()` (JPA); provider resolution must also be inside a `Tx.run()` call
- `AgentRunner.runStreaming()` already proves the correct pattern: short `Tx.run()` blocks for all JPA touches, LLM HTTP work outside any transaction; `run()` must be brought to parity
- No schema migrations — only additive DDL (one new index, applied by Hibernate `jpa.ddl=update`)

## Goals / Non-Goals

**Goals:**

- Release the JDBC connection from the pool during LLM HTTP calls in the synchronous agent path (`AgentRunner.run()`)
- Propagate client disconnect from SSE callbacks into the virtual thread so it stops LLM/tool work early
- Configure an explicit connection pool to survive virtual-thread concurrency
- Fix three concurrency bugs in `ConversationQueue`: unsynchronized `clear()`, unsynchronized `size()`, and the mode race / drain window
- Eliminate N+1 queries from `AgentBinding.list()`, `listConversations()`, `Task.list()`, and `JpaMemoryStore` search methods
- Fix Tailwind CSS purge so production bundles only include used utilities

**Non-Goals:**

- No new features, endpoints, or UI changes
- No schema migrations (one additive index only via Hibernate DDL auto-update)
- No changes to streaming happy-path logic (tool calls, retry, context trimming) — only transaction scoping and cancellation wiring
- No changes to `ConversationQueue` modes or batching semantics

## Design

### 1. fix-sync-transaction-scope

#### Architecture

Before (current `run()` on the Play request thread):

```
HTTP request thread  [Play JPA transaction open — JDBC connection held]
  AgentRunner.run()
    ├─ ConversationService.appendUserMessage()            [JPA write]
    ├─ SystemPromptAssembler.assemble()                   [JPA read via MemoryStoreFactory]
    ├─ buildMessages()                                    [JPA read]
    ├─ ProviderRegistry.get() / getPrimary() / listAll()  [JPA read via ConfigService]
    ├─ ToolRegistry.getToolDefsForAgent()                 [JPA read]
    └─ callWithToolLoop()   ←── JDBC connection held through everything below
         ├─ OpenAiCompatibleClient.chat()                 [blocking HTTP, up to 180s × 10 rounds]
         ├─ ConversationService.appendAssistantMessage()  [JPA write — still holding conn]
         └─ ConversationService.appendToolResult()        [JPA write — still holding conn]
```

After (target — mirrors `runStreaming()` pattern):

```
HTTP request thread
  AgentRunner.run()
    └─ Tx.run() [SHORT — setup only]
         ├─ appendUserMessage()
         ├─ SystemPromptAssembler.assemble()
         ├─ buildMessages()
         ├─ ProviderRegistry.get() / getPrimary()
         ├─ ToolRegistry.getToolDefsForAgent()
         └─ returns PreparedData(messages, primary, secondary, tools)
    [transaction closed — JDBC connection returned to pool]

    └─ callWithToolLoop(agent, conversationId, ...)   [no JPA context]
         ├─ OpenAiCompatibleClient.chat()             [HTTP — pool conn FREE]
         └─ per tool call:
              └─ Tx.run() [SHORT] ── findById(conversationId)
                                     appendAssistantMessage(toolCall)
                                     appendToolResult(toolCall.id, result)

    └─ Tx.run() [SHORT] ── findById(conversationId), appendAssistantMessage(finalResponse)
```

#### Implementation

**`app/agents/AgentRunner.java`**

Add a private record after the `RunResult` record (line 25):

```java
private record PreparedData(
    List<ChatMessage> messages,
    ProviderConfig primary,
    ProviderConfig secondary,
    List<ToolDef> tools
) {}
```

Replace `run()` (lines 30–83) with the following:

```java
public static RunResult run(Agent agent, Conversation conversation, String userMessage) {
    var queueMsg = new services.ConversationQueue.QueuedMessage(
            userMessage, conversation.channelType, conversation.peerId, agent);
    if (!services.ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
        return new RunResult("Your message has been queued and will be processed shortly.", conversation);
    }

    final Long conversationId = conversation.id;

    try {
        // Short setup transaction: all JPA reads + user message write
        var prepared = services.Tx.run(() -> {
            ConversationService.appendUserMessage(conversation, userMessage);

            var assembled = SystemPromptAssembler.assemble(agent, userMessage);
            var messages = buildMessages(assembled.systemPrompt(), conversation);

            var primary = ProviderRegistry.get(agent.modelProvider);
            if (primary == null) primary = ProviderRegistry.getPrimary();
            if (primary == null) {
                var error = "No LLM provider configured. Add provider config via Settings.";
                EventLogger.error("llm", agent.name, null, error);
                ConversationService.appendAssistantMessage(conversation, error, null);
                return null; // signals early exit
            }
            var secondary = ProviderRegistry.getSecondary();

            var trimmed = trimToContextWindow(messages, agent, primary);
            var tools = ToolRegistry.getToolDefsForAgent(agent);

            EventLogger.info("llm", agent.name, conversation.channelType,
                    "Calling %s / %s".formatted(primary.name(), agent.modelId));

            return new PreparedData(trimmed, primary, secondary, tools);
        });

        if (prepared == null) {
            var error = "No LLM provider configured. Add provider config via Settings.";
            return new RunResult(error, services.Tx.run(() -> ConversationService.findById(conversationId)));
        }

        // LLM call loop — no transaction open, JDBC connection back in pool
        var response = callWithToolLoop(agent, conversationId,
                prepared.messages(), prepared.tools(), prepared.primary(), prepared.secondary());

        // Short persistence transaction: final assistant message
        services.Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            ConversationService.appendAssistantMessage(conv, response, null);
        });

        EventLogger.info("llm", agent.name, conversation.channelType,
                "Response generated (%d chars)".formatted(response.length()));

        var updatedConversation = services.Tx.run(() -> ConversationService.findById(conversationId));
        return new RunResult(response, updatedConversation);

    } finally {
        services.ConversationQueue.drain(conversationId);
    }
}
```

Change `callWithToolLoop` to accept `Long conversationId` instead of `Conversation conversation`:

```java
private static String callWithToolLoop(Agent agent, Long conversationId,
                                        List<ChatMessage> messages, List<ToolDef> tools,
                                        ProviderConfig primary, ProviderConfig secondary) {
    // ... (loop structure unchanged) ...

    // Replace the tool-persistence block (currently lines 269–272):
    services.Tx.run(() -> {
        var conv = ConversationService.findById(conversationId);
        ConversationService.appendAssistantMessage(conv, null, gson.toJson(toolCall));
        ConversationService.appendToolResult(conv, toolCall.id(), result);
    });
    // ...
}
```

**`app/services/ConversationService.java`**

After the `run()` refactor, `appendMessage()` is always called from inside a `Tx.run()` block where the `Conversation` entity is freshly loaded by ID within the same transaction. Remove the merge workaround at lines 35–37:

```java
// Remove these three lines:
if (!JPA.em().contains(conversation)) {
    conversation = JPA.em().merge(conversation);
}
```

---

### 2. fix-db-pool-and-disconnect

#### Architecture

**2a — Connection pool configuration** is a configuration-only change. Without explicit settings, Play 1.x uses BoneCP defaults (typically 5–10 connections). With virtual-thread concurrency, even after fix 1 eliminates the transaction-during-HTTP problem, `EventLogger` per-call transactions, `JpaMemoryStore` operations, and task poller all compete for the same pool.

**2b — SSE disconnect propagation:**

Before (current `streamChat()`):

```
virtual thread running runStreaming()
  onToken callback: writeChunk() throws (client disconnected)
    └─ exception swallowed at lines 104–106
    └─ virtual thread continues: more LLM rounds, tool calls, DB writes — all wasted

controller thread: latch.await(600s) — blocks for up to 10 minutes
```

After:

```
AtomicBoolean cancelled shared between controller callbacks and virtual thread

onToken / onInit: writeChunk() throws
  → cancelled.set(true), latch.countDown()

runStreaming(): checks cancelled.get() before each major phase → returns early

Heartbeat ScheduledExecutorService: fires every 30s
  → writes ": keep-alive\n\n"
  → if writeChunk() throws → cancelled.set(true), latch.countDown()

controller thread: latch.await(180s) — matches LLM timeout, not 600s
  → heartbeatExecutor.shutdownNow() in finally block
```

#### Implementation

**`conf/application.conf`**

Add immediately after the existing `jpa.ddl=update` line (line 87):

```
# Connection pool
db.pool.minSize=5
db.pool.maxSize=20
db.pool.timeout=10000

%prod.db.pool.maxSize=30
%prod.db.pool.timeout=5000
```

**`app/controllers/ApiChatController.java`**

Refactor `streamChat()` (lines 65–143). Add `AtomicBoolean cancelled` and a heartbeat scheduler. Change the `onToken` and `onInit` catch blocks to set the cancellation flag and release the latch. Add the cancellation parameter to the `runStreaming()` call. Replace `latch.await(600, ...)` with `latch.await(180, ...)`. Shut down the heartbeat in `finally`:

```java
public static void streamChat() {
    // lines 67–85 unchanged (body parsing, agent lookup, SSE headers)

    var latch = new CountDownLatch(1);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

    var heartbeatExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().unstarted(r));
    heartbeatExecutor.scheduleAtFixedRate(() -> {
        if (cancelled.get()) return;
        try {
            res.writeChunk(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            cancelled.set(true);
            latch.countDown();
        }
    }, 30, 30, TimeUnit.SECONDS);

    AgentRunner.runStreaming(agent, conversationId, "web", username, messageText,
            cancelled,                    // NEW parameter
            conversation -> {
                try {
                    var initEvent = gson.toJson(Map.of("type", "init", "conversationId", conversation.id));
                    res.writeChunk("data: %s\n\n".formatted(initEvent).getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    cancelled.set(true);   // FIX: was silently swallowed
                    latch.countDown();
                }
            },
            token -> { ... },             // same pattern: cancelled.set(true) + latch.countDown()
            content -> { ... },           // unchanged (latch.countDown() in finally)
            error -> { ... }              // unchanged (latch.countDown() in finally)
    );

    try {
        if (!latch.await(180, TimeUnit.SECONDS)) {  // FIX: was 600
            // ... timeout error event ...
        }
    } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
    } finally {
        heartbeatExecutor.shutdownNow();
    }
}
```

**`app/agents/AgentRunner.java`**

Add `AtomicBoolean isCancelled` as a new parameter to `runStreaming()`. Add cancellation checks before each major phase:

1. After `onInit.accept(conversation)`
2. After `SystemPromptAssembler.assemble()`
3. After `buildMessages()`
4. After provider resolution
5. After `accumulator.awaitCompletion()` (initial and retry)
6. After `handleToolCallsStreaming()` returns, before final persist

Each check:
```java
if (isCancelled.get()) {
    EventLogger.info("llm", agent.name, channelType, "Stream cancelled by client disconnect");
    return;
}
```

Pass `isCancelled` through to `handleToolCallsStreaming()` as an additional parameter. Check at the start of each tool round.

---

### 3. fix-conversation-queue-safety

#### Architecture

Three bugs in the current implementation:

**Bug 1 — Unsynchronized `clear()` at line 65:** The interrupt-mode path calls `state.pending.clear()` directly, outside any monitor. `drain()` calls `state.pending.pollFirst()` inside `synchronized(state)`. `ArrayDeque` is not thread-safe — concurrent structural modification corrupts its internal circular array.

**Bug 2 — Unsynchronized `size()` at line 135 in `getQueueSize()`:** `ArrayDeque.size()` reads from a field that can be modified concurrently.

**Bug 3 — Mode race and drain window:**
- `state.mode` is written at line 56 without holding the state monitor. Concurrent `tryAcquire()` calls overwrite each other's mode.
- `drain()` calls `state.finishProcessing()` at line 97 (releasing the processing flag) and then enters `synchronized(state)` at line 99. Between lines 97 and 99, another `tryAcquire()` can succeed, and `drain()` then removes that message.

**Dead field:** `QueueState.lock` (line 28, `ReentrantLock`) is declared but never referenced.

#### Implementation

**`app/services/ConversationQueue.java`**

Replace the `QueueState` inner class. Remove the `lock` field:

```java
static class QueueState {
    final ArrayDeque<QueuedMessage> pending = new ArrayDeque<>();
    volatile boolean processing = false;
    String mode = "queue"; // all reads/writes guarded by synchronized(this)

    synchronized boolean tryStartProcessing() {
        if (processing) return false;
        processing = true;
        return true;
    }

    synchronized void finishProcessing() {
        processing = false;
    }

    synchronized boolean isProcessing() {
        return processing;
    }
}
```

Replace `tryAcquire()`. Snapshot mode inside `synchronized(state)`:

```java
public static boolean tryAcquire(Long conversationId, QueuedMessage message) {
    var state = queues.computeIfAbsent(conversationId, _ -> new QueueState());

    String mode;
    synchronized (state) {
        mode = ConfigService.get("agent." + message.agent().name + ".queue.mode", "queue");
        state.mode = mode;
    }

    if (state.tryStartProcessing()) {
        return true;
    }

    if ("interrupt".equals(mode)) {
        synchronized (state) {            // FIX: was unsynchronized
            state.pending.clear();
        }
        return true;
    }

    synchronized (state) {
        if (state.pending.size() >= MAX_QUEUE_SIZE) {
            state.pending.pollFirst();
            // ... warn log ...
        }
        state.pending.addLast(message);
    }
    // ... info log ...
    return false;
}
```

Replace `drain()`. Move `finishProcessing()` inside `synchronized(state)`:

```java
public static List<QueuedMessage> drain(Long conversationId) {
    var state = queues.get(conversationId);
    if (state == null) return List.of();

    synchronized (state) {
        state.finishProcessing();      // FIX: was outside synchronized

        if (state.pending.isEmpty()) {
            queues.remove(conversationId);
            return List.of();
        }

        if ("collect".equals(state.mode)) {
            var all = new ArrayList<>(state.pending);
            state.pending.clear();
            return all;
        }

        var next = state.pending.pollFirst();
        return next != null ? List.of(next) : List.of();
    }
}
```

Replace `getQueueSize()`:

```java
public static int getQueueSize(Long conversationId) {
    var state = queues.get(conversationId);
    if (state == null) return 0;
    synchronized (state) {            // FIX: was unsynchronized
        return state.pending.size();
    }
}
```

---

### 4. fix-tailwind-purge

#### Architecture

Tailwind CSS 3.x uses the `content` array to determine which source files to scan for class names. With `content: []` (an explicit empty array), the JIT scanner finds no class names. The `@nuxtjs/tailwindcss` module would normally inject Nuxt-aware default paths, but only when `content` is absent from the user config (`undefined`). An explicit empty array is treated as a deliberate override, bypassing automatic injection. Result: all ~3–4 MB of Tailwind utilities ship unscanned.

#### Implementation

**`frontend/tailwind.config.js`**

Replace the entire file:

```js
/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './pages/**/*.vue',
    './layouts/**/*.vue',
    './components/**/*.vue',
    './composables/**/*.ts',
    './app.vue',
  ],
  theme: {
    extend: {}
  },
  plugins: []
}
```

Verify: run `pnpm build` and inspect `.output/public/_nuxt/*.css`. Expected CSS bundle: under 20 KB gzipped. Check `frontend/components/` for dynamically-constructed class strings (e.g., `` `bg-${color}-500` ``) which would be purged — add a Tailwind `safelist` entry for any such patterns.

---

### 5. fix-n-plus-one-queries

#### Architecture

Play 1.x's `Model.findAll()` and `Model.find(string, params...)` emit bare SQL without joins. Even though `@ManyToOne` defaults to `FetchType.EAGER`, Hibernate satisfies EAGER loading via per-entity secondary SELECTs when the primary query has no JOIN clause. For a page of 50 tasks, this is 50 additional `SELECT * FROM agent WHERE id = ?` queries.

`JpaMemoryStore` compounds this with an explicit per-ID `Memory.findById(id)` loop after a raw SQL search, and `DB.getConnection()` which acquires a second JDBC connection from the pool while the JPA EntityManager already holds one.

#### Implementation

**`app/controllers/ApiBindingsController.java`**

In `list()`, replace the `findAll()` call. Play 1.x's `Model.find()` prepends `FROM ClassName` to its argument and does not accept full JPQL SELECT statements. Use `JPA.em().createQuery()` directly:

```java
// Before:
java.util.List<AgentBinding> bindings = AgentBinding.findAll();

// After:
java.util.List<AgentBinding> bindings = JPA.em()
        .createQuery("SELECT b FROM AgentBinding b JOIN FETCH b.agent", AgentBinding.class)
        .getResultList();
```

**`app/controllers/ApiChatController.java`**

In `listConversations()`, replace `Conversation.find()` with `JPA.em().createQuery()` for JOIN FETCH support. Bind positional parameters manually:

```java
String jpql;
if (query.isEmpty()) {
    jpql = "SELECT c FROM Conversation c JOIN FETCH c.agent ORDER BY c.updatedAt DESC";
} else {
    jpql = "SELECT c FROM Conversation c JOIN FETCH c.agent WHERE " + query + " ORDER BY c.updatedAt DESC";
}
var q = JPA.em().createQuery(jpql, Conversation.class);
for (int i = 0; i < params.size(); i++) {
    q.setParameter(i + 1, params.get(i));
}
List<Conversation> convos = q.setFirstResult(effectiveOffset)
        .setMaxResults(effectiveLimit).getResultList();
```

**`app/controllers/ApiTasksController.java`**

In `list()`, same approach with LEFT JOIN FETCH (agent is nullable):

```java
String jpql;
if (query.isEmpty()) {
    jpql = "SELECT t FROM Task t LEFT JOIN FETCH t.agent ORDER BY t.createdAt DESC";
} else {
    jpql = "SELECT t FROM Task t LEFT JOIN FETCH t.agent WHERE " + query + " ORDER BY t.createdAt DESC";
}
var q = JPA.em().createQuery(jpql, Task.class);
for (int i = 0; i < params.size(); i++) {
    q.setParameter(i + 1, params.get(i));
}
List<Task> tasks = q.setFirstResult(effectiveOffset)
        .setMaxResults(effectiveLimit).getResultList();
```

**`app/memory/JpaMemoryStore.java`**

Replace `DB.getConnection()` with EntityManager's connection (both `fullTextSearch()` and `hybridSearch()`):

```java
// Before:
try (var conn = DB.getConnection();
     var stmt = conn.prepareStatement(sql)) {

// After:
var conn = play.db.jpa.JPA.em().unwrap(java.sql.Connection.class);
try (var stmt = conn.prepareStatement(sql)) {
```

Replace per-ID loop with batch query (both methods):

```java
// Before:
return ids.stream()
        .map(id -> (Memory) Memory.findById(id))
        .filter(m -> m != null)
        .map(this::toEntry)
        .toList();

// After:
if (ids.isEmpty()) return List.of();
List<Memory> memories = Memory.<Memory>find("id IN ?1", ids).fetch();
var idRank = new java.util.HashMap<Long, Integer>();
for (int i = 0; i < ids.size(); i++) idRank.put(ids.get(i), i);
return memories.stream()
        .sorted(java.util.Comparator.comparingInt(m -> idRank.getOrDefault((Long) m.id, Integer.MAX_VALUE)))
        .map(this::toEntry)
        .toList();
```

**`app/models/AgentBinding.java`**

Add standalone `agent_id` index:

```java
@Table(name = "agent_binding", indexes = {
        @Index(name = "idx_binding_channel_peer", columnList = "channel_type,peer_id"),
        @Index(name = "idx_binding_agent", columnList = "agent_id")
})
```

---

## Risks

**fix-sync-transaction-scope** is the highest-risk change. The `Conversation` entity returned in `RunResult` is now detached (loaded inside a closed `Tx.run()`). The only caller is `ApiChatController.send()` (line 51), which uses `conversation.id` at line 55 — safe on a detached entity. Additionally, `Tx.run()` checks `JPA.isInsideTransaction()` — since `run()` is called from a Play request thread inside Play's open transaction, the setup `Tx.run()` runs inline (no new transaction opened), which is correct. The connection is released when the setup `Tx.run()` returns. Verify Play's request transaction lifecycle does not interfere.

**fix-conversation-queue-safety** moves `finishProcessing()` inside `synchronized(state)` in `drain()`. The `tryStartProcessing()` method is also `synchronized` on `state`. Java's `synchronized` is reentrant per thread, so calling `state.finishProcessing()` while already holding `state`'s monitor is safe — no deadlock. Hold time on the state monitor is slightly longer but all operations are O(1) in-memory.

**fix-n-plus-one-queries** changes bare HQL fragments to full JPQL SELECT strings. Play's `Model.find()` passes the string directly to JPA — full JPQL is supported. Test against H2 in MySQL mode (the test database) to confirm syntax compatibility. The `query` variable in both controllers previously held a suffix that Play prepended an implicit select to — after the change it is used only as a WHERE predicate embedded in a manually constructed JPQL string.

**fix-db-pool-and-disconnect** — the heartbeat `ScheduledExecutorService` uses `Thread.ofVirtual().unstarted(r)` as the thread factory. The `shutdownNow()` call in `finally` must execute even on `InterruptedException`.

**fix-tailwind-purge** — dynamically-constructed Tailwind class names will be purged. Audit `frontend/components/` and `frontend/pages/` for dynamic class construction before the production build.

## Testing Strategy

**fix-sync-transaction-scope:**
- Load test: 5 concurrent `POST /api/chat/send` requests with a mock LLM that sleeps 3s. Assert connection pool shows fewer than `maxSize` connections held at the 1-second mark.
- Regression: existing agent pipeline tests pass unchanged.

**fix-db-pool-and-disconnect:**
- Integration test: open `POST /api/chat/stream`, read the first token event, close the connection. Assert no further `Message` rows are inserted for that conversation.
- Timeout test: mock LLM that never responds. Assert `streamChat()` returns after 180s, not 600s.

**fix-conversation-queue-safety:**
- Concurrency test: 20 virtual threads call `tryAcquire(conversationId=1L, ...)` simultaneously. Assert exactly one returns `true`; rest return `false` and queue size equals 19.
- Drain atomicity test: verify newly acquired message is not lost during concurrent `drain()` + `tryAcquire()`.
- Interrupt mode test: `tryAcquire()` in interrupt mode while processing active. Assert `pending.clear()` leaves clean state.

**fix-tailwind-purge:**
- Run `pnpm build`. Assert total CSS in `.output/public/_nuxt/` under 20 KB gzipped.
- Visual regression: load SPA in browser, verify all pages render correctly.

**fix-n-plus-one-queries:**
- Enable Hibernate SQL debug logging. Call `GET /api/bindings`, `/api/conversations`, `/api/tasks` with 10 rows. Assert each endpoint emits exactly 1 SQL SELECT.
- Index creation test: start app with `jpa.ddl=update` against H2. Assert `idx_binding_agent` exists.
