package services;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JCLAW-414: in-memory registry of in-flight {@link models.TaskRun}s and their
 * cooperative-cancellation flags, so an operator can abort a running task fire
 * from the Tasks UI ({@code POST /api/task-runs/{id}/cancel}).
 *
 * <p>Mirrors {@link SubagentRegistry} but is thinner: a task fire is launched
 * fire-and-forget by db-scheduler's virtual thread, so there is no awaiting
 * caller to unblock — we only need the boolean flag that
 * {@link agents.AgentRunner#checkTaskRunCancel} polls at the tool loop's safe
 * checkpoints (top of each LLM round, between tool calls). When the flag is
 * set, the loop throws {@link agents.RunCancelledException} and the task path
 * closes the run as {@link models.TaskRun.Status#CANCELLED}.
 *
 * <p><b>Cooperative, never preemptive.</b> Like {@link SubagentRegistry}, this
 * deliberately does NOT capture or interrupt the carrier thread — a
 * {@code Thread.interrupt()} during a blocked H2 {@code FileChannel} write
 * closes the MVStore file out from under the JVM (the JDK NIO contract). The
 * cancel takes effect at the next checkpoint, not mid-LLM-call or mid-tool.
 *
 * <p>Per-JVM and process-local: jclaw runs as a single JVM in prod, and a run
 * only executes where its db-scheduler pick fired, so the registry always holds
 * the entry for a run executing on this node.
 */
public final class TaskRunRegistry {

    private TaskRunRegistry() {}

    /** taskRunId → cooperative-cancellation flag. Present iff the run is
     *  currently executing on this JVM. */
    private static final Map<Long, AtomicBoolean> ACTIVE = new ConcurrentHashMap<>();

    /** Register {@code taskRunId} as in-flight with a fresh (un-flipped) cancel
     *  flag. Called by {@link services.TaskExecutor} right after the RUNNING
     *  TaskRun row is opened, so {@link #isCancelled} works the instant the
     *  entry is in the map. No-op on a null id. */
    public static void register(Long taskRunId) {
        if (taskRunId == null) return;
        ACTIVE.put(taskRunId, new AtomicBoolean(false));
    }

    /** Remove the entry for {@code taskRunId}. Called from a {@code finally}
     *  block in the fire path so the slot clears on any terminal outcome. */
    public static void unregister(Long taskRunId) {
        if (taskRunId == null) return;
        ACTIVE.remove(taskRunId);
    }

    /**
     * Cooperative-cancellation poll used by {@link agents.AgentRunner}'s task
     * checkpoints. True iff an entry exists for {@code taskRunId} and its flag
     * has been flipped by {@link #requestCancel}. Cheap — one hash lookup, one
     * volatile read.
     */
    public static boolean isCancelled(Long taskRunId) {
        if (taskRunId == null) return false;
        var flag = ACTIVE.get(taskRunId);
        return flag != null && flag.get();
    }

    /**
     * Flip the cooperative-cancellation flag for {@code taskRunId} so the tool
     * loop bails at its next safe checkpoint. Returns {@code true} when an
     * entry existed (the run is in-flight on this JVM and will observe the
     * flag), {@code false} when none is registered (already finished, or never
     * ran here — the caller still marks the row CANCELLED in the DB). Does NOT
     * write the DB or touch any thread — terminal-status persistence is the
     * caller's job (see {@code ApiTasksController.cancelRun}).
     */
    public static boolean requestCancel(Long taskRunId) {
        if (taskRunId == null) return false;
        var flag = ACTIVE.get(taskRunId);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }

    /** Snapshot of the currently-registered run ids. For tests + introspection. */
    public static Set<Long> activeRunIds() {
        return Set.copyOf(ACTIVE.keySet());
    }

    /** Test-only: clear the entire registry. Production code must never call
     *  this — concurrent cancels would race. */
    public static void clear() {
        ACTIVE.clear();
    }
}
