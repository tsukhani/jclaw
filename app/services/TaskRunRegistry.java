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

    /**
     * JCLAW-803: Task ids with a fire live on this JVM. Distinct from
     * {@link #ACTIVE} (which is keyed by {@code taskRunId} for cancellation) —
     * this is keyed by {@code taskId} so a revived duplicate fire of the same
     * Task can be detected and dropped by {@link #tryClaimTask} before it opens
     * a second RUNNING TaskRun. Process-local by design: a {@code RUNNING} row
     * orphaned by a crashed prior JVM is absent here, so it is correctly re-fired
     * on recovery — only a fire still live in <em>this</em> process is blocked.
     */
    private static final Set<Long> ACTIVE_TASKS = ConcurrentHashMap.newKeySet();

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
     * JCLAW-803: atomically claim {@code taskId} as firing on this JVM. Returns
     * {@code true} when the claim is taken (no other live fire for this Task
     * here) and {@code false} when a fire is already in flight for it — in which
     * case the caller ({@link services.TaskExecutionHandler}) must drop the
     * duplicate revive rather than start a second concurrent fire. The claim
     * MUST be released via {@link #releaseTask} in a {@code finally} once the
     * fire terminates. A null id never blocks (returns {@code true}; the caller
     * handles undecodable instance ids on its own skip path).
     */
    public static boolean tryClaimTask(Long taskId) {
        if (taskId == null) return true;
        return ACTIVE_TASKS.add(taskId);
    }

    /** Release a claim taken by {@link #tryClaimTask}. No-op on a null id or an
     *  id that was never claimed. */
    public static void releaseTask(Long taskId) {
        if (taskId == null) return;
        ACTIVE_TASKS.remove(taskId);
    }

    /** True iff a fire for {@code taskId} is currently claimed on this JVM. For
     *  tests + introspection. */
    public static boolean isTaskActive(Long taskId) {
        return taskId != null && ACTIVE_TASKS.contains(taskId);
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
        ACTIVE_TASKS.clear();
    }
}
