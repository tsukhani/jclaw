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
    public static final int RETENTION_DISABLED = RetentionDays.DISABLED;

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
     * Retention window for {@code logs.retentionDays}. Takes the raw value rather than
     * reading config so the fallbacks are testable without writing the shared config
     * table; {@link RetentionDays#resolve} is the shared rule.
     */
    public static int resolveRetentionDays(@Nullable String raw) {
        return RetentionDays.resolve(raw, DEFAULT_RETENTION_DAYS, RetentionDays.NO_CEILING,
                "system", CONFIG_KEY);
    }
}
