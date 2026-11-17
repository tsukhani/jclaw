package jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import models.Task;
import models.TaskRun;
import org.jspecify.annotations.Nullable;
import play.jobs.Job;
import services.EventLogger;
import services.LostTaskDetector;
import services.RestartCutOff;
import services.TaskExecutionHandler;
import services.TaskRunRegistry;
import services.TaskSchedulingService;
import services.Tx;
import utils.AppClock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * JCLAW-21: at JVM start, register existing non-terminal
 * {@link Task} rows that don't already have a {@code scheduled_tasks}
 * row in db-scheduler. Closes the two gaps that come from running a
 * scheduler with persistent state alongside JClaw's Task table:
 *
 * <ol>
 *   <li><b>Migration:</b> Tasks created before the db-scheduler
 *   cutover exist as PENDING rows with {@link Task#nextRunAt} set
 *   but no {@code scheduled_tasks} row. They'd sit forever without
 *   this sweep — no other code path picks them up.</li>
 *
 *   <li><b>Crash recovery:</b> a JVM crash between an IMMEDIATE
 *   Task being persisted and {@link TaskSchedulingService#register}
 *   being called leaves the Task PENDING but unscheduled. This
 *   sweep heals that on the next start.</li>
 * </ol>
 *
 * <p><b>What this does NOT cover:</b>
 * <ul>
 *   <li>Re-firing a Task in {@link Task.Status#RUNNING}: a fire a stop of
 *   JClaw cut off never runs again (JF-355, {@link RestartCutOff}). This
 *   sweep closes the run it left and its Task; the row it left picked is
 *   {@link services.TaskDeadExecutionHandler}'s once db-scheduler declares
 *   it dead (30 s heartbeat × 4 misses = 120 s, per
 *   {@link DbSchedulerBootstrapJob}). JCLAW-258's visibility step still
 *   runs first: {@link LostTaskDetector#detect()} reconciles any RUNNING
 *   Task whose {@code scheduled_tasks.last_heartbeat} is older than 60 s
 *   to {@link Task.Status#LOST}. Both need the row: JCLAW-1103 added
 *   {@link #reconcileStrandedRunning} for a RUNNING Task that has lost
 *   it, which neither can see.</li>
 *
 *   <li>Tasks in terminal states (COMPLETED / FAILED / CANCELLED)
 *   — they shouldn't have rows in {@code scheduled_tasks} and we
 *   don't want to re-fire them.</li>
 *
 *   <li>CRON Tasks whose stored cron expression has drifted to be
 *   un-parseable since they were last registered. Logged but
 *   skipped — operators need to fix the expression via the API.</li>
 * </ul>
 *
 * <h2>Invocation</h2>
 * Called by {@link DbSchedulerBootstrapJob}'s gate thread
 * immediately after {@code scheduler.start()} so the ordering is
 * deterministic. Pre-fix this class was its own
 * {@code @OnApplicationStart} job, but Play 1.x doesn't order
 * sibling startup jobs — on some restarts it fired BEFORE the
 * bootstrap and logged "scheduler not bootstrapped; skipping
 * sweep", stranding pre-existing PENDING Tasks until the next
 * restart. Driving it from the bootstrap removes the race.
 *
 * <p>The class is still a Play {@link Job} for the test hook
 * {@link #runForTest} that exists so test classes can re-trigger
 * the sweep without restarting the JVM.
 */
public class BootConsistencyCheck extends Job<Void> {

    @Override
    public void doJob() {
        var scheduler = DbSchedulerBootstrapJob.scheduler();
        if (scheduler == null) {
            EventLogger.warn("task", null, null,
                    "BootConsistencyCheck: scheduler not bootstrapped; skipping sweep");
            return;
        }
        sweep(scheduler);
    }

    /**
     * The sweep itself, extracted so functional tests can drive it
     * directly with a stub {@link SchedulerClient} without spinning
     * up a live scheduler. Returns the count of Tasks newly
     * registered — operator-visible in the summary log line and
     * useful for tests as a yes/no signal.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Read the set of {@code task_instance} ids currently
     *       sitting in {@code scheduled_tasks} for our
     *       {@link TaskExecutionHandler#TASK_NAME}.</li>
     *   <li>Walk all alive Tasks. Skip any whose id is in the
     *       set (already scheduled — no action needed).</li>
     *   <li>For each orphan, call
     *       {@link TaskSchedulingService#register} which translates
     *       the Task's type into a fresh scheduled_tasks row.</li>
     * </ol>
     */
    public static int sweep(SchedulerClient scheduler) {
        return sweep(scheduler, AppClock.now());
    }

    /**
     * Sweep variant taking an explicit {@code bootCutoff} for orphaned-run
     * reconciliation. The production bootstrap captures this instant BEFORE
     * starting the scheduler (see {@link DbSchedulerBootstrapJob#doJob}) so a
     * re-fire opened by this generation's first poll — whose {@code startedAt}
     * is necessarily after the cutoff — is never mistaken for an orphan, while
     * every run left RUNNING by a PRIOR scheduler generation (started before
     * this bootstrap) is reconciled.
     *
     * <p>Using a per-bootstrap instant rather than the JVM start instant is what
     * makes the sweep correct under Play dev-mode hot reloads:
     * {@code @OnApplicationStart} re-runs (a fresh scheduler generation that
     * re-fires the overdue task) but the JVM — and thus
     * {@code RuntimeMXBean.getStartTime()} — does not change, so a JVM-start
     * cutoff would leave every prior-generation RUNNING run uncleaned and the
     * Tasks UI would accumulate stale "RUNNING" pills on each reload.
     */
    public static int sweep(SchedulerClient scheduler, Instant bootCutoff) {
        // JCLAW-258: surface LOST tasks first so the admin UI is correct
        // by the time the rest of the sweep finishes. Reads scheduled_tasks
        // directly, no scheduler interaction — runs cheaply when the table
        // is empty (the common case after a clean shutdown).
        int lost = LostTaskDetector.detect();
        if (lost > 0) {
            EventLogger.info("task", null, null,
                    "BootConsistencyCheck: %d Task(s) reconciled to LOST at startup"
                            .formatted(lost));
        }

        // JCLAW-410: close out per-fire run-history rows orphaned in RUNNING by a
        // prior scheduler generation (a fire that died mid-execution before
        // stamping a terminal status). The LOST/re-arm steps heal the Task;
        // without this the task_run row shows "RUNNING" with an ever-growing
        // elapsed timer forever for a run that isn't executing. {@code bootCutoff}
        // divides prior-generation runs from runs opened by this generation's
        // first poll, so a freshly-opened re-fire is treated as in-flight and
        // left alone.
        reconcileOrphanedRuns(bootCutoff);

        return reArmOrphans(scheduler);
    }

    /**
     * Flip every {@link TaskRun} left {@link TaskRun.Status#RUNNING} by a prior
     * scheduler generation to {@link TaskRun.Status#FAILED}, stamping {@code completedAt},
     * {@code durationMs}, and an {@code error} reason. A fire that dies mid-execution
     * (JVM crash/restart before it writes a terminal status) leaves its run-history
     * row RUNNING with {@code completedAt == null} — Task-level recovery never
     * touches it, so the UI shows an ever-growing "RUNNING" timer for a run that is
     * not executing (and nothing ever clears it: {@link TaskCleanupJob} only deletes
     * terminal Task trees past retention).
     *
     * <p>{@code bootCutoff} is the cutoff: only RUNNING runs whose {@code startedAt}
     * is strictly before it are reconciled. A fire that opened its row within THIS
     * scheduler generation started after the cutoff, so it is genuinely in-flight
     * and skipped — this is what makes the sweep safe to run alongside
     * db-scheduler's first poll without racing a freshly-opened run to FAILED.
     *
     * <p>Each run's Task is closed with it as {@link RestartCutOff} says, unless a fire of it is live on
     * this node: it is not run again.
     *
     * @param bootCutoff cutoff instant; runs started before it are considered orphaned
     * @return number of run rows flipped to FAILED
     */
    public static int reconcileOrphanedRuns(Instant bootCutoff) {
        var closedRuns = new ArrayList<TaskRun>();
        int count = Tx.run(() -> {
            var now = AppClock.now();
            int closed = 0;
            for (Object o : TaskRun.find("status = ?1 and startedAt < ?2",
                    TaskRun.Status.RUNNING, bootCutoff).fetch()) {
                var run = (TaskRun) o;
                run.status = TaskRun.Status.FAILED;
                run.completedAt = now;
                run.durationMs = Duration.between(run.startedAt, now).toMillis();
                if (run.error == null || run.error.isBlank()) run.error = RestartCutOff.runError(run.task);
                run.save();
                // JF-355: the task goes with its run, before reArmOrphans below would arm a one-shot again.
                if (!TaskRunRegistry.isTaskActive(run.task.id) && RestartCutOff.close(run.task)) closedRuns.add(run);
                closed++;
            }
            return closed;
        });
        for (var run : closedRuns) RestartCutOff.announce(run.task, run);
        if (count > 0) {
            long recurring = closedRuns.stream().filter(r -> RestartCutOff.isRecurring(r.task)).count();
            EventLogger.info("task", null, null,
                    ("BootConsistencyCheck: closed %d task run(s) a stop of JClaw cut off, none run again: %d one-shot"
                            + " task(s) end FAILED, %d recurring task(s) wait for their next occurrence")
                            .formatted(count, closedRuns.size() - recurring, recurring));
        }
        return count;
    }

    /**
     * Settle Tasks stuck in {@link Task.Status#RUNNING} with no
     * {@code scheduled_tasks} row: a recurring one returns to its alive state, so the
     * caller's re-arm pass registers a fresh row for it, and a one-shot one ends as
     * {@link RestartCutOff} says, since its fire already started.
     *
     * <p>No other sweep can see this state: {@link LostTaskDetector} reads
     * staleness <em>off</em> the row, so a missing row reads as healthy, and the
     * re-arm scan below walks only PENDING and ACTIVE.
     *
     * <p>All three guards are load-bearing — RUNNING-with-no-row is also the
     * momentary state of a healthy fire between its completion handler's stop and
     * re-schedule: no row in {@code alreadyScheduled}, no fire claimed in
     * {@link TaskRunRegistry} (an in-flight fire on this node), and no write since
     * {@code staleBefore}.
     *
     * @param alreadyScheduled {@code task_instance} ids currently holding a
     *                         {@code scheduled_tasks} row
     * @param staleBefore      cutoff; only Tasks untouched since this are
     *                         reconciled. Production passes {@code now -
     *                         STALE_THRESHOLD}; tests pass an explicit instant so
     *                         the assertion doesn't ride on wall-clock drift.
     * @return number of Tasks settled
     */
    public static int reconcileStrandedRunning(Set<String> alreadyScheduled, Instant staleBefore) {
        var endedOneShots = new ArrayList<Task>();
        int reconciled = Tx.run(() -> {
            int count = 0;
            for (var task : Task.findByStatus(Task.Status.RUNNING)) {
                if (alreadyScheduled.contains(task.id.toString())
                        || TaskRunRegistry.isTaskActive(task.id)
                        || task.updatedAt == null
                        || task.updatedAt.isAfter(staleBefore)) {
                    continue;
                }
                long strandedSeconds = Duration.between(task.updatedAt, AppClock.now()).toSeconds();
                // A one-shot task enters RUNNING only when its fire starts, so one stranded here was cut off,
                // and arming it again would run it twice (JF-355). A recurring task re-arms for its next fire.
                if (RestartCutOff.isRecurring(task)) {
                    task.transitionTo(Task.initialStatusFor(task.type));
                    task.save();
                } else if (RestartCutOff.close(task)) {
                    endedOneShots.add(task);
                }
                EventLogger.warn("task",
                        task.agent != null ? task.agent.name : null, null,
                        ("BootConsistencyCheck: Task '%s' (id=%d) stranded in RUNNING with no schedule "
                                + "row for %ds; now %s")
                                .formatted(task.name, task.id, strandedSeconds, task.status));
                count++;
            }
            return count;
        });
        for (var task : endedOneShots) RestartCutOff.announce(task, latestRun(task.id));
        return reconciled;
    }

    private static @Nullable TaskRun latestRun(Long taskId) {
        return Tx.run(() -> (TaskRun) TaskRun.find("task.id = ?1 ORDER BY startedAt DESC", taskId).first());
    }

    /**
     * Re-arm orphaned non-terminal Tasks: register a fresh
     * {@code scheduled_tasks} row for any PENDING or ACTIVE Task that has
     * lost one. Split out of {@link #sweep} (JCLAW-22) so the periodic
     * {@link OrphanReArmJob} can run JUST this step without re-invoking
     * {@link LostTaskDetector#detect()} — {@link LostTaskScanJob} already
     * ticks that every 30 s, so double-running it would be wasted work.
     * Discovers the live schedule via the scheduler's public
     * {@code getScheduledExecutionsForTask} API, not a raw
     * {@code scheduled_tasks} read. Idempotent: a Task already holding a row
     * is skipped, so an extra run is a no-op. Returns the count of Tasks
     * newly registered.
     */
    public static int reArmOrphans(SchedulerClient scheduler) {
        // Build the set of task_instance ids currently scheduled, so we can
        // skip Tasks that already have a row. One query (typically a small
        // set — most Tasks complete and remove their row).
        var alreadyScheduled = new HashSet<String>();
        for (var row : scheduler.getScheduledExecutionsForTask(TaskExecutionHandler.TASK_NAME)) {
            alreadyScheduled.add(row.getTaskInstance().getId());
        }

        // JCLAW-1103: reconcile stranded RUNNING Tasks before the alive scan below,
        // so this same pass re-registers whatever it returns to an alive state.
        reconcileStrandedRunning(alreadyScheduled,
                AppClock.now().minus(LostTaskDetector.STALE_THRESHOLD));

        // Scan both PENDING (one-shot waiting) and ACTIVE (recurring
        // ongoing). Both need their scheduled_tasks rows reconstructed
        // after a clean-shutdown wipe. Run inside Tx because Play's
        // enhancer needs an open EntityManager for the finder.
        var alive = Tx.run(() -> {
            var combined = new ArrayList<Task>();
            combined.addAll(Task.findByStatus(Task.Status.PENDING));
            combined.addAll(Task.findByStatus(Task.Status.ACTIVE));
            return combined;
        });
        int registered = 0;
        int skippedAlreadyScheduled = 0;
        for (var task : alive) {
            if (alreadyScheduled.contains(task.id.toString())) {
                skippedAlreadyScheduled++;
                continue;
            }
            try {
                TaskSchedulingService.register(task);
                registered++;
            } catch (RuntimeException e) {
                EventLogger.warn("task",
                        task.agent != null ? task.agent.name : null, null,
                        "BootConsistencyCheck: register failed for Task '%s' (id=%d): %s"
                                .formatted(task.name, task.id, e.getMessage()));
            }
        }

        if (registered > 0 || skippedAlreadyScheduled > 0) {
            EventLogger.info("task", null, null,
                    "BootConsistencyCheck: %d Task(s) registered, %d already scheduled"
                            .formatted(registered, skippedAlreadyScheduled));
        }
        return registered;
    }

    /**
     * Test hook: re-run the sweep against the live scheduler.
     * Production callers should not use this — it's the same
     * {@code @OnApplicationStart} entry point under a different name so
     * tests can re-trigger without restarting the JVM.
     */
    public static void runForTest() {
        new BootConsistencyCheck().doJob();
    }
}
