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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Core agent pipeline: receive message → load conversation → assemble prompt →
 * call LLM → handle tool calls (loop) → persist response → return.
 */
public class AgentRunner {

    private static final Gson gson = INSTANCE;
    /**
     * Default per-turn tool-round cap. Raised from 10 to 100 ahead of
     * JCLAW-21 Tasks: scheduled task fires have no human in the loop to
     * nudge a continuation, so the budget needs headroom for fan-out work
     * (e.g. "summarise yesterday + email it" runs through fetch → parse →
     * summarise → format → send chains that legitimately consume rounds).
     * Hermes ships with 90 by default for the same reason; OpenClaw
     * inherits per-agent. Override via {@code chat.maxToolRounds} in
     * application.conf.
     */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 100;

    /** EventLogger category for agent-lifecycle events (turn suspend, resume, yield). Also doubles as the NPE message for the {@code agent} parameter. */
    private static final String EVT_CATEGORY_AGENT = "agent";

    /** Standard error surfaced when no LLM provider is configured for an agent — covers Settings.UI, EventLogger, and runtime exception messages. */
    private static final String NO_LLM_PROVIDER_ERROR =
            "No LLM provider configured. Add provider config via Settings.";

    /** Canned response returned when an inbound message can't acquire the conversation queue. */
    private static final String QUEUED_MESSAGE_RESPONSE =
            "Your message has been queued and will be processed shortly.";

    // Package-private so ToolCallLoopRunner (JCLAW-299) can read the
    // operator-configurable per-turn round cap.
    static int maxToolRounds() {
        return services.ConfigService.getInt("chat.maxToolRounds", DEFAULT_MAX_TOOL_ROUNDS);
    }

    /**
     * Carries the model's final reply along with the (possibly-new) conversation reference.
     *
     * @param response     the model's final assistant content
     * @param conversation the resolved conversation (carriers like
     *                     {@code /new} create a fresh one mid-run)
     * @param truncated    true when the final non-tool-call assistant turn
     *                     came back with {@code finish_reason=length}
     */
    public record RunResult(String response, Conversation conversation, boolean truncated) {
        /**
         * 2-arg compatibility: legacy paths that don't track truncation.
         *
         * @param response     the model's final assistant content
         * @param conversation the resolved conversation
         */
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
     * {@link tools.SubagentSpawnTool#runAsyncAndAnnounce} once the child
     * terminates).
     */
    public static final String YIELDED_RESPONSE = "__JCLAW_273_YIELDED__";

    /**
     * JCLAW-170: granular tool-invocation event, fired from the agent loop
     * once each tool call completes. Carries enough metadata for the UI to
     * render a per-call row (icon, name, arguments) plus the optional
     * structured result payload used by search-style tools to produce
     * clickable chips with favicons.
     *
     * @param id                   provider-supplied tool-call id (correlates
     *                             the assistant {@code tool_calls} entry with
     *                             the matching tool-role result row)
     * @param name                 tool name (matches {@link ToolRegistry.Tool#name})
     * @param icon                 icon identifier the UI renders next to the
     *                             tool name
     * @param arguments            raw JSON arguments string the model sent
     * @param resultText           plain-text tool output the LLM sees on its
     *                             next turn
     * @param resultStructuredJson optional structured payload the UI renders
     *                             richly; {@code null} for tools that don't
     *                             emit a structured view
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
     * @param onInit      fires once with the resolved {@link Conversation}
     *                    so transports can flush an "init" frame carrying
     *                    the conversation id / channel context
     * @param onToken     fires for every assistant content delta
     * @param onReasoning fires for every reasoning-stream delta (thinking
     *                    models only)
     * @param onStatus    out-of-band status updates the transport can
     *                    surface (e.g. "queued", "tool call in progress")
     * @param onToolCall  fires once per completed tool call with the full
     *                    {@link ToolCallEvent}
     * @param onComplete  fires once with the final assistant content when
     *                    the turn closes normally
     * @param onError     fires once with the failure cause when the turn
     *                    fails mid-stream
     * @param onCancel    fires once when {@link CancellationManager#checkCancelled}
     *                    detects a cancellation flag flip and the streaming
     *                    thread is about to early-return. Unlike
     *                    {@code onComplete} / {@code onError} it carries no
     *                    payload — its job is to let transports quiesce
     *                    side-channel state (the Telegram typing heartbeat
     *                    is the motivating case: JCLAW-181 follow-up). Web's
     *                    per-request cancellation is signalled via SSE
     *                    close, not this hook, so the web caller passes a
     *                    no-op.
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
        List<VisionAudioAssembler.AudioBearer> audioBearers,
        List<VisionAudioAssembler.ImageBearer> imageBearers,
        List<VisionAudioAssembler.VideoBearer> videoBearers
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
        List<VisionAudioAssembler.AudioBearer> audioBearers,
        List<VisionAudioAssembler.ImageBearer> imageBearers,
        List<VisionAudioAssembler.VideoBearer> videoBearers
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
     *
     * @param conversation the conversation whose pending SubagentRun should
     *                     be checked for cancellation; null or transient
     *                     conversations are no-ops
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
        if (runId != null) {
            // JCLAW-424: heartbeat the idle-timeout clock at this safe boundary
            // (before each LLM round / between tool calls) so an actively-working
            // child never trips the inactivity budget; only genuine silence does.
            services.SubagentRegistry.touch(runId);
            if (services.SubagentRegistry.isCancelled(runId)) {
                throw new RunCancelledException(runId);
            }
        }
    }

    /**
     * JCLAW-414: cooperative-cancellation checkpoint for task fires, the task
     * counterpart of {@link #checkSubagentCancel}. If the operator cancelled
     * this run via {@code POST /api/task-runs/{id}/cancel} — which flips the
     * {@link services.TaskRunRegistry} flag — throws {@link RunCancelledException}
     * so the tool loop bails at the next safe boundary. No-op when
     * {@code taskRunId} is null (the chat / subagent paths) or no cancel was
     * requested. Like the subagent path, this is cooperative (no
     * {@code Thread.interrupt()}); see {@link services.SubagentRegistry} for the
     * H2-corruption post-mortem.
     */
    public static void checkTaskRunCancel(Long taskRunId) {
        if (taskRunId != null && services.TaskRunRegistry.isCancelled(taskRunId)) {
            throw new RunCancelledException(taskRunId, "Task");
        }
    }

    /**
     * Run the agent synchronously. Returns the final assistant response.
     * JPA transactions are scoped to short Tx.run() blocks — no JDBC connection
     * is held during LLM HTTP calls or tool execution.
     *
     * @param agent        the executing agent
     * @param conversation the conversation to run inside
     * @param userMessage  the user's input text
     * @return the run outcome carrying the final assistant content and the
     *         (possibly-new) conversation reference
     */
    public static RunResult run(Agent agent, Conversation conversation, String userMessage) {
        return run(agent, conversation, userMessage, null);
    }

    /**
     * JCLAW-25 vision variant. Image attachments ride into the LLM request
     * as OpenAI {@code image_url} content parts; non-image attachments are
     * referenced by filename inside the text part.
     *
     * @param agent        the executing agent
     * @param conversation the conversation to run inside
     * @param userMessage  the user's input text
     * @param attachments  files the caller already pre-uploaded via
     *                     {@code POST /api/chat/upload}; each staged file
     *                     gets finalized into the conversation-keyed
     *                     directory and gains a
     *                     {@link models.MessageAttachment} row against the
     *                     new user message
     * @return the run outcome
     */
    public static RunResult run(Agent agent, Conversation conversation, String userMessage,
                                 java.util.List<services.AttachmentService.Input> attachments) {
        var queueMsg = new services.ConversationQueue.QueuedMessage(
                userMessage, conversation.channelType, conversation.peerId, agent);
        if (!services.ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
            return new RunResult(QUEUED_MESSAGE_RESPONSE, conversation);
        }
        return runAfterAcquire(agent, conversation, userMessage, attachments);
    }

    /**
     * Variant of {@link #run} for callers that have already acquired the
     * conversation queue via {@link services.ConversationQueue#drain}
     * (JCLAW-117). Skips {@code tryAcquire} because the caller holds
     * ownership; the shared body's {@code finally} calls
     * {@link QueueDrainOrchestrator#processQueueDrain} which releases
     * ownership when the pending deque is empty (or transfers it to the
     * next drained message).
     *
     * @param agent        the executing agent
     * @param conversation the conversation the caller already owns
     * @param userMessage  the user's input text
     * @return the run outcome
     */
    public static RunResult runWithOwnedQueue(Agent agent, Conversation conversation, String userMessage) {
        return runAfterAcquire(agent, conversation, userMessage, null, false);
    }

    /**
     * JCLAW-273: resume entrypoint used by
     * {@link tools.SubagentSpawnTool#runAsyncAndAnnounce} after a yielded
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
     *
     * @param agent        the executing agent (typically the parent of the
     *                     just-terminated child)
     * @param conversation the parent conversation carrying the yielded
     *                     subagent_announce row
     * @return the run outcome, or a queued-canned-response result when the
     *         conversation queue is currently owned by another turn
     */
    public static RunResult runYieldResume(Agent agent, Conversation conversation) {
        // Empty user message because the actual input — the child's reply —
        // is already in the persisted USER-role announce row. The
        // skipUserAppend flag suppresses appendUserMessage so we don't
        // double-append.
        var queueMsg = new services.ConversationQueue.QueuedMessage(
                "", conversation.channelType, conversation.peerId, agent);
        if (!services.ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
            return new RunResult(QUEUED_MESSAGE_RESPONSE, conversation);
        }
        return runAfterAcquire(agent, conversation, "", null, true);
    }

    private static RunResult runAfterAcquire(Agent agent, Conversation conversation, String userMessage,
                                              java.util.List<services.AttachmentService.Input> attachments) {
        return runAfterAcquire(agent, conversation, userMessage, attachments, false);
    }

    /**
     * JCLAW-21: sink-based entry point for scheduled task fires. No
     * {@link Conversation} is manufactured — each fire is a fresh agent
     * invocation with no per-fire history loaded — but the rest of the
     * runner machinery composes the same as it does for chat:
     * {@link SystemPromptAssembler} builds the system prompt,
     * {@link ToolCallLoopRunner#callWithToolLoop} drives the
     * LLM → tools → continuation cycle up to {@code chat.maxToolRounds},
     * and {@link ParallelToolExecutor} writes turns via the supplied
     * sink. The sink is the only persistence target — for the production
     * Tasks path that's a {@code TaskRunSink} writing to
     * {@code task_run_message}.
     *
     * <p><b>Stub Conversation:</b> a transient (not persisted)
     * {@link Conversation} carries the few metadata fields the loop
     * reads — {@code agent} for provider resolution,
     * {@code channelType=null} (the existing convention for non-chat
     * callers, per {@link llm.LlmProvider#chat}). The instance is never
     * passed to {@code ConversationService} or saved; only the loop's
     * helpers see it.
     *
     * <p>What this method skips vs the chat path:
     * <ul>
     *   <li>No {@link services.ConversationQueue} acquire — fires are
     *   scheduled, not queued by inbound traffic; each fire is a
     *   fresh agent run with no concurrent siblings on the same
     *   "conversation".</li>
     *   <li>No history load — every fire starts from a clean
     *   {@code [system, user]} message list.</li>
     *   <li>No compaction or context-window trim — small message list,
     *   no growth between fires.</li>
     *   <li>No audio attachments — Tasks accept only the
     *   {@code userPrompt} string. Image/audio attachment support
     *   ships as a separate Tasks-feature story.</li>
     *   <li>No streaming surface — Tasks run headless; transports
     *   render the final outcome from {@link TaskRunSink#onComplete}.</li>
     * </ul>
     *
     * <p>Reuses the chat per-turn round cap
     * ({@code chat.maxToolRounds}, default 100) — Hermes ships 90 by
     * default for the same reason, OpenClaw inherits per-agent. A
     * single shared cap keeps the loop body identical and the
     * operational dial single-pointed.
     *
     * @param agent      the executing agent
     * @param userPrompt the task's input text (typically {@code task.description})
     * @param sink       persistence target — {@link ConversationSink} for
     *                   tests that want to round-trip via the chat schema,
     *                   {@link TaskRunSink} for production task fires
     * @return outcome carrying the final assistant content and the
     *         truncated flag (true when the model hit
     *         {@code finish_reason=length} on the final turn)
     */
    public static ToolCallLoopRunner.LoopOutcome runForTask(Agent agent, String userPrompt,
                                                             AgentExecutionSink sink) {
        java.util.Objects.requireNonNull(agent, EVT_CATEGORY_AGENT);
        java.util.Objects.requireNonNull(userPrompt, "userPrompt");
        java.util.Objects.requireNonNull(sink, "sink");

        services.Tx.run(() -> sink.appendUserMessage(userPrompt, null));

        var stubConv = new Conversation();
        stubConv.agent = agent;

        // The fire path runs on db-scheduler's virtual-thread carrier with
        // no inherited JPA Tx — the chat path inherits one from the
        // [agent-stream] thread's request-scoped context, but Tasks have no
        // such context. SystemPromptAssembler reads McpServer via
        // McpServerCatalog, and ToolRegistry.getToolDefsForAgent reads
        // AgentToolConfig — both need a live EntityManager. Bundle them
        // into one short Tx that commits well before the long-running LLM
        // call below, preserving the "no Tx during LLM" design while
        // letting the assembler / tool-registry stay tx-agnostic.
        record Prelude(SystemPromptAssembler.AssembledPrompt assembled, List<ToolDef> tools) {}
        var prelude = services.Tx.run(() -> new Prelude(
                SystemPromptAssembler.assemble(agent, userPrompt, null, null),
                ToolRegistry.getToolDefsForAgent(agent)));
        var assembled = prelude.assembled();
        var tools = prelude.tools();

        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(assembled.systemPrompt()));
        messages.add(ChatMessage.user(userPrompt));

        var agentProvider = ProviderRegistry.get(ModelResolver.effectiveModelProvider(agent, stubConv));
        var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
        if (primary == null) {
            var error = NO_LLM_PROVIDER_ERROR;
            EventLogger.error("llm", agent.name, null, error);
            services.Tx.run(() -> sink.appendAssistantMessage(error, null));
            return new ToolCallLoopRunner.LoopOutcome(error);
        }
        var secondary = ProviderRegistry.getSecondary();

        EventLogger.info("llm", agent.name, null,
                "Task fire: calling %s / %s".formatted(
                        primary.config().name(),
                        ModelResolver.effectiveModelId(agent, stubConv)));

        // JCLAW-414: a task fire is the one path that can be operator-cancelled
        // mid-run; hand the TaskRun id to the loop so its checkpoints can poll
        // the cancel flag. null for any non-task sink (no-op checkpoint).
        Long taskRunId = (sink instanceof TaskRunSink trs) ? trs.taskRunId() : null;
        var outcome = ToolCallLoopRunner.callWithToolLoop(
                agent, stubConv, null, messages, tools, primary, secondary,
                new ArrayList<>(), new ArrayList<>(), sink, taskRunId); // task fire carries no attachments

        final var response = outcome.content();
        final var truncated = outcome.truncated();
        services.Tx.run(() -> sink.appendAssistantMessage(response, null, null, null, truncated));
        return outcome;
    }

    private static RunResult runAfterAcquire(Agent agent, Conversation conversation, String userMessage,
                                              java.util.List<services.AttachmentService.Input> attachments,
                                              boolean skipUserAppend) {
        final Long conversationId = conversation.id;
        // JCLAW-21: every persistence write inside the runner routes
        // through this sink. ConversationSink keeps existing chat
        // semantics (re-fetch managed entity, delegate to
        // ConversationService); TaskRunSink overrides the same surface
        // to write into task_run_message. Constructed at the boundary
        // where AgentRunner takes responsibility for the conversation.
        final AgentExecutionSink sink = new ConversationSink(conversation);
        // Non-streaming callers (background jobs, webhook follow-ups) have
        // no pre-runner queue-accept timestamp, so queue_wait is naturally
        // skipped. Every other segment is captured, which is why
        // scheduled turns now show up in the Chat Performance dashboard
        // (channel-partitioned per JCLAW-102).
        var trace = LatencyTrace.forTurn(conversation.channelType, null);
        trace.mark(LatencyTrace.PROLOGUE_REQUEST_PARSED);

        // JCLAW-291: cooperative-cancel checkpoint at the conversation-forward
        // boundary. If a /subagent kill landed between queue acquire and now,
        // bail before we burn any LLM budget or persist any state.
        checkSubagentCancel(conversation);

        try {
            // Short setup transaction: persist user message, assemble prompt, resolve provider.
            // Re-fetch the conversation by ID so it is managed in this persistence context.
            // Callers on virtual threads (TaskExecutionHandler, webhooks) pass entities that were
            // loaded in a separate, already-committed Tx.run() — those are detached and
            // would throw PersistentObjectException on save().
            var prepared = services.Tx.run(() -> {
                var conv = ConversationService.findById(conversationId);
                // JCLAW-273: skipUserAppend=true comes from runYieldResume — the
                // yield-resume announce was already persisted as a USER-role
                // Message before this call, so re-appending would duplicate the
                // row in both the chat scrollback and the LLM context.
                if (!skipUserAppend) {
                    sink.appendUserMessage(userMessage, attachments);
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
                var imageBearers = new ArrayList<VisionAudioAssembler.ImageBearer>();
                var videoBearers = new ArrayList<VisionAudioAssembler.VideoBearer>();
                var messages = MessageHydrator.buildMessages(sysPrompt, conv, audioBearers, imageBearers, videoBearers);

                // JCLAW-108: resolve the provider from the effective provider name
                // (conversation override when set, agent default otherwise), not
                // from agent.modelProvider directly. Downstream helpers that take
                // (agent, conv, provider) compute their own effective model id.
                var agentProvider = ProviderRegistry.get(ModelResolver.effectiveModelProvider(agent, conv));
                var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
                if (primary == null) {
                    var error = NO_LLM_PROVIDER_ERROR;
                    EventLogger.error("llm", agent.name, null, error);
                    sink.appendAssistantMessage(error, null);
                    return null;
                }
                var secondary = ProviderRegistry.getSecondary();

                // Conversation-aware overload: lazy-load MCP tool schemas
                // based on which servers the model has discovered via
                // list_mcp_tools. Native tools always ship.
                var tools = ToolRegistry.getToolDefsForAgent(agent, conv);

                EventLogger.info("llm", agent.name, conv.channelType,
                        "Calling %s / %s".formatted(primary.config().name(), ModelResolver.effectiveModelId(agent, conv)));

                return new PreparedData(messages, primary, secondary, tools, audioBearers, imageBearers, videoBearers);
            });

            if (prepared == null) {
                var error = NO_LLM_PROVIDER_ERROR;
                return new RunResult(error,
                        services.Tx.run(() -> ConversationService.findById(conversationId)));
            }

            // JCLAW-38: if the just-built context exceeds the compaction
            // budget, summarize older turns (LLM call, outside Tx) and
            // rebuild. trimToContextWindow below stays as a drop-oldest
            // fallback for when compaction is skipped (too few turns) or
            // fails.
            // JCLAW-465: content-aware compression of TOOL-role outputs, before
            // compaction so the budget check sees the smaller payload. No-op
            // unless chat.compression.enabled=true. When compaction fires it
            // rebuilds from originals; trimToContextWindow below stays the net.
            var compressedMessages = CompressionPipeline.compress(prepared.messages(), agent, conversation);
            var compactedMessages = CompactionGate.maybeCompactAndRebuild(
                    agent, conversationId, userMessage, null,
                    prepared.primary(), compressedMessages, prepared.tools());
            var finalMessages = ContextWindowManager.trimToContextWindow(compactedMessages, agent, conversation,
                    prepared.primary(), prepared.tools());
            // JCLAW-165: when the active model lacks supportsAudio, await
            // any in-flight transcription futures and rewrite the user
            // messages as text-with-transcript before the LLM call. The
            // audio-capable happy path is a no-op and pays zero added latency.
            var modelInfoForAudio = ModelResolver.resolveModelInfo(agent, conversation, prepared.primary()).orElse(null);
            var supportsAudioForCall = modelInfoForAudio != null && modelInfoForAudio.supportsAudio();
            finalMessages = VisionAudioAssembler.applyTranscriptsForCapability(finalMessages, prepared.audioBearers(), supportsAudioForCall);
            // JCLAW-215: when the active model lacks supportsVision, caption any
            // image attachments (outside Tx) and rewrite the user messages as
            // text-with-caption. supportsAudioForCall is threaded so a turn with
            // both an image and a downgraded voice note rebuilds correctly.
            var supportsVisionForCall = modelInfoForAudio != null && modelInfoForAudio.supportsVision();
            finalMessages = VisionAudioAssembler.applyCaptionsForCapability(finalMessages, prepared.imageBearers(), supportsVisionForCall, supportsAudioForCall);
            // JCLAW-224: route any video attachments through the dispatcher (native-video /
            // multi-image / text-summary) and splice the content parts in. supportsVision/Audio
            // are threaded so a co-attached downgraded image / voice note survives the rebuild.
            finalMessages = VisionAudioAssembler.applyVideoForCapability(finalMessages, prepared.videoBearers(), agent, supportsAudioForCall, supportsVisionForCall);
            prepared = new PreparedData(finalMessages, prepared.primary(), prepared.secondary(), prepared.tools(), prepared.audioBearers(), prepared.imageBearers(), prepared.videoBearers());
            trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);

            trace.mark(LatencyTrace.PROLOGUE_DONE);
            // LLM call loop — no transaction open, JDBC connection back in pool
            var outcome = ToolCallLoopRunner.callWithToolLoop(agent, conversation, conversationId,
                    prepared.messages(), prepared.tools(), prepared.primary(), prepared.secondary(),
                    prepared.audioBearers(), prepared.imageBearers(), sink, null);  // JCLAW-414: chat path is not task-cancellable
            var response = outcome.content();
            var truncated = outcome.truncated();
            trace.mark(LatencyTrace.STREAM_BODY_END);

            // JCLAW-273: yielded response — no final assistant message to
            // persist. The parent agent's turn ended cleanly after the
            // subagent_yield tool call; the resume re-invocation will
            // arrive from tools.SubagentSpawnTool#runAsyncAndAnnounce once
            // the child terminates. Return immediately so the caller sees
            // YIELDED_RESPONSE on the RunResult.
            if (YIELDED_RESPONSE.equals(response)) {
                EventLogger.info(EVT_CATEGORY_AGENT, agent.name, conversation.channelType,
                        "Parent turn suspended via subagent_yield");
                var updatedConv = services.Tx.run(() -> ConversationService.findById(conversationId));
                return new RunResult(response, updatedConv);
            }

            // Short persistence transaction: final assistant message.
            // Conversation may have been deleted between LLM call and persist
            // (loadtest cleanup, manual UI delete, etc.); ConversationSink
            // logs + skips internally rather than inserting a row with a
            // null FK.
            services.Tx.run(() ->
                sink.appendAssistantMessage(response, null, null, null, truncated));

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
     * @param agent          the executing agent
     * @param conversationId persisted Conversation id, or {@code null} when
     *                       the channel hasn't created one yet (the runner
     *                       creates one inside the virtual thread)
     * @param channelType    {@code "web"}, {@code "telegram"},
     *                       {@code "slack"}, {@code "whatsapp"}
     * @param peerId         channel-specific peer identifier (Telegram chat
     *                       id, Slack channel id, WhatsApp e.164, or
     *                       {@code null} for web)
     * @param userMessage    the user's input text
     * @param isCancelled    flag the transport flips on disconnect / Telegram
     *                       /cancel
     * @param cb             event callbacks for SSE / streaming transports
     * @param acceptedAtNs   {@code System.nanoTime()} of when the inbound
     *                       message entered the process. Web controllers
     *                       forward the Netty-set stamp so {@code queue_wait}
     *                       can be measured; Telegram polling, scheduled
     *                       tasks, and other channels without a pre-runner
     *                       timestamp pass {@code null}. A
     *                       {@link LatencyTrace} is always constructed
     *                       inside the virtual thread, so every channel
     *                       contributes to the performance histograms.
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
     * JCLAW-25 vision variant. Each attachment's staged file is moved into
     * the conversation's attachments directory and recorded as a
     * {@link models.MessageAttachment} row on the user message.
     *
     * @param agent          the executing agent
     * @param conversationId persisted Conversation id (see no-attachments
     *                       overload)
     * @param channelType    channel identifier
     * @param peerId         channel-specific peer identifier
     * @param userMessage    the user's input text
     * @param isCancelled    cancellation flag
     * @param cb             event callbacks
     * @param acceptedAtNs   {@code System.nanoTime()} at process entry
     * @param attachments    per-file metadata the frontend roundtripped
     *                       from the prior {@code /api/chat/upload}
     *                       response
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
            cb.onComplete().accept(QUEUED_MESSAGE_RESPONSE);
            return null;
        }

        // JCLAW-21: route the user-message persist through ConversationSink.
        // Local sink construction here keeps this method's signature
        // unchanged; streamLlmLoop builds its own ConversationSink from
        // the returned Conversation for the post-LLM writes.
        AgentExecutionSink sink = new ConversationSink(conversation);
        services.Tx.run(() -> sink.appendUserMessage(userMessage, attachments));

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

        var prepared = buildStreamingPrologue(agent, conversation, userMessage);

        var modelInfoForAudioStream = ModelResolver.resolveModelInfo(agent, conversation, primary).orElse(null);
        var supportsAudioForStream = modelInfoForAudioStream != null && modelInfoForAudioStream.supportsAudio();
        var supportsVisionForStream = modelInfoForAudioStream != null && modelInfoForAudioStream.supportsVision();
        var messages = applyAudioCapabilityRewrite(agent, conversation, userMessage, primary, prepared,
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
        if (accumulator.error != null) {
            cb.onError().accept(accumulator.error);
            return;
        }

        // Round 1 folded into turn-level usage. addRound also propagates
        // round-local reasoning/content timing into TurnUsage so the
        // turn-level reasoningDurationMs spans first-reasoning → first-content
        // across every round (matches the frontend's live measurement).
        turnUsage.addRound(accumulator);

        // Check for truncated response (max tokens hit mid-tool-call)
        if (TruncationDiagnostics.isTruncationFinish(accumulator.finishReason) && !accumulator.toolCalls.isEmpty()) {
            persistAndCompleteTruncatedToolCall(accumulator.content, agent, channelType, trace, sink, cb);
            return;
        }

        var post = runPostAccumulatorToolLoop(accumulator, agent, conversation, primary, channelType, messages, tools,
                cb, thinkingMode, isCancelled, trace, turnUsage, sink);

        if (CancellationManager.checkCancelled(isCancelled, agent, channelType, cb)) return;

        // JCLAW-273: parent agent yielded into an async subagent. No final
        // assistant message to persist or emit; the parent's logical turn
        // resumes later from tools.SubagentSpawnTool#runAsyncAndAnnounce
        // once the child terminates.
        if (YIELDED_RESPONSE.equals(post.content())) {
            handleStreamingYield(agent, channelType, trace, cb);
            return;
        }

        trace.mark(LatencyTrace.STREAM_BODY_END);
        finalizeStreamingTurn(post.content(), post.replyTruncated(), turnUsage, modelInfo, streamStartMs,
                agent, conversation, channelType, trace, sink, cb);
    }

    /**
     * Resolve the streaming LLM provider for an agent+conversation. Fires
     * {@code cb.onError} and returns {@code null} when no provider is configured.
     */
    private static LlmProvider resolveStreamingProvider(Agent agent, Conversation conversation,
                                                         String channelType, StreamingCallbacks cb) {
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
     * Run the prologue Tx: load disabled tools, fetch the managed conversation, assemble
     * the system prompt + compaction summary + parent-context, hydrate messages and
     * tool definitions. Fold everything into ONE transaction so nested Tx.run calls
     * inside helpers don't open additional connections.
     */
    private static PreparedPrologue buildStreamingPrologue(Agent agent, Conversation conversation,
                                                            String userMessage) {
        return services.Tx.run(() -> {
            var disabledTools = ToolRegistry.loadDisabledTools(agent);
            var convo = ConversationService.findById(conversation.id);
            var assembled0 = SystemPromptAssembler.assemble(agent, userMessage, disabledTools, convo.channelType);
            // JCLAW-38: re-inject latest compaction summary (if any)
            var sysPrompt = services.SessionCompactor.appendSummaryToPrompt(assembled0.systemPrompt(), convo);
            // JCLAW-268: re-inject spawn-time parent context for inherit-mode subagents.
            sysPrompt = services.SessionCompactor.appendParentContextToPrompt(sysPrompt, convo);
            var audioBearers = new ArrayList<VisionAudioAssembler.AudioBearer>();
            var imageBearers = new ArrayList<VisionAudioAssembler.ImageBearer>();
            var videoBearers = new ArrayList<VisionAudioAssembler.VideoBearer>();
            var msgs = MessageHydrator.buildMessages(sysPrompt, convo, audioBearers, imageBearers, videoBearers);
            // Conversation-aware overload: applies the loadtest-agent
            // short-circuit AND the lazy MCP discovery gate (only ship
            // schemas for servers the model has called list_mcp_tools on).
            var toolDefs = ToolRegistry.getToolDefsForAgent(agent, convo);
            return new PreparedPrologue(assembled0, msgs, toolDefs, disabledTools, audioBearers, imageBearers, videoBearers);
        });
    }

    /**
     * Compaction + context-window trim + audio-capability rewrite. JCLAW-165: when the
     * active model lacks {@code supportsAudio}, rewrite audio messages to
     * text-with-transcript before the LLM call (no-op on audio-capable models).
     */
    private static List<ChatMessage> applyAudioCapabilityRewrite(Agent agent, Conversation conversation,
                                                                  String userMessage, LlmProvider primary,
                                                                  PreparedPrologue prepared,
                                                                  boolean supportsAudioForStream,
                                                                  boolean supportsVisionForStream) {
        // JCLAW-38: if the just-built context exceeds the compaction budget,
        // summarize older turns (LLM call, outside Tx) and rebuild.
        // trimToContextWindow below stays as a drop-oldest fallback for
        // when compaction is skipped or fails.
        // JCLAW-465: same content-aware compression hook on the streaming path.
        var compressedMessages = CompressionPipeline.compress(prepared.messages(), agent, conversation);
        var compactedMessages = CompactionGate.maybeCompactAndRebuild(
                agent, conversation.id, userMessage, prepared.disabledTools(),
                primary, compressedMessages, prepared.tools());
        var trimmedMessages = ContextWindowManager.trimToContextWindow(compactedMessages, agent, conversation,
                primary, prepared.tools());
        var rewritten = VisionAudioAssembler.applyTranscriptsForCapability(trimmedMessages, prepared.audioBearers(),
                supportsAudioForStream);
        // JCLAW-215: caption image attachments for non-vision models, mirroring
        // the audio downgrade above.
        var captioned = VisionAudioAssembler.applyCaptionsForCapability(rewritten, prepared.imageBearers(),
                supportsVisionForStream, supportsAudioForStream);
        // JCLAW-224: route video attachments through the dispatcher on the streaming path too.
        return VisionAudioAssembler.applyVideoForCapability(captioned, prepared.videoBearers(), agent,
                supportsAudioForStream, supportsVisionForStream);
    }

    /**
     * Run the round-1 streaming call and retry once on transient HTTP 5xx errors.
     * Returns {@code null} when cancellation fired during either await.
     */
    @SuppressWarnings("java:S107") // Streaming first-round invocation needs the full call surface
    private static LlmProvider.StreamAccumulator streamFirstRoundWithRetry(
            LlmProvider primary, String effectiveModelIdForCall, List<ChatMessage> messages, List<ToolDef> tools,
            StreamingCallbacks cb, Integer maxTokens, String thinkingMode, String channelType,
            AtomicBoolean isCancelled, Agent agent) throws InterruptedException {
        var accumulator = primary.chatStreamAccumulate(
                effectiveModelIdForCall, messages, tools, cb.onToken(), cb.onReasoning(),
                maxTokens, thinkingMode, channelType);

        if (!CancellationManager.awaitAccumulatorOrCancel(accumulator, isCancelled, agent, channelType, cb)) return null;

        // Retry once on transient 5xx errors
        if (accumulator.error != null && accumulator.error.getMessage() != null
                && accumulator.error.getMessage().contains("HTTP 5")) {
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
            StreamingCallbacks cb, Integer maxTokens, String thinkingMode, String channelType,
            AtomicBoolean isCancelled, Agent agent, Conversation conversation, PreparedPrologue prepared,
            boolean supportsAudio) throws InterruptedException {
        var acc = streamFirstRoundWithRetry(primary, effectiveModelId, messages, tools, cb, maxTokens,
                thinkingMode, channelType, isCancelled, agent);
        if (acc == null) return null;

        if (prepared.audioBearers().isEmpty()) return new StreamRound1(acc, messages); // no audio → no log

        // Audio rode native (passthrough) and the model rejected the FORMAT before any token streamed:
        // await the transcript and re-stream as text.
        if (acc.error != null && supportsAudio && acc.content.isEmpty()
                && AudioRetryStrategy.isAudioFormatRejection(acc.error)) {
            var rewritten = VisionAudioAssembler.applyTranscriptsForCapability(messages, prepared.audioBearers(), false);
            if (!AudioRetryStrategy.anyTranscriptAvailable(prepared.audioBearers())) {
                AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary, "error",
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
                    retry.error == null ? "downgraded" : "error",
                    retry.error == null ? null : AudioRetryStrategy.shortErrorTag(retry.error), true);
            return new StreamRound1(retry, rewritten);
        }

        // No fallback fired — log the plain passthrough outcome (accepted / error). transcript_awaited
        // is false for a happy native passthrough, true when the rewrite already ran (text-only model).
        AudioRetryStrategy.logAudioPassthroughOutcome(agent, conversation, primary,
                acc.error == null ? "accepted" : "error",
                acc.error == null ? null : AudioRetryStrategy.shortErrorTag(acc.error), !supportsAudio);
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
                                                                        StreamingCallbacks cb, String thinkingMode,
                                                                        AtomicBoolean isCancelled, LatencyTrace trace,
                                                                        LlmProvider.TurnUsage turnUsage,
                                                                        AgentExecutionSink sink) {
        var content = accumulator.content;

        // JCLAW-291: detect the empty-toolCalls truncation case — a plain
        // assistant reply that hit max_tokens. Sibling to the tool-call
        // truncation guard in the caller; that one fires only when toolCalls is
        // non-empty (incomplete JSON args), this one fires when toolCalls
        // is empty (the model just ran out of output budget mid-reply).
        boolean replyTruncated = TruncationDiagnostics.isTruncationFinish(accumulator.finishReason)
                && accumulator.toolCalls.isEmpty();
        if (replyTruncated) {
            TruncationDiagnostics.logEmptyToolCallsTruncation("streamLlmLoop", agent, conversation, primary,
                    channelType, accumulator.finishReason, messages, tools);
        }

        // Handle tool calls if present. JCLAW-104: the image collector lives
        // at turn scope (not per recursion level) so a screenshot captured
        // mid-chain — say in round 1 — still reaches buildImagePrefix when
        // the final synthesis happens in round N.
        var turnImages = new ArrayList<String>();
        if (!accumulator.toolCalls.isEmpty()) {
            content = ToolCallLoopRunner.handleToolCallsStreaming(agent, conversation, conversation.id, messages, tools,
                    accumulator.toolCalls, content, primary, cb, thinkingMode, 0,
                    isCancelled, trace, turnUsage, turnImages, channelType, sink);
        }
        return new StreamingPostAccumulator(content, replyTruncated);
    }

    /**
     * JCLAW-273: parent agent yielded into an async subagent. Fire {@code cb.onComplete}
     * with empty content so transports release their per-turn resources cleanly.
     */
    private static void handleStreamingYield(Agent agent, String channelType, LatencyTrace trace,
                                              StreamingCallbacks cb) {
        EventLogger.info(EVT_CATEGORY_AGENT, agent.name, channelType,
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
                                               LatencyTrace trace, AgentExecutionSink sink, StreamingCallbacks cb) {
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
        services.Tx.run(() ->
            sink.appendAssistantMessage(finalContent, null, usageJson, finalReasoning, finalTruncated));
        utils.LatencyStats.record(channelType, "persist",
                (System.nanoTime() - persistStartNs) / 1_000_000L);
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
                                                            StreamingCallbacks cb) {
        EventLogger.warn("llm", agent.name, channelType,
                "Response truncated (finish_reason=length) with pending tool calls — skipping execution of incomplete tool arguments");
        var truncMsg = content.isEmpty()
                ? "I tried to use a tool but my response was too long and got cut off. Let me try a more concise approach."
                : content;
        cb.onToken().accept(truncMsg.equals(content) ? "" : "\n\n*[Response was truncated — retrying with a simpler approach]*");
        trace.mark(LatencyTrace.STREAM_BODY_END);
        var finalContent = truncMsg;
        long truncPersistStartNs = System.nanoTime();
        services.Tx.run(() -> sink.appendAssistantMessage(finalContent, null));
        utils.LatencyStats.record(channelType, "persist",
                (System.nanoTime() - truncPersistStartNs) / 1_000_000L);
        cb.onComplete().accept(finalContent);
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
     *
     * @param agent        the bound agent the caller already resolved
     * @param channelType  channel identifier
     * @param peerId       channel-specific peer id (per-user conversation key)
     * @param text         inbound message text
     * @param sendResponse callback to deliver the response (receives
     *                     {@code peerId} and response text)
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
     *
     * @param agent        the bound agent
     * @param channelType  channel identifier
     * @param peerId       channel-specific peer id
     * @param text         inbound message text
     * @param sinkFactory  factory that returns a streaming sink for the
     *                     given message id; called inside the streaming
     *                     virtual thread once the persisted message id is
     *                     known
     */
    public static void processInboundForAgentStreaming(
            Agent agent, String channelType, String peerId, String text,
            java.util.function.Function<Long, channels.ChannelStreamingSink> sinkFactory) {
        processInboundForAgentStreaming(agent, channelType, peerId, text, sinkFactory,
                java.util.List.of(), null);
    }

    /**
     * JCLAW-136 overload: accepts inbound file attachments (images, audio,
     * documents, video) alongside the text. The caller (webhook or polling
     * runner) has already resolved Telegram file_ids via Bot API getFile and
     * streamed the bytes into the agent's {@code attachments/staging}
     * directory, so each {@link services.AttachmentService.Input} points at
     * a real staged file the runner can finalize. Empty list is the
     * text-only path — same behavior as before.
     *
     * @param agent       the bound agent
     * @param channelType channel identifier
     * @param peerId      channel-specific peer id
     * @param text        inbound message text
     * @param sinkFactory factory that returns a streaming sink for the
     *                    given message id
     * @param attachments staged file metadata to finalize against the new
     *                    user message; empty list is the text-only path
     */
    public static void processInboundForAgentStreaming(
            Agent agent, String channelType, String peerId, String text,
            java.util.function.Function<Long, channels.ChannelStreamingSink> sinkFactory,
            java.util.List<services.AttachmentService.Input> attachments) {
        processInboundForAgentStreaming(agent, channelType, peerId, text, sinkFactory,
                attachments, null);
    }

    /**
     * Chat-type-aware variant: stamps the Telegram {@code chat.type} onto the
     * conversation at creation so {@link ConversationService#effectiveHistoryLimit}
     * (and any other chat-type-scoped behavior) can distinguish a plain DM from a
     * plain group. Only the two Telegram ingress call sites
     * ({@link controllers.WebhookTelegramController} and
     * {@link channels.TelegramPollingRunner}) pass a real value; every other
     * overload delegates with {@code chatType=null}, leaving the column null and
     * behavior unchanged. The chat type is stamped once at creation — an existing
     * conversation row is never re-stamped.
     *
     * @param agent       the bound agent
     * @param channelType channel identifier
     * @param peerId      channel-specific peer id
     * @param text        inbound message text
     * @param sinkFactory factory that returns a streaming sink for the given
     *                    message id
     * @param attachments staged file metadata to finalize; empty list is the
     *                    text-only path
     * @param chatType    Telegram {@code chat.type} ("private"/"group"/
     *                    "supergroup"), or null
     */
    public static void processInboundForAgentStreaming(
            Agent agent, String channelType, String peerId, String text,
            java.util.function.Function<Long, channels.ChannelStreamingSink> sinkFactory,
            java.util.List<services.AttachmentService.Input> attachments,
            String chatType) {
        // JCLAW-26: intercept slash commands before the LLM round. Reuse the
        // existing sink machinery to deliver the canned response — an unused
        // sink's seal() path falls through to the per-binding TelegramChannel's
        // sendTurn, which keeps the bot-token / chat-id routing owned by the caller.
        var slashCmd = slash.Commands.parse(text);
        if (slashCmd.isPresent()) {
            var cmd = slashCmd.get();
            Conversation current = cmd == slash.Commands.Command.NEW
                    ? null
                    : services.Tx.run(() -> ConversationService.findOrCreate(agent, channelType, peerId, chatType));
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
        // JCLAW-430: /start always introduces the bot via the LLM. Telegram
        // auto-sends /start on first open, and a user can re-invoke it anytime.
        // Open a FRESH conversation (so the intro isn't muddied by prior context)
        // and replace the bare "/start" with an explicit intro instruction — the
        // agent's identity/persona comes from its md-file system prompt, and we
        // hand it the slash-command list, so it produces a proper self-
        // introduction instead of improvising from "/start" alone. Replaces the
        // first-contact-only deterministic welcome (JCLAW-97).
        final boolean isStart = slash.Commands.isStart(text);
        final String turnText = isStart ? slash.Commands.startIntroPrompt() : text;
        var conversation = services.Tx.run(() -> isStart
                ? ConversationService.create(agent, channelType, peerId)
                : ConversationService.findOrCreate(agent, channelType, peerId, chatType));
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
                tc -> sink.toolProgress(tc.name()),  // onToolCall — Slack off-thread draft preview (JCLAW-346); default no-op elsewhere
                sink::seal,              // onComplete — final edit / planner fallback
                sink::errorFallback,     // onError — delete placeholder + send error
                sink::cancel);           // onCancel — quiesce typing heartbeat on /stop
        runStreaming(agent, conversation.id, channelType, peerId, turnText,
                isCancelled, cb, null, attachments);
    }

    /** Telegram {@code chat.type} for a one-on-one DM; everything else is a group context. */
    private static final String TELEGRAM_CHAT_TYPE_PRIVATE = "private";

    /**
     * JCLAW-370: compute the conversation peer key for an inbound Telegram
     * message. A DM keys off the binding owner ({@code ownerKey} — the
     * binding's {@code telegramUserId}, unchanged from today; in a private chat
     * {@code chat.id == user.id} so DMs are identical to the old behavior). A
     * group / supergroup keys off the chat id so every allowed member shares ONE
     * conversation per chat — owned by the binding's JClaw peer, not per member.
     * A forum-topic message gains a {@code ":topic:<threadId>"} suffix so each
     * topic is its own conversation within the chat. The topic is encoded in the
     * peerId string — no DB schema change ({@code ConversationService} already
     * keys on the {@code (agent, channelType, peerId)} tuple).
     *
     * @param ownerKey        the binding owner key (binding's telegramUserId)
     * @param chatType        Telegram {@code chat.type} string (nullable → treated as group)
     * @param chatId          Telegram chat id
     * @param messageThreadId forum-topic thread id, or null for a non-topic message
     * @return the composite conversation peer key
     */
    public static String telegramConversationPeerId(String ownerKey, String chatType, String chatId,
                                             Integer messageThreadId) {
        if (TELEGRAM_CHAT_TYPE_PRIVATE.equals(chatType)) {
            return ownerKey;
        }
        return messageThreadId != null
                ? chatId + ":topic:" + messageThreadId
                : chatId;
    }

    /**
     * JCLAW-370: prefix sender attribution onto a group message so the agent
     * knows WHO spoke in a shared group conversation. No-op for a DM
     * ({@code chat.type == "private"}) and for blank text — DMs stay unannotated
     * exactly as before. The prefix carries the sender's display name (falling
     * back to the id when no name is set) plus the numeric id, e.g.
     * {@code "[Ada Lovelace (id 42)]: hello"}.
     *
     * @param text            the raw inbound message text
     * @param chatType        Telegram {@code chat.type} string (nullable → treated as group)
     * @param fromDisplayName sender's display name, or null
     * @param fromId          sender's Telegram user id
     * @return the (possibly attributed) message text
     */
    public static String telegramSenderAttributed(String text, String chatType,
                                            String fromDisplayName, String fromId) {
        if (TELEGRAM_CHAT_TYPE_PRIVATE.equals(chatType) || text == null || text.isEmpty()) {
            return text;
        }
        var who = fromDisplayName != null && !fromDisplayName.isBlank() ? fromDisplayName : fromId;
        return "[%s (id %s)]: %s".formatted(who, fromId, text);
    }

    /**
     * Best-effort delivery of a response to an external channel. Web channel
     * responses are already persisted to the DB by {@link #run} — the user sees
     * them on next conversation load or refresh. External channels need explicit
     * dispatch because there is no persistent connection to push through.
     *
     * <p>JCLAW-141: resolves the right {@link channels.Channel} via
     * {@link channels.ChannelRegistry#forChannel} (which carries Telegram's
     * per-binding bot token, looked up from (agent, peerId)) and calls the generic
     * {@link channels.Channel#sendText(String, String, Agent)} — no channel-type
     * switch.
     * The agent-aware overload lets Telegram's planner resolve workspace file
     * links; the other channels ignore the agent. A null channel (unknown type,
     * or a Telegram conversation with no enabled binding) drops the dispatch,
     * matching the prior null-branch behavior.
     */
    // Package-private so QueueDrainOrchestrator (JCLAW-299) can dispatch
    // queued replies back through the originating channel.
    static void dispatchToChannel(Agent agent, String channelType, String peerId, String text) {
        if (peerId == null || text == null) return;
        try {
            var channel = services.Tx.run(() ->
                    channels.ChannelRegistry.forChannel(channelType, agent, peerId));
            if (channel == null) {
                if (ChannelType.TELEGRAM == ChannelType.fromValue(channelType)) {
                    EventLogger.warn("channel", agent != null ? agent.name : null, "telegram",
                            "No enabled binding for (agent=%s, userId=%s); dropping queued response"
                                    .formatted(agent != null ? agent.name : "?", peerId));
                }
                return;
            }
            channel.sendText(peerId, text, agent);
        } catch (Exception e) {
            EventLogger.error("channel", null, channelType,
                    "Failed to dispatch queued response to %s/%s: %s"
                            .formatted(channelType, peerId, e.getMessage()));
        }
    }


}
