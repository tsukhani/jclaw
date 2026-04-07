## Why

A full-codebase performance audit (2026-04-07) identified 50+ issues across database/JPA, memory, concurrency, HTTP/API, and frontend. Five findings have outsized impact — they cause connection pool starvation, data corruption, thread leaks, 3-4 MB of wasted CSS, and O(N) redundant queries on every list page. Left unfixed, these prevent JClaw from handling more than a handful of concurrent users.

These are the top 5 recommendations from the audit, ordered by production impact.

## What Changes

### 1. fix-sync-transaction-scope: Break JPA Transaction Around LLM Calls

**Problem:** `AgentRunner.run()` executes inside Play's request-scoped JPA transaction. `callWithToolLoop()` makes up to 10 rounds of blocking LLM HTTP calls (each up to 180s with retries) while holding a JDBC connection from the pool. A few concurrent synchronous chats exhaust the entire connection pool, starving all other DB operations (logging, task polling, config reads, webhook processing).

**Root cause:** The streaming path (`runStreaming`) already scopes each DB touch to a short `Tx.run()` call and does HTTP work outside any transaction. The synchronous path never received the same treatment.

**Fix:**
- Load conversation history, assemble the prompt, and resolve the provider inside one short `Tx.run()` block at the top of `run()`
- Execute `callWithToolLoop()` (all LLM HTTP calls and tool executions) outside any transaction
- Wrap each tool interaction persistence (`appendAssistantMessage`, `appendToolResult`) inside its own `Tx.run()` call within the loop
- Wrap the final `appendAssistantMessage` at the end in `Tx.run()`
- Pass `conversationId` (Long) rather than the `Conversation` entity into the loop, reloading inside each `Tx.run()` to avoid detached entity issues (also fixes the `merge()` workaround in `ConversationService.appendMessage()`)

**Files:** `app/agents/AgentRunner.java` (primary), `app/services/ConversationService.java` (remove merge workaround after refactor)

### 2. fix-db-pool-and-disconnect: Connection Pool Config + SSE Disconnect Handling

Two independent changes that together prevent resource exhaustion under load.

**2a. Connection pool configuration**

**Problem:** `application.conf` has no `db.pool.*` settings. Play 1.x defaults to a small pool (~5-10 connections). Virtual thread concurrency, transaction-during-HTTP (fix 1), `EventLogger` per-call transactions, and `JpaMemoryStore` double-connection acquisition all compete for the same pool.

**Fix:** Add explicit pool settings to `application.conf`:
```
db.pool.minSize=5
db.pool.maxSize=20
db.pool.timeout=10000

%prod.db.pool.maxSize=30
%prod.db.pool.timeout=5000
```

**Files:** `conf/application.conf`

**2b. SSE client disconnect propagation**

**Problem:** When a client disconnects during SSE streaming, `writeChunk()` throws in the `onToken` callback. The exception is silently swallowed. The virtual thread continues the entire LLM pipeline (inference, tool calls, DB writes) to completion. The controller thread blocks on `latch.await(600, ...)` for up to 10 minutes. One disconnected client ties up both a Play request thread and a virtual thread.

**Fix:**
- Add an `AtomicBoolean cancelled` shared between the controller callbacks and the virtual thread
- In `onToken`, `onInit`, and `onComplete` catch blocks: set `cancelled = true` and call `latch.countDown()`
- In `AgentRunner.runStreaming()`: check `cancelled` before each major phase (prompt assembly, LLM call, tool execution, persistence). If cancelled, skip remaining work and exit
- Reduce the latch timeout from 600s to 180s (matching the LLM request timeout)
- Add SSE heartbeat comments (`: keep-alive\n\n`) every 30 seconds to prevent proxy/browser timeouts during long tool chains

**Files:** `app/controllers/ApiChatController.java`, `app/agents/AgentRunner.java` (add `Supplier<Boolean>` or `AtomicBoolean` cancellation parameter to `runStreaming`)

### 3. fix-conversation-queue-safety: ConversationQueue Thread Safety

**Problem:** Three concurrency bugs in `ConversationQueue`:

1. **Unsynchronized ArrayDeque mutation (data corruption):** The `interrupt` mode path calls `state.pending.clear()` at line 65 without holding `synchronized(state)`. A concurrent `drain()` call (which does hold the monitor) can race with this `clear()`, corrupting `ArrayDeque` internal state.

2. **Unsynchronized read (stale/corrupt data):** `getQueueSize()` reads `state.pending.size()` with no synchronization.

3. **Mode race condition (wrong behavior):** `tryAcquire()` writes `state.mode` from `ConfigService.get()` at line 56 without synchronization. Concurrent calls for the same conversation overwrite each other's mode. The mode read at line 63 and the mode read inside `drain()` at line 105 can differ because a new `tryAcquire()` from another thread wrote it between calls.

**Fix:**
- Remove the dead `ReentrantLock lock` field from `QueueState`
- Wrap the `interrupt` path's `state.pending.clear()` in `synchronized(state)`
- Synchronize `getQueueSize()` on the `QueueState` instance
- Read `mode` once at the top of `tryAcquire()` into a local variable; use that local for all decisions. Stop writing to `state.mode` from `tryAcquire()` — instead snapshot the mode into `QueueState` under the `synchronized` guard, or pass the mode as a parameter to `drain()`
- Move `finishProcessing()` inside the `synchronized(state)` block in `drain()` to close the narrow window where a new `tryAcquire()` can succeed and then have its queue entry removed

**Files:** `app/services/ConversationQueue.java`

### 4. fix-tailwind-purge: Configure Tailwind CSS Content Paths

**Problem:** `tailwind.config.js` has `content: []` — an empty array. Tailwind's purge/content scanner never runs, so the entire Tailwind utility set (~3-4 MB uncompressed, ~300-400 KB gzipped) ships to every user on every page load.

**Fix:** Set content paths to cover all Vue/TS source files:
```js
export default {
  content: [
    './pages/**/*.vue',
    './layouts/**/*.vue',
    './components/**/*.vue',
    './composables/**/*.ts',
    './app.vue',
  ],
  theme: { extend: {} },
  plugins: []
}
```

Verify by running `pnpm build` and checking CSS bundle size before/after. Note: `@nuxtjs/tailwindcss` may auto-inject content paths — if the module already handles this, the explicit config may be redundant. Check the build output to confirm.

**Files:** `frontend/tailwind.config.js`

### 5. fix-n-plus-one-queries: Add JOIN FETCH to List Endpoints

**Problem:** Four list endpoints fire N+1 queries — one query to load the list, then one additional `SELECT agent` per row when accessing the `agent` relationship:

| Endpoint | Query | N+1 access |
|----------|-------|------------|
| `ApiBindingsController.list()` | `AgentBinding.findAll()` | `b.agent.id`, `b.agent.name` |
| `ApiChatController.listConversations()` | `Conversation.find(...)` | `c.agent.id`, `c.agent.name` |
| `ApiTasksController.list()` | `Task.find(...)` | `t.agent.id`, `t.agent.name` |
| `JpaMemoryStore.fullTextSearch/hybridSearch` | Raw SQL for IDs | `Memory.findById(id)` in loop |

For a page of 100 conversations, that's up to 100 extra `SELECT agent` queries. The memory search fires 10 individual `SELECT memory` queries after the initial search.

**Fix:**
- Add join-fetch named queries for the three entity list endpoints:
  ```java
  // AgentBinding
  AgentBinding.find("SELECT b FROM AgentBinding b JOIN FETCH b.agent").fetch();

  // Conversation (in listConversations)
  Conversation.find("SELECT c FROM Conversation c JOIN FETCH c.agent " + whereClause, ...).fetch(limit);

  // Task (in list)
  Task.find("SELECT t FROM Task t LEFT JOIN FETCH t.agent " + whereClause, ...).fetch(limit);
  ```
- For `JpaMemoryStore`, replace the ID-by-ID loop with a single batch query:
  ```java
  Memory.<Memory>find("id IN (?1)", ids).fetch();
  ```
  Also replace `DB.getConnection()` with `JPA.em().unwrap(java.sql.Connection.class)` to avoid acquiring a second pool connection while JPA already holds one.

**Files:** `app/controllers/ApiBindingsController.java`, `app/controllers/ApiChatController.java`, `app/controllers/ApiTasksController.java`, `app/memory/JpaMemoryStore.java`, `app/models/AgentBinding.java` (add `@Index` on `agent_id` while here)

## Capabilities

### Modified Capabilities
- `agent-pipeline`: Transaction scoping refactored to release DB connections during LLM calls; cancellation signal added to streaming pipeline
- `conversation-queue`: Thread safety fixes for queue state, mode resolution, and drain lifecycle
- `admin-api`: N+1 queries eliminated from all list endpoints (bindings, conversations, tasks, memory search)
- `channel-web`: SSE disconnect propagated to cancel agent work; heartbeat keeps proxy connections alive

### No New Capabilities
This proposal fixes existing behavior only. No new features, endpoints, or UI changes.

## Impact

- **Backend:** Primary changes to `AgentRunner.java` (transaction scoping + cancellation), `ConversationQueue.java` (synchronization), `ApiChatController.java` (disconnect handling), and 4 list controllers/stores (JOIN FETCH). Secondary touch to `ConversationService.java` (remove merge workaround).
- **Frontend:** One-line change to `tailwind.config.js`.
- **Configuration:** New `db.pool.*` entries in `application.conf`.
- **Database:** No schema changes. One new index on `agent_binding.agent_id` (additive, applied by Hibernate DDL auto-update).
- **Risk:** The `AgentRunner.run()` transaction refactor is the highest-risk change — it restructures the core synchronous agent pipeline. The streaming path already proves the pattern works. All other changes are isolated and low-risk. Test with concurrent synchronous chat requests to verify connection pool behavior before/after.
