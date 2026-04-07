## Context

JClaw v0.1.0 is a Play Framework 1.x (Java) + Nuxt 3 application that was built with a velocity-first approach during the OpenClaw/JavaClaw port. The system is functionally complete but a comprehensive performance audit has identified 30+ issues across 5 severity levels. The most critical issues involve resource leaks (JDBC connections), thread hangs (unbounded latch.await), and concurrency bugs (missing transaction contexts) that will cause hard failures under even minimal concurrent load.

**Audit methodology:** Static analysis of all backend services, controllers, models, LLM client, streaming layer, and all frontend pages. Issues categorized by: database/JPA, memory leaks, concurrency, connection management, caching, rendering, data fetching, and security.

**Constraints:**
- Play 1.x's thread-local JPA requires explicit `Tx.run()` for virtual thread contexts
- JDK HttpClient's `HttpRequest.timeout()` is wall-clock, not idle-read
- JDBC drivers with `synchronized` internals can pin virtual thread carrier threads
- Nuxt 3 composables (`useFetch`, `useAsyncData`) must only be called at setup top-level

## Goals / Non-Goals

**Goals:**
- Eliminate all critical resource leaks and thread-hang scenarios
- Add safety guards (timeouts, depth limits, size limits) to all unbounded operations
- Add database indexes for all hot-path queries
- Fix XSS vulnerability in chat markdown rendering
- Fix Nuxt composable misuse and add proper cleanup for SSE streams
- Improve caching strategy for ProviderRegistry, SkillLoader, and workspace files
- Consolidate HTTP client instances

**Non-Goals:**
- Full async/reactive rewrite of the backend
- Switching from Play 1.x's JPA to a virtual-thread-friendly ORM
- Adding distributed caching (Redis, etc.)
- Frontend SSR support (remains SPA-only)
- Load testing or benchmarking (separate effort after fixes)
- Changing the streaming architecture (virtual thread + latch pattern is fine with proper guards)

## Design

### Phase 1 — Critical Resource & Safety Fixes

#### 1.1 JDBC Connection Leak in JpaMemoryStore

**Problem:** `DB.getConnection()` called at 3 sites without closing the returned connection.

**Fix:** Wrap all three call sites in try-with-resources:

```java
// fullTextSearch, hybridSearch, generateAndStoreEmbedding
try (var conn = DB.getConnection();
     var stmt = conn.prepareStatement(sql)) {
    // ...
}
```

**Files:** `app/memory/JpaMemoryStore.java` (lines 93-113, 138-156, 170-176)

#### 1.2 SSE Latch Timeout and Callback Safety

**Problem:** `latch.await()` blocks forever if virtual thread dies. `onError` callback can throw (client disconnected), preventing `latch.countDown()`.

**Fix in ApiChatController:**

```java
// Add timeout
if (!latch.await(120, TimeUnit.SECONDS)) {
    res.writeChunk("data: {\"type\":\"error\",\"content\":\"Request timed out\"}\n\n"
        .getBytes(StandardCharsets.UTF_8));
}

// Wrap callbacks with finally guard
var latch = new CountDownLatch(1);
AgentRunner.runStreaming(agent, conversationId, "web", username, messageText,
    conversation -> { /* onInit - wrap in try/catch */ },
    token -> { /* onToken - wrap in try/catch */ },
    content -> {
        try { /* write complete event */ }
        finally { latch.countDown(); }
    },
    error -> {
        try { /* write error event */ }
        finally { latch.countDown(); }
    }
);
```

**Files:** `app/controllers/ApiChatController.java` (lines 86-121)

#### 1.3 Webhook Long Transaction

**Problem:** Full `AgentRunner.run()` (30-120s) wrapped in single `Tx.run()`, holding JDBC connection.

**Fix:** Break into discrete transactions:

```java
// Transaction 1: find/create conversation + persist user message
var conversation = Tx.run(() -> {
    var c = ConversationService.findOrCreate(agent, channel, peerId);
    ConversationService.appendUserMessage(c, messageText);
    return c;
});

// No transaction: LLM call (blocking HTTP, 30-120s)
var result = AgentRunner.run(agent, conversation, messageText);

// Transaction 2: persist response
Tx.run(() -> ConversationService.appendAssistantMessage(conversation, result.response()));
```

**Files:** `app/controllers/WebhookSlackController.java`, `WebhookTelegramController.java`, `WebhookWhatsAppController.java`

#### 1.4 TaskPollerJob Tx Context

**Problem:** Virtual thread runs JPA operations without EntityManager context.

**Fix:** Wrap all JPA ops in `Tx.run()`:

```java
Thread.ofVirtual().start(() -> {
    Tx.run(() -> { task.status = RUNNING; task.save(); });
    try {
        // ... execute task ...
        Tx.run(() -> onSuccess(task));
    } catch (Exception e) {
        Tx.run(() -> onFailure(task, e));
    }
});
```

**Files:** `app/jobs/TaskPollerJob.java` (lines 29-81)

#### 1.5 XSS Sanitization

**Problem:** `marked` output rendered via `v-html` without sanitization.

**Fix:** Add DOMPurify:

```ts
import DOMPurify from 'dompurify'

function renderMarkdown(text: string): string {
  if (!text) return ''
  return DOMPurify.sanitize(marked.parse(text) as string)
}
```

**Files:** `frontend/pages/chat.vue` (line 10-13), `frontend/package.json`

### Phase 2 — Correctness & Safety

#### 2.1 Streaming Tool-Call Round Limit

Add `int round` parameter to `handleToolCallsStreaming`, cap at `MAX_TOOL_ROUNDS`.

**Files:** `app/agents/AgentRunner.java` (lines 208-243)

#### 2.2 Database Indexes

Add `@Table(indexes = ...)` annotations:

| Table | Index | Columns |
|-------|-------|---------|
| message | idx_message_conversation | conversation_id |
| message | idx_message_conversation_created | conversation_id, created_at |
| task | idx_task_status_next_run | status, next_run_at |
| event_log | idx_event_log_timestamp | timestamp |
| event_log | idx_event_log_category_level | category, level |
| agent_binding | idx_binding_channel_peer | channel_type, peer_id |
| memory | idx_memory_agent | agent_id |
| conversation | idx_conversation_agent_channel_peer | agent_id, channel_type, peer_id |

**Files:** All model files in `app/models/`

#### 2.3 SSE AbortController

Add `AbortController` to `sendMessage()` and cancel on `onUnmounted`.

**Files:** `frontend/pages/chat.vue`

#### 2.4 Replace useFetch in Event Handlers

Replace `useFetch` with `$fetch` in all imperative call sites.

**Files:** `frontend/pages/conversations.vue`, `frontend/pages/chat.vue`, `frontend/pages/skills.vue`

#### 2.5 Token Counting

Estimate token count (chars/4 heuristic) against `ModelInfo.contextWindow`. Trim oldest non-system messages to fit. Log warning when trimming occurs.

**Files:** `app/agents/AgentRunner.java`, `app/services/ConversationService.java`

### Phase 3 — Performance Under Load

#### 3.1 Atomic ProviderRegistry Refresh

Replace mutable `ConcurrentHashMap` with atomic reference swap + double-checked locking:

```java
private static volatile Map<String, ProviderConfig> cache = Map.of();
private static final Object refreshLock = new Object();

private static void refreshIfNeeded() {
    if (System.currentTimeMillis() - lastRefresh > REFRESH_INTERVAL_MS) {
        synchronized (refreshLock) {
            if (System.currentTimeMillis() - lastRefresh > REFRESH_INTERVAL_MS) {
                var newCache = new HashMap<String, ProviderConfig>();
                // ... populate ...
                cache = Map.copyOf(newCache);
                lastRefresh = System.currentTimeMillis();
            }
        }
    }
}
```

**Files:** `app/llm/ProviderRegistry.java`

#### 3.2 Thread-Safe ToolRegistry

Replace `LinkedHashMap` with immutable snapshot approach: build new map during registration, assign via volatile reference.

**Files:** `app/agents/ToolRegistry.java`

#### 3.3 N+1 Query Fix

Add denormalized `messageCount` and `preview` columns to `Conversation`, updated on message append. Eliminates per-conversation subqueries in list endpoint.

**Files:** `app/models/Conversation.java`, `app/services/ConversationService.java`, `app/controllers/ApiChatController.java`

#### 3.4 Skill and Workspace File Caching

Add `ConcurrentHashMap<String, CachedEntry>` with 30s TTL to `SkillLoader` and `AgentService.readWorkspaceFile`. Invalidate on write.

**Files:** `app/agents/SkillLoader.java`, `app/services/AgentService.java`

#### 3.5 Shared HttpClient

Create `HttpClients` utility with two shared instances: one for LLM calls (60s timeout), one for channel/general use (15s timeout).

**Files:** New `app/utils/HttpClients.java`, modify `app/llm/OpenAiCompatibleClient.java`, `app/channels/*.java`, `app/tools/WebFetchTool.java`

#### 3.6 Batch Conversation.updatedAt

Track dirty flag in `ConversationService`, flush once at end of agent run instead of per-message.

**Files:** `app/services/ConversationService.java`, `app/agents/AgentRunner.java`

### Phase 4 — Frontend Quality

#### 4.1 Parallel Dashboard Fetches

Use `Promise.all` for the 4 `useFetch` calls.

**Files:** `frontend/pages/index.vue`

#### 4.2 Settings Config Dedup

Change raw config table to iterate `providerEntries.other` instead of all entries.

**Files:** `frontend/pages/settings.vue`

#### 4.3 Mutation Error Handling

Add try/catch with `finally { saving.value = false }` to all `$fetch` mutation calls across all pages.

**Files:** `frontend/pages/agents.vue`, `channels.vue`, `settings.vue`, `skills.vue`

#### 4.4 Stable Message Keys

Use `msg.id ?? msg._key` (temporary UUID for optimistic messages) instead of array index.

**Files:** `frontend/pages/chat.vue`

#### 4.5 Throttle scrollToBottom

Use `requestAnimationFrame` to coalesce scroll calls during streaming.

**Files:** `frontend/pages/chat.vue`

#### 4.6 Pause Log Polling When Hidden

Check `document.hidden` before firing refresh in the interval callback.

**Files:** `frontend/pages/logs.vue`

### Phase 5 — Hardening

#### 5.1 Request Body Size Limits

Configure `play.netty.maxContentLength` in `application.conf`. Add Content-Length check in `readJsonBody` helper.

**Files:** `conf/application.conf`, controller helpers

#### 5.2 File Size Limit on FileSystemTools

Check `Files.size(path)` against `MAX_FILE_READ_BYTES` (default 1MB) before reading.

**Files:** `app/tools/FileSystemTools.java`

#### 5.3 Pass maxTokens to ChatRequest

Read `ModelInfo.maxTokens` from resolved `ProviderConfig` and pass to `ChatRequest`.

**Files:** `app/agents/AgentRunner.java`, `app/llm/OpenAiCompatibleClient.java`

#### 5.4 Parameterize pgvector SQL

Replace string interpolation with parameterized `?::text::vector` cast.

**Files:** `app/memory/JpaMemoryStore.java`

#### 5.5 ConfigService Negative-Hit Cache

Cache `null` results with TTL to prevent repeated DB lookups for absent keys.

**Files:** `app/services/ConfigService.java`

#### 5.6 Bounded Memory.findByAgent

Add `.fetch(limit)` with configurable upper bound (default 1000).

**Files:** `app/models/Memory.java`, `app/memory/JpaMemoryStore.java`

#### 5.7 Retry-After Parsing

Wrap `Long.parseLong` in try/catch to handle HTTP-date format gracefully.

**Files:** `app/llm/OpenAiCompatibleClient.java`

#### 5.8 CronParser Optimization

Skip non-matching months/days at coarser granularity before minute iteration.

**Files:** `app/jobs/CronParser.java`

#### 5.9 Streaming Retry

Add retry wrapper around `chatStreamAccumulate` for transient 5xx errors.

**Files:** `app/llm/OpenAiCompatibleClient.java`, `app/agents/AgentRunner.java`

## Risks

- **Phase 1 changes touch the core request path.** The webhook transaction split (1.3) changes the atomicity model — a crash between transaction 1 and 2 could leave a user message without a response. This is acceptable since the LLM call itself was never transactional.
- **Database indexes (2.2)** will be auto-created by Hibernate DDL in dev mode but require manual migration scripts for production PostgreSQL.
- **N+1 denormalization (3.3)** adds write overhead on every message append. The trade-off is worthwhile: message append is O(1) while conversation list is called far more frequently.
- **DOMPurify (1.5)** adds ~60KB to the frontend bundle. This is acceptable for security.

## Testing Strategy

- Each phase should be tested independently before merging
- Phase 1: Manual testing of SSE streaming (happy path + client disconnect + timeout), memory search, webhook message handling, task polling
- Phase 2: Verify streaming tool calls cap at MAX_TOOL_ROUNDS, verify indexes created (check H2 schema), verify SSE cleanup on navigation
- Phase 3: Load test with 10+ concurrent chat sessions, verify no connection pool exhaustion
- Phase 4-5: Manual UI testing, verify no console errors
