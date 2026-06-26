package jobs;

import play.Play;
import play.jobs.Every;
import play.jobs.Job;
import services.LatencyMetricRecorder;

/**
 * Periodically flush the {@link LatencyMetricRecorder} queue to the DB (JCLAW-515).
 * Mirrors {@code EventLogFlushJob}: the recorder only persists when its batch
 * threshold trips, so on a quiet instance samples would sit in memory — invisible
 * to the Chat Performance dashboard — until the next batch. This bounds that
 * staleness to ~30s.
 *
 * <p>Skipped in test mode: the TestEngine runs unit and functional tests
 * concurrently, so a background drain here would race a test populating the same
 * process-global queue. Tests call {@code LatencyMetricRecorder.flush()} explicitly.
 */
@Every("30s")
public class LatencyMetricFlushJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) return;
        LatencyMetricRecorder.flush();
    }
}
