package services.transcription;

import services.ConfigService;
import services.LocalSidecarDaemon;
import services.UvProbe;

/**
 * Lifecycle owner for the pyannote diarization Python sidecar (JCLAW-565).
 * One sidecar per JVM, reached over {@code 127.0.0.1:<transcription.diarization.local.port>}.
 * The spawn/drain/health/stop mechanism is shared with the imagegen/videogen
 * daemons via {@link LocalSidecarDaemon}; this facade adds the diarization
 * config prefix and the {@code uv} preflight. Backend selection (and the
 * sherpa fallback) lives in {@link DiarizationRouter} — this class only
 * manages the process.
 *
 * <p>Registered for graceful shutdown via {@code jobs.ShutdownJob}. The daemon
 * self-evicts after its idle timeout, so callers always go through
 * {@link #ensureRunning()} rather than caching the running state.
 */
public final class PyannoteSidecarManager {

    static final String DEFAULT_MODEL = "pyannote/speaker-diarization-community-1";
    static final String CONFIG_PREFIX = "transcription.diarization.local";

    private static final LocalSidecarDaemon DAEMON = new LocalSidecarDaemon(new LocalSidecarDaemon.Config(
            "sidecar/diarize", "data/pyannote-models", CONFIG_PREFIX, 9529, 300,
            "transcription", "diarize-sidecar", "diarization sidecar",
            "the first launch installs ~2 GB of Python deps (torch/pyannote) and downloads the "
                    + "gated community-1 weights — check the HF token and the model-page license acceptance",
            TranscriptionException::new));

    private PyannoteSidecarManager() {}

    /**
     * Ensure the sidecar is up and return its base URL. Idempotent and
     * single-flight: concurrent callers serialize on the daemon lock so only
     * one daemon is ever spawned. Throws {@link TranscriptionException} when
     * uv is absent, the script is missing, or the daemon doesn't become
     * healthy in time.
     */
    public static String ensureRunning() {
        if (DAEMON.isHealthy()) return DAEMON.baseUrl();
        synchronized (DAEMON.lock()) {
            if (DAEMON.isHealthy()) return DAEMON.baseUrl();
            if (!UvProbe.isAvailable()) {
                throw new TranscriptionException(
                        "the pyannote diarization sidecar requires 'uv' on PATH: "
                                + UvProbe.lastResult().reason());
            }
            DAEMON.spawn(ConfigService.get(CONFIG_PREFIX + ".model", DEFAULT_MODEL),
                    effectiveHfToken());
            DAEMON.awaitHealthy();
            return DAEMON.baseUrl();
        }
    }

    /**
     * The HF token the sidecar runs with: the diarization key when set,
     * otherwise the image-generation sidecar's token ({@code
     * imagegen.local.hfToken}) — an operator who already pasted a token for
     * gated image models shouldn't have to paste the same token twice
     * (JCLAW-565 follow-up). Blank/null when neither is configured.
     */
    public static String effectiveHfToken() {
        var own = ConfigService.get(CONFIG_PREFIX + ".hfToken");
        if (own != null && !own.isBlank()) return own;
        return ConfigService.get("imagegen.local.hfToken");
    }

    /** Stop the sidecar if running. Wired into {@code jobs.ShutdownJob}. */
    public static void stop() {
        DAEMON.stop();
    }
}
