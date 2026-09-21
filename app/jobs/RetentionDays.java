package jobs;

import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.EventLogger;

/**
 * JCLAW-1231: the one retention-window resolver the three cleanup jobs share.
 * They had a copy each and the copies disagreed on what {@code 0} meant, which
 * is how {@code logs.retentionDays=0} came to empty the event log (JCLAW-1269).
 */
public final class RetentionDays {

    /** Sentinel value (0) meaning "retention disabled, never auto-delete". */
    public static final int DISABLED = 0;

    /** No upper bound, for a key whose value was never ceiling-checked. */
    public static final int NO_CEILING = Integer.MAX_VALUE;

    private RetentionDays() {}

    /**
     * Retention window for {@code keyName}, read from {@link ConfigService}.
     *
     * @see #resolve(String, int, int, String, String)
     */
    public static int fromConfig(String keyName, int defaultDays, int maxDays, String eventCategory) {
        return resolve(ConfigService.get(keyName), defaultDays, maxDays, eventCategory, keyName);
    }

    /**
     * Retention window from a raw config value: absent or blank → {@code defaultDays};
     * non-numeric or above {@code maxDays} → {@code defaultDays} plus a warn; zero or
     * less → {@link #DISABLED}, because any cutoff at or after now deletes the whole
     * table and 0 is the operator-facing "off" switch on all three keys.
     *
     * <p>Takes the raw value rather than reading config so the fallbacks are testable
     * without writing the shared config table.
     */
    public static int resolve(@Nullable String raw, int defaultDays, int maxDays,
                              String eventCategory, String keyName) {
        if (raw == null || raw.isBlank()) return defaultDays;
        try {
            var parsed = Integer.parseInt(raw.trim());
            if (parsed < 0) {
                // Silently disabling on a typo is how a table grows forever (JCLAW-1067).
                EventLogger.warn(eventCategory,
                        "%s is negative (%d); treating it as %d (cleanup disabled)"
                                .formatted(keyName, parsed, DISABLED));
                return DISABLED;
            }
            if (parsed == 0) return DISABLED;
            if (parsed > maxDays) {
                EventLogger.warn(eventCategory,
                        ("%s out of range (%d); using default %d. Allowed: 0 (disabled) or 1..%d.")
                                .formatted(keyName, parsed, defaultDays, maxDays));
                return defaultDays;
            }
            return parsed;
        } catch (NumberFormatException _) {
            EventLogger.warn(eventCategory, "%s is not numeric ('%s'); using default %d"
                    .formatted(keyName, raw, defaultDays));
            return defaultDays;
        }
    }
}
