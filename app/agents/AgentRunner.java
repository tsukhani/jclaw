package agents;

import com.google.gson.Gson;
import static utils.GsonHolder.INSTANCE;
import llm.LlmProvider;
import llm.LlmTypes.*;
import llm.ProviderRegistry;
import models.Agent;
import models.ChannelType;
import models.Conversation;
import services.ConversationService;
import services.EventLogger;
import utils.LatencyTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Core agent pipeline: receive message → load conversation → assemble prompt →
 * call LLM → handle tool calls (loop) → persist response → return.
 */
public class AgentRunner {

    private static final Gson gson = INSTANCE;
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 10;

    // Package-private so ToolCallLoopRunner (JCLAW-299) can read the
    // operator-configurable per-turn round cap.
    static int maxToolRounds() {
        return services.ConfigService.getInt("chat.maxToolRounds", DEFAULT_MAX_TOOL_ROUNDS);
    }

    public record RunResult(String response, Conversation conversation, boolean truncated) {
        /** 2-arg compatibility: legacy paths that don't track truncation. */
        public RunResult(String response, Conversation conversation) {
            this(response, conversation, false);
        }
    }

    /**
     * JCLAW-273: sentinel content the tool loop returns when the parent
     * agent has yielded into an async subagent. {@link #runAfterAcquire}
     * and {@link #runYieldResume} check {@code response.equals(YIELDED_RESPONSE)}
     * to skip the final-assistant-message persist (no reply to emit;
     * the resume re-invocation comes from
     * {@link tools.SpawnSubagentTool#runAsyncAndAnnounce} once the child
     * terminates).
     */
    public static final String YIELDED_RESPONSE = "__JCLAW_273_YIELDED__";

    /**
     * JCLAW-170: granular tool-invocation event, fired from the agent loop
     * once each tool call completes. Carries enough metadata for the UI to
     * render a per-call row (icon, name, arguments) plus the optional
     * structured result payload used by search-style tools to produce
     * clickable chips with favicons. {@code resultStructuredJson} is null
     * for tools that don't emit a structured view.
     */
    public record ToolCallEvent(
        String id,
        String name,
        String icon,
        String arguments,
        String resultText,
        String resultStructuredJson
    ) {}

    /**
     * Callbacks for streaming mode. Groups the event handlers that
     * {@link #runStreaming} pushes SSE data through.
     *
     * <p>{@code onCancel} fires once when {@link CancellationManager#checkCancelled} detects a
     * cancellation flag flip and the streaming thread is about to early-return.
     * Unlike {@code onComplete} / {@code onError} it carries no payload — its
     * job is to let transports quiesce side-channel state (the Telegram typing
     * heartbeat is the motivating case: JCLAW-181 follow-up). Web's per-request
     * cancellation is signalled via SSE close, not this hook, so the web
     * caller passes a no-op.
     */
    public record StreamingCallbacks(
        Consumer<Conversation> onInit,
        Consumer<String> onToken,
        Consumer<String> onReasoning,
        Consumer<String> onStatus,
        Consumer<ToolCallEvent> onToolCall,
        Consumer<String> onComplete,
        Consumer<Exception> onError,
        Runnable onCancel
    ) {}

    private record PreparedData(
        List<ChatMessage> messages,
        LlmProvider primary,
        LlmProvider secondary,
        List<ToolDef> tools,
        List<VisionAudioAssembler.AudioBearer> audioBearers
    ) {}

    /**
     * Bundle of read-only prologue data computed in a single JPA transaction.
     * Used by {@link #streamLlmLoop} to fold what used to be 5+ separate
     * {@code Tx.run} blocks into one round-trip to the connection pool.
     */
    private record PreparedPrologue(
        SystemPromptAssembler.AssembledPrompt assembled,
        List<ChatMessage> messages,
        List<ToolDef> tools,
        java.util.Set<String> disabledTools,
        List<VisionAudioAssembler.AudioBearer> audioBearers
    ) {}

    /**
     * JCLAW-291: cooperative-cancellation checkpoint for subagent runs. If
     * the conversation belongs to a still-RUNNING subagent whose
     * {@link services.SubagentRegistry} entry has had its cancel flag
     * flipped (by {@code /subagent kill} or the admin UI), throws
     * {@link RunCancelledException} so the caller bails cleanly. No-op for
     * non-subagent conversations (no SubagentRun row points at them).
     *
     * <p>Replaces the prior {@code Thread.interrupt()} mechanism. The
     * checkpoint is intentionally placed at safe boundaries — between LLM
     * rounds, between tool calls, at the top of queue-drain — never inside
     * a streaming chunk loop or a tool execution. The cost is losing the
     * "instant break a hung HTTP read" property; the
     * {@code runTimeoutSeconds} budget remains the ceiling for that case.
     * See {@link services.SubagentRegistry} for the H2-corruption post-mortem.
     */
    public static void checkSubagentCancel(Conversation conversation) {
        if (conversation == null || conversation.id == null) return;
        // Query both the inline-mode (parentConversation FK == this conv) and
        // session-mode (childConversation FK == this conv) cases. In practice
        // inline-mode reuses the parent conversation as the child target so
        // both columns reference the same row; session mode uses a separate
        // child conv. Either way, the cancel flag is keyed on the SubagentRun
        // id, and a kill against the matching run should bail the runner.
        var runId = services.Tx.run(() -> {
            var run = (models.SubagentRun) models.SubagentRun.find(
                    "childConversation.id = ?1 AND status = ?2",
                    conversation.id, models.SubagentRun.Status.RUNNING).first();
            return run != null ? run.id : null;
        });
        if (runId != null && services.SubagentRegistry.isCancelled(runId)) {
            throw new RunCancelledException(runId);
        }
    }

    /**
     * Run the agent synchronously. Returns the final assistant response.
     * JPA transactions are scoped to short Tx.run() blocks — no JDBC connection
     * is held during LLM HTTP calls or tool execution.
     */
    public static RunResult run(Agent agent, Conversation conversation, String userMessage) {
        return run(agent, conversation, userMessage, null);
    }

    /**
     * JCLAW-25 vision variant: {@code attachments} is the list of files the
     * caller already pre-uploaded via {@code POST /api/chat/upload}; each
     * staged file gets finalized into the conversation-keyed directory and
     * gains a {@link models.MessageAttachment} row against the new user
     * message. Image attachments ride into the LLM request as OpenAI
     * {@code image_url} content parts; non-image attachments are referenced
     * by filename inside the text part.
     */
    public static RunResult run(Agent agent, Conversation conversation, String userMessage,
                                 java.util.List<services.AttachmentService.Input> attachments) {
        var queueMsg = new services.ConversationQueue.QueuedMessage(
                userMessage, conversation.channelType, conversation.peerId, agent);
        if (!services.ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
            return new RunResult("Your message has been queued and will be processed shortly.", conversation);
        }
        return runAfterAcquire(agent, conversation, userMessage, attachments);
    }

    /**
     * Variant of {@link #run} for callers that have already acquired the
     * conversation queue via {@link services.ConversationQueue#drain}
     * (JCLAW-117). Skips {@code tryAcquire} because the caller holds
     * ownership; the shared body's {@code finally} calls
     * {@link QueueDrainOrchestrator#processQueueDrain} which releases ownership when the pending
     * deque is empty (or transfers it to the next drained message).
     */
    public static RunResult runWithOwnedQueue(Agent agent, Conversation conversation, String userMessage) {
        return runAfterAcquire(agent, conversation, userMessage, null, false);
    }

    /**
     * JCLAW-273: resume entrypoint used by
     * {@link tools.SpawnSubagentTool#runAsyncAndAnnounce} after a yielded
     * async child terminates. The yield-resume announce Message has already
     * been persisted into the parent conversation as a USER-role row (with
     * {@code messageKind=subagent_announce}); this entrypoint acquires the
     * conversation queue (so it serialises against any concurrent inbound
     * turn) and then runs the standard {@link #runAfterAcquire} pipeline
     * with {@code skipUserAppend=true} so the announce isn't duplicated.
     * The LLM sees the announce verbatim via
     * {@link ConversationService#loadRecentMessages} (whose filter keeps
     * USER-role announces in context).
     *
     * <p>If the queue is busy (a Telegram message or web turn arrived in the
     * gap between the child's terminal state and this call), the resume is
     * queued just like any other inbound turn would be — the queued-canned-
     * response path returns a {@link RunResult} but the actual resume work
     * runs after the current owner finishes.
     */
    public static RunResult runYieldResume(Agent agent, Conversation conversation) {
        // Empty user message because the actual input — the child's reply —
        // is already in the persisted USER-role announce row. The
        // skipUserAppend flag suppresses appendUserMessage so we don't
        // double-append.
        var queueMsg = new services.ConversationQueue.QueuedMessage(
                "", conversation.channelType, conversation.peerId, agent);
        if (!services.ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
            return new RunResult("Your message has been queued and will be processed shortly.", conversation);
        }
        return runAfterAcquire(agent, conversation, "", null, true);
    }

    private static RunResult runAfterAcquire(Agent agent, Conversation conversation, String userMessage,
                                              java.util.List<services.AttachmentService.Input> attachments) {
        return runAfterAcquire(agent, conversation, userMessage, attachments, false);
    }

    private static RunResult runAfterAcquire(Agent agent, Conversation conversation, String userMessage,
                                              java.util.List<services.AttachmentService.Input> attachments,
                                              boolean skipUserAppend) {
        final Long conversationId = conversation.id;
        // Non-streaming callers (TaskPollerJob, background) have no pre-runner
        // queue-accept timestamp, so queue_wait is naturally skipped. Every other
        // segment is captured, which is why scheduled turns now show up in the
        // Chat Performance dashboard (channel-partitioned per JCLAW-102).
        var trace = LatencyTrace.forTurn(conversation.channelType, null);
        trace.mark(LatencyTrace.PROLOGUE_REQUEST_PARSED);

        // JCLAW-291: cooperative-cancel checkpoint at the conversation-forward
        // boundary. If a /subagent kill landed between queue acquire and now,
        // bail before we burn any LLM budget or persist any state.
        checkSubagentCancel(conversation);

        try {
            // Short setup transaction: persist user message, assemble prompt, resolve provider.
            // Re-fetch the conversation by ID so it is managed in this persistence context.
            // Callers on virtual threads (TaskPollerJob, webhooks) pass entities that were
            // loaded in a separate, already-committed Tx.run() — those are detached and
            // would throw PersistentObjectException on save().
            var prepared = services.Tx.run(() -> {
                var conv = ConversationService.findById(conversationId);
                // JCLAW-273: skipUserAppend=true comes from runYieldResume — the
                // yield-resume announce was already persisted as a USER-role
                // Message before this call, so re-appending would duplicate the
                // row in both the chat scrollback and the LLM context.
                if (!skipUserAppend) {
                    ConversationService.appendUserMessage(conv, userMessage, attachments);
                }
                trace.mark(LatencyTrace.PROLOGUE_CONV_RESOLVED);

                var assembled = SystemPromptAssembler.assemble(agent, userMessage, null, conv.channelType);
                // JCLAW-38: re-inject the latest compaction summary (if any)
                // into the system prompt so the LLM keeps continuity with
                // turns that have since been dropped from the raw history.
                var sysPrompt = services.SessionCompactor.appendSummaryToPrompt(assembled.systemPrompt(), conv);
                // JCLAW-268: re-inject the spawn-time parent-conversation context
                // for inherit-mode subagents. No-op for fresh-mode and non-subagent
                // conversations (parentContext is null).
                sysPrompt = services.SessionCompactor.appendParentContextToPrompt(sysPrompt, conv);
                var audioBearers = new ArrayList<VisionAudioAssembler.AudioBearer>();
                var messages = MessageHydrator.buildMessages(sysPrompt, conv, audioBearers);

                // JCLAW-108: resolve the provider from the effective provider name
                // (conversation override when set, agent default otherwise), not
                // from agent.modelProvider directly. Downstream helpers that take
                // (agent, conv, provider) compute their own effective model id.
                var agentProvider = ProviderRegistry.get(ModelResolver.effectiveModelProvider(agent, conv));
                var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
                if (primary == null) {
                    var error = "No LLM provider configured. Add provider config via Settings.";
                    EventLogger.error("llm", agent.name, null, error);
                    ConversationService.appendAssistantMessage(conv, error, null);
                    return null;
                }
                var secondary = ProviderRegistry.getSecondary();

                // Conversation-aware overload: lazy-load MCP tool schemas
                // based on which servers the model has discovered via
                // list_mcp_tools. Native tools always ship.
                var tools = ToolRegistry.getToolDefsForAgent(agent, conv);

                EventLogger.info("llm", agent.name, conv.channelType,
                        "Calling %s / %s".formatted(primary.config().name(), ModelResolver.effectiveModelId(agent, conv)));

                return new PreparedData(messages, primary, secondary, tools, audioBearers);
            });

            if (prepared == null) {
                var error = "No LLM provider configured. Add provider config via Settings.";
                return new RunResult(error,
                        services.Tx.run(() -> ConversationService.findById(conversationId)));
            }

            // JCLAW-38: if the just-built context exceeds the compaction
            // budget, summarize older turns (LLM call, outside Tx) and
            // rebuild. trimToContextWindow below stays as a drop-oldest
            // fallback for when compaction is skipped (too few turns) or
            // fails.
            var compactedMessages = CompactionGate.maybeCompactAndRebuild(
                    agent, conversationId, userMessage, null,
                    prepared.primary(), prepared.messages());
            var finalMessages = ContextWindowManager.trimToContextWindow(compactedMessages, agent, conversation, prepared.primary());
            // JCLAW-165: when the active model lacks supportsAudio, await
            // any in-flight transcription futures and rewrite the user
            // messages as text-with-transcript before the LLM call. The
            // audio-capable happy path is a no-op and pays zero added latency.
            var modelInfoForAudio = ModelResolver.resolveModelInfo(agent, conversation, prepared.primary()).orElse(null);
            var supportsAudioForCall = modelInfoForAudio != null && modelInfoForAudio.supportsAudio();
            finalMessages = VisionAudioAssembler.applyTranscriptsForCapability(finalMessages, prepared.audioBearers(), supportsAudioForCall);
            prepared = new PreparedData(finalMessages, prepared.primary(), prepared.secondary(), prepared.tools(), prepared.audioBearers());
            trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);

            trace.mark(LatencyTrace.PROLOGUE_DONE);
            // LLM call loop — no transaction open, JDBC connection back in pool
            var outcome = ToolCallLoopRunner.callWithToolLoop(agent, conversation, conversationId,
                    prepared.messages(), prepared.tools(), prepared.primary(), prepared.secondary(),
                    prepared.audioBearers());
            var response = outcome.content();
            var truncated = outcome.truncated();
            trace.mark(LatencyTrace.STREAM_BODY_END);

            // JCLAW-273: yielded response — no final assistant message to
            // persist. The parent agent's turn ended cleanly after the
            // yield_to_subagent tool call; the resume re-invocation will
            // arrive from tools.SpawnSubagentTool#runAsyncAndAnnounce once
            // the child terminates. Return immediately so the caller sees
            // YIELDED_RESPONSE on the RunResult.
            if (YIELDED_RESPONSE.equals(response)) {
                EventLogger.info("agent", agent.name, conversation.channelType,
                        "Parent turn suspended via yield_to_subagent");
                var updatedConv = services.Tx.run(() -> ConversationService.findById(conversationId));
                return new RunResult(response, updatedConv);
            }

            // Short persistence transaction: final assistant message.
            // Conversation may have been deleted between LLM call and persist
            // (loadtest cleanup, manual UI delete, etc.) — log + skip rather
            // than insert a Message with a null FK.
            services.Tx.run(() -> {
                var conv = ConversationService.findById(conversationId);
                if (conv == null) {
                    EventLogger.warn("agent", agent.name, conversation.channelType,
                            "Persist skipped: conversation %d was deleted before persist completed"
                                    .formatted(conversationId));
                    return;
                }
                ConversationService.appendAssistantMessage(conv, response, null, null, null, truncated);
            });

            EventLogger.info("llm", agent.name, conversation.channelType,
                    "Response generated (%d chars%s)".formatted(response.length(),
                            truncated ? ", TRUNCATED" : ""));

            var updatedConversation = services.Tx.run(() -> ConversationService.findById(conversationId));
            return new RunResult(response, updatedConversation, truncated);
        } finally {
            trace.mark(LatencyTrace.TERMINAL_SENT);
            trace.end();
            EventLogger.flush();
            QueueDrainOrchestrator.processQueueDrain(conversationId);
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
    @SuppressWarnings("java:S107") // Streaming entrypoint signature retained for binary compat with channel callers
    public static void runStreaming(Agent agent, Long conversationId, String channelType, String peerId,
                                    String userMessage,
                                    AtomicBoolean isCancelled,
                                    StreamingCallbacks cb,
                                    Long acceptedAtNs) {
        runStreaming(agent, conversationId, channelType, peerId, userMessage,
                isCancelled, cb, acceptedAtNs, null);
    }

    /**
     * JCLAW-25 vision variant. {@code attachments} is the per-file metadata
     * the frontend roundtripped from the prior {@code /api/chat/upload}
     * response; each entry's staged file is moved into the conversation's
     * attachments directory and recorded as a {@link models.MessageAttachment}
     * row on the user message.
     */
    @SuppressWarnings("java:S107") // Streaming entrypoint signature retained for binary compat with channel callers
    public static void runStreaming(Agent agent, Long conversationId, String channelType, String peerId,
                                    String userMessage,
                                    AtomicBoolean isCancelled,
                                    StreamingCallbacks cb,
                                    Long acceptedAtNs,
                                    java.util.List<services.AttachmentService.Input> attachments) {
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
                var conversation = resolveConversationAndAcquireQueue(
                        agent, conversationId, channelType, peerId, userMessage, tracedCb, attachments);
                if (conversation == null) return; // queued, not-found, or error — already handled
                conversationIdRef[0] = conversation.id;

                trace.mark(LatencyTrace.PROLOGUE_CONV_RESOLVED);

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
     * Wrap a caller's {@link StreamingCallbacks} so the trace captures the three
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
    private static StreamingCallbacks wrapCallbacksWithTrace(StreamingCallbacks cb, LatencyTrace trace,
                                                              Long[] conversationIdRef,
                                                              AtomicBoolean queueReleased) {
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
     * or {@code null} if the request was queued, not found, or errored (in which
     * case callbacks have already been invoked).
     */
    private static Conversation resolveConversationAndAcquireQueue(
            Agent agent, Long conversationId, String channelType, String peerId,
            String userMessage, StreamingCallbacks cb,
            java.util.List<services.AttachmentService.Input> attachments) {

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

        // Now that we hold the lock, persist the user message (and any
        // attachments that rode with it — JCLAW-25).
        services.Tx.run(() -> {
            var convo = ConversationService.findById(conversation.id);
            ConversationService.appendUserMessage(convo, userMessage, attachments);
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
        // JCLAW-108: use the effective provider (conversation override when set,
        // agent default otherwise) so /model NAME actually routes turns to the
        // overridden provider rather than the agent's original.
        var agentProvider = ProviderRegistry.get(ModelResolver.effectiveModelProvider(agent, conversation));
        var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
        if (primary == null) {
            EventLogger.error("llm", agent.name, channelType, "No LLM provider configured");
            cb.onError().accept(new RuntimeException("No LLM provider configured"));
            return;
        }

        if (CancellationManager.checkCancelled(isCancelled, agent, channelType, cb)) return;

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
            // JCLAW-38: re-inject latest compaction summary (if any)
            var sysPrompt = services.SessionCompactor.appendSummaryToPrompt(assembled0.systemPrompt(), convo);
            // JCLAW-268: re-inject spawn-time parent context for inherit-mode subagents.
            sysPrompt = services.SessionCompactor.appendParentContextToPrompt(sysPrompt, convo);
            var audioBearers = new ArrayList<VisionAudioAssembler.AudioBearer>();
            var msgs = MessageHydrator.buildMessages(sysPrompt, convo, audioBearers);
            // Conversation-aware overload: applies the loadtest-agent
            // short-circuit AND the lazy MCP discovery gate (only ship
            // schemas for servers the model has called list_mcp_tools on).
            var toolDefs = ToolRegistry.getToolDefsForAgent(agent, convo);
            return new PreparedPrologue(assembled0, msgs, toolDefs, disabledTools, audioBearers);
        });

        // JCLAW-38: if the just-built context exceeds the compaction budget,
        // summarize older turns (LLM call, outside Tx) and rebuild.
        // trimToContextWindow below stays as a drop-oldest fallback for
        // when compaction is skipped or fails.
        var compactedMessages = CompactionGate.maybeCompactAndRebuild(
                agent, conversation.id, userMessage, prepared.disabledTools(),
                primaryRef, prepared.messages());
        var trimmedMessages = ContextWindowManager.trimToContextWindow(compactedMessages, agent, conversation, primaryRef);
        // JCLAW-165: rewrite audio messages to text-with-transcript when the
        // active model lacks supportsAudio. Audio-capable happy path is a no-op.
        var modelInfoForAudioStream = ModelResolver.resolveModelInfo(agent, conversation, primaryRef).orElse(null);
        var supportsAudioForStream = modelInfoForAudioStream != null && modelInfoForAudioStream.supportsAudio();
        trimmedMessages = VisionAudioAssembler.applyTranscriptsForCapability(trimmedMessages, prepared.audioBearers(), supportsAudioForStream);

        var assembled = prepared.assembled();
        var messages = trimmedMessages;

        if (CancellationManager.checkCancelled(isCancelled, agent, channelType, cb)) return;

        trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);

        var tools = prepared.tools();
        var thinkingMode = ModelResolver.resolveThinkingMode(agent, conversation, primary);
        EventLogger.info("llm", agent.name, channelType,
                "Streaming: calling %s / %s (%d messages, %d tools, %d skills%s)"
                        .formatted(primary.config().name(), ModelResolver.effectiveModelId(agent, conversation),
                                messages.size(), tools.size(), assembled.skills().size(),
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
        var accumulator = primary.chatStreamAccumulate(
                effectiveModelIdForCall, messages, tools, cb.onToken(), cb.onReasoning(),
                maxTokens, thinkingMode, channelType);

        if (!CancellationManager.awaitAccumulatorOrCancel(accumulator, isCancelled, agent, channelType, cb)) return;

        // Retry once on transient 5xx errors
        if (accumulator.error != null && accumulator.error.getMessage() != null
                && accumulator.error.getMessage().contains("HTTP 5")) {
            EventLogger.warn("llm", agent.name, null, "Retrying streaming after transient error");
            accumulator = primary.chatStreamAccumulate(
                    effectiveModelIdForCall, messages, tools, cb.onToken(), cb.onReasoning(),
                    maxTokens, thinkingMode, channelType);
            if (!CancellationManager.awaitAccumulatorOrCancel(accumulator, isCancelled, agent, channelType, cb)) return;
        }

        if (CancellationManager.checkCancelled(isCancelled, agent, channelType, cb)) return;

        // JCLAW-165: fire AUDIO_PASSTHROUGH_OUTCOME for the round-1 LLM call
        // when the request carried audio. Streaming has no retry (you can't
        // un-send streamed tokens) so the outcome here is binary — accepted
        // or error. transcript_awaited is true when the rewrite already ran
        // (text-only model), false when the audio rode native (passthrough
        // happy path on a supportsAudio model).
        boolean hasAudioForStream = !prepared.audioBearers().isEmpty();
        if (accumulator.error != null) {
            if (hasAudioForStream) {
                AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary, "error",
                        AudioRetryStrategy.shortErrorTag(accumulator.error), !supportsAudioForStream);
            }
            cb.onError().accept(accumulator.error);
            return;
        }
        if (hasAudioForStream) {
            AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary, "accepted",
                    null, !supportsAudioForStream);
        }

        // Round 1 folded into turn-level usage. addRound also propagates
        // round-local reasoning/content timing into TurnUsage so the
        // turn-level reasoningDurationMs spans first-reasoning → first-content
        // across every round (matches the frontend's live measurement).
        turnUsage.addRound(accumulator);
        var content = accumulator.content;

        // Check for truncated response (max tokens hit mid-tool-call)
        if (TruncationDiagnostics.isTruncationFinish(accumulator.finishReason) && !accumulator.toolCalls.isEmpty()) {
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
            // Persist BEFORE the terminal frame so the assistant message is
            // committed by the time the SSE closes / HTTP response completes.
            // The previous parallel-vthread arrangement (JCLAW-100) raced on
            // web: cb.onComplete fires sse.close, the controller's await
            // unblocks, and the HTTP response went out before persist had a
            // chance to run — letting downstream cleanup observe a "done"
            // request whose message wasn't actually saved. The defensive
            // null-check stays as belt-and-suspenders against any future
            // re-introduction of an out-of-order delete.
            long truncPersistStartNs = System.nanoTime();
            services.Tx.run(() -> {
                var conv = ConversationService.findById(conversation.id);
                if (conv == null) {
                    EventLogger.warn("agent", agent.name, channelType,
                            "Trunc persist skipped: conversation %d was deleted before persist completed"
                                    .formatted(conversation.id));
                    return;
                }
                ConversationService.appendAssistantMessage(conv, finalContent, null);
            });
            utils.LatencyStats.record(channelType, "persist",
                    (System.nanoTime() - truncPersistStartNs) / 1_000_000L);
            cb.onComplete().accept(finalContent);
            return;
        }

        // JCLAW-291: detect the empty-toolCalls truncation case — a plain
        // assistant reply that hit max_tokens. Sibling to the tool-call
        // truncation guard above; that one fires only when toolCalls is
        // non-empty (incomplete JSON args), this one fires when toolCalls
        // is empty (the model just ran out of output budget mid-reply).
        // The flag rides through to the persist site so the chat UI can
        // render a "Reply was truncated" marker.
        boolean replyTruncated = TruncationDiagnostics.isTruncationFinish(accumulator.finishReason)
                && accumulator.toolCalls.isEmpty();
        if (replyTruncated) {
            TruncationDiagnostics.logEmptyToolCallsTruncation("streamLlmLoop", agent, conversation, primary,
                    channelType, accumulator.finishReason, messages, tools);
        }

        // Handle tool calls if present. JCLAW-104: the image collector lives
        // at turn scope (not per recursion level) so a screenshot captured
        // mid-chain — say in round 1 — still reaches buildImagePrefix when
        // the final synthesis happens in round N. Pre-fix the list was
        // reset at every recursion depth, which silently dropped images
        // from intermediate rounds when the LLM chose not to re-embed.
        var turnImages = new ArrayList<String>();
        if (!accumulator.toolCalls.isEmpty()) {
            content = ToolCallLoopRunner.handleToolCallsStreaming(agent, conversation, conversation.id, messages, tools,
                    accumulator.toolCalls, content, primary, cb, thinkingMode, 0,
                    isCancelled, trace, turnUsage, turnImages, channelType);
        }

        if (CancellationManager.checkCancelled(isCancelled, agent, channelType, cb)) return;

        // JCLAW-273: parent agent yielded into an async subagent. No final
        // assistant message to persist or emit; the parent's logical turn
        // resumes later from tools.SpawnSubagentTool#runAsyncAndAnnounce
        // once the child terminates. Fire cb.onComplete with empty content
        // so transports release their per-turn resources cleanly (the SSE
        // wrapper's onComplete drains the conversation queue + closes the
        // terminal frame; without it, web turns would dangle waiting for a
        // completion that's coming from a different VT in a different turn).
        if (YIELDED_RESPONSE.equals(content)) {
            EventLogger.info("agent", agent.name, channelType,
                    "Streaming parent turn suspended via yield_to_subagent");
            trace.mark(LatencyTrace.STREAM_BODY_END);
            cb.onComplete().accept("");
            return;
        }

        trace.mark(LatencyTrace.STREAM_BODY_END);

        // Build usage JSON before persisting so it can be stored alongside the message.
        // JCLAW-108: pass the conversation so resolved (override-aware) model identity
        // goes into usageJson rather than the agent's underlying fields.
        // streamBodyMs (FIRST_TOKEN → STREAM_BODY_END) is the denominator for
        // realized generation rate — see the buildUsageJson 6-arg overload doc.
        var usageJson = UsageMetricsBuilder.buildUsageJson(turnUsage, modelInfo, streamStartMs, agent, conversation,
                trace.streamBodyMs());

        // Persist BEFORE the terminal frame so the assistant message is
        // committed by the time emitUsageAndComplete fires cb.onComplete
        // (which closes the SSE on web and triggers the Telegram bot-API
        // call on telegram). Either path can have an external observer —
        // a loadtest cleanup, a UI poll, a queued follow-up turn — that
        // checks DB state right after the user-visible "done" signal;
        // putting persist before that signal makes the contract honest.
        //
        // Pre-fix: JCLAW-100 spawned persist on a parallel virtual thread
        // and joined AFTER emitUsageAndComplete, hiding the ~8 ms persist
        // behind telegram's ~500 ms bot-API call. On web, cb.onComplete
        // is sub-millisecond (sse.send + sse.close), so the parallel
        // arrangement saved nothing AND let the HTTP response complete
        // before persist finished — observable as FK-violation log noise
        // when any concurrent endpoint deleted the conversation in that
        // window. The 8 ms regression on telegram is a tiny price to pay
        // for a correct happens-before contract on every channel.
        //
        // The defensive null-check stays as belt-and-suspenders against
        // any future re-introduction of an out-of-order delete.
        var finalContent = content;
        var finalReasoning = turnUsage.reasoningText();
        // JCLAW-291: replyTruncated is captured from the round-1 accumulator;
        // a tool-call recursion that lands on a clean final reply WON'T flip
        // this — by design, since the final reply itself is what the user
        // reads. The recursive truncation case is covered by the
        // handleToolCallsStreaming guard at the existing tool-call site.
        var finalTruncated = replyTruncated;
        long persistStartNs = System.nanoTime();
        services.Tx.run(() -> {
            var conv = ConversationService.findById(conversation.id);
            if (conv == null) {
                EventLogger.warn("agent", agent.name, channelType,
                        "Persist skipped: conversation %d was deleted before persist completed"
                                .formatted(conversation.id));
                return;
            }
            ConversationService.appendAssistantMessage(conv, finalContent, null, usageJson, finalReasoning, finalTruncated);
        });
        utils.LatencyStats.record(channelType, "persist",
                (System.nanoTime() - persistStartNs) / 1_000_000L);
        UsageMetricsBuilder.emitUsageAndComplete(agent, channelType, content, turnUsage, streamStartMs, usageJson, cb);
    }

    // --- Internal ---



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
            var cmd = slashCmd.get();
            Conversation current = cmd == slash.Commands.Command.NEW
                    ? null
                    : services.Tx.run(() -> ConversationService.findOrCreate(agent, channelType, peerId));
            var result = slash.Commands.execute(cmd, agent, channelType, peerId, current,
                    slash.Commands.extractArgs(text));
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
        processInboundForAgentStreaming(agent, channelType, peerId, text, sinkFactory,
                java.util.List.of());
    }

    /**
     * JCLAW-136 overload: accepts inbound file attachments (images, audio,
     * documents, video) alongside the text. The caller (webhook or polling
     * runner) has already resolved Telegram file_ids via Bot API getFile and
     * streamed the bytes into the agent's {@code attachments/staging}
     * directory, so each {@link services.AttachmentService.Input} points at
     * a real staged file the runner can finalize. Empty list is the
     * text-only path — same behavior as before.
     */
    public static void processInboundForAgentStreaming(
            Agent agent, String channelType, String peerId, String text,
            java.util.function.Function<Long, channels.TelegramStreamingSink> sinkFactory,
            java.util.List<services.AttachmentService.Input> attachments) {
        // JCLAW-26: intercept slash commands before the LLM round. Reuse the
        // existing sink machinery to deliver the canned response — an unused
        // sink's seal() path falls through to TelegramChannel.sendMessage,
        // which keeps the bot-token / chat-id routing owned by the caller.
        var slashCmd = slash.Commands.parse(text);
        if (slashCmd.isPresent()) {
            var cmd = slashCmd.get();
            Conversation current = cmd == slash.Commands.Command.NEW
                    ? null
                    : services.Tx.run(() -> ConversationService.findOrCreate(agent, channelType, peerId));
            var result = slash.Commands.execute(cmd, agent, channelType, peerId, current,
                    slash.Commands.extractArgs(text));
            var slashSink = sinkFactory.apply(
                    result.conversation() != null ? result.conversation().id : null);
            // JCLAW-109: an empty responseText is the handler's signal that
            // it already delivered the reply itself (e.g. /model on Telegram
            // sent an inline-keyboard message via a dedicated Bot API path).
            // Fall through to seal only when there's text to emit.
            if (result.responseText() != null && !result.responseText().isEmpty()) {
                slashSink.seal(result.responseText());
            }
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
                _ -> {},                 // onToolCall — JCLAW-170, web-only for now
                sink::seal,              // onComplete — final edit / planner fallback
                sink::errorFallback,     // onError — delete placeholder + send error
                sink::cancel);           // onCancel — quiesce typing heartbeat on /stop
        runStreaming(agent, conversation.id, channelType, peerId, text,
                isCancelled, cb, null, attachments);
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
    // Package-private so QueueDrainOrchestrator (JCLAW-299) can dispatch
    // queued replies back through the originating channel.
    static void dispatchToChannel(Agent agent, String channelType, String peerId, String text) {
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


}
