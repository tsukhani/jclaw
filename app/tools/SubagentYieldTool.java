package tools;

import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.SubagentRun;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.SubagentRegistry;
import services.Tx;
import utils.GsonHolder;
import utils.JsonArgs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-273 / JCLAW-326: companion tool to {@link SubagentSpawnTool} for the
 * {@code sessions_yield} flow. The parent agent calls this tool with the
 * {@code run_id} (or alternatively {@code conversation_id}) returned from a
 * prior {@code subagent_spawn} with {@code async=true}; the tool flips
 * {@link SubagentRun#yielded} to {@code true} and returns a sentinel JSON
 * payload that {@link agents.AgentRunner} recognizes to exit its tool-call
 * loop without emitting a final assistant reply.
 *
 * <p>The actual resume — the child's final reply landing as the parent's
 * next user-role message and the parent's loop re-invoking — happens later,
 * inside {@link SubagentSpawnTool#runAsyncAndAnnounce} when the async child
 * VT reaches its terminal state. This tool just records the parent's intent
 * to suspend; the announce VT reads {@link SubagentRun#yielded} to decide
 * which announce-shape (SYSTEM-role fire-and-forget vs USER-role resume) to
 * post.
 *
 * <p>A run that resumes no turn, a {@code tasks[]} batch child or any run of a
 * task fire, is block-awaited instead and its outcome returned inline (JF-169).
 *
 * <p>JCLAW-326 adds two parameters:
 * <ul>
 *   <li>{@code conversationId} — alternate lookup key. The most-recent
 *       {@link SubagentRun} whose {@code childConversation} matches resolves
 *       to the run. When both are provided, {@code runId} wins (explicit
 *       beats inferred).</li>
 *   <li>{@code timeoutSeconds} — caller-tightened resume budget. Persisted
 *       on {@link SubagentRun#yieldTimeoutSeconds} and used by
 *       {@link SubagentRegistry#scheduleYieldTimeout} to arm a watchdog VT
 *       that fires a synthetic {@link java.util.concurrent.TimeoutException}
 *       into the in-flight future after the window elapses. Lets a parent
 *       with stricter wall-clock needs cut the spawn-time
 *       {@code runTimeoutSeconds} short without re-issuing the spawn.</li>
 * </ul>
 *
 * <p>Validation, in this order:
 * <ul>
 *   <li>Exactly one of {@code runId} / {@code conversationId} must resolve a
 *       run; both blank → error.</li>
 *   <li>The row's {@code parentAgent} must equal the calling agent — a
 *       parent can only yield into its own running children, not into a
 *       sibling or unrelated agent's run.</li>
 *   <li>The row's {@code parentConversation} must be the calling turn's
 *       conversation, the one the run's end resumes.</li>
 *   <li>A {@code tasks[]} child whose outcome is still held is collected
 *       inline whatever its status, so a finished one is returned once.</li>
 *   <li>Otherwise the row's {@link SubagentRun#status} must be
 *       {@link SubagentRun.Status#RUNNING}; yielding into a terminal run
 *       surfaces a structured {@code already_terminal} envelope so the LLM
 *       still gets the recorded outcome.</li>
 *   <li>{@code timeoutSeconds} above 3600 is clamped; 0 disables the
 *       watchdog, a negative value takes the default. It applies only to a
 *       yield that suspends the turn.</li>
 * </ul>
 * Invalid calls return a descriptive error string and do not mutate any
 * state, matching the {@link SubagentSpawnTool} validation idiom.
 *
 * <p>Two-step shape (vs combining yield into {@code subagent_spawn}): the
 * AC explicitly references a separate companion tool, and the two-step
 * shape composes cleanly with the JCLAW-270 async flow — the parent gets
 * the {@code run_id} from {@code subagent_spawn}, can optionally do other
 * work in the same turn, then yields on a later tool call when it wants to
 * suspend. A combined {@code subagent_spawn {async:true, yield:true}}
 * would forfeit that intermediate-work window.
 */
public class SubagentYieldTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "subagent_yield";

    /** The single-run argument, and the key {@link #YIELD_SENTINEL_PREFIX}'s payload names its run under. */
    public static final String PARAM_RUN_ID = "runId";
    private static final String PARAM_CONVERSATION_ID = "conversationId";
    private static final String PARAM_TIMEOUT_SECONDS = "timeoutSeconds";
    private static final String PARAM_RUN_IDS = "runIds";

    /** Default resume budget when the caller does not supply
     *  {@code timeoutSeconds}. Matches the JCLAW-326 AC. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** Hard upper bound on {@code timeoutSeconds}. Anything larger is clamped
     *  so a stray million-second value can't pin a watchdog VT indefinitely. */
    public static final int MAX_TIMEOUT_SECONDS = 3600;

    /** JCLAW-812: operator-configurable global default for the {@code timeoutSeconds}
     *  arg (Settings → Subagents). A yield that omits the arg falls back to this;
     *  a call-site value still overrides. DB-backed, applies without a restart. */
    public static final String DEFAULT_YIELD_TIMEOUT_KEY = "subagent.defaultYieldTimeoutSeconds";

    /** Effective default resume budget for a yield that omits {@code timeoutSeconds}:
     *  the configured {@link #DEFAULT_YIELD_TIMEOUT_KEY}, clamped to
     *  {@link #MAX_TIMEOUT_SECONDS}. Unlike the spawn budget, {@code 0} is a valid
     *  configured default — it disables the yield watchdog so the parent parks until
     *  the child ends via its own spawn-time {@code runTimeoutSeconds}. Only a
     *  negative (nonsensical) value coerces to {@link #DEFAULT_TIMEOUT_SECONDS}. */
    public static int defaultYieldTimeoutSeconds() {
        int n = ConfigService.getInt(DEFAULT_YIELD_TIMEOUT_KEY, DEFAULT_TIMEOUT_SECONDS);
        if (n < 0) return DEFAULT_TIMEOUT_SECONDS;
        return Math.min(n, MAX_TIMEOUT_SECONDS);
    }

    /** Marker that {@link agents.AgentRunner} scans for on tool-result text
     *  to recognize a successful yield call and break out of its tool-call
     *  loop without persisting a final assistant reply. */
    public static final String YIELD_SENTINEL_PREFIX = "{\"action\":\"yielded\"";

    /** An all=true with nothing left to collect: a bare empty result reads as "not yet", and a model polls it. */
    static final String NOTHING_TO_COLLECT = "{\"results\":[],\"count\":0,\"note\":\"Nothing to collect: "
            + "no async subagent spawned in this conversation or task is waiting to be collected. "
            + "Calling subagent_yield again will not change that.\"}";

    /** An all=true with nothing outstanding but finished runs in scope: their ids, never their reports again. */
    private static final String FINISHED_NOTE = "Nothing is waiting to be collected. The runs in finished have ended and "
            + "keep their reports: pass the ids of any whose report you have not seen as runIds to read it. "
            + "Calling subagent_yield with all=true again will not change that.";

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "pause"; }

    @Override
    public String shortDescription() {
        return "Wait for an async subagent: suspend this turn until it ends, or get a batch child's result inline.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction("yield",
                        "Wait for the named subagent: suspend the turn until it ends, or return a batch "
                                + "child's result inline."));
    }

    @Override
    public String description() {
        return """
                Wait for an async-spawned subagent. With the run id of a chat `subagent_spawn` \
                with `async=true`, your AgentRunner loop exits without emitting a final assistant \
                reply; once the child terminates (completion, failure, or timeout), its final output \
                is delivered back as your next user-role message and your loop resumes from there. \
                A `tasks[]` batch child, or any async child in a task fire, is waited for here \
                instead and its result returned inline, without suspending. Use this when you want \
                to block your logical turn on a child's result rather than continuing in parallel; \
                see the parameters for single, batch, and collect-all forms.""";
    }

    @Override
    public String summary() {
        return "Wait for an async subagent: suspend until it ends, or get a batch child's result inline.";
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put(PARAM_RUN_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Run id returned by a prior subagent_spawn call with async=true or tasks[]. "
                        + "A tasks[] run is waited for and returned inline, without suspending. "
                        + "Provide this OR conversationId (runId wins when both set)."));
        props.put(PARAM_RUN_IDS, Map.of(SchemaKeys.TYPE, SchemaKeys.ARRAY,
                SchemaKeys.ITEMS, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING),
                SchemaKeys.DESCRIPTION,
                "BATCH COLLECT: run_ids from subagent_spawn calls with tasks[] or async=true. "
                        + "Block-awaits ALL of them and returns {results:[...]} inline, each with its "
                        + "whole reply. Use instead of runId to collect a whole fan-out in one call. "
                        + "A run that already finished returns its saved reply again."));
        props.put("all", Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                SchemaKeys.DESCRIPTION,
                "BATCH COLLECT: when true, block-await EVERY async child (tasks[] or async=true) "
                        + "spawned in this conversation or task that is not yet collected, and return "
                        + "them all — no need to list run_ids."));
        props.put(PARAM_CONVERSATION_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Child conversation id returned by the prior subagent_spawn call. "
                        + "Resolves to the most-recent SubagentRun whose childConversation "
                        + "matches; ignored when runId is provided."));
        props.put(PARAM_TIMEOUT_SECONDS, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Caller-tightened resume budget in seconds (0-" + MAX_TIMEOUT_SECONDS
                        + ", default " + defaultYieldTimeoutSeconds()
                        + "). If the child hasn't terminated by then a synthetic TIMEOUT "
                        + "announce resumes your turn. Pass 0 to disable the yield timeout — "
                        + "the parent waits until the child terminates via its own spawn-time "
                        + "runTimeoutSeconds. Applies only when the yield suspends the turn: an "
                        + "inline wait (a tasks[] child, a task fire) is bounded by the child's "
                        + "own run timeout."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props
        );
    }

    /** Mutates a {@link SubagentRun} row; share with {@link SubagentSpawnTool}'s
     *  parallel-safe stance (false) so two concurrent yields against the same
     *  run never race. */
    @Override
    public boolean parallelSafe() { return false; }

    /** Subagent-lifecycle group: shared with {@link SubagentSpawnTool} so a
     *  same-message {@code subagent_spawn} + {@code subagent_yield} pair
     *  serializes inside the {@link agents.ParallelToolExecutor}. Yield reads
     *  the SubagentRun row spawn just inserted; the default name-keyed group
     *  would put each tool in its own VT and yield's findById could race
     *  spawn's INSERT commit. */
    @Override
    public String serializationGroup() { return "subagent_lifecycle"; }

    @Override
    public String execute(String argsJson, Agent callingAgent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();

        // JCLAW-498: batch collect — runIds[] or all=true block-awaits MANY async
        // children (the fan-out from subagent_spawn tasks[]) and returns them all
        // inline. Works in chat and task fires; no YIELDED sentinel.
        var batchRunIds = collectBatchRunIds(args);
        if (batchRunIds != null) {
            if (batchRunIds.isEmpty()) return nothingToCollect();
            return SubagentSpawnTool.awaitAsyncOutcomes(batchRunIds);
        }

        var parsed = parseYieldArgs(args);
        if (parsed.error() != null) return parsed.error();

        // JCLAW-497: inside a task fire there is no conversation to resume into,
        // so yield BLOCK-AWAITS the async child's outcome and returns it inline —
        // the agent then synthesizes and the task delivers the real output —
        // rather than emitting the YIELDED sentinel that suspends a chat turn.
        // (The chat announce/resume side effects below — the yielded flag and the
        // yield-timeout watchdog — are deliberately skipped on this path.)
        if (ToolContext.taskRunId() != null) {
            if (parsed.runId() == null) {
                return "Error: subagent_yield inside a task run requires the 'runId' "
                        + "returned by the async subagent_spawn call.";
            }
            return SubagentSpawnTool.awaitAsyncOutcome(parsed.runId());
        }

        final var parentAgentId = callingAgent.id;
        var callingConvId = ToolContext.conversationId();
        var target = Tx.run(() -> installYield(parsed, parentAgentId, callingConvId));
        var answer = target.answer();
        if (answer != null) return answer;
        var runId = target.runId();
        // Outside the transaction: the wait lasts as long as the child runs.
        if (target.blockAwait()) return SubagentSpawnTool.awaitAsyncOutcome(runId);

        // JCLAW-326: arm the yield-timeout watchdog now that the row is
        // flipped. The watchdog fires a synthetic TimeoutException into the
        // in-flight async future after timeoutSeconds elapse; the
        // SubagentSpawnTool await translates that to a TIMEOUT outcome and
        // the announce VT delivers the resume message. No-op when the run
        // isn't in the registry (already terminal between installYield's
        // commit and here — the announce VT will deliver whatever happened).
        SubagentRegistry.scheduleYieldTimeout(runId, parsed.timeoutSeconds());

        // Sentinel payload — AgentRunner scans tool-result text for
        // YIELD_SENTINEL_PREFIX to recognize the yield and break out of the
        // tool-call loop cleanly. Keeping the shape data-only (no
        // ThreadLocal, no exception) makes the suspend mechanism visible in
        // the persisted message log and easy to reason about post-hoc.
        var payload = new LinkedHashMap<String, Object>();
        payload.put("action", "yielded");
        payload.put(PARAM_RUN_ID, String.valueOf(runId));
        return GsonHolder.GSON.toJson(payload, Map.class);
    }

    /** An all=true with nothing outstanding: the scope's finished runs, which a runIds yield reads back,
     *  or a plain no when there are none. */
    private static String nothingToCollect() {
        var finished = SubagentRunStore.recentFinished();
        if (finished.isEmpty()) return NOTHING_TO_COLLECT;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("results", List.of());
        payload.put("count", 0);
        payload.put("finished", finished);
        payload.put("note", FINISHED_NOTE);
        return GsonHolder.GSON.toJson(payload, Map.class);
    }

    /** JCLAW-498: resolve a batch-collect request — {@code all:true} collects every
     *  outstanding async child this parent fanned out (current scope); {@code
     *  runIds:[...]} collects exactly those. Returns null when neither is present
     *  (the single-yield path takes over). */
    private static @Nullable List<Long> collectBatchRunIds(JsonObject args) {
        if (args.has("all") && !args.get("all").isJsonNull() && args.get("all").getAsBoolean()) {
            return SubagentSpawnTool.outstandingForCurrentScope();
        }
        if (args.has(PARAM_RUN_IDS) && args.get(PARAM_RUN_IDS).isJsonArray()) {
            var list = new ArrayList<Long>();
            for (var el : args.get(PARAM_RUN_IDS).getAsJsonArray()) {
                try {
                    list.add(el.getAsLong());
                } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException _) {
                    // skip a non-numeric entry rather than failing the whole collect
                }
            }
            return list.isEmpty() ? null : list;
        }
        return null;
    }

    /** Parsed-args bundle. {@code error} non-null short-circuits execute.
     *  Exactly one of {@code runId} / {@code conversationId} is populated; the
     *  other is null. */
    private record ParsedArgs(@Nullable String error, @Nullable Long runId, @Nullable Long conversationId, int timeoutSeconds) {
        static ParsedArgs fail(String msg) { return new ParsedArgs(msg, null, null, 0); }
        static ParsedArgs ok(@Nullable Long runId, @Nullable Long convId, int timeoutSeconds) {
            return new ParsedArgs(null, runId, convId, timeoutSeconds);
        }
    }

    private static ParsedArgs parseYieldArgs(JsonObject args) {
        var runIdStr = JsonArgs.optString(args, PARAM_RUN_ID);
        var convIdStr = JsonArgs.optString(args, PARAM_CONVERSATION_ID);
        if ((runIdStr == null || runIdStr.isBlank())
                && (convIdStr == null || convIdStr.isBlank())) {
            return ParsedArgs.fail("Error: one of 'runId' or 'conversationId' is required.");
        }
        Long runId = null;
        if (runIdStr != null && !runIdStr.isBlank()) {
            try {
                runId = Long.parseLong(runIdStr);
            } catch (NumberFormatException _) {
                return ParsedArgs.fail("Error: 'runId' must be a numeric run id (got '" + runIdStr + "').");
            }
        }
        Long convId = null;
        if (runId == null && convIdStr != null && !convIdStr.isBlank()) {
            try {
                convId = Long.parseLong(convIdStr);
            } catch (NumberFormatException _) {
                return ParsedArgs.fail("Error: 'conversationId' must be numeric (got '" + convIdStr + "').");
            }
        }
        var yieldDefault = defaultYieldTimeoutSeconds();
        var timeout = JsonArgs.optInt(args, PARAM_TIMEOUT_SECONDS, yieldDefault);
        // 0 is a load-bearing sentinel: explicitly disables the yield watchdog
        // so the parent parks until the child terminates naturally via its own
        // runTimeoutSeconds. Negative values are nonsense — coerce to the default.
        // SubagentRegistry.scheduleYieldTimeout already no-ops for non-positive
        // inputs, so storing 0 on the row + handing it to the scheduler is the
        // entire "no timeout" implementation.
        if (timeout < 0) timeout = yieldDefault;
        if (timeout > MAX_TIMEOUT_SECONDS) timeout = MAX_TIMEOUT_SECONDS;
        return ParsedArgs.ok(runId, convId, timeout);
    }

    /** What a single-run yield does: return {@code answer} as it is, or block-await or suspend on {@code runId}. */
    private record Target(@Nullable String answer, long runId, boolean blockAwait) {
        static Target answering(String answer) { return new Target(answer, 0, false); }
    }

    /**
     * Look up the run (by runId first, falling back to conversationId),
     * validate parent ownership, then either answer (error /
     * already-terminal envelope), block-await a run that will never resume
     * the turn, or flip {@code yielded=true} + {@code yieldTimeoutSeconds}
     * to suspend on it. Must be called inside an active Tx.
     */
    private static Target installYield(ParsedArgs parsed, Long parentAgentId, @Nullable Long callingConvId) {
        SubagentRun run;
        long resolvedRunId;
        if (parsed.runId() != null) {
            resolvedRunId = parsed.runId();
            run = (SubagentRun) SubagentRun.findById(resolvedRunId);
            if (run == null) {
                return Target.answering("Error: no SubagentRun found for runId " + resolvedRunId + ".");
            }
        } else {
            run = SubagentRun.find(
                    "childConversation.id = ?1 ORDER BY startedAt DESC",
                    parsed.conversationId()).first();
            if (run == null) {
                return Target.answering("Error: no SubagentRun found for conversationId " + parsed.conversationId() + ".");
            }
            resolvedRunId = run.id;
        }
        // Parent-ownership gate: a parent can only yield into its own
        // running children. Yielding into a sibling's or unrelated
        // agent's run would let one parent hijack another's suspension
        // semantics.
        if (run.parentAgent == null || !parentAgentId.equals(run.parentAgent.id)) {
            return Target.answering("Error: run " + resolvedRunId + " is not owned by the calling agent.");
        }
        // The run's end resumes the conversation that started it: a yield from any other would hang this turn (JF-166).
        if (run.parentConversation == null || !run.parentConversation.id.equals(callingConvId)) {
            return Target.answering("Error: run " + resolvedRunId + " was not started from this conversation; "
                    + "a turn can wait only on a run its own conversation started.");
        }
        // Ahead of the status check, so a finished detached run is collected once, as runIds would collect it.
        if (SubagentAsyncRunner.awaitsInline(resolvedRunId)) {
            return new Target(null, resolvedRunId, true);
        }
        if (run.status != SubagentRun.Status.RUNNING) {
            return Target.answering(alreadyTerminalEnvelope(run, resolvedRunId));
        }
        run.yielded = true;
        run.yieldTimeoutSeconds = parsed.timeoutSeconds();
        run.save();
        return new Target(null, resolvedRunId, false);
    }

    /**
     * Race-fix follow-up: if the child finished between spawn returning
     * {status:RUNNING} and yield's findById acquiring the row, there's no
     * yield semantics to install — the async-finalize VT has either already
     * posted the announce (as SYSTEM since yielded was still false) or is
     * about to. Either way, the parent's suspend would wait for an event
     * that's already happened. Return the recorded outcome directly as a
     * structured "already_terminal" envelope so the LLM gets the answer
     * immediately rather than being told its yield failed. The
     * announce-as-SYSTEM stays visible in the chat UI; we just hand the
     * LLM a usable result for its current turn.
     */
    private static String alreadyTerminalEnvelope(SubagentRun run, long runId) {
        var alreadyDone = new LinkedHashMap<String, Object>();
        alreadyDone.put("action", "already_terminal");
        alreadyDone.put(PARAM_RUN_ID, String.valueOf(runId));
        alreadyDone.put("status", run.status.name());
        alreadyDone.put("reply", run.outcome != null ? run.outcome : "");
        if (run.childConversation != null) {
            alreadyDone.put("conversation_id", String.valueOf(run.childConversation.id));
        }
        return GsonHolder.GSON.toJson(alreadyDone, Map.class);
    }
}
