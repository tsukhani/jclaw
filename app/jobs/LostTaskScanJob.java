package jobs;

import play.Play;
import play.jobs.Every;
import services.EventLogger;
import services.LostTaskDetector;

/**
 * JCLAW-258: periodic sweep that reconciles RUNNING Tasks whose
 * db-scheduler heartbeat has gone stale to {@link models.Task.Status#LOST}.
 *
 * <p>Runs every 30 s — one full {@code heartbeatInterval} cycle —
 * so a transition from "live" to "stale" is observed within roughly
 * {@code heartbeatInterval + scanInterval = 60 s} after the JVM
 * stops heartbeating. That keeps the operator-visible LOST flip
 * consistently ahead of db-scheduler's own dead-execution detection
 * (which fires at {@code heartbeatInterval × missedHeartbeatsLimit
 * = 120 s}).
 *
 * <p>{@link BootConsistencyCheck#sweep} also calls
 * {@link LostTaskDetector#detect()} once at startup, so a
 * crash-then-restart surfaces LOST tasks within the first sweep
 * rather than waiting up to 30 s for this job's first tick.
 */
@Every("30s")
public class LostTaskScanJob extends PeriodicJob {

    @Override
    public void doJob() {
        // Test mode has no db-scheduler — DbSchedulerBootstrapJob skips starting it — so
        // nothing heartbeats and every RUNNING Task reads as stale. Left ungated, this sweep
        // flips whichever test's seeded rows it finds to LOST, roughly fourteen times over a
        // suite run. LostTaskDetector stays reachable for the tests that drive it directly.
        if (Play.runningInTestMode()) return;
        int lost = LostTaskDetector.detect();
        if (lost > 0) {
            EventLogger.info("task", null, null,
                    "LostTaskScanJob: %d Task(s) reconciled to LOST"
                            .formatted(lost));
        }
    }
}
