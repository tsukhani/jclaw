## Phase 1 — Critical Resource & Safety Fixes

- [ ] 1.1 Fix JDBC connection leak in `JpaMemoryStore.fullTextSearch` — wrap `DB.getConnection()` in try-with-resources
- [ ] 1.2 Fix JDBC connection leak in `JpaMemoryStore.hybridSearch` — wrap `DB.getConnection()` in try-with-resources
- [ ] 1.3 Fix JDBC connection leak in `JpaMemoryStore.generateAndStoreEmbedding` — wrap `DB.getConnection()` in try-with-resources
- [ ] 1.4 Add timeout to `latch.await()` in `ApiChatController.streamChat()` (120s default)
- [ ] 1.5 Wrap all SSE callbacks (`onInit`, `onToken`, `onComplete`, `onError`) in try/catch in `ApiChatController`
- [ ] 1.6 Add `finally { latch.countDown() }` guard to `onComplete` and `onError` callbacks
- [ ] 1.7 Break `Tx.run()` in `WebhookSlackController` into discrete short transactions around `AgentRunner.run()`
- [ ] 1.8 Break `Tx.run()` in `WebhookTelegramController` into discrete short transactions
- [ ] 1.9 Break `Tx.run()` in `WebhookWhatsAppController` into discrete short transactions
- [ ] 1.10 Wrap JPA operations in `TaskPollerJob.executeTask` virtual thread with `Tx.run()`
- [ ] 1.11 Add DOMPurify dependency to frontend `package.json`
- [ ] 1.12 Sanitize `marked` output with DOMPurify before passing to `v-html` in `chat.vue`
- [ ] 1.13 Write test: verify SSE stream completes normally and on timeout
- [ ] 1.14 Write test: verify `TaskPollerJob` executes tasks without `TransactionRequiredException`

## Phase 2 — Correctness & Safety

- [ ] 2.1 Add `int round` parameter to `AgentRunner.handleToolCallsStreaming`, cap at `MAX_TOOL_ROUNDS`
- [ ] 2.2 Add `@Table(indexes=...)` to `Message` model — `(conversation_id)` and `(conversation_id, created_at)`
- [ ] 2.3 Add `@Table(indexes=...)` to `Task` model — `(status, next_run_at)`
- [ ] 2.4 Add `@Table(indexes=...)` to `EventLog` model — `(timestamp)` and `(category, level)`
- [ ] 2.5 Add `@Table(indexes=...)` to `AgentBinding` model — `(channel_type, peer_id)`
- [ ] 2.6 Add `@Table(indexes=...)` to `Memory` model — `(agent_id)`
- [ ] 2.7 Add `@Table(indexes=...)` to `Conversation` model — `(agent_id, channel_type, peer_id)`
- [ ] 2.8 Add `AbortController` to `chat.vue` `sendMessage()` and cancel on `onUnmounted`
- [ ] 2.9 Replace `useFetch` with `$fetch` in `conversations.vue` `selectConversation()`
- [ ] 2.10 Replace `useFetch` with `$fetch` in `chat.vue` `loadConversation()`
- [ ] 2.11 Replace `useFetch` with `$fetch` in `skills.vue` watch callback
- [ ] 2.12 Add token count estimation in `AgentRunner` before LLM dispatch, trim to fit `ModelInfo.contextWindow`
- [ ] 2.13 Log warning when message history is truncated due to context window limits
- [ ] 2.14 Write test: verify streaming tool calls stop at `MAX_TOOL_ROUNDS`

## Phase 3 — Performance Under Load

- [ ] 3.1 Refactor `ProviderRegistry.refresh()` to use atomic reference swap (build new map, assign immutable copy)
- [ ] 3.2 Add double-checked locking to `ProviderRegistry.refreshIfNeeded()`
- [ ] 3.3 Refactor `ToolRegistry` to use `ConcurrentHashMap` or immutable snapshot with volatile reference
- [ ] 3.4 Add denormalized `messageCount` and `preview` columns to `Conversation` model
- [ ] 3.5 Update `ConversationService.appendMessage` to maintain denormalized columns
- [ ] 3.6 Remove per-conversation subqueries from `ApiChatController.listConversations`
- [ ] 3.7 Add `ConcurrentHashMap<String, CachedEntry>` with 30s TTL to `SkillLoader`
- [ ] 3.8 Add file content cache with TTL to `AgentService.readWorkspaceFile`, invalidate on write
- [ ] 3.9 Create `HttpClients` utility with shared LLM client (60s timeout) and general client (15s timeout)
- [ ] 3.10 Replace static `HttpClient` in `OpenAiCompatibleClient` with shared instance
- [ ] 3.11 Replace static `HttpClient` in `SlackChannel`, `TelegramChannel`, `WhatsAppChannel` with shared instance
- [ ] 3.12 Replace static `HttpClient` in `WebFetchTool` with shared instance
- [ ] 3.13 Refactor `ConversationService.appendMessage` to track dirty flag, flush `updatedAt` once at end of run
- [ ] 3.14 Write test: verify ProviderRegistry refresh is atomic (concurrent reads never see empty cache)

## Phase 4 — Frontend Quality

- [ ] 4.1 Parallelize dashboard `useFetch` calls with `Promise.all` in `index.vue`
- [ ] 4.2 Change settings raw config table to iterate `providerEntries.other` instead of `configData?.entries`
- [ ] 4.3 Add try/catch with `finally { saving.value = false }` to `$fetch` mutations in `agents.vue`
- [ ] 4.4 Add try/catch with `finally { saving.value = false }` to `$fetch` mutations in `channels.vue`
- [ ] 4.5 Add try/catch with `finally { saving.value = false }` to `$fetch` mutations in `settings.vue`
- [ ] 4.6 Add try/catch to `$fetch` mutations in `skills.vue`
- [ ] 4.7 Use stable message IDs (`:key="msg.id ?? msg._key"`) in `chat.vue` message list
- [ ] 4.8 Assign temporary UUID to optimistic messages in `sendMessage()`
- [ ] 4.9 Throttle `scrollToBottom` with `requestAnimationFrame` in `chat.vue`
- [ ] 4.10 Check `document.hidden` before polling in `logs.vue` interval callback

## Phase 5 — Hardening

- [ ] 5.1 Set `play.netty.maxContentLength` in `application.conf` (default 10MB)
- [ ] 5.2 Add Content-Length check or LimitedInputStream wrapper to `readJsonBody` helpers
- [ ] 5.3 Add `Files.size()` check against `MAX_FILE_READ_BYTES` (1MB) in `FileSystemTools.readFile`
- [ ] 5.4 Pass `ModelInfo.maxTokens` from `ProviderConfig` to `ChatRequest` in `AgentRunner`
- [ ] 5.5 Replace pgvector string interpolation with parameterized `?::text::vector` in `JpaMemoryStore`
- [ ] 5.6 Add negative-hit caching to `ConfigService.get()` — cache null results with TTL
- [ ] 5.7 Add `.fetch(limit)` to `Memory.findByAgent` (default 1000)
- [ ] 5.8 Wrap `Long.parseLong` for `Retry-After` header in try/catch in `OpenAiCompatibleClient`
- [ ] 5.9 Add coarse-granularity skipping to `CronParser.nextExecution` for non-matching months/days
- [ ] 5.10 Add retry wrapper around `chatStreamAccumulate` for transient 5xx errors
- [ ] 5.11 Delete unused `useApi` composable in `frontend/composables/useApi.ts`
- [ ] 5.12 Cache `isPostgreSQL()` result as final field in `JpaMemoryStore` constructor
