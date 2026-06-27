package services.videogen;

import services.LocalSidecarDaemon;
import services.imagegen.FluxSidecarProbe;

/**
 * Lifecycle owner for the local video-generation Python sidecar (JCLAW-232/233).
 * DEDICATED (not the Flux process): a video model can't share VRAM with Flux and
 * the Mac path differs (SV-3). One sidecar per JVM serving one engine
 * ({@code ltx} / {@code wan-5b} / {@code wan-14b}) over
 * {@code 127.0.0.1:<videogen.local.port>}; switching the active engine restarts it.
 * The spawn/drain/health/stop mechanism is shared with the imagegen daemon via
 * {@link LocalSidecarDaemon}.
 *
 * <p>{@link #ensureRunning(String)} is the single entry point: it returns fast
 * when the daemon is already healthy on the requested engine, otherwise it
 * preflights {@code uv}, spawns {@code uv run serve.py}, and blocks until
 * {@code /health} responds. The daemon self-evicts after its idle timeout, so
 * callers must always go through {@code ensureRunning}. Registered for graceful
 * shutdown via {@code jobs.ShutdownJob}.
 */
public final class LocalVideoSidecarManager {

    private static final LocalSidecarDaemon DAEMON = new LocalSidecarDaemon(new LocalSidecarDaemon.Config(
            "sidecar/video", "data/video-models", "videogen.local", 9528, 300,
            "videogen", "video-sidecar", "video sidecar",
            "the first launch installs ~GB of Python deps (torch/diffusers) and may pull a multi-GB model; "
                    + "pre-warm it from Settings",
            VideoGenerationException::new));

    private static String runningModel; // the engine the live sidecar is serving

    private LocalVideoSidecarManager() {}

    static String baseUrl() {
        return DAEMON.baseUrl();
    }

    /** Ensure the sidecar is up serving {@code model} and return its base URL; restarts if it's serving
     *  a different engine. Idempotent + single-flight on the daemon lock. */
    public static String ensureRunning(String model) {
        if (model.equals(runningModel) && DAEMON.isHealthy()) return DAEMON.baseUrl();
        synchronized (DAEMON.lock()) {
            if (model.equals(runningModel) && DAEMON.isHealthy()) return DAEMON.baseUrl();
            if (!FluxSidecarProbe.isAvailable()) { // shared uv-on-PATH probe
                throw new VideoGenerationException(
                        "local video generation requires 'uv' on PATH: " + FluxSidecarProbe.lastResult().reason());
            }
            if (DAEMON.hasProcess()) { // switching engine, or stale -> restart
                DAEMON.stop();
                runningModel = null;
            }
            DAEMON.spawn(model);
            DAEMON.awaitHealthy();
            runningModel = model;
            return DAEMON.baseUrl();
        }
    }

    /** Stop the sidecar if running (releases its GPU memory). Wired into {@code jobs.ShutdownJob}. */
    public static void stop() {
        synchronized (DAEMON.lock()) {
            DAEMON.stop();
            runningModel = null;
        }
    }
}
