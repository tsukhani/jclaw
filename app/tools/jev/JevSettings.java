package tools.jev;

import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.decision.DecisionSettings;

/**
 * The {@code browser.*} key, the engine edited in Settings &gt; Browser, and the rule
 * {@code ConfigService.setWithSideEffects} applies when it is written. It is not seeded: an
 * absent engine is Playwright. The TypeSafe key belongs to {@link DecisionSettings}, since the
 * router's JEV classifier sends it too.
 */
public final class JevSettings {

    public static final String KEY_PREFIX = "browser.";
    public static final String ENGINE = "browser.engine";

    public static final String PLAYWRIGHT = "playwright";
    public static final String JEV = "jev";

    private JevSettings() {}

    /** Jev drives the browser tool only when it is selected and has a key; otherwise the tool is unchanged. */
    public static boolean active() {
        return activeKey() != null;
    }

    /** The TypeSafe key while Jev is the engine, or null when it is not selected or has no key. */
    public static @Nullable String activeKey() {
        return JEV.equals(ConfigService.get(ENGINE)) ? DecisionSettings.apiKey() : null;
    }

    /** A message naming what {@code value} must be, or null when {@code key} accepts it. */
    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        if (!key.equals(ENGINE)) {
            return "%s is not a browser setting; the only browser key is %s, and the TypeSafe key is %s."
                    .formatted(key, ENGINE, DecisionSettings.API_KEY);
        }
        // Exact: the panel's radios compare the stored value as written, so a padded one would select neither.
        return PLAYWRIGHT.equals(value) || JEV.equals(value) ? null
                : "%s must be '%s' or '%s'.".formatted(ENGINE, PLAYWRIGHT, JEV);
    }
}
