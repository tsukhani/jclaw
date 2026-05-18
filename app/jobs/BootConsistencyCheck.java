package jobs;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import models.Task;
import play.jobs.Job;
import services.EventLogger;
import services.LostTaskDetector;
import services.TaskExecutionHandler;
import services.TaskSchedulingService;
import services.Tx;

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
 * <h3>Invocation</h3>
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

        // Build the set of task_instance ids currently scheduled, so we can
        // skip Tasks that already have a row. One query (typically a small
        // set — most Tasks complete and remove their row).
        var alreadyScheduled = new HashSet<String>();
        for (var row : scheduler.getScheduledExecutionsForTask(TaskExecutionHandler.TASK_NAME)) {
            alreadyScheduled.add(row.getTaskInstance().getId());
        }

        // Scan PENDING Tasks. Run inside Tx because Play's enhancer needs
        // an open EntityManager for the finder.
        var pending = Tx.run(() -> Task.findByStatus(Task.Status.PENDING));
        int registered = 0;
        int skippedAlreadyScheduled = 0;
        for (var task : pending) {
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
     * @OnApplicationStart entry point under a different name so
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
