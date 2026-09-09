package services;

import play.Play;
import services.scrape.ScrapeSidecarException;

import java.io.File;

/**
 * Lifecycle for the TLS-impersonating fetch sidecar — escalation rung 2 (JCLAW-1087).
 *
 * <p>Deliberately does <em>not</em> extend {@code SidecarHttpClient}. That base serializes
 * every call through a JVM-wide fair lock because the inference sidecars answer 409 when
 * busy and can only run one job at a time; a fetch is I/O-bound and the scrape frontier
 * runs several at once (JCLAW-1093), so inheriting it would serialize the crawl. It also
 * lifts the read timeout to zero for multi-minute model loads, which is the opposite of
 * what a page fetch wants. {@code LocalImageSidecarManager} and its video counterpart set
 * the same precedent: the daemon discipline without that client base.
 */
public final class FetchSidecarManager {

    /** {@code --model} carries the impersonation profile. That is not a pun on the slot:
     *  {@link LocalSidecarDaemon#isHealthy(String)} compares the running sidecar's
     *  reported model against config, so repinning the profile forces a respawn instead
     *  of silently leaving the old fingerprint in service. */
    private static final String DEFAULT_PROFILE = "chrome";

    /** Public because Play's tests live in the default package and cannot reach a
     *  package-private seam. */
    public static final String CFG_PROFILE = "scrape.impersonate.profile";
    public static final String CFG_ENABLED = "scrape.impersonate.enabled";

    private static final LocalSidecarDaemon DAEMON = new LocalSidecarDaemon(new LocalSidecarDaemon.Config(
            "sidecar/fetch", "data/fetch-sidecar", "scrape.impersonate", 9533, 180,
            "scrape", "fetch-sidecar", "fetch sidecar",
            "the first launch installs curl_cffi (a few MB); it is not a model download",
            ScrapeSidecarException::new));

    private FetchSidecarManager() {}

    /** The profile the operator has pinned, or the rolling {@code chrome} alias. */
    public static String profile() {
        return ConfigService.get(CFG_PROFILE, DEFAULT_PROFILE);
    }

    /**
     * Whether rung 2 can even be attempted, without spawning anything. Feature
     * detection is a precondition of the rung, not an error path: an install with no
     * {@code uv} and no sidecar directory must fall back to rung 1 silently rather
     * than fail a scrape.
     */
    public static boolean available() {
        if (!ConfigService.getBoolean(CFG_ENABLED, true)) return false;
        if (!UvProbe.isAvailable()) return false;
        return new File(new File(Play.applicationPath, "sidecar/fetch"), "serve.py").isFile();
    }

    /** Base URL of a healthy sidecar, spawning it if needed. Single-flight (JCLAW-830). */
    public static String ensureRunning() {
        var profile = profile();
        if (DAEMON.isHealthy(profile)) return DAEMON.baseUrl();
        return DAEMON.singleFlight(() -> {
            if (DAEMON.isHealthy(profile)) return DAEMON.baseUrl();
            if (!UvProbe.isAvailable()) {
                throw new ScrapeSidecarException(
                        "the fetch sidecar requires 'uv' on PATH: " + UvProbe.lastResult().reason(), null);
            }
            DAEMON.spawn(profile);
            DAEMON.awaitHealthy();
            return DAEMON.baseUrl();
        });
    }

    public static String authToken() {
        return DAEMON.authToken();
    }

    /** Stop the sidecar if running. Wired into {@code jobs.ShutdownJob}. */
    public static void stop() {
        DAEMON.stop();
    }
}
