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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private static int maxToolRounds() {
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
        List<AudioBearer> audioBearers
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
        List<AudioBearer> audioBearers
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
     * Effective model id for this turn — honors the conversation-scoped
     * override (JCLAW-108 per-conversation, JCLAW-269 per-spawn) when
     * present, otherwise returns the agent's default. Thin wrapper over
     * {@link services.ModelOverrideResolver#modelId} kept on this class so
     * call sites read naturally next to the rest of the runner's helpers.
     */
    static String effectiveModelId(Agent agent, Conversation conv) {
        return services.ModelOverrideResolver.modelId(conv, agent);
    }

    /** Companion to {@link #effectiveModelId} — returns the effective provider name. */
    static String effectiveModelProvider(Agent agent, Conversation conv) {
        return services.ModelOverrideResolver.provider(conv, agent);
    }

    /**
     * Resolve the model's {@link ModelInfo} from the provider's configured model list.
     * Used by {@link #callWithToolLoop}, {@link #runStreaming}, and {@link ContextWindowManager#trimToContextWindow}.
     * Honors the conversation-scoped override (JCLAW-108): when
     * {@code conv.modelIdOverride} is set, looks up that id instead of the
     * agent's default.
     */
    // Package-private so ContextWindowManager (JCLAW-299) can resolve
    // model info when computing trim-target and effectiveMaxTokens.
    static Optional<ModelInfo> resolveModelInfo(Agent agent, Conversation conv, LlmProvider provider) {
        var modelId = effectiveModelId(agent, conv);
        if (modelId == null) return Optional.empty();
        return provider.config().models().stream()
                .filter(m -> modelId.equals(m.id()))
                .findFirst();
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
    private static String resolveThinkingMode(Agent agent, Conversation conv, LlmProvider provider) {
        if (agent.thinkingMode == null || agent.thinkingMode.isBlank()) return null;
        return resolveModelInfo(agent, conv, provider)
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
                var audioBearers = new ArrayList<AudioBearer>();
                var messages = buildMessages(sysPrompt, conv, audioBearers);

                // JCLAW-108: resolve the provider from the effective provider name
                // (conversation override when set, agent default otherwise), not
                // from agent.modelProvider directly. Downstream helpers that take
                // (agent, conv, provider) compute their own effective model id.
                var agentProvider = ProviderRegistry.get(effectiveModelProvider(agent, conv));
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
                        "Calling %s / %s".formatted(primary.config().name(), effectiveModelId(agent, conv)));

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
            var compactedMessages = maybeCompactAndRebuild(
                    agent, conversationId, userMessage, null,
                    prepared.primary(), prepared.messages());
            var finalMessages = ContextWindowManager.trimToContextWindow(compactedMessages, agent, conversation, prepared.primary());
            // JCLAW-165: when the active model lacks supportsAudio, await
            // any in-flight transcription futures and rewrite the user
            // messages as text-with-transcript before the LLM call. The
            // audio-capable happy path is a no-op and pays zero added latency.
            var modelInfoForAudio = resolveModelInfo(agent, conversation, prepared.primary()).orElse(null);
            var supportsAudioForCall = modelInfoForAudio != null && modelInfoForAudio.supportsAudio();
            finalMessages = applyTranscriptsForCapability(finalMessages, prepared.audioBearers(), supportsAudioForCall);
            prepared = new PreparedData(finalMessages, prepared.primary(), prepared.secondary(), prepared.tools(), prepared.audioBearers());
            trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);

            trace.mark(LatencyTrace.PROLOGUE_DONE);
            // LLM call loop — no transaction open, JDBC connection back in pool
            var outcome = callWithToolLoop(agent, conversation, conversationId,
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
        var agentProvider = ProviderRegistry.get(effectiveModelProvider(agent, conversation));
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
            var audioBearers = new ArrayList<AudioBearer>();
            var msgs = buildMessages(sysPrompt, convo, audioBearers);
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
        var compactedMessages = maybeCompactAndRebuild(
                agent, conversation.id, userMessage, prepared.disabledTools(),
                primaryRef, prepared.messages());
        var trimmedMessages = ContextWindowManager.trimToContextWindow(compactedMessages, agent, conversation, primaryRef);
        // JCLAW-165: rewrite audio messages to text-with-transcript when the
        // active model lacks supportsAudio. Audio-capable happy path is a no-op.
        var modelInfoForAudioStream = resolveModelInfo(agent, conversation, primaryRef).orElse(null);
        var supportsAudioForStream = modelInfoForAudioStream != null && modelInfoForAudioStream.supportsAudio();
        trimmedMessages = applyTranscriptsForCapability(trimmedMessages, prepared.audioBearers(), supportsAudioForStream);

        var assembled = prepared.assembled();
        var messages = trimmedMessages;

        if (CancellationManager.checkCancelled(isCancelled, agent, channelType, cb)) return;

        trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);

        var tools = prepared.tools();
        var thinkingMode = resolveThinkingMode(agent, conversation, primary);
        EventLogger.info("llm", agent.name, channelType,
                "Streaming: calling %s / %s (%d messages, %d tools, %d skills%s)"
                        .formatted(primary.config().name(), effectiveModelId(agent, conversation),
                                messages.size(), tools.size(), assembled.skills().size(),
                                thinkingMode != null ? ", thinking=" + thinkingMode : ""));
        var maxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, primary, messages, tools);
        var modelInfo = resolveModelInfo(agent, conversation, primary).orElse(null);

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
        var effectiveModelIdForCall = effectiveModelId(agent, conversation);
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
                logAudioPassthroughOutcome(agent, conversation, primary, "error",
                        shortErrorTag(accumulator.error), !supportsAudioForStream);
            }
            cb.onError().accept(accumulator.error);
            return;
        }
        if (hasAudioForStream) {
            logAudioPassthroughOutcome(agent, conversation, primary, "accepted",
                    null, !supportsAudioForStream);
        }

        // Round 1 folded into turn-level usage. addRound also propagates
        // round-local reasoning/content timing into TurnUsage so the
        // turn-level reasoningDurationMs spans first-reasoning → first-content
        // across every round (matches the frontend's live measurement).
        turnUsage.addRound(accumulator);
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
        boolean replyTruncated = isTruncationFinish(accumulator.finishReason)
                && accumulator.toolCalls.isEmpty();
        if (replyTruncated) {
            logEmptyToolCallsTruncation("streamLlmLoop", agent, conversation, primary,
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
            content = handleToolCallsStreaming(agent, conversation, conversation.id, messages, tools,
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
     * JCLAW-165: takes the audio-bearer side-map so the format-rejection
     * retry path can rewrite messages to text-with-transcript and re-issue
     * the call once.
     */
    @SuppressWarnings({"java:S107", "java:S127"}) // S107: internal tool-loop dispatcher; S127: round-- in body is JCLAW-165's single-use audio-format retry
    /**
     * JCLAW-291: result wrapper for {@link #callWithToolLoop}. Carries the
     * model's reply text plus a {@code truncated} flag set when the final
     * non-tool-call assistant turn came back with {@code finish_reason =
     * length / max_tokens}. Caller plumbs the flag into the persist site
     * and (for subagents) into the {@link RunResult} so the announce
     * card can surface a truncation marker without the chat UI having
     * to introspect raw provider responses.
     */
    public record LoopOutcome(String content, boolean truncated) {
        public LoopOutcome(String content) { this(content, false); }
    }

    private static LoopOutcome callWithToolLoop(Agent agent, Conversation conversation, Long conversationId,
                                            List<ChatMessage> messages, List<ToolDef> tools,
                                            LlmProvider primary, LlmProvider secondary,
                                            List<AudioBearer> audioBearers) {
        // Helpers like effectiveModelId / effectiveMaxTokens accept a nullable
        // conversation for use elsewhere, but this loop dereferences
        // conversation.channelType when handing off to the LLM provider —
        // the channel type is a required field on the call. Assert the
        // precondition explicitly so a future caller passing null gets a
        // clear failure here instead of an opaque NPE deeper in the stack.
        Objects.requireNonNull(conversation, "conversation");
        var currentMessages = new ArrayList<>(messages);
        var thinkingMode = resolveThinkingMode(agent, conversation, primary);
        var effectiveModelId = effectiveModelId(agent, conversation);
        var modelInfoForOutcome = resolveModelInfo(agent, conversation, primary).orElse(null);
        var supportsAudioInitially = modelInfoForOutcome != null && modelInfoForOutcome.supportsAudio();
        boolean audioRetryAttempted = false;
        boolean transcriptAwaitedAlready = !supportsAudioInitially && !audioBearers.isEmpty();

        for (int round = 0; round < maxToolRounds(); round++) {
            // JCLAW-291: cooperative-cancel checkpoint at the top of each
            // LLM round. Between-rounds is the natural safe point — we
            // never check inside a streaming chunk handler (too chatty)
            // or mid-tool-call (would orphan partial side effects).
            checkSubagentCancel(conversation);
            // Recompute per-round so the clamp tracks the growing history.
            var maxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, primary, currentMessages, tools);
            ChatResponse response;
            try {
                response = (secondary != null)
                        ? LlmProvider.chatWithFailover(primary, secondary, effectiveModelId, currentMessages, tools, maxTokens, thinkingMode, conversation.channelType)
                        : primary.chat(effectiveModelId, currentMessages, tools, maxTokens, thinkingMode, conversation.channelType);
            } catch (Exception e) {
                // JCLAW-165: provider-side audio-format rejection — fall back
                // to transcript-as-text and retry once. Only kicks in when the
                // request actually carried audio (audioBearers non-empty) and
                // we haven't already retried this turn.
                if (!audioRetryAttempted && !audioBearers.isEmpty() && isAudioFormatRejection(e)) {
                    audioRetryAttempted = true;
                    transcriptAwaitedAlready = true;
                    if (!anyTranscriptAvailable(audioBearers)) {
                        // No usable transcript means we'd just send fallback
                        // notes — better to fail with a clear error than
                        // ship a degraded prompt the user can't tell came
                        // from a transcription failure.
                        logAudioPassthroughOutcome(agent, conversation, primary, "error",
                                "no_transcript_after_rejection", true);
                        EventLogger.warn("llm", agent.name, null,
                                "Audio format rejected and no transcript available — failing turn");
                        return new LoopOutcome("I'm sorry — the audio attachment couldn't be transcribed and the model rejected the audio format directly. Please try again.");
                    }
                    EventLogger.warn("llm", agent.name, null,
                            "Provider %s rejected audio format; retrying with transcript-as-text"
                                    .formatted(primary.config().name()));
                    currentMessages = new ArrayList<>(applyTranscriptsForCapability(
                            currentMessages, audioBearers, false));
                    round--;  // JCLAW-165: re-issue this round with the rewritten messages (gated by audioRetryAttempted)
                    continue;
                }
                EventLogger.error("llm", agent.name, null, "LLM call failed: %s".formatted(e.getMessage()));
                if (!audioBearers.isEmpty()) {
                    logAudioPassthroughOutcome(agent, conversation, primary, "error",
                            shortErrorTag(e), transcriptAwaitedAlready);
                }
                return new LoopOutcome("I'm sorry, I encountered an error communicating with the AI provider. Please try again.");
            }
            // Successful response. Fire AUDIO_PASSTHROUGH_OUTCOME log when the
            // request carried audio so the field-data set we'll later use to
            // grow a known-good provider/format matrix has full coverage.
            if (round == 0 && !audioBearers.isEmpty()) {
                logAudioPassthroughOutcome(agent, conversation, primary,
                        audioRetryAttempted ? "downgraded" : "accepted",
                        null, transcriptAwaitedAlready);
            }

            if (response.choices() == null || response.choices().isEmpty()) {
                return new LoopOutcome("No response received from the AI provider.");
            }

            var choice = response.choices().getFirst();
            var assistantMsg = choice.message();
            boolean toolCallsEmpty = assistantMsg.toolCalls() == null || assistantMsg.toolCalls().isEmpty();

            // No tool calls — return the content. JCLAW-291: when finish_reason
            // signals truncation on this branch, the model ran out of output
            // budget mid-reply (the prompt-fills-window scenario). Carry the
            // flag up to the persist site so the chat UI can mark the row.
            if (toolCallsEmpty) {
                if (isTruncationFinish(choice.finishReason())) {
                    logEmptyToolCallsTruncation("callWithToolLoop", agent, conversation, primary,
                            conversation.channelType, choice.finishReason(), currentMessages, tools);
                    return new LoopOutcome(contentAsString(assistantMsg.content()), true);
                }
                return new LoopOutcome(contentAsString(assistantMsg.content()));
            }

            // Check for truncated response (max tokens hit mid-tool-call)
            if (isTruncationFinish(choice.finishReason())) {
                EventLogger.warn("llm", agent.name, null,
                        "Response truncated (finish_reason=length) with pending tool calls — skipping execution of incomplete tool arguments");
                var content = assistantMsg.content() != null ? (String) assistantMsg.content()
                        : "I tried to use a tool but my response was too long and got cut off. Let me try a more concise approach.";
                return new LoopOutcome(content, true);
            }

            // Tool calls — execute (in parallel when multiple) and continue
            currentMessages.add(assistantMsg);
            int toolResultsAnchor = currentMessages.size();
            EventLogger.info("tool", agent.name, null,
                    "Round %d: executing %d tool call(s)".formatted(round + 1, assistantMsg.toolCalls().size()));

            ParallelToolExecutor.executeToolsParallel(assistantMsg.toolCalls(), agent, conversationId,
                    currentMessages, null, null, null, null);

            // JCLAW-291: cooperative-cancel checkpoint between tool calls
            // and the next LLM round. If /subagent kill landed during the
            // tool-call batch, abort here rather than spending another
            // round on the now-stale plan.
            checkSubagentCancel(conversation);

            // JCLAW-273: detect a successful yield_to_subagent call and bail
            // out of the tool-call loop without continuing to the next LLM
            // round. The runner returns YIELDED_RESPONSE so the caller skips
            // its final-assistant-message persist; the parent's logical
            // turn resumes later from tools.SpawnSubagentTool#runAsyncAndAnnounce
            // once the child terminates.
            if (yieldRequestedInLastRound(currentMessages, toolResultsAnchor)) {
                EventLogger.info("tool", agent.name, null,
                        "Round %d: yield_to_subagent invoked — suspending parent turn".formatted(round + 1));
                return new LoopOutcome(YIELDED_RESPONSE);
            }
        }

        return new LoopOutcome("I reached the maximum number of tool execution rounds. Please try a simpler request.");
    }

    /**
     * JCLAW-273: scan the just-appended tool-result entries (those at index
     * {@code >= fromIndex}) for the
     * {@link tools.YieldToSubagentTool#YIELD_SENTINEL_PREFIX} marker that the
     * yield companion tool returns on success. Returns {@code true} if any
     * tool-result content starts with the marker, meaning the parent's loop
     * should exit without emitting a final assistant reply.
     *
     * <p>String-prefix scan rather than parsing JSON because the AC sentinel
     * shape is closed (the tool always returns the same shape) and a prefix
     * compare is robust against any future field reordering.
     */
    private static boolean yieldRequestedInLastRound(List<ChatMessage> currentMessages, int fromIndex) {
        for (int i = fromIndex; i < currentMessages.size(); i++) {
            var m = currentMessages.get(i);
            if (m == null) continue;
            if (!MessageRole.TOOL.value.equals(m.role())) continue;
            var content = m.content();
            if (content instanceof String s
                    && s.startsWith(tools.YieldToSubagentTool.YIELD_SENTINEL_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * JCLAW-165 heuristic: detect provider 400-class errors that are actually
     * "we don't accept this audio format" rejections rather than generic
     * client errors. Looks for the keywords providers spell this concept with:
     * OpenAI's {@code unsupported_format}, Gemini's {@code invalid_argument},
     * and the more generic {@code format} + {@code not supported} / {@code unsupported}
     * combos. False positives downgrade to a usable transcript-text retry —
     * worse than a passthrough success but better than a flat error — so the
     * heuristic is intentionally lenient.
     */
    public static boolean isAudioFormatRejection(Throwable t) {
        if (t == null) return false;
        var msg = t.getMessage();
        if (msg == null) return false;
        var lower = msg.toLowerCase();
        if (!lower.contains("http 4")) return false; // 400-class only
        if (lower.contains("unsupported_format")) return true;
        if (lower.contains("invalid_argument") || lower.contains("invalid argument")) return true;
        if (lower.contains("format") && (lower.contains("not supported") || lower.contains("unsupported"))) return true;
        return lower.contains("audio") && lower.contains("format");
    }

    /** Check whether at least one of the audio attachments has a non-empty
     *  transcript persisted. Used by the rejection-retry path to decide
     *  whether to retry with text or fail with a user-visible error. */
    private static boolean anyTranscriptAvailable(List<AudioBearer> audioBearers) {
        if (audioBearers == null || audioBearers.isEmpty()) return false;
        return services.Tx.run(() -> {
            for (var b : audioBearers) {
                for (var attId : b.audioAttachmentIds()) {
                    var att = (models.MessageAttachment) models.MessageAttachment.findById(attId);
                    if (att != null && att.transcript != null && !att.transcript.isBlank()) return true;
                }
            }
            return false;
        });
    }

    /** Compact one-token tag for the {@code error_tag} field of the
     *  {@code AUDIO_PASSTHROUGH_OUTCOME} log event. We only emit the
     *  exception class + a short status hint to keep the field
     *  searchable; full error bodies stay out of structured logs to
     *  avoid leaking provider-side prose. */
    private static String shortErrorTag(Throwable t) {
        if (t == null) return "unknown";
        var name = t.getClass().getSimpleName();
        var msg = t.getMessage();
        if (msg == null) return name;
        var lower = msg.toLowerCase();
        if (lower.contains("http 4")) return name + ":4xx";
        if (lower.contains("http 5")) return name + ":5xx";
        if (lower.contains("timeout") || lower.contains("timed out")) return name + ":timeout";
        return name;
    }

    /**
     * JCLAW-165 / absorbed JCLAW-169: structured log event emitted on
     * every audio-bearing LLM call so the field-data set grows a
     * provider/format/outcome matrix we can act on later. Only format
     * + timing metadata is logged; no message content, no PII.
     *
     * @param outcome one of {@code "accepted"} (passthrough success),
     *                {@code "downgraded"} (success after retry), or
     *                {@code "error"} (failed even after retry).
     * @param errorTag short tag from {@link #shortErrorTag} when
     *                 {@code outcome="error"}; null otherwise.
     * @param transcriptAwaited whether any branch awaited a Whisper
     *                          future during this call — true on the
     *                          text-only branch and on rejection-retry,
     *                          false on the audio-capable happy path.
     */
    private static void logAudioPassthroughOutcome(Agent agent, Conversation conversation,
                                                    LlmProvider provider, String outcome,
                                                    String errorTag, boolean transcriptAwaited) {
        var providerName = provider != null && provider.config() != null
                ? provider.config().name() : "unknown";
        var modelId = effectiveModelId(agent, conversation);
        var channel = conversation != null ? conversation.channelType : null;
        var detail = "provider=%s model=%s outcome=%s transcript_awaited=%s%s".formatted(
                providerName, modelId, outcome, transcriptAwaited,
                errorTag != null ? " error_tag=" + errorTag : "");
        EventLogger.info("AUDIO_PASSTHROUGH_OUTCOME",
                agent != null ? agent.name : null, channel, detail);
    }

    @SuppressWarnings("java:S107") // Tool-call streaming dispatcher — every parameter is required orchestration state
    private static String handleToolCallsStreaming(Agent agent, Conversation conversation, Long conversationId,
                                                    List<ChatMessage> messages, List<ToolDef> tools,
                                                    List<ToolCall> toolCalls, String priorContent,
                                                    LlmProvider provider,
                                                    StreamingCallbacks cb,
                                                    String thinkingMode,
                                                    int round, AtomicBoolean isCancelled,
                                                    LatencyTrace trace,
                                                    LlmProvider.TurnUsage turnUsage,
                                                    List<String> collectedImages,
                                                    String channelType) {
        if (round >= maxToolRounds()) {
            return "I reached the maximum number of tool execution rounds. Please try a simpler request.";
        }
        if (isCancelled.get()) {
            return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);
        }
        EventLogger.info("tool", agent.name, null,
                "Streaming round %d: executing %d tool call(s)".formatted(round + 1, toolCalls.size()));

        var currentMessages = new ArrayList<>(messages);
        currentMessages.add(ChatMessage.assistant(priorContent, toolCalls));

        int streamingToolResultsAnchor = currentMessages.size();
        var toolRoundStartNs = System.nanoTime();
        ParallelToolExecutor.executeToolsParallel(toolCalls, agent, conversationId, currentMessages,
                cb.onStatus(), cb.onToolCall(), collectedImages, isCancelled);
        trace.addToolRound((System.nanoTime() - toolRoundStartNs) / 1_000_000L);

        if (isCancelled.get()) return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);

        // JCLAW-291: cooperative-cancel checkpoint between the tool round
        // and the LLM continuation. Subagent-driven streaming runs aren't
        // the common case but the checkpoint is cheap and keeps the two
        // round-loops symmetric.
        checkSubagentCancel(conversation);

        // JCLAW-273: yield_to_subagent detected in this round — exit the
        // streaming loop without continuing to the next LLM round and
        // without emitting a final assistant payload. Returning the
        // YIELDED_RESPONSE sentinel lets streamLlmLoop short-circuit its
        // persistence + terminal-callback path; the parent's logical turn
        // resumes later from tools.SpawnSubagentTool#runAsyncAndAnnounce.
        if (yieldRequestedInLastRound(currentMessages, streamingToolResultsAnchor)) {
            EventLogger.info("tool", agent.name, null,
                    "Streaming round %d: yield_to_subagent invoked — suspending parent turn"
                            .formatted(round + 1));
            return YIELDED_RESPONSE;
        }

        cb.onStatus().accept("Processing results (round %d)...".formatted(round + 1));
        EventLogger.info("llm", agent.name, null,
                "Streaming round %d: continuing LLM call after tool results".formatted(round + 1));

        // Continue with streaming after tool results. JCLAW-108: effective
        // model id honors conversation override, same as the round-1 call.
        var effectiveModelIdForCall = effectiveModelId(agent, conversation);
        // Recompute max_tokens against the grown message list so the clamp
        // tightens as the tool loop accumulates history.
        var maxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, provider, currentMessages, tools);
        var accumulator = provider.chatStreamAccumulate(
                effectiveModelIdForCall, currentMessages, tools, cb.onToken(), cb.onReasoning(),
                maxTokens, thinkingMode, channelType);

        try {
            if (!CancellationManager.awaitAccumulatorOrCancel(accumulator, isCancelled, agent, null, cb))
                return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);
        }

        if (isCancelled.get()) return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);

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

        // Recursively handle if more tool calls. JCLAW-104: pass the SAME
        // collectedImages through so images from this round accumulate into
        // the deeper round's final buildImagePrefix call. channelType threads
        // through too so buildDownloadSuffix can stay channel-aware.
        if (!accumulator.toolCalls.isEmpty()) {
            return handleToolCallsStreaming(agent, conversation, conversationId, currentMessages, tools,
                    accumulator.toolCalls, accumulator.content, provider, cb, thinkingMode,
                    round + 1, isCancelled, trace, turnUsage, collectedImages, channelType);
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

            var retryMaxTokens = ContextWindowManager.effectiveMaxTokens(agent, conversation, provider, retryMessages, tools);
            var retry = provider.chatStreamAccumulate(
                    effectiveModelIdForCall, retryMessages, tools, cb.onToken(), cb.onReasoning(),
                    retryMaxTokens, thinkingMode, channelType);
            try {
                if (!CancellationManager.awaitAccumulatorOrCancel(retry, isCancelled, agent, null, cb))
                    return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CancellationManager.cancelledReturn(priorContent, collectedImages, channelType, cb, agent, round);
            }

            // Retry round is a real LLM call — its usage counts too (JCLAW-76).
            turnUsage.addRound(retry);

            if (retry.content != null && !retry.content.isBlank()) {
                return MessageDeduplicator.buildImagePrefix(collectedImages, retry.content)
                        + retry.content
                        + MessageDeduplicator.buildDownloadSuffix(collectedImages, retry.content, channelType);
            }

            // Retry also empty — emit a labeled diagnostic so the user knows why.
            EventLogger.warn("llm", agent.name, null,
                    "Retry also returned empty content — emitting diagnostic fallback");
            // No LLM content to dedupe against — prepend every collected image unchanged.
            var fallbackPrefix = collectedImages.isEmpty() ? ""
                    : String.join("\n\n", collectedImages) + "\n\n";
            var fallbackSuffix = MessageDeduplicator.buildDownloadSuffix(collectedImages, "", channelType);
            var fallback = fallbackPrefix
                    + "*[The model returned no synthesis after tool calls. Tool results are in the conversation history above — try rephrasing your request or switching to a larger model.]*"
                    + fallbackSuffix;
            cb.onToken().accept(fallback);
            return fallback;
        }

        return MessageDeduplicator.buildImagePrefix(collectedImages, accumulator.content)
                + accumulator.content
                + MessageDeduplicator.buildDownloadSuffix(collectedImages, accumulator.content, channelType);
    }


    /**
     * OpenAI {@code input_audio.format} values we know the shared adapter
     * can emit. JCLAW-132: {@link services.MimeExtensions} reverse-looks a
     * MIME against this list to derive the format hint; the underlying data
     * lives in Play's {@code mime-types.properties} + {@code mimetype.*}
     * application config, so adding a new audio format is a config change,
     * not a code edit.
     */
    private static final String[] AUDIO_FORMAT_CANDIDATES = {
            "mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "opus", "weba"
    };

    /**
     * Build the {@link ChatMessage} for a historical user turn, lifting it
     * into OpenAI-style content parts when the row has attachments. Images
     * ride as {@code image_url} parts (JCLAW-25); audio rides as
     * {@code input_audio} parts (JCLAW-132); other files fall through as
     * bracketed filename references inside the text part. Plain-text turns
     * without attachments still emit the compact {@code {role,content:"..."}}
     * shape; the provider registry's shared serializer routes either form
     * correctly.
     *
     * <p>Exposed as {@code public} purely so the unit test
     * {@code VisionAudioAssemblyTest} can exercise the content-part
     * assembly directly — Play 1.x pins tests to the default package, so
     * package-private access is unreachable from the test. Not part of
     * the production contract; callers outside {@link AgentRunner} itself
     * should route through {@link #buildMessages}.
     */
    public static ChatMessage userMessageFor(models.Message msg) {
        // Default behaviour preserves the pre-JCLAW-165 input_audio shape;
        // audio-capable models still go through this overload from
        // buildMessages and from any caller that wants the Telegram
        // happy-path (Gemini accepting OGG Opus natively).
        return userMessageFor(msg, true);
    }

    /**
     * JCLAW-165 capability-aware overload. {@code supportsAudio=true} emits
     * the OpenAI {@code input_audio} content part for each audio attachment;
     * {@code supportsAudio=false} emits a text part containing the cached
     * transcript (or a clear "could not be transcribed" fallback note when
     * the transcript field is null/empty).
     *
     * <p>This method is pure with respect to async work: it reads the
     * {@link models.MessageAttachment#transcript} field as-is. The caller
     * is responsible for awaiting any in-flight {@link services.transcription.PendingTranscripts}
     * future and persisting the result before calling with
     * {@code supportsAudio=false} — the orchestrator handles that outside
     * any blocking JPA transaction.
     */
    public static ChatMessage userMessageFor(models.Message msg, boolean supportsAudio) {
        // Defensive fallback: in most paths Play's enhancer installs a
        // Hibernate lazy proxy on @OneToMany and the field is non-null;
        // VisionAudioAssemblyTest saw null collections after direct entity
        // manipulation, so the explicit query is cheap insurance against
        // the field being null for any reason.
        var atts = msg.attachments;
        if (atts == null) {
            atts = models.MessageAttachment.findByMessage(msg);
        }
        if (atts.isEmpty()) {
            return ChatMessage.user(msg.content);
        }

        var text = msg.content == null ? "" : msg.content;
        var fileNotes = new StringBuilder();
        var parts = new ArrayList<Map<String, Object>>();
        for (var a : atts) {
            if (!a.isImage() && !a.isAudio()) {
                // FILE-kind attachments are surfaced to the LLM as a
                // filename + workspace path it can read via the filesystem
                // tool. Images and audio ride as structured content parts
                // below, so they skip this branch.
                fileNotes.append("\n[Attached file: ")
                        .append(a.originalFilename)
                        .append(" — workspace:")
                        .append(a.storagePath)
                        .append("]");
            }
        }
        // Transcript blocks for the !supportsAudio branch ride INSIDE the
        // text part so the LLM sees one cohesive prompt rather than fragmented
        // text + transcript. Append after the user's typed content (if any).
        var transcriptBlocks = new StringBuilder();
        if (!supportsAudio) {
            for (var a : atts) {
                if (a.isAudio()) {
                    var transcript = a.transcript;
                    if (transcript != null && !transcript.isBlank()) {
                        transcriptBlocks.append("\n\n[Voice note transcription: ")
                                .append(transcript.trim())
                                .append("]");
                    } else {
                        transcriptBlocks.append("\n\n[Voice note ")
                                .append(a.originalFilename != null ? a.originalFilename : "unnamed")
                                .append(": transcription unavailable]");
                    }
                }
            }
        }
        var combinedText = text + fileNotes + transcriptBlocks;
        if (!combinedText.isBlank()) {
            parts.add(Map.of("type", "text", "text", combinedText));
        }
        for (var a : atts) {
            if (a.isImage()) {
                parts.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", services.AttachmentService.readAsDataUrl(a))));
            } else if (a.isAudio() && supportsAudio) {
                var format = services.MimeExtensions.forMime(a.mimeType, AUDIO_FORMAT_CANDIDATES);
                var inner = new LinkedHashMap<String, Object>();
                inner.put("data", services.AttachmentService.readAsBase64(a));
                if (!format.isEmpty()) inner.put("format", format);
                parts.add(Map.of("type", "input_audio", "input_audio", inner));
            }
        }
        return new ChatMessage(MessageRole.USER.value, parts, null, null, null);
    }

    /**
     * JCLAW-165: backward-compat overload for callers that don't need the
     * audio-bearer side-map (compaction path, tests). Drops the captured
     * refs on the floor.
     */
    private static List<ChatMessage> buildMessages(String systemPrompt, Conversation conversation) {
        return buildMessages(systemPrompt, conversation, new ArrayList<>());
    }

    /**
     * JCLAW-165: tracks which user messages in the assembled list carry
     * audio attachments, so the post-Tx orchestrator can rewrite them as
     * text-with-transcript when the active model lacks {@code supportsAudio}.
     * The {@code chatMessageIndex} is the position in the returned
     * {@link ChatMessage} list (which always begins with the system prompt
     * at index 0); {@code msgId} re-locates the persisted Message in a
     * fresh transaction at rewrite time.
     */
    record AudioBearer(int chatMessageIndex, Long msgId, List<Long> audioAttachmentIds) {}

    /**
     * Build the LLM message list and capture the audio-bearer side-map
     * concurrently. Caller passes in {@code audioBearersOut} (typically a
     * fresh ArrayList); the method appends one entry per user message that
     * has audio attachments. Inside-Tx use only — reads conversation
     * history via {@link ConversationService#loadRecentMessages} which
     * touches lazy collections.
     */
    /** Bounded ceiling for awaiting a Whisper future during message
     *  reassembly. The text-only branch normally awaits a future whose
     *  VT has been running since appendUserMessage committed (well before
     *  this point), so timeouts should be rare; the cap is defensive
     *  against pathological transcription stalls (e.g. a stuck ffmpeg).
     *  After timeout, the empty-string sentinel is used and the fallback
     *  "could not be transcribed" note appears in the prompt. */
    private static final long TRANSCRIPT_AWAIT_TIMEOUT_SECONDS = 60;

    /**
     * JCLAW-165 outside-Tx helper: when the active model lacks
     * {@code supportsAudio}, await any in-flight transcripts for the
     * messages identified by {@code audioBearers}, then rebuild those
     * user messages as text-with-transcript via
     * {@link #userMessageFor(models.Message, boolean) userMessageFor(msg, false)}.
     *
     * <p>Awaits run on the calling (typically virtual) thread with a
     * bounded timeout per future. The dispatcher in
     * {@link services.ConversationService} also persists the transcript
     * to {@link models.MessageAttachment#transcript} on success, so the
     * fresh re-fetch inside {@code userMessageFor} sees the field
     * populated. Failed/timed-out futures leave the field NULL and the
     * fallback note carries the user's original filename.
     *
     * <p>Returns {@code messages} unchanged when {@code supportsAudio}
     * is true or {@code audioBearers} is empty — the audio-capable
     * happy path keeps zero added latency.
     */
    private static List<ChatMessage> applyTranscriptsForCapability(
            List<ChatMessage> messages, List<AudioBearer> audioBearers, boolean supportsAudio) {
        if (supportsAudio || audioBearers == null || audioBearers.isEmpty()) return messages;

        // Phase 1 (no Tx): await each in-flight future. Bounded timeout
        // protects against a stuck Whisper from blocking the LLM call
        // indefinitely. Failures resolve with empty-string sentinel via
        // the dispatcher's catch-all, so .get() returns "" rather than
        // throwing — the timeout branch is the only one the catch handles.
        for (var b : audioBearers) {
            for (var attId : b.audioAttachmentIds()) {
                var future = services.transcription.PendingTranscripts.lookup(attId);
                if (future.isEmpty()) continue;
                try {
                    future.get().get(TRANSCRIPT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    EventLogger.warn("transcription",
                            "Transcript await timeout for attachment %d after %ds"
                                    .formatted(attId, TRANSCRIPT_AWAIT_TIMEOUT_SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return messages;
                } catch (Exception _) {
                    // ExecutionException — the dispatcher's silent-failure
                    // contract means this shouldn't fire, but treat any
                    // surprise as "use the fallback note."
                }
            }
        }

        // Phase 2 (fresh Tx): refetch each affected message + rebuild.
        return services.Tx.run(() -> {
            var rewritten = new ArrayList<>(messages);
            for (var b : audioBearers) {
                var msg = (models.Message) models.Message.findById(b.msgId());
                if (msg == null) continue;
                rewritten.set(b.chatMessageIndex(), userMessageFor(msg, false));
            }
            return rewritten;
        });
    }

    private static List<ChatMessage> buildMessages(String systemPrompt, Conversation conversation,
                                                    List<AudioBearer> audioBearersOut) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(systemPrompt));

        // JCLAW-193: tool-row history doesn't store the function name, but
        // Ollama Cloud's Gemini bridge requires it on the tool-result message.
        // Recover it from the immediately-preceding ASSISTANT row's tool_calls
        // by registering id->name as we iterate; the loop is in chronological
        // order, so the assistant row containing the matching call is always
        // visited before its TOOL row.
        var toolNamesById = new HashMap<String, String>();

        var history = ConversationService.loadRecentMessages(conversation);
        for (var msg : history) {
            var role = MessageRole.fromValue(msg.role);
            messages.add(switch (role != null ? role : MessageRole.USER) {
                case USER -> {
                    // Capture audio-bearer ids before assembling the
                    // ChatMessage so the rewrite path can re-target the
                    // exact slot in the messages list.
                    var atts = msg.attachments;
                    if (atts == null) atts = models.MessageAttachment.findByMessage(msg);
                    var audioIds = new ArrayList<Long>();
                    for (var a : atts) {
                        if (a.isAudio() && a.id != null) audioIds.add(a.id);
                    }
                    if (!audioIds.isEmpty()) {
                        audioBearersOut.add(new AudioBearer(messages.size(), msg.id, audioIds));
                    }
                    yield userMessageFor(msg);
                }
                case ASSISTANT -> {
                    if (msg.toolCalls != null && !msg.toolCalls.isBlank()) {
                        var toolCalls = parseToolCalls(msg.toolCalls);
                        for (var tc : toolCalls) {
                            if (tc.id() != null && tc.function() != null && tc.function().name() != null) {
                                toolNamesById.put(tc.id(), tc.function().name());
                            }
                        }
                        yield ChatMessage.assistant(msg.content, toolCalls);
                    }
                    yield ChatMessage.assistant(msg.content != null ? msg.content : "");
                }
                // JCLAW-119: sanitize the tool_call_id on the TOOL-role row so
                // it matches the normalized id on the assistant-row tool_calls.
                // Paired normalization is deterministic — same input string
                // produces the same output on both sides of the pair — so this
                // does not break pairing.
                case TOOL -> {
                    var sanitizedId = sanitizeToolCallId(msg.toolResults);
                    yield ChatMessage.toolResult(sanitizedId, toolNamesById.get(sanitizedId), msg.content);
                }
                case SYSTEM -> ChatMessage.system(msg.content);
            });
        }

        return messages;
    }

    /**
     * Replace every character outside {@code [a-zA-Z0-9_-]} with {@code '_'}
     * (JCLAW-119). Historical tool_call IDs sometimes carry provider-specific
     * shapes — Gemini and some open-weight model servers emit forms like
     * {@code "functions.web_search:7"} — that stricter providers (Ollama
     * Cloud on kimi-k2.6, for example) reject with HTTP 400 {@code
     * "invalid tool call arguments"}. Normalizing at read time lets a
     * /model switch across provider families keep working without
     * mutating the DB or losing context. Returns {@code null} unchanged
     * so callers can detect missing IDs.
     */
    public static String sanitizeToolCallId(String id) {
        if (id == null) return null;
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * If {@code current} exceeds the compaction budget for the effective
     * model, run {@link services.SessionCompactor#compact} and return a
     * freshly rebuilt message list (with the new summary injected into
     * the system prompt and the older turns dropped). Otherwise returns
     * {@code current} unchanged (JCLAW-38).
     *
     * <p>Called from both {@link #run} and {@link #streamLlmLoop} after
     * the initial prep Tx closes, because the summarization call itself
     * is LLM-bound and must not hold a JDBC connection. On success the
     * caller should still pass the result through
     * {@link #trimToContextWindow} as a final safety net — if the
     * summary plus retained tail somehow still doesn't fit, drop-oldest
     * guarantees we never ship an over-budget context.
     */
    private static List<ChatMessage> maybeCompactAndRebuild(
            Agent agent, Long conversationId, String userMessage,
            java.util.Set<String> disabledTools, LlmProvider primary,
            List<ChatMessage> current) {
        // Cheap snapshot: model info + effective model id + channel type.
        // resolveModelInfo reads only in-memory provider config, so this
        // Tx is bounded by one findById.
        var snapshot = services.Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) return null;
            var mi = resolveModelInfo(agent, conv, primary).orElse(null);
            var modelId = effectiveModelId(agent, conv);
            return new CompactionDecision(mi, modelId, conv.channelType);
        });
        if (snapshot == null || snapshot.modelInfo() == null || snapshot.modelId() == null) return current;
        if (!services.SessionCompactor.shouldCompact(ContextWindowManager.estimateTokens(current), snapshot.modelInfo())) return current;

        final var modelId = snapshot.modelId();
        final var compactionChannel = snapshot.channelType();
        final var maxOutput = services.ConfigService.getInt("chat.compactionMaxTokens", 8192);
        final var modelLabel = primary.config().name() + "/" + modelId;

        services.SessionCompactor.Summarizer summarizer = sumMsgs -> {
            var resp = primary.chat(modelId, sumMsgs, List.of(), maxOutput, null, compactionChannel);
            return services.SessionCompactor.firstChoiceText(resp);
        };

        var result = services.SessionCompactor.compact(conversationId, modelLabel, summarizer);
        if (!result.compacted()) {
            EventLogger.info("compaction", agent.name, snapshot.channelType(),
                    "Compaction skipped (%s); falling back to drop-oldest".formatted(result.skipReason()));
            return current;
        }
        EventLogger.info("compaction", agent.name, snapshot.channelType(),
                "Compacted %d turns (%d chars) via %s".formatted(
                        result.turnsCompacted(), result.summaryChars(), modelLabel));

        // Rebuild messages: fresh read picks up the bumped compactionSince,
        // appendSummaryToPrompt re-injects the (now stored) summary.
        return services.Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) return current;
            var assembled = SystemPromptAssembler.assemble(agent, userMessage, disabledTools, conv.channelType);
            var sysPrompt = services.SessionCompactor.appendSummaryToPrompt(assembled.systemPrompt(), conv);
            // JCLAW-268: re-inject spawn-time parent context for inherit-mode subagents.
            sysPrompt = services.SessionCompactor.appendParentContextToPrompt(sysPrompt, conv);
            return buildMessages(sysPrompt, conv);
        });
    }

    private record CompactionDecision(ModelInfo modelInfo, String modelId, String channelType) {}

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
     * JCLAW-291: emit a structured warn line whenever the model truncates a
     * plain (non-tool-call) reply via {@code finish_reason = length /
     * max_tokens}. Mirrors the existing tool-call truncation guards but
     * dumps the headroom math so operators can correlate the truncation
     * with the model's effective output budget. Single call site so the
     * format stays canonical across streaming and non-streaming.
     */
    private static void logEmptyToolCallsTruncation(String site, Agent agent, Conversation conversation,
                                                     LlmProvider provider, String channelType,
                                                     String finishReason, List<ChatMessage> messages,
                                                     List<ToolDef> tools) {
        var modelInfo = resolveModelInfo(agent, conversation, provider).orElse(null);
        int promptTokens = ContextWindowManager.estimateTokens(messages) + ContextWindowManager.estimateToolTokens(tools);
        int configured = modelInfo != null ? modelInfo.maxTokens() : -1;
        int contextWindow = modelInfo != null ? modelInfo.contextWindow() : -1;
        int headroom = contextWindow > 0
                ? contextWindow - promptTokens - ContextWindowManager.OUTPUT_SAFETY_MARGIN_TOKENS
                : -1;
        Integer clamped = ContextWindowManager.effectiveMaxTokens(agent, conversation, provider, messages, tools);
        EventLogger.warn("llm", agent.name, channelType,
                "Truncated reply (site=%s, finish=%s, configured=%d, contextWindow=%d, prompt~%d, headroom=%d, clamped=%s)"
                        .formatted(site, finishReason, configured, contextWindow, promptTokens, headroom,
                                clamped == null ? "null" : clamped.toString()));
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
            if (tc == null) return List.of();
            // JCLAW-119: normalize historical IDs so cross-provider /model
            // switches don't re-ship IDs the new provider rejects. Same
            // transformation as the TOOL-role sanitizer in buildMessages so
            // assistant-tool_calls and tool-row tool_call_id still pair.
            var safeId = sanitizeToolCallId(tc.id());
            if (!java.util.Objects.equals(safeId, tc.id())) {
                tc = new ToolCall(safeId, tc.type(), tc.function());
            }
            return List.of(tc);
        } catch (Exception _) {
            return List.of();
        }
    }
}
