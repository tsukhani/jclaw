package tools;

import agents.AgentRunner;
import agents.DangerousActionGate;
import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.ConfigService;
import services.EventLogger;
import services.SubagentRegistry;
import services.Tx;
import utils.GsonHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

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
 * {@link services.SessionCompactor#PARENT_CONTEXT_MAX_CHARS} characters), stamps the
 * result on the child Conversation, and re-injects it into the child's system
 * prompt each turn via
 * {@link services.SessionCompactor#appendParentContextToPrompt}, and (b) unions the
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
 * <p><b>Timeout enforcement.</b> {@link SubagentSyncRunner#awaitFuture} times a
 * synchronous child out once it has been idle longer than {@code runTimeoutSeconds}
 * or has run past the wall-clock ceiling, and stops it cooperatively through
 * {@link services.SubagentRegistry#requestStop}, never by interrupting its thread.
 *
 * <p>JCLAW-677: this class is the stable public facade + LLM tool contract.
 * The implementation lives in cohesive package-private collaborators in this
 * package: {@link SubagentSpawnArgs} (parsing), {@link SubagentLimits}
 * (recursion caps), {@link SubagentChildBootstrap} (child rows +
 * grants), {@link SubagentRunStore} (audit rows), {@link SubagentSyncRunner}
 * and {@link SubagentAsyncRunner} (dispatch), {@link SubagentResponses}
 * (transcript shaping), {@link SubagentChatBridge} (chat SSE bridge),
 * {@link SubagentAcpRunner} (external harness), and
 * {@link SubagentHarnessPermissions} (rpc permission arbitration).
 */
public class SubagentSpawnTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "subagent_spawn";

    static final String DEFAULT_MODE = "session";
    /** JCLAW-666: codingSlug's fallback when a task normalizes to nothing —
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
    static final String STOPPED_BEFORE_START = "Error: The turn was stopped before the subagent started; no subagent ran.";
    static final String INSIDE_TRANSACTION = "Error: The subagent cannot start from this turn: the turn runs inside an "
            + "uncommitted database transaction, so the subagent could not read its own records. This is a JClaw "
            + "defect, not a problem with the request; no subagent ran.";
    static final Set<String> ALLOWED_CONTEXTS = Set.of(CONTEXT_FRESH, CONTEXT_INHERIT);

    /** Channel value stamped on subagent conversations. Not a real transport —
     *  {@link models.ChannelType#fromValue} returns null, so dispatchers no-op
     *  and the row exists purely as the child's audit-trail container. */
    public static final String SUBAGENT_CHANNEL = "subagent";

    static final String FIELD_NOTE = "note";
    static final String INLINE_RAN_AS_SESSION =
            "mode=\"inline\" ran as mode=\"session\": a task fire has no conversation to embed the child into.";
    static final String ASYNC_RAN_IN_FOREGROUND =
            "async ran in the foreground: this chat is a group or a guest's, where a background result would "
                    + "reach it unedited. Relay the reply yourself; there is nothing to collect with subagent_yield. "
                    + "To run several children in parallel here, pass them as one tasks[] batch.";

    static final String FIELD_STATUS = "status";
    static final String FIELD_LABEL = "label";
    // JCLAW-S1192: request-arg and response-field key constants.
    private static final String ARG_TASKS = "tasks";
    static final String ARG_AGENT_ID = "agentId";
    static final String ARG_CONTEXT = "context";
    static final String ARG_RUN_TIMEOUT_SECONDS = "runTimeoutSeconds";
    static final String ARG_RUNTIME = "runtime";
    /** The {@code runtime} value that hands the child to the external coding harness, and what its run row records. */
    public static final String RUNTIME_ACP = "acp";
    static final String ARG_FOLDER = "folder";
    static final String FIELD_RUN_ID = "run_id";
    static final String FIELD_CONVERSATION_ID = "conversation_id";
    static final String FIELD_REPLY = "reply";
    static final String FIELD_ERROR = "error";
    /** JCLAW-662 bus-event key — camelCase, consumed by the frontend
     *  (d.runId / CodingRunMonitor envelope.runId). NOT the
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
     * {@link jobs.DefaultConfigJob}; Settings has no row for it, so it is changed through the Config API.
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

    /** {@link models.Message#messageKind} value stamped on async-spawn announce rows. */
    public static final String MESSAGE_KIND_ANNOUNCE = "subagent_announce";

    /** JCLAW-662: {@link models.Message#messageKind} value stamped on the child-Conversation
     *  rows that persist a coding-harness run's streamed steps, so
     *  {@link controllers.ApiSubagentRunsController#steps} can replay only the
     *  transcript rows and skip any other child-conversation messages. */
    public static final String MESSAGE_KIND_CODINGRUN_STEP = "codingrun_step";

    /**
     * JCLAW-266: recursion caps, defaulted for a single-operator install —
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

    /** JCLAW-659: which harness adapter drives the acp runtime — an id in
     *  {@code SubagentAcpRunner.ACP_HARNESS_IDS}. Operator-configured
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
    /** The values {@link #ACP_MODE_KEY} accepts, lower-case. */
    public static final Set<String> ACP_MODES = Set.of(DEFAULT_ACP_MODE, "json", "rpc");

    /** The refusal for a write of {@code value} to {@link #ACP_MODE_KEY}, or null when it is blank or a known mode. */
    public static @Nullable String modeRejectionFor(@Nullable String value) {
        if (value == null || value.isBlank() || ACP_MODES.contains(value.strip().toLowerCase(Locale.ROOT))) {
            return null;
        }
        return ACP_MODE_KEY + " must be 'batch', 'json' or 'rpc'; blank means batch.";
    }

    /** JCLAW-657 (finding A): the directory coding-run folders are created in, one per
     *  run ({@link services.CodingRunFolders}). Operator-configured (Config DB); when
     *  unset, the child agent's own workspace, so a real harness — whose file writes are
     *  OUTSIDE JClaw's tool/workspace confinement — starts there rather than in the
     *  backend's CWD (the repo root). */
    public static final String ACP_WORKDIR_KEY = "subagent.acp.workdir";

    /** Settings default for the provider/model an acp harness runs with instead of its own
     *  default. A spawn's {@link #ARG_MODEL_PROVIDER}/{@link #ARG_MODEL_ID} wins over it; both
     *  unset means the harness keeps its own model. Bound per harness by {@link HarnessModelBinding}. */
    public static final String ACP_MODEL_PROVIDER_KEY = "subagent.acp.modelProvider";
    public static final String ACP_MODEL_ID_KEY = "subagent.acp.modelId";
    static final String ARG_MODEL_PROVIDER = "modelProvider";
    static final String ARG_MODEL_ID = "modelId";

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
        // Only the Task-vs-subagent routing rule has no parameter of its own to live on;
        // every optional argument is described on its parameter, not repeated here.
        return """
                Spawn a child subagent to carry out a task on your behalf. The child runs \
                in its own fresh conversation and you receive its final assistant reply. \
                Use this when a task is well-scoped and benefits from an isolated context \
                (long research, separate tool surface, exploratory work). \
                Fires ONCE per call — there is no schedule/interval parameter. For \
                anything recurring or scheduled ("every 30 seconds", "every minute", \
                "every day at 9am"), even if the operator describes it as a \
                "subagent", use `task_manager`'s createTask action instead — that \
                is the abstraction that carries cron/interval and can invoke an agent \
                on each fire.""";
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
                        + "as a collapsible nested-turn block. A task fire has no conversation, "
                        + "so there \"inline\" runs as \"session\"."));
        props.put(ARG_MODEL_PROVIDER, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Optional provider override for the child (a configured JClaw provider name). With "
                        + "runtime=\"acp\" the coding harness is pointed at that provider's endpoint — "
                        + "claude and codex accept this; pi and gemini take modelId only."));
        props.put(ARG_MODEL_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Optional model id override for the child. With runtime=\"acp\" it replaces the "
                        + "harness's own default model (alone, the harness keeps its own endpoint and "
                        + "login); it also overrides the operator's Settings default for the harness."));
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
                        + "spawn more or do other work, then collect with subagent_yield (runIds or all=true "
                        + "returns each child's whole reply). In a chat only "
                        + "its owner reads (web chat, or the owner's private chat), a child no yield is waiting on "
                        + "when it ends posts a completion card "
                        + "to your conversation and the result is also sent to the chat's channel; in a group or "
                        + "a guest's chat the child runs in the foreground and you relay its result (a tasks[] batch "
                        + "still runs in parallel there). In a task "
                        + "fire you block-await the result with subagent_yield. Only with mode=\"session\"."));
        props.put(ARG_RUNTIME, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Child runtime: \"native\" (default) runs a JClaw subagent, or \"acp\" runs the "
                        + "operator-configured external agent harness (e.g. a Codex/Claude/Gemini CLI) "
                        + "with your task on stdin, capturing its output as the reply. Composes with "
                        + "sync, async, and batch."));
        props.put(ARG_FOLDER, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "With runtime=\"acp\" only: the name of the folder the coding harness works in. Name an "
                        + "existing folder to continue a project there; a new name creates it. Omit it "
                        + "for a fresh folder named from the task. One folder name, never a path."));
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
        var requested = SubagentSpawnArgs.parse(args);
        if (requested.error() != null) return requested.error();
        // Not refused: the fire gets the same reply either way, and an unattended run that loses a
        // round to an error can lose the run with it.
        final boolean inlineRanAsSession = ToolContext.taskRunId() != null && MODE_INLINE.equals(requested.resolvedMode());
        final var parsed = inlineRanAsSession ? requested.asSession() : requested;
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
        if (insideTransaction(parentAgent)) return INSIDE_TRANSACTION;

        // Resolve the parent conversation: the calling turn's own, else the
        // agent's most recent (DeliveryResolver.operatingConversation);
        // the lookup is wrapped in a Tx to dodge detached-entity issues on
        // VT-dispatched tool calls.
        var parentAgentId = parentAgent.id;
        // A task fire runs in no conversation: its runs record the task run, never a chat picked by recency.
        var parentTaskRunId = ToolContext.taskRunId();
        var parentConv = parentTaskRunId != null ? null
                : Tx.run(() -> SubagentChildBootstrap.resolveParentConversation(parentAgentId));
        if (parentConv == null && parentTaskRunId == null) {
            return "Error: Could not resolve a parent conversation for agent '%s'."
                    .formatted(parentAgent.name);
        }
        final var parentConvIdFinal = parentConv == null ? null : parentConv.id;

        var summary = SubagentChildBootstrap.buildInheritSummary(parentAgent, parentConvIdFinal, parsed.resolvedContext());
        // The summary can be a model call: a turn stopped meanwhile starts no child and writes no rows.
        if (ToolContext.cancelled()) return STOPPED_BEFORE_START;
        var bootstrap = SubagentChildBootstrap.bootstrapChildInTx(parentAgent, parentConv, parsed, summary);
        if (bootstrap.error() != null) return bootstrap.error();
        var childAgentId = bootstrap.childAgentId();
        var childConvId = bootstrap.childConvId();
        var childAgentName = bootstrap.childAgentName();

        // Step 3: insert the SubagentRun audit row (RUNNING + startedAt). It commits at once, and is
        // visible to the thread we hand the run to, only because insideTransaction refused a caller
        // holding a transaction: its Tx.run would join that one.
        var runId = SubagentRunStore.insertSubagentRun(parentAgentId, childAgentId, parentConvIdFinal,
                parentTaskRunId, childConvId, parsed.label(), SubagentAcpRunner.isAcpRuntime(args));
        var runIdStr = String.valueOf(runId);
        EventLogger.recordSubagentSpawn(
                parentAgent.name, childAgentName,
                runIdStr, parsed.resolvedMode(), parsed.resolvedContext());

        // JCLAW-499: register the external-harness command for this run when
        // runtime=acp (already validated above); executeChildRun consumes it.
        if (SubagentAcpRunner.isAcpRuntime(args)) {
            SubagentAcpRunner.ACP_RUNS.put(runId, SubagentAcpRunner.resolveAcpLaunch(args));
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
                    runIdStr, parsed.resolvedMode(), parsed.resolvedContext(), summary.errorReason());
        }

        final boolean inlineMode = MODE_INLINE.equals(parsed.resolvedMode());
        if (inlineMode && parentConvIdFinal != null
                && !SubagentResponses.writeInlineStartMarker(parentConvIdFinal, runId, parsed.label(), parsed.resolvedTask())) {
            SubagentRegistry.kill(runId, SubagentSyncRunner.PARENT_STOPPED_REASON);
            return STOPPED_BEFORE_START;
        }

        // JCLAW-270: async branch — dispatch the run to a background VT and
        // return immediately with {run_id, conversation_id, status: RUNNING}.
        // The VT runs the same AgentRunner.run as the synchronous path and
        // posts a structured announce Message into the parent conversation
        // on terminal state (completion / failure / timeout). Async + inline
        // was already rejected up top so this branch can hard-assume session
        // mode (inlineMode == false).
        @Nullable String note = inlineRanAsSession ? INLINE_RAN_AS_SESSION : null;
        if (parsed.asyncRequested()) {
            // JCLAW-497: a task fire has no persistent conversation to resume into,
            // so the announce/resume model can't deliver there. Use a block-await
            // handoff instead — dispatch the child (parallel) and let subagent_yield
            // collect its result inline. Chat keeps the announce/resume path.
            if (parentConv == null) {
                return SubagentAsyncRunner.launchAsyncSpawnForTask(runId, childAgentId, childConvId,
                        parentAgent.name, parsed, runIdStr);
            }
            // JCLAW-1604: an announce reaches the chat unedited, so only a chat the owner reads alone gets one.
            if (DangerousActionGate.ownerReadsAlone(parentConv.id, parentConv.channelType, parentConv.peerId)) {
                return SubagentAsyncRunner.launchAsyncSpawn(runId, childAgentId, childConvId, parentConv.id,
                        parentAgent.name, parsed, runIdStr);
            }
            note = ASYNC_RAN_IN_FOREGROUND;
        }

        return runSyncSpawn(runId, bootstrap, parentConvIdFinal, parentAgent.name, parsed,
                SubagentAcpRunner.isAcpRuntime(args), note);
    }

    private static String runSyncSpawn(Long runId, SubagentChildBootstrap.Bootstrap child, @Nullable Long parentConvId,
                                       String parentAgentName, SubagentSpawnArgs parsed,
                                       boolean acpBridged, @Nullable String note) {
        var runIdStr = String.valueOf(runId);
        var childConvId = child.childConvId();
        final boolean inlineMode = MODE_INLINE.equals(parsed.resolvedMode());
        // Step 4: synchronous AgentRunner.run on a VT so we can enforce the
        // idle and ceiling budgets.
        // JCLAW-661: for an acp coding run, bridge the open chat SSE (if any) to
        // this run so the harness's live steps stream into the turn that spawned
        // it; always unbind in the finally so a finished run can't leak callbacks.
        if (acpBridged) SubagentChatBridge.bindChatCallbacksToRun(runId);
        SyncRunOutcome runOutcome;
        try {
            runOutcome = SubagentSyncRunner.runChildSynchronously(runId, child.childAgentId(), childConvId,
                    parsed.resolvedTask(), parsed.timeoutSeconds(), inlineMode);
        } finally {
            if (acpBridged) SubagentChatBridge.clearRunCallbacks(runId);
        }

        // Step 5: write the terminal SubagentRun update in its own short Tx.
        if (!runOutcome.killedByOperator()) {
            SubagentRunStore.persistTerminalRun(runId, runOutcome.terminalStatus(), runOutcome.terminalOutcome());
        }

        // JCLAW-267: inline-mode boundary-end marker. Skipped on kill (see
        // writeInlineEndMarker javadoc for the double-render rationale).
        if (inlineMode && parentConvId != null && !runOutcome.killedByOperator()) {
            SubagentResponses.writeInlineEndMarker(parentConvId, runId, runOutcome.terminalStatus(),
                    runOutcome.reply(), runOutcome.replyTruncated());
        }

        // Step 6: emit the terminal lifecycle event. JCLAW-291: kill path
        // already emitted SUBAGENT_KILL — don't duplicate as SUBAGENT_ERROR.
        if (!runOutcome.killedByOperator()) {
            SubagentResponses.emitTerminalEvent(parentAgentName, child.childAgentName(), runIdStr,
                    parsed.resolvedMode(), parsed.resolvedContext(),
                    runOutcome.terminalStatus(), runOutcome.errorReason());
        }

        // Tool return — the LLM will see this JSON string.
        var payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_RUN_ID, runIdStr);
        payload.put(FIELD_CONVERSATION_ID, String.valueOf(childConvId));
        payload.put(FIELD_REPLY, runOutcome.reply());
        payload.put(FIELD_STATUS, runOutcome.terminalStatus().name());
        var failure = runOutcome.failure();
        if (failure != null) payload.put(FIELD_ERROR, failure);
        if (note != null) payload.put(FIELD_NOTE, note);
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
        final var acpLaunch = SubagentAcpRunner.isAcpRuntime(args) ? SubagentAcpRunner.resolveAcpLaunch(args) : null;

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
        if (insideTransaction(parentAgent)) return INSIDE_TRANSACTION;

        final var parentAgentId = parentAgent.id;
        final var parentTaskRunId = ToolContext.taskRunId();
        var parentConv = parentTaskRunId != null ? null
                : Tx.run(() -> SubagentChildBootstrap.resolveParentConversation(parentAgentId));
        if (parentConv == null && parentTaskRunId == null) {
            return "Error: Could not resolve a parent conversation for agent '%s'.".formatted(parentAgent.name);
        }
        final var parentConvIdFinal = parentConv == null ? null : parentConv.id;
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
            var perArgs = SubagentSpawnArgs.batchChild(args, spec.task(), spec.label(), spec.agentId(),
                    fMode, fContext, fTimeout);
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
                    parentConvIdFinal, parentTaskRunId, bootstrap.childConvId(), spec.label(), acpLaunch != null);
            EventLogger.recordSubagentSpawn(parentAgent.name, bootstrap.childAgentName(),
                    String.valueOf(runId), fMode, fContext);
            if (acpLaunch != null) {
                SubagentAcpRunner.ACP_RUNS.put(runId, acpLaunch);
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

    /** True, with a warning, when the calling thread holds a transaction: the spawn's rows would join it, and
     *  the child's thread cannot read them before it commits (JF-163). */
    private static boolean insideTransaction(Agent parentAgent) {
        if (!JPA.isInsideTransaction()) return false;
        EventLogger.warn(SUBAGENT_CHANNEL, parentAgent.name, null,
                "Refused subagent_spawn: this turn runs inside an uncommitted database transaction, which a subagent "
                        + "on another thread cannot read. Whatever runs the turn must not hold a transaction across it.");
        return true;
    }

    /** JCLAW-498: one entry of a batch fan-out. */
    private record BatchTaskSpec(String task, @Nullable String label, @Nullable Long agentId) {}

    /**
     * JCLAW-809: reject a spawn mode that is not in {@link #ALLOWED_MODES},
     * returning the rejection message (identical in format to the single-spawn
     * {@code SubagentSpawnArgs.parse} path) or {@code null} when the normalized
     * mode is allowed. {@code requestedMode} is the raw arg, used only for the
     * {@code (got '…')} suffix. Extracted so the single-spawn and batch paths
     * cannot diverge on which modes they accept.
     */
    static @Nullable String modeRejection(@Nullable String requestedMode, String normalizedMode) {
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
            String terminalOutcome, @Nullable String errorReason,
            boolean killedByOperator, boolean replyTruncated) {

        /** Why the run did not complete, for the parent's tool result; null when it completed. */
        public @Nullable String failure() {
            return terminalStatus == SubagentRun.Status.COMPLETED ? null : terminalOutcome;
        }
    }

    /** Resolved (provider, modelId) pair for a spawned subagent. */
    public record SubagentModel(@Nullable String provider, @Nullable String modelId) {}

    /**
     * JCLAW-422: resolve the model a session subagent runs on. Public facade
     * delegator over {@link SubagentChildBootstrap#resolveSubagentModel} for
     * {@code SubagentSpawnToolTest}.
     */
    public static SubagentModel resolveSubagentModel(@Nullable Conversation parentConv, Agent childAgent,
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
    public static void registerChatCallbacks(@Nullable Long conversationId, AgentRunner.@Nullable StreamingCallbacks cb) {
        SubagentChatBridge.registerChatCallbacks(conversationId, cb);
    }

    /** JCLAW-661: drop the chat-turn callbacks for a conversation (turn closed).
     *  Facade delegator for {@link controllers.ApiChatController}. */
    public static void unregisterChatCallbacks(@Nullable Long conversationId, AgentRunner.@Nullable StreamingCallbacks cb) {
        SubagentChatBridge.unregisterChatCallbacks(conversationId, cb);
    }

    /** Test seam: the chat-turn callbacks registered for {@code conversationId}, or null. */
    public static AgentRunner.@Nullable StreamingCallbacks chatCallbacksForTest(Long conversationId) {
        return SubagentChatBridge.chatCallbacks(conversationId);
    }

    /** Test seam: {@link SubagentChatBridge#relayActivity} at the test's own interval. */
    public static Future<?> relayActivityForTest(List<Long> runIds, Duration every) {
        return SubagentChatBridge.relayActivity(runIds, every);
    }

    /**
     * JCLAW-670: append the permission flags — the operator's
     * {@link #ACP_PERMISSION_ARGS_KEY} when set ({@code none} = none at all),
     * the adapter's conservative {@code defaultPermissionArgs()} otherwise.
     */
    public static List<String> withPermissionArgs(HarnessAdapter adapter, List<String> argv) {
        var configured = ConfigService.get(ACP_PERMISSION_ARGS_KEY);
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
     *  a fixed fallback for tasks that normalize to nothing. */
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
    static boolean notBlank(@Nullable String s) {
        return s != null && !s.isBlank();
    }
}
