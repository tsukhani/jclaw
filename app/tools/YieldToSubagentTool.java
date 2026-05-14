package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.SubagentRun;
import services.Tx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-273: companion tool to {@link SpawnSubagentTool} for the
 * {@code sessions_yield} flow. The parent agent calls this tool with the
 * {@code run_id} returned from a prior {@code spawn_subagent} with
 * {@code async=true}; the tool flips {@link SubagentRun#yielded} to
 * {@code true} and returns a sentinel JSON payload that
 * {@link agents.AgentRunner} recognises to exit its tool-call loop without
 * emitting a final assistant reply.
 *
 * <p>The actual resume — the child's final reply landing as the parent's
 * next user-role message and the parent's loop re-invoking — happens later,
 * inside {@link SpawnSubagentTool#runAsyncAndAnnounce} when the async child
 * VT reaches its terminal state. This tool just records the parent's intent
 * to suspend; the announce VT reads {@link SubagentRun#yielded} to decide
 * which announce-shape (SYSTEM-role fire-and-forget vs USER-role resume) to
 * post.
 *
 * <p>Validation:
 * <ul>
 *   <li>{@code runId} is required and must reference an existing
 *       {@link SubagentRun} row.</li>
 *   <li>The row's {@link SubagentRun#status} must be
 *       {@link SubagentRun.Status#RUNNING}; yielding into a terminal run is
 *       a no-op programming error and surfaces a clear error string.</li>
 *   <li>The row's {@code parentAgent} must equal the calling agent — a
 *       parent can only yield into its own running children, not into a
 *       sibling or unrelated agent's run.</li>
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
                Required: `runId` (the run id returned from the prior `spawn_subagent` call).""";
    }

    @Override
    public String summary() {
        return "Suspend this turn until an async subagent completes; its reply becomes your next user message.";
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put("runId", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Run id returned by a prior spawn_subagent call with async=true (required)."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
                SchemaKeys.REQUIRED, List.of("runId")
        );
    }

    /** Mutates a {@link SubagentRun} row; share with {@link SpawnSubagentTool}'s
     *  parallel-safe stance (false) so two concurrent yields against the same
     *  run never race. */
    @Override
    public boolean parallelSafe() { return false; }

    @Override
    public String execute(String argsJson, Agent callingAgent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var runIdStr = optString(args, "runId");
        if (runIdStr == null || runIdStr.isBlank()) {
            return "Error: 'runId' is required.";
        }
        long runId;
        try {
            runId = Long.parseLong(runIdStr);
        } catch (NumberFormatException _) {
            return "Error: 'runId' must be a numeric run id (got '" + runIdStr + "').";
        }

        final var parentAgentId = callingAgent.id;
        var error = Tx.run(() -> {
            var run = (SubagentRun) SubagentRun.findById(runId);
            if (run == null) {
                return "Error: no SubagentRun found for runId " + runId + ".";
            }
            // Parent-ownership gate: a parent can only yield into its own
            // running children. Yielding into a sibling's or unrelated
            // agent's run would let one parent hijack another's suspension
            // semantics.
            if (run.parentAgent == null || !parentAgentId.equals(run.parentAgent.id)) {
                return "Error: runId " + runId + " is not owned by the calling agent.";
            }
            // Yielding into a terminal run is a programming error: the child
            // already finished, so there's nothing to await. Surface a clear
            // error rather than silently flipping a column nobody reads.
            if (run.status != SubagentRun.Status.RUNNING) {
                return "Error: runId " + runId + " is not RUNNING (status=" + run.status + ").";
            }
            run.yielded = true;
            run.save();
            return null;
        });
        if (error != null) return error;

        // Sentinel payload — AgentRunner scans tool-result text for
        // YIELD_SENTINEL_PREFIX to recognise the yield and break out of the
        // tool-call loop cleanly. Keeping the shape data-only (no
        // ThreadLocal, no exception) makes the suspend mechanism visible in
        // the persisted message log and easy to reason about post-hoc.
        var payload = new LinkedHashMap<String, Object>();
        payload.put("action", "yielded");
        payload.put("runId", String.valueOf(runId));
        return utils.GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    private static String optString(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }
}
