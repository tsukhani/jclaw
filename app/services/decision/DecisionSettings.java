package services.decision;

import org.jspecify.annotations.Nullable;
import services.ConfigService;

/**
 * The {@code decision.*} keys, edited in Settings &gt; Decision Providers, and the rules
 * {@code ConfigService.setWithSideEffects} applies when one is written. Nothing is seeded.
 * Settings that belong to one consumer stay with it: the browser engine in {@code browser.*},
 * the classifier's timeout and minimum confidence in {@code router.*}.
 */
public final class DecisionSettings {

    public static final String KEY_PREFIX = "decision.";
    public static final String API_KEY = "decision.jev.apiKey";
    /** Where the TypeSafe key lived before JCLAW-1302; {@code DefaultConfigJob} moves it to {@link #API_KEY} on boot. */
    public static final String LEGACY_API_KEY = "browser.jev.apiKey";

    private DecisionSettings() {}

    /** The TypeSafe key both the Jev browser engine and the router's JEV classifier send; null when unset. */
    public static @Nullable String apiKey() {
        var key = ConfigService.get(API_KEY);
        return key == null || key.isBlank() ? null : key.trim();
    }

    /** A message naming what {@code value} must be, or null when {@code key} accepts it. */
    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        if (!key.equals(API_KEY)) {
            return "%s is not a decision-provider setting; the only one is %s.".formatted(key, API_KEY);
        }
        // The key rides an Authorization header, where OkHttp throws on anything but printable ASCII.
        return value == null || value.isBlank() || value.chars().allMatch(c -> c > ' ' && c < 0x7f) ? null
                : "%s must be printable ASCII with no spaces.".formatted(API_KEY);
    }
}
