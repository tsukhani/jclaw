package jobs;

import play.Play;
import play.jobs.Every;
import play.jobs.Job;
import services.EventLogger;

/**
 * Periodically flush the EventLogger queue to the DB (JCLAW-402).
 *
 * <p>EventLogger batches events and only persists when {@code BATCH_SIZE} is
 * reached or a request/agent boundary explicitly calls {@code flush()}. On a
 * scheduled-task-only deployment those boundaries are rare, so events would
 * sit in memory — invisible in the DB — until the batch trips. This @Every
 * job bounds that staleness to ~30s.
 *
 * <p>Skipped in test mode: TestEngine runs unit and functional tests
 * concurrently, so a background drain here would race a functional test
 * mid-populating the same process-global queue (see EventLoggerTest's
 * JCLAW-334 note). Tests call {@code EventLogger.flush()} explicitly anyway.
 */
@Every("30s")
public class EventLogFlushJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) return;
        EventLogger.flush();
    }
}
