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
import java.util.LinkedHashMap;
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
    // Matches any image-markdown embed, not just /api/ URLs. Used by buildImagePrefix
    // to distinguish "LLM re-embedded the image" (suppress prepend) from "LLM
    // mentioned the filename/URL as text" (still prepend — the user wants both
    // an inline image AND a textual link reference).
    private static final Pattern ANY_IMAGE_EMBED =
            Pattern.compile("!\\[[^\\]]*\\]\\(([^)]+)\\)");
    // Matches HTML <img src="..."> embeds (single, double, or unquoted src).
    // LLMs occasionally emit HTML instead of markdown; catching both ensures a
    // re-embed in either form suppresses the prepend and prevents duplicate
    // rendering.
    private static final Pattern HTML_IMG_EMBED =
            Pattern.compile("<img\\s[^>]*?src\\s*=\\s*[\"']?([^\"'\\s>]+)",
                    Pattern.CASE_INSENSITIVE);

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
        // Non-streaming callers (TaskPollerJob, background) have no pre-runner
        // queue-accept timestamp, so queue_wait is naturally skipped. Every other
        // segment is captured, which is why scheduled turns now show up in the
        // Chat Performance dashboard (channel-partitioned per JCLAW-102).
        var trace = LatencyTrace.forTurn(conversation.channelType, null);
        trace.mark(LatencyTrace.PROLOGUE_REQUEST_PARSED);

        try {
            // Short setup transaction: persist user message, assemble prompt, resolve provider.
            // Re-fetch the conversation by ID so it is managed in this persistence context.
            // Callers on virtual threads (TaskPollerJob, webhooks) pass entities that were
            // loaded in a separate, already-committed Tx.run() — those are detached and
            // would throw PersistentObjectException on save().
            var prepared = services.Tx.run(() -> {
                var conv = ConversationService.findById(conversationId);
                ConversationService.appendUserMessage(conv, userMessage);
                trace.mark(LatencyTrace.PROLOGUE_CONV_RESOLVED);

                var assembled = SystemPromptAssembler.assemble(agent, userMessage, null, conv.channelType);
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
                trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);
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

            trace.mark(LatencyTrace.PROLOGUE_DONE);
            // LLM call loop — no transaction open, JDBC connection back in pool
            var response = callWithToolLoop(agent, conversationId,
                    prepared.messages(), prepared.tools(), prepared.primary(), prepared.secondary());
            trace.mark(LatencyTrace.STREAM_BODY_END);

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
            trace.mark(LatencyTrace.TERMINAL_SENT);
            trace.end();
            EventLogger.flush();
            processQueueDrain(conversationId);
        }
    }

    /**
     * Run the agent with streaming. Resolves the conversation inside the virtual thread
     * so it commits in its own transaction before inserting messages.
     *
     * <p>{@code acceptedAtNs} is the {@code System.nanoTime()} of when the inbound
     * message entered the process. Web controllers forward the Netty-set stamp so
     * {@code queue_wait} can be measured; Telegram polling, scheduled tasks, and
     * other channels without a pre-runner timestamp pass {@code null}. A
     * {@link LatencyTrace} is always constructed inside the virtual thread, so
     * every channel contributes to the performance histograms (JCLAW performance
     * dashboard).
     */
    public static void runStreaming(Agent agent, Long conversationId, String channelType, String peerId,
                                    String userMessage,
                                    AtomicBoolean isCancelled,
                                    StreamingCallbacks cb,
                                    Long acceptedAtNs) {
        Thread.ofVirtual().start(() -> {
            final Long[] conversationIdRef = {null};
            var trace = LatencyTrace.forTurn(channelType, acceptedAtNs);
            trace.mark(LatencyTrace.PROLOGUE_REQUEST_PARSED);
            var tracedCb = wrapCallbacksWithTrace(cb, trace);
            try {
                // Phase 1: Resolve conversation, acquire queue, persist user message
                var conversation = resolveConversationAndAcquireQueue(
                        agent, conversationId, channelType, peerId, userMessage, tracedCb);
                if (conversation == null) return; // queued, not-found, or error — already handled
                conversationIdRef[0] = conversation.id;

                trace.mark(LatencyTrace.PROLOGUE_CONV_RESOLVED);

                if (checkCancelled(isCancelled, agent, channelType)) return;

                // Phase 2: Assemble prompt, resolve provider, call LLM in streaming loop
                streamLlmLoop(agent, conversation, channelType, userMessage, isCancelled, tracedCb, trace);

            } catch (Exception e) {
                EventLogger.error("llm", agent.name, channelType,
                        "Streaming error: %s".formatted(e.getMessage()));
                tracedCb.onError().accept(e);
            } finally {
                EventLogger.flush();
                if (conversationIdRef[0] != null) {
                    processQueueDrain(conversationIdRef[0]);
                }
            }
        });
    }

    /**
     * Wrap a caller's {@link StreamingCallbacks} so the trace captures the three
     * boundaries only the transport layer knows about:
     * {@code FIRST_TOKEN} on the first token write, and {@code TERMINAL_SENT} +
     * {@link LatencyTrace#end()} after the caller's onComplete/onError handler
     * returns (i.e. after the final SSE frame, Telegram edit, etc. is delivered).
     * Invoking the caller's handler first ensures the mark captures the
     * post-delivery timestamp, matching the pre-refactor web behavior.
     */
    private static StreamingCallbacks wrapCallbacksWithTrace(StreamingCallbacks cb, LatencyTrace trace) {
        var firstTokenSeen = new AtomicBoolean(false);
        return new StreamingCallbacks(
                cb.onInit(),
                token -> {
                    if (firstTokenSeen.compareAndSet(false, true)) {
                        trace.mark(LatencyTrace.FIRST_TOKEN);
                    }
                    cb.onToken().accept(token);
                },
                cb.onReasoning(),
                cb.onStatus(),
                content -> {
                    try { cb.onComplete().accept(content); }
                    finally { trace.mark(LatencyTrace.TERMINAL_SENT); trace.end(); }
                },
                error -> {
                    try { cb.onError().accept(error); }
                    finally { trace.mark(LatencyTrace.TERMINAL_SENT); trace.end(); }
                }
        );
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
            var convo = ConversationService.findById(conversation.id);
            var assembled0 = SystemPromptAssembler.assemble(agent, userMessage, disabledTools, convo.channelType);
            var msgs = buildMessages(assembled0.systemPrompt(), convo);
            msgs = trimToContextWindow(msgs, agent, primaryRef);
            var toolDefs = ToolRegistry.getToolDefsForAgent(disabledTools);
            return new PreparedPrologue(assembled0, msgs, toolDefs);
        });

        var assembled = prepared.assembled();
        var messages = prepared.messages();

        if (checkCancelled(isCancelled, agent, channelType)) return;

        trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);

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
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        var streamStartMs = System.currentTimeMillis();
        // Turn-level cumulative usage. Each LLM round (first round here, plus
        // any tool-result continuation rounds inside handleToolCallsStreaming)
        // folds its per-round usage into this object. buildUsageJson below
        // reads from this cumulative so stats pills reflect the whole turn,
        // not just round 1 (JCLAW-76).
        var turnUsage = new LlmProvider.TurnUsage();
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

        // Round 1 folded into turn-level usage. The first-round accumulator is
        // also kept in scope so buildUsageJson can read reasoningDurationMs
        // from it (that timing is anchored to round 1 per JCLAW-76 AC7).
        turnUsage.addRound(accumulator);
        var firstRoundAccumulator = accumulator;
        var content = accumulator.content;

        // Check for truncated response (max tokens hit mid-tool-call)
        if (isTruncationFinish(accumulator.finishReason) && !accumulator.toolCalls.isEmpty()) {
            EventLogger.warn("llm", agent.name, channelType,
                    "Response truncated (finish_reason=length) with pending tool calls — skipping execution of incomplete tool arguments");
            var truncMsg = content.isEmpty()
                    ? "I tried to use a tool but my response was too long and got cut off. Let me try a more concise approach."
                    : content;
            cb.onToken().accept(truncMsg.equals(content) ? "" : "\n\n*[Response was truncated — retrying with a simpler approach]*");
            trace.mark(LatencyTrace.STREAM_BODY_END);
            var finalContent = truncMsg;
            // JCLAW-100: run the DB persist concurrently with the terminal
            // delivery. No data dependency between them, and the terminal
            // emit blocks on a Telegram Bot API round-trip (~500 ms p99) for
            // Telegram turns — so piggy-backing persist on that window hides
            // its ~8 ms entirely from the user-visible path. We still join
            // before returning, because the outer runStreaming finally →
            // processQueueDrain needs the conversation committed so the next
            // queued turn loads fresh history.
            long truncPersistStartNs = System.nanoTime();
            var truncPersistFuture = Thread.ofVirtual().start(() -> {
                services.Tx.run(() -> {
                    var conv = ConversationService.findById(conversation.id);
                    ConversationService.appendAssistantMessage(conv, finalContent, null);
                });
                utils.LatencyStats.record(channelType, "persist",
                        (System.nanoTime() - truncPersistStartNs) / 1_000_000L);
            });
            cb.onComplete().accept(finalContent);
            try { truncPersistFuture.join(); }
            catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            return;
        }

        // Handle tool calls if present
        if (!accumulator.toolCalls.isEmpty()) {
            content = handleToolCallsStreaming(agent, conversation.id, messages, tools,
                    accumulator.toolCalls, content, primary, cb, maxTokens, thinkingMode, 0,
                    isCancelled, trace, turnUsage);
        }

        if (checkCancelled(isCancelled, agent, channelType)) return;

        trace.mark(LatencyTrace.STREAM_BODY_END);

        // Build usage JSON before persisting so it can be stored alongside the message
        var usageJson = buildUsageJson(turnUsage, firstRoundAccumulator, modelInfo, streamStartMs);

        // JCLAW-100: persist the assistant message concurrently with the
        // terminal delivery. No data dependency between them, and the
        // terminal emit blocks on a Telegram Bot API round-trip (~500 ms
        // p99) for Telegram turns — so running persist in parallel hides
        // its ~8 ms entirely from the user-visible path. The join below
        // ensures the outer runStreaming finally → processQueueDrain still
        // observes a committed conversation before any queued follow-up
        // turn runs.
        var finalContent = content;
        var finalReasoning = turnUsage.reasoningText();
        long persistStartNs = System.nanoTime();
        var persistFuture = Thread.ofVirtual().start(() -> {
            services.Tx.run(() -> {
                var conv = ConversationService.findById(conversation.id);
                ConversationService.appendAssistantMessage(conv, finalContent, null, usageJson, finalReasoning);
            });
            utils.LatencyStats.record(channelType, "persist",
                    (System.nanoTime() - persistStartNs) / 1_000_000L);
        });
        emitUsageAndComplete(agent, channelType, content, turnUsage, streamStartMs, usageJson, cb);
        try { persistFuture.join(); }
        catch (InterruptedException _) { Thread.currentThread().interrupt(); }
    }

    /**
     * Poll an accumulator for completion, checking for cancellation every 5 s.
     * Returns {@code true} if the accumulator completed, {@code false} if
     * cancelled (in which case the cancellation has already been logged).
     */
    /**
     * Resolve the reasoning-token count to surface in usage metrics. Prefers the
     * summed provider-reported {@code reasoning_tokens} across every LLM round in
     * the turn when available; falls back to a character-length estimate over
     * the streamed reasoning text (≈4 chars per token, the standard English
     * heuristic) when every round reported zero despite reasoning having been
     * detected on at least one round. Returning an estimate — clearly non-zero
     * when reasoning ran — is better than showing no count at all, since the
     * UI's reasoning-count badge is gated on this value being truthy.
     */
    private static int effectiveReasoningTokens(LlmProvider.TurnUsage turnUsage) {
        if (turnUsage.reasoningTokens > 0) return turnUsage.reasoningTokens;
        if (!turnUsage.reasoningDetected) return 0;
        var chars = turnUsage.reasoningChars;
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
    /**
     * Build the usage JSON string from the turn-level cumulative token counts.
     * Token fields are summed across every LLM round in the turn (JCLAW-76);
     * {@code reasoningDurationMs} remains a per-first-round measurement on
     * {@code firstRoundAccumulator} since the reasoning phase timing is
     * anchored to the first round where reasoning primarily happens in
     * practice. Returns a compact JSON with just duration fields when no
     * round reported provider usage.
     */
    public static String buildUsageJson(LlmProvider.TurnUsage turnUsage,
                                        LlmProvider.StreamAccumulator firstRoundAccumulator,
                                        ModelInfo modelInfo, long streamStartMs) {
        var durationMs = System.currentTimeMillis() - streamStartMs;
        var reasoningMs = firstRoundAccumulator != null ? firstRoundAccumulator.reasoningDurationMs() : 0L;
        if (!turnUsage.hasProviderUsage) {
            return reasoningMs > 0L
                    ? "{\"durationMs\":%d,\"reasoningDurationMs\":%d}".formatted(durationMs, reasoningMs)
                    : "{\"durationMs\":%d}".formatted(durationMs);
        }

        var reasoningCount = effectiveReasoningTokens(turnUsage);
        var usageMap = new com.google.gson.JsonObject();
        usageMap.addProperty("prompt", turnUsage.promptTokens);
        usageMap.addProperty("completion", turnUsage.completionTokens);
        usageMap.addProperty("total", turnUsage.totalTokens);
        usageMap.addProperty("reasoning", reasoningCount);
        usageMap.addProperty("cached", turnUsage.cachedTokens);
        usageMap.addProperty("cacheCreation", turnUsage.cacheCreationTokens);
        usageMap.addProperty("durationMs", durationMs);
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
                                              LlmProvider.TurnUsage turnUsage,
                                              long streamStartMs, String usageJson,
                                              StreamingCallbacks cb) {
        var durationMs = System.currentTimeMillis() - streamStartMs;
        if (turnUsage.hasProviderUsage) {
            var reasoningCount = effectiveReasoningTokens(turnUsage);
            var extras = new StringBuilder();
            if (reasoningCount > 0) extras.append(", %d reasoning".formatted(reasoningCount));
            if (turnUsage.cachedTokens > 0) extras.append(", %d cached".formatted(turnUsage.cachedTokens));
            if (turnUsage.cacheCreationTokens > 0) extras.append(", %d cache-write".formatted(turnUsage.cacheCreationTokens));
            var usageSummary = " [%d prompt, %d completion, %d total tokens%s, %.1fs]".formatted(
                    turnUsage.promptTokens, turnUsage.completionTokens, turnUsage.totalTokens,
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
                                                    LatencyTrace trace,
                                                    LlmProvider.TurnUsage turnUsage) {
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
        trace.addToolRound((System.nanoTime() - toolRoundStartNs) / 1_000_000L);

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

        // Fold this round's usage into the turn-level cumulative (JCLAW-76).
        // Runs regardless of whether the round resolves to more tool calls,
        // truncation, synthesis, or empty-retry — every round contributes.
        turnUsage.addRound(accumulator);

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
                    round + 1, isCancelled, trace, turnUsage);
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

            // Retry round is a real LLM call — its usage counts too (JCLAW-76).
            turnUsage.addRound(retry);

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
     * Execute a batch of tool calls, honoring a three-tier scheduling model
     * that preserves ordering for stateful tools while keeping independent
     * calls parallel. Results are always committed in the LLM's declared
     * order so {@code tool_call_id → tool_result} pairing matches the
     * pre-parallel history exactly.
     *
     * <p><b>Scheduling model</b> (JCLAW-80 + JCLAW-81):
     * <ul>
     *   <li><b>Parallel-safe tool</b> (opt-in via {@link
     *   ToolRegistry.Tool#parallelSafe()}): each call gets its own virtual
     *   thread — the pre-v0.7.13 behavior. Appropriate for stateless HTTP
     *   clients ({@code web_fetch}, {@code web_search}), pure-compute helpers
     *   ({@code date_time}), and validators ({@code checklist}).</li>
     *   <li><b>Non-parallel-safe tool</b>: calls are grouped by tool name
     *   into a single virtual thread and run sequentially in declared order.
     *   This is the JCLAW-80 fix — the LLM's declared call order is the
     *   authoritative contract for stateful tools (browser Page, shell cwd,
     *   workspace writers) because racing them gives screenshot-before-navigate
     *   class bugs.</li>
     *   <li><b>Across tool-name groups</b> (whether safe or unsafe): always
     *   parallel. Different tools touch different state, so there's no
     *   correctness reason to serialize them.</li>
     * </ul>
     *
     * <p>Single-tool batches skip the virtual-thread overhead and execute
     * inline on the caller. Cancellation is honored — in-flight tools finish
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
            // Partition calls into work units:
            //   - parallel-safe tools → one work unit per CALL (each races freely)
            //   - non-parallel-safe tools → one work unit per tool-NAME group
            //     (calls within it run sequentially in declared order)
            // LinkedHashMap preserves first-occurrence order so the unsafe
            // groups, like the safe singletons, see their declared positions.
            var unsafeGroups = new LinkedHashMap<String, List<Integer>>();
            var safeCalls = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                var name = toolCalls.get(i).function().name();
                if (ToolRegistry.isParallelSafe(name)) {
                    safeCalls.add(i);
                } else {
                    unsafeGroups.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
                }
            }

            int workUnits = safeCalls.size() + unsafeGroups.size();
            var latch = new CountDownLatch(workUnits);

            // One virtual thread per parallel-safe call — full concurrency.
            for (int idx : safeCalls) {
                final int i = idx;
                Thread.ofVirtual().start(() -> {
                    try {
                        if (isCancelled != null && isCancelled.get()) return;
                        var tc = toolCalls.get(i);
                        try {
                            results[i] = runToolCall(tc, agent, onStatus);
                        } catch (Exception e) {
                            EventLogger.error("tool", agent.name, null,
                                    "Tool '%s' threw: %s"
                                            .formatted(tc.function().name(), e.getMessage()));
                            results[i] = "Error executing tool: " + e.getMessage();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // One virtual thread per non-parallel-safe tool-name group —
            // calls within execute sequentially in declared order.
            for (var group : unsafeGroups.values()) {
                Thread.ofVirtual().start(() -> {
                    try {
                        for (int idx : group) {
                            if (isCancelled != null && isCancelled.get()) break;
                            var tc = toolCalls.get(idx);
                            try {
                                results[idx] = runToolCall(tc, agent, onStatus);
                            } catch (Exception e) {
                                EventLogger.error("tool", agent.name, null,
                                        "Tool '%s' threw: %s"
                                                .formatted(tc.function().name(), e.getMessage()));
                                results[idx] = "Error executing tool: " + e.getMessage();
                            }
                        }
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
                dispatchToChannel(msg.agent(), msg.channelType(), msg.peerId(), result.response());
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
     * Binding-first inbound dispatch used by the Telegram channel. The caller has
     * already resolved (bot token → binding → agent) and verified the sender, so
     * we skip {@link AgentRouter} entirely and hand the message straight to the
     * bound agent. Conversation persistence still keys on (agent, channelType,
     * peerId) so history per end-user is preserved.
     */
    public static void processInboundForAgent(Agent agent, String channelType, String peerId,
                                               String text, BiConsumer<String, String> sendResponse) {
        // JCLAW-26: intercept slash commands before the LLM round. /new creates
        // a fresh conversation and short-circuits; /reset + /help mutate the
        // current conversation and short-circuit. Unknown /foo falls through.
        var slashCmd = slash.Commands.parse(text);
        if (slashCmd.isPresent()) {
            Conversation current = slashCmd.get() == slash.Commands.Command.NEW
                    ? null
                    : services.Tx.run(() -> ConversationService.findOrCreate(agent, channelType, peerId));
            var result = slash.Commands.execute(slashCmd.get(), agent, channelType, peerId, current);
            sendResponse.accept(peerId, result.responseText());
            return;
        }
        var conversation = services.Tx.run(() ->
                ConversationService.findOrCreate(agent, channelType, peerId));
        var result = run(agent, conversation, text);
        sendResponse.accept(peerId, result.response());
    }

    /**
     * Streaming-aware counterpart to {@link #processInboundForAgent}: wires
     * LLM tokens into a {@link channels.TelegramStreamingSink} so the user
     * sees progressive edits rather than waiting for the full response
     * (JCLAW-94). {@link #runStreaming} starts its own virtual thread, so
     * this call returns immediately after queue acquisition; the sink is
     * sealed asynchronously when the LLM call completes.
     *
     * <p>The sink receives text-only tokens (reasoning and status callbacks
     * are no-ops — Telegram has no separate surface for them yet). On
     * completion the full response is handed to {@link channels.TelegramStreamingSink#seal},
     * which either performs one final HTML edit or falls back to the
     * chunked planner path for oversize / media-rich responses.
     */
    public static void processInboundForAgentStreaming(
            Agent agent, String channelType, String peerId, String text,
            java.util.function.Function<Long, channels.TelegramStreamingSink> sinkFactory) {
        // JCLAW-26: intercept slash commands before the LLM round. Reuse the
        // existing sink machinery to deliver the canned response — an unused
        // sink's seal() path falls through to TelegramChannel.sendMessage,
        // which keeps the bot-token / chat-id routing owned by the caller.
        var slashCmd = slash.Commands.parse(text);
        if (slashCmd.isPresent()) {
            Conversation current = slashCmd.get() == slash.Commands.Command.NEW
                    ? null
                    : services.Tx.run(() -> ConversationService.findOrCreate(agent, channelType, peerId));
            var result = slash.Commands.execute(slashCmd.get(), agent, channelType, peerId, current);
            var slashSink = sinkFactory.apply(
                    result.conversation() != null ? result.conversation().id : null);
            slashSink.seal(result.responseText());
            return;
        }
        var conversation = services.Tx.run(() ->
                ConversationService.findOrCreate(agent, channelType, peerId));
        // The sink needs the conversation id so it can persist / clear the
        // stream checkpoint (JCLAW-95). Callers supply a factory — they own
        // botToken / chatId, we own conversation lookup.
        var sink = sinkFactory.apply(conversation.id);
        // JCLAW-98: show Telegram's native typing indicator during the
        // prologue gap (request received → first token). Cancels itself
        // on first sink.update(), seal(), or errorFallback(). Intentionally
        // NOT started for slash commands above — their responses are
        // instant and don't benefit from the indicator.
        sink.startTypingHeartbeat();
        var isCancelled = services.ConversationQueue.cancellationFlag(conversation.id);
        var cb = new StreamingCallbacks(
                _ -> {},                 // onInit — nothing to do for Telegram
                sink::update,            // onToken — live preview edits
                _ -> {},                 // onReasoning — not surfaced on Telegram
                _ -> {},                 // onStatus — not surfaced on Telegram
                sink::seal,              // onComplete — final edit / planner fallback
                sink::errorFallback);    // onError — delete placeholder + send error
        runStreaming(agent, conversation.id, channelType, peerId, text,
                isCancelled, cb, null);
    }

    /**
     * Best-effort delivery of a response to an external channel. Web channel
     * responses are already persisted to the DB by {@link #run} — the user sees
     * them on next conversation load or refresh. External channels need explicit
     * dispatch because there is no persistent connection to push through.
     *
     * <p>Telegram is special: outbound needs a specific bot token, so we look up
     * the matching {@link models.TelegramBinding} by (agent, peerId) instead of
     * using the generic {@link Channel} interface.
     */
    private static void dispatchToChannel(Agent agent, String channelType, String peerId, String text) {
        if (peerId == null || text == null) return;
        try {
            var type = ChannelType.fromValue(channelType);
            if (type == null) return;
            if (type == ChannelType.TELEGRAM) {
                var binding = services.Tx.run(() ->
                        models.TelegramBinding.findEnabledByAgentAndUser(agent, peerId));
                if (binding == null) {
                    EventLogger.warn("channel", agent != null ? agent.name : null, "telegram",
                            "No enabled binding for (agent=%s, userId=%s); dropping queued response"
                                    .formatted(agent != null ? agent.name : "?", peerId));
                    return;
                }
                channels.TelegramChannel.sendMessage(binding.botToken, peerId, text, agent);
                return;
            }
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
     * Build a leading image block containing only collected images that the LLM
     * reply has not already embedded as an image. Prevents double-rendering when
     * the LLM echoes the image from a tool result — in either markdown
     * ({@code ![alt](url)}) or HTML ({@code <img src="url">}) form — but does
     * <em>not</em> suppress the prepend when the LLM merely references the URL
     * or filename as a plain text link. In the text-mention case the user
     * expects to see both the inline image AND the link reference, so the
     * prepend still fires.
     *
     * <p>A collected image is suppressed when its filename appears inside any
     * {@code ![...](...)} or {@code <img src=...>} in the reply. Filenames in
     * JClaw are timestamp-suffixed (e.g. {@code screenshot-1713100000000.png})
     * so collisions are effectively impossible, and an LLM reply that re-embeds
     * the image with a rewritten path (e.g.
     * {@code ![alt](./workspace/screenshot-1713100000000.png)}) is still caught
     * by filename match.
     *
     * <p>When the LLM drops the image entirely, every collected image survives
     * the filter and the prefix acts as a safety net so the user still sees it.
     *
     * <p>Exposed for unit tests; not part of the public runner API.
     */
    public static String buildImagePrefix(List<String> collectedImages, String content) {
        if (collectedImages == null || collectedImages.isEmpty()) return "";
        var safeContent = content != null ? content : "";

        // Collect filenames of images already embedded (as markdown OR HTML) in
        // the reply. Plain-text URL / filename / link mentions are intentionally
        // NOT collected here — that's the behavioral distinction from the prior
        // filename-substring dedup.
        var embeddedFilenames = new java.util.HashSet<String>();
        for (var pattern : List.of(ANY_IMAGE_EMBED, HTML_IMG_EMBED)) {
            var m = pattern.matcher(safeContent);
            while (m.find()) {
                var url = m.group(1);
                var slash = url.lastIndexOf('/');
                var fn = slash >= 0 ? url.substring(slash + 1) : url;
                if (!fn.isEmpty()) embeddedFilenames.add(fn);
            }
        }

        var missing = new ArrayList<String>();
        for (var img : collectedImages) {
            var m = PAREN_URL_PATTERN.matcher(img);
            if (m.find()) {
                var fullUrl = m.group(1);
                var slash = fullUrl.lastIndexOf('/');
                var filename = slash >= 0 ? fullUrl.substring(slash + 1) : fullUrl;
                if (!filename.isEmpty() && embeddedFilenames.contains(filename)) continue;
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
