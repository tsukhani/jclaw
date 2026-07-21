package services.tts;

import services.LocalSidecarDaemon;
import services.UvProbe;

/**
 * Lifecycle owner for the TTS Python sidecar (JCLAW-789): Qwen3-TTS / Kokoro via
 * mlx-audio on Apple silicon (the NVIDIA/vLLM leg is deferred to the JCLAW-788
 * RTX 4090 validation). One sidecar per JVM, reached over
 * {@code 127.0.0.1:<tts.local.port>}. The spawn/drain/health/stop mechanism is
 * shared with the ASR/imagegen/videogen daemons via {@link LocalSidecarDaemon}.
 *
 * <p>Registered for graceful shutdown via {@code jobs.ShutdownJob}. The daemon
 * self-evicts after its idle timeout, so callers always go through
 * {@link #ensureRunning()} rather than caching the running state. Mirrors
 * {@link services.transcription.AsrSidecarManager}.
 */
public final class TtsSidecarManager {

    /** Identity string the daemon reports on /health — a stable fingerprint,
     *  not a model id (the TTS model is chosen per request). */
    public static final String IDENTITY = "tts";
    static final String CONFIG_PREFIX = "tts.local";

    private static final LocalSidecarDaemon DAEMON = new LocalSidecarDaemon(new LocalSidecarDaemon.Config(
            "sidecar/tts", "data/tts-models", CONFIG_PREFIX, 9531, 300,
            "tts", "tts-sidecar", "TTS sidecar",
            "the first launch installs the Python TTS deps (mlx-audio) via uv",
            TtsException::new));

    private TtsSidecarManager() {}

    /**
     * Ensure the sidecar is up and return its base URL. Idempotent and
     * single-flight (JCLAW-830): the spawn + health-await run under the daemon's
     * {@code startLock} — a separate lock from the one {@code stop()} uses — so a
     * concurrent starter waits for the in-flight spawn and then no-ops on the
     * re-check, while {@code stop()}/idle-respawn never stall behind the startup
     * poll. Throws {@link TtsException} when uv is absent, the script is missing,
     * or the daemon doesn't become healthy in time.
     */
    public static String ensureRunning() {
        if (DAEMON.isHealthy(IDENTITY)) return DAEMON.baseUrl();
        return DAEMON.singleFlight(() -> {
            if (DAEMON.isHealthy(IDENTITY)) return DAEMON.baseUrl();
            if (!UvProbe.isAvailable()) {
                throw new TtsException(
                        "the TTS sidecar requires 'uv' on PATH: " + UvProbe.lastResult().reason());
            }
            DAEMON.spawn(IDENTITY, null);
            DAEMON.awaitHealthy();
            return DAEMON.baseUrl();
        });
    }

    /** Cheap liveness probe for {@code /api/tts/state} — reports whether the
     *  sidecar is already up without spawning it. */
    public static boolean isRunning() {
        return DAEMON.isHealthy(IDENTITY);
    }

    /** Stop the sidecar if running. Wired into {@code jobs.ShutdownJob}. */
    public static void stop() {
        DAEMON.stop();
    }
}
