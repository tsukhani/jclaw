package tools;

import models.Agent;
import models.SubagentRun;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.SubagentRegistry;

/**
 * JCLAW-677: recursion caps for subagent
 * spawning, extracted from {@link SubagentSpawnTool}. Depth/breadth enforcement
 * reads the operator-editable limits off the facade's config keys.
 */
final class SubagentLimits {

    /** Soft cap on how far we walk the parent chain when computing depth,
     *  so a cycle (shouldn't happen, but defense-in-depth) can't spin
     *  forever. Far above any plausible depth limit. */
    private static final int MAX_DEPTH_WALK = 64;

    private SubagentLimits() {}

    /** {@code refusal} non-null refuses the spawn; otherwise the child runs at {@code childDepth}. */
    record Admission(@Nullable String refusal, int childDepth) {}

    /**
     * Refuses a spawn that would violate either recursion cap, or admits it at the child's depth. Must be
     * called inside a {@link services.Tx#run} block on the spawning thread: it touches the persisted
     * {@code parentAgent} chain, queries {@link SubagentRun}, and reads the run this thread executes.
     *
     * <p>Race note: two concurrent spawns from the same parent could both
     * see "N-1 RUNNING" and both proceed, transiently exceeding the breadth
     * cap by one. In practice this is rare (the parent LLM
     * issues tool calls sequentially within a turn); accepted as an
     * advisory limit for now. A {@code SELECT ... FOR UPDATE} on the parent
     * Agent row would close the window if/when it matters.
     */
    static Admission enforceRecursionLimits(Agent parentAgent) {
        return enforceRecursionLimits(parentAgent, 1);
    }

    /** JCLAW-498: as above, but for spawning {@code additionalChildren} at once
     *  (batch fan-out) — the breadth check refuses when the running count plus the
     *  requested count would exceed the cap, so a single fan-out can't blow it. */
    static Admission enforceRecursionLimits(Agent parentAgent, int additionalChildren) {
        // Read from the runtime Config table so the Settings page can edit
        // these without a restart. ConfigService.getInt falls back to the
        // hard-coded default when the row is absent or unparseable; values
        // <= 0 are also coerced to the default so an operator typo can't
        // silently disable the cap.
        var depthLimit = readPositiveIntConfig(SubagentSpawnTool.DEPTH_LIMIT_KEY,
                SubagentSpawnTool.DEFAULT_DEPTH_LIMIT);
        var breadthLimit = readPositiveIntConfig(SubagentSpawnTool.BREADTH_LIMIT_KEY,
                SubagentSpawnTool.DEFAULT_BREADTH_LIMIT);

        // An agent_spawn callee has no parentAgent, so its agent chain alone would count it as top-level.
        var currentDepth = Math.max(depthOf(parentAgent), SubagentRegistry.currentRunDepth());
        // Spawning would create a child at depth currentDepth+1; refuse when that exceeds the limit.
        if (currentDepth >= depthLimit) {
            return refuse("depth limit %d exceeded (current depth: %d)."
                    .formatted(depthLimit, currentDepth));
        }

        // Re-fetch the parent inside this tx for a managed reference. The
        // count query takes the managed instance to keep Hibernate happy.
        var managed = (Agent) Agent.findById(parentAgent.id);
        if (managed == null) {
            // The spawning agent vanished between the controller and here.
            // Surface a clear refusal rather than spinning into bootstrap.
            return refuse("parent agent %d not found.".formatted(parentAgent.id));
        }
        long running = SubagentRun.count(
                "parentAgent = ?1 AND status = ?2",
                managed, SubagentRun.Status.RUNNING);
        if (running + additionalChildren > breadthLimit) {
            return refuse(additionalChildren == 1
                    ? "breadth limit %d exceeded (running children: %d).".formatted(breadthLimit, running)
                    : "breadth limit %d exceeded: %d already running + %d requested.".formatted(
                            breadthLimit, running, additionalChildren));
        }
        return new Admission(null, currentDepth + 1);
    }

    private static Admission refuse(String reason) {
        return new Admission(reason, 0);
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
}
