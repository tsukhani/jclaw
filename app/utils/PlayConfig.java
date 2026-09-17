package utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import play.Play;
import services.EventLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tiny typed accessors over {@code play.Play.configuration} (the static
 * application.conf properties), with a fallback when the key is absent, blank,
 * or unparseable. This is the operator-set, boot-time config source — distinct
 * from {@link services.ConfigService}, which reads the DB-backed runtime
 * {@code Config} model.
 */
public final class PlayConfig {

    private PlayConfig() {}

    /** Parse {@code key} as an int, or {@code fallback} if absent/blank/invalid; an invalid value is reported. */
    public static int intOr(@NonNull String key, int fallback) {
        var raw = Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            report(key, raw, "a whole number", String.valueOf(fallback));
            return fallback;
        }
    }

    /** {@link #intOr}, also rejecting — and reporting — a value below {@code min}. */
    public static int intAtLeast(@NonNull String key, int min, int fallback) {
        var raw = Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= min) return value;
        } catch (NumberFormatException _) {
            // Reported below, with the out-of-range case.
        }
        report(key, raw, "a whole number of at least " + min, String.valueOf(fallback));
        return fallback;
    }

    /** Parse {@code key} as a long, or {@code fallback} if absent/blank/invalid; an invalid value is reported. */
    public static long longOr(@NonNull String key, long fallback) {
        var raw = Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException _) {
            report(key, raw, "a whole number", String.valueOf(fallback));
            return fallback;
        }
    }

    // One entry per key holding the value last reported: these are read on request paths, so only
    // the first read of a bad value is logged (the same gate ConfigService keeps for the Config DB).
    private static final ConcurrentMap<String, String> reportedParseFailures = new ConcurrentHashMap<>();

    private static void report(String key, String raw, String expected, String fallback) {
        var report = parseFailureReport(key, raw, expected, fallback);
        if (report != null) EventLogger.error("config", report);
    }

    /**
     * The three-part message for a rejected {@code application.conf} value, or null when this key
     * has already reported this same value. Public because test sources are the default package;
     * calling it marks the value as reported.
     */
    public static @Nullable String parseFailureReport(String key, String raw, String expected,
                                                      String fallback) {
        if (raw.equals(reportedParseFailures.put(key, raw))) return null;
        return ErrorRendering.PLAIN.render(
                StartupErrorTemplates.appConfigParseFailure(key, raw.trim(), expected, fallback));
    }
}
