## Phase 1 — Critical Resource & Safety Fixes

- [x] 1.1 Fix JDBC connection leak in `JpaMemoryStore.fullTextSearch` — wrap `DB.getConnection()` in try-with-resources
- [x] 1.2 Fix JDBC connection leak in `JpaMemoryStore.hybridSearch` — wrap `DB.getConnection()` in try-with-resources
- [x] 1.3 Fix JDBC connection leak in `JpaMemoryStore.generateAndStoreEmbedding` — wrap `DB.getConnection()` in try-with-resources
- [x] 1.4 Add timeout to `latch.await()` in `ApiChatController.streamChat()` (120s default)
- [x] 1.5 Wrap all SSE callbacks (`onInit`, `onToken`, `onComplete`, `onError`) in try/catch in `ApiChatController`
- [x] 1.6 Add `finally { latch.countDown() }` guard to `onComplete` and `onError` callbacks
- [x] 1.7 Break `Tx.run()` in `WebhookSlackController` into discrete short transactions around `AgentRunner.run()`
- [x] 1.8 Break `Tx.run()` in `WebhookTelegramController` into discrete short transactions
- [x] 1.9 Break `Tx.run()` in `WebhookWhatsAppController` into discrete short transactions
- [x] 1.10 Wrap JPA operations in `TaskPollerJob.executeTask` virtual thread with `Tx.run()`
- [x] 1.11 Add DOMPurify dependency to frontend `package.json`
- [x] 1.12 Sanitize `marked` output with DOMPurify before passing to `v-html` in `chat.vue`
- [x] 1.13 Write test: verify SSE stream completes normally and on timeout
- [x] 1.14 Write test: verify `TaskPollerJob` executes tasks without `TransactionRequiredException`

## Phase 2 — Correctness & Safety

- [x] 2.1 Add `int round` parameter to `AgentRunner.handleToolCallsStreaming`, cap at `MAX_TOOL_ROUNDS`
- [x] 2.2 Add `@Table(indexes=...)` to `Message` model — `(conversation_id)` and `(conversation_id, created_at)`
- [x] 2.3 Add `@Table(indexes=...)` to `Task` model — `(status, next_run_at)`
- [x] 2.4 Add `@Table(indexes=...)` to `EventLog` model — `(timestamp)` and `(category, level)`
- [x] 2.5 Add `@Table(indexes=...)` to `AgentBinding` model — `(channel_type, peer_id)`
- [x] 2.6 Add `@Table(indexes=...)` to `Memory` model — `(agent_id)`
- [x] 2.7 Add `@Table(indexes=...)` to `Conversation` model — `(agent_id, channel_type, peer_id)`
- [x] 2.8 Add `AbortController` to `chat.vue` `sendMessage()` and cancel on `onUnmounted`
- [x] 2.9 Replace `useFetch` with `$fetch` in `conversations.vue` `selectConversation()`
- [x] 2.10 Replace `useFetch` with `$fetch` in `chat.vue` `loadConversation()`
- [x] 2.11 Replace `useFetch` with `$fetch` in `skills.vue` watch callback
- [x] 2.12 Add token count estimation in `AgentRunner` before LLM dispatch, trim to fit `ModelInfo.contextWindow`
- [x] 2.13 Log warning when message history is truncated due to context window limits
- [x] 2.14 Write test: verify streaming tool calls stop at `MAX_TOOL_ROUNDS`

## Phase 3 — Performance Under Load

- [x] 3.1 Refactor `ProviderRegistry.refresh()` to use atomic reference swap (build new map, assign immutable copy)
- [x] 3.2 Add double-checked locking to `ProviderRegistry.refreshIfNeeded()`
- [x] 3.3 Refactor `ToolRegistry` to use `ConcurrentHashMap` or immutable snapshot with volatile reference
- [x] 3.4 Add denormalized `messageCount` and `preview` columns to `Conversation` model
- [x] 3.5 Update `ConversationService.appendMessage` to maintain denormalized columns
- [x] 3.6 Remove per-conversation subqueries from `ApiChatController.listConversations`
- [x] 3.7 Add `ConcurrentHashMap<String, CachedEntry>` with 30s TTL to `SkillLoader`
- [x] 3.8 Add file content cache with TTL to `AgentService.readWorkspaceFile`, invalidate on write
- [x] 3.9 Create `HttpClients` utility with shared LLM client (60s timeout) and general client (15s timeout)
- [x] 3.10 Replace static `HttpClient` in `OpenAiCompatibleClient` with shared instance
- [x] 3.11 Replace static `HttpClient` in `SlackChannel`, `TelegramChannel`, `WhatsAppChannel` with shared instance
- [x] 3.12 Replace static `HttpClient` in `WebFetchTool` with shared instance
- [x] 3.13 Refactor `ConversationService.appendMessage` to skip redundant saves during tool call rounds
- [x] 3.14 Write test: verify ProviderRegistry refresh is atomic (concurrent reads never see empty cache)

## Phase 4 — Frontend Quality

- [x] 4.1 Parallelize dashboard `useFetch` calls with `Promise.all` in `index.vue`
- [x] 4.2 Change settings raw config table to iterate `providerEntries.other` instead of `configData?.entries`
- [x] 4.3 Add try/catch with `finally { saving.value = false }` to `$fetch` mutations in `agents.vue`
- [x] 4.4 Add try/catch with `finally { saving.value = false }` to `$fetch` mutations in `channels.vue`
- [x] 4.5 Add try/catch with `finally { saving.value = false }` to `$fetch` mutations in `settings.vue`
- [x] 4.6 Add try/catch to `$fetch` mutations in `skills.vue`
- [x] 4.7 Use stable message IDs (`:key="msg.id ?? msg._key"`) in `chat.vue` message list
- [x] 4.8 Assign temporary UUID to optimistic messages in `sendMessage()`
- [x] 4.9 Throttle `scrollToBottom` with `requestAnimationFrame` in `chat.vue`
- [x] 4.10 Check `document.hidden` before polling in `logs.vue` interval callback

## Phase 5 — Hardening

- [x] 5.1 Set `play.netty.maxContentLength` in `application.conf` (default 10MB)
- [x] 5.2 Request body size enforced at Netty level via `play.netty.maxContentLength` — no controller-level check needed
- [x] 5.3 Add `Files.size()` check against `MAX_FILE_READ_BYTES` (1MB) in `FileSystemTools.readFile`
- [x] 5.4 Pass `ModelInfo.maxTokens` from `ProviderConfig` to `ChatRequest` in `AgentRunner`
- [x] 5.5 Replace pgvector string interpolation with parameterized `?::text::vector` in `JpaMemoryStore`
- [x] 5.6 Add negative-hit caching to `ConfigService.get()` — cache null results with TTL
- [x] 5.7 Add `.fetch(limit)` to `Memory.findByAgent` (default 1000)
- [x] 5.8 Wrap `Long.parseLong` for `Retry-After` header in try/catch in `OpenAiCompatibleClient`
- [x] 5.9 Add coarse-granularity skipping to `CronParser.nextExecution` for non-matching months/days
- [x] 5.10 Add retry wrapper around `chatStreamAccumulate` for transient 5xx errors
- [x] 5.11 Delete unused `useApi` composable in `frontend/composables/useApi.ts`
- [x] 5.12 Cache `isPostgreSQL()` result as final field in `JpaMemoryStore` constructor
