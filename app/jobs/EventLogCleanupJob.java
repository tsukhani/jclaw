package jobs;

import models.EventLog;
import play.Play;
import play.jobs.Every;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import utils.AppClock;

import java.time.temporal.ChronoUnit;

/**
 * JCLAW-1067: {@code @Every} first fires one whole interval after boot, so a 24h
 * period needs 24h of unbroken uptime before this runs even once. On an instance
 * restarted more often than that it never ran, and the retention window went
 * unenforced indefinitely — 178,740 rows past a 30-day window when measured.
 * The startup trigger removes the uptime dependency; {@code async} keeps a bulk
 * delete off the boot path, where a failure would otherwise abort startup.
 */
@OnApplicationStart(async = true)
@Every("24h")
public class EventLogCleanupJob extends Job<Void> {

    private static final String CONFIG_KEY = "jclaw.logs.retention.days";
    private static final int DEFAULT_RETENTION_DAYS = 30;

    @Override
    public void doJob() {
        var retentionDays = resolveRetentionDays(Play.configuration.getProperty(CONFIG_KEY));
        var cutoff = AppClock.now().minus(retentionDays, ChronoUnit.DAYS);
        var deleted = EventLog.deleteOlderThan(cutoff);
        if (deleted > 0) {
            EventLogger.info("system", "Cleaned up %s event log entries older than %s days"
                    .formatted(deleted, retentionDays));
        }
    }

    /**
     * Retention window for {@code jclaw.logs.retention.days}: absent, blank or
     * non-numeric falls back to {@link #DEFAULT_RETENTION_DAYS} with a warn, so
     * an operator typo cannot throw out of every 24h run and stop retention.
     * Takes the raw value rather than reading config so the fallback is
     * testable without mutating the process-global {@link Play#configuration}.
     */
    public static int resolveRetentionDays(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_RETENTION_DAYS;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            EventLogger.warn("system", "%s is not numeric ('%s'); using default %d"
                    .formatted(CONFIG_KEY, raw, DEFAULT_RETENTION_DAYS));
            return DEFAULT_RETENTION_DAYS;
        }
    }
}
