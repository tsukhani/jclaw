package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.SubagentRun;
import services.SubagentRegistry;
import services.Tx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-273 / JCLAW-326: companion tool to {@link SpawnSubagentTool} for the
 * {@code sessions_yield} flow. The parent agent calls this tool with the
 * {@code run_id} (or alternatively {@code conversation_id}) returned from a
 * prior {@code spawn_subagent} with {@code async=true}; the tool flips
 * {@link SubagentRun#yielded} to {@code true} and returns a sentinel JSON
 * payload that {@link agents.AgentRunner} recognises to exit its tool-call
 * loop without emitting a final assistant reply.
 *
 * <p>The actual resume — the child's final reply landing as the parent's
 * next user-role message and the parent's loop re-invoking — happens later,
 * inside {@link SpawnSubagentTool#runAsyncAndAnnounce} when the async child
 * VT reaches its terminal state. This tool just records the parent's intent
 * to suspend; the announce VT reads {@link SubagentRun#yielded} to decide
 * which announce-shape (SYSTEM-role fire-and-forget vs USER-role resume) to
 * post.
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
 * <p>Validation:
 * <ul>
 *   <li>Exactly one of {@code runId} / {@code conversationId} must resolve a
 *       run; both blank → error.</li>
 *   <li>The row's {@link SubagentRun#status} must be
 *       {@link SubagentRun.Status#RUNNING}; yielding into a terminal run is
 *       a no-op programming error and surfaces a structured
 *       {@code already_terminal} envelope so the LLM still gets the recorded
 *       outcome.</li>
 *   <li>The row's {@code parentAgent} must equal the calling agent — a
 *       parent can only yield into its own running children, not into a
 *       sibling or unrelated agent's run.</li>
 *   <li>{@code timeoutSeconds}, when provided, must be in {@code [1, 3600]};
 *       out-of-range values are clamped silently.</li>
 * </ul>
 * Invalid calls return a descriptive error string and do not mutate any
 * state, matching the {@link SpawnSubagentTool} validation idiom.
 *
 * <p>Two-step shape (vs combining yield into {@code spawn_subagent}): the
 * AC explicitly references a separate companion tool, and the two-step
 * shape composes cleanly with the JCLAW-270 async flow — the parent gets
 * the {@code run_id} from {@code spawn_subagent}, can optionally do other
 * work in the same turn, then yields on a later tool call when it wants to
 * suspend. A combined {@code spawn_subagent {async:true, yield:true}}
 * would forfeit that intermediate-work window.
 */
public class YieldToSubagentTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "yield_to_subagent";

    private static final String PARAM_RUN_ID = "runId";
    private static final String PARAM_CONVERSATION_ID = "conversationId";
    private static final String PARAM_TIMEOUT_SECONDS = "timeoutSeconds";

    /** Default resume budget when the caller does not supply
     *  {@code timeoutSeconds}. Matches the JCLAW-326 AC. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** Hard upper bound on {@code timeoutSeconds}. Anything larger is clamped
     *  so a stray million-second value can't pin a watchdog VT indefinitely. */
    public static final int MAX_TIMEOUT_SECONDS = 3600;

    /** Marker that {@link agents.AgentRunner} scans for on tool-result text
     *  to recognise a successful yield call and break out of its tool-call
     *  loop without persisting a final assistant reply. */
    public static final String YIELD_SENTINEL_PREFIX = "{\"action\":\"yielded\"";

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "pause"; }

    @Override
    public String shortDescription() {
        return "Suspend this turn until an async-spawned subagent completes; its reply lands as your next user message.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction("yield",
                        "Suspend the current turn and resume when the named subagent terminates."));
    }

    @Override
    public String description() {
        return """
                Suspend this turn until the named async-spawned subagent finishes. When invoked, \
                your AgentRunner loop exits without emitting a final assistant reply; once the \
                child terminates (completion, failure, or timeout), its final output is delivered \
                back as your next user-role message and your loop resumes from there. \
                Use this after a `spawn_subagent` call with `async=true` when you want to block \
                your logical turn on the child's result rather than continuing in parallel. \
                Provide EITHER `runId` (the run id returned from the prior `spawn_subagent` call) \
                OR `conversationId` (the child conversation id from the same return payload); \
                when both are provided, `runId` wins. \
                Optional: `timeoutSeconds` (1-3600, default 300) — caller-tightened resume budget; \
                a synthetic TIMEOUT outcome is delivered if the child has not finished by then.""";
    }

    @Override
    public String summary() {
        return "Suspend this turn until an async subagent completes; its reply becomes your next user message.";
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put(PARAM_RUN_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Run id returned by a prior spawn_subagent call with async=true. "
                        + "Provide this OR conversationId (runId wins when both set)."));
        props.put(PARAM_CONVERSATION_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Child conversation id returned by the prior spawn_subagent call. "
                        + "Resolves to the most-recent SubagentRun whose childConversation "
                        + "matches; ignored when runId is provided."));
        props.put(PARAM_TIMEOUT_SECONDS, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Caller-tightened resume budget in seconds (1-" + MAX_TIMEOUT_SECONDS
                        + ", default " + DEFAULT_TIMEOUT_SECONDS
                        + "). If the child hasn't terminated by then a synthetic TIMEOUT "
                        + "announce resumes your turn."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props
        );
    }

    /** Mutates a {@link SubagentRun} row; share with {@link SpawnSubagentTool}'s
     *  parallel-safe stance (false) so two concurrent yields against the same
     *  run never race. */
    @Override
    public boolean parallelSafe() { return false; }

    /** Subagent-lifecycle group: shared with {@link SpawnSubagentTool} so a
     *  same-message {@code spawn_subagent} + {@code yield_to_subagent} pair
     *  serializes inside the {@link agents.ParallelToolExecutor}. Yield reads
     *  the SubagentRun row spawn just inserted; the default name-keyed group
     *  would put each tool in its own VT and yield's findById could race
     *  spawn's INSERT commit. */
    @Override
    public String serializationGroup() { return "subagent_lifecycle"; }

    @Override
    public String execute(String argsJson, Agent callingAgent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var parsed = parseYieldArgs(args);
        if (parsed.error() != null) return parsed.error();

        final var parentAgentId = callingAgent.id;
        // Either an error string (returned verbatim to the LLM) OR an
        // already-terminal structured JSON envelope (when the child raced
        // ahead of yield's lookup). Both paths short-circuit the sentinel
        // build below. The resolved run id is returned via the holder so the
        // watchdog scheduling below can use it without re-querying.
        var holder = new long[1];
        var shortCircuit = Tx.run(() -> installYield(parsed, parentAgentId, holder));
        if (shortCircuit != null) return shortCircuit;
        var runId = holder[0];

        // JCLAW-326: arm the yield-timeout watchdog now that the row is
        // flipped. The watchdog fires a synthetic TimeoutException into the
        // in-flight async future after timeoutSeconds elapse; the
        // SpawnSubagentTool await translates that to a TIMEOUT outcome and
        // the announce VT delivers the resume message. No-op when the run
        // isn't in the registry (already terminal between installYield's
        // commit and here — the announce VT will deliver whatever happened).
        SubagentRegistry.scheduleYieldTimeout(runId, parsed.timeoutSeconds());

        // Sentinel payload — AgentRunner scans tool-result text for
        // YIELD_SENTINEL_PREFIX to recognise the yield and break out of the
        // tool-call loop cleanly. Keeping the shape data-only (no
        // ThreadLocal, no exception) makes the suspend mechanism visible in
        // the persisted message log and easy to reason about post-hoc.
        var payload = new LinkedHashMap<String, Object>();
        payload.put("action", "yielded");
        payload.put(PARAM_RUN_ID, String.valueOf(runId));
        return utils.GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    /** Parsed-args bundle. {@code error} non-null short-circuits execute.
     *  Exactly one of {@code runId} / {@code conversationId} is populated; the
     *  other is null. */
    private record ParsedArgs(String error, Long runId, Long conversationId, int timeoutSeconds) {
        static ParsedArgs fail(String msg) { return new ParsedArgs(msg, null, null, 0); }
        static ParsedArgs ok(Long runId, Long convId, int timeoutSeconds) {
            return new ParsedArgs(null, runId, convId, timeoutSeconds);
        }
    }

    private static ParsedArgs parseYieldArgs(JsonObject args) {
        var runIdStr = optString(args, PARAM_RUN_ID);
        var convIdStr = optString(args, PARAM_CONVERSATION_ID);
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
        var timeout = optInt(args, PARAM_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
        if (timeout <= 0) timeout = DEFAULT_TIMEOUT_SECONDS;
        if (timeout > MAX_TIMEOUT_SECONDS) timeout = MAX_TIMEOUT_SECONDS;
        return ParsedArgs.ok(runId, convId, timeout);
    }

    /**
     * Look up the run (by runId first, falling back to conversationId),
     * validate parent ownership, then either short-circuit (error /
     * already-terminal envelope) or flip {@code yielded=true} +
     * {@code yieldTimeoutSeconds} and return null. {@code runIdOut[0]}
     * receives the resolved run id on success so the caller can arm the
     * watchdog without a re-query. Must be called inside an active Tx.
     */
    private static String installYield(ParsedArgs parsed, Long parentAgentId, long[] runIdOut) {
        SubagentRun run;
        long resolvedRunId;
        if (parsed.runId() != null) {
            resolvedRunId = parsed.runId();
            run = (SubagentRun) SubagentRun.findById(resolvedRunId);
            if (run == null) {
                return "Error: no SubagentRun found for runId " + resolvedRunId + ".";
            }
        } else {
            run = SubagentRun.find(
                    "childConversation.id = ?1 ORDER BY startedAt DESC",
                    parsed.conversationId()).first();
            if (run == null) {
                return "Error: no SubagentRun found for conversationId " + parsed.conversationId() + ".";
            }
            resolvedRunId = run.id;
        }
        // Parent-ownership gate: a parent can only yield into its own
        // running children. Yielding into a sibling's or unrelated
        // agent's run would let one parent hijack another's suspension
        // semantics.
        if (run.parentAgent == null || !parentAgentId.equals(run.parentAgent.id)) {
            return "Error: run " + resolvedRunId + " is not owned by the calling agent.";
        }
        if (run.status != SubagentRun.Status.RUNNING) {
            return alreadyTerminalEnvelope(run, resolvedRunId);
        }
        run.yielded = true;
        run.yieldTimeoutSeconds = parsed.timeoutSeconds();
        run.save();
        runIdOut[0] = resolvedRunId;
        return null;
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
        return utils.GsonHolder.INSTANCE.toJson(alreadyDone, Map.class);
    }

    private static String optString(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return fallback;
        try { return el.getAsInt(); } catch (RuntimeException _) { return fallback; }
    }
}
