package utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Small string helpers shared across services and channel code.
 *
 * <p>Intentionally narrow: add a method here only when the same pattern
 * appears in 2+ callers. General-purpose string utility classes have a way
 * of accreting every pet helper someone writes — the bar for this class is
 * "I found duplicate inline logic."
 */
public final class Strings {

    private Strings() {}

    /**
     * Standard cap, in characters, for an error- or response-body excerpt kept
     * for a log line or surfaced in a failure message. Every "truncate an
     * upstream error body before we log/return it" site shares this bound so the
     * amount of foreign payload we retain is decided in one place.
     */
    public static final int ERROR_SNIPPET_MAX_CHARS = 500;

    /**
     * Return {@code s} unchanged if it fits in {@code maxLen} characters, or a
     * truncated version suffixed with {@code "…"} otherwise. Returns {@code ""}
     * for null input. Does not validate {@code maxLen} — a non-positive value
     * produces just {@code "…"} or similar; callers that need stricter bounds
     * should guard upstream.
     *
     * <p>Use cases: log-preview clipping for long user messages, error-body
     * excerpts in HTTP failure paths, any "show a snippet, full data lives
     * elsewhere" rendering.
     */
    public static @NonNull String truncate(@Nullable String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    /**
     * Redact {@code secret} from {@code body} (every occurrence becomes
     * {@code "<redacted>"}) and truncate the result to
     * {@link #ERROR_SNIPPET_MAX_CHARS}. Returns {@code ""} for a null/empty body
     * and skips redaction when {@code secret} is null/empty. Shared by the LLM
     * error-body sanitizers (JCLAW-811) so both redact identically before an
     * error snippet reaches event logs or the UI.
     */
    public static @NonNull String redactAndTruncate(@Nullable String body, @Nullable String secret) {
        if (body == null || body.isEmpty()) return "";
        var scrubbed = (secret == null || secret.isEmpty()) ? body : body.replace(secret, "<redacted>");
        return truncate(scrubbed, ERROR_SNIPPET_MAX_CHARS);
    }

    /** First value that is non-null and not blank, or null if none qualify. */
    public static @Nullable String firstNonBlank(@Nullable String... values) {
        for (var v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** Strip a single trailing '/', if present. Assumes non-null input. */
    public static @NonNull String trimTrailingSlash(@NonNull String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
