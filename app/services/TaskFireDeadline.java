package services;

import play.Play;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * JCLAW-803: bounds how long a single {@link models.Task} fire may run.
 *
 * <p>db-scheduler's dead-execution detection cannot see a hung-but-heartbeating
 * fire — the heartbeat on {@code scheduled_tasks.last_heartbeat} is refreshed by
 * db-scheduler's housekeeper, not by the fire itself — so a fire wedged in an
 * agent loop would run forever, holding a virtual thread and re-firing on every
 * revive after a restart. This watchdog arms a one-shot timer per fire that
 * flips the fire's {@link TaskRunRegistry} cancel flag when the deadline
 * elapses; {@link agents.AgentRunner}'s tool-loop checkpoints observe the flag
 * and bail with {@link agents.RunCancelledException} at the next safe boundary.
 *
 * <p><b>Cooperative, never preemptive.</b> The timer does NOT interrupt the
 * fire's thread: a {@code Thread.interrupt()} during a blocked H2
 * {@code FileChannel} write closes the MVStore file out from under the JVM (the
 * JDK NIO contract — see {@link TaskRunRegistry} / {@link SubagentRegistry}).
 * The cancel lands at the next checkpoint (top of an LLM round, between tool
 * calls), so a fire wedged <em>inside</em> a single un-timed-out socket read is
 * not bounded by this alone; upstream LLM/tool HTTP read timeouts cover that
 * case (tracked separately).
 *
 * <p>The scheduler is a single <em>platform</em> daemon thread (mirroring
 * {@code LuceneIndexer}'s commit scheduler), so arming a timer per fire never
 * routes through the ForkJoinPool delay path that trips JDK-8373224's
 * virtual-thread {@code Thread.sleep} starvation.
 */
public final class TaskFireDeadline {

    private TaskFireDeadline() {}

    /** Config key for the per-fire wall-clock bound, in seconds. Blank /
     *  non-numeric falls back to {@link #DEFAULT_MAX_DURATION_SECONDS}; a
     *  non-positive value disables the watchdog. */
    static final String MAX_DURATION_PROPERTY = "jclaw.tasks.fire.maxDurationSeconds";

    /** Default per-fire bound: 10 minutes. Generous for a multi-round agent
     *  loop, but finite so a wedged fire cannot run indefinitely. */
    static final long DEFAULT_MAX_DURATION_SECONDS = Duration.ofMinutes(10).toSeconds();

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "jclaw-task-fire-deadline");
                t.setDaemon(true);
                return t;
            });

    /** Run ids the watchdog cancelled because they blew the deadline (as
     *  opposed to an operator cancel), so the fire path can pick the right
     *  close-out note. Cleared by {@link #disarm}. */
    private static final Set<Long> TIMED_OUT = ConcurrentHashMap.newKeySet();

    /**
     * Arm the configured deadline for {@code taskRunId}. Returns the
     * {@link ScheduledFuture} so the fire path can {@link #disarm} it on normal
     * completion, or {@code null} when the watchdog is disabled (non-positive
     * configured duration) or the id is null.
     */
    public static ScheduledFuture<?> arm(Long taskRunId) {
        return arm(taskRunId, maxDurationSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Test seam: arm with an explicit delay/unit, bypassing the config read (so
     * a unit test doesn't mutate the process-global {@code Play.configuration}).
     */
    static ScheduledFuture<?> arm(Long taskRunId, long delay, TimeUnit unit) {
        if (taskRunId == null || delay <= 0) return null;
        return SCHEDULER.schedule(() -> fire(taskRunId, delay, unit), delay, unit);
    }

    private static void fire(Long taskRunId, long delay, TimeUnit unit) {
        // Tag as timed-out BEFORE flipping the cancel flag: a fire path that
        // observes the flag at its next checkpoint is then guaranteed — via the
        // AtomicBoolean's happens-before — to also observe this tag when it calls
        // wasTimedOut(). requestCancel returns false when the run already
        // terminated and unregistered; then there is nothing to time out, so
        // untag (disarm may already have run).
        TIMED_OUT.add(taskRunId);
        if (TaskRunRegistry.requestCancel(taskRunId)) {
            EventLogger.warn("task", null, null,
                    "TaskFireDeadline: task run %d exceeded its %d %s max duration; requesting cooperative cancel"
                            .formatted(taskRunId, delay, unit.toString().toLowerCase()));
        } else {
            TIMED_OUT.remove(taskRunId);
        }
    }

    /**
     * Whether the watchdog (not an operator) cancelled {@code taskRunId}. Read
     * by the fire path's {@link agents.RunCancelledException} handler to pick
     * the close-out note; a peek, so it stays true until {@link #disarm} clears
     * it in the fire's {@code finally}.
     */
    public static boolean wasTimedOut(Long taskRunId) {
        return taskRunId != null && TIMED_OUT.contains(taskRunId);
    }

    /**
     * Disarm the deadline for a fire that is ending. Cancels the pending timer
     * (a no-op if it already fired) so it doesn't linger in the scheduler queue,
     * and clears the {@code TIMED_OUT} slot. Safe to call with a {@code null}
     * future (watchdog disabled) or a {@code null} id.
     */
    public static void disarm(ScheduledFuture<?> future, Long taskRunId) {
        if (future != null) future.cancel(false);
        if (taskRunId != null) TIMED_OUT.remove(taskRunId);
    }

    private static long maxDurationSeconds() {
        var raw = Play.configuration.getProperty(MAX_DURATION_PROPERTY);
        if (raw == null || raw.isBlank()) return DEFAULT_MAX_DURATION_SECONDS;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException _) {
            return DEFAULT_MAX_DURATION_SECONDS;
        }
    }
}
