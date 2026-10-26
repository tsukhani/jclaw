package services;

import models.SubagentRun;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * JCLAW-803 / JCLAW-1567: bounds a single {@link models.Task} fire. It is cut after
 * {@link #IDLE_KEY} seconds with no activity, its own or that of a subagent run it started that is
 * still working, and after {@link #MAX_DURATION_KEY} seconds however busy.
 *
 * <p>db-scheduler's dead-execution detection cannot see a hung-but-heartbeating
 * fire — the heartbeat on {@code scheduled_tasks.last_heartbeat} is refreshed by
 * db-scheduler's housekeeper, not by the fire itself — so a fire wedged in an
 * agent loop would run forever, holding a virtual thread and re-firing on every
 * revive after a restart. When a limit passes, the watchdog flips the fire's
 * {@link TaskRunRegistry} cancel flag; {@link agents.AgentRunner}'s tool-loop checkpoints observe
 * the flag and bail with {@link agents.RunCancelledException} at the next safe boundary, and
 * {@link SubagentRegistry#stopWhenTaskRunCancelled} stops the subagents the fire is waiting on.
 *
 * <p><b>Cooperative, never preemptive.</b> The watchdog does NOT interrupt the
 * fire's thread: a {@code Thread.interrupt()} during a blocked H2
 * {@code FileChannel} write closes the MVStore file out from under the JVM (the
 * JDK NIO contract — see {@link TaskRunRegistry} / {@link SubagentRegistry}).
 *
 * <p>The scheduler is a single <em>platform</em> daemon thread (mirroring
 * {@code LuceneIndexer}'s commit scheduler), so a watchdog per fire never
 * routes through the ForkJoinPool delay path that trips JDK-8373224's
 * virtual-thread {@code Thread.sleep} starvation.
 */
public final class TaskFireDeadline {

    private TaskFireDeadline() {}

    /** Settings &gt; Tasks: the absolute per-fire bound, in seconds. Non-positive turns it off. */
    static final String MAX_DURATION_KEY = "tasks.fireMaxDurationSeconds";

    /** Settings &gt; Tasks: seconds a fire may go without activity. Non-positive turns it off. */
    static final String IDLE_KEY = "tasks.fireIdleSeconds";

    static final long DEFAULT_MAX_DURATION_SECONDS = Duration.ofMinutes(30).toSeconds();

    static final long DEFAULT_IDLE_SECONDS = Duration.ofMinutes(10).toSeconds();

    private static final Duration CHECK_EVERY = Duration.ofSeconds(15);

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "jclaw-task-fire-deadline");
                t.setDaemon(true);
                return t;
            });

    /** Run ids the watchdog canceled (as opposed to an operator cancel), with the note saying why.
     *  Cleared by {@link #disarm}. */
    private static final Map<Long, String> TIMED_OUT = new ConcurrentHashMap<>();

    /**
     * Arm the configured limits for {@code taskRunId}. Returns the watchdog's future so the fire
     * path can {@link #disarm} it, or {@code null} when both limits are off or the id is null.
     */
    public static @Nullable ScheduledFuture<?> arm(@Nullable Long taskRunId) {
        return arm(taskRunId,
                Duration.ofSeconds(ConfigService.getLong(IDLE_KEY, DEFAULT_IDLE_SECONDS)),
                Duration.ofSeconds(ConfigService.getLong(MAX_DURATION_KEY, DEFAULT_MAX_DURATION_SECONDS)),
                CHECK_EVERY);
    }

    /** Test seam: arm with explicit limits and check interval, bypassing the config read. */
    static @Nullable ScheduledFuture<?> arm(@Nullable Long taskRunId, Duration idle, Duration ceiling, Duration every) {
        if (taskRunId == null || (!idle.isPositive() && !ceiling.isPositive())) return null;
        long startNanos = System.nanoTime();
        return SCHEDULER.scheduleWithFixedDelay(() -> check(taskRunId, startNanos, idle, ceiling),
                every.toNanos(), every.toNanos(), TimeUnit.NANOSECONDS);
    }

    private static void check(Long taskRunId, long startNanos, Duration idle, Duration ceiling) {
        // A throw would end every later check of this fire, silently.
        try {
            if (TIMED_OUT.containsKey(taskRunId)) return;
            if (ceiling.isPositive() && System.nanoTime() - startNanos >= ceiling.toNanos()) {
                fire(taskRunId, "Cancelled: exceeded max run duration of %d seconds".formatted(ceiling.toSeconds()));
            } else if (idle.isPositive() && idleNanos(taskRunId, idle) >= idle.toNanos()) {
                fire(taskRunId, "Cancelled: no activity for %d seconds".formatted(idle.toSeconds()));
            }
        } catch (RuntimeException e) {
            EventLogger.warn("task", null, null,
                    "TaskFireDeadline: could not check task run %d: %s".formatted(taskRunId, e.getMessage()));
        }
    }

    /** Since the fire, or a subagent run it started that is still working, last recorded activity. */
    private static long idleNanos(Long taskRunId, Duration idle) {
        long quietest = TaskRunRegistry.nanosSinceActivity(taskRunId);
        // Also -1 once the fire has ended. Only a fire quiet past its limit pays for the query.
        if (quietest < idle.toNanos()) return quietest;
        List<Long> running = Tx.run(() -> JPA.em().createQuery("SELECT sr.id FROM SubagentRun sr"
                        + " WHERE sr.parentTaskRun.id = :taskRun AND sr.status = :running", Long.class)
                .setParameter("taskRun", taskRunId)
                .setParameter("running", SubagentRun.Status.RUNNING)
                .getResultList());
        for (var runId : running) {
            long since = SubagentRegistry.nanosSinceActivity(runId);
            if (since >= 0) quietest = Math.min(quietest, since);
        }
        return quietest;
    }

    private static void fire(Long taskRunId, String note) {
        // Tag BEFORE flipping the cancel flag: a fire path that observes the flag at its next
        // checkpoint is then guaranteed to also see the note. requestCancel returns false when the
        // run already terminated and unregistered; then there is nothing to time out.
        TIMED_OUT.put(taskRunId, note);
        if (TaskRunRegistry.requestCancel(taskRunId)) {
            EventLogger.warn("task", null, null,
                    "TaskFireDeadline: task run %d: %s; requesting cooperative cancel".formatted(taskRunId, note));
        } else {
            TIMED_OUT.remove(taskRunId);
        }
    }

    /**
     * Whether the watchdog (not an operator) canceled {@code taskRunId}; a peek, so it stays true
     * until {@link #disarm} clears it in the fire's {@code finally}.
     */
    public static boolean wasTimedOut(@Nullable Long taskRunId) {
        return taskRunId != null && TIMED_OUT.containsKey(taskRunId);
    }

    /** The close-out note for a fire the watchdog canceled, or null when it did not. */
    public static @Nullable String timeoutNote(@Nullable Long taskRunId) {
        return taskRunId == null ? null : TIMED_OUT.get(taskRunId);
    }

    /**
     * Disarm the watchdog for a fire that is ending, and clear its {@code TIMED_OUT} slot. Safe to
     * call with a {@code null} future (watchdog disabled) or a {@code null} id.
     */
    public static void disarm(@Nullable ScheduledFuture<?> future, @Nullable Long taskRunId) {
        if (future != null) future.cancel(false);
        if (taskRunId != null) TIMED_OUT.remove(taskRunId);
    }
}
