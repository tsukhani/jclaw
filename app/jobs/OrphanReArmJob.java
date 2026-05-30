package jobs;

import play.jobs.Every;
import play.jobs.Job;
import services.EventLogger;

/**
 * JCLAW-22: periodic self-healing sweep. Re-arms any PENDING or ACTIVE
 * {@link models.Task} that has lost its db-scheduler {@code scheduled_tasks}
 * row (orphan recovery) so it heals without waiting for a JVM restart —
 * {@link BootConsistencyCheck} runs the same re-arm only once, at startup.
 *
 * <p>Runs ONLY the re-arm half ({@link BootConsistencyCheck#reArmOrphans});
 * LOST detection stays with {@link LostTaskScanJob} (every 30 s), so this
 * job never double-runs {@link services.LostTaskDetector#detect()}.
 *
 * <p>Hourly cadence: orphan recovery is far less time-sensitive than LOST
 * detection, the boot sweep already covers the restart case, and an
 * orphaned recurring Task is rare now that the JCLAW-294 race
 * (commit {@code 9d9d6bf}) is fixed. The literal {@code @Every} matches the
 * cadence convention of the other cleanup jobs
 * ({@code EventLogCleanupJob}, {@code ConversationQueueEvictionJob}).
 * Idempotent — a Task already holding a row is skipped, so a tick that
 * overlaps the boot sweep is a harmless no-op.
 */
@Every("1h")
public class OrphanReArmJob extends Job<Void> {

    @Override
    public void doJob() {
        var scheduler = DbSchedulerBootstrapJob.scheduler();
        if (scheduler == null) {
            // Scheduler not bootstrapped yet (early startup / test mode) —
            // the boot sweep will cover the first reconciliation.
            return;
        }
        int registered = BootConsistencyCheck.reArmOrphans(scheduler);
        if (registered > 0) {
            EventLogger.info("task", null, null,
                    "OrphanReArmJob: re-armed %d orphaned Task(s)".formatted(registered));
        }
    }
}
