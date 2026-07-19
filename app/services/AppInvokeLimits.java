package services;

import play.Play;

import java.util.concurrent.ConcurrentHashMap;

/**
 * JCLAW-766 / AD-5: per-app rate limit for {@code POST /api/apps/<slug>/invoke},
 * enforced server-side at the invoke boundary. A fixed window of
 * {@link #windowSeconds()} seconds; each app may make up to its effective limit of
 * invokes per window, after which further calls are rejected (HTTP 429) until the
 * window rolls.
 *
 * <p>The effective limit is {@code min(app.json "limit" override, ceiling)}:
 * {@code application.conf} holds the global default and the <em>authoritative</em>
 * hard ceiling; an {@code app.json} override may only <em>tighten</em> within the
 * ceiling (a value above it is clamped, never honored). Because the app authors
 * {@code app.json} but not {@code application.conf}, an app can never raise its own
 * budget — it can only lower it.
 *
 * <p>State is in-process (single-JVM Personal Edition — see the multi-tenancy design);
 * a restart clears the counters, which is acceptable for a rate limit. The window math
 * takes an explicit {@code nowMillis} so the core is a pure, deterministically testable
 * function with no wall-clock or global-config dependency.
 */
public final class AppInvokeLimits {

    private AppInvokeLimits() {}

    public static final String KEY_DEFAULT = "apps.invoke.limit.default";
    public static final String KEY_CEILING = "apps.invoke.limit.ceiling";
    public static final String KEY_WINDOW_SECONDS = "apps.invoke.limit.windowSeconds";

    public static final int DEFAULT_LIMIT = 30;
    public static final int DEFAULT_CEILING = 120;
    public static final int DEFAULT_WINDOW_SECONDS = 60;

    /** slug -> its current fixed-window bucket. The only mutable state. */
    private static final ConcurrentHashMap<String, Window> WINDOWS = new ConcurrentHashMap<>();

    /** A slug's window bucket: which window it belongs to and how many invokes it has admitted. */
    private static final class Window {
        long id;
        int count;
    }

    /** Global default invokes/window from {@code application.conf} ({@link #DEFAULT_LIMIT} fallback). */
    public static int defaultLimit() {
        return confInt(KEY_DEFAULT, DEFAULT_LIMIT);
    }

    /** The authoritative hard ceiling from {@code application.conf} ({@link #DEFAULT_CEILING} fallback). */
    public static int ceiling() {
        return confInt(KEY_CEILING, DEFAULT_CEILING);
    }

    /** The fixed-window length in seconds ({@link #DEFAULT_WINDOW_SECONDS} fallback; never &lt; 1). */
    public static int windowSeconds() {
        int w = confInt(KEY_WINDOW_SECONDS, DEFAULT_WINDOW_SECONDS);
        return w < 1 ? DEFAULT_WINDOW_SECONDS : w;
    }

    /**
     * The effective per-window invoke cap for an app: the {@code app.json} override
     * tightened into {@code [0, ceiling]}, or the global default when there is no
     * override. A null or negative override means "no override" (use the default); an
     * override above the ceiling is clamped to the ceiling — the app can only tighten,
     * never raise (AD-5). Pure — pass live config via {@link #defaultLimit()} /
     * {@link #ceiling()}.
     */
    public static int effectiveLimit(Integer override, int defaultLimit, int ceiling) {
        int base = (override != null && override >= 0) ? override : defaultLimit;
        return Math.min(base, ceiling);
    }

    /** {@link #effectiveLimit(Integer, int, int)} resolved against live {@code application.conf}. */
    public static int effectiveLimit(Integer override) {
        return effectiveLimit(override, defaultLimit(), ceiling());
    }

    /**
     * Record an invoke attempt for {@code slug} and report whether it is within
     * {@code limit} for the current window. Increments the counter only when admitted,
     * so a rejected call does not consume further budget. Returns {@code false} once the
     * window already holds {@code limit} invokes, or when {@code limit <= 0} (a
     * tightened-to-zero app admits nothing). Pure w.r.t. {@code nowMillis} /
     * {@code windowSeconds}; the per-slug {@code compute} is atomic, so concurrent
     * invokes for one slug can't race the counter.
     */
    public static boolean tryAcquire(String slug, int limit, long nowMillis, int windowSeconds) {
        if (limit <= 0) {
            return false;
        }
        long windowId = nowMillis / (windowSeconds * 1000L);
        boolean[] admitted = {false};
        WINDOWS.compute(slug, (k, w) -> {
            if (w == null || w.id != windowId) {
                w = new Window();
                w.id = windowId;
            }
            if (w.count < limit) {
                w.count++;
                admitted[0] = true;
            }
            return w;
        });
        return admitted[0];
    }

    /** {@link #tryAcquire(String, int, long, int)} against the wall clock + live window config. */
    public static boolean tryAcquire(String slug, int limit) {
        return tryAcquire(slug, limit, System.currentTimeMillis(), windowSeconds());
    }

    private static int confInt(String key, int fallback) {
        var raw = Play.configuration.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            return fallback;
        }
    }
}
