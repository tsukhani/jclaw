package jobs;

import models.LatencyMetric;
import play.jobs.Every;
import play.jobs.Job;
import services.ConfigService;
import services.EventLogger;
import services.Tx;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JCLAW-515: scheduled auto-cleanup for {@link LatencyMetric} rows past their
 * {@code latency.metrics.retentionDays} TTL. Latency emits ~14 rows per turn, so
 * the table grows much faster than the other metric tables — retention is what
 * bounds it. Runs every 24h off the chat hot path; a single bulk JPQL delete
 * removes every expired row regardless of count.
 *
 * <p>Configuration: {@code latency.metrics.retentionDays} (integer; default
 * {@link #DEFAULT_RETENTION_DAYS}). Set to {@link #RETENTION_DISABLED} — or unset
 * to the default — to govern behavior; out-of-range / non-numeric values fall back
 * to the default with a one-shot warn. Mirrors {@code TaskCleanupJob}'s parsing.
 */
@Every("24h")
public class LatencyMetricCleanupJob extends Job<Void> {

    private static final String EVENT_CATEGORY = "LATENCY_CLEANUP";
    private static final String CONFIG_KEY = "latency.metrics.retentionDays";

    /** Default retention window when the config key is absent. Shorter than the
     *  task default because latency rows are an order of magnitude more numerous. */
    public static final int DEFAULT_RETENTION_DAYS = 14;

    /** Sentinel value (0) meaning "retention disabled, never auto-delete". */
    public static final int RETENTION_DISABLED = 0;

    private static final int MAX_RETENTION_DAYS = 3650;

    @Override
    public void doJob() {
        var retentionDays = resolveRetentionDays();
        if (retentionDays == RETENTION_DISABLED) return;

        var cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = Tx.run(() -> LatencyMetric.delete("createdAt < ?1", cutoff));
        if (deleted > 0) {
            EventLogger.info(EVENT_CATEGORY, null, null,
                    "Deleted %d latency metric(s) older than %d day(s) (cutoff=%s)"
                            .formatted(deleted, retentionDays, cutoff));
        }
    }

    /** Read {@code latency.metrics.retentionDays}: missing → default, 0 → disabled,
     *  out-of-range / non-numeric → default plus a warn. Public for direct test access. */
    public static int resolveRetentionDays() {
        var raw = ConfigService.get(CONFIG_KEY);
        if (raw == null || raw.isBlank()) return DEFAULT_RETENTION_DAYS;
        try {
            var parsed = Integer.parseInt(raw.trim());
            if (parsed == 0) return RETENTION_DISABLED;
            if (parsed < 0 || parsed > MAX_RETENTION_DAYS) {
                EventLogger.warn(EVENT_CATEGORY,
                        ("latency.metrics.retentionDays out of range (%d); using default %d. "
                                + "Allowed: 0 (disabled) or 1..%d.")
                                .formatted(parsed, DEFAULT_RETENTION_DAYS, MAX_RETENTION_DAYS));
                return DEFAULT_RETENTION_DAYS;
            }
            return parsed;
        } catch (NumberFormatException _) {
            EventLogger.warn(EVENT_CATEGORY,
                    "latency.metrics.retentionDays is not numeric ('%s'); using default %d"
                            .formatted(raw, DEFAULT_RETENTION_DAYS));
            return DEFAULT_RETENTION_DAYS;
        }
    }
}
