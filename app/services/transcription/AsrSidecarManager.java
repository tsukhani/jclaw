package services.transcription;

import services.LocalSidecarDaemon;
import services.UvProbe;

/**
 * Lifecycle owner for the ASR Python sidecar (JCLAW-565; ASR-only since
 * JCLAW-654 — local diarization was removed and speaker attribution moved
 * to the cloud audio-model tool). One sidecar per JVM, reached over
 * {@code 127.0.0.1:<transcription.asr.local.port>}. The
 * spawn/drain/health/stop mechanism is shared with the imagegen/videogen
 * daemons via {@link LocalSidecarDaemon}.
 *
 * <p>Registered for graceful shutdown via {@code jobs.ShutdownJob}. The daemon
 * self-evicts after its idle timeout, so callers always go through
 * {@link #ensureRunning()} rather than caching the running state.
 */
public final class AsrSidecarManager {

    /** Identity string the daemon reports on /health — a stable fingerprint,
     *  not a model id (the ASR model is chosen per request). */
    public static final String IDENTITY = "asr";
    static final String CONFIG_PREFIX = "transcription.asr.local";

    private static final LocalSidecarDaemon DAEMON = new LocalSidecarDaemon(new LocalSidecarDaemon.Config(
            "sidecar/asr", "data/asr-models", CONFIG_PREFIX, 9529, 300,
            "transcription", "asr-sidecar", "ASR sidecar",
            "the first launch installs the Python ASR deps (mlx-whisper or faster-whisper) via uv",
            TranscriptionException::new));

    private AsrSidecarManager() {}

    /**
     * Ensure the sidecar is up and return its base URL. Idempotent and
     * single-flight (JCLAW-830): the spawn + health-await run under the daemon's
     * {@code startLock} — a separate lock from the one {@code stop()} uses — so a
     * concurrent starter waits for the in-flight spawn and then no-ops on the
     * re-check, while {@code stop()}/idle-respawn never stall behind the startup
     * poll. Throws {@link TranscriptionException} when uv is absent, the script is
     * missing, or the daemon doesn't become healthy in time.
     */
    public static String ensureRunning() {
        if (DAEMON.isHealthy(IDENTITY)) return DAEMON.baseUrl();
        return DAEMON.singleFlight(() -> {
            if (DAEMON.isHealthy(IDENTITY)) return DAEMON.baseUrl();
            if (!UvProbe.isAvailable()) {
                throw new TranscriptionException(
                        "the ASR sidecar requires 'uv' on PATH: "
                                + UvProbe.lastResult().reason());
            }
            DAEMON.spawn(IDENTITY, null);
            DAEMON.awaitHealthy();
            return DAEMON.baseUrl();
        });
    }

    /** Stop the sidecar if running. Wired into {@code jobs.ShutdownJob}. */
    public static void stop() {
        DAEMON.stop();
    }
}
