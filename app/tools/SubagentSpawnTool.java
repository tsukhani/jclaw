package tools;

import agents.AgentRunner;
import agents.DangerousActionGate;
import agents.RunCancelledException;
import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentToolConfig;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.NotificationBus;
import services.SessionCompactor;
import services.SubagentRegistry;
import services.Tx;
import utils.GsonHolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

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
    // JCLAW-S1192: request-arg and response-field key constants.
    private static final String ARG_TASKS = "tasks";
    private static final String ARG_AGENT_ID = "agentId";
    private static final String ARG_CONTEXT = "context";
    private static final String ARG_RUN_TIMEOUT_SECONDS = "runTimeoutSeconds";
    private static final String ARG_RUNTIME = "runtime";
    private static final String FIELD_RUN_ID = "run_id";
    private static final String FIELD_CONVERSATION_ID = "conversation_id";
    private static final String FIELD_REPLY = "reply";
    /** JCLAW-662 bus-event key — camelCase, consumed by the frontend
     *  (chat.vue d.runId / CodingRunMonitor envelope.runId). NOT the
     *  snake_case FIELD_RUN_ID of the spawn-response payload. */
    private static final String BUS_RUN_ID = "runId";
    /** JCLAW-665/S2445: per-run lock guarding decision-frame writes to the
     *  harness stdin — a dedicated, stable lock rather than the passed-in
     *  stream ref. Evicted when the run ends (runAcpRpc finally). */
    private static final ConcurrentHashMap<Long, Object> STDIN_WRITE_LOCKS = new ConcurrentHashMap<>();
    private static final String NO_OUTPUT = "(no output)";
    private static final String ACP_EXIT_MSG = "ACP harness exited %d: %s";
    private static final String GOT_LITERAL = " (got '";
    private static final String FIELD_TRUNCATED = "truncated";
    private static final String FIELD_ERROR = "error";

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

    /** JCLAW-662: {@link Message#messageKind} value stamped on the child-Conversation
     *  rows that persist a coding-harness run's streamed steps, so
     *  {@link controllers.ApiSubagentRunsController#steps} can replay only the
     *  transcript rows and skip any other child-conversation messages. */
    public static final String MESSAGE_KIND_CODINGRUN_STEP = "codingrun_step";

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
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction("spawn",
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
                SchemaKeys.DESCRIPTION, "Instruction for the child subagent (required unless you pass 'tasks' for a batch)"));
        props.put(ARG_TASKS, Map.of(SchemaKeys.TYPE, SchemaKeys.ARRAY,
                SchemaKeys.ITEMS, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING),
                SchemaKeys.DESCRIPTION,
                "BATCH FAN-OUT: an array of task strings to run as async subagents IN PARALLEL "
                        + "from one call (provide this INSTEAD of 'task'). Returns {run_ids:[...]} "
                        + "immediately; collect them all with ONE subagent_yield using runIds (or "
                        + "all=true). Session mode only."));
        props.put(FIELD_LABEL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Optional short display name for the spawn"));
        props.put(ARG_AGENT_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
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
        props.put(ARG_CONTEXT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Context inheritance mode: \"fresh\" (default) for an empty child history, "
                        + "or \"inherit\" to summarize the parent's recent turns into the "
                        + "child's system prompt and grant the child the union of the parent's "
                        + "enabled tools and its own."));
        props.put(ARG_RUN_TIMEOUT_SECONDS, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Idle budget: seconds of inactivity (no LLM round/tool call) before the run times out; "
                        + "active work resets it, so it need not cover total runtime (default 300)"));
        props.put("async", Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                SchemaKeys.DESCRIPTION,
                "When true (default false), return the child's run id immediately so you can "
                        + "spawn more or do other work, then collect with subagent_yield. In a chat "
                        + "a completion card is posted to your conversation; in a task fire you "
                        + "block-await the result with subagent_yield. Only with mode=\"session\"."));
        props.put(ARG_RUNTIME, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Child runtime: \"native\" (default) runs a JClaw subagent, or \"acp\" runs the "
                        + "operator-configured external agent harness (e.g. a Codex/Claude/Gemini CLI) "
                        + "with your task on stdin, capturing its output as the reply. Composes with "
                        + "sync, async, and batch."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
                // task XOR tasks — validated at runtime (single vs batch path).
                SchemaKeys.REQUIRED, List.of()
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
        // JCLAW-498: batch fan-out — a tasks[] array spawns N async children in
        // parallel; the single-child flow below handles a scalar task.
        if (args.has(ARG_TASKS) && !args.get(ARG_TASKS).isJsonNull()) {
            return executeBatch(args, parentAgent);
        }
        var parsed = parseSpawnArgs(args);
        if (parsed.error() != null) return parsed.error();
        // JCLAW-499: validate the runtime (native | acp) before any DB work.
        var acpError = acpRuntimeError(args, parentAgent);
        if (acpError != null) return acpError;

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

        // JCLAW-499: register the external-harness command for this run when
        // runtime=acp (already validated above); executeChildRun consumes it.
        if (isAcpRuntime(args)) {
            ACP_RUNS.put(runId, resolveAcpCommand());
        }

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
            // JCLAW-497: a task fire has no persistent conversation to resume into,
            // so the announce/resume model can't deliver there. Use a block-await
            // handoff instead — dispatch the child (parallel) and let subagent_yield
            // collect its result inline. Chat keeps the announce/resume path.
            if (ToolContext.taskRunId() != null) {
                return launchAsyncSpawnForTask(runId, childAgentId, childConvId,
                        parentAgent.name, parsed, runIdStr);
            }
            return launchAsyncSpawn(runId, childAgentId, childConvId, parentConvIdFinal,
                    parentAgent.name, parsed, runIdStr);
        }

        // Step 4: synchronous AgentRunner.run on a VT so we can enforce the
        // wall-clock budget via Future.get(timeout).
        // JCLAW-661: for an acp coding run, bridge the open chat SSE (if any) to
        // this run so the harness's live steps stream into the turn that spawned
        // it; always unbind in the finally so a finished run can't leak callbacks.
        boolean acpBridged = isAcpRuntime(args);
        if (acpBridged) bindChatCallbacksToRun(runId);
        SyncRunOutcome runOutcome;
        try {
            runOutcome = runChildSynchronously(runId, childAgentId, childConvId,
                    parsed.task(), parsed.timeoutSeconds(), inlineMode);
        } finally {
            if (acpBridged) RUN_CALLBACKS.remove(runId);
        }

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
        payload.put(FIELD_RUN_ID, runIdStr);
        payload.put(FIELD_CONVERSATION_ID, String.valueOf(childConvId));
        payload.put(FIELD_REPLY, runOutcome.reply());
        payload.put(FIELD_STATUS, runOutcome.terminalStatus().name());
        // JCLAW-291: hint to the parent LLM that the child's reply was cut
        // off — useful when the parent decides whether to re-summarize or
        // to surface the truncation to the user.
        if (runOutcome.replyTruncated()) payload.put(FIELD_TRUNCATED, Boolean.TRUE);
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    /**
     * JCLAW-498: batch async fan-out. Spawn one async child per entry in
     * {@code tasks[]} concurrently (each on its own VT), returning the run_ids
     * immediately so the parent can keep working and later collect them all with
     * one subagent_yield (runIds / all). Works in chat and task fires alike — the
     * children are detached (no announce/resume), collected via block-await.
     */
    private String executeBatch(JsonObject args, Agent parentAgent) {
        var tasksEl = args.get(ARG_TASKS);
        if (!tasksEl.isJsonArray() || tasksEl.getAsJsonArray().isEmpty()) {
            return "Error: 'tasks' must be a non-empty array of task strings or {task, ...} objects.";
        }
        // Batch is inherently async + session-scoped; inline has no batch semantics.
        var rawMode = optString(args, "mode");
        var mode = (rawMode == null || rawMode.isBlank()) ? DEFAULT_MODE : rawMode.toLowerCase();
        if (MODE_INLINE.equals(mode)) {
            return "Error: batch 'tasks' is only compatible with mode=\"session\" (inline embeds a single child into the parent transcript).";
        }
        var rawContext = optString(args, ARG_CONTEXT);
        var context = (rawContext == null || rawContext.isBlank()) ? DEFAULT_CONTEXT : rawContext.toLowerCase();
        if (!ALLOWED_CONTEXTS.contains(context)) {
            return "Error: 'context' must be one of " + ALLOWED_CONTEXTS + ".";
        }
        var timeoutSeconds = optInt(args, ARG_RUN_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
        if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        // JCLAW-499: runtime validation (native | acp) + resolved harness command,
        // applied to every child of the fan-out.
        var acpError = acpRuntimeError(args, parentAgent);
        if (acpError != null) return acpError;
        final var acpCommand = isAcpRuntime(args) ? resolveAcpCommand() : null;

        var specs = new ArrayList<BatchTaskSpec>();
        for (var el : tasksEl.getAsJsonArray()) {
            if (el.isJsonPrimitive()) {
                var t = el.getAsString();
                if (t.isBlank()) return "Error: each 'tasks' entry must be a non-blank task.";
                specs.add(new BatchTaskSpec(t, null, null));
            } else if (el.isJsonObject()) {
                var o = el.getAsJsonObject();
                var t = optString(o, "task");
                if (t == null || t.isBlank()) return "Error: each 'tasks' object must have a non-blank 'task'.";
                specs.add(new BatchTaskSpec(t, optString(o, FIELD_LABEL), optLong(o, ARG_AGENT_ID)));
            } else {
                return "Error: each 'tasks' entry must be a string or an object.";
            }
        }

        // Breadth cap for the whole fan-out (running + N must fit).
        final int n = specs.size();
        var refusal = Tx.run(() -> enforceRecursionLimits(parentAgent, n));
        if (refusal != null) {
            EventLogger.recordSubagentLimitExceeded(parentAgent.name, refusal);
            return "Subagent spawn refused: " + refusal;
        }

        final var parentAgentId = parentAgent.id;
        var parentConv = Tx.run(() -> resolveParentConversation(parentAgentId));
        if (parentConv == null) {
            return "Error: Could not resolve a parent conversation for agent '%s'.".formatted(parentAgent.name);
        }
        final var parentConvIdFinal = parentConv.id;
        final var scopeKey = currentScopeKey();
        final var fMode = mode;
        final var fContext = context;
        final var fTimeout = timeoutSeconds;

        // JCLAW-503: every child of the fan-out shares the same parent conversation
        // and context, so the inherit parent-context summary is identical for all of
        // them. Compute it ONCE here instead of re-running the summarizer LLM call per
        // child inside the loop. NONE in fresh mode (no LLM call); a summarization
        // failure degrades the whole batch to fresh, consistently.
        var summary = buildInheritSummary(parentAgent, parentConvIdFinal, fContext);

        var runIds = new ArrayList<String>();
        for (var spec : specs) {
            var perArgs = new SpawnArgs(null, spec.task(), spec.label(), spec.agentId(),
                    null, null, fMode, fContext, fTimeout, true);
            var bootstrap = bootstrapChildInTx(parentAgent, parentConv, perArgs, summary);
            if (bootstrap.error() != null) {
                continue; // skip a child that failed to bootstrap; the rest still run
            }
            var runId = insertSubagentRun(parentAgentId, bootstrap.childAgentId(),
                    parentConvIdFinal, bootstrap.childConvId(), spec.label());
            EventLogger.recordSubagentSpawn(parentAgent.name, bootstrap.childAgentName(),
                    String.valueOf(runId), fMode, fContext);
            if (acpCommand != null) {
                ACP_RUNS.put(runId, acpCommand);
            }
            dispatchDetachedAsync(runId, bootstrap.childAgentId(), bootstrap.childConvId(),
                    parentAgent.name, fMode, fContext, fTimeout, spec.task(), scopeKey);
            runIds.add(String.valueOf(runId));
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("run_ids", runIds);
        payload.put("count", runIds.size());
        payload.put(FIELD_STATUS, SubagentRun.Status.RUNNING.name());
        payload.put("hint", "children run in parallel; collect them all with one subagent_yield using runIds (or all=true)");
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    /** JCLAW-498: one entry of a batch fan-out. */
    private record BatchTaskSpec(String task, String label, Long agentId) {}

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
        var requestedAgentId = optLong(args, ARG_AGENT_ID);
        var modelProviderOverride = optString(args, "modelProvider");
        var modelIdOverride = optString(args, "modelId");
        var timeoutSeconds = optInt(args, ARG_RUN_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
        if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        // JCLAW-267: mode parameter — "session" (default) materializes a fresh
        // child Conversation; "inline" runs the child in the parent's
        // Conversation with messages tagged so the chat UI folds them.
        var requestedMode = optString(args, "mode");
        var mode = requestedMode == null || requestedMode.isBlank()
                ? DEFAULT_MODE
                : requestedMode.toLowerCase();
        if (!ALLOWED_MODES.contains(mode)) {
            return SpawnArgs.fail("Error: 'mode' must be one of " + ALLOWED_MODES + GOT_LITERAL + requestedMode + "').");
        }
        // JCLAW-268: context parameter — "fresh" (default) is the JCLAW-265
        // behavior; "inherit" summarizes the parent's recent turns and unions
        // tool grants. Validate strictly so an LLM typo produces a clear
        // error rather than silently degrading.
        var requestedContext = optString(args, ARG_CONTEXT);
        var context = requestedContext == null || requestedContext.isBlank()
                ? DEFAULT_CONTEXT
                : requestedContext.toLowerCase();
        if (!ALLOWED_CONTEXTS.contains(context)) {
            return SpawnArgs.fail("Error: 'context' must be one of " + ALLOWED_CONTEXTS + GOT_LITERAL + requestedContext + "').");
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
        // JCLAW-497: async subagents ARE supported in task fires (a block-await
        // handoff collected by subagent_yield — see launchAsyncSpawnForTask),
        // superseding JCLAW-494's interim rejection.

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
        asyncPayload.put(FIELD_RUN_ID, runIdStr);
        asyncPayload.put(FIELD_CONVERSATION_ID, String.valueOf(childConvId));
        asyncPayload.put(FIELD_STATUS, SubagentRun.Status.RUNNING.name());
        return GsonHolder.INSTANCE.toJson(asyncPayload, Map.class);
    }

    /** JCLAW-497/498: detached async subagent OUTCOMES, keyed by runId, handed off
     *  from a spawn (single async-in-task or a batch fan-out) to the inline
     *  block-await in subagent_yield. These children have no persistent
     *  conversation to resume into, so the chat announce/resume path doesn't
     *  apply; the parent collects results via a blocking yield. */
    private static final ConcurrentHashMap<Long, CompletableFuture<SyncRunOutcome>>
            ASYNC_OUTCOMES = new ConcurrentHashMap<>();

    /** JCLAW-498: outstanding async-child runIds grouped by the spawning parent's
     *  scope ({@code task:<id>} or {@code conv:<id>}), so subagent_yield all=true
     *  can collect every child this parent fanned out without re-listing run ids. */
    private static final ConcurrentHashMap<String, Set<Long>>
            OUTSTANDING_BY_SCOPE = new ConcurrentHashMap<>();

    /** JCLAW-498: the current tool-dispatch scope key — {@code task:<id>} in a task
     *  fire, {@code conv:<id>} in a chat turn, or null when neither is bound. */
    private static String currentScopeKey() {
        var t = ToolContext.taskRunId();
        if (t != null) return "task:" + t;
        var c = ToolContext.conversationId();
        return c != null ? "conv:" + c : null;
    }

    /** JCLAW-498: the outstanding async-child runIds for the current scope, for
     *  subagent_yield all=true. */
    public static List<Long> outstandingForCurrentScope() {
        var key = currentScopeKey();
        if (key == null) return List.of();
        var set = OUTSTANDING_BY_SCOPE.get(key);
        return set == null ? List.of() : new ArrayList<>(set);
    }

    private static void forgetOutstanding(Long runId) {
        for (var set : OUTSTANDING_BY_SCOPE.values()) set.remove(runId);
    }

    /**
     * JCLAW-497/498: dispatch a child on a VT (parallel) and stash a future that
     * completes with its terminal outcome for subagent_yield to block-await inline.
     * No announce / parent-resume — the parent collects via a blocking yield. Used
     * by the single async-in-task path and by batch fan-out (chat or task).
     */
    private static void dispatchDetachedAsync(Long runId, Long childAgentId, Long childConvId,
                                              String parentAgentName, String mode, String context,
                                              int timeoutSeconds, String task, String scopeKey) {
        var outcomeFuture = new CompletableFuture<SyncRunOutcome>();
        ASYNC_OUTCOMES.put(runId, outcomeFuture);
        if (scopeKey != null) {
            OUTSTANDING_BY_SCOPE.computeIfAbsent(scopeKey, _ -> ConcurrentHashMap.newKeySet())
                    .add(runId);
        }
        Thread.ofVirtual().name("subagent-async-task-" + runId).start(() ->
                runAsyncDetached(runId, childAgentId, childConvId, parentAgentName,
                        mode, context, timeoutSeconds, task, outcomeFuture));
    }

    /** JCLAW-497: single async spawn inside a task fire. Returns run_id immediately. */
    private static String launchAsyncSpawnForTask(Long runId, Long childAgentId, Long childConvId,
                                                   String parentAgentName, SpawnArgs parsed, String runIdStr) {
        dispatchDetachedAsync(runId, childAgentId, childConvId, parentAgentName,
                parsed.mode(), parsed.context(), parsed.timeoutSeconds(), parsed.task(), currentScopeKey());
        var payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_RUN_ID, runIdStr);
        payload.put(FIELD_CONVERSATION_ID, String.valueOf(childConvId));
        payload.put(FIELD_STATUS, SubagentRun.Status.RUNNING.name());
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    /**
     * JCLAW-497/498: run a detached async child to completion and hand its terminal
     * outcome to the awaiting subagent_yield. Mirrors {@link #runAsyncAndAnnounce}
     * minus the announce + parent-resume: run the child, do the terminal audit +
     * lifecycle bookkeeping, then complete {@code outcomeFuture}.
     */
    @SuppressWarnings("java:S1181")
    private static void runAsyncDetached(Long runId, Long childAgentId, Long childConvId,
                                         String parentAgentName, String mode, String context,
                                         int timeoutSeconds, String task,
                                         CompletableFuture<SyncRunOutcome> outcomeFuture) {
        try {
            var future = startAsyncChild(runId, childAgentId, childConvId, task);
            SyncRunOutcome outcome;
            try {
                outcome = awaitAsyncFuture(future, timeoutSeconds,
                        ConfigService.getInt(MAX_WALLCLOCK_KEY, DEFAULT_MAX_WALLCLOCK_SECONDS), runId);
            } finally {
                SubagentRegistry.unregister(runId);
            }
            if (!outcome.killedByOperator()) {
                persistAsyncTerminalRun(runId, outcome);
                emitTerminalEvent(parentAgentName, lookupAgentName(childAgentId), String.valueOf(runId),
                        mode, context, outcome.terminalStatus(), outcome.errorReason());
            }
            EventLogger.flush();
            outcomeFuture.complete(outcome);
        } catch (Throwable t) {
            outcomeFuture.completeExceptionally(t);
        }
    }

    /** Block-await one detached async outcome, bounded a little past the ceiling so
     *  it can't hang even if completion signalling misbehaves. */
    private static SyncRunOutcome awaitOutcomeFuture(CompletableFuture<SyncRunOutcome> f)
            throws InterruptedException, ExecutionException, TimeoutException {
        int ceiling = ConfigService.getInt(MAX_WALLCLOCK_KEY, DEFAULT_MAX_WALLCLOCK_SECONDS);
        return ceiling <= 0 ? f.get() : f.get(ceiling + 30L, TimeUnit.SECONDS);
    }

    private static LinkedHashMap<String, Object> outcomeMap(Long runId, SyncRunOutcome outcome) {
        var m = new LinkedHashMap<String, Object>();
        m.put(FIELD_RUN_ID, String.valueOf(runId));
        m.put(FIELD_REPLY, outcome.reply());
        m.put(FIELD_STATUS, outcome.terminalStatus().name());
        if (outcome.replyTruncated()) m.put(FIELD_TRUNCATED, Boolean.TRUE);
        return m;
    }

    /**
     * JCLAW-497: block-await ONE async subagent's outcome (subagent_yield with a
     * single runId) and return it as the same JSON a synchronous spawn returns.
     */
    public static String awaitAsyncOutcome(Long runId) {
        var f = ASYNC_OUTCOMES.remove(runId);
        forgetOutstanding(runId);
        if (f == null) {
            return "Error: no pending async subagent for runId " + runId
                    + " (already collected, or not an async spawn).";
        }
        try {
            return GsonHolder.INSTANCE.toJson(outcomeMap(runId, awaitOutcomeFuture(f)), Map.class);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "Error: interrupted while awaiting async subagent " + runId + ".";
        } catch (Exception e) {
            return "Error: failed awaiting async subagent " + runId + ": " + e.getMessage();
        }
    }

    /**
     * JCLAW-498: block-await MANY async subagents (subagent_yield runIds[] / all)
     * and return {@code {results:[...], count:n}}. Each entry mirrors a sync spawn's
     * shape; a missing/already-collected runId is reported in-line rather than
     * failing the whole batch. The children ran concurrently, so the wall-clock of
     * this collect ≈ the slowest still-running child.
     */
    public static String awaitAsyncOutcomes(List<Long> runIds) {
        var results = new ArrayList<Object>();
        for (var runId : runIds) {
            var f = ASYNC_OUTCOMES.remove(runId);
            forgetOutstanding(runId);
            if (f == null) {
                var miss = new LinkedHashMap<String, Object>();
                miss.put(FIELD_RUN_ID, String.valueOf(runId));
                miss.put(FIELD_STATUS, "UNKNOWN");
                miss.put(FIELD_ERROR, "no pending async subagent (already collected, or not an async spawn)");
                results.add(miss);
                continue;
            }
            try {
                results.add(outcomeMap(runId, awaitOutcomeFuture(f)));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                var err = new LinkedHashMap<String, Object>();
                err.put(FIELD_RUN_ID, String.valueOf(runId));
                err.put(FIELD_STATUS, "FAILED");
                err.put(FIELD_ERROR, "interrupted while awaiting");
                results.add(err);
            } catch (Exception e) {
                var err = new LinkedHashMap<String, Object>();
                err.put(FIELD_RUN_ID, String.valueOf(runId));
                err.put(FIELD_STATUS, "FAILED");
                err.put(FIELD_ERROR, e.getMessage());
                results.add(err);
            }
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("results", results);
        payload.put("count", results.size());
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
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
        var future = new CompletableFuture<AgentRunner.RunResult>();
        SubagentRegistry.register(runId, future);
        Thread.ofVirtual().name("subagent-" + runId).start(() -> {
            try {
                var childAgent = Tx.run(() -> (Agent) Agent.findById(childAgentId));
                var childConv = Tx.run(() -> (Conversation) Conversation.findById(childConvId));
                if (childAgent == null || childConv == null) {
                    throw new IllegalStateException(
                            "Subagent rows vanished before AgentRunner.run");
                }
                future.complete(executeChildRun(runId, childAgent, childConv, task, inlineMode));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            int ceilingSeconds = ConfigService.getInt(MAX_WALLCLOCK_KEY, DEFAULT_MAX_WALLCLOCK_SECONDS);
            return awaitFuture(future, timeoutSeconds, ceilingSeconds, runId);
        } finally {
            SubagentRegistry.unregister(runId);
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
            CompletableFuture<AgentRunner.RunResult> future,
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
                long idleNanos = SubagentRegistry.nanosSinceActivity(runId);
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
            } catch (CancellationException _) {
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
            CompletableFuture<AgentRunner.RunResult> future, Long runId, String reason) {
        SubagentRegistry.requestStop(runId);
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
                Message last = Message.find(
                        "conversation.id = ?1 and role = ?2 order by createdAt desc, id desc",
                        run.childConversation.id, MessageRole.ASSISTANT.value).first();
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
        if (cause instanceof RunCancelledException) {
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
        return enforceRecursionLimits(parentAgent, 1);
    }

    /** JCLAW-498: as above, but for spawning {@code additionalChildren} at once
     *  (batch fan-out) — the breadth check refuses when the running count plus the
     *  requested count would exceed the cap, so a single fan-out can't blow it. */
    static String enforceRecursionLimits(Agent parentAgent, int additionalChildren) {
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
        if (running + additionalChildren > breadthLimit) {
            return additionalChildren == 1
                    ? "breadth limit %d exceeded (running children: %d).".formatted(breadthLimit, running)
                    : "breadth limit %d exceeded: %d already running + %d requested.".formatted(
                            breadthLimit, running, additionalChildren);
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
            // JCLAW-500: bound the fresh clone's tool surface above by the
            // parent's restrictions before any inherit-mode widening below.
            copyParentToolRestrictions(parentAgent, childAgent);
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
            // JCLAW-500 (Change 3): a reused agent must not be MORE capable than
            // the spawning agent, or the spawn is a privilege escalation (a
            // confined parent naming the main / an unrestricted agent to run
            // with its tools or acp). Self-reuse and equal-or-narrower agents
            // pass.
            var escalation = capabilityEscalationError(parentAgent, existing);
            if (escalation != null) return ResolvedChildAgent.fail(escalation);
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
     * JCLAW-500 (Change 3): bound a reused (agentId) child above by the
     * spawning agent. A subagent must not be MORE capable than its parent, or
     * naming an agentId becomes a privilege-escalation hatch (a confined custom
     * agent reusing the main or an unrestricted agent to act with its tools /
     * acp). Enforces child_capabilities ⊆ parent_capabilities:
     * <ul>
     *   <li>every tool the parent has disabled must also be disabled on the
     *       named child (so child_enabled ⊆ parent_enabled);</li>
     *   <li>the child may use the acp runtime only if the parent may.</li>
     * </ul>
     * Shell privileges (global paths / allowlist bypass) are main-only, so an
     * {@code isMain} mismatch is already caught by the tool-subset check — the
     * main agent disables nothing a restricted parent does. Self-reuse and
     * equal-or-narrower agents pass. Returns an error string on escalation, or
     * null when the named agent is within bounds.
     */
    private static String capabilityEscalationError(Agent spawningAgent, Agent named) {
        if (named.id != null && named.id.equals(spawningAgent.id)) {
            return null; // self-reuse is always within bounds
        }
        var parentDisabled = ToolRegistry.loadDisabledTools(spawningAgent);
        var childDisabled = ToolRegistry.loadDisabledTools(named);
        if (!childDisabled.containsAll(parentDisabled)) {
            return ("Error: agentId %d ('%s') is more capable than the spawning agent '%s' "
                    + "(it enables tools the spawning agent has disabled); a subagent may not "
                    + "exceed its parent's tool capabilities.")
                    .formatted(named.id, named.name, spawningAgent.name);
        }
        boolean parentAcp = spawningAgent.isMain() || spawningAgent.acpAllowed;
        boolean childAcp = named.isMain() || named.acpAllowed;
        if (childAcp && !parentAcp) {
            return ("Error: agentId %d ('%s') may use the acp runtime but the spawning agent "
                    + "'%s' may not; a subagent may not exceed its parent's capabilities.")
                    .formatted(named.id, named.name, spawningAgent.name);
        }
        return null;
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
        if (!Objects.equals(resolved.provider(), childAgent.modelProvider)
                || !Objects.equals(resolved.modelId(), childAgent.modelId)) {
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
        var cfgProvider = ConfigService.get(CFG_SUBAGENT_PROVIDER);
        var cfgModel = ConfigService.get(CFG_SUBAGENT_MODEL);
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

    /**
     * JCLAW-500 (Change 1): copy the parent's explicit tool DENY-rows onto a
     * freshly-cloned child so the child's capability set is bounded above by
     * the parent's. {@link AgentService#create} seeds only the standard
     * non-main defaults (browser, jclaw_api, plus MCP-default-disabled); it
     * does not carry the parent's custom restrictions, so without this a child
     * cloned from a restricted custom agent reverts to the broader non-main
     * baseline and ends up MORE capable than its parent. We copy only the
     * parent's {@code enabled=false} rows (the restrictions) and leave the
     * child's own non-main defaults intact, giving
     * {@code child_disabled = parent_disabled ∪ non-main-default}.
     *
     * <p>Ordering: this runs before {@link #grantParentMcpGrants} and
     * {@link #unionParentToolGrants}. Those only ever widen the child toward
     * the parent's ENABLED set, and {@code unionParentToolGrants} explicitly
     * skips any tool in the parent's disabled set, so the deny-rows copied
     * here survive both. Scoped to freshly-cloned children — the {@code
     * agentId}-reuse path runs an existing agent that already carries its own
     * rows.
     */
    private static void copyParentToolRestrictions(Agent parentAgent, Agent childAgent) {
        var childByName = new HashMap<String, AgentToolConfig>();
        for (var r : AgentToolConfig.findByAgent(childAgent)) childByName.put(r.toolName, r);
        boolean any = false;
        for (var pr : AgentToolConfig.findByAgent(parentAgent)) {
            if (pr.enabled) continue;                       // copy restrictions only
            var existing = childByName.get(pr.toolName);
            if (existing == null) {
                var row = new AgentToolConfig();
                row.agent = childAgent;
                row.toolName = pr.toolName;
                row.enabled = false;
                row.save();
                any = true;
            } else if (existing.enabled) {
                existing.enabled = false;
                existing.save();
                any = true;
            }
        }
        if (any) ToolRegistry.invalidateDisabledToolsCache(childAgent);
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
            SubagentRegistry.unregister(runId);
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
        SubagentRegistry.register(runId, future);
        Thread.ofVirtual().name("subagent-async-runner-" + runId).start(() -> {
            try {
                var childAgent = Tx.run(() -> (Agent) Agent.findById(childAgentId));
                var childConv = Tx.run(() -> (Conversation) Conversation.findById(childConvId));
                if (childAgent == null || childConv == null) {
                    throw new IllegalStateException(
                            "Subagent rows vanished before AgentRunner.run");
                }
                future.complete(executeChildRun(runId, childAgent, childConv, task, false));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** JCLAW-499: the per-spawn external-harness command for a run, set when
     *  runtime=acp, consumed once by {@link #executeChildRun}. */
    private static final ConcurrentHashMap<Long, List<String>>
            ACP_RUNS = new ConcurrentHashMap<>();

    /**
     * JCLAW-661: Rail A bridge — the live streaming callbacks (if any) of a chat
     * turn watching a run, keyed by runId and consulted by
     * {@link #dispatchHarnessEvent}. Absent (so {@link #callbacksFor} returns null)
     * whenever no chat SSE is open for the run, in which case only Rail B (bus +
     * transcript persist) fires. Populated from {@link #CHAT_CALLBACKS} at spawn
     * time — see {@link #bindChatCallbacksToRun}.
     */
    private static final ConcurrentHashMap<Long, AgentRunner.StreamingCallbacks>
            RUN_CALLBACKS = new ConcurrentHashMap<>();

    /**
     * JCLAW-661: the open chat turn's streaming callbacks, keyed by conversation
     * id. {@link controllers.ApiChatController#streamChat} registers the current
     * turn here so a coding run spawned mid-turn can promote them onto its runId —
     * the conversation is the only id the freshly-minted run and the chat turn
     * both know (the runId does not exist yet when the turn opens).
     */
    private static final ConcurrentHashMap<Long, AgentRunner.StreamingCallbacks>
            CHAT_CALLBACKS = new ConcurrentHashMap<>();

    /** JCLAW-661: register the open chat turn's streaming callbacks under its
     *  conversation id so a coding run spawned during the turn can pick them up.
     *  Null-safe on both args — a closed tab simply never registers. */
    public static void registerChatCallbacks(Long conversationId, AgentRunner.StreamingCallbacks cb) {
        if (conversationId == null || cb == null) return;
        CHAT_CALLBACKS.put(conversationId, cb);
    }

    /** JCLAW-661: drop the chat-turn callbacks for a conversation (turn closed). */
    public static void unregisterChatCallbacks(Long conversationId) {
        if (conversationId == null) return;
        CHAT_CALLBACKS.remove(conversationId);
    }

    /** JCLAW-661: the live streaming callbacks bound to {@code runId}, or null when
     *  no chat turn is watching it (Rail A off; Rail B still fires). */
    static AgentRunner.StreamingCallbacks callbacksFor(Long runId) {
        return runId == null ? null : RUN_CALLBACKS.get(runId);
    }

    /** JCLAW-661: promote the chat SSE bound to the current tool dispatch's
     *  conversation onto {@code runId} so the run's harness events reach the open
     *  turn. No-op outside a chat dispatch or when no tab is watching. */
    private static void bindChatCallbacksToRun(Long runId) {
        var conversationId = ToolContext.conversationId();
        if (conversationId == null) return;
        var cb = CHAT_CALLBACKS.get(conversationId);
        if (cb != null) RUN_CALLBACKS.put(runId, cb);
    }

    /** JCLAW-499: config key for the operator-configured external agent harness
     *  command (e.g. {@code claude -p} or {@code codex exec}). The command is NOT
     *  model-supplied — runtime=acp runs this configured command, so a subagent
     *  can't be steered into executing arbitrary shell. */
    public static final String ACP_COMMAND_KEY = "subagent.acp.command";
    /** JCLAW-670: operator override for the adapter's default permission flags
     *  (whitespace-split; literal "none" = no flags at all). */
    public static final String ACP_PERMISSION_ARGS_KEY = "subagent.acp.permissionArgs";
    private static final int ACP_MAX_OUTPUT_BYTES = 400_000;

    /** JCLAW-659: which harness adapter drives the acp runtime — {@code pi},
     *  {@code claude}, {@code codex}, or {@code generic}. Operator-configured
     *  (Config DB), not model-supplied; selects the {@link HarnessAdapter} that
     *  knows how to launch and parse that CLI. */
    public static final String ACP_HARNESS_KEY = "subagent.acp.harness";
    /** JCLAW-659: how the acp runtime talks to the harness — {@code batch}
     *  (one-shot task-in / output-out, the current behavior and default),
     *  {@code json} (parse a streamed line protocol into {@link HarnessEvent}s),
     *  or {@code rpc} (a bidirectional session). Operator-configured. */
    public static final String ACP_MODE_KEY = "subagent.acp.mode";
    /** JCLAW-659: default harness id when {@link #ACP_HARNESS_KEY} is unset. */
    public static final String DEFAULT_ACP_HARNESS = "generic";
    /** JCLAW-659: default mode when {@link #ACP_MODE_KEY} is unset. */
    public static final String DEFAULT_ACP_MODE = "batch";

    /** JCLAW-657 (finding A): working directory the acp harness process runs in.
     *  Operator-configured (Config DB); when unset, defaults to the child agent's
     *  own workspace so a real harness — whose file writes are OUTSIDE JClaw's
     *  tool/workspace confinement — is scoped there rather than the backend's CWD
     *  (the repo root). */
    public static final String ACP_WORKDIR_KEY = "subagent.acp.workdir";

    private static final Set<String> ACP_HARNESS_IDS = Set.of("pi", "claude", "codex", DEFAULT_ACP_HARNESS);
    private static final Set<String> ACP_MODES = Set.of(DEFAULT_ACP_MODE, "json", "rpc");

    /** JCLAW-659: harness-id → adapter registry. JCLAW-660 seeds the {@code pi}
     *  (streaming JSONL) and {@code generic} (line-tail) adapters; JCLAW-667 adds
     *  the {@code claude} (streaming NDJSON) adapter; the {@code codex} adapter
     *  lands in a later JCLAW-657 story. */
    private static final Map<String, HarnessAdapter> HARNESS_ADAPTERS = new ConcurrentHashMap<>();

    static {
        registerAdapter("pi", new PiAdapter());
        registerAdapter("claude", new ClaudeAdapter());
        registerAdapter("generic", new GenericAdapter());
    }

    /** JCLAW-659: register a harness adapter under a harness id (see
     *  {@link #ACP_HARNESS_IDS}). Called by later stories' adapter classes. */
    static void registerAdapter(String id, HarnessAdapter adapter) {
        HARNESS_ADAPTERS.put(id, adapter);
    }

    /**
     * JCLAW-499: run the child body — the configured external harness
     * ({@code runtime:"acp"}) when an ACP command was registered for this run,
     * otherwise the native {@link AgentRunner}. Shared by the sync and detached
     * dispatch paths so ACP composes with sync, async, and batch fan-out.
     */
    /**
     * JCLAW-669: a coding-harness run whose ORIGIN is an unsafe channel (anything
     * but web chat) must be operator-approved before the process launches — an
     * inbound Telegram/Slack message can prompt-inject an agent into spawning
     * arbitrary code execution. Web spawns pass untouched (the pi -p / claude -p
     * uninterrupted contract); Telegram/Slack route through DangerousActionGate;
     * other channels follow tool.approval.offChannelPolicy. Throws on denial.
     */
    private static void enforceChannelApproval(Long runId, Agent childAgent, String task) {
        var originChannel = parentChannelType(runId);
        if (originChannel == null || "web".equals(originChannel)) return;
        var decision = agents.DangerousActionGate.guardHarnessPermission(
                childAgent, parentConversationId(runId), "coding_harness_run", task);
        var approved = decision == agents.DangerousActionGate.Decision.PROCEED;
        dispatchHarnessEvent(runId, new HarnessEvent(
                approved ? HarnessEvent.STEP : HarnessEvent.ERROR,
                "channel approval (%s): coding run %s".formatted(
                        originChannel, approved ? "approved" : "denied"),
                null), 0);
        if (!approved) {
            throw new IllegalStateException(
                    "coding harness run denied: origin channel '%s' requires operator "
                            + "approval and it was not granted".formatted(originChannel));
        }
    }

    private static AgentRunner.RunResult executeChildRun(Long runId, Agent childAgent,
                                                         Conversation childConv, String task, boolean inlineMode) {
        var acpCommand = ACP_RUNS.remove(runId);
        if (acpCommand != null) {
            // JCLAW-669: a coding-harness run whose ORIGIN is an unsafe channel
            // (anything but the operator's web chat) must be operator-approved
            // before the process launches — an inbound Telegram/Slack message
            // can prompt-inject an agent into spawning arbitrary code
            // execution. Web spawns pass untouched (the pi -p / claude -p
            // uninterrupted contract); Telegram/Slack route through the
            // existing DangerousActionGate approval prompt; other channels
            // follow tool.approval.offChannelPolicy.
            enforceChannelApproval(runId, childAgent, task);
            // JCLAW-657 (finding A): scope the harness's cwd to a configured
            // workdir or the child agent's workspace instead of the backend's
            // CWD. This ORGANIZES output; it does not confine the process —
            // containment is JCLAW-669/670/671 (channel gating, permission
            // flags, sandboxing). acpAllowed is the security boundary today.
            var workdir = resolveAcpWorkdir(childAgent, task);
            recordWorkdir(runId, workdir);
            // JCLAW-660: batch stays one-shot; json/rpc stream the harness output
            // line-by-line through the selected adapter.
            var mode = resolveAcpMode();
            if ("batch".equals(mode)) {
                return runAcpBatch(runId, acpCommand, task, workdir);
            }
            var adapter = resolveAdapter();
            if (adapter == null) {
                // No adapter registered for the configured harness — degrade to the
                // one-shot batch path rather than fail the run.
                return runAcpBatch(runId, acpCommand, task, workdir);
            }
            // JCLAW-665: rpc mode against a harness that advertises a bidirectional
            // session routes the harness's mid-run permission requests through the
            // operator approval gate (decision written back on stdin). Strictly
            // capability-gated: any non-bidirectional harness — or json mode — falls
            // back to one-way streaming.
            if ("rpc".equals(mode) && adapter.capabilities().bidirectional()) {
                return runAcpRpc(runId, acpCommand, task, adapter, childAgent, workdir);
            }
            return runAcpStreaming(acpCommand, task, runId, adapter, workdir);
        }
        if (inlineMode) {
            // JCLAW-267: inline runs in the parent Conversation (queue owned), with
            // a ThreadLocal marker stamping every Message AgentRunner persists.
            return ConversationService.withSubagentRunIdMarker(runId,
                    () -> AgentRunner.runWithOwnedQueue(childAgent, childConv, task));
        }
        return AgentRunner.run(childAgent, childConv, task);
    }

    /**
     * JCLAW-657 (finding A): resolve the directory the acp harness process runs
     * in. An operator-set {@link #ACP_WORKDIR_KEY} wins; otherwise the child
     * agent's own workspace, so a real coding harness (whose file writes are
     * outside JClaw's tool confinement) is scoped there rather than the backend's
     * CWD. Returns {@code null} — inherit the server CWD — only when neither is
     * resolvable or the directory can't be created.
     */
    private static File resolveAcpWorkdir(Agent childAgent, String task) {
        var configured = ConfigService.get(ACP_WORKDIR_KEY, null);
        Path dir;
        if (configured != null && !configured.isBlank()) {
            dir = Path.of(configured.strip());
        } else if (childAgent != null && childAgent.name != null) {
            // JCLAW-666: each coding session gets its own directory under the
            // agent workspace's coding/ parent, named from the task ("create
            // fibonacci program" -> coding/create-fibonacci-program/). An
            // existing directory is never reused — collisions get -2, -3, …
            // so consecutive sessions' artifacts don't interleave.
            var base = AgentService.workspacePath(childAgent.name).resolve("coding");
            var slug = codingSlug(task);
            dir = base.resolve(slug);
            for (int n = 2; Files.exists(dir); n++) {
                dir = base.resolve(slug + "-" + n);
            }
        } else {
            return null;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            EventLogger.warn(SUBAGENT_CHANNEL, null, null,
                    "acp workdir '%s' could not be created (%s); harness inherits the server CWD"
                            .formatted(dir, e.getMessage()));
            return null;
        }
        return dir.toFile();
    }

    /**
     * JCLAW-670: append the permission flags — the operator's
     * {@link #ACP_PERMISSION_ARGS_KEY} when set ({@code none} = none at all),
     * the adapter's conservative {@code defaultPermissionArgs()} otherwise.
     */
    public static List<String> withPermissionArgs(HarnessAdapter adapter, List<String> argv) {
        var configured = ConfigService.get(ACP_PERMISSION_ARGS_KEY, null);
        List<String> extra;
        if (configured != null && !configured.isBlank()) {
            extra = "none".equalsIgnoreCase(configured.strip())
                    ? List.of()
                    : List.of(configured.strip().split("\\s+"));
        } else {
            extra = adapter.defaultPermissionArgs();
        }
        if (extra.isEmpty()) return argv;
        var out = new java.util.ArrayList<String>(argv.size() + extra.size());
        out.addAll(argv);
        out.addAll(extra);
        return List.copyOf(out);
    }

    /** JCLAW-666: deterministic filename-safe session slug from the task text —
     *  lowercased, non-alphanumerics collapsed to single dashes, bounded, with
     *  a fixed fallback for tasks that normalise to nothing. */
    public static String codingSlug(String task) {
        if (task == null) return "session";
        var slug = task.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(?:^-++|-++$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-++$", "");
        }
        return slug.isEmpty() ? "session" : slug;
    }

    /** JCLAW-666: persist the resolved session directory on the run row so the
     *  operator and the CodingRunMonitor can find the coding artifacts (they
     *  live in the workspace, not in MessageAttachment). Best-effort. */
    private static void recordWorkdir(Long runId, File workdir) {
        if (runId == null || workdir == null) return;
        try {
            Tx.run(() -> {
                SubagentRun run = SubagentRun.findById(runId);
                if (run != null) {
                    run.workdir = workdir.getAbsolutePath();
                    run.save();
                }
                return null;
            });
        } catch (RuntimeException e) {
            EventLogger.warn("subagent", null, null,
                    "could not record acp workdir on run %d: %s".formatted(runId, e.getMessage()));
        }
    }

    /**
     * JCLAW-499: run an external agent harness (Codex / Claude / Gemini CLI, …)
     * one-shot ({@code subagent.acp.mode=batch}): launch the operator-configured
     * command, deliver {@code task} on stdin, and capture stdout (bounded) as the
     * child reply. Bounded by the wall-clock ceiling — a runaway harness is
     * force-killed. A non-zero exit raises with the harness's stderr so the spawn
     * records a FAILED outcome.
     */
    private static AgentRunner.RunResult runAcpBatch(Long runId, List<String> command, String task,
                                                     File workdir) {
        Process proc = null;
        try {
            // JCLAW-672: batch mode has no streaming adapter; sandbox with the
            // generic (no HOME allowances) profile when enabled.
            var launched = HarnessSandbox.wrap(command, workdir, new GenericAdapter());
            var pb = new ProcessBuilder(launched);
            if (workdir != null) pb.directory(workdir);
            proc = pb.start();
            // JCLAW-664: track the live process so SubagentRegistry.kill (and the
            // idle/ceiling timeout via requestStop) can force-terminate it and its
            // descendants instead of leaving it orphaned.
            SubagentRegistry.registerProcess(runId, proc);
            try (var stdin = proc.getOutputStream()) {
                stdin.write(task.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            var out = drainAsync(proc.getInputStream());
            var err = drainAsync(proc.getErrorStream());
            int ceiling = ConfigService.getInt(MAX_WALLCLOCK_KEY, DEFAULT_MAX_WALLCLOCK_SECONDS);
            boolean done;
            if (ceiling <= 0) {
                proc.waitFor();   // no ceiling configured — wait until the harness exits
                done = true;
            } else {
                done = proc.waitFor(ceiling, TimeUnit.SECONDS);
            }
            if (!done) {
                proc.destroyForcibly();
                throw new IllegalStateException("ACP harness exceeded the %ds ceiling and was killed.".formatted(ceiling));
            }
            int exit = proc.exitValue();
            String stdout = out.get(10, TimeUnit.SECONDS);
            if (exit != 0) {
                String stderr = err.get(10, TimeUnit.SECONDS);
                String detail;
                if (!stderr.isBlank()) {
                    detail = stderr.strip();
                } else if (!stdout.isBlank()) {
                    detail = stdout.strip();
                } else {
                    detail = NO_OUTPUT;
                }
                throw new IllegalStateException(ACP_EXIT_MSG.formatted(exit, detail));
            }
            return new AgentRunner.RunResult(stdout.strip(), null);
        } catch (InterruptedException e) {
            // proc is non-null here: InterruptedException only comes from waitFor(),
            // which runs after ProcessBuilder.start() above has already succeeded.
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted awaiting the ACP harness.", e);
        } catch (IOException | ExecutionException | TimeoutException e) {
            if (proc != null) proc.destroyForcibly();
            throw new IllegalStateException("ACP harness failed: " + e.getMessage(), e);
        } finally {
            STDIN_WRITE_LOCKS.remove(runId);
            SubagentRegistry.unregisterProcess(runId);
        }
    }

    /**
     * JCLAW-660: run an external harness in streaming mode ({@code
     * subagent.acp.mode=json|rpc}). Launch the argv the {@code adapter} builds,
     * deliver {@code task} on stdin when the adapter left it out of the argv, then
     * read stdout LINE BY LINE — each line is parsed into a {@link HarnessEvent}
     * and fanned out via {@link #dispatchHarnessEvent} while the reply text
     * accumulates. Same guardrails as {@link #runAcpBatch}: the {@link
     * #ACP_MAX_OUTPUT_BYTES} output cap, the wall-clock ceiling, and {@code
     * destroyForcibly} on either overrun. A non-zero exit raises with the
     * harness's stderr so the spawn records a FAILED outcome.
     */
    private static AgentRunner.RunResult runAcpStreaming(List<String> command, String task,
                                                         Long runId, HarnessAdapter adapter, File workdir) {
        var argv = withPermissionArgs(adapter, adapter.launchArgs(command, task));
        // The adapter delivers the task on stdin unless it placed it in the argv.
        boolean taskOnStdin = !argv.contains(task);
        Process proc = null;
        try {
            var pb = new ProcessBuilder(HarnessSandbox.wrap(argv, workdir, adapter));
            if (workdir != null) pb.directory(workdir);
            proc = pb.start();
            // JCLAW-664: track the live process so SubagentRegistry.kill and the
            // idle/ceiling timeout (via requestStop) can force-terminate it and
            // its descendants — the harness has no cooperative checkpoint.
            SubagentRegistry.registerProcess(runId, proc);
            try (var stdin = proc.getOutputStream()) {
                if (taskOnStdin) {
                    stdin.write(task.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }
            }
            var err = drainAsync(proc.getErrorStream());
            var reply = streamStdout(proc, runId, adapter, null);
            // JCLAW-664: the idle + wall-clock budgets are enforced by the outer
            // awaitFuture — each streamed line resets the idle clock (touch(), see
            // streamStdout), and on idle/ceiling expiry stopChildOnTimeout calls
            // requestStop, which destroys this process and records TIMEOUT with the
            // partial transcript. So wait unbounded here: a timeout or a kill
            // destroys the process, which unblocks this waitFor.
            proc.waitFor();
            int exit = proc.exitValue();
            String stdout = reply.get(10, TimeUnit.SECONDS);
            if (exit != 0) {
                String stderr = err.get(10, TimeUnit.SECONDS);
                String detail;
                if (!stderr.isBlank()) {
                    detail = stderr.strip();
                } else if (!stdout.isBlank()) {
                    detail = stdout.strip();
                } else {
                    detail = NO_OUTPUT;
                }
                throw new IllegalStateException(ACP_EXIT_MSG.formatted(exit, detail));
            }
            return new AgentRunner.RunResult(stdout.strip(), null);
        } catch (InterruptedException e) {
            // proc is non-null here: InterruptedException only comes from waitFor(),
            // which runs after ProcessBuilder.start() above has already succeeded.
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted awaiting the ACP harness.", e);
        } catch (IOException | ExecutionException | TimeoutException e) {
            if (proc != null) proc.destroyForcibly();
            throw new IllegalStateException("ACP harness failed: " + e.getMessage(), e);
        } finally {
            SubagentRegistry.unregisterProcess(runId);
        }
    }

    /**
     * JCLAW-665: run an external harness in bidirectional rpc mode ({@code
     * subagent.acp.mode=rpc}) against an adapter that advertises {@code
     * capabilities().bidirectional()}. Same launch + line-streaming machinery as
     * {@link #runAcpStreaming}, with one addition: stdin is kept OPEN for the whole
     * run, and each parsed line is inspected for a permission-request frame (see
     * {@link #detectPermission}). A detected request is routed through {@link
     * DangerousActionGate#guardHarnessPermission} for operator approval and the
     * approve/deny decision is written back to the harness on stdin — a denial
     * cleanly aborts just that action, leaving the run itself alive (no {@code
     * destroyForcibly}). Non-bidirectional harnesses never reach here; {@link
     * #executeChildRun} falls them back to one-way {@link #runAcpStreaming}.
     */
    private static AgentRunner.RunResult runAcpRpc(Long runId, List<String> command, String task,
                                                   HarnessAdapter adapter, Agent childAgent, File workdir) {
        var argv = withPermissionArgs(adapter, adapter.launchArgs(command, task));
        boolean taskOnStdin = !argv.contains(task);
        // Route approval prompts to the PARENT conversation — the child's own
        // conversation is channelType="subagent" with no approval surface, so a
        // Telegram/Slack prompt must reach the operator where they're watching.
        var conversationId = parentConversationId(runId);
        Process proc = null;
        OutputStream stdin = null;
        try {
            var pb = new ProcessBuilder(HarnessSandbox.wrap(argv, workdir, adapter));
            if (workdir != null) pb.directory(workdir);
            proc = pb.start();
            // JCLAW-664: track the live process so the kill / idle-timeout paths can
            // force-terminate it and its descendants.
            SubagentRegistry.registerProcess(runId, proc);
            // JCLAW-665: unlike batch/streaming (which close stdin right after the
            // task), rpc keeps it OPEN so permission decisions can be written back
            // mid-run. Not a try-with-resources — closed in the finally.
            stdin = proc.getOutputStream();
            if (taskOnStdin) {
                stdin.write((task + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            var err = drainAsync(proc.getErrorStream());
            final var stdinRef = stdin;
            var reply = streamStdout(proc, runId, adapter,
                    ev -> arbitratePermission(ev, stdinRef, runId, childAgent, conversationId));
            // See runAcpStreaming: the outer awaitFuture enforces the idle/ceiling
            // budgets and force-kills on expiry, which unblocks this waitFor.
            proc.waitFor();
            int exit = proc.exitValue();
            String stdout = reply.get(10, TimeUnit.SECONDS);
            if (exit != 0) {
                String stderr = err.get(10, TimeUnit.SECONDS);
                String detail;
                if (!stderr.isBlank()) {
                    detail = stderr.strip();
                } else if (!stdout.isBlank()) {
                    detail = stdout.strip();
                } else {
                    detail = NO_OUTPUT;
                }
                throw new IllegalStateException(ACP_EXIT_MSG.formatted(exit, detail));
            }
            return new AgentRunner.RunResult(stdout.strip(), null);
        } catch (InterruptedException e) {
            // proc is non-null here: InterruptedException only comes from waitFor(),
            // which runs after ProcessBuilder.start() above has already succeeded.
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted awaiting the ACP harness.", e);
        } catch (IOException | ExecutionException | TimeoutException e) {
            if (proc != null) proc.destroyForcibly();
            throw new IllegalStateException("ACP harness failed: " + e.getMessage(), e);
        } finally {
            closeQuietly(stdin);
            SubagentRegistry.unregisterProcess(runId);
        }
    }

    /** JCLAW-665: the operator-facing (parent) conversation id for a run, used to
     *  route a harness permission prompt to a channel the operator is watching.
     *  Null when the run or its parent conversation can't be resolved.
     *
     *  <p>JCLAW-669: the channelType of the run's operator-facing conversation,
     *  or null when the run has no parent-conversation context. */
    private static String parentChannelType(Long runId) {
        if (runId == null) return null;
        return Tx.run(() -> {
            SubagentRun run = SubagentRun.findById(runId);
            return run != null && run.parentConversation != null
                    ? run.parentConversation.channelType : null;
        });
    }

    private static Long parentConversationId(Long runId) {
        if (runId == null) return null;
        return Tx.run(() -> {
            var run = (SubagentRun) SubagentRun.findById(runId);
            return run != null && run.parentConversation != null ? run.parentConversation.id : null;
        });
    }

    /** JCLAW-665: a permission request parsed out of an rpc harness event. */
    private record HarnessPermission(String id, String toolName, String argsJson) {}

    /** JCLAW-665: discriminator values (across a harness event's type-ish fields)
     *  that mark a line as a permission request. Tolerant of the several shapes
     *  different harnesses use — the exact protocol is not pinned. */
    private static final Set<String> PERMISSION_EVENT_TYPES = Set.of(
            "permission_request", "permission", "request_permission", "requestpermission",
            "can_use_tool", "tool_permission", "approval_request", "approval");

    /**
     * JCLAW-665: handle one streamed rpc event. Non-permission events are ignored
     * here (they flow through the normal reply/rails path in {@link #streamStdout});
     * a permission request is routed to {@link DangerousActionGate#guardHarnessPermission}
     * and the decision is written back to the harness on {@code stdin}. Runs on the
     * stdout-reader VT — which touches the DB and must never be interrupted — so a
     * routing failure is caught and fails CLOSED (deny) rather than propagating.
     */
    private static void arbitratePermission(HarnessEvent ev, OutputStream stdin, Long runId,
                                            Agent childAgent, Long conversationId) {
        var perm = detectPermission(ev);
        if (perm == null) return;
        // Operator deliberation is not harness inactivity — reset the idle clock so
        // the inactivity budget doesn't force-kill the run while it awaits a tap.
        SubagentRegistry.touch(runId);
        boolean approved;
        try {
            approved = DangerousActionGate.guardHarnessPermission(
                    childAgent, conversationId, perm.toolName(), perm.argsJson())
                    == DangerousActionGate.Decision.PROCEED;
        } catch (RuntimeException e) {
            approved = false;
            EventLogger.warn("subagent", childAgent == null ? null : childAgent.name, null,
                    "Harness permission routing failed for run %s — denying: %s"
                            .formatted(runId, e.getMessage()));
        }
        writePermissionDecision(stdin, perm, approved, runId);
    }

    /**
     * JCLAW-665: recognize a harness permission-request frame in a parsed event and
     * extract what the approval gate needs — a correlation id, the tool name, and a
     * JSON args blob. Returns {@code null} for any event that is not a permission
     * request (the common case), so the rpc reader treats it as ordinary streamed
     * output. Best-effort and tolerant: the on-the-wire shape varies by harness, so
     * we probe a handful of conventional field names and never throw.
     */
    private static HarnessPermission detectPermission(HarnessEvent ev) {
        var raw = ev == null ? null : ev.raw();
        if (raw == null) return null;
        var discriminator = firstJsonString(raw, "type", "kind", "event", "method", "subtype");
        if (discriminator == null
                || !PERMISSION_EVENT_TYPES.contains(discriminator.strip().toLowerCase())) {
            return null;
        }
        // The tool/args may sit at the top level or under a nested request envelope.
        var payload = raw;
        for (var envelope : List.of("params", "request", "permission", "input", "data")) {
            var nested = raw.get(envelope);
            if (nested != null && nested.isJsonObject()) {
                payload = nested.getAsJsonObject();
                break;
            }
        }
        var id = firstJsonString(raw, "id", "request_id", "requestId", "permissionId",
                "permission_id", "tool_call_id", "toolCallId");
        if (id == null) {
            id = firstJsonString(payload, "id", "request_id", "requestId", "tool_call_id");
        }
        var toolName = firstJsonString(payload, "tool", "tool_name", "toolName", "name");
        if (toolName == null) {
            toolName = firstJsonString(raw, "tool", "tool_name", "toolName", "name");
        }
        if (toolName == null) toolName = "(harness action)";
        var argsEl = firstJsonMember(payload, "input", "arguments", "args", "params");
        var argsJson = argsEl != null ? argsEl.toString() : payload.toString();
        return new HarnessPermission(id, toolName, argsJson);
    }

    /**
     * JCLAW-665: relay an approve/deny decision back to the harness on stdin as a
     * one-line JSON frame. A denial lets the harness abort just that action and keep
     * running (we never kill the process here). Best-effort: a write failure (harness
     * gone / stdin closed) is logged and swallowed — the reader VT must not be
     * interrupted. Writes are serialized on a per-run lock so a decision frame is
     * never interleaved with another writer.
     */
    private static void writePermissionDecision(OutputStream stdin, HarnessPermission perm,
                                                boolean approved, Long runId) {
        var resp = new LinkedHashMap<String, Object>();
        resp.put("type", "permission_response");
        if (perm.id() != null) resp.put("id", perm.id());
        resp.put("decision", approved ? "allow" : "deny");
        resp.put("approved", approved);
        var line = GsonHolder.INSTANCE.toJson(resp, Map.class) + "\n";
        try {
            synchronized (STDIN_WRITE_LOCKS.computeIfAbsent(runId, _ -> new Object())) {
                stdin.write(line.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        } catch (IOException e) {
            EventLogger.warn("subagent", null, null,
                    "Failed to write permission decision for run %s: %s"
                            .formatted(runId, e.getMessage()));
        }
    }

    /** JCLAW-665: close the harness stdin, swallowing any error — the run's terminal
     *  outcome is already decided by the time we tear the pipe down. */
    private static void closeQuietly(OutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException _) {
            // best-effort teardown
        }
    }

    /** First present, non-null primitive field among {@code keys}, as a string. */
    private static String firstJsonString(JsonObject obj, String... keys) {
        for (var key : keys) {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonPrimitive()) {
                return el.getAsString();
            }
        }
        return null;
    }

    /** First present object/array member among {@code keys}, or null. */
    private static JsonElement firstJsonMember(JsonObject obj, String... keys) {
        for (var key : keys) {
            JsonElement el = obj.get(key);
            if (el != null && (el.isJsonObject() || el.isJsonArray())) {
                return el;
            }
        }
        return null;
    }

    /**
     * JCLAW-660: read harness stdout line-by-line on a VT — parse each line into a
     * {@link HarnessEvent}, dispatch it, and accumulate the reply. The process is
     * force-killed (never {@link Thread#interrupt interrupted}, since the reader
     * may touch the DB via {@link #dispatchHarnessEvent}) once cumulative output
     * crosses {@link #ACP_MAX_OUTPUT_BYTES}. The reply prefers a {@code result}
     * event, else concatenated {@code token} text, else newline-joined {@code
     * step} lines (the generic line-tail case).
     */
    /** JCLAW-660: folds a harness event stream into a single reply — a RESULT
     *  frame wins, else concatenated TOKEN output, else the STEP log. */
    private static final class ReplyAccumulator {
        private final StringBuilder tokens = new StringBuilder();
        private final StringBuilder steps = new StringBuilder();
        private String resultText;

        void fold(HarnessEvent ev) {
            switch (ev.kind()) {
                case HarnessEvent.TOKEN -> tokens.append(ev.text());
                case HarnessEvent.RESULT -> resultText = ev.text();
                case HarnessEvent.STEP -> {
                    if (!steps.isEmpty()) steps.append('\n');
                    steps.append(ev.text());
                }
                default -> { /* tool_call / error dispatched but not part of the reply */ }
            }
        }

        String reply() {
            if (resultText != null && !resultText.isBlank()) return resultText;
            if (!tokens.isEmpty()) return tokens.toString();
            return steps.toString();
        }
    }

    private static CompletableFuture<String> streamStdout(Process proc, Long runId,
                                                          HarnessAdapter adapter,
                                                          Consumer<HarnessEvent> permissionArbiter) {
        var f = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            var acc = new ReplyAccumulator();
            long bytes = 0;
            int seq = 1;   // seq 0 is the JCLAW-669 channel-approval step (when gated)
            try (var reader = proc.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // JCLAW-664: each streamed line is activity — reset the idle clock.
                    SubagentRegistry.touch(runId);
                    bytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                    var ev = adapter.parse(line);
                    // A null event is the adapter dropping a noise/duplicate line.
                    if (ev != null) {
                        // JCLAW-665: rpc permission requests route to the gate first.
                        if (permissionArbiter != null) permissionArbiter.accept(ev);
                        dispatchHarnessEvent(runId, ev, seq++);
                        acc.fold(ev);
                    }
                    if (bytes >= ACP_MAX_OUTPUT_BYTES) {
                        proc.destroyForcibly();
                        break;
                    }
                }
            } catch (IOException _) {
                // EOF, force-kill, or overrun closed the stream — complete with what we have.
            }
            String reply = acc.reply();
            // JCLAW-662: the harness output stream has ended — signal terminal once
            // per run (every adapter) so a live monitor stops tailing and, on
            // reconnect, falls back to the persisted transcript.
            var done = new LinkedHashMap<String, Object>();
            done.put(BUS_RUN_ID, runId);
            done.put("seq", seq);
            done.put(FIELD_REPLY, reply);
            NotificationBus.publish(NotificationBus.BUS_CODINGRUN_DONE, done);
            f.complete(reply);
        });
        return f;
    }

    /**
     * JCLAW-660/662: fan a parsed {@link HarnessEvent} out to the run's rails —
     * (a) publish it live on the {@link NotificationBus} so a connected monitor
     * sees it immediately, and (b) persist it as a child-Conversation
     * {@link Message} row so a client that reconnects mid-run can replay the
     * steps it missed via {@link controllers.ApiSubagentRunsController#steps}.
     */
    private static void dispatchHarnessEvent(Long runId, HarnessEvent ev, int seq) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(BUS_RUN_ID, runId);
        payload.put("seq", seq);
        payload.put("kind", ev.kind());
        payload.put("text", ev.text());
        NotificationBus.publish(NotificationBus.BUS_CODINGRUN_STEP, payload);
        persistHarnessStep(runId, seq, ev);
        streamToChat(runId, ev);
    }

    /**
     * JCLAW-661: Rail A — when a chat turn is watching this run, map the harness
     * event onto its live SSE callbacks: {@code token} → onToken, {@code tool_call}
     * → onToolCall, everything else (step / error / result) → onStatus. Never fires
     * onComplete/onError — those belong to the chat turn's own lifecycle, not the
     * run's. Best-effort: a write to an already-closed SSE throws, and swallowing it
     * here keeps the harness reader VT (which touches the DB and must never be
     * interrupted) and Rail B intact; the turn's own onComplete/onError unregisters
     * the callbacks.
     */
    private static void streamToChat(Long runId, HarnessEvent ev) {
        var cb = callbacksFor(runId);
        if (cb == null) return;
        try {
            switch (ev.kind()) {
                case HarnessEvent.TOKEN -> cb.onToken().accept(ev.text());
                case HarnessEvent.TOOL_CALL -> cb.onToolCall().accept(toToolCallEvent(ev));
                default -> cb.onStatus().accept(ev.text());
            }
        } catch (RuntimeException _) {
            // Chat SSE closed mid-run — drop Rail A for this event; Rail B already fired.
        }
    }

    /** JCLAW-661: adapt a harness {@code tool_call} event to the chat SSE's
     *  {@link AgentRunner.ToolCallEvent} shape — name from the event text, the raw
     *  JSON frame (when the line was JSON) as arguments; no result payload. */
    private static AgentRunner.ToolCallEvent toToolCallEvent(HarnessEvent ev) {
        var arguments = ev.raw() == null ? "" : ev.raw().toString();
        return new AgentRunner.ToolCallEvent(null, ev.text(), "harness", arguments, "", null, null);
    }

    /**
     * JCLAW-662: persist one harness step as a Message row on the run's child
     * Conversation (short {@link Tx}) so the transcript survives a monitor
     * disconnect. Best-effort — a transcript-write failure is logged and
     * swallowed so it never aborts the stdout reader (whose VT touches the DB
     * and is therefore only ever force-killed, never interrupted).
     */
    private static void persistHarnessStep(Long runId, int seq, HarnessEvent ev) {
        try {
            Tx.run(() -> {
                var run = (SubagentRun) SubagentRun.findById(runId);
                if (run == null || run.childConversation == null) return;
                var msg = new Message();
                msg.conversation = run.childConversation;
                msg.subagentRunId = runId;
                msg.role = MessageRole.ASSISTANT.value;
                msg.messageKind = MESSAGE_KIND_CODINGRUN_STEP;
                msg.content = ev.text();
                msg.metadata = GsonHolder.INSTANCE.toJson(
                        Map.of("seq", seq, "kind", ev.kind()), Map.class);
                msg.save();
            });
        } catch (RuntimeException e) {
            EventLogger.warn("subagent", null, null,
                    "Failed to persist coding-run step seq=%d for run %s: %s"
                            .formatted(seq, runId, e.getMessage()));
        }
    }

    /** Drain a process stream on a VT, bounded to {@link #ACP_MAX_OUTPUT_BYTES}. */
    private static CompletableFuture<String> drainAsync(InputStream in) {
        var f = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try (in) {
                var bytes = in.readNBytes(ACP_MAX_OUTPUT_BYTES);
                f.complete(new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException _) {
                f.complete("");
            }
        });
        return f;
    }

    /**
     * JCLAW-499: if {@code runtime} requests the ACP harness, validate it and
     * register the configured command for {@code runId} (consumed by
     * {@link #executeChildRun}). Returns an error string to short-circuit the
     * spawn, or null to proceed (native or successfully-registered acp).
     *
     * <p>JCLAW-500 (Change 2): acp is a privileged per-agent capability. The
     * harness is an operator-configured external process that runs OUTSIDE
     * JClaw's tool gating and workspace confinement, so only the main agent
     * (always) and agents an operator has granted {@link Agent#acpAllowed} may
     * request it. The gate is on the SPAWNING agent, so a confined custom agent
     * cannot break out via acp — and a subagent of main is itself non-main, so
     * it cannot escalate either.
     */
    private static String acpRuntimeError(JsonObject args, Agent spawningAgent) {
        var runtime = optString(args, ARG_RUNTIME);
        if (runtime == null || runtime.isBlank() || "native".equalsIgnoreCase(runtime)) {
            return null;
        }
        if (!"acp".equalsIgnoreCase(runtime)) {
            return "Error: 'runtime' must be \"native\" (default) or \"acp\"" + GOT_LITERAL + runtime + "').";
        }
        if (spawningAgent != null && !spawningAgent.isMain() && !spawningAgent.acpAllowed) {
            return "Error: runtime=\"acp\" is not permitted for agent '" + spawningAgent.name
                    + "'. The acp runtime launches an external harness outside JClaw's tool and "
                    + "workspace confinement, so it is restricted to the main agent and agents an "
                    + "operator has explicitly granted acp.";
        }
        if (resolveAcpCommand().isEmpty()) {
            return "Error: runtime=\"acp\" needs an external harness — the operator must configure '"
                    + ACP_COMMAND_KEY + "' (e.g. \"claude -p\" or \"codex exec\").";
        }
        // JCLAW-659: reject an operator-misconfigured harness id / mode up front,
        // with a clear message, rather than silently falling back to defaults.
        var harness = ConfigService.get(ACP_HARNESS_KEY, DEFAULT_ACP_HARNESS);
        if (harness != null && !harness.isBlank()
                && !ACP_HARNESS_IDS.contains(harness.strip().toLowerCase())) {
            return "Error: '" + ACP_HARNESS_KEY + "' must be one of " + ACP_HARNESS_IDS
                    + " (got '" + harness.strip() + "').";
        }
        var mode = ConfigService.get(ACP_MODE_KEY, DEFAULT_ACP_MODE);
        if (mode != null && !mode.isBlank()
                && !ACP_MODES.contains(mode.strip().toLowerCase())) {
            return "Error: '" + ACP_MODE_KEY + "' must be one of " + ACP_MODES
                    + " (got '" + mode.strip() + "').";
        }
        return null;
    }

    private static boolean isAcpRuntime(JsonObject args) {
        return "acp".equalsIgnoreCase(optString(args, ARG_RUNTIME));
    }

    /** Operator-configured ACP harness command, whitespace-split. Empty when unset. */
    private static List<String> resolveAcpCommand() {
        var configured = ConfigService.get(ACP_COMMAND_KEY, null);
        if (configured == null || configured.isBlank()) return List.of();
        return List.of(configured.strip().split("\\s+"));
    }

    /** JCLAW-659: configured harness id, normalized and falling back to
     *  {@link #DEFAULT_ACP_HARNESS} when unset or unrecognized. */
    private static String resolveHarnessId() {
        var configured = ConfigService.get(ACP_HARNESS_KEY, DEFAULT_ACP_HARNESS);
        if (configured == null || configured.isBlank()) return DEFAULT_ACP_HARNESS;
        var id = configured.strip().toLowerCase();
        return ACP_HARNESS_IDS.contains(id) ? id : DEFAULT_ACP_HARNESS;
    }

    /** JCLAW-659: configured acp mode, normalized and falling back to
     *  {@link #DEFAULT_ACP_MODE} when unset or unrecognized. */
    static String resolveAcpMode() {
        var configured = ConfigService.get(ACP_MODE_KEY, DEFAULT_ACP_MODE);
        if (configured == null || configured.isBlank()) return DEFAULT_ACP_MODE;
        var mode = configured.strip().toLowerCase();
        return ACP_MODES.contains(mode) ? mode : DEFAULT_ACP_MODE;
    }

    /** JCLAW-659: the {@link HarnessAdapter} for the configured harness, falling
     *  back to the {@link #DEFAULT_ACP_HARNESS} adapter. JCLAW-660 wires this into
     *  {@link #runAcpStreaming} for the json/rpc modes; returns {@code null} only
     *  if no adapter is registered for the configured harness. */
    static HarnessAdapter resolveAdapter() {
        var adapter = HARNESS_ADAPTERS.get(resolveHarnessId());
        if (adapter == null) {
            adapter = HARNESS_ADAPTERS.get(DEFAULT_ACP_HARNESS);
        }
        return adapter;
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
        payload.put(BUS_RUN_ID, runId);
        payload.put(FIELD_LABEL, label != null ? label : "");
        payload.put(FIELD_STATUS, status.name());
        payload.put(FIELD_REPLY, truncatedReply != null ? truncatedReply : "");
        payload.put("childConversationId", childConvId);
        payload.put("yielded", yielded);
        // JCLAW-291: separate from {@code truncatedReply} (which is the
        // 4000-char display cap on the announce body) — this flag means the
        // CHILD'S underlying reply was cut off by max_tokens. The chat-page
        // announce card reads this and renders a "Reply was truncated by
        // the model" marker.
        if (modelOutputTruncated) payload.put(FIELD_TRUNCATED, Boolean.TRUE);

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
        msg.metadata = GsonHolder.INSTANCE.toJson(payload, Map.class);
        // JCLAW-291: also stamp the column on the announce row itself so
        // queries that count truncated messages see it without parsing JSON.
        msg.truncated = modelOutputTruncated;
        msg.save();

        parentConv.messageCount++;
        parentConv.save();
    }
}
