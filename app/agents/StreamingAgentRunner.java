package agents;

import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ToolDef;
import llm.ProviderRegistry;
import memory.MemoryAutoCapture;
import models.Agent;
import models.ChannelType;
import models.Conversation;
import services.AttachmentService;
import services.ConversationQueue;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import utils.LatencyStats;
import utils.LatencyTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JCLAW-678: the streaming execution engine extracted from {@link AgentRunner}.
 * Owns the {@code agent-stream} virtual thread, the prologue/queue phase, the
 * streaming LLM call loop (round-1 retry + audio-format fallback, tool-call
 * continuation, truncation handling, usage reporting), and the terminal-callback
 * trace wrapper. {@link AgentRunner#runStreaming} is a thin delegator into
 * {@link #runStreaming}; the public {@link AgentRunner.StreamingCallbacks},
 * {@link AgentRunner.ToolCallEvent}, and {@link AgentRunner.RunResult} records stay
 * declared on {@link AgentRunner}.
 */
final class StreamingAgentRunner {

    private StreamingAgentRunner() {}

    /** Outcome label for the {@code AUDIO_PASSTHROUGH_OUTCOME} log when the audio passthrough fails (provider error or no usable transcript). */
    private static final String OUTCOME_ERROR = "error";

    /**
     * Run the agent with streaming. Resolves the conversation inside the virtual thread
     * so it commits in its own transaction before inserting messages. Entry point for
     * {@link AgentRunner#runStreaming}.
     *
     * @param agent          the executing agent
     * @param conversationId persisted Conversation id, or {@code null} when
     *                       the channel hasn't created one yet (the runner
     *                       creates one inside the virtual thread)
     * @param channelType    {@code "web"}, {@code "telegram"},
     *                       {@code "slack"}, {@code "whatsapp"}
     * @param peerId         channel-specific peer identifier
     * @param userMessage    the user's input text
     * @param isCancelled    flag the transport flips on disconnect / Telegram /cancel
     * @param cb             event callbacks for SSE / streaming transports
     * @param acceptedAtNs   {@code System.nanoTime()} at process entry, or {@code null}
     * @param attachments    per-file metadata the frontend roundtripped, or {@code null}
     */
    @SuppressWarnings("java:S107") // Streaming entrypoint signature retained for binary compat with channel callers
    static void runStreaming(Agent agent, Long conversationId, String channelType, String peerId,
                             String userMessage,
                             AtomicBoolean isCancelled,
                             AgentRunner.StreamingCallbacks cb,
                             Long acceptedAtNs,
                             List<AttachmentService.Input> attachments) {
        Thread.ofVirtual().name("agent-stream").start(() -> {
            final Long[] conversationIdRef = {null};
            // queueReleased is shared between the wrapper's terminal
            // callbacks (which do the early release before cb.onComplete
            // flushes the SSE terminal) and this finally block (which does
            // a defense-in-depth release for exception paths that didn't
            // reach a terminal callback). The CAS inside releaseQueueOnce
            // guarantees exactly one drain per turn regardless of which
            // path fires first.
            final var queueReleased = new AtomicBoolean(false);
            var trace = LatencyTrace.forTurn(channelType, acceptedAtNs);
            trace.mark(LatencyTrace.PROLOGUE_REQUEST_PARSED);
            var tracedCb = wrapCallbacksWithTrace(cb, trace, conversationIdRef, queueReleased);
            try {
                // Phase 1: Resolve conversation, acquire queue, persist user message
                var conversationOpt = resolveConversationAndAcquireQueue(
                        agent, conversationId, channelType, peerId, userMessage, tracedCb, attachments);
                if (conversationOpt.isEmpty()) return; // queued, not-found, or error — already handled
                var conversation = conversationOpt.get();
                conversationIdRef[0] = conversation.id;

                trace.mark(LatencyTrace.PROLOGUE_CONV_RESOLVED);
                trace.agentId(AgentRunner.agentIdOf(agent));

                if (CancellationManager.checkCancelled(isCancelled, agent, channelType, tracedCb)) return;

                // Phase 2: Assemble prompt, resolve provider, call LLM in streaming loop
                streamLlmLoop(agent, conversation, channelType, userMessage, isCancelled, tracedCb, trace);

            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                EventLogger.error("llm", agent.name, channelType,
                        "Streaming error: %s".formatted(e.getMessage()));
                tracedCb.onError().accept(e);
            } finally {
                EventLogger.flush();
                // Defense-in-depth: if a terminal callback already drained
                // the queue (the normal happy / error / cancel paths via
                // the wrapper's releaseQueueOnce), the CAS inside
                // releaseQueueOnce no-ops here. The block only fires for
                // exception paths that returned without invoking any
                // terminal callback.
                QueueDrainOrchestrator.releaseQueueOnce(conversationIdRef, queueReleased);
            }
        });
    }

    /**
     * Wrap a caller's {@link AgentRunner.StreamingCallbacks} so the trace captures the three
     * boundaries only the transport layer knows about:
     * {@code FIRST_TOKEN} on the first token write, and {@code TERMINAL_SENT} +
     * {@link LatencyTrace#end()} after the caller's onComplete/onError handler
     * returns (i.e. after the final SSE frame, Telegram edit, etc. is delivered).
     * Invoking the caller's handler first ensures the mark captures the
     * post-delivery timestamp, matching the pre-refactor web behavior.
     *
     * <p>Also handles the conversation-queue early release. The terminal
     * callbacks (onComplete / onError / onCancel) call
     * {@link QueueDrainOrchestrator#processQueueDrain} BEFORE invoking the wrapped caller's handler,
     * so the queue lock is freed before the SSE terminal frame flushes to
     * the wire. Without this, a fast client (e.g. the loadtest worker firing
     * back-to-back turns inside the same conversation) would observe the
     * SSE close, immediately fire the next request, and race the post-
     * onComplete finally block — the next {@code tryAcquire} would see
     * {@code processing=true} and short-circuit with the canned "queued"
     * response. The queueReleased CAS makes the release single-shot so the
     * outer finally block is a defense-in-depth path for exception cases.
     *
     * <p>{@code conversationIdRef} is captured as a 1-element array so the
     * caller can populate it AFTER the wrapper is constructed (during
     * {@link #resolveConversationAndAcquireQueue}). The terminal callbacks
     * read {@code conversationIdRef[0]} lazily — null means the queue
     * acquire never succeeded (e.g. the canned-queued response path),
     * so there's nothing to release.
     */
    private static AgentRunner.StreamingCallbacks wrapCallbacksWithTrace(AgentRunner.StreamingCallbacks cb, LatencyTrace trace,
                                                              Long[] conversationIdRef,
                                                              AtomicBoolean queueReleased) {
        var firstTokenSeen = new AtomicBoolean(false);
        return new AgentRunner.StreamingCallbacks(
                cb.onInit(),
                token -> {
                    if (firstTokenSeen.compareAndSet(false, true)) {
                        trace.mark(LatencyTrace.FIRST_TOKEN);
                    }
                    cb.onToken().accept(token);
                },
                cb.onReasoning(),
                cb.onStatus(),
                cb.onToolCall(),
                content -> {
                    QueueDrainOrchestrator.releaseQueueOnce(conversationIdRef, queueReleased);
                    try { cb.onComplete().accept(content); }
                    finally { trace.mark(LatencyTrace.TERMINAL_SENT); trace.end(); }
                },
                error -> {
                    QueueDrainOrchestrator.releaseQueueOnce(conversationIdRef, queueReleased);
                    try { cb.onError().accept(error); }
                    finally { trace.mark(LatencyTrace.TERMINAL_SENT); trace.end(); }
                },
                () -> {
                    QueueDrainOrchestrator.releaseQueueOnce(conversationIdRef, queueReleased);
                    try { cb.onCancel().run(); }
                    finally { trace.mark(LatencyTrace.TERMINAL_SENT); trace.end(); }
                }
        );
    }

    /**
     * Phase 1 of streaming: resolve or create conversation, acquire the
     * conversation queue, and persist the user message. Returns the conversation
     * or {@link Optional#empty()} if the request was queued, not found, or
     * errored (in which case callbacks have already been invoked).
     */
    private static Optional<Conversation> resolveConversationAndAcquireQueue(
            Agent agent, Long conversationId, String channelType, String peerId,
            String userMessage, AgentRunner.StreamingCallbacks cb,
            List<AttachmentService.Input> attachments) {

        Conversation conversation = Tx.run(() -> {
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
            return Optional.empty();
        }

        var queueMsg = new ConversationQueue.QueuedMessage(
                userMessage, channelType, peerId, agent);
        if (!ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
            cb.onInit().accept(conversation);
            cb.onComplete().accept(AgentRunner.QUEUED_MESSAGE_RESPONSE);
            return Optional.empty();
        }

        // JCLAW-21: route the user-message persist through ConversationSink.
        // Local sink construction here keeps this method's signature
        // unchanged; streamLlmLoop builds its own ConversationSink from
        // the returned Conversation for the post-LLM writes.
        AgentExecutionSink sink = new ConversationSink(conversation);
        Tx.run(() -> sink.appendUserMessage(userMessage, attachments));

        cb.onInit().accept(conversation);
        return Optional.of(conversation);
    }

    /**
     * Phase 2 of streaming: assemble the prompt, resolve the provider, and run
     * the streaming LLM call loop (including tool-call continuation, retry on
     * transient errors, truncation handling, and usage reporting).
     */
    private static void streamLlmLoop(Agent agent, Conversation conversation,
                                       String channelType, String userMessage,
                                       AtomicBoolean isCancelled, AgentRunner.StreamingCallbacks cb,
                                       LatencyTrace trace)
            throws InterruptedException {

        // JCLAW-21: streaming-side sink. Same construction shape as the
        // sync runAfterAcquire path; the user-message append already
        // happened inside resolveConversationAndAcquireQueue using its
        // own local sink, so this one only owns the post-LLM writes
        // (final assistant, per-tool-call via ParallelToolExecutor,
        // truncation-fallback persist).
        final AgentExecutionSink sink = new ConversationSink(conversation);

        EventLogger.info("llm", agent.name, channelType,
                "Streaming: assembling prompt for conversation id: %d".formatted(conversation.id));

        var primary = resolveStreamingProvider(agent, conversation, channelType, cb);
        if (primary == null) return;

        if (CancellationManager.checkCancelled(isCancelled, agent, channelType, cb)) return;

        var prepared = AgentPromptPreparer.buildStreamingPrologue(agent, conversation, channelType, userMessage);

        var modelInfoForAudioStream = ModelResolver.resolveModelInfo(agent, conversation, primary).orElse(null);
        var supportsAudioForStream = modelInfoForAudioStream != null && modelInfoForAudioStream.supportsAudio();
        var supportsVisionForStream = modelInfoForAudioStream != null && modelInfoForAudioStream.supportsVision();
        var messages = AgentPromptPreparer.applyMediaRewrite(agent, conversation, userMessage, primary, prepared,
                supportsAudioForStream, supportsVisionForStream);

        if (CancellationManager.checkCancelled(isCancelled, agent, channelType, cb)) return;

        trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);

        var tools = prepared.tools();
        var thinkingMode = ModelResolver.resolveThinkingMode(agent, conversation, primary);
        EventLogger.info("llm", agent.name, channelType,
                "Streaming: calling %s / %s (%d messages, %d tools, %d skills%s)"
                        .formatted(primary.config().name(), ModelResolver.effectiveModelId(agent, conversation),
                                messages.size(), tools.size(), prepared.assembled().skills().size(),
                                thinkingMode != null ? ", thinking=" + thinkingMode : ""));
        var maxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, primary, messages, tools);
        var modelInfo = ModelResolver.resolveModelInfo(agent, conversation, primary).orElse(null);

        // Stream with tool call handling (HTTP, no JPA)
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        var streamStartMs = System.currentTimeMillis();
        // Turn-level cumulative usage. Each LLM round (first round here, plus
        // any tool-result continuation rounds inside handleToolCallsStreaming)
        // folds its per-round usage into this object. buildUsageJson below
        // reads from this cumulative so stats pills reflect the whole turn,
        // not just round 1 (JCLAW-76).
        var turnUsage = new LlmProvider.TurnUsage();
        // JCLAW-108: route the actual LLM call through the effective modelId,
        // so conversation overrides take effect on the wire. Failover (line 706)
        // and tool-loop continuations (line 781) use the same effective id.
        var effectiveModelIdForCall = ModelResolver.effectiveModelId(agent, conversation);
        // Round-1 stream, with a transient-5xx retry and (JCLAW) an audio-format-rejection →
        // Whisper-transcript re-stream. When the audio fallback fires it rewrites the message to the
        // transcript and returns it, so the tool-call continuation loop below reuses the rewritten
        // (no-longer-audio) messages rather than re-sending the rejected audio.
        var round1 = streamRound1WithAudioFallback(primary, effectiveModelIdForCall, messages, tools, cb,
                maxTokens, thinkingMode, channelType, isCancelled, agent, conversation, prepared, supportsAudioForStream);
        if (round1 == null) return; // cancelled mid-stream
        var accumulator = round1.accumulator();
        messages = round1.messages();

        if (CancellationManager.checkCancelled(isCancelled, agent, channelType, cb)) return;

        // The AUDIO_PASSTHROUGH_OUTCOME log already fired inside the helper; here we only surface a
        // terminal round-1 error to the caller (a streamed error can't be un-sent, so this ends the turn).
        if (accumulator.error() != null) {
            cb.onError().accept(accumulator.error());
            return;
        }

        // Round 1 folded into turn-level usage. addRound also propagates
        // round-local reasoning/content timing into TurnUsage so the
        // turn-level reasoningDurationMs spans first-reasoning → first-content
        // across every round (matches the frontend's live measurement).
        turnUsage.addRound(accumulator);

        // Check for truncated response (max tokens hit mid-tool-call)
        if (TruncationDiagnostics.isTruncationFinish(accumulator.finishReason()) && !accumulator.toolCalls().isEmpty()) {
            persistAndCompleteTruncatedToolCall(accumulator.content(), agent, channelType, trace, sink, cb);
            return;
        }

        var post = runPostAccumulatorToolLoop(accumulator, agent, conversation, primary, channelType, messages, tools,
                cb, thinkingMode, isCancelled, trace, turnUsage, sink);

        if (CancellationManager.checkCancelled(isCancelled, agent, channelType, cb)) return;

        // JCLAW-273: parent agent yielded into an async subagent. No final
        // assistant message to persist or emit; the parent's logical turn
        // resumes later from tools.SubagentSpawnTool#runAsyncAndAnnounce
        // once the child terminates.
        if (AgentRunner.YIELDED_RESPONSE.equals(post.content())) {
            handleStreamingYield(agent, channelType, trace, cb);
            return;
        }

        trace.mark(LatencyTrace.STREAM_BODY_END);
        finalizeStreamingTurn(post.content(), post.replyTruncated(), turnUsage, modelInfo, streamStartMs,
                agent, conversation, channelType, trace, sink, cb);

        // JCLAW-39: async memory auto-capture for the completed streaming turn.
        // Placed after finalize (response persisted + terminal emitted) so it
        // never blocks delivery; the YIELDED_RESPONSE path above already returned.
        MemoryAutoCapture.captureAsync(agent, conversation.id, userMessage, post.content());
    }

    /**
     * Resolve the streaming LLM provider for an agent+conversation. Fires
     * {@code cb.onError} and returns {@code null} when no provider is configured.
     */
    private static LlmProvider resolveStreamingProvider(Agent agent, Conversation conversation,
                                                         String channelType, AgentRunner.StreamingCallbacks cb) {
        var agentProvider = ProviderRegistry.get(ModelResolver.effectiveModelProvider(agent, conversation));
        var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
        if (primary == null) {
            EventLogger.error("llm", agent.name, channelType, "No LLM provider configured");
            cb.onError().accept(new RuntimeException("No LLM provider configured"));
            return null;
        }
        return primary;
    }

    /**
     * Run the round-1 streaming call and retry once on transient HTTP 5xx errors.
     * Returns {@code null} when cancellation fired during either await.
     */
    @SuppressWarnings("java:S107") // Streaming first-round invocation needs the full call surface
    private static LlmProvider.StreamAccumulator streamFirstRoundWithRetry(
            LlmProvider primary, String effectiveModelIdForCall, List<ChatMessage> messages, List<ToolDef> tools,
            AgentRunner.StreamingCallbacks cb, Integer maxTokens, String thinkingMode, String channelType,
            AtomicBoolean isCancelled, Agent agent) throws InterruptedException {
        var accumulator = primary.chatStreamAccumulate(
                effectiveModelIdForCall, messages, tools, cb.onToken(), cb.onReasoning(),
                maxTokens, thinkingMode, channelType);

        if (!CancellationManager.awaitAccumulatorOrCancel(accumulator, isCancelled, agent, channelType, cb)) return null;

        // Retry once on transient 5xx errors
        if (accumulator.error() != null && accumulator.error().getMessage() != null
                && accumulator.error().getMessage().contains("HTTP 5")) {
            EventLogger.warn("llm", agent.name, null, "Retrying streaming after transient error");
            accumulator = primary.chatStreamAccumulate(
                    effectiveModelIdForCall, messages, tools, cb.onToken(), cb.onReasoning(),
                    maxTokens, thinkingMode, channelType);
            if (!CancellationManager.awaitAccumulatorOrCancel(accumulator, isCancelled, agent, channelType, cb)) return null;
        }
        return accumulator;
    }

    /** Round-1 streaming outcome: the accumulator plus the messages actually sent (transcript-rewritten
     *  when the audio fallback fired, so the tool-loop continuation reuses them). */
    private record StreamRound1(LlmProvider.StreamAccumulator accumulator, List<ChatMessage> messages) {}

    /**
     * Round-1 stream with the 5xx retry ({@link #streamFirstRoundWithRetry}) AND a JCLAW-165 audio
     * fallback, owning the {@code AUDIO_PASSTHROUGH_OUTCOME} log for the round.
     *
     * <p>When a {@code supportsAudio} model rejects the audio FORMAT (e.g. Xiaomi accepts only
     * mp3/flac/m4a/wav/ogg) <b>before any tokens stream</b>, the passthrough request is recoverable:
     * nothing has been emitted, so we await the Whisper transcript (the future was registered at send
     * time), rewrite audio→text via {@link VisionAudioAssembler#applyTranscriptsForCapability}, and
     * re-stream once. This is the streaming analogue of the {@code ToolCallLoopRunner} sync-path retry,
     * which interactive voice notes never reached — they just errored. The {@code content.isEmpty()}
     * guard is what makes the re-stream safe (a partially-streamed reply can't be un-sent). When no
     * usable transcript results (Whisper failed/timed out) the original 4xx is surfaced unchanged.
     */
    @SuppressWarnings("java:S107") // mirrors streamFirstRoundWithRetry's call surface + audio context
    private static StreamRound1 streamRound1WithAudioFallback(
            LlmProvider primary, String effectiveModelId, List<ChatMessage> messages, List<ToolDef> tools,
            AgentRunner.StreamingCallbacks cb, Integer maxTokens, String thinkingMode, String channelType,
            AtomicBoolean isCancelled, Agent agent, Conversation conversation,
            AgentPromptPreparer.PreparedPrologue prepared,
            boolean supportsAudio) throws InterruptedException {
        var acc = streamFirstRoundWithRetry(primary, effectiveModelId, messages, tools, cb, maxTokens,
                thinkingMode, channelType, isCancelled, agent);
        if (acc == null) return null;

        if (prepared.audioBearers().isEmpty()) return new StreamRound1(acc, messages); // no audio → no log

        // Audio rode native (passthrough) and the model rejected the FORMAT before any token streamed:
        // await the transcript and re-stream as text.
        if (acc.error() != null && supportsAudio && acc.content().isEmpty()
                && AudioRetryStrategy.isAudioFormatRejection(acc.error())) {
            var rewritten = VisionAudioAssembler.applyTranscriptsForCapability(messages, prepared.audioBearers(), false);
            if (!AudioRetryStrategy.anyTranscriptAvailable(prepared.audioBearers())) {
                AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary, OUTCOME_ERROR,
                        "no_transcript_after_rejection", true);
                return new StreamRound1(acc, messages); // surface the original 4xx — nothing to retry with
            }
            EventLogger.warn("llm", agent.name, channelType,
                    "Provider %s rejected the audio format; retrying with a Whisper transcript"
                            .formatted(primary.config().name()));
            var retry = streamFirstRoundWithRetry(primary, effectiveModelId, rewritten, tools, cb, maxTokens,
                    thinkingMode, channelType, isCancelled, agent);
            if (retry == null) return null;
            AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary,
                    retry.error() == null ? "downgraded" : OUTCOME_ERROR,
                    retry.error() == null ? null : AudioRetryStrategy.shortErrorTag(retry.error()), true);
            return new StreamRound1(retry, rewritten);
        }

        // No fallback fired — log the plain passthrough outcome (accepted / error). transcript_awaited
        // is false for a happy native passthrough, true when the rewrite already ran (text-only model).
        AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary,
                acc.error() == null ? "accepted" : OUTCOME_ERROR,
                acc.error() == null ? null : AudioRetryStrategy.shortErrorTag(acc.error()), !supportsAudio);
        return new StreamRound1(acc, messages);
    }

    /**
     * Outcome of {@link #runPostAccumulatorToolLoop}: the (possibly tool-loop-extended)
     * content, plus the round-1 reply-truncation flag that rides through to the persist
     * site so the chat UI can render a "Reply was truncated" marker (JCLAW-291).
     */
    private record StreamingPostAccumulator(String content, boolean replyTruncated) {}

    /**
     * Post round-1 processing: detect the empty-toolCalls truncation case, then drive
     * the tool-call loop if the model produced tool calls. Returns {@code null} when
     * cancellation fired inside the loop.
     */
    @SuppressWarnings("java:S107") // mirrors the orchestration state of streamLlmLoop
    private static StreamingPostAccumulator runPostAccumulatorToolLoop(LlmProvider.StreamAccumulator accumulator,
                                                                        Agent agent, Conversation conversation,
                                                                        LlmProvider primary, String channelType,
                                                                        List<ChatMessage> messages, List<ToolDef> tools,
                                                                        AgentRunner.StreamingCallbacks cb, String thinkingMode,
                                                                        AtomicBoolean isCancelled, LatencyTrace trace,
                                                                        LlmProvider.TurnUsage turnUsage,
                                                                        AgentExecutionSink sink) {
        var content = accumulator.content();

        // JCLAW-291: detect the empty-toolCalls truncation case — a plain
        // assistant reply that hit max_tokens. Sibling to the tool-call
        // truncation guard in the caller; that one fires only when toolCalls is
        // non-empty (incomplete JSON args), this one fires when toolCalls
        // is empty (the model just ran out of output budget mid-reply).
        boolean replyTruncated = TruncationDiagnostics.isTruncationFinish(accumulator.finishReason())
                && accumulator.toolCalls().isEmpty();
        if (replyTruncated) {
            TruncationDiagnostics.logEmptyToolCallsTruncation("streamLlmLoop", agent, conversation, primary,
                    channelType, accumulator.finishReason(), messages, tools);
        }

        // Handle tool calls if present. JCLAW-104: the image collector lives
        // at turn scope (not per recursion level) so a screenshot captured
        // mid-chain — say in round 1 — still reaches buildImagePrefix when
        // the final synthesis happens in round N.
        var turnImages = new ArrayList<String>();
        if (!accumulator.toolCalls().isEmpty()) {
            content = ToolCallLoopRunner.handleToolCallsStreaming(agent, conversation, conversation.id, messages, tools,
                    accumulator.toolCalls(), content, primary, cb, thinkingMode, 0,
                    isCancelled, trace, turnUsage, turnImages, channelType, sink);
        }
        return new StreamingPostAccumulator(content, replyTruncated);
    }

    /**
     * JCLAW-273: parent agent yielded into an async subagent. Fire {@code cb.onComplete}
     * with empty content so transports release their per-turn resources cleanly.
     */
    private static void handleStreamingYield(Agent agent, String channelType, LatencyTrace trace,
                                              AgentRunner.StreamingCallbacks cb) {
        EventLogger.info(AgentRunner.EVT_CATEGORY_AGENT, agent.name, channelType,
                "Streaming parent turn suspended via subagent_yield");
        trace.mark(LatencyTrace.STREAM_BODY_END);
        cb.onComplete().accept("");
    }

    /**
     * Persist the final assistant message and emit the terminal usage frame. Persist
     * BEFORE the terminal frame so the assistant message is committed by the time
     * {@code emitUsageAndComplete} fires {@code cb.onComplete}.
     */
    @SuppressWarnings("java:S107") // Final persist receives every piece of turn state by design
    private static void finalizeStreamingTurn(String content, boolean replyTruncated, LlmProvider.TurnUsage turnUsage,
                                               ModelInfo modelInfo, long streamStartMs,
                                               Agent agent, Conversation conversation, String channelType,
                                               LatencyTrace trace, AgentExecutionSink sink, AgentRunner.StreamingCallbacks cb) {
        // Build usage JSON before persisting so it can be stored alongside the message.
        // JCLAW-108: pass the conversation so resolved (override-aware) model identity
        // goes into usageJson. streamBodyMs (FIRST_TOKEN → STREAM_BODY_END) is the
        // denominator for realized generation rate.
        var usageJson = UsageMetricsBuilder.buildUsageJson(turnUsage, modelInfo, streamStartMs, agent, conversation,
                trace.streamBodyMs());

        var finalContent = content;
        var finalReasoning = turnUsage.reasoningText();
        var finalTruncated = replyTruncated;
        long persistStartNs = System.nanoTime();
        Tx.run(() ->
            sink.appendAssistantMessage(finalContent, null, usageJson, finalReasoning, finalTruncated));
        LatencyStats.record(channelType, "persist",
                (System.nanoTime() - persistStartNs) / 1_000_000L, AgentRunner.agentIdOf(agent));
        UsageMetricsBuilder.emitUsageAndComplete(agent, channelType, content, turnUsage, streamStartMs, usageJson, cb);
    }

    /**
     * Streaming-side truncated-tool-call handler: emit a retry marker,
     * persist the truncated content, and fire the terminal complete
     * callback. Persist BEFORE the terminal frame so the assistant
     * message is committed by the time the SSE closes / HTTP response
     * completes.
     */
    private static void persistAndCompleteTruncatedToolCall(String content, Agent agent, String channelType,
                                                            LatencyTrace trace, AgentExecutionSink sink,
                                                            AgentRunner.StreamingCallbacks cb) {
        EventLogger.warn("llm", agent.name, channelType,
                "Response truncated (finish_reason=length) with pending tool calls — skipping execution of incomplete tool arguments");
        var truncMsg = content.isEmpty()
                ? "I tried to use a tool but my response was too long and got cut off. Let me try a more concise approach."
                : content;
        cb.onToken().accept(truncMsg.equals(content) ? "" : "\n\n*[Response was truncated — retrying with a simpler approach]*");
        trace.mark(LatencyTrace.STREAM_BODY_END);
        var finalContent = truncMsg;
        long truncPersistStartNs = System.nanoTime();
        Tx.run(() -> sink.appendAssistantMessage(finalContent, null));
        LatencyStats.record(channelType, "persist",
                (System.nanoTime() - truncPersistStartNs) / 1_000_000L, AgentRunner.agentIdOf(agent));
        cb.onComplete().accept(finalContent);
    }
}
