package jobs;

import models.EventLog;
import org.jspecify.annotations.Nullable;
import play.jobs.Every;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.ConfigService;
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

    /** Settings &gt; Logging. */
    public static final String CONFIG_KEY = "logs.retentionDays";

    /** Sentinel value (0) meaning "retention disabled, never auto-delete". */
    public static final int RETENTION_DISABLED = 0;

    private static final int DEFAULT_RETENTION_DAYS = 30;

    @Override
    public void doJob() {
        var retentionDays = resolveRetentionDays(ConfigService.get(CONFIG_KEY));
        if (retentionDays == RETENTION_DISABLED) return;

        var cutoff = AppClock.now().minus(retentionDays, ChronoUnit.DAYS);
        var deleted = EventLog.deleteOlderThan(cutoff);
        if (deleted > 0) {
            EventLogger.info("system", "Cleaned up %s event log entries older than %s days"
                    .formatted(deleted, retentionDays));
        }
    }

    /**
     * Retention window for {@code logs.retentionDays}: absent, blank or
     * non-numeric falls back to {@link #DEFAULT_RETENTION_DAYS} with a warn, and
     * zero or less reads as {@link #RETENTION_DISABLED}, so a value written around
     * the API's validation can neither throw out of every 24h run and stop
     * retention nor empty the log. Takes the raw value rather than reading config
     * so the fallback is testable without writing the shared config table.
     */
    public static int resolveRetentionDays(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_RETENTION_DAYS;
        try {
            var parsed = Integer.parseInt(raw.trim());
            // Any cutoff at or after now takes the whole table; 0 means "disabled" in both siblings.
            if (parsed <= 0) return RETENTION_DISABLED;
            return parsed;
        } catch (NumberFormatException _) {
            EventLogger.warn("system", "%s is not numeric ('%s'); using default %d"
                    .formatted(CONFIG_KEY, raw, DEFAULT_RETENTION_DAYS));
            return DEFAULT_RETENTION_DAYS;
        }
    }
}
