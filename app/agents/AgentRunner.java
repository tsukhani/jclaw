package agents;

import channels.ChannelStreamingSink;
import com.google.gson.Gson;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ToolDef;
import llm.ProviderRegistry;
import memory.MemoryAutoCapture;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import services.AttachmentService;
import services.ConfigService;
import services.ConversationQueue;
import services.ConversationService;
import services.EventLogger;
import services.SubagentRegistry;
import services.TaskRunRegistry;
import services.Tx;
import utils.LatencyTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static utils.GsonHolder.INSTANCE;

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

    /**
     * EventLogger category for agent-lifecycle events (turn suspend, resume, yield). Also doubles as the NPE message for the {@code agent} parameter.
     * Package-private so {@link StreamingAgentRunner} (JCLAW-678) shares the identical category label.
     */
    static final String EVT_CATEGORY_AGENT = "agent";

    /**
     * Standard error surfaced when no LLM provider is configured for an agent — covers Settings.UI, EventLogger, and runtime exception messages.
     * Package-private so {@link AgentPromptPreparer} (JCLAW-678) shares the identical string on the sync prologue path.
     */
    static final String NO_LLM_PROVIDER_ERROR =
            "No LLM provider configured. Add provider config via Settings.";

    /**
     * Canned response returned when an inbound message can't acquire the conversation queue.
     * Package-private so {@link StreamingAgentRunner} (JCLAW-678) returns the identical canned text on the streaming queued path.
     */
    static final String QUEUED_MESSAGE_RESPONSE =
            "Your message has been queued and will be processed shortly.";

    // Package-private so ToolCallLoopRunner (JCLAW-299) can read the
    // operator-configurable per-turn round cap.
    static int maxToolRounds() {
        return ConfigService.getInt("chat.maxToolRounds", DEFAULT_MAX_TOOL_ROUNDS);
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
        String resultStructuredJson,
        // JCLAW-228/562: JSON array of tool-produced attachments (generate_image's image,
        // diarize_audio's voice clips) so the live SSE tool_call frame can render them inline;
        // null for every ordinary tool call.
        String generatedAttachmentsJson,
        // uuids of this call's persisted generated attachments. The web transport renders them
        // from generatedAttachmentsJson above; external channels (Telegram) that deliver out-of-
        // band resolve the persisted files by uuid and upload them. Empty for ordinary calls.
        List<String> generatedAttachmentUuids
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
        var runId = Tx.run(() -> {
            var run = (SubagentRun) SubagentRun.find(
                    "childConversation.id = ?1 AND status = ?2",
                    conversation.id, SubagentRun.Status.RUNNING).first();
            return run != null ? run.id : null;
        });
        if (runId != null) {
            // JCLAW-424: heartbeat the idle-timeout clock at this safe boundary
            // (before each LLM round / between tool calls) so an actively-working
            // child never trips the inactivity budget; only genuine silence does.
            SubagentRegistry.touch(runId);
            if (SubagentRegistry.isCancelled(runId)) {
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
        if (taskRunId != null && TaskRunRegistry.isCancelled(taskRunId)) {
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
                                 List<AttachmentService.Input> attachments) {
        var queueMsg = new ConversationQueue.QueuedMessage(
                userMessage, conversation.channelType, conversation.peerId, agent);
        if (!ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
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
        var queueMsg = new ConversationQueue.QueuedMessage(
                "", conversation.channelType, conversation.peerId, agent);
        if (!ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
            return new RunResult(QUEUED_MESSAGE_RESPONSE, conversation);
        }
        return runAfterAcquire(agent, conversation, "", null, true);
    }

    private static RunResult runAfterAcquire(Agent agent, Conversation conversation, String userMessage,
                                              List<AttachmentService.Input> attachments) {
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
        Objects.requireNonNull(agent, EVT_CATEGORY_AGENT);
        Objects.requireNonNull(userPrompt, "userPrompt");
        Objects.requireNonNull(sink, "sink");

        Tx.run(() -> sink.appendUserMessage(userPrompt, null));

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
        var prelude = Tx.run(() -> new Prelude(
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
            Tx.run(() -> sink.appendAssistantMessage(error, null));
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
        Tx.run(() -> sink.appendAssistantMessage(response, null, null, null, truncated));
        return outcome;
    }

    private static RunResult runAfterAcquire(Agent agent, Conversation conversation, String userMessage,
                                              List<AttachmentService.Input> attachments,
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
            var preparedOpt = AgentPromptPreparer.prepareSyncData(agent, userMessage, attachments,
                    skipUserAppend, conversationId, sink, trace);

            if (preparedOpt.isEmpty()) {
                var error = NO_LLM_PROVIDER_ERROR;
                return new RunResult(error,
                        Tx.run(() -> ConversationService.findById(conversationId)));
            }
            var prepared = preparedOpt.get();

            // Compression → compaction → context-window trim → audio/vision/video
            // capability rewrite, all outside the prologue Tx (LLM calls inside).
            prepared = AgentPromptPreparer.rewriteSyncMedia(prepared, agent, conversation, conversationId, userMessage);
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
                var updatedConv = Tx.run(() -> ConversationService.findById(conversationId));
                return new RunResult(response, updatedConv);
            }

            // Short persistence transaction: final assistant message.
            // Conversation may have been deleted between LLM call and persist
            // (loadtest cleanup, manual UI delete, etc.); ConversationSink
            // logs + skips internally rather than inserting a row with a
            // null FK.
            Tx.run(() ->
                sink.appendAssistantMessage(response, null, null, null, truncated));

            EventLogger.info("llm", agent.name, conversation.channelType,
                    "Response generated (%d chars%s)".formatted(response.length(),
                            truncated ? ", TRUNCATED" : ""));

            // JCLAW-39: async memory auto-capture for the completed turn. Runs on
            // its own virtual thread after the reply is persisted, so it never
            // blocks the response. No-op in test mode / when disabled.
            MemoryAutoCapture.captureAsync(agent, conversationId, userMessage, response);

            var updatedConversation = Tx.run(() -> ConversationService.findById(conversationId));
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
                                    List<AttachmentService.Input> attachments) {
        StreamingAgentRunner.runStreaming(agent, conversationId, channelType, peerId, userMessage,
                isCancelled, cb, acceptedAtNs, attachments);
    }

    /**
     * Agent id as a string for latency-metric tagging (JCLAW-515); null for a null/transient agent.
     * Package-private so the extracted {@link AgentPromptPreparer} / {@link StreamingAgentRunner} (JCLAW-678) share it.
     */
    static String agentIdOf(Agent agent) {
        return agent == null || agent.id == null ? null : agent.id.toString();
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
        ChannelInboundDispatcher.processWebhookMessage(channelType, peerId, text, sendResponse, sendNoRoute);
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
        ChannelInboundDispatcher.processInboundForAgent(agent, channelType, peerId, text, sendResponse);
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
            Function<Long, ChannelStreamingSink> sinkFactory) {
        ChannelInboundDispatcher.processInboundForAgentStreaming(agent, channelType, peerId, text, sinkFactory);
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
            Function<Long, ChannelStreamingSink> sinkFactory,
            List<AttachmentService.Input> attachments) {
        ChannelInboundDispatcher.processInboundForAgentStreaming(agent, channelType, peerId, text,
                sinkFactory, attachments);
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
            Function<Long, ChannelStreamingSink> sinkFactory,
            List<AttachmentService.Input> attachments,
            String chatType) {
        ChannelInboundDispatcher.processInboundForAgentStreaming(agent, channelType, peerId, text,
                sinkFactory, attachments, chatType);
    }

    /**
     * JCLAW-370: compute the conversation peer key for an inbound Telegram
     * message. Delegates to {@link TelegramMessageAddressing#telegramConversationPeerId}.
     *
     * @param ownerKey        the binding owner key (binding's telegramUserId)
     * @param chatType        Telegram {@code chat.type} string (nullable → treated as group)
     * @param chatId          Telegram chat id
     * @param messageThreadId forum-topic thread id, or null for a non-topic message
     * @return the composite conversation peer key
     */
    public static String telegramConversationPeerId(String ownerKey, String chatType, String chatId,
                                             Integer messageThreadId) {
        return TelegramMessageAddressing.telegramConversationPeerId(ownerKey, chatType, chatId, messageThreadId);
    }

    /**
     * JCLAW-370: prefix sender attribution onto a group message so the agent
     * knows WHO spoke in a shared group conversation. Delegates to
     * {@link TelegramMessageAddressing#telegramSenderAttributed}.
     *
     * @param text            the raw inbound message text
     * @param chatType        Telegram {@code chat.type} string (nullable → treated as group)
     * @param fromDisplayName sender's display name, or null
     * @param fromId          sender's Telegram user id
     * @return the (possibly attributed) message text
     */
    public static String telegramSenderAttributed(String text, String chatType,
                                            String fromDisplayName, String fromId) {
        return TelegramMessageAddressing.telegramSenderAttributed(text, chatType, fromDisplayName, fromId);
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
        ChannelInboundDispatcher.dispatchToChannel(agent, channelType, peerId, text);
    }


}
