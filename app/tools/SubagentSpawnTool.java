package tools;

import agents.AgentRunner;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentToolConfig;
import models.Conversation;
import models.SubagentRun;
import models.Message;
import models.MessageRole;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JCLAW-265: synchronous spawn of a child agent as a subagent.
 *
 * <p>The parent calls this tool with a {@code task} (the instruction the child
 * should carry out). The tool materializes a fresh child {@link Agent} and a
 * fresh child {@link Conversation} both wired to the parent via the JCLAW-264
 * self-FKs, records a {@link SubagentRun} row (RUNNING + startedAt), invokes
 * {@link AgentRunner#run} synchronously, then updates the audit row with the
 * terminal status, outcome, and ended_at. {@link EventLogger#recordSubagentSpawn}
 * fires immediately after the audit row is persisted (so the {@code run_id} in
 * the payload is real); {@link EventLogger#recordSubagentComplete} or
 * {@link EventLogger#recordSubagentError} fires on terminal outcomes.
 *
 * <p>Scope: the {@code mode} parameter selects the run shape (JCLAW-267).
 * Session mode (default) creates a fresh child Conversation and runs the
 * child there — its history persists independently and renders as a separate
 * row in the operator's sidebar. Inline mode reuses the parent Conversation
 * as the child's run target; AgentRunner persists the child's messages back
 * into the parent transcript stamped with the SubagentRun id so the chat UI
 * folds them into a nested-turn collapsible block. Both modes return the
 * child's final reply to the calling LLM identically.
 * The {@code context} parameter accepts {@code "fresh"} (default) or
 * {@code "inherit"} (JCLAW-268). Fresh gives the child only its configured
 * system prompt and an empty history. Inherit additionally (a) summarizes the
 * parent's recent turns via a synchronous LLM call (hard-capped at
 * {@link SessionCompactor#PARENT_CONTEXT_MAX_CHARS} characters), stamps the
 * result on the child Conversation, and re-injects it into the child's system
 * prompt each turn via
 * {@link SessionCompactor#appendParentContextToPrompt}, and (b) unions the
 * parent's enabled tools into the child Agent's tool config so the child can
 * use the same tool surface the parent had — at-spawn snapshot, matching the
 * per-spawn child Agent lifetime JCLAW-265 established.
 *
 * <p>If the inherit-mode summarization LLM call fails or returns blank, the
 * spawn degrades silently to fresh-mode behavior and emits a
 * {@code SUBAGENT_ERROR} event capturing the reason; the child still spawns,
 * still gets the tool-union grant, and still runs. The reverse (skip the
 * spawn outright) would be a worse failure mode for resilience.
 *
 * <p>The parent's tool registration provides the child's tool surface: the
 * child Agent row is cloned from the parent so it inherits the same provider,
 * model, and tool-config policies as the parent (operator-toggleable on the
 * child's own agent row going forward).
 *
 * <p><b>Timeout enforcement (best-effort).</b> The synchronous child run is
 * dispatched to a virtual thread and we await up to {@code runTimeoutSeconds}.
 * On {@link TimeoutException} we mark the SubagentRun {@code TIMEOUT} and emit
 * {@code SUBAGENT_TIMEOUT}; the child VT is interrupted but
 * {@code AgentRunner.run} has no first-class cancel hook today, so any
 * in-flight LLM HTTP call or tool execution may continue to completion in the
 * background. Tight cancellation semantics are JCLAW-270 (async path) territory.
 */
public class SubagentSpawnTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "subagent_spawn";

    static final String DEFAULT_MODE = "session";
    static final String MODE_SESSION = "session";
    static final String MODE_INLINE = "inline";
    private static final Set<String> ALLOWED_MODES = Set.of(MODE_SESSION, MODE_INLINE);
    static final String DEFAULT_CONTEXT = "fresh";
    static final String CONTEXT_FRESH = "fresh";
    static final String CONTEXT_INHERIT = "inherit";
    private static final Set<String> ALLOWED_CONTEXTS = Set.of(CONTEXT_FRESH, CONTEXT_INHERIT);

    /** Channel value stamped on subagent conversations. Not a real transport —
     *  {@link models.ChannelType#fromValue} returns null, so dispatchers no-op
     *  and the row exists purely as the child's audit-trail container. */
    public static final String SUBAGENT_CHANNEL = "subagent";

    private static final String FIELD_STATUS = "status";
    private static final String FIELD_LABEL = "label";

    /**
     * Default value for the per-spawn {@code runTimeoutSeconds} arg.
     * JCLAW-424: this is now an IDLE (inactivity) budget, not total wall-clock —
     * a run times out only after this many seconds with NO activity (no LLM
     * round / tool call); active work resets the clock. The absolute total
     * runtime is bounded separately by {@link #MAX_WALLCLOCK_KEY}.
     */
    static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * JCLAW-424: operator-controlled absolute wall-clock ceiling on a single
     * subagent run, independent of activity — the runaway guard the idle budget
     * (which an active child never trips) cannot provide. NOT settable by the
     * spawning LLM. {@code 0} disables the ceiling (idle budget only). Seeded by
     * {@link jobs.DefaultConfigJob}, editable in Settings &gt; Subagents.
     */
    public static final String MAX_WALLCLOCK_KEY = "subagent.maxWallClockSeconds";
    public static final int DEFAULT_MAX_WALLCLOCK_SECONDS = 1800;

    /** JCLAW-424: idle-await poll cadence — how often the await loop wakes to
     *  re-check the inactivity and ceiling budgets while the child runs. */
    private static final long IDLE_POLL_INTERVAL_MS = 1000;

    /** JCLAW-424: bounded grace after a timeout flips the cooperative-stop flag,
     *  giving the child's next checkpoint a chance to observe it and stop cleanly
     *  before the spawn path unregisters the run. */
    private static final long STOP_GRACE_MS = 3000;

    /**
     * JCLAW-270: hard cap on the reply-field length in async-spawn announce
     * messages. The full reply is always available by navigating to the
     * child conversation (the announce card surfaces a "View full" link);
     * the inline preview is bounded so a runaway child can't blow up the
     * parent's transcript or the chat UI's render budget.
     */
    static final int ANNOUNCE_REPLY_MAX_CHARS = 4000;

    /** {@link Message#messageKind} value stamped on async-spawn announce rows. */
    public static final String MESSAGE_KIND_ANNOUNCE = "subagent_announce";

    /**
     * JCLAW-266: recursion caps. Defaults match Personal Edition posture —
     * single level of delegation (top-level Agent spawns one tier of
     * subagents, no grandchildren) and a small fan-out so one parent can't
     * saturate the executor with concurrent children.
     *
     * <p>Depth = count of {@link Agent#parentAgent} links walking from the
     * spawning agent back to a root. {@code depthLimit=1} means
     * "only top-level agents (parentAgent == null) can spawn"; a child
     * would itself be at depth 1, hit the limit, and be refused.
     *
     * <p>Breadth = count of {@link SubagentRun} rows whose
     * {@code parentAgent} is the spawning agent and {@code status = RUNNING}.
     * Per direct parent — not the whole subtree.
     */
    public static final int DEFAULT_DEPTH_LIMIT = 1;
    public static final int DEFAULT_BREADTH_LIMIT = 5;
    /** Config-DB keys (seeded by {@link jobs.DefaultConfigJob}, editable from
     *  the Settings page's Subagents section). Names match the JCLAW-266 AC
     *  verbatim. */
    public static final String DEPTH_LIMIT_KEY = "subagent.maxDepth";
    public static final String BREADTH_LIMIT_KEY = "subagent.maxChildrenPerParent";

    /** Soft cap on how far we walk the parent chain when computing depth,
     *  so a cycle (shouldn't happen, but defense-in-depth) can't spin
     *  forever. Far above any plausible depth limit. */
    private static final int MAX_DEPTH_WALK = 64;

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "users"; }

    @Override
    public String shortDescription() {
        return "Delegate a task to a child subagent that runs in its own conversation and returns its final reply.";
    }

    @Override
    public List<agents.ToolAction> actions() {
        return List.of(
                new agents.ToolAction("spawn",
                        "Run a task on a child subagent synchronously and return its final reply"));
    }

    @Override
    public String description() {
        return """
                Spawn a child subagent to carry out a task on your behalf. The child runs \
                in its own fresh conversation and you receive its final assistant reply. \
                Fires ONCE per call — there is no schedule/interval parameter. For \
                anything recurring or scheduled ("every 30 seconds", "every minute", \
                "every day at 9am"), even if the operator describes it as a \
                "subagent", use the `task` tool's createTask action instead — that \
                is the abstraction that carries cron/interval and can invoke an agent \
                on each fire. \
                Use this when a task is well-scoped and benefits from an isolated context \
                (long research, separate tool surface, exploratory work). \
                Required: `task` (the instruction the child should execute). \
                Optional: `label` (short display name), `agentId` (numeric id of an existing \
                agent to run as the child; defaults to a fresh clone of the current agent), \
                `mode` ("session" — default — runs the child in a fresh sidebar conversation; \
                "inline" runs the child within your own conversation as a collapsible \
                nested-turn block), \
                `modelProvider` and `modelId` (override the child's model), \
                `context` ("fresh" — default — gives the child an empty history; "inherit" \
                injects a summary of your recent turns into the child's system prompt and \
                grants it the union of your enabled tools and its own), \
                `async` (false — default — blocks until the child finishes; true returns the \
                run id immediately and posts a completion card to your conversation when \
                the child terminates. Only compatible with mode="session"), \
                `runTimeoutSeconds` (idle budget — seconds of INACTIVITY before \
                the run is timed out; an actively-working child resets it, so \
                this need not cover total runtime, default 300).""";
    }

    @Override
    public String summary() {
        return "Spawn a child subagent to run a task and return its final reply.";
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put("task", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Instruction for the child subagent (required)"));
        props.put(FIELD_LABEL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Optional short display name for the spawn"));
        props.put("agentId", Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Optional id of an existing Agent row to run as the child; "
                        + "defaults to a fresh clone of the calling agent"));
        props.put("mode", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Spawn mode: \"session\" (default) creates a child Conversation that "
                        + "renders as a separate row in the operator's sidebar, or "
                        + "\"inline\" to run the child within the parent's conversation "
                        + "as a collapsible nested-turn block."));
        props.put("modelProvider", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Optional provider override for the child"));
        props.put("modelId", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Optional model id override for the child"));
        props.put("context", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Context inheritance mode: \"fresh\" (default) for an empty child history, "
                        + "or \"inherit\" to summarize the parent's recent turns into the "
                        + "child's system prompt and grant the child the union of the parent's "
                        + "enabled tools and its own."));
        props.put("runTimeoutSeconds", Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Idle budget: seconds of inactivity (no LLM round/tool call) before the run times out; "
                        + "active work resets it, so it need not cover total runtime (default 300)"));
        props.put("async", Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                SchemaKeys.DESCRIPTION,
                "When true (default false), return the child's run id immediately and "
                        + "post a structured completion card to your conversation when the "
                        + "child terminates. Only compatible with mode=\"session\"."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
                SchemaKeys.REQUIRED, List.of("task")
        );
    }

    /** Touches DB rows that other tool invocations might race; safer to keep
     *  serial within a single parent turn. */
    @Override
    public boolean parallelSafe() { return false; }

    /** Subagent-lifecycle group: shared with {@link SubagentYieldTool} so
     *  the {@link agents.ParallelToolExecutor} cannot dispatch yield's findById
     *  before spawn's SubagentRun INSERT commits. Without this, a model that
     *  emits both tool calls in one assistant message (guessing the runId
     *  from prior history) races on the row visibility — the symptom is
     *  subagent_yield returning "no SubagentRun found for runId X" for a
     *  freshly-spawned async run. */
    @Override
    public String serializationGroup() { return "subagent_lifecycle"; }

    @Override
    public String execute(String argsJson, Agent parentAgent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var parsed = parseSpawnArgs(args);
        if (parsed.error() != null) return parsed.error();

        // JCLAW-266: enforce recursion caps before touching the DB. Both checks
        // run inside a Tx so the ancestor walk and the RUNNING-row count see a
        // consistent snapshot. On refusal we emit SUBAGENT_LIMIT_EXCEEDED and
        // return a plain-text error — no SubagentRun row, no child Agent, no
        // child Conversation gets written.
        var refusal = Tx.run(() -> enforceRecursionLimits(parentAgent));
        if (refusal != null) {
            EventLogger.recordSubagentLimitExceeded(parentAgent.name, refusal);
            return "Subagent spawn refused: " + refusal;
        }

        // Resolve the parent conversation. AgentRunner does not pass it into
        // tools — by convention the most-recent conversation for this agent
        // is the one the tool call is being made from. Subagent spawning is
        // first introduced here so there's no incumbent pattern to match;
        // the lookup is wrapped in a Tx to dodge detached-entity issues on
        // VT-dispatched tool calls.
        var parentAgentId = parentAgent.id;
        var parentConv = Tx.run(() -> resolveParentConversation(parentAgentId));
        if (parentConv == null) {
            return "Error: Could not resolve a parent conversation for agent '%s'."
                    .formatted(parentAgent.name);
        }

        var summary = buildInheritSummary(parentAgent, parentConv.id, parsed.context());
        var bootstrap = bootstrapChildInTx(parentAgent, parentConv, parsed, summary);
        if (bootstrap.error() != null) return bootstrap.error();
        var childAgentId = bootstrap.childAgentId();
        var childConvId = bootstrap.childConvId();
        var childAgentName = bootstrap.childAgentName();

        // Step 3: insert the SubagentRun audit row (RUNNING + startedAt) in
        // its own short Tx so the row commits and is visible from any thread
        // we hand the run to.
        final var parentConvIdFinal = parentConv.id;
        var runId = insertSubagentRun(parentAgentId, childAgentId, parentConvIdFinal, childConvId,
                parsed.label());
        var runIdStr = String.valueOf(runId);
        EventLogger.recordSubagentSpawn(
                parentAgent.name, childAgentName,
                runIdStr, parsed.mode(), parsed.context());

        // JCLAW-268: surface inherit-mode summarization failure as a SUBAGENT_ERROR
        // immediately after spawn. The child still runs (degraded to fresh-mode
        // semantics for the parent-context blob; tool-union grant is also
        // skipped, see bootstrapChild). The spawn-time event makes the
        // degradation auditable; the terminal event below covers the run
        // itself once it finishes.
        if (summary.errorReason() != null) {
            EventLogger.recordSubagentError(
                    parentAgent.name, childAgentName,
                    runIdStr, parsed.mode(), parsed.context(), summary.errorReason());
        }

        final boolean inlineMode = MODE_INLINE.equals(parsed.mode());
        if (inlineMode) {
            writeInlineStartMarker(parentConvIdFinal, runId, parsed.label(), parsed.task());
        }

        // JCLAW-270: async branch — dispatch the run to a background VT and
        // return immediately with {run_id, conversation_id, status: RUNNING}.
        // The VT runs the same AgentRunner.run as the synchronous path and
        // posts a structured announce Message into the parent conversation
        // on terminal state (completion / failure / timeout). Async + inline
        // was already rejected up top so this branch can hard-assume session
        // mode (inlineMode == false, runIdForMarker == null).
        if (parsed.asyncRequested()) {
            return launchAsyncSpawn(runId, childAgentId, childConvId, parentConvIdFinal,
                    parentAgent.name, parsed, runIdStr);
        }

        // Step 4: synchronous AgentRunner.run on a VT so we can enforce the
        // wall-clock budget via Future.get(timeout).
        var runOutcome = runChildSynchronously(runId, childAgentId, childConvId,
                parsed.task(), parsed.timeoutSeconds(), inlineMode);

        // Step 5: write the terminal SubagentRun update in its own short Tx.
        if (!runOutcome.killedByOperator()) {
            persistTerminalRun(runId, runOutcome.terminalStatus(), runOutcome.terminalOutcome());
        }

        // JCLAW-267: inline-mode boundary-end marker. Skipped on kill (see
        // writeInlineEndMarker javadoc for the double-render rationale).
        if (inlineMode && !runOutcome.killedByOperator()) {
            writeInlineEndMarker(parentConvIdFinal, runId, runOutcome.terminalStatus(),
                    runOutcome.reply(), runOutcome.replyTruncated());
        }

        // Step 6: emit the terminal lifecycle event. JCLAW-291: kill path
        // already emitted SUBAGENT_KILL — don't duplicate as SUBAGENT_ERROR.
        if (!runOutcome.killedByOperator()) {
            emitTerminalEvent(parentAgent.name, childAgentName, runIdStr,
                    parsed.mode(), parsed.context(),
                    runOutcome.terminalStatus(), runOutcome.errorReason());
        }

        // Tool return — the LLM will see this JSON string.
        var payload = new LinkedHashMap<String, Object>();
        payload.put("run_id", runIdStr);
        payload.put("conversation_id", String.valueOf(childConvId));
        payload.put("reply", runOutcome.reply());
        payload.put(FIELD_STATUS, runOutcome.terminalStatus().name());
        // JCLAW-291: hint to the parent LLM that the child's reply was cut
        // off — useful when the parent decides whether to re-summarize or
        // to surface the truncation to the user.
        if (runOutcome.replyTruncated()) payload.put("truncated", Boolean.TRUE);
        return utils.GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    /** Parsed-args bundle for the spawn flow. {@code error} non-null
     *  short-circuits execute. */
    private record SpawnArgs(
            String error,
            String task, String label, Long requestedAgentId,
            String modelProvider, String modelId,
            String mode, String context, int timeoutSeconds, boolean asyncRequested) {
        static SpawnArgs fail(String msg) {
            return new SpawnArgs(msg, null, null, null, null, null, null, null, 0, false);
        }
    }

    /** Result of {@link #buildInheritSummary}: either the populated summary
     *  text (or null when fresh / nothing to summarize) plus an optional
     *  failure reason that maps to a deferred SUBAGENT_ERROR event. */
    private record InheritSummary(String text, String errorReason) {
        static final InheritSummary NONE = new InheritSummary(null, null);
    }

    /** Result of the synchronous child run + idle await. Public for
     *  {@code SubagentSpawnToolTest} (default package), which inspects the
     *  terminal status from {@link #awaitFuture}. */
    public record SyncRunOutcome(
            String reply, SubagentRun.Status terminalStatus,
            String terminalOutcome, String errorReason,
            boolean killedByOperator, boolean replyTruncated) {}

    private static SpawnArgs parseSpawnArgs(JsonObject args) {
        var task = optString(args, "task");
        if (task == null || task.isBlank()) {
            return SpawnArgs.fail("Error: 'task' is required.");
        }
        var label = optString(args, FIELD_LABEL);
        var requestedAgentId = optLong(args, "agentId");
        var modelProviderOverride = optString(args, "modelProvider");
        var modelIdOverride = optString(args, "modelId");
        var timeoutSeconds = optInt(args, "runTimeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        // JCLAW-267: mode parameter — "session" (default) materializes a fresh
        // child Conversation; "inline" runs the child in the parent's
        // Conversation with messages tagged so the chat UI folds them.
        var requestedMode = optString(args, "mode");
        var mode = requestedMode == null || requestedMode.isBlank()
                ? DEFAULT_MODE
                : requestedMode.toLowerCase();
        if (!ALLOWED_MODES.contains(mode)) {
            return SpawnArgs.fail("Error: 'mode' must be one of " + ALLOWED_MODES + " (got '" + requestedMode + "').");
        }
        // JCLAW-268: context parameter — "fresh" (default) is the JCLAW-265
        // behavior; "inherit" summarizes the parent's recent turns and unions
        // tool grants. Validate strictly so an LLM typo produces a clear
        // error rather than silently degrading.
        var requestedContext = optString(args, "context");
        var context = requestedContext == null || requestedContext.isBlank()
                ? DEFAULT_CONTEXT
                : requestedContext.toLowerCase();
        if (!ALLOWED_CONTEXTS.contains(context)) {
            return SpawnArgs.fail("Error: 'context' must be one of " + ALLOWED_CONTEXTS + " (got '" + requestedContext + "').");
        }

        // JCLAW-270: async parameter — false (default) preserves the synchronous
        // JCLAW-265 flow; true dispatches the child run to a background VT and
        // returns the run id immediately. Async + inline is rejected because
        // inline mode embeds the child's messages directly into the parent
        // transcript; returning control to the LLM before the child finishes
        // would leave a half-written nested block dangling. The completion-card
        // post-flow (announce Message into the parent conversation) is the
        // async equivalent of inline's inline-rendering — they're alternatives
        // for surfacing child output, not complements.
        var asyncRequested = optBool(args, "async");
        if (asyncRequested && MODE_INLINE.equals(mode)) {
            return SpawnArgs.fail("Error: 'async' is only compatible with mode=\"session\" (inline mode embeds child messages directly into the parent transcript, which has no meaningful semantics before the child finishes).");
        }
        // JCLAW-494: async subagents have no resume path inside a scheduled task
        // fire. The yield/resume model (JCLAW-273) re-invokes the parent via a
        // persisted conversation + channel announce, but a task fire runs on a
        // transient stub conversation with a one-shot delivery spec — a yield
        // would deliver the __JCLAW_273_YIELDED__ sentinel and strand the child's
        // result in an orphan conversation. Force synchronous spawns in tasks.
        if (asyncRequested && ToolContext.taskRunId() != null) {
            return SpawnArgs.fail("Error: 'async' subagents aren't supported inside a scheduled task run — a task fire has no conversation to resume into. Spawn the subagent synchronously (omit 'async' or set async=false) so its result is returned inline and feeds the task's output.");
        }

        return new SpawnArgs(null, task, label, requestedAgentId,
                modelProviderOverride, modelIdOverride,
                mode, context, timeoutSeconds, asyncRequested);
    }

    /**
     * JCLAW-268: inherit-mode pre-step. Snapshot the parent's recent messages
     * inside a short Tx, then call the LLM synchronously to produce the
     * summary outside of any Tx so the round-trip doesn't hold a DB
     * connection. On failure we degrade to fresh and surface the reason as a
     * deferred SUBAGENT_ERROR. Fresh-mode requests return {@link
     * InheritSummary#NONE} unconditionally.
     */
    private static InheritSummary buildInheritSummary(Agent parentAgent, Long parentConvId,
                                                       String context) {
        if (!CONTEXT_INHERIT.equals(context)) return InheritSummary.NONE;
        try {
            var text = buildParentContextSummary(parentAgent, parentConvId);
            return new InheritSummary(text, null);
        } catch (Exception e) {
            return new InheritSummary(null,
                    "Parent-context summarization failed: " + e.getMessage());
        }
    }

    /**
     * Step 1+2 wrapper. Materializes child Agent + Conversation in one short
     * Tx so both rows commit before the SubagentRun row is opened.
     */
    private static Bootstrap bootstrapChildInTx(Agent parentAgent, Conversation parentConv,
                                                SpawnArgs parsed, InheritSummary summary) {
        final boolean inheritRequested = CONTEXT_INHERIT.equals(parsed.context());
        final boolean applyInheritGrants = inheritRequested && summary.text() != null;
        final boolean inlineMode = MODE_INLINE.equals(parsed.mode());
        return Tx.run(() -> bootstrapChild(
                parentAgent, parentConv, parsed.requestedAgentId(),
                parsed.label(), parsed.modelProvider(), parsed.modelId(),
                applyInheritGrants, summary.text(), inlineMode));
    }

    /** Insert the SubagentRun audit row in its own short Tx and return the
     *  generated id. JCLAW-326: persist the spawn-time {@code label} on the
     *  row so {@code conversation_list} can filter / display without re-parsing
     *  per-run announce-message metadata JSON. */
    private static Long insertSubagentRun(Long parentAgentId, Long childAgentId,
                                           Long parentConvId, Long childConvId, String label) {
        return Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = Agent.findById(parentAgentId);
            run.childAgent = Agent.findById(childAgentId);
            run.parentConversation = Conversation.findById(parentConvId);
            run.childConversation = Conversation.findById(childConvId);
            run.label = label != null && !label.isBlank() ? label : null;
            // status defaults to RUNNING, startedAt populated by @PrePersist.
            run.save();
            return run.id;
        });
    }

    /**
     * JCLAW-267: inline-mode boundary-start marker. Written into the parent
     * Conversation BEFORE the child reasons so the chat UI's collapsible
     * block can fold from this marker forward. The marker is an
     * assistant-role row carrying the task instruction and stamped with the
     * SubagentRun id so it groups with the child's own messages. The start
     * marker's content becomes the header label on the collapsed block.
     */
    private static void writeInlineStartMarker(Long parentConvId, Long runId,
                                                String label, String task) {
        Tx.run(() -> {
            var conv = Conversation.<Conversation>findById(parentConvId);
            ConversationService.withSubagentRunIdMarker(runId, () -> {
                var startContent = "Spawning subagent: "
                        + (label != null && !label.isBlank() ? label + " — " : "")
                        + task;
                ConversationService.appendAssistantMessage(conv, startContent, null);
                return null;
            });
        });
    }

    /** Launch the async-spawn background VT and return the immediate JSON
     *  acknowledgement payload. */
    private static String launchAsyncSpawn(Long runId, Long childAgentId, Long childConvId,
                                            Long parentConvId, String parentAgentName,
                                            SpawnArgs parsed, String runIdStr) {
        Thread.ofVirtual().name("subagent-async-" + runId).start(() ->
                runAsyncAndAnnounce(runId, childAgentId, childConvId, parentConvId,
                        parentAgentName, parsed.mode(), parsed.context(), parsed.label(),
                        parsed.timeoutSeconds(), parsed.task()));
        var asyncPayload = new LinkedHashMap<String, Object>();
        asyncPayload.put("run_id", runIdStr);
        asyncPayload.put("conversation_id", String.valueOf(childConvId));
        asyncPayload.put(FIELD_STATUS, SubagentRun.Status.RUNNING.name());
        return utils.GsonHolder.INSTANCE.toJson(asyncPayload, Map.class);
    }

    /**
     * Step 4: dispatch the child run to a virtual thread, enforce the
     * wall-clock budget via Future.get(timeout), and translate the await
     * outcome into a {@link SyncRunOutcome}. The child Agent + Conversation
     * are re-fetched inside the VT so they're managed in a fresh persistence
     * context.
     *
     * <p>JCLAW-267: inline mode wraps the runner in
     * {@link ConversationService#withSubagentRunIdMarker} so every Message
     * AgentRunner persists during the child run carries the SubagentRun id
     * for the chat UI's nested-turn folding. Session mode passes the null
     * marker through.
     *
     * <p>JCLAW-291: cooperative-cancel registration via
     * {@link services.SubagentRegistry}. We deliberately do NOT capture the
     * carrier Thread — see services.SubagentRegistry's class comment for
     * the H2 FileChannel post-mortem.
     */
    @SuppressWarnings("java:S1181")
    private static SyncRunOutcome runChildSynchronously(Long runId, Long childAgentId,
                                                        Long childConvId, String task,
                                                        int timeoutSeconds, boolean inlineMode) {
        final Long runIdForMarker = inlineMode ? runId : null;
        var future = new java.util.concurrent.CompletableFuture<AgentRunner.RunResult>();
        services.SubagentRegistry.register(runId, future);
        Thread.ofVirtual().name("subagent-" + runId).start(() -> {
            try {
                var childAgent = Tx.run(() -> (Agent) Agent.findById(childAgentId));
                var childConv = Tx.run(() -> (Conversation) Conversation.findById(childConvId));
                if (childAgent == null || childConv == null) {
                    throw new IllegalStateException(
                            "Subagent rows vanished before AgentRunner.run");
                }
                AgentRunner.RunResult result;
                if (runIdForMarker == null) {
                    result = AgentRunner.run(childAgent, childConv, task);
                } else {
                    // JCLAW-267: inline mode runs in the parent Conversation,
                    // whose queue the parent already owns. Use runWithOwnedQueue
                    // to bypass the redundant tryAcquire (which would queue the
                    // child's "user message" behind the parent's still-running
                    // turn). The ThreadLocal marker stamps every Message
                    // AgentRunner persists during the child run.
                    result = ConversationService.withSubagentRunIdMarker(runIdForMarker,
                            () -> AgentRunner.runWithOwnedQueue(childAgent, childConv, task));
                }
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            int ceilingSeconds = ConfigService.getInt(MAX_WALLCLOCK_KEY, DEFAULT_MAX_WALLCLOCK_SECONDS);
            return awaitFuture(future, timeoutSeconds, ceilingSeconds, runId);
        } finally {
            services.SubagentRegistry.unregister(runId);
        }
    }

    /**
     * JCLAW-424: await the child future on an IDLE-timeout model. The future is
     * polled every {@link #IDLE_POLL_INTERVAL_MS}; the run times out only when
     * it has been INACTIVE (no {@link services.SubagentRegistry#touch}) for
     * longer than {@code idleBudgetSeconds}, or when its total runtime exceeds
     * the operator-configured {@link #MAX_WALLCLOCK_KEY} ceiling. An actively-
     * working child resets the idle clock at every LLM round / tool call (see
     * {@link agents.AgentRunner#checkSubagentCancel}) and so never trips the
     * idle budget — fixing the prior failure mode where a child still producing
     * output (e.g. a long report-generation turn) was killed at a fixed
     * wall-clock deadline. Every other terminal path (success, kill,
     * yield-watchdog timeout, runner exception) is translated to a uniform
     * {@link SyncRunOutcome} exactly as before.
     *
     * <p>Public for {@code SubagentSpawnToolTest} (default package).
     */
    public static SyncRunOutcome awaitFuture(
            java.util.concurrent.CompletableFuture<AgentRunner.RunResult> future,
            int idleBudgetSeconds, int ceilingSeconds, Long runId) {
        long idleBudgetNanos = TimeUnit.SECONDS.toNanos(Math.max(1, idleBudgetSeconds));
        long ceilingNanos = ceilingSeconds <= 0 ? Long.MAX_VALUE : TimeUnit.SECONDS.toNanos(ceilingSeconds);
        long startNanos = System.nanoTime();
        while (true) {
            try {
                var result = future.get(IDLE_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                var reply = result == null ? "" : result.response();
                var truncated = result != null && result.truncated();
                return new SyncRunOutcome(reply, SubagentRun.Status.COMPLETED,
                        reply, null, false, truncated);
            } catch (TimeoutException _) {
                // Poll tick — the child is still running. Check the two budgets.
                long idleNanos = services.SubagentRegistry.nanosSinceActivity(runId);
                if (idleNanos < 0) idleNanos = System.nanoTime() - startNanos; // unregistered fallback
                if (idleNanos > idleBudgetNanos) {
                    return stopChildOnTimeout(future, runId,
                            "Subagent run exceeded its %d-second idle budget (no activity)".formatted(idleBudgetSeconds));
                }
                if (System.nanoTime() - startNanos > ceilingNanos) {
                    return stopChildOnTimeout(future, runId,
                            "Subagent run exceeded the absolute %d-second ceiling".formatted(ceilingSeconds));
                }
                // budgets intact — keep polling
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                var reason = "Parent thread interrupted while awaiting subagent";
                return new SyncRunOutcome("", SubagentRun.Status.FAILED, reason, reason, false, false);
            } catch (java.util.concurrent.CancellationException _) {
                // JCLAW-291: kill primitive cancelled our Future. Registry already KILLED.
                return new SyncRunOutcome("", SubagentRun.Status.KILLED,
                        "Killed by operator", null, true, false);
            } catch (ExecutionException ee) {
                return fromExecutionException(ee);
            }
        }
    }

    /**
     * JCLAW-424: on idle/ceiling timeout, flip the cooperative-stop flag and
     * give the child a bounded grace ({@link #STOP_GRACE_MS}) to observe it at
     * its next checkpoint and unwind cleanly — so the absolute ceiling actually
     * halts a runaway rather than orphaning its VT — then return the TIMEOUT
     * outcome.
     */
    private static SyncRunOutcome stopChildOnTimeout(
            java.util.concurrent.CompletableFuture<AgentRunner.RunResult> future, Long runId, String reason) {
        services.SubagentRegistry.requestStop(runId);
        long graceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MS);
        while (!future.isDone() && System.nanoTime() < graceDeadline) {
            try {
                future.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException _) {
                // still unwinding — keep waiting within grace
            } catch (InterruptedException _) {
                // Restore the interrupt flag and stop waiting — don't swallow it
                // (java:S2142); we then finalize the TIMEOUT outcome below.
                Thread.currentThread().interrupt();
                break;
            } catch (Exception _) {
                break; // child stopped (RunCancelled / cancellation / completion)
            }
        }
        future.cancel(false);
        // JCLAW-424 (AC5): surface the child's partial output so the parent can
        // salvage progress rather than receiving an empty reply. The audit row's
        // outcome still records the timeout reason (terminalOutcome == reason).
        var partial = capturePartialReply(runId);
        return new SyncRunOutcome(partial, SubagentRun.Status.TIMEOUT, reason, reason, false, false);
    }

    /**
     * JCLAW-424 (AC5): the child's most recent assistant message, captured on a
     * timeout so the parent can salvage partial work. Empty when the child timed
     * out before producing any response, or on any lookup error (defensive — a
     * partial-capture failure must never mask the timeout outcome itself).
     */
    @SuppressWarnings("java:S1181")
    private static String capturePartialReply(Long runId) {
        if (runId == null) return "";
        try {
            return Tx.run(() -> {
                var run = (SubagentRun) SubagentRun.findById(runId);
                if (run == null || run.childConversation == null) return "";
                models.Message last = models.Message.find(
                        "conversation.id = ?1 and role = ?2 order by createdAt desc, id desc",
                        run.childConversation.id, models.MessageRole.ASSISTANT.value).first();
                return last != null && last.content != null ? last.content : "";
            });
        } catch (Throwable t) {
            EventLogger.warn(SUBAGENT_CHANNEL,
                    "Failed to capture partial reply for run " + runId + ": " + t.getMessage());
            return "";
        }
    }

    /**
     * Translate an {@link ExecutionException} from the child future into a
     * {@link SyncRunOutcome}, preserving the JCLAW-291 (kill) and JCLAW-326
     * (yield-watchdog timeout) terminal semantics.
     */
    private static SyncRunOutcome fromExecutionException(ExecutionException ee) {
        var cause = ee.getCause() != null ? ee.getCause() : ee;
        if (cause instanceof agents.RunCancelledException) {
            // JCLAW-291: runner observed the cancel flag at a checkpoint and threw.
            return new SyncRunOutcome("", SubagentRun.Status.KILLED,
                    "Killed by operator", null, true, false);
        }
        if (cause instanceof TimeoutException) {
            // JCLAW-326: yield watchdog completed the future exceptionally.
            var reason = cause.getMessage() != null ? cause.getMessage() : "yield timeout";
            return new SyncRunOutcome("", SubagentRun.Status.TIMEOUT, reason, reason, false, false);
        }
        var reason = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return new SyncRunOutcome("", SubagentRun.Status.FAILED, reason, reason, false, false);
    }

    /**
     * Step 5: write the terminal SubagentRun update in its own short Tx.
     * JCLAW-271 / JCLAW-291: belt-and-suspenders against a race where the
     * kill flipped the row but our future.get returned a non-cancel
     * exception path first — re-check the row's status in DB before
     * overwriting.
     */
    private static void persistTerminalRun(Long runId, SubagentRun.Status status, String outcome) {
        Tx.run(() -> {
            var fresh = (SubagentRun) SubagentRun.findById(runId);
            if (fresh != null && fresh.status != SubagentRun.Status.KILLED) {
                fresh.status = status;
                fresh.endedAt = Instant.now();
                fresh.outcome = outcome;
                fresh.save();
            }
        });
    }

    /**
     * JCLAW-267: inline-mode boundary-end marker. Written into the parent
     * Conversation AFTER the child run terminates so the chat UI's
     * collapsible block has a clear end. The marker carries the terminal
     * status in its content so the collapsed header can render
     * "Completed / Failed / Timed out" without a separate join to
     * SubagentRun.
     *
     * <p>JCLAW-291: when killed by operator, the kill confirmation in the
     * operator's slash-command response is the user-facing signal; the
     * caller skips this writer so a kill doesn't double-render as both
     * "Killed by operator" and a synthesized "Subagent killed" line.
     */
    private static void writeInlineEndMarker(Long parentConvId, Long runId,
                                              SubagentRun.Status status, String reply,
                                              boolean replyTruncated) {
        Tx.run(() -> {
            var conv = Conversation.<Conversation>findById(parentConvId);
            ConversationService.withSubagentRunIdMarker(runId, () -> {
                var endContent = "Subagent " + status.name().toLowerCase()
                        + (reply != null && !reply.isBlank() ? ": " + reply : "");
                // JCLAW-291: stamp truncated on the inline run's terminal
                // marker so the chat UI surfaces the marker on the inline
                // subagent block's last assistant row.
                ConversationService.appendAssistantMessage(conv, endContent, null, null, null,
                        replyTruncated);
                return null;
            });
        });
    }

    private static void emitTerminalEvent(String parentAgentName, String childName,
                                           String runIdStr, String mode, String context,
                                           SubagentRun.Status status, String errorReason) {
        switch (status) {
            case COMPLETED -> EventLogger.recordSubagentComplete(
                    parentAgentName, childName, runIdStr, mode, context, "ok");
            case TIMEOUT -> EventLogger.recordSubagentTimeout(parentAgentName, runIdStr);
            default -> EventLogger.recordSubagentError(
                    parentAgentName, childName, runIdStr,
                    mode, context, errorReason);
        }
    }

    // ----- internals -----

    /** Result of bootstrapping the child rows. {@code error} non-null means the
     *  caller should bail and surface the message verbatim. */
    private record Bootstrap(Long childAgentId, Long childConvId, String childAgentName, String error) {
        static Bootstrap ok(Long agentId, Long convId, String agentName) {
            return new Bootstrap(agentId, convId, agentName, null);
        }
        static Bootstrap fail(String msg) { return new Bootstrap(null, null, null, msg); }
    }

    /**
     * Returns a refusal reason string when the spawn would violate either
     * recursion cap, or {@code null} when the spawn may proceed. Must be
     * called inside a {@link Tx#run} block: it touches the persisted
     * {@code parentAgent} chain and queries {@link SubagentRun}.
     *
     * <p>Race note: two concurrent spawns from the same parent could both
     * see "N-1 RUNNING" and both proceed, transiently exceeding the breadth
     * cap by one. Personal Edition rarely runs into this (the parent LLM
     * issues tool calls sequentially within a turn); accepted as an
     * advisory limit for now. A {@code SELECT ... FOR UPDATE} on the parent
     * Agent row would close the window if/when it matters.
     */
    static String enforceRecursionLimits(Agent parentAgent) {
        // Read from the runtime Config table so the Settings page can edit
        // these without a restart. ConfigService.getInt falls back to the
        // hard-coded default when the row is absent or unparseable; values
        // <= 0 are also coerced to the default so an operator typo can't
        // silently disable the cap.
        var depthLimit = readPositiveIntConfig(DEPTH_LIMIT_KEY, DEFAULT_DEPTH_LIMIT);
        var breadthLimit = readPositiveIntConfig(BREADTH_LIMIT_KEY, DEFAULT_BREADTH_LIMIT);

        var currentDepth = depthOf(parentAgent);
        // currentDepth is the spawning agent's depth. Spawning would create
        // a child at depth currentDepth+1; refuse when currentDepth+1 exceeds
        // the limit, i.e. currentDepth >= limit.
        if (currentDepth >= depthLimit) {
            return "depth limit %d exceeded (current depth: %d)."
                    .formatted(depthLimit, currentDepth);
        }

        // Re-fetch the parent inside this tx for a managed reference. The
        // count query takes the managed instance to keep Hibernate happy.
        var managed = (Agent) Agent.findById(parentAgent.id);
        if (managed == null) {
            // The spawning agent vanished between the controller and here.
            // Surface a clear refusal rather than spinning into bootstrap.
            return "parent agent %d not found.".formatted(parentAgent.id);
        }
        long running = SubagentRun.count(
                "parentAgent = ?1 AND status = ?2",
                managed, SubagentRun.Status.RUNNING);
        if (running >= breadthLimit) {
            return "breadth limit %d exceeded (running children: %d).".formatted(
                    breadthLimit, running);
        }
        return null;
    }

    /** Count of parentAgent links from {@code start} back to a root.
     *  Capped by {@link #MAX_DEPTH_WALK} as a cycle guard. */
    private static int depthOf(Agent start) {
        int depth = 0;
        var cursor = start != null ? start.parentAgent : null;
        while (cursor != null && depth < MAX_DEPTH_WALK) {
            depth++;
            cursor = cursor.parentAgent;
        }
        return depth;
    }

    private static int readPositiveIntConfig(String key, int fallback) {
        int n = ConfigService.getInt(key, fallback);
        return n > 0 ? n : fallback;
    }

    private static Bootstrap bootstrapChild(Agent parentAgent, Conversation parentConv,
                                            Long requestedAgentId,
                                            String label,
                                            String modelProviderOverride,
                                            String modelIdOverride,
                                            boolean applyInheritGrants,
                                            String parentContextSummary,
                                            boolean inlineMode) {
        var resolved = resolveChildAgent(parentAgent, requestedAgentId, label);
        if (resolved.error() != null) return Bootstrap.fail(resolved.error());
        var childAgent = resolved.agent();

        // JCLAW-495: a freshly-cloned subagent is a delegate of its parent and
        // must inherit the parent's MCP server grants. MCP grouped tools are
        // default-disabled for non-main agents (ToolRegistry#addMcpDefaultDisabled)
        // and a new subagent has no explicit grant rows, so without this it sees
        // zero MCP tools even when the parent has them enabled — independent of
        // the fresh/inherit context choice. Scoped to freshly-created children so
        // operator-configured reused (agentId) agents are untouched.
        if (requestedAgentId == null) {
            grantParentMcpGrants(parentAgent, childAgent);
        }

        // JCLAW-268: inherit-mode tool union. Snapshot the parent's enabled
        // tool set and flip every tool the parent has enabled but the child
        // currently has disabled (via AgentService.create's default-disabled
        // rows for browser / jclaw_api on non-main agents) to enabled. The
        // child's already-enabled tools stay enabled; "union" means the child
        // gets the broader of the two surfaces. Skipped in fresh mode AND in
        // the inherit-mode summarization-failure degradation path so the
        // failure-degraded spawn is also tool-conservative.
        if (applyInheritGrants) {
            unionParentToolGrants(parentAgent, childAgent);
        }

        var childConv = resolveChildConversation(childAgent, parentConv,
                modelProviderOverride, modelIdOverride,
                applyInheritGrants ? parentContextSummary : null, inlineMode);

        return Bootstrap.ok(childAgent.id, childConv.id, childAgent.name);
    }

    /** {@code error} non-null short-circuits {@link #bootstrapChild}. */
    private record ResolvedChildAgent(Agent agent, String error) {
        static ResolvedChildAgent ok(Agent a) { return new ResolvedChildAgent(a, null); }
        static ResolvedChildAgent fail(String msg) { return new ResolvedChildAgent(null, msg); }
    }

    /**
     * Resolve the child {@link Agent}: either look up an existing row by
     * {@code requestedAgentId} or clone the parent into a fresh subagent
     * row. The {@code parent_agent_id} FK is only set on freshly-created
     * rows (see in-method commentary for the rationale).
     */
    private static ResolvedChildAgent resolveChildAgent(Agent parentAgent,
                                                        Long requestedAgentId, String label) {
        if (requestedAgentId != null) {
            Agent existing = Agent.findById(requestedAgentId);
            if (existing == null) {
                return ResolvedChildAgent.fail(
                        "Error: agentId %d not found.".formatted(requestedAgentId));
            }
            // Don't mutate Agent.parent_agent_id on a pre-existing row. The
            // lineage of *this* run is already recorded on the SubagentRun
            // (parentAgentId + childAgentId), and stamping parent_agent_id
            // on the Agent row permanently demotes an operator-created
            // top-level agent into a subagent: it disappears from the
            // Agents page (ApiAgentsController.list filters parentAgent !=
            // null) and the operator can't recreate one with the same
            // name because Agent.name carries a global UNIQUE constraint.
            // For freshly-created child agents (else branch) the FK is
            // still set because those rows are genuinely subagents.
            return ResolvedChildAgent.ok(existing);
        }
        // Clone the parent's runtime config (provider, model, thinkingMode)
        // into a fresh row so the child is its own auditable identity.
        // JCLAW-269: child Agent ALWAYS inherits the parent's defaults; the
        // per-spawn modelProvider/modelId override (when supplied) lands on
        // the child Conversation, not on this row. Keeping the Agent row
        // clean of one-shot overrides means re-running the same child agent
        // later (via agentId) doesn't carry stale per-spawn state.
        var name = buildChildAgentName(parentAgent.name);
        Agent created;
        try {
            // Subagents are delegates of the parent — they inherit the
            // parent's on-disk workspace via AgentService.workspacePath's
            // parent-chain walk and never need their own SOUL / IDENTITY /
            // USER / BOOTSTRAP / AGENT skeleton. The createWorkspace=false
            // arg suppresses the directory + markdown stubs that the
            // operator-facing create paths still produce. Subagent tool
            // calls resolve to the parent's workspace transparently.
            created = AgentService.create(name,
                    parentAgent.modelProvider, parentAgent.modelId,
                    parentAgent.thinkingMode,
                    label != null && !label.isBlank()
                            ? label
                            : "Subagent of " + parentAgent.name,
                    /* createWorkspace */ false);
        } catch (RuntimeException e) {
            return ResolvedChildAgent.fail(
                    "Error: failed to create child agent: " + e.getMessage());
        }
        created.parentAgent = parentAgent;
        created.save();
        return ResolvedChildAgent.ok(created);
    }

    /**
     * Resolve the child {@link Conversation}: inline mode reuses the
     * parent's row, session mode creates a fresh row stamped with the
     * per-spawn model override and inherited parent-context summary.
     *
     * <p>Per-spawn model override + inherited parent-context blob only apply
     * to session mode: they live on the *child* Conversation row, and in
     * inline mode that "child" is the parent itself — writing them there
     * would clobber the parent's effective model and context for the rest
     * of the parent's turns. Inline-mode children effectively run with the
     * parent's settings (same model, same prompt assembly); the model
     * override and parent-context summary parameters are no-ops in this
     * mode by design.
     */
    private static Conversation resolveChildConversation(Agent childAgent,
                                                          Conversation parentConv,
                                                          String modelProviderOverride,
                                                          String modelIdOverride,
                                                          String parentContextSummary,
                                                          boolean inlineMode) {
        // JCLAW-267: inline mode reuses the parent Conversation as the child's
        // run target — the SubagentRun row points its childConversation FK at
        // the same row as parentConversation, and AgentRunner persists the
        // child's messages back into the parent transcript stamped with the
        // SubagentRun id (via the ConversationService ThreadLocal marker).
        // No new Conversation row is created. Session mode keeps the existing
        // JCLAW-265 behavior: fresh child Conversation, separate transcript,
        // visible as its own row in the sidebar.
        if (inlineMode) {
            return parentConv;
        }
        // JCLAW-327 AC-5: the child Conversation inherits the parent's
        // channelType + peerId. Two reasons. (a) The new {@link MessageTool}
        // infers its default delivery channel + target from the calling
        // agent's active Conversation; without inheritance, a subagent
        // spawned inside a Telegram thread would default to
        // channelType="subagent" and have nowhere to push progress
        // updates. (b) The /conversations + /subagents UIs filter by
        // {@code parentConversation IS NULL}, so subagent children stay
        // hidden from the main listings regardless of channelType — no UI
        // regression. The {@code SUBAGENT_CHANNEL} constant survives as
        // an EventLogger category tag (see warn() sites below); it's no
        // longer used as a Conversation column value.
        var childConv = ConversationService.create(childAgent,
                parentConv.channelType, parentConv.peerId);
        childConv.parentConversation = parentConv;
        // JCLAW-269 / JCLAW-422: persist the resolved model on the child
        // Conversation so AgentRunner's ModelOverrideResolver picks it up for
        // this run, and so the JCLAW-28 cost dashboard's
        // COALESCE(c.modelProviderOverride, c.agent.modelProvider) attributes
        // spend to the actually-used model. The resolver tracks the model the
        // operator is ACTUALLY using (the parent conversation's override),
        // not just the agent's base — see resolveSubagentModel.
        var resolved = resolveSubagentModel(parentConv, childAgent, modelProviderOverride, modelIdOverride);
        // Only stamp an override when the resolved model DIFFERS from the child
        // agent's base — the plain inherit case keeps a null override (matches
        // prior behavior and the cost dashboard's COALESCE(override, base)).
        if (!java.util.Objects.equals(resolved.provider(), childAgent.modelProvider)
                || !java.util.Objects.equals(resolved.modelId(), childAgent.modelId)) {
            childConv.modelProviderOverride = resolved.provider();
            childConv.modelIdOverride = resolved.modelId();
        }
        // JCLAW-268: stamp the inherited parent-context summary on the child
        // Conversation. AgentRunner re-injects this into the child's system
        // prompt every turn via SessionCompactor.appendParentContextToPrompt.
        // Null in fresh mode, in the summarization-failure degradation path,
        // and when the parent had no usable history — all of which leave
        // the column null and turn the injection into a no-op.
        if (parentContextSummary != null && !parentContextSummary.isBlank()) {
            childConv.parentContext = parentContextSummary;
        }
        childConv.save();
        return childConv;
    }

    /** Resolved (provider, modelId) pair for a spawned subagent. */
    public record SubagentModel(String provider, String modelId) {}

    public static final String CFG_SUBAGENT_PROVIDER = "subagent.modelProvider";
    public static final String CFG_SUBAGENT_MODEL = "subagent.modelId";

    /**
     * JCLAW-422: resolve the model a session subagent runs on. Precedence:
     * <ol>
     *   <li>explicit per-spawn override ({@code modelProvider}/{@code modelId} args);</li>
     *   <li>operator-configured subagent default (Settings → Subagents:
     *       {@code subagent.modelProvider}/{@code subagent.modelId}) — pin every
     *       fan-out to e.g. a cheaper model;</li>
     *   <li>the parent conversation's EFFECTIVE model — its mid-chat override if
     *       the operator switched models, else the spawning agent's base. This is
     *       the fix: subagents track the model you're ACTUALLY using, not just the
     *       agent's base (a chat switched to Qwen used to spawn children onto the
     *       agent's stale lm-studio base).</li>
     * </ol>
     */
    public static SubagentModel resolveSubagentModel(Conversation parentConv, Agent childAgent,
                                                     String overrideProvider, String overrideId) {
        if (notBlank(overrideProvider) && notBlank(overrideId)) {
            return new SubagentModel(overrideProvider, overrideId);
        }
        var cfgProvider = services.ConfigService.get(CFG_SUBAGENT_PROVIDER);
        var cfgModel = services.ConfigService.get(CFG_SUBAGENT_MODEL);
        if (notBlank(cfgProvider) && notBlank(cfgModel)) {
            return new SubagentModel(cfgProvider, cfgModel);
        }
        var provider = parentConv != null && notBlank(parentConv.modelProviderOverride)
                ? parentConv.modelProviderOverride : childAgent.modelProvider;
        var modelId = parentConv != null && notBlank(parentConv.modelIdOverride)
                ? parentConv.modelIdOverride : childAgent.modelId;
        return new SubagentModel(provider, modelId);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * JCLAW-268: snapshot-into-child-Agent's-tool-config implementation of the
     * "union of parent's enabled tools and child's configured tools" AC.
     *
     * <p>Picks the snapshot approach over a per-Conversation overlay because
     * (a) the child Agent is already per-spawn under JCLAW-265's create-or-
     * reuse flow, so there's no risk of stale grants leaking into an
     * unrelated future spawn, and (b) the existing
     * {@link ToolRegistry#loadDisabledTools} fast path already reads from
     * {@code AgentToolConfig} on every turn — no new overlay codepath to
     * teach.
     *
     * <p>Mechanics:
     * <ol>
     *   <li>Compute the parent's effective enabled-tool set (every registered
     *       tool minus the parent's disabled set).</li>
     *   <li>Walk the child's existing {@code AgentToolConfig} rows. For any
     *       row whose {@code toolName} is in the parent's enabled set and
     *       currently has {@code enabled=false} on the child (the
     *       {@link AgentService#create} default-disables for {@code browser}
     *       and {@code jclaw_api} on non-main agents), flip it to
     *       {@code enabled=true}.</li>
     * </ol>
     *
     * <p>Tools the child doesn't have an explicit row for are already
     * enabled by default — no row needed. We never write a brand-new
     * {@code enabled=true} row for a tool that's already default-enabled;
     * that would only bloat the table.
     *
     * <p>Toolset-restriction caveat: the AC references "JCLAW-252 patterns"
     * for an additional restriction layer on top of the union. That ticket
     * does not exist in this codebase. There is no explicit allowlist /
     * deny-list mechanism beyond {@code AgentToolConfig} itself, so the
     * union IS the full grant — the child only sees tools the parent had
     * enabled OR the child default-allowed.
     */
    private static void unionParentToolGrants(Agent parentAgent, Agent childAgent) {
        var parentDisabled = ToolRegistry.loadDisabledTools(parentAgent);
        var allRegistered = ToolRegistry.listTools();
        // Parent's enabled set: every registered tool not in the parent's
        // disabled set. Avoids materializing the full registry as a set just
        // to negate the disabled set.
        var childRows = AgentToolConfig.findByAgent(childAgent);
        boolean anyFlipped = false;
        for (var row : childRows) {
            // Skip rows the parent doesn't grant, rows already enabled, or
            // stale rows referencing a removed tool — flipping a stale row
            // would be lying about the registry's current shape.
            if (!row.enabled
                    && !parentDisabled.contains(row.toolName)
                    && isStillRegistered(allRegistered, row.toolName)) {
                row.enabled = true;
                row.save();
                anyFlipped = true;
            }
        }
        if (anyFlipped) {
            // Mirror the existing {@link controllers.ApiToolsController} write
            // path: cached disabled-set is invalidated after every toggle so
            // the very next AgentRunner turn for this child sees the freshly
            // flipped grants.
            ToolRegistry.invalidateDisabledToolsCache(childAgent);
        }
    }

    /**
     * JCLAW-495: grant a freshly-cloned subagent the parent's enabled MCP server
     * handles. MCP grouped tools are default-disabled for non-main agents
     * ({@link ToolRegistry#loadDisabledTools}); a delegate subagent needs the
     * same MCP surface as its parent to carry out delegated work, so write an
     * explicit enabled {@link AgentToolConfig} row for every MCP handle the
     * parent grants but the child (by default) does not. Mirrors the operator
     * opt-in write path (a single row keyed by the {@code mcp_<group>} handle).
     * Unlike {@link #unionParentToolGrants}, this <em>creates</em> rows — the
     * child has none for MCP tools (they are disabled-by-default, not by an
     * explicit row), so there is nothing to flip.
     */
    private static void grantParentMcpGrants(Agent parentAgent, Agent childAgent) {
        var parentDisabled = ToolRegistry.loadDisabledTools(parentAgent);
        var childDisabled = ToolRegistry.loadDisabledTools(childAgent);
        boolean anyGranted = false;
        for (var tool : ToolRegistry.listTools()) {
            if (tool.group() == null) continue;                 // MCP-grouped tools only
            if (parentDisabled.contains(tool.name())) continue; // parent doesn't grant it
            if (!childDisabled.contains(tool.name())) continue; // child already has it
            var row = new AgentToolConfig();
            row.agent = childAgent;
            row.toolName = tool.name();
            row.enabled = true;
            row.save();
            anyGranted = true;
        }
        if (anyGranted) {
            ToolRegistry.invalidateDisabledToolsCache(childAgent);
        }
    }

    private static boolean isStillRegistered(List<ToolRegistry.Tool> registered, String toolName) {
        for (var t : registered) {
            if (t.name().equals(toolName)) return true;
        }
        return false;
    }

    /**
     * JCLAW-268: synchronously build the parent-context summary. Snapshots
     * the parent's recent messages inside a Tx, then calls the LLM outside
     * any Tx so the chat round-trip doesn't hold a DB connection. Returns
     * {@code null} when there's nothing useful to summarize (no recent
     * messages, model returned blank) — the caller treats that as "skip
     * silently, no error". Any other failure (provider unconfigured, LLM
     * error, network) throws and the caller emits SUBAGENT_ERROR.
     */
    private static String buildParentContextSummary(Agent parentAgent, Long parentConvId) throws Exception {
        var snapshot = Tx.run(() -> {
            var conv = Conversation.<Conversation>findById(parentConvId);
            return SessionCompactor.snapshotParentMessages(conv);
        });
        if (snapshot == null || snapshot.isEmpty()) return null;

        var provider = ProviderRegistry.get(parentAgent.modelProvider);
        if (provider == null) {
            throw new IllegalStateException(
                    "Parent provider '" + parentAgent.modelProvider + "' is not configured");
        }
        final var maxOutput = ConfigService.getInt("subagent.parentContextMaxTokens", 4096);
        final var modelId = parentAgent.modelId;
        SessionCompactor.Summarizer summarizer = sumMsgs -> {
            var resp = provider.chat(modelId, sumMsgs, List.of(), maxOutput, null, null);
            return SessionCompactor.firstChoiceText(resp);
        };
        return SessionCompactor.summarizeParentForSubagent(snapshot, summarizer);
    }

    /** Unique child agent name: parent + short token. Agent.name has a unique
     *  constraint; we keep the parent prefix so the workspace folder is easy to
     *  locate when debugging. */
    private static String buildChildAgentName(String parentName) {
        var suffix = Long.toString(System.nanoTime(), 36);
        return parentName + "-sub-" + suffix;
    }

    private static Conversation resolveParentConversation(Long parentAgentId) {
        var parent = (Agent) Agent.findById(parentAgentId);
        if (parent == null) return null;
        // Pick the most recently-updated conversation that ISN'T a subagent
        // child of someone else (a subagent calling spawn nests under its own
        // parent conversation, which is itself an existing Conversation row —
        // no special-case needed; channelType="subagent" simply means "I'm a
        // child of someone" and we still want that as the parent for a nested
        // spawn).
        return Conversation.find("agent = ?1 ORDER BY updatedAt DESC", parent).first();
    }

    private static String lookupAgentName(Long id) {
        if (id == null) return null;
        return Tx.run(() -> {
            var a = (Agent) Agent.findById(id);
            return a != null ? a.name : null;
        });
    }

    private static String optString(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }

    private static Long optLong(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try { return el.getAsLong(); } catch (RuntimeException _) { return null; }
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return fallback;
        try { return el.getAsInt(); } catch (RuntimeException _) { return fallback; }
    }

    private static boolean optBool(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return false;
        try { return el.getAsBoolean(); } catch (RuntimeException _) { return false; }
    }

    /**
     * JCLAW-270: async-spawn body. Runs on a dedicated virtual thread
     * (name {@code subagent-async-<runId>}) so the parent's tool-call return
     * doesn't wait on the child's wall clock. The shape mirrors the
     * synchronous flow (AgentRunner.run with a Future.get timeout) — when it
     * terminates, this method writes the announce Message into the parent
     * Conversation, updates the SubagentRun audit row, and fires the
     * terminal lifecycle event (SUBAGENT_COMPLETE / SUBAGENT_ERROR /
     * SUBAGENT_TIMEOUT).
     *
     * <p>Restart resilience: in-flight VTs do not survive a JVM restart.
     * {@link jobs.SubagentOrphanRecoveryJob} marks any RUNNING SubagentRun
     * older than a small window FAILED at boot so the audit log reflects
     * truth; no announce Message is posted for orphans (the parent
     * conversation may have moved on, surfacing a stale completion card
     * would be more disruptive than helpful).
     *
     * @param runId           the {@link models.SubagentRun} audit-row id
     * @param childAgentId    id of the child agent to run
     * @param childConvId     the child Conversation id (inline reuses the
     *                        parent; session mode has a separate child)
     * @param parentConvId    the parent Conversation id where the announce
     *                        Message will be written
     * @param parentAgentName parent agent's name (used in announce metadata
     *                        and lifecycle events)
     * @param mode            {@code "inline"} or {@code "session"}
     * @param context         optional context string included in the
     *                        announce Message body
     * @param label           short label for the announce card
     * @param timeoutSeconds  wall-clock cap; on expiry the SubagentRun is
     *                        marked TIMED_OUT
     * @param task            the user-prompt-equivalent string the child
     *                        agent processes
     */
    @SuppressWarnings("java:S1181")
    public static void runAsyncAndAnnounce(Long runId, Long childAgentId, Long childConvId,
                                            Long parentConvId, String parentAgentName,
                                            String mode, String context, String label,
                                            int timeoutSeconds, String task) {
        var future = startAsyncChild(runId, childAgentId, childConvId, task);
        SyncRunOutcome outcome;
        try {
            outcome = awaitAsyncFuture(future, timeoutSeconds,
                    ConfigService.getInt(MAX_WALLCLOCK_KEY, DEFAULT_MAX_WALLCLOCK_SECONDS), runId);
        } finally {
            services.SubagentRegistry.unregister(runId);
        }

        // JCLAW-291: kill primitive owns the terminal state on this path —
        // skip every downstream side effect (audit write, announce post,
        // lifecycle event, yield resume). The operator's slash-command
        // response is the user-visible kill confirmation.
        if (outcome.killedByOperator()) {
            EventLogger.flush();
            return;
        }

        // Update the SubagentRun audit row (terminal status + outcome).
        // JCLAW-271: skip the write when /subagent kill already stamped
        // KILLED — the operator's reason is the source of truth, and
        // overwriting it here would replace "Killed by operator" with the
        // TIMEOUT / FAILED message we synthesized when the cancelled
        // Future threw.
        persistAsyncTerminalRun(runId, outcome);

        // Build + post the announce Message into the parent Conversation.
        var yieldedFlag = postAnnounceAndReadYieldFlag(runId, childConvId, parentConvId,
                label, outcome);

        emitTerminalEvent(parentAgentName, lookupAgentName(childAgentId), String.valueOf(runId),
                mode, context, outcome.terminalStatus(), outcome.errorReason());
        EventLogger.flush();

        // JCLAW-273: resume the parent agent's logical turn for a yielded
        // caller. The announce Message we just posted is the parent's next
        // user input; calling AgentRunner.run kicks off a fresh turn that
        // picks up the announce via ConversationService.loadRecentMessages
        // (which keeps USER-role announce rows visible to the LLM) and
        // produces a final assistant reply.
        if (Boolean.TRUE.equals(yieldedFlag)) {
            try {
                resumeParentAfterYield(parentConvId, runId);
            } catch (Throwable t) {
                EventLogger.warn(SUBAGENT_CHANNEL,
                        "Failed to resume parent for yielded run " + runId
                                + ": " + t.getMessage());
            }
        }
    }

    /**
     * JCLAW-270 / JCLAW-291: spin up the inner runner VT and register the
     * future with {@link services.SubagentRegistry} for cooperative cancel.
     * The carrier thread is intentionally NOT captured — see
     * {@code services.SubagentRegistry}'s class doc for the H2 FileChannel
     * post-mortem.
     */
    @SuppressWarnings("java:S1181")
    private static CompletableFuture<AgentRunner.RunResult> startAsyncChild(
            Long runId, Long childAgentId, Long childConvId, String task) {
        var future = new CompletableFuture<AgentRunner.RunResult>();
        services.SubagentRegistry.register(runId, future);
        Thread.ofVirtual().name("subagent-async-runner-" + runId).start(() -> {
            try {
                var childAgent = Tx.run(() -> (Agent) Agent.findById(childAgentId));
                var childConv = Tx.run(() -> (Conversation) Conversation.findById(childConvId));
                if (childAgent == null || childConv == null) {
                    throw new IllegalStateException(
                            "Subagent rows vanished before AgentRunner.run");
                }
                future.complete(AgentRunner.run(childAgent, childConv, task));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Async variant of {@link #awaitFuture}: identical catch ladder plus a
     * top-level {@link Throwable} guard so this background VT never leaks an
     * unchecked failure (which would lose the announce + terminal event
     * entirely).
     */
    @SuppressWarnings("java:S1181")
    private static SyncRunOutcome awaitAsyncFuture(
            CompletableFuture<AgentRunner.RunResult> future, int timeoutSeconds, int ceilingSeconds, Long runId) {
        try {
            return awaitFuture(future, timeoutSeconds, ceilingSeconds, runId);
        } catch (Throwable t) {
            // Top-level guard: see method javadoc.
            var reason = t.getMessage() != null ? t.getMessage() : t.toString();
            return new SyncRunOutcome("", SubagentRun.Status.FAILED,
                    reason, reason, false, false);
        }
    }

    /**
     * Async variant of {@link #persistTerminalRun}: wraps the Tx in a
     * Throwable catch so a persistence failure logs but never aborts the
     * announce post. {@link SubagentRun#outcome} is the reply on COMPLETED
     * and the error reason otherwise — matches the synchronous path's
     * semantics.
     */
    @SuppressWarnings("java:S1181")
    private static void persistAsyncTerminalRun(Long runId, SyncRunOutcome outcome) {
        final var finalStatus = outcome.terminalStatus();
        final var outcomeText = finalStatus == SubagentRun.Status.COMPLETED
                ? outcome.reply()
                : outcome.errorReason();
        try {
            Tx.run(() -> {
                var fresh = (SubagentRun) SubagentRun.findById(runId);
                if (fresh != null && fresh.status != SubagentRun.Status.KILLED) {
                    fresh.status = finalStatus;
                    fresh.endedAt = Instant.now();
                    fresh.outcome = outcomeText;
                    fresh.save();
                }
            });
        } catch (Throwable t) {
            EventLogger.warn(SUBAGENT_CHANNEL,
                    "Failed to persist terminal SubagentRun update for run " + runId
                            + ": " + t.getMessage());
        }
    }

    /**
     * JCLAW-273: read the yield flag inside the same Tx that persists the
     * announce so the role-decision and the message insert see a consistent
     * snapshot of {@link SubagentRun#yielded}. A parent that called
     * subagent_yield expects USER-role delivery (the announce IS its next
     * user message); a fire-and-forget async caller expects SYSTEM-role
     * (the JCLAW-270 semantics — visible card, never feeds back into LLM
     * context).
     *
     * <p>NB: the local {@code displayTruncatedBody} is the 4000-char display
     * cap on the announce body, NOT the JCLAW-291 model-output truncation
     * flag. The two flow through {@link #postAnnounceMessage} as separate
     * parameters.
     */
    @SuppressWarnings("java:S1181")
    private static Boolean postAnnounceAndReadYieldFlag(Long runId, Long childConvId,
                                                        Long parentConvId, String label,
                                                        SyncRunOutcome outcome) {
        var status = outcome.terminalStatus();
        var failureBody = outcome.errorReason() != null ? outcome.errorReason() : "";
        // JCLAW-424 (AC5): on a timeout, append the child's partial output (carried
        // on outcome.reply()) to the timeout reason so the async parent salvages it
        // too — the reason itself is preserved for the announce's terminal semantics.
        var failureOrPartialBody = status == SubagentRun.Status.TIMEOUT && notBlank(outcome.reply())
                ? failureBody + "\n\nPartial output before timeout:\n" + outcome.reply()
                : failureBody;
        var announceBody = status == SubagentRun.Status.COMPLETED
                ? outcome.reply()
                : failureOrPartialBody;
        var displayTruncatedBody = truncateForAnnounce(announceBody);
        final var modelOutputTruncated = outcome.replyTruncated();
        try {
            return Tx.run(() -> {
                var run = (SubagentRun) SubagentRun.findById(runId);
                boolean isYielded = run != null && run.yielded;
                postAnnounceMessage(parentConvId, runId, label, status,
                        displayTruncatedBody, childConvId, isYielded, modelOutputTruncated);
                return isYielded;
            });
        } catch (Throwable t) {
            EventLogger.warn(SUBAGENT_CHANNEL,
                    "Failed to post announce Message for run " + runId
                            + ": " + t.getMessage());
            return Boolean.FALSE;
        }
    }

    /**
     * JCLAW-273: re-invoke {@link AgentRunner#runYieldResume} on the parent
     * conversation after a yielded async run terminates. The announce
     * Message has already been persisted as a USER-role row (see
     * {@link #postAnnounceMessage}); the resume entrypoint runs the
     * standard prompt-assembly + LLM pipeline against the now-extended
     * conversation history WITHOUT re-appending a user message (which
     * would duplicate the announce). The LLM picks up the announce via
     * {@link services.ConversationService#loadRecentMessages}, whose
     * JCLAW-273 filter keeps USER-role announces in LLM context.
     *
     * <p>Runs in the announce VT (so the call is naturally outside any
     * other turn's queue acquisition). Failures are caught + logged at the
     * caller — losing the resume must not lose the audit row or the
     * announce.
     */
    private static void resumeParentAfterYield(Long parentConvId, Long runId) {
        var conv = Tx.run(() -> (Conversation) Conversation.findById(parentConvId));
        if (conv == null) {
            EventLogger.warn(SUBAGENT_CHANNEL,
                    "Yielded resume skipped: parent conversation " + parentConvId
                            + " not found for run " + runId);
            return;
        }
        var parentAgent = Tx.run(() -> {
            var c = (Conversation) Conversation.findById(parentConvId);
            return c != null ? c.agent : null;
        });
        if (parentAgent == null) {
            EventLogger.warn(SUBAGENT_CHANNEL,
                    "Yielded resume skipped: no agent on parent conversation " + parentConvId
                            + " for run " + runId);
            return;
        }
        AgentRunner.runYieldResume(parentAgent, conv);
    }

    /**
     * Hard-truncate to {@link #ANNOUNCE_REPLY_MAX_CHARS} with an ellipsis
     * marker so the reader can tell the preview is bounded. The full reply
     * stays accessible via the announce card's "View full" link to the
     * child Conversation.
     */
    static String truncateForAnnounce(String s) {
        if (s == null) return "";
        if (s.length() <= ANNOUNCE_REPLY_MAX_CHARS) return s;
        // Reserve 3 chars for the ellipsis so the visible char count
        // including the marker matches ANNOUNCE_REPLY_MAX_CHARS exactly.
        return s.substring(0, ANNOUNCE_REPLY_MAX_CHARS - 3) + "...";
    }

    /**
     * Persist the announce Message into the parent Conversation. The role
     * depends on whether the parent yielded into this run (JCLAW-273): a
     * fire-and-forget async caller gets a {@link MessageRole#SYSTEM}-role
     * row (kept out of the LLM's view by
     * {@link services.ConversationService#loadRecentMessages}), while a
     * yielded caller gets a {@link MessageRole#USER}-role row that the LLM
     * sees as its next user-role input on resume. Either way the row is
     * stamped with {@link #MESSAGE_KIND_ANNOUNCE} so the chat UI renders
     * the same structured card (run id, label, status, truncated reply,
     * "View full" link to the child Conversation).
     *
     * <p>{@code content} carries a plain-text fallback for transports that
     * don't understand the card. For yielded rows the plain text is what
     * the LLM sees in the rebuilt context — keep it the rich-prose form
     * (status + reply) so a model that doesn't understand the metadata
     * still gets a coherent user turn.
     */
    private static void postAnnounceMessage(Long parentConvId, Long runId, String label,
                                            SubagentRun.Status status, String truncatedReply,
                                            Long childConvId, boolean yielded,
                                            boolean modelOutputTruncated) {
        var parentConv = (Conversation) Conversation.findById(parentConvId);
        if (parentConv == null) return;

        var payload = new LinkedHashMap<String, Object>();
        payload.put("runId", runId);
        payload.put(FIELD_LABEL, label != null ? label : "");
        payload.put(FIELD_STATUS, status.name());
        payload.put("reply", truncatedReply != null ? truncatedReply : "");
        payload.put("childConversationId", childConvId);
        payload.put("yielded", yielded);
        // JCLAW-291: separate from {@code truncatedReply} (which is the
        // 4000-char display cap on the announce body) — this flag means the
        // CHILD'S underlying reply was cut off by max_tokens. The chat-page
        // announce card reads this and renders a "Reply was truncated by
        // the model" marker.
        if (modelOutputTruncated) payload.put("truncated", Boolean.TRUE);

        var fallbackLabel = label != null && !label.isBlank() ? label : "subagent run";
        var fallback = "Subagent " + status.name().toLowerCase() + " (" + fallbackLabel + ")"
                + (truncatedReply != null && !truncatedReply.isBlank()
                        ? ": " + truncatedReply
                        : "");

        var msg = new Message();
        msg.conversation = parentConv;
        msg.role = yielded ? MessageRole.USER.value : MessageRole.SYSTEM.value;
        msg.content = fallback;
        msg.messageKind = MESSAGE_KIND_ANNOUNCE;
        msg.metadata = utils.GsonHolder.INSTANCE.toJson(payload, Map.class);
        // JCLAW-291: also stamp the column on the announce row itself so
        // queries that count truncated messages see it without parsing JSON.
        msg.truncated = modelOutputTruncated;
        msg.save();

        parentConv.messageCount++;
        parentConv.save();
    }
}
