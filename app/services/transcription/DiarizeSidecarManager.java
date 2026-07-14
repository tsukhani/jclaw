package services.transcription;

import services.ConfigService;
import services.LocalSidecarDaemon;
import services.UvProbe;

/**
 * Lifecycle owner for the local diarization Python sidecar (JCLAW-565 lineage;
 * revived for the local privacy path — pyannote-only, after the DiaRemot
 * classical pipeline collapsed on 8kHz telephony in the bake-off). One sidecar
 * per JVM, reached over {@code 127.0.0.1:<transcription.diarization.local.port>}.
 * The spawn/drain/health/stop mechanism is shared with the asr/imagegen/videogen
 * daemons via {@link LocalSidecarDaemon}.
 *
 * <p>Registered for graceful shutdown via {@code jobs.ShutdownJob}. The daemon
 * self-evicts after its idle timeout, so callers always go through
 * {@link #ensureRunning()} rather than caching the running state.
 */
public final class DiarizeSidecarManager {

    /** Identity string the daemon reports on /health — a stable fingerprint. */
    public static final String IDENTITY = "diarize";
    static final String CONFIG_PREFIX = "transcription.diarization.local";

    private static final LocalSidecarDaemon DAEMON = new LocalSidecarDaemon(new LocalSidecarDaemon.Config(
            "sidecar/diarize", "data/diarize-models", CONFIG_PREFIX, 9530, 300,
            "transcription", "diarize-sidecar", "Diarization sidecar",
            "the first launch installs the Python diarization deps (pyannote.audio + torch) via uv "
                    + "and downloads the gated community-1 weights (needs a Hugging Face token)",
            TranscriptionException::new));

    private DiarizeSidecarManager() {}

    /**
     * Ensure the sidecar is up and return its base URL. Idempotent and
     * single-flight: concurrent callers serialize on the daemon lock so only
     * one daemon is ever spawned. Throws {@link TranscriptionException} when
     * uv is absent, the script is missing, or the daemon doesn't become
     * healthy in time (the gated pyannote weights need a Hugging Face token).
     */
    public static String ensureRunning() {
        if (DAEMON.isHealthy(IDENTITY)) return DAEMON.baseUrl();
        synchronized (DAEMON.lock()) {
            if (DAEMON.isHealthy(IDENTITY)) return DAEMON.baseUrl();
            if (!UvProbe.isAvailable()) {
                throw new TranscriptionException(
                        "the diarization sidecar requires 'uv' on PATH: "
                                + UvProbe.lastResult().reason());
            }
            DAEMON.spawn(IDENTITY, resolveHfToken());
            DAEMON.awaitHealthy();
            return DAEMON.baseUrl();
        }
    }

    /** JCLAW-565/614: the diarization-specific token wins; blank falls back to
     *  the shared {@code imagegen.local.hfToken} (both gate Hugging Face
     *  downloads). Blank both → no token; the sidecar fails fast on the gated
     *  community-1 download with the startup hint. */
    private static String resolveHfToken() {
        var token = ConfigService.get(CONFIG_PREFIX + ".hfToken", "");
        return token.isBlank() ? ConfigService.get("imagegen.local.hfToken", "") : token;
    }

    /** Stop the sidecar if running. Wired into {@code jobs.ShutdownJob}. */
    public static void stop() {
        DAEMON.stop();
    }
}
