package jobs;

import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import models.Task;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
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
 *   built-in dead-execution detection handles these via the
 *   {@code last_heartbeat} mechanism (default 5-minute heartbeat,
 *   6 missed heartbeats → declared dead and re-fired). We don't
 *   need to do anything here.</li>
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
 * <p>Runs in a separate {@code @OnApplicationStart} job rather
 * than chained into {@link DbSchedulerBootstrapJob} because Play
 * 1.x doesn't guarantee startup-job ordering, and the consistency
 * sweep depends on the scheduler being live. The job reads
 * {@code DbSchedulerBootstrapJob.scheduler()} at run-time and
 * exits cleanly if the bootstrap hasn't completed yet (rare, but
 * possible under specific Play startup interleaving).
 */
@OnApplicationStart
public class BootConsistencyCheck extends Job<Void> {

    @Override
    public void doJob() {
        var scheduler = DbSchedulerBootstrapJob.scheduler();
        if (scheduler == null) {
            EventLogger.warn("task", null, null,
                    "BootConsistencyCheck: scheduler not bootstrapped; skipping sweep");
            return;
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
    }

    /**
     * Test hook: re-run the sweep. Exposed so the cutover commit's
     * acceptance test can verify behavior without restarting the
     * Play test JVM.
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
