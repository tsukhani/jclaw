package jobs;

import models.EventLog;
import play.Play;
import play.jobs.Every;
import play.jobs.Job;
import services.EventLogger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Every("24h")
public class EventLogCleanupJob extends Job<Void> {

    @Override
    public void doJob() {
        var retentionDays = Integer.parseInt(
                Play.configuration.getProperty("jclaw.logs.retention.days", "30"));
        var cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        var deleted = EventLog.deleteOlderThan(cutoff);
        if (deleted > 0) {
            EventLogger.info("system", "Cleaned up %s event log entries older than %s days"
                    .formatted(deleted, retentionDays));
        }
    }
}
