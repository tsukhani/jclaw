package tools.jev;

import org.jspecify.annotations.Nullable;
import services.ConfigService;

/**
 * The {@code browser.*} keys, both edited in Settings &gt; Browser, and the rules
 * {@code ConfigService.setWithSideEffects} applies when one is written. Neither is seeded:
 * an absent engine is Playwright.
 */
public final class JevSettings {

    public static final String KEY_PREFIX = "browser.";
    public static final String ENGINE = "browser.engine";
    public static final String API_KEY = "browser.jev.apiKey";

    public static final String PLAYWRIGHT = "playwright";
    public static final String JEV = "jev";

    private JevSettings() {}

    /** Jev drives the browser tool only when it is selected and has a key; otherwise the tool is unchanged. */
    public static boolean active() {
        return activeKey() != null;
    }

    /** The TypeSafe key while Jev is the engine, or null when it is not selected or has no key. */
    public static @Nullable String activeKey() {
        return JEV.equals(ConfigService.get(ENGINE)) ? apiKey() : null;
    }

    /** The TypeSafe key whatever the engine, since the router's JEV classifier uses it too; null when unset. */
    public static @Nullable String apiKey() {
        var key = ConfigService.get(API_KEY);
        return key == null || key.isBlank() ? null : key.trim();
    }

    /** A message naming what {@code value} must be, or null when {@code key} accepts it. */
    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        return switch (key) {
            // Exact: the panel's radios compare the stored value as written, so a padded one would select neither.
            case ENGINE -> PLAYWRIGHT.equals(value) || JEV.equals(value) ? null
                    : "%s must be '%s' or '%s'.".formatted(ENGINE, PLAYWRIGHT, JEV);
            // The key rides an Authorization header, where OkHttp throws on anything but printable ASCII.
            case API_KEY -> value == null || value.isBlank() || value.chars().allMatch(c -> c > ' ' && c < 0x7f)
                    ? null
                    : "%s must be printable ASCII with no spaces.".formatted(API_KEY);
            default -> "%s is not a browser setting; the browser keys are %s and %s."
                    .formatted(key, ENGINE, API_KEY);
        };
    }
}
