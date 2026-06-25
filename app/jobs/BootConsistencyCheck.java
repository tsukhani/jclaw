package jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import models.Task;
import models.TaskRun;
import play.jobs.Job;
import services.EventLogger;
import services.LostTaskDetector;
import services.TaskExecutionHandler;
import services.TaskSchedulingService;
import services.Tx;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;

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
 *   this sweep. (TaskPollerJob's deletion in this same commit
 *   means no other code path picks them up.)</li>
 *
 *   <li><b>Crash recovery:</b> a JVM crash between an IMMEDIATE
 *   Task being persisted and {@link TaskSchedulingService#register}
 *   being called leaves the Task PENDING but unscheduled. This
 *   sweep heals that on the next start.</li>
 * </ol>
 *
 * <p><b>What this does NOT cover:</b>
 * <ul>
 *   <li>Tasks in {@link Task.Status#RUNNING} — db-scheduler's
 *   built-in dead-execution detection still handles the actual
 *   re-fire (30 s heartbeat × 4 misses = 120 s before declared
 *   dead, per {@link DbSchedulerBootstrapJob}). JCLAW-258 layered
 *   a visibility step on top: the boot sweep also runs
 *   {@link LostTaskDetector#detect()} so any RUNNING Task whose
 *   {@code scheduled_tasks.last_heartbeat} is older than 60 s is
 *   reconciled to {@link Task.Status#LOST} immediately on restart,
 *   rather than the operator seeing a stale RUNNING pill for up
 *   to the next periodic detector tick.</li>
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
 * Called inline from {@link DbSchedulerBootstrapJob#doJob}
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
     *   <li>Walk all PENDING Tasks. Skip any whose id is in the
     *       set (already scheduled — no action needed).</li>
     *   <li>For each orphan, call
     *       {@link TaskSchedulingService#register} which translates
     *       the Task's type into a fresh scheduled_tasks row.</li>
     * </ol>
     */
    public static int sweep(SchedulerClient scheduler) {
        return sweep(scheduler, Instant.now());
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
     * @param bootCutoff cutoff instant; runs started before it are considered orphaned
     * @return number of run rows flipped to FAILED
     */
    public static int reconcileOrphanedRuns(Instant bootCutoff) {
        return Tx.run(() -> {
            var now = Instant.now();
            int count = 0;
            for (Object o : TaskRun.find("status = ?1 and startedAt < ?2",
                    TaskRun.Status.RUNNING, bootCutoff).fetch()) {
                var run = (TaskRun) o;
                run.status = TaskRun.Status.FAILED;
                run.completedAt = now;
                run.durationMs = Duration.between(run.startedAt, now).toMillis();
                if (run.error == null || run.error.isBlank()) {
                    run.error = "Run orphaned by a JVM restart/crash before completion; "
                            + "reconciled to FAILED at startup.";
                }
                run.save();
                count++;
            }
            if (count > 0) {
                EventLogger.info("task", null, null,
                        "BootConsistencyCheck: reconciled %d orphaned RUNNING task_run row(s) to FAILED"
                                .formatted(count));
            }
            return count;
        });
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

    /**
     * Single-call existence check for a specific Task. Exposed because
     * the same lookup is useful from operator tooling — "is this Task
     * actually scheduled?" — without exposing the SchedulerClient
     * directly. Returns false if the scheduler hasn't bootstrapped.
     */
    public static boolean isScheduled(Long taskId) {
        var scheduler = DbSchedulerBootstrapJob.scheduler();
        if (scheduler == null || taskId == null) return false;
        return scheduler.getScheduledExecution(
                TaskInstanceId.of(TaskExecutionHandler.TASK_NAME, taskId.toString())
        ).isPresent();
    }
}
