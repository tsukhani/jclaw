## 1. fix-tailwind-purge: Configure Tailwind CSS Content Paths

- [ ] 1.1 Open `frontend/tailwind.config.js` and replace `content: []` with the five glob patterns: `'./pages/**/*.vue'`, `'./layouts/**/*.vue'`, `'./components/**/*.vue'`, `'./composables/**/*.ts'`, `'./app.vue'`
- [ ] 1.2 Run `cd frontend && pnpm build` and record CSS bundle size before and after to confirm purge is active
- [ ] 1.3 If `@nuxtjs/tailwindcss` module is present in `nuxt.config.ts`, verify build output to confirm explicit config is not redundant and does not conflict with module-injected paths

## 2. fix-db-pool-config: Connection Pool Configuration

- [ ] 2.1 Add `db.pool.minSize=5` to `conf/application.conf` under the Database configuration section
- [ ] 2.2 Add `db.pool.maxSize=20` to `conf/application.conf`
- [ ] 2.3 Add `db.pool.timeout=10000` to `conf/application.conf`
- [ ] 2.4 Add `%prod.db.pool.maxSize=30` to `conf/application.conf`
- [ ] 2.5 Add `%prod.db.pool.timeout=5000` to `conf/application.conf`
- [ ] 2.6 Start the application and verify no pool-related startup errors in logs

## 3. fix-conversation-queue-safety: ConversationQueue Thread Safety

- [ ] 3.1 Remove the `final ReentrantLock lock = new ReentrantLock()` field from `QueueState` in `app/services/ConversationQueue.java`
- [ ] 3.2 In `tryAcquire()`, read mode into a local variable (`var mode = ConfigService.get(...)`). Move the `state.mode = mode` write inside a `synchronized(state)` block so it is guarded by the monitor. Use the local `mode` variable for all branching decisions within `tryAcquire()` (the unsynchronized `state.mode` read at line 63 is replaced by the local)
- [ ] 3.3 Wrap the `interrupt` path's `state.pending.clear()` call inside `synchronized(state)` (currently at line 65, it is called without holding the monitor)
- [ ] 3.4 Wrap `getQueueSize()` body in `synchronized(state)` before reading `state.pending.size()`
- [ ] 3.5 Confirm that mode flows correctly end-to-end: `tryAcquire()` writes `state.mode` under `synchronized(state)` (3.2), `drain()` reads it inside its own `synchronized(state)` block (3.6). No signature change to `drain()` is needed. The two `drain()` call sites in `AgentRunner.java` (line 81 in `run()`, line 209 in `runStreaming()`) keep their existing `drain(conversationId)` signature — note that task 4 will later rename the local variable at line 81 from `conversation.id` to `conversationId`, but the `drain()` call signature is unchanged
- [ ] 3.6 Move `state.finishProcessing()` inside the `synchronized(state)` block in `drain()`, before the `state.pending.isEmpty()` check, to close the window where a new `tryAcquire()` can succeed and immediately have its entry removed
- [ ] 3.7 Write or update unit test: two virtual threads call `tryAcquire()` simultaneously in `interrupt` mode and verify `ArrayDeque` is not corrupted
- [ ] 3.8 Write or update unit test: `getQueueSize()` returns a consistent value under concurrent enqueue

## 4. fix-sync-transaction-scope: Break JPA Transaction Around LLM Calls

- [ ] 4.1 In `AgentRunner.run()`, capture `final Long conversationId = conversation.id` before the try block. Wrap the opening block (`appendUserMessage`, `SystemPromptAssembler.assemble`, `buildMessages`, `ProviderRegistry.get`, `trimToContextWindow`, `ToolRegistry.getToolDefsForAgent`) in a single `Tx.run()` and capture all results as local variables. Note: the `Conversation` is passed in as a parameter — it is not loaded inside `run()`
- [ ] 4.2 Change `callWithToolLoop()` signature to accept `Long conversationId` instead of `Conversation conversation`. Update its call site at line 70 of `run()` to pass `conversationId` instead of `conversation`
- [ ] 4.3 Inside `callWithToolLoop()`, wrap each `ConversationService.appendAssistantMessage` + `ConversationService.appendToolResult` pair (the tool-interaction persistence block) in its own `Tx.run()`, reloading `Conversation` by ID inside that block
- [ ] 4.4 Wrap the final `ConversationService.appendAssistantMessage` call at the end of `run()` in a `Tx.run()` block, reloading `Conversation` by ID inside the block: `var conv = ConversationService.findById(conversationId); ConversationService.appendAssistantMessage(conv, response, null);` (same reload pattern as 4.3 — required because task 4.5 removes the merge workaround)
- [ ] 4.5 In `ConversationService.appendMessage()`, remove the `JPA.em().contains()` / `JPA.em().merge()` workaround that was compensating for detached entities — it is no longer needed after 4.2–4.4
- [ ] 4.6 Verify the synchronous path with a manual test: send a message via `POST /api/chat/send` and confirm no `LazyInitializationException` or `detached entity` errors in logs
- [ ] 4.7 Write integration test: two concurrent `AgentRunner.run()` calls for different conversations complete without connection pool exhaustion (use a pool size of 2 and confirm both complete)

## 5. fix-n-plus-one-queries: Add JOIN FETCH to List Endpoints

- [ ] 5.1 In `ApiBindingsController.list()`, add `import play.db.jpa.JPA;` and replace `AgentBinding.findAll()` with `JPA.em().createQuery("SELECT b FROM AgentBinding b JOIN FETCH b.agent", AgentBinding.class).getResultList()`. Note: Play 1.x's `Model.find()` prepends `FROM ClassName` to its argument and does not accept full JPQL SELECT statements — `JPA.em().createQuery()` must be used instead for JOIN FETCH queries
- [ ] 5.2 In `ApiChatController.listConversations()`, add `import play.db.jpa.JPA;` (if not already present) and replace the `Conversation.find(orderBy, params)` call with `JPA.em().createQuery()`. Build the full JPQL string: if `query` is empty use `"SELECT c FROM Conversation c JOIN FETCH c.agent ORDER BY c.updatedAt DESC"`, otherwise use `"SELECT c FROM Conversation c JOIN FETCH c.agent WHERE " + query + " ORDER BY c.updatedAt DESC"`. Remove the current `orderBy` variable. Apply pagination via `.setFirstResult(effectiveOffset).setMaxResults(effectiveLimit)` on the `TypedQuery` (replaces Play's `.from().fetch()` which is not available on JPA queries). Bind positional parameters from the `params` list using `query.setParameter(idx, value)` in a loop
- [ ] 5.3 In `ApiTasksController.list()`, add `import play.db.jpa.JPA;` and apply the same `JPA.em().createQuery()` approach as 5.2: if `query` is empty use `"SELECT t FROM Task t LEFT JOIN FETCH t.agent ORDER BY t.createdAt DESC"`, otherwise use `"SELECT t FROM Task t LEFT JOIN FETCH t.agent WHERE " + query + " ORDER BY t.createdAt DESC"`. Use `LEFT JOIN FETCH` because `Task.agent` is nullable. Apply pagination via `.setFirstResult().setMaxResults()`
- [ ] 5.4 In `JpaMemoryStore.fullTextSearch()`, replace the `ids.stream().map(id -> Memory.findById(id))` loop with a batch query. Guard for empty IDs first: `if (ids.isEmpty()) return List.of();`. Then load all at once: `Memory.<Memory>find("id IN (?1)", ids).fetch()`. Preserve the SQL rank order by sorting results against the original ID list order
- [ ] 5.5 In `JpaMemoryStore.hybridSearch()`, apply the same batch replacement as 5.4, including the empty-IDs guard
- [ ] 5.6 In `JpaMemoryStore.java`, add `import play.db.jpa.JPA;` and in both `fullTextSearch()` and `hybridSearch()`, replace `DB.getConnection()` with `JPA.em().unwrap(java.sql.Connection.class)`. Remove the connection from the try-with-resources (JPA manages its lifecycle) — only the `PreparedStatement` should remain in try-with-resources
- [ ] 5.7 In `JpaMemoryStore.generateAndStoreEmbedding()`, apply the same `DB.getConnection()` → `JPA.em().unwrap(...)` replacement as 5.6
- [ ] 5.8 Add a second `@Index` entry to the existing `indexes` array in the `@Table` annotation on `app/models/AgentBinding.java`: `@Index(name = "idx_binding_agent", columnList = "agent_id")`. The existing `idx_binding_channel_peer` index must be preserved
- [ ] 5.9 Verify the index is created: start the app against a fresh H2 database and confirm `idx_binding_agent` appears in the schema
- [ ] 5.10 Write test: call `ApiBindingsController.list()` via `FunctionalTest` with 5 bindings across 2 agents and assert response contains correct `agentId`/`agentName` for each

## 6. fix-sse-disconnect: SSE Disconnect Propagation

*Depends on AgentRunner changes from task 4.*

- [ ] 6.1 Add `AtomicBoolean cancelled = new AtomicBoolean(false)` in `ApiChatController.streamChat()`, allocated per request before the `AgentRunner.runStreaming()` call
- [ ] 6.2 In the `onInit` callback catch block, add `cancelled.set(true)` and `latch.countDown()`
- [ ] 6.3 In the `onToken` callback catch block, add `cancelled.set(true)` and `latch.countDown()`
- [ ] 6.4 In the `onComplete` callback, move `latch.countDown()` to a `finally` block and add `cancelled.set(true)` in the catch path
- [ ] 6.5 Add `AtomicBoolean isCancelled` as a new parameter to `AgentRunner.runStreaming()`. Update the call site in `ApiChatController.streamChat()` (line 89) to pass the `cancelled` AtomicBoolean created in 6.1
- [ ] 6.6 In `runStreaming()`, check `cancelled.get()` before prompt assembly and exit early (still calling `ConversationQueue.drain()` in the finally block)
- [ ] 6.7 In `runStreaming()`, check `cancelled.get()` before the `OpenAiCompatibleClient.chatStreamAccumulate()` call and exit early
- [ ] 6.8 Add `AtomicBoolean isCancelled` as a parameter to `handleToolCallsStreaming()`. Update its call site at line 189 of `runStreaming()` to pass the cancellation flag. Update the recursive call within `handleToolCallsStreaming()` itself (line 331) to pass it through. Check `isCancelled.get()` at the start of each tool round and return early if set
- [ ] 6.9 Reduce `latch.await(600, TimeUnit.SECONDS)` to `latch.await(180, TimeUnit.SECONDS)` in `streamChat()`
- [ ] 6.10 Add SSE heartbeat logic: start a scheduled task or use a separate virtual thread that writes `: keep-alive\n\n` every 30 seconds until the latch is counted down; cancel it in the latch await finally block
- [ ] 6.11 Test disconnect propagation: start a stream, close the connection mid-response, and verify in logs that the virtual thread did not proceed past the next cancellation checkpoint
