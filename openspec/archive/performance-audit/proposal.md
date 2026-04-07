## Why

JClaw v0.1.0 is functionally complete but was built with a velocity-first approach. A comprehensive performance audit has revealed critical resource leaks, concurrency bugs, and scalability bottlenecks that will cause failures under production load. Several issues (JDBC connection leaks, unbounded thread blocking, missing transaction contexts) can cause hard crashes or connection pool exhaustion with as few as 2-3 concurrent users. The frontend has XSS vulnerabilities, memory leaks from uncleaned SSE streams, and misuse of Nuxt composables that will degrade over time. These must be addressed before any production deployment or load testing.

## What Changes

### Phase 1 — Critical Resource & Safety Fixes

- **JDBC connection leak in JpaMemoryStore**: Three call sites (`fullTextSearch`, `hybridSearch`, `generateAndStoreEmbedding`) acquire `DB.getConnection()` but never close it. Each unclosed connection permanently leaks a pool slot. Fix: wrap all three in try-with-resources.
- **SSE latch hang**: `ApiChatController.streamChat()` calls `latch.await()` with no timeout. If the streaming virtual thread dies (uncaught `Error`, `onError` callback throws), the Play request thread hangs forever. Fix: add timeout, wrap all callbacks in try/catch, use `finally { latch.countDown() }`.
- **Webhook long transaction**: All three webhook controllers (`Slack`, `Telegram`, `WhatsApp`) wrap the entire `AgentRunner.run()` call — which includes 30-120s of LLM HTTP calls — inside a single `Tx.run()`. This holds a JDBC connection for the full duration. Fix: break into discrete short transactions (find/create conversation, then run agent outside Tx, then persist response).
- **TaskPollerJob missing Tx context**: `executeTask` runs JPA operations (`task.save()`) on a virtual thread with no `Tx.run()` wrapper. Will throw `TransactionRequiredException` at runtime. Fix: wrap all JPA ops in the virtual thread body with `Tx.run()`.
- **XSS via v-html in chat**: `marked` output is rendered via `v-html` without sanitization. A malicious LLM response can inject scripts. Fix: add DOMPurify.

### Phase 2 — Correctness & Safety

- **Unbounded streaming tool recursion**: `handleToolCallsStreaming` recurses without a depth counter. The sync path caps at `MAX_TOOL_ROUNDS=10` but the streaming path has no limit. Fix: thread a round counter through the recursive calls.
- **Missing database indexes**: No indexes on `message.conversation_id`, `task.(status,next_run_at)`, `event_log.timestamp`, `agent_binding.(channel_type,peer_id)`, `memory.agent_id`, `conversation.(agent_id,channel_type,peer_id)`. Performance degrades linearly with data growth. Fix: add `@Table(indexes=...)` annotations.
- **SSE stream not aborted on unmount**: `chat.vue` acquires a `ReadableStreamDefaultReader` with no `AbortController`. Navigating away leaks the stream and writes to destroyed refs. Fix: add `AbortController` + `onUnmounted` cleanup.
- **`useFetch` called inside event handlers**: `conversations.vue` and `chat.vue` call `useFetch` inside async functions, registering persistent watchers that are never cleaned up. Fix: replace with `$fetch`.
- **No token counting or context window enforcement**: `ModelInfo.contextWindow` and `maxTokens` are defined but never used. Oversized requests get 400 errors with no truncation or warning. Fix: estimate token count before dispatch, trim oldest messages to fit.

### Phase 3 — Performance Under Load

- **ProviderRegistry cache stampede**: Non-atomic check-then-act on `lastRefresh`. Concurrent threads all trigger `refresh()` simultaneously, each hitting the database. Fix: double-checked locking or atomic reference swap.
- **Non-atomic ProviderRegistry cache swap**: `cache.clear()` then repopulate creates a window where `get()` returns null for valid providers. Fix: build new map and swap atomically.
- **ToolRegistry thread safety**: `LinkedHashMap` used without synchronization across virtual threads. Fix: use `ConcurrentHashMap` or immutable snapshot with volatile reference.
- **N+1 queries in listConversations**: 2 extra queries per conversation (message count + first message preview). 20 conversations = 41 DB round-trips. Fix: denormalize or batch-query.
- **Filesystem scan on every LLM call**: `SkillLoader` reads all SKILL.md files from disk per request. Fix: cache with TTL.
- **Multiple HttpClient instances**: 5 separate static `HttpClient` instances across channels and LLM client, each with its own connection pool. Fix: consolidate to 1-2 shared instances.
- **Conversation UPDATE on every message**: `ConversationService.appendMessage` saves the Conversation entity on every single message. A 10-tool round generates 20+ UPDATE statements. Fix: flush `updatedAt` once at end of run.

### Phase 4 — Frontend Quality

- **Sequential dashboard fetches**: 4 `await useFetch` calls in series on the dashboard. Fix: `Promise.all`.
- **Settings page renders config entries twice**: Provider entries appear in both the provider section and the raw config table. Fix: iterate `providerEntries.other` for the raw section.
- **No error handling on $fetch mutations**: Unhandled rejections lock `saving` ref to `true` forever. Fix: try/catch with `finally` reset.
- **Message list keyed by index**: `:key="i"` causes full re-render on every streamed token. Fix: use stable message IDs.
- **scrollToBottom on every token**: Forces layout recalc dozens of times per second. Fix: throttle with `requestAnimationFrame`.
- **Log polling while tab hidden**: 5s interval fires even when document is not visible. Fix: check `document.hidden`.
- **`useFetch` inside watch in skills.vue**: Registers persistent watchers + duplicate fetch. Fix: use `$fetch` only.

### Phase 5 — Hardening

- **No request body size limit**: `JsonParser.parseReader` reads unbounded input across all controllers. Fix: check Content-Length or use LimitedInputStream.
- **No file size limit on FileSystemTools.readFile**: Agent can read arbitrarily large files into memory. Fix: add size check before read.
- **maxTokens never set on LLM requests**: Unbounded response size could fill StringBuilder in memory. Fix: pass `ModelInfo.maxTokens` to `ChatRequest`.
- **SQL string interpolation for pgvector**: Vector literal built via `String.format` prevents prepared statement caching. Fix: use parameterized query with cast.
- **ConfigService negative-hit caching**: Missing keys hit DB on every lookup. Fix: cache null results with TTL.
- **Unbounded Memory.findByAgent**: Loads all memories for an agent with no limit. Fix: add `.fetch(limit)`.
- **Retry-After header parsing**: HTTP-date format throws `NumberFormatException`. Fix: try/catch inside parser.
- **CronParser worst case**: Up to 527K iterations for non-matching expressions. Fix: add early termination or use a cron library.
- **Streaming path has no retry logic**: Sync path retries with backoff; streaming fails immediately. Fix: add retry wrapper.

## Capabilities

### Modified Capabilities
- `llm-client`: Add request timeout guards, token counting, maxTokens enforcement, streaming retry, idle-read timeout, Retry-After parsing fix, shared HttpClient, atomic ProviderRegistry refresh
- `agent-system`: Add streaming tool-call round limit, workspace file caching, skill caching, memory query limits
- `channel-telegram`: Break long transaction into discrete short transactions
- `channel-slack`: Break long transaction into discrete short transactions
- `channel-whatsapp`: Break long transaction into discrete short transactions
- `channel-web`: Add SSE latch timeout, callback error guards, SSE terminator events, AbortController on frontend
- `memory-system`: Fix JDBC connection leak, parameterize pgvector queries, cache isPostgreSQL check, limit memory loads
- `tool-system`: Thread-safe ToolRegistry, file size limits on FileSystemTools
- `task-scheduling`: Add Tx.run() wrapper in TaskPollerJob virtual threads, CronParser optimization
- `config-system`: Negative-hit caching, request body size limits
- `admin-ui`: XSS sanitization, fix useFetch misuse, error handling on mutations, parallel dashboard fetches, pagination prep, polling optimization, stable list keys, settings dedup
- `event-logging`: Add database indexes for query performance

## Impact

- **Backend**: Modifications across `app/controllers/`, `app/services/`, `app/agents/`, `app/llm/`, `app/memory/`, `app/jobs/`, `app/models/`, `app/tools/`
- **Frontend**: Modifications across `frontend/pages/`, `frontend/composables/`
- **Dependencies**: Add `dompurify` to frontend `package.json`
- **Database**: New indexes on 6+ tables (auto-created by Hibernate DDL in dev, manual migration for production PostgreSQL)
- **Risk**: Phase 1 and 2 changes affect core request paths. Each phase should be tested independently. Phase 3-5 are lower risk and can be parallelized.
