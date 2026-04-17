package agents;

import com.google.gson.Gson;
import static utils.GsonHolder.INSTANCE;
import llm.LlmProvider;
import llm.LlmTypes.*;
import llm.ProviderRegistry;
import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.MessageRole;
import services.ConversationService;
import services.EventLogger;
import utils.LatencyTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Core agent pipeline: receive message → load conversation → assemble prompt →
 * call LLM → handle tool calls (loop) → persist response → return.
 */
public class AgentRunner {

    private static final Gson gson = INSTANCE;
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 10;
    private static final Pattern IMAGE_URL_PATTERN =
            Pattern.compile("!\\[([^\\]]*)\\]\\((/api/[^)]+)\\)");
    private static final Pattern PAREN_URL_PATTERN =
            Pattern.compile("\\(([^)]+)\\)");

    private static int maxToolRounds() {
        return services.ConfigService.getInt("chat.maxToolRounds", DEFAULT_MAX_TOOL_ROUNDS);
    }

    public record RunResult(String response, Conversation conversation) {}

    /**
     * Callbacks for streaming mode. Groups the 6 event handlers that
     * {@link #runStreaming} pushes SSE data through.
     */
    public record StreamingCallbacks(
        Consumer<Conversation> onInit,
        Consumer<String> onToken,
        Consumer<String> onReasoning,
        Consumer<String> onStatus,
        Consumer<String> onComplete,
        Consumer<Exception> onError
    ) {}

    private record PreparedData(
        List<ChatMessage> messages,
        LlmProvider primary,
        LlmProvider secondary,
        List<ToolDef> tools
    ) {}

    /**
     * Bundle of read-only prologue data computed in a single JPA transaction.
     * Used by {@link #streamLlmLoop} to fold what used to be 5+ separate
     * {@code Tx.run} blocks into one round-trip to the connection pool.
     */
    private record PreparedPrologue(
        SystemPromptAssembler.AssembledPrompt assembled,
        List<ChatMessage> messages,
        List<ToolDef> tools
    ) {}

    private static final String STREAM_CANCELLED_MSG = "Stream cancelled by client disconnect";

    /**
     * Check whether the streaming client has disconnected. Logs the cancellation
     * when detected and returns {@code true} so the caller can short-circuit.
     */
    private static boolean checkCancelled(AtomicBoolean isCancelled, Agent agent, String channelType) {
        if (isCancelled.get()) {
            EventLogger.info("llm", agent.name, channelType, STREAM_CANCELLED_MSG);
            return true;
        }
        return false;
    }

    /**
     * Resolve the model's {@link ModelInfo} from the provider's configured model list.
     * Used by {@link #callWithToolLoop}, {@link #runStreaming}, and {@link #trimToContextWindow}.
     */
    private static Optional<ModelInfo> resolveModelInfo(Agent agent, LlmProvider provider) {
        return provider.config().models().stream()
                .filter(m -> m.id().equals(agent.modelId))
                .findFirst();
    }

    /**
     * Derive the effective max-tokens limit from the resolved model info.
     * Returns {@code null} when the model has no configured limit.
     */
    private static Integer effectiveMaxTokens(Agent agent, LlmProvider provider) {
        return resolveModelInfo(agent, provider)
                .filter(m -> m.maxTokens() > 0)
                .map(ModelInfo::maxTokens)
                .orElse(null);
    }

    /**
     * Resolve the reasoning-effort level this call should use. Combines the
     * agent's persisted {@code thinkingMode} with the model's capability:
     * the setting only takes effect when the model supports thinking and the
     * stored level is still advertised by the model. Otherwise returns
     * {@code null} (reasoning disabled).
     *
     * <p>The {@code null} path is not redundant with
     * {@link services.AgentService#normalizeThinkingMode}: agents can persist a
     * valid level today and see their model's levels change tomorrow (operator
     * edits the provider config), and we prefer to silently disable reasoning
     * rather than send a level the model no longer understands.
     */
    private static String resolveThinkingMode(Agent agent, LlmProvider provider) {
        if (agent.thinkingMode == null || agent.thinkingMode.isBlank()) return null;
        return resolveModelInfo(agent, provider)
                .filter(ModelInfo::supportsThinking)
                .filter(m -> m.effectiveThinkingLevels().contains(agent.thinkingMode))
                .map(_ -> agent.thinkingMode)
                .orElse(null);
    }

    /**
     * Run the agent synchronously. Returns the final assistant response.
     * JPA transactions are scoped to short Tx.run() blocks — no JDBC connection
     * is held during LLM HTTP calls or tool execution.
     */
    public static RunResult run(Agent agent, Conversation conversation, String userMessage) {
        // Acquire conversation queue — prevents concurrent message corruption
        var queueMsg = new services.ConversationQueue.QueuedMessage(
                userMessage, conversation.channelType, conversation.peerId, agent);
        if (!services.ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
            return new RunResult("Your message has been queued and will be processed shortly.", conversation);
        }

        final Long conversationId = conversation.id;

        try {
            // Short setup transaction: persist user message, assemble prompt, resolve provider.
            // Re-fetch the conversation by ID so it is managed in this persistence context.
            // Callers on virtual threads (TaskPollerJob, webhooks) pass entities that were
            // loaded in a separate, already-committed Tx.run() — those are detached and
            // would throw PersistentObjectException on save().
            var prepared = services.Tx.run(() -> {
                var conv = ConversationService.findById(conversationId);
                ConversationService.appendUserMessage(conv, userMessage);

                var assembled = SystemPromptAssembler.assemble(agent, userMessage);
                var messages = buildMessages(assembled.systemPrompt(), conv);

                var agentProvider = ProviderRegistry.get(agent.modelProvider);
                var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
                if (primary == null) {
                    var error = "No LLM provider configured. Add provider config via Settings.";
                    EventLogger.error("llm", agent.name, null, error);
                    ConversationService.appendAssistantMessage(conv, error, null);
                    return null;
                }
                var secondary = ProviderRegistry.getSecondary();

                var trimmed = trimToContextWindow(messages, agent, primary);
                var tools = ToolRegistry.getToolDefsForAgent(agent);

                EventLogger.info("llm", agent.name, conv.channelType,
                        "Calling %s / %s".formatted(primary.config().name(), agent.modelId));

                return new PreparedData(trimmed, primary, secondary, tools);
            });

            if (prepared == null) {
                var error = "No LLM provider configured. Add provider config via Settings.";
                return new RunResult(error,
                        services.Tx.run(() -> ConversationService.findById(conversationId)));
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
            EventLogger.flush();
            processQueueDrain(conversationId);
        }
    }

    /**
     * Run the agent with streaming. Resolves the conversation inside the virtual thread
     * so it commits in its own transaction before inserting messages.
     */
    public static void runStreaming(Agent agent, Long conversationId, String channelType, String peerId,
                                    String userMessage,
                                    AtomicBoolean isCancelled,
                                    StreamingCallbacks cb,
                                    LatencyTrace trace) {
        Thread.ofVirtual().start(() -> {
            final Long[] conversationIdRef = {null};
            try {
                // Phase 1: Resolve conversation, acquire queue, persist user message
                var conversation = resolveConversationAndAcquireQueue(
                        agent, conversationId, channelType, peerId, userMessage, cb);
                if (conversation == null) return; // queued, not-found, or error — already handled
                conversationIdRef[0] = conversation.id;

                if (trace != null) trace.mark(LatencyTrace.PROLOGUE_CONV_RESOLVED);

                if (checkCancelled(isCancelled, agent, channelType)) return;

                // Phase 2: Assemble prompt, resolve provider, call LLM in streaming loop
                streamLlmLoop(agent, conversation, channelType, userMessage, isCancelled, cb, trace);

            } catch (Exception e) {
                EventLogger.error("llm", agent.name, channelType,
                        "Streaming error: %s".formatted(e.getMessage()));
                cb.onError().accept(e);
            } finally {
                EventLogger.flush();
                if (conversationIdRef[0] != null) {
                    processQueueDrain(conversationIdRef[0]);
                }
            }
        });
    }

    /**
     * Phase 1 of streaming: resolve or create conversation, acquire the
     * conversation queue, and persist the user message. Returns the conversation
     * or {@code null} if the request was queued, not found, or errored (in which
     * case callbacks have already been invoked).
     */
    private static Conversation resolveConversationAndAcquireQueue(
            Agent agent, Long conversationId, String channelType, String peerId,
            String userMessage, StreamingCallbacks cb) {

        Conversation conversation = services.Tx.run(() -> {
            if (conversationId != null) {
                return ConversationService.findById(conversationId);
            } else if (ChannelType.WEB.value.equals(channelType)) {
                return ConversationService.create(agent, channelType, peerId);
            } else {
                return ConversationService.findOrCreate(agent, channelType, peerId);
            }
        });

        if (conversation == null) {
            cb.onError().accept(new RuntimeException("Conversation not found"));
            return null;
        }

        var queueMsg = new services.ConversationQueue.QueuedMessage(
                userMessage, channelType, peerId, agent);
        if (!services.ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
            cb.onInit().accept(conversation);
            cb.onComplete().accept("Your message has been queued and will be processed shortly.");
            return null;
        }

        // Now that we hold the lock, persist the user message.
        services.Tx.run(() -> {
            var convo = ConversationService.findById(conversation.id);
            ConversationService.appendUserMessage(convo, userMessage);
        });

        cb.onInit().accept(conversation);
        return conversation;
    }

    /**
     * Phase 2 of streaming: assemble the prompt, resolve the provider, and run
     * the streaming LLM call loop (including tool-call continuation, retry on
     * transient errors, truncation handling, and usage reporting).
     */
    private static void streamLlmLoop(Agent agent, Conversation conversation,
                                       String channelType, String userMessage,
                                       AtomicBoolean isCancelled, StreamingCallbacks cb,
                                       LatencyTrace trace)
            throws InterruptedException {

        EventLogger.info("llm", agent.name, channelType,
                "Streaming: assembling prompt for conversation id: %d".formatted(conversation.id));

        // Provider resolution first — no JPA tx needed. ProviderRegistry.refresh()
        // self-wraps its own tx when the 60s cache is stale, so callers don't need to.
        var agentProvider = ProviderRegistry.get(agent.modelProvider);
        var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
        if (primary == null) {
            EventLogger.error("llm", agent.name, channelType, "No LLM provider configured");
            cb.onError().accept(new RuntimeException("No LLM provider configured"));
            return;
        }

        if (checkCancelled(isCancelled, agent, channelType)) return;

        // Fold the remaining DB reads into ONE transaction. Tx.run short-circuits
        // nested calls, so inner helpers that also call Tx.run (e.g. loadRecentMessages
        // via buildMessages, any SystemPromptAssembler internals) don't pay twice.
        // `loadDisabledTools` is computed once and threaded into both the system prompt
        // assembler (tool catalog) and the tool-defs for the LLM request, eliminating
        // the redundant DB query that used to happen in each path.
        final LlmProvider primaryRef = primary;
        var prepared = services.Tx.run(() -> {
            var disabledTools = ToolRegistry.loadDisabledTools(agent);
            var assembled0 = SystemPromptAssembler.assemble(agent, userMessage, disabledTools);
            var convo = ConversationService.findById(conversation.id);
            var msgs = buildMessages(assembled0.systemPrompt(), convo);
            msgs = trimToContextWindow(msgs, agent, primaryRef);
            var toolDefs = ToolRegistry.getToolDefsForAgent(disabledTools);
            return new PreparedPrologue(assembled0, msgs, toolDefs);
        });

        var assembled = prepared.assembled();
        var messages = prepared.messages();

        if (checkCancelled(isCancelled, agent, channelType)) return;

        if (trace != null) trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);

        var tools = prepared.tools();
        var thinkingMode = resolveThinkingMode(agent, primary);
        EventLogger.info("llm", agent.name, channelType,
                "Streaming: calling %s / %s (%d messages, %d tools, %d skills%s)"
                        .formatted(primary.config().name(), agent.modelId,
                                messages.size(), tools.size(), assembled.skills().size(),
                                thinkingMode != null ? ", thinking=" + thinkingMode : ""));
        var maxTokens = effectiveMaxTokens(agent, primary);
        var modelInfo = resolveModelInfo(agent, primary).orElse(null);

        // Stream with tool call handling (HTTP, no JPA)
        if (trace != null) trace.mark(LatencyTrace.PROLOGUE_DONE);
        var streamStartMs = System.currentTimeMillis();
        var accumulator = primary.chatStreamAccumulate(
                agent.modelId, messages, tools, cb.onToken(), cb.onReasoning(), maxTokens, thinkingMode);

        if (!awaitAccumulatorOrCancel(accumulator, isCancelled, agent, channelType)) return;

        // Retry once on transient 5xx errors
        if (accumulator.error != null && accumulator.error.getMessage() != null
                && accumulator.error.getMessage().contains("HTTP 5")) {
            EventLogger.warn("llm", agent.name, null, "Retrying streaming after transient error");
            accumulator = primary.chatStreamAccumulate(
                    agent.modelId, messages, tools, cb.onToken(), cb.onReasoning(), maxTokens, thinkingMode);
            if (!awaitAccumulatorOrCancel(accumulator, isCancelled, agent, channelType)) return;
        }

        if (checkCancelled(isCancelled, agent, channelType)) return;

        if (accumulator.error != null) {
            cb.onError().accept(accumulator.error);
            return;
        }

        var content = accumulator.content;

        // Check for truncated response (max tokens hit mid-tool-call)
        if (isTruncationFinish(accumulator.finishReason) && !accumulator.toolCalls.isEmpty()) {
            EventLogger.warn("llm", agent.name, channelType,
                    "Response truncated (finish_reason=length) with pending tool calls — skipping execution of incomplete tool arguments");
            var truncMsg = content.isEmpty()
                    ? "I tried to use a tool but my response was too long and got cut off. Let me try a more concise approach."
                    : content;
            cb.onToken().accept(truncMsg.equals(content) ? "" : "\n\n*[Response was truncated — retrying with a simpler approach]*");
            if (trace != null) trace.mark(LatencyTrace.STREAM_BODY_END);
            var finalContent = truncMsg;
            // Fire the terminal SSE frame FIRST — user sees "done" immediately, and
            // trace.end() (registered on streamDone.whenComplete) records a tight
            // `total` that excludes the DB persist. Persist runs below on this same
            // virtual thread, which means the outer wrapper's finally → processQueueDrain
            // still observes a fully committed conversation before any queued follow-up
            // message runs. No race, no stale history for back-to-back turns.
            cb.onComplete().accept(finalContent);
            long truncPersistStartNs = System.nanoTime();
            services.Tx.run(() -> {
                var conv = ConversationService.findById(conversation.id);
                ConversationService.appendAssistantMessage(conv, finalContent, null);
            });
            utils.LatencyStats.record("persist",
                    (System.nanoTime() - truncPersistStartNs) / 1_000_000L);
            return;
        }

        // Handle tool calls if present
        if (!accumulator.toolCalls.isEmpty()) {
            content = handleToolCallsStreaming(agent, conversation.id, messages, tools,
                    accumulator.toolCalls, content, primary, cb, maxTokens, thinkingMode, 0,
                    isCancelled, trace);
        }

        if (checkCancelled(isCancelled, agent, channelType)) return;

        if (trace != null) trace.mark(LatencyTrace.STREAM_BODY_END);

        // Build usage JSON before persisting so it can be stored alongside the message
        var usageJson = buildUsageJson(accumulator, modelInfo, streamStartMs);

        // Fire the terminal SSE frame FIRST so the user-visible `total` ends at the
        // outgoing write, not at the DB round-trip. Persist happens below on this
        // same virtual thread — the outer wrapper's finally → processQueueDrain
        // therefore still observes a committed conversation before any queued
        // follow-up turn runs. See the truncation branch above for the full rationale.
        emitUsageAndComplete(agent, channelType, content, accumulator, modelInfo, streamStartMs, usageJson, cb);

        var finalContent = content;
        long persistStartNs = System.nanoTime();
        services.Tx.run(() -> {
            var conv = ConversationService.findById(conversation.id);
            ConversationService.appendAssistantMessage(conv, finalContent, null, usageJson);
        });
        utils.LatencyStats.record("persist",
                (System.nanoTime() - persistStartNs) / 1_000_000L);
    }

    /**
     * Poll an accumulator for completion, checking for cancellation every 5 s.
     * Returns {@code true} if the accumulator completed, {@code false} if
     * cancelled (in which case the cancellation has already been logged).
     */
    /**
     * Resolve the reasoning-token count to surface in usage metrics. Prefers the
     * provider-reported {@code usage.reasoning_tokens} when available; falls back
     * to a character-length estimate over the streamed reasoning text (≈4 chars
     * per token, the standard English heuristic) when the provider reported zero
     * despite reasoning having been detected. Returning an estimate — clearly
     * non-zero when reasoning ran — is better than showing no count at all, since
     * the UI's reasoning-count badge is gated on this value being truthy.
     */
    private static int effectiveReasoningTokens(llm.LlmTypes.Usage usage,
                                                 LlmProvider.StreamAccumulator accumulator) {
        if (usage.reasoningTokens() > 0) return usage.reasoningTokens();
        if (!accumulator.reasoningDetected) return 0;
        var chars = accumulator.reasoningChars();
        if (chars <= 0) return 0;
        // Round up: a small amount of text still represents at least one reasoning
        // token on the wire, and rounding down would silently swallow short traces.
        return Math.max(1, (chars + 3) / 4);
    }

    private static boolean awaitAccumulatorOrCancel(LlmProvider.StreamAccumulator accumulator,
                                                     AtomicBoolean isCancelled,
                                                     Agent agent, String channelType)
            throws InterruptedException {
        while (!accumulator.awaitCompletion(5000)) {
            if (checkCancelled(isCancelled, agent, channelType)) return false;
        }
        return true;
    }

    /**
     * Log usage, build the usage JSON payload (including pricing), and invoke
     * the status + complete callbacks.
     */
    /** Build the usage JSON string from the accumulator, or null if no usage data. */
    private static String buildUsageJson(LlmProvider.StreamAccumulator accumulator,
                                          ModelInfo modelInfo, long streamStartMs) {
        var durationMs = System.currentTimeMillis() - streamStartMs;
        if (accumulator.usage == null) {
            var reasoningMs = accumulator.reasoningDurationMs();
            return reasoningMs > 0L
                    ? "{\"durationMs\":%d,\"reasoningDurationMs\":%d}".formatted(durationMs, reasoningMs)
                    : "{\"durationMs\":%d}".formatted(durationMs);
        }

        var u = accumulator.usage;
        var reasoningCount = effectiveReasoningTokens(u, accumulator);
        var usageMap = new com.google.gson.JsonObject();
        usageMap.addProperty("prompt", u.promptTokens());
        usageMap.addProperty("completion", u.completionTokens());
        usageMap.addProperty("total", u.totalTokens());
        usageMap.addProperty("reasoning", reasoningCount);
        usageMap.addProperty("cached", u.cachedTokens());
        usageMap.addProperty("cacheCreation", u.cacheCreationTokens());
        usageMap.addProperty("durationMs", durationMs);
        var reasoningMs = accumulator.reasoningDurationMs();
        if (reasoningMs > 0L) usageMap.addProperty("reasoningDurationMs", reasoningMs);

        if (modelInfo != null) {
            if (modelInfo.promptPrice() >= 0) usageMap.addProperty("promptPrice", modelInfo.promptPrice());
            if (modelInfo.completionPrice() >= 0) usageMap.addProperty("completionPrice", modelInfo.completionPrice());
            if (modelInfo.cachedReadPrice() >= 0) usageMap.addProperty("cachedReadPrice", modelInfo.cachedReadPrice());
            if (modelInfo.cacheWritePrice() >= 0) usageMap.addProperty("cacheWritePrice", modelInfo.cacheWritePrice());
        }
        return gson.toJson(usageMap);
    }

    private static void emitUsageAndComplete(Agent agent, String channelType, String content,
                                              LlmProvider.StreamAccumulator accumulator,
                                              ModelInfo modelInfo,
                                              long streamStartMs, String usageJson,
                                              StreamingCallbacks cb) {
        var durationMs = System.currentTimeMillis() - streamStartMs;
        if (accumulator.usage != null) {
            var u = accumulator.usage;
            var reasoningCount = effectiveReasoningTokens(u, accumulator);
            var extras = new StringBuilder();
            if (reasoningCount > 0) extras.append(", %d reasoning".formatted(reasoningCount));
            if (u.cachedTokens() > 0) extras.append(", %d cached".formatted(u.cachedTokens()));
            if (u.cacheCreationTokens() > 0) extras.append(", %d cache-write".formatted(u.cacheCreationTokens()));
            var usageSummary = " [%d prompt, %d completion, %d total tokens%s, %.1fs]".formatted(
                    u.promptTokens(), u.completionTokens(), u.totalTokens(),
                    extras.toString(),
                    durationMs / 1000.0);
            EventLogger.info("llm", agent.name, channelType,
                    "Streaming complete (%d chars)%s".formatted(content.length(), usageSummary));
        } else {
            EventLogger.info("llm", agent.name, channelType,
                    "Streaming complete (%d chars, %.1fs)".formatted(content.length(), durationMs / 1000.0));
        }
        cb.onStatus().accept("{\"usage\":%s}".formatted(usageJson));
        cb.onComplete().accept(content);
    }

    // --- Internal ---

    private static String callWithToolLoop(Agent agent, Long conversationId,
                                            List<ChatMessage> messages, List<ToolDef> tools,
                                            LlmProvider primary, LlmProvider secondary) {
        var currentMessages = new ArrayList<>(messages);
        var maxTokens = effectiveMaxTokens(agent, primary);
        var thinkingMode = resolveThinkingMode(agent, primary);

        for (int round = 0; round < maxToolRounds(); round++) {
            ChatResponse response;
            try {
                response = (secondary != null)
                        ? LlmProvider.chatWithFailover(primary, secondary, agent.modelId, currentMessages, tools, maxTokens, thinkingMode)
                        : primary.chat(agent.modelId, currentMessages, tools, maxTokens, thinkingMode);
            } catch (Exception e) {
                EventLogger.error("llm", agent.name, null, "LLM call failed: %s".formatted(e.getMessage()));
                return "I'm sorry, I encountered an error communicating with the AI provider. Please try again.";
            }

            if (response.choices() == null || response.choices().isEmpty()) {
                return "No response received from the AI provider.";
            }

            var choice = response.choices().getFirst();
            var assistantMsg = choice.message();

            // No tool calls — return the content
            if (assistantMsg.toolCalls() == null || assistantMsg.toolCalls().isEmpty()) {
                return contentAsString(assistantMsg.content());
            }

            // Check for truncated response (max tokens hit mid-tool-call)
            if (isTruncationFinish(choice.finishReason())) {
                EventLogger.warn("llm", agent.name, null,
                        "Response truncated (finish_reason=length) with pending tool calls — skipping execution of incomplete tool arguments");
                return assistantMsg.content() != null ? (String) assistantMsg.content()
                        : "I tried to use a tool but my response was too long and got cut off. Let me try a more concise approach.";
            }

            // Tool calls — execute (in parallel when multiple) and continue
            currentMessages.add(assistantMsg);
            EventLogger.info("tool", agent.name, null,
                    "Round %d: executing %d tool call(s)".formatted(round + 1, assistantMsg.toolCalls().size()));

            executeToolsParallel(assistantMsg.toolCalls(), agent, conversationId,
                    currentMessages, null, null, null);
        }

        return "I reached the maximum number of tool execution rounds. Please try a simpler request.";
    }

    private static String handleToolCallsStreaming(Agent agent, Long conversationId,
                                                    List<ChatMessage> messages, List<ToolDef> tools,
                                                    List<ToolCall> toolCalls, String priorContent,
                                                    LlmProvider provider,
                                                    StreamingCallbacks cb,
                                                    Integer maxTokens, String thinkingMode,
                                                    int round, AtomicBoolean isCancelled,
                                                    LatencyTrace trace) {
        if (round >= maxToolRounds()) {
            return "I reached the maximum number of tool execution rounds. Please try a simpler request.";
        }
        if (isCancelled.get()) {
            return priorContent;
        }
        EventLogger.info("tool", agent.name, null,
                "Streaming round %d: executing %d tool call(s)".formatted(round + 1, toolCalls.size()));

        var currentMessages = new ArrayList<>(messages);
        currentMessages.add(ChatMessage.assistant(priorContent, toolCalls));
        var collectedImages = new ArrayList<String>();

        var toolRoundStartNs = System.nanoTime();
        executeToolsParallel(toolCalls, agent, conversationId, currentMessages,
                cb.onStatus(), collectedImages, isCancelled);
        if (trace != null) {
            trace.addToolRound((System.nanoTime() - toolRoundStartNs) / 1_000_000L);
        }

        if (isCancelled.get()) return priorContent;

        cb.onStatus().accept("Processing results (round %d)...".formatted(round + 1));
        EventLogger.info("llm", agent.name, null,
                "Streaming round %d: continuing LLM call after tool results".formatted(round + 1));

        // Continue with streaming after tool results
        var accumulator = provider.chatStreamAccumulate(
                agent.modelId, currentMessages, tools, cb.onToken(), cb.onReasoning(), maxTokens, thinkingMode);

        try {
            if (!awaitAccumulatorOrCancel(accumulator, isCancelled, agent, null)) return priorContent;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return priorContent;
        }

        if (isCancelled.get()) return priorContent;

        // Truncation guard: if the model hit max_tokens mid-tool-call, the tool arguments
        // will be an incomplete JSON fragment. Passing that to ToolRegistry.execute causes
        // a Gson EOFException and the user sees a cryptic "End of input" error. Instead,
        // surface a clear message so the LLM can retry with a more concise approach.
        if (isTruncationFinish(accumulator.finishReason) && !accumulator.toolCalls.isEmpty()) {
            EventLogger.warn("tool", agent.name, null,
                    "Response truncated (finish_reason=%s) with pending tool calls in round %d — skipping execution of incomplete tool arguments"
                            .formatted(accumulator.finishReason, round + 1));
            var truncMsg = accumulator.content != null && !accumulator.content.isEmpty()
                    ? accumulator.content + "\n\n*[Response was truncated before the next tool call could complete. Try breaking the task into smaller steps.]*"
                    : "I tried to use a tool but the response exceeded the token limit before the tool arguments finished. Try breaking the task into smaller steps — for example, write large files in multiple append operations instead of one big write.";
            cb.onToken().accept(accumulator.content != null && !accumulator.content.isEmpty()
                    ? "\n\n*[Response was truncated before the next tool call could complete.]*"
                    : truncMsg);
            return truncMsg;
        }

        // Recursively handle if more tool calls
        if (!accumulator.toolCalls.isEmpty()) {
            return handleToolCallsStreaming(agent, conversationId, currentMessages, tools,
                    accumulator.toolCalls, accumulator.content, provider, cb, maxTokens, thinkingMode,
                    round + 1, isCancelled, trace);
        }

        // Some models (especially smaller/distilled ones) occasionally return zero tokens
        // on the continue-after-tool-results turn, treating the tool output as self-explanatory
        // even when the user clearly wants synthesis. Retry once with an explicit synthesis
        // nudge before giving up and emitting a diagnostic fallback.
        if (accumulator.content == null || accumulator.content.isBlank()) {
            EventLogger.warn("llm", agent.name, null,
                    "Empty continuation after tool calls in round %d — retrying with synthesis nudge"
                            .formatted(round + 1));
            cb.onStatus().accept("Synthesizing response (retry)...");

            var retryMessages = new ArrayList<>(currentMessages);
            retryMessages.add(ChatMessage.user(
                    "Synthesize the final response for me now using the tool results above. "
                            + "Do not call any more tools. Write the full answer as markdown."));

            var retry = provider.chatStreamAccumulate(
                    agent.modelId, retryMessages, tools, cb.onToken(), cb.onReasoning(), maxTokens, thinkingMode);
            try {
                if (!awaitAccumulatorOrCancel(retry, isCancelled, agent, null)) return priorContent;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return priorContent;
            }

            if (retry.content != null && !retry.content.isBlank()) {
                return buildImagePrefix(collectedImages, retry.content) + retry.content;
            }

            // Retry also empty — emit a labeled diagnostic so the user knows why.
            EventLogger.warn("llm", agent.name, null,
                    "Retry also returned empty content — emitting diagnostic fallback");
            // No LLM content to dedupe against — prepend every collected image unchanged.
            var fallbackPrefix = collectedImages.isEmpty() ? ""
                    : String.join("\n\n", collectedImages) + "\n\n";
            var fallback = fallbackPrefix
                    + "*[The model returned no synthesis after tool calls. Tool results are in the conversation history above — try rephrasing your request or switching to a larger model.]*";
            cb.onToken().accept(fallback);
            return fallback;
        }

        return buildImagePrefix(collectedImages, accumulator.content) + accumulator.content;
    }

    /**
     * Pure compute: dispatch one tool call and return its result. No side
     * effects on shared state (message lists, image collector, DB). Safe to
     * call from multiple virtual threads concurrently.
     */
    private static String runToolCall(ToolCall toolCall, Agent agent, Consumer<String> onStatus) {
        if (onStatus != null) {
            onStatus.accept("Using tool: " + toolCall.function().name());
        }
        EventLogger.info("tool", agent.name, null,
                "Executing tool '%s' (id: %s, args: %s)"
                        .formatted(toolCall.function().name(), toolCall.id(),
                                toolCall.function().arguments().length() > 200
                                        ? toolCall.function().arguments().substring(0, 200) + "..."
                                        : toolCall.function().arguments()));
        var result = ToolRegistry.execute(toolCall.function().name(),
                toolCall.function().arguments(), agent);
        var resultPreview = result.length() > 200
                ? result.substring(0, 200) + "... (%d chars)".formatted(result.length()) : result;
        EventLogger.info("tool", agent.name, null,
                "Tool '%s' returned: %s".formatted(toolCall.function().name(), resultPreview));
        return result;
    }

    /**
     * Execute a batch of tool calls, in parallel when there are multiple.
     * Preserves original order when committing results to the message list
     * and DB, so LLM history invariants (tool_call_id → tool_result pairing
     * and order) match the pre-parallel behavior exactly.
     *
     * <p>Single-tool batches skip the virtual-thread overhead and execute
     * inline. Cancellation between tools is honored — in-flight tools finish
     * naturally (their results are discarded at commit time).
     */
    private static void executeToolsParallel(List<ToolCall> toolCalls,
                                              Agent agent, Long conversationId,
                                              List<ChatMessage> currentMessages,
                                              Consumer<String> onStatus,
                                              List<String> imageCollector,
                                              AtomicBoolean isCancelled) {
        int n = toolCalls.size();
        if (n == 0) return;

        String[] results = new String[n];

        if (n == 1) {
            if (isCancelled == null || !isCancelled.get()) {
                results[0] = runToolCall(toolCalls.getFirst(), agent, onStatus);
            }
        } else {
            var latch = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final ToolCall tc = toolCalls.get(i);
                Thread.ofVirtual().start(() -> {
                    try {
                        if (isCancelled == null || !isCancelled.get()) {
                            results[idx] = runToolCall(tc, agent, onStatus);
                        }
                    } catch (Exception e) {
                        EventLogger.error("tool", agent.name, null,
                                "Tool '%s' threw: %s"
                                        .formatted(tc.function().name(), e.getMessage()));
                        results[idx] = "Error executing tool: " + e.getMessage();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        // Commit phase: append to message history and persist to DB in
        // original order, preserving LLM tool_result ordering invariants.
        for (int i = 0; i < n; i++) {
            var result = results[i];
            if (result == null) continue; // skipped due to cancellation
            var tc = toolCalls.get(i);
            currentMessages.add(ChatMessage.toolResult(tc.id(), result));
            if (imageCollector != null) {
                extractImageUrls(result, imageCollector);
            }
            final String r = result;
            services.Tx.run(() -> {
                var conv = ConversationService.findById(conversationId);
                ConversationService.appendAssistantMessage(conv, null, gson.toJson(tc));
                ConversationService.appendToolResult(conv, tc.id(), r);
            });
        }
    }

    private static List<ChatMessage> buildMessages(String systemPrompt, Conversation conversation) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(systemPrompt));

        var history = ConversationService.loadRecentMessages(conversation);
        for (var msg : history) {
            var role = MessageRole.fromValue(msg.role);
            messages.add(switch (role != null ? role : MessageRole.USER) {
                case USER -> ChatMessage.user(msg.content);
                case ASSISTANT -> {
                    if (msg.toolCalls != null && !msg.toolCalls.isBlank()) {
                        var toolCalls = parseToolCalls(msg.toolCalls);
                        yield ChatMessage.assistant(msg.content, toolCalls);
                    }
                    yield ChatMessage.assistant(msg.content != null ? msg.content : "");
                }
                case TOOL -> ChatMessage.toolResult(msg.toolResults, msg.content);
                case SYSTEM -> ChatMessage.system(msg.content);
            });
        }

        return messages;
    }

    private static List<ChatMessage> trimToContextWindow(List<ChatMessage> messages, Agent agent, LlmProvider provider) {
        var modelInfo = resolveModelInfo(agent, provider).orElse(null);
        if (modelInfo == null || modelInfo.contextWindow() <= 0) return messages;

        int maxTokens = modelInfo.contextWindow();
        int estimatedTokens = estimateTokens(messages);

        if (estimatedTokens <= maxTokens) return messages;

        // Find how many oldest non-system messages to drop. Scan forward from index 1
        // (first after system prompt) and accumulate tokens to remove until we fit.
        int total = estimatedTokens;
        int dropCount = 0;
        for (int i = 1; i < messages.size() - 1 && total > maxTokens; i++) {
            total -= estimateTokens(List.of(messages.get(i)));
            dropCount++;
        }
        if (dropCount > 0) {
            EventLogger.warn("llm", agent.name, null,
                    "Trimmed %d messages to fit context window (%d tokens max, estimated %d)"
                            .formatted(dropCount, maxTokens, estimatedTokens));
            // Build result: system message + surviving history (skip dropped range)
            var trimmed = new ArrayList<ChatMessage>(messages.size() - dropCount);
            trimmed.add(messages.getFirst());
            trimmed.addAll(messages.subList(1 + dropCount, messages.size()));
            return trimmed;
        }
        return messages;
    }

    private static int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (var msg : messages) {
            if (msg.content() instanceof String s) {
                chars += s.length();
            } else if (msg.content() instanceof List<?> parts) {
                // Multi-part content (vision/image blocks): sum text parts only.
                // Image data is base64 and doesn't meaningfully correspond to
                // chars/4 token estimation — providers count image tokens separately.
                for (var part : parts) {
                    if (part instanceof Map<?,?> m) {
                        var text = m.get("text");
                        if (text instanceof String t) chars += t.length();
                    }
                }
            }
            // Tool call names + arguments also consume input tokens.
            if (msg.toolCalls() != null) {
                for (var tc : msg.toolCalls()) {
                    if (tc.function() != null) {
                        if (tc.function().name() != null) chars += tc.function().name().length();
                        if (tc.function().arguments() != null) chars += tc.function().arguments().length();
                    }
                }
            }
        }
        return chars / 4; // rough approximation: ~4 chars per token
    }

    /**
     * Drain the conversation queue after processing completes, then re-process
     * any waiting messages on a virtual thread. Each drained message gets a
     * full {@link #run} invocation (which handles its own queue acquisition,
     * user-message persistence, LLM call, and response persistence). For
     * external channels (Telegram, Slack, WhatsApp), the response is dispatched
     * back through the channel. For web, the response is persisted to the DB
     * and the user sees it on next conversation load.
     *
     * <p>Called from the {@code finally} block of both {@link #run} and
     * {@link #runStreaming}. Failures are logged but never propagated — the
     * primary request must not fail because a queued message's re-processing
     * fails.
     */
    private static void processQueueDrain(Long conversationId) {
        var drained = services.ConversationQueue.drain(conversationId);
        if (drained.isEmpty()) return;

        Thread.ofVirtual().start(() -> {
            var combined = services.ConversationQueue.formatCollectedMessages(drained);
            var msg = drained.getFirst(); // channel info is the same for all queued messages
            try {
                var conversation = services.Tx.run(() -> ConversationService.findById(conversationId));
                if (conversation == null) return;
                var result = run(msg.agent(), conversation, combined);
                dispatchToChannel(msg.channelType(), msg.peerId(), result.response());
            } catch (Exception e) {
                EventLogger.error("queue", msg.agent().name, msg.channelType(),
                        "Failed to process queued message: %s".formatted(e.getMessage()));
            }
        });
    }

    /**
     * Shared webhook message handler: resolve agent route, find/create conversation,
     * run the agent synchronously, and send the response via the provided sender.
     * Used by Slack, Telegram, and WhatsApp webhook controllers.
     *
     * @param channelType  channel identifier ("slack", "telegram", "whatsapp")
     * @param peerId       channel-specific peer/chat ID
     * @param text         inbound message text
     * @param sendResponse callback to deliver the response (receives peerId and response text)
     * @param sendNoRoute  callback when no agent is routed (receives peerId); may be null to silently drop
     */
    public static void processWebhookMessage(String channelType, String peerId, String text,
                                              BiConsumer<String, String> sendResponse,
                                              Consumer<String> sendNoRoute) {
        var route = services.Tx.run(() -> AgentRouter.resolve(channelType, peerId));
        if (route == null) {
            if (sendNoRoute != null) sendNoRoute.accept(peerId);
            return;
        }

        var conversation = services.Tx.run(() ->
                ConversationService.findOrCreate(route.agent(), channelType, peerId));
        var result = run(route.agent(), conversation, text);
        sendResponse.accept(peerId, result.response());
    }

    /**
     * Best-effort delivery of a response to an external channel. Web channel
     * responses are already persisted to the DB by {@link #run} — the user sees
     * them on next conversation load or refresh. External channels need explicit
     * dispatch because there is no persistent connection to push through.
     */
    private static void dispatchToChannel(String channelType, String peerId, String text) {
        if (peerId == null || text == null) return;
        try {
            var type = ChannelType.fromValue(channelType);
            if (type == null) return;
            var channel = type.resolve();
            if (channel != null) {
                channel.sendWithRetry(peerId, text);
            }
        } catch (Exception e) {
            EventLogger.error("channel", null, channelType,
                    "Failed to dispatch queued response to %s/%s: %s"
                            .formatted(channelType, peerId, e.getMessage()));
        }
    }

    /**
     * Return {@code true} when a streaming finish_reason signals the model
     * exhausted its output token budget mid-response. OpenAI-compatible routes
     * emit {@code "length"}; Anthropic-native (and OpenRouter's Bedrock route
     * for Anthropic models) emit {@code "max_tokens"}. Both must be treated as
     * truncation — if only {@code "length"} is matched, Bedrock-routed Claude
     * tool-call deltas get dispatched with incomplete JSON args and the
     * downstream tool fails with a cryptic Gson EOFException. Visible for the
     * {@code AgentRunnerTest} coverage; kept here so the canonical list of
     * truncation values lives next to the guards that consume it.
     */
    public static boolean isTruncationFinish(String finishReason) {
        return "length".equals(finishReason) || "max_tokens".equals(finishReason);
    }

    /**
     * Extract markdown image URLs from tool results and stream them directly to the frontend.
     * This ensures rendered images (screenshots, QR codes, etc.) appear in the chat
     * regardless of whether the LLM includes them in its response.
     *
     * <p>Exposed for unit tests; not part of the public runner API.
     */
    public static void extractImageUrls(String toolResult, List<String> collectedImages) {
        if (collectedImages == null || toolResult == null) return;
        var matcher = IMAGE_URL_PATTERN.matcher(toolResult);
        while (matcher.find()) {
            collectedImages.add(matcher.group(0));
        }
    }

    /**
     * Build a leading image block containing only collected images whose filename
     * is NOT already present in {@code content}. Prevents double-rendering when the
     * LLM echoes (or mis-echoes) the markdown image from a tool result.
     *
     * <p>The dedup matches by <em>filename</em> rather than full URL. Filenames in
     * JClaw are timestamp-suffixed (e.g. {@code screenshot-1713100000000.png}) so
     * collisions are effectively impossible, and any LLM reply that references the
     * filename in any form — the original URL, a rewritten path, a plain-text
     * mention, or a fabricated {@code <img>} tag — will be caught as "already
     * rendered" and the prepended copy will be skipped. This is strictly looser
     * than full-URL substring matching and catches LLM reply drift that the older
     * exact-URL check missed.
     *
     * <p>When the LLM drops the image entirely, every collected image survives
     * the filter and the prefix acts as a safety net so the user still sees it.
     *
     * <p>Exposed for unit tests; not part of the public runner API.
     */
    public static String buildImagePrefix(List<String> collectedImages, String content) {
        if (collectedImages == null || collectedImages.isEmpty()) return "";
        var safeContent = content != null ? content : "";
        var missing = new ArrayList<String>();
        for (var img : collectedImages) {
            var m = PAREN_URL_PATTERN.matcher(img);
            if (m.find()) {
                var fullUrl = m.group(1);
                var slash = fullUrl.lastIndexOf('/');
                var filename = slash >= 0 ? fullUrl.substring(slash + 1) : fullUrl;
                if (!filename.isEmpty() && safeContent.contains(filename)) continue;
            }
            missing.add(img);
        }
        return missing.isEmpty() ? "" : String.join("\n\n", missing) + "\n\n";
    }

    /**
     * Safely extract string content from a {@link ChatMessage#content()} which may
     * be a {@code String} or a multi-part content array (vision). Returns empty
     * string if content is null or a non-string type that can't be converted.
     */
    private static String contentAsString(Object content) {
        if (content instanceof String s) return s;
        if (content == null) return "";
        // Multi-part content (e.g. vision blocks): extract text parts
        if (content instanceof List<?> parts) {
            var sb = new StringBuilder();
            for (var part : parts) {
                if (part instanceof Map<?,?> m && m.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static List<ToolCall> parseToolCalls(String json) {
        try {
            var tc = gson.fromJson(json, ToolCall.class);
            return tc != null ? List.of(tc) : List.of();
        } catch (Exception _) {
            return List.of();
        }
    }
}
