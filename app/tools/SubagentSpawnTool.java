package tools;

import agents.AgentRunner;
import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import services.ConfigService;
import services.EventLogger;
import services.Tx;
import utils.GsonHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
 *
 * <p>JCLAW-677: this class is the stable public facade + LLM tool contract.
 * The implementation lives in cohesive package-private collaborators in this
 * package: {@link SubagentSpawnArgs} (parsing), {@link SubagentLimits}
 * (recursion/capability policy), {@link SubagentChildBootstrap} (child rows +
 * grants), {@link SubagentRunStore} (audit rows), {@link SubagentSyncRunner}
 * and {@link SubagentAsyncRunner} (dispatch), {@link SubagentResponses}
 * (transcript shaping), {@link SubagentChatBridge} (chat SSE bridge),
 * {@link SubagentAcpRunner} (external harness), and
 * {@link SubagentHarnessPermissions} (rpc permission arbitration).
 */
public class SubagentSpawnTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "subagent_spawn";

    static final String DEFAULT_MODE = "session";
    /** JCLAW-666: codingSlug's fallback when a task normalises to nothing —
     *  a distinct concept from DEFAULT_MODE despite the shared string. */
    private static final String SLUG_FALLBACK = "session";
    static final String MODE_SESSION = "session";
    static final String MODE_INLINE = "inline";
    /** JCLAW-809: canonical set of accepted spawn modes — the single source of
     *  truth for {@link #modeRejection} so the single-spawn ({@code SubagentSpawnArgs.parse})
     *  and batch ({@link #executeBatch}) paths validate identically. Mirrors the
     *  {@link #ALLOWED_CONTEXTS} home so both allowed-value sets live on the facade. */
    static final Set<String> ALLOWED_MODES = Set.of(MODE_SESSION, MODE_INLINE);
    static final String DEFAULT_CONTEXT = "fresh";
    static final String CONTEXT_FRESH = "fresh";
    static final String CONTEXT_INHERIT = "inherit";
    static final Set<String> ALLOWED_CONTEXTS = Set.of(CONTEXT_FRESH, CONTEXT_INHERIT);

    /** Channel value stamped on subagent conversations. Not a real transport —
     *  {@link models.ChannelType#fromValue} returns null, so dispatchers no-op
     *  and the row exists purely as the child's audit-trail container. */
    public static final String SUBAGENT_CHANNEL = "subagent";

    static final String FIELD_STATUS = "status";
    static final String FIELD_LABEL = "label";
    // JCLAW-S1192: request-arg and response-field key constants.
    private static final String ARG_TASKS = "tasks";
    static final String ARG_AGENT_ID = "agentId";
    static final String ARG_CONTEXT = "context";
    static final String ARG_RUN_TIMEOUT_SECONDS = "runTimeoutSeconds";
    static final String ARG_RUNTIME = "runtime";
    static final String FIELD_RUN_ID = "run_id";
    static final String FIELD_CONVERSATION_ID = "conversation_id";
    static final String FIELD_REPLY = "reply";
    /** JCLAW-662 bus-event key — camelCase, consumed by the frontend
     *  (chat.vue d.runId / CodingRunMonitor envelope.runId). NOT the
     *  snake_case FIELD_RUN_ID of the spawn-response payload. */
    static final String BUS_RUN_ID = "runId";
    static final String GOT_LITERAL = " (got '";
    static final String FIELD_TRUNCATED = "truncated";

    /**
     * Default value for the per-spawn {@code runTimeoutSeconds} arg.
     * JCLAW-424: this is now an IDLE (inactivity) budget, not total wall-clock —
     * a run times out only after this many seconds with NO activity (no LLM
     * round / tool call); active work resets the clock. The absolute total
     * runtime is bounded separately by {@link #MAX_WALLCLOCK_KEY}.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * JCLAW-424: operator-controlled absolute wall-clock ceiling on a single
     * subagent run, independent of activity — the runaway guard the idle budget
     * (which an active child never trips) cannot provide. NOT settable by the
     * spawning LLM. {@code 0} disables the ceiling (idle budget only). Seeded by
     * {@link jobs.DefaultConfigJob}, editable in Settings &gt; Subagents.
     */
    public static final String MAX_WALLCLOCK_KEY = "subagent.maxWallClockSeconds";
    public static final int DEFAULT_MAX_WALLCLOCK_SECONDS = 1800;

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

    /** JCLAW-812: operator-configurable global default for the per-spawn
     *  {@code runTimeoutSeconds} arg (Settings → Subagents). A spawn that omits
     *  the arg falls back to this; a call-site value still overrides. DB-backed,
     *  so it applies without a restart. */
    public static final String DEFAULT_RUN_TIMEOUT_KEY = "subagent.defaultRunTimeoutSeconds";

    /** Effective default idle budget for a spawn that omits {@code runTimeoutSeconds}:
     *  the configured {@link #DEFAULT_RUN_TIMEOUT_KEY}, coerced to
     *  {@link #DEFAULT_TIMEOUT_SECONDS} when unset or non-positive (a run always
     *  needs a positive idle budget; total runtime is capped by {@link #MAX_WALLCLOCK_KEY}). */
    public static int defaultRunTimeoutSeconds() {
        int n = ConfigService.getInt(DEFAULT_RUN_TIMEOUT_KEY, DEFAULT_TIMEOUT_SECONDS);
        return n > 0 ? n : DEFAULT_TIMEOUT_SECONDS;
    }

    public static final String CFG_SUBAGENT_PROVIDER = "subagent.modelProvider";
    public static final String CFG_SUBAGENT_MODEL = "subagent.modelId";

    /** JCLAW-499: config key for the operator-configured external agent harness
     *  command (e.g. {@code claude -p} or {@code codex exec}). The command is NOT
     *  model-supplied — runtime=acp runs this configured command, so a subagent
     *  can't be steered into executing arbitrary shell. */
    public static final String ACP_COMMAND_KEY = "subagent.acp.command";
    /** JCLAW-670: operator override for the adapter's default permission flags
     *  (whitespace-split; literal "none" = no flags at all). */
    public static final String ACP_PERMISSION_ARGS_KEY = "subagent.acp.permissionArgs";

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
                        + "active work resets it, so it need not cover total runtime (default "
                        + defaultRunTimeoutSeconds() + ")"));
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
        var parsed = SubagentSpawnArgs.parse(args);
        if (parsed.error() != null) return parsed.error();
        // JCLAW-499: validate the runtime (native | acp) before any DB work.
        var acpError = SubagentAcpRunner.acpRuntimeError(args, parentAgent);
        if (acpError != null) return acpError;

        // JCLAW-266: enforce recursion caps before touching the DB. Both checks
        // run inside a Tx so the ancestor walk and the RUNNING-row count see a
        // consistent snapshot. On refusal we emit SUBAGENT_LIMIT_EXCEEDED and
        // return a plain-text error — no SubagentRun row, no child Agent, no
        // child Conversation gets written.
        var refusal = Tx.run(() -> SubagentLimits.enforceRecursionLimits(parentAgent));
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
        var parentConv = Tx.run(() -> SubagentChildBootstrap.resolveParentConversation(parentAgentId));
        if (parentConv == null) {
            return "Error: Could not resolve a parent conversation for agent '%s'."
                    .formatted(parentAgent.name);
        }

        var summary = SubagentChildBootstrap.buildInheritSummary(parentAgent, parentConv.id, parsed.context());
        var bootstrap = SubagentChildBootstrap.bootstrapChildInTx(parentAgent, parentConv, parsed, summary);
        if (bootstrap.error() != null) return bootstrap.error();
        var childAgentId = bootstrap.childAgentId();
        var childConvId = bootstrap.childConvId();
        var childAgentName = bootstrap.childAgentName();

        // Step 3: insert the SubagentRun audit row (RUNNING + startedAt) in
        // its own short Tx so the row commits and is visible from any thread
        // we hand the run to.
        final var parentConvIdFinal = parentConv.id;
        var runId = SubagentRunStore.insertSubagentRun(parentAgentId, childAgentId, parentConvIdFinal, childConvId,
                parsed.label());
        var runIdStr = String.valueOf(runId);
        EventLogger.recordSubagentSpawn(
                parentAgent.name, childAgentName,
                runIdStr, parsed.mode(), parsed.context());

        // JCLAW-499: register the external-harness command for this run when
        // runtime=acp (already validated above); executeChildRun consumes it.
        if (SubagentAcpRunner.isAcpRuntime(args)) {
            SubagentAcpRunner.ACP_RUNS.put(runId, SubagentAcpRunner.resolveAcpCommand());
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
            SubagentResponses.writeInlineStartMarker(parentConvIdFinal, runId, parsed.label(), parsed.task());
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
                return SubagentAsyncRunner.launchAsyncSpawnForTask(runId, childAgentId, childConvId,
                        parentAgent.name, parsed, runIdStr);
            }
            return SubagentAsyncRunner.launchAsyncSpawn(runId, childAgentId, childConvId, parentConvIdFinal,
                    parentAgent.name, parsed, runIdStr);
        }

        // Step 4: synchronous AgentRunner.run on a VT so we can enforce the
        // wall-clock budget via Future.get(timeout).
        // JCLAW-661: for an acp coding run, bridge the open chat SSE (if any) to
        // this run so the harness's live steps stream into the turn that spawned
        // it; always unbind in the finally so a finished run can't leak callbacks.
        boolean acpBridged = SubagentAcpRunner.isAcpRuntime(args);
        if (acpBridged) SubagentChatBridge.bindChatCallbacksToRun(runId);
        SyncRunOutcome runOutcome;
        try {
            runOutcome = SubagentSyncRunner.runChildSynchronously(runId, childAgentId, childConvId,
                    parsed.task(), parsed.timeoutSeconds(), inlineMode);
        } finally {
            if (acpBridged) SubagentChatBridge.clearRunCallbacks(runId);
        }

        // Step 5: write the terminal SubagentRun update in its own short Tx.
        if (!runOutcome.killedByOperator()) {
            SubagentRunStore.persistTerminalRun(runId, runOutcome.terminalStatus(), runOutcome.terminalOutcome());
        }

        // JCLAW-267: inline-mode boundary-end marker. Skipped on kill (see
        // writeInlineEndMarker javadoc for the double-render rationale).
        if (inlineMode && !runOutcome.killedByOperator()) {
            SubagentResponses.writeInlineEndMarker(parentConvIdFinal, runId, runOutcome.terminalStatus(),
                    runOutcome.reply(), runOutcome.replyTruncated());
        }

        // Step 6: emit the terminal lifecycle event. JCLAW-291: kill path
        // already emitted SUBAGENT_KILL — don't duplicate as SUBAGENT_ERROR.
        if (!runOutcome.killedByOperator()) {
            SubagentResponses.emitTerminalEvent(parentAgent.name, childAgentName, runIdStr,
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
        return GsonHolder.GSON.toJson(payload, Map.class);
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
        var rawMode = SubagentSpawnArgs.optString(args, "mode");
        var mode = (rawMode == null || rawMode.isBlank()) ? DEFAULT_MODE : rawMode.toLowerCase(Locale.ROOT);
        // JCLAW-809: enforce the same ALLOWED_MODES check the single-spawn path
        // applies, so a mode typo is rejected here instead of silently accepted.
        var rejection = modeRejection(rawMode, mode);
        if (rejection != null) return rejection;
        if (MODE_INLINE.equals(mode)) {
            return "Error: batch 'tasks' is only compatible with mode=\"session\" (inline embeds a single child into the parent transcript).";
        }
        var rawContext = SubagentSpawnArgs.optString(args, ARG_CONTEXT);
        var context = (rawContext == null || rawContext.isBlank()) ? DEFAULT_CONTEXT : rawContext.toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTEXTS.contains(context)) {
            return "Error: 'context' must be one of " + ALLOWED_CONTEXTS + ".";
        }
        var runDefault = defaultRunTimeoutSeconds();
        var timeoutSeconds = SubagentSpawnArgs.optInt(args, ARG_RUN_TIMEOUT_SECONDS, runDefault);
        if (timeoutSeconds <= 0) timeoutSeconds = runDefault;
        // JCLAW-499: runtime validation (native | acp) + resolved harness command,
        // applied to every child of the fan-out.
        var acpError = SubagentAcpRunner.acpRuntimeError(args, parentAgent);
        if (acpError != null) return acpError;
        final var acpCommand = SubagentAcpRunner.isAcpRuntime(args) ? SubagentAcpRunner.resolveAcpCommand() : null;

        var specs = new ArrayList<BatchTaskSpec>();
        for (var el : tasksEl.getAsJsonArray()) {
            if (el.isJsonPrimitive()) {
                var t = el.getAsString();
                if (t.isBlank()) return "Error: each 'tasks' entry must be a non-blank task.";
                specs.add(new BatchTaskSpec(t, null, null));
            } else if (el.isJsonObject()) {
                var o = el.getAsJsonObject();
                var t = SubagentSpawnArgs.optString(o, "task");
                if (t == null || t.isBlank()) return "Error: each 'tasks' object must have a non-blank 'task'.";
                specs.add(new BatchTaskSpec(t, SubagentSpawnArgs.optString(o, FIELD_LABEL),
                        SubagentSpawnArgs.optLong(o, ARG_AGENT_ID)));
            } else {
                return "Error: each 'tasks' entry must be a string or an object.";
            }
        }

        // Breadth cap for the whole fan-out (running + N must fit).
        final int n = specs.size();
        var refusal = Tx.run(() -> SubagentLimits.enforceRecursionLimits(parentAgent, n));
        if (refusal != null) {
            EventLogger.recordSubagentLimitExceeded(parentAgent.name, refusal);
            return "Subagent spawn refused: " + refusal;
        }

        final var parentAgentId = parentAgent.id;
        var parentConv = Tx.run(() -> SubagentChildBootstrap.resolveParentConversation(parentAgentId));
        if (parentConv == null) {
            return "Error: Could not resolve a parent conversation for agent '%s'.".formatted(parentAgent.name);
        }
        final var parentConvIdFinal = parentConv.id;
        final var scopeKey = SubagentAsyncRunner.currentScopeKey();
        final var fMode = mode;
        final var fContext = context;
        final var fTimeout = timeoutSeconds;

        // JCLAW-503: every child of the fan-out shares the same parent conversation
        // and context, so the inherit parent-context summary is identical for all of
        // them. Compute it ONCE here instead of re-running the summarizer LLM call per
        // child inside the loop. NONE in fresh mode (no LLM call); a summarization
        // failure degrades the whole batch to fresh, consistently.
        var summary = SubagentChildBootstrap.buildInheritSummary(parentAgent, parentConvIdFinal, fContext);

        var runIds = new ArrayList<String>();
        var failures = new LinkedHashMap<String, String>();
        for (var spec : specs) {
            var perArgs = new SubagentSpawnArgs(null, spec.task(), spec.label(), spec.agentId(),
                    null, null, fMode, fContext, fTimeout, true);
            var bootstrap = SubagentChildBootstrap.bootstrapChildInTx(parentAgent, parentConv, perArgs, summary);
            if (bootstrap.error() != null) {
                // JCLAW-823: don't silently drop a child whose bootstrap failed.
                // Record the reason against its task so the parent LLM sees which
                // spawns were skipped and why, and emit an auditable event per skip
                // (no child/run id yet — bootstrap never got that far).
                failures.put(spec.task(), bootstrap.error());
                EventLogger.recordSubagentError(parentAgent.name, null, null, fMode, fContext, bootstrap.error());
                continue; // the rest of the fan-out still runs
            }
            var runId = SubagentRunStore.insertSubagentRun(parentAgentId, bootstrap.childAgentId(),
                    parentConvIdFinal, bootstrap.childConvId(), spec.label());
            EventLogger.recordSubagentSpawn(parentAgent.name, bootstrap.childAgentName(),
                    String.valueOf(runId), fMode, fContext);
            if (acpCommand != null) {
                SubagentAcpRunner.ACP_RUNS.put(runId, acpCommand);
            }
            SubagentAsyncRunner.dispatchDetachedAsync(runId, bootstrap.childAgentId(), bootstrap.childConvId(),
                    parentAgent.name, fMode, fContext, fTimeout, spec.task(), scopeKey);
            runIds.add(String.valueOf(runId));
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("run_ids", runIds);
        payload.put("count", runIds.size());
        payload.put(FIELD_STATUS, SubagentRun.Status.RUNNING.name());
        // JCLAW-823: surface bootstrap-skipped children (task -> reason) so the
        // parent LLM can retry or report them instead of assuming all N launched.
        if (!failures.isEmpty()) {
            payload.put("skipped", failures);
            payload.put("skipped_count", failures.size());
        }
        payload.put("hint", "children run in parallel; collect them all with one subagent_yield using runIds (or all=true)");
        return GsonHolder.GSON.toJson(payload, Map.class);
    }

    /** JCLAW-498: one entry of a batch fan-out. */
    private record BatchTaskSpec(String task, String label, Long agentId) {}

    /**
     * JCLAW-809: reject a spawn mode that is not in {@link #ALLOWED_MODES},
     * returning the rejection message (identical in format to the single-spawn
     * {@code SubagentSpawnArgs.parse} path) or {@code null} when the normalized
     * mode is allowed. {@code requestedMode} is the raw arg, used only for the
     * {@code (got '…')} suffix. Extracted so the single-spawn and batch paths
     * cannot diverge on which modes they accept.
     */
    static String modeRejection(String requestedMode, String normalizedMode) {
        if (!ALLOWED_MODES.contains(normalizedMode)) {
            return "Error: 'mode' must be one of " + ALLOWED_MODES
                    + GOT_LITERAL + requestedMode + "').";
        }
        return null;
    }

    /** Result of the synchronous child run + idle await. Public for
     *  {@code SubagentSpawnToolTest} (default package), which inspects the
     *  terminal status from {@link #awaitFuture}. */
    public record SyncRunOutcome(
            String reply, SubagentRun.Status terminalStatus,
            String terminalOutcome, String errorReason,
            boolean killedByOperator, boolean replyTruncated) {}

    /** Resolved (provider, modelId) pair for a spawned subagent. */
    public record SubagentModel(String provider, String modelId) {}

    /**
     * JCLAW-422: resolve the model a session subagent runs on. Public facade
     * delegator over {@link SubagentChildBootstrap#resolveSubagentModel} for
     * {@code SubagentSpawnToolTest}.
     */
    public static SubagentModel resolveSubagentModel(Conversation parentConv, Agent childAgent,
                                                     String overrideProvider, String overrideId) {
        return SubagentChildBootstrap.resolveSubagentModel(parentConv, childAgent, overrideProvider, overrideId);
    }

    /**
     * JCLAW-424: await the child future on an IDLE-timeout model. Public facade
     * delegator over {@link SubagentSyncRunner#awaitFuture} for
     * {@code SubagentSpawnToolTest} (default package).
     */
    public static SyncRunOutcome awaitFuture(
            CompletableFuture<AgentRunner.RunResult> future,
            int idleBudgetSeconds, int ceilingSeconds, Long runId) {
        return SubagentSyncRunner.awaitFuture(future, idleBudgetSeconds, ceilingSeconds, runId);
    }

    /**
     * JCLAW-270: async-spawn body. Public facade delegator over
     * {@link SubagentAsyncRunner#runAsyncAndAnnounce}; kept on the facade because
     * {@code SubagentSpawnToolTest} and {@code SubagentYieldToolTest} drive it
     * directly.
     */
    public static void runAsyncAndAnnounce(Long runId, Long childAgentId, Long childConvId,
                                           Long parentConvId, String parentAgentName,
                                           String mode, String context, String label,
                                           int timeoutSeconds, String task) {
        SubagentAsyncRunner.runAsyncAndAnnounce(runId, childAgentId, childConvId, parentConvId,
                parentAgentName, mode, context, label, timeoutSeconds, task);
    }

    /**
     * JCLAW-497: block-await ONE async subagent's outcome. Facade delegator for
     * {@link SubagentYieldTool}.
     */
    public static String awaitAsyncOutcome(Long runId) {
        return SubagentAsyncRunner.awaitAsyncOutcome(runId);
    }

    /**
     * JCLAW-498: block-await MANY async subagents. Facade delegator for
     * {@link SubagentYieldTool}.
     */
    public static String awaitAsyncOutcomes(List<Long> runIds) {
        return SubagentAsyncRunner.awaitAsyncOutcomes(runIds);
    }

    /** JCLAW-498: the outstanding async-child runIds for the current scope. Facade
     *  delegator for {@link SubagentYieldTool} (all=true). */
    public static List<Long> outstandingForCurrentScope() {
        return SubagentAsyncRunner.outstandingForCurrentScope();
    }

    /** JCLAW-661: register the open chat turn's streaming callbacks under its
     *  conversation id. Facade delegator for {@link controllers.ApiChatController}. */
    public static void registerChatCallbacks(Long conversationId, AgentRunner.StreamingCallbacks cb) {
        SubagentChatBridge.registerChatCallbacks(conversationId, cb);
    }

    /** JCLAW-661: drop the chat-turn callbacks for a conversation (turn closed).
     *  Facade delegator for {@link controllers.ApiChatController}. */
    public static void unregisterChatCallbacks(Long conversationId) {
        SubagentChatBridge.unregisterChatCallbacks(conversationId);
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
        var out = new ArrayList<String>(argv.size() + extra.size());
        out.addAll(argv);
        out.addAll(extra);
        return List.copyOf(out);
    }

    /** JCLAW-666: deterministic filename-safe session slug from the task text —
     *  lowercased, non-alphanumerics collapsed to single dashes, bounded, with
     *  a fixed fallback for tasks that normalise to nothing. */
    public static String codingSlug(String task) {
        if (task == null) return SLUG_FALLBACK;
        var normalized = task.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        var slug = trimDashes(normalized);
        if (slug.length() > 40) {
            slug = trimDashes(slug.substring(0, 40));
        }
        return slug.isEmpty() ? SLUG_FALLBACK : slug;
    }

    /** Strip leading/trailing '-' — a linear char-index trim (no regex, so no
     *  ReDoS surface). */
    private static String trimDashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') start++;
        while (end > start && s.charAt(end - 1) == '-') end--;
        return s.substring(start, end);
    }

    /** Shared blank-check used by the spawn collaborators. */
    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
