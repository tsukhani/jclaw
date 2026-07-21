package services.videogen;

import services.LocalSidecarDaemon;
import services.UvProbe;

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

    // The engine the live sidecar is serving. A best-effort hint that lets the
    // fast path detect an engine-switch without a health round-trip; the true
    // source of truth is DAEMON.isHealthy(), which reconciles a stale value (see
    // ensureRunning). Volatile for cross-thread visibility (JCLAW-830).
    private static volatile String runningModel;

    private LocalVideoSidecarManager() {}

    static String baseUrl() {
        return DAEMON.baseUrl();
    }

    /** Ensure the sidecar is up serving {@code model} and return its base URL; restarts if it's serving
     *  a different engine. Idempotent + single-flight (JCLAW-830): the stop/spawn/health-await runs under
     *  the daemon's {@code startLock} — separate from the lock {@code stop()} uses — so a shutdown stop
     *  never stalls behind an engine-switch startup poll. */
    public static String ensureRunning(String model) {
        if (model.equals(runningModel) && DAEMON.isHealthy()) return DAEMON.baseUrl();
        return DAEMON.singleFlight(() -> {
            if (model.equals(runningModel) && DAEMON.isHealthy()) return DAEMON.baseUrl();
            if (!UvProbe.isAvailable()) { // shared uv-on-PATH probe
                throw new VideoGenerationException(
                        "local video generation requires 'uv' on PATH: " + UvProbe.lastResult().reason());
            }
            if (DAEMON.hasProcess()) { // switching engine, or stale -> restart
                DAEMON.stop();
                runningModel = null;
            }
            DAEMON.spawn(model);
            DAEMON.awaitHealthy();
            runningModel = model;
            return DAEMON.baseUrl();
        });
    }

    /** Stop the sidecar if running (releases its GPU memory). Wired into {@code jobs.ShutdownJob}.
     *  Clears the engine hint; if this races an in-flight engine-switch the daemon's stop-generation
     *  hand-off keeps it orphan-free and the next {@code ensureRunning} reconciles the hint via the
     *  health check (JCLAW-830). */
    public static void stop() {
        DAEMON.stop();
        runningModel = null;
    }
}
