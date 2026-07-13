package utils;

import org.jspecify.annotations.NonNull;
import play.Play;

/**
 * Tiny typed accessors over {@code play.Play.configuration} (the static
 * application.conf properties), with a fallback when the key is absent, blank,
 * or unparseable. This is the operator-set, boot-time config source — distinct
 * from {@link services.ConfigService}, which reads the DB-backed runtime
 * {@code Config} model. Factored out of {@code TelegramInboundTextBuffer} and
 * {@code WebhookTelegramController}, which had byte-identical private copies.
 */
public final class PlayConfig {

    private PlayConfig() {}

    /** Parse {@code key} as an int, or {@code fallback} if absent/blank/invalid. */
    public static int intOr(@NonNull String key, int fallback) {
        var raw = Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            return fallback;
        }
    }

    /** Parse {@code key} as a long, or {@code fallback} if absent/blank/invalid. */
    public static long longOr(@NonNull String key, long fallback) {
        var raw = Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException _) {
            return fallback;
        }
    }
}
