package services.imagegen;

import services.ConfigService;
import services.LocalSidecarDaemon;

/**
 * Lifecycle owner for the local Flux 2 Klein Python sidecar (JCLAW-226). One
 * sidecar per JVM, reached over {@code 127.0.0.1:<imagegen.local.port>}. The
 * spawn/drain/health/stop mechanism is shared with the videogen daemon via
 * {@link LocalSidecarDaemon}; this facade adds the imagegen-specific config and
 * the {@code uv} preflight.
 *
 * <p>{@link #ensureRunning()} is the single entry point: it returns fast when the
 * daemon is already healthy, otherwise it preflights {@link FluxSidecarProbe},
 * spawns {@code uv run serve.py}, and blocks until {@code /health} responds. The
 * daemon may self-evict after its idle timeout, so callers must always go through
 * {@code ensureRunning()} rather than caching the running state.
 *
 * <p>Registered for graceful shutdown via {@code jobs.ShutdownJob}.
 */
public final class LocalFluxSidecarManager {

    private static final String DEFAULT_MODEL = "black-forest-labs/FLUX.2-klein-4B";

    private static final LocalSidecarDaemon DAEMON = new LocalSidecarDaemon(new LocalSidecarDaemon.Config(
            "sidecar/flux", "data/flux-models", "imagegen.local", 9527, 180,
            "imagegen", "flux-sidecar", "Flux sidecar",
            "the first launch installs ~GB of Python deps (torch/diffusers); pre-warm it from Settings",
            ImageGenerationException::new));

    private LocalFluxSidecarManager() {}

    static String baseUrl() {
        return DAEMON.baseUrl();
    }

    /**
     * Ensure the sidecar is up and return its base URL. Idempotent and
     * single-flight: concurrent callers serialize on the daemon lock so only one
     * daemon is ever spawned. Throws {@link ImageGenerationException} when uv is
     * absent, the script is missing, or the daemon doesn't become healthy in time.
     */
    public static String ensureRunning() {
        if (DAEMON.isHealthy()) return DAEMON.baseUrl();
        synchronized (DAEMON.lock()) {
            if (DAEMON.isHealthy()) return DAEMON.baseUrl();
            if (!FluxSidecarProbe.isAvailable()) {
                throw new ImageGenerationException(
                        "local image generation requires 'uv' on PATH: "
                                + FluxSidecarProbe.lastResult().reason());
            }
            DAEMON.spawn(ConfigService.get("imagegen.local.model", DEFAULT_MODEL));
            DAEMON.awaitHealthy();
            return DAEMON.baseUrl();
        }
    }

    /**
     * Stop the sidecar if running. Wired into {@code jobs.ShutdownJob} so the
     * daemon (and its GPU memory) is released on JVM shutdown.
     */
    public static void stop() {
        DAEMON.stop();
    }
}
