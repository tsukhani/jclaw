package utils;

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
     * Return {@code s} unchanged if it fits in {@code maxLen} characters, or a
     * truncated version suffixed with {@code "..."} otherwise. Null-safe
     * (returns null for null input). Does not validate {@code maxLen} — a
     * non-positive value produces {@code "..."} or similar; callers that need
     * stricter bounds should guard upstream.
     *
     * <p>Use cases: log-preview clipping for long user messages, error-body
     * excerpts in HTTP failure paths, any "show a snippet, full data lives
     * elsewhere" rendering.
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
