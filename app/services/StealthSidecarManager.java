package services;

import play.Play;
import services.scrape.ScrapeSidecarException;

import java.io.File;
import java.util.List;

/**
 * Lifecycle for the stealth rendering sidecar — escalation rung 3 (JCLAW-1088).
 *
 * <p>The browser is launched and driven <em>inside</em> the sidecar rather than attached
 * to from the JVM over CDP. Patchright's anti-detection patches are driver-side — it
 * avoids issuing {@code Runtime.enable} and disables {@code Console.enable} — so a stock
 * Playwright client connecting with {@code connectOverCDP} re-introduces the very leaks
 * the patches remove. Attaching would have yielded a real browser with none of the
 * stealth, which fixes {@code THIN_CONTENT} and does nothing for {@code JS_CHALLENGE}.
 *
 * <p>Consequence, and the reason this is a lifecycle class rather than a client wrapper:
 * because the sidecar owns the launch, it owns {@code --host-resolver-rules}. The JVM
 * supplies the guard-validated pin with each render request; it no longer holds the
 * pinning itself.
 */
public final class StealthSidecarManager {

    private static final String IDENTITY = "patchright-chromium";

    /** Public because Play's tests live in the default package. */
    public static final String CFG_ENABLED = "scrape.stealth.enabled";

    /** Public because Play's tests live in the default package. */
    public static final String CFG_SOLVE_TURNSTILE = "scrape.stealth.solveTurnstile";

    /** Public because Play's tests live in the default package. */
    public static final String CFG_MAX_SESSIONS = "scrape.stealth.maxSessions";

    private static final LocalSidecarDaemon DAEMON = new LocalSidecarDaemon(new LocalSidecarDaemon.Config(
            "sidecar/stealth", "data/stealth-sidecar", "scrape.stealth", 9532, 300,
            "scrape", "stealth-sidecar", "stealth browser sidecar",
            "the first launch installs Patchright and may download a Chromium build",
            ScrapeSidecarException::new, StealthSidecarManager::sessionArgs));

    private StealthSidecarManager() {}

    /**
     * Whether rung 3 can be attempted, without launching anything. Feature detection is
     * a precondition, not an error path: an install without {@code uv} or without the
     * sidecar falls back to the rungs below rather than failing a scrape.
     */
    public static boolean available() {
        if (!ConfigService.getBoolean(CFG_ENABLED, true)) return false;
        if (!UvProbe.isAvailable()) return false;
        return new File(new File(Play.applicationPath, "sidecar/stealth"), "serve.py").isFile();
    }

    /** Whether a render may click the checkbox of a Cloudflare challenge gate (JCLAW-1306). Read per
     *  render; absent means off. */
    public static boolean solveTurnstile() {
        return ConfigService.getBoolean(CFG_SOLVE_TURNSTILE, false);
    }

    /**
     * The sidecar's cap on live crawl sessions (JCLAW-1307) as launch arguments, read when it
     * spawns. Absent keeps the sidecar's own default; 0 refuses every session, so each crawl
     * falls back to a browser per render.
     *
     * <p>Public because Play's tests live in the default package.
     */
    public static List<String> sessionArgs() {
        int cap = ConfigService.getInt(CFG_MAX_SESSIONS, -1);
        return cap < 0 ? List.of() : List.of("--max-sessions", String.valueOf(cap));
    }

    /** Base URL of a healthy sidecar, spawning it if needed. Single-flight (JCLAW-830). */
    public static String ensureRunning() {
        if (DAEMON.isHealthy(IDENTITY)) return DAEMON.baseUrl();
        return DAEMON.singleFlight(() -> {
            if (DAEMON.isHealthy(IDENTITY)) return DAEMON.baseUrl();
            if (!UvProbe.isAvailable()) {
                throw new ScrapeSidecarException(
                        "the stealth sidecar requires 'uv' on PATH: " + UvProbe.lastResult().reason(), null);
            }
            DAEMON.spawn(IDENTITY);
            DAEMON.awaitHealthy();
            return DAEMON.baseUrl();
        });
    }

    /** Where the sidecar listens, whether or not it is running. */
    public static String baseUrl() {
        return DAEMON.baseUrl();
    }

    public static String authToken() {
        return DAEMON.authToken();
    }

    /** Stop the sidecar if running. Wired into {@code jobs.ShutdownJob}. */
    public static void stop() {
        DAEMON.stop();
    }
}
