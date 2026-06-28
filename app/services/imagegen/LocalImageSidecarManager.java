package services.imagegen;

import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import services.ConfigService;
import services.LocalSidecarDaemon;
import services.UvProbe;
import utils.HttpFactories;

import java.time.Duration;

/**
 * Lifecycle owner for the local image-generation Python sidecar (JCLAW-226).
 * Implementation-agnostic (the default engine is Flux 2 Klein, configured via
 * {@code imagegen.local.model}). One sidecar per JVM, reached over
 * {@code 127.0.0.1:<imagegen.local.port>}. The
 * spawn/drain/health/stop mechanism is shared with the videogen daemon via
 * {@link LocalSidecarDaemon}; this facade adds the imagegen-specific config and
 * the {@code uv} preflight.
 *
 * <p>{@link #ensureRunning()} is the single entry point: it returns fast when the
 * daemon is already healthy, otherwise it preflights {@link UvProbe},
 * spawns {@code uv run serve.py}, and blocks until {@code /health} responds. The
 * daemon may self-evict after its idle timeout, so callers must always go through
 * {@code ensureRunning()} rather than caching the running state.
 *
 * <p>Registered for graceful shutdown via {@code jobs.ShutdownJob}.
 */
public final class LocalImageSidecarManager {

    private static final String DEFAULT_MODEL = "black-forest-labs/FLUX.2-klein-4B";

    private static final LocalSidecarDaemon DAEMON = new LocalSidecarDaemon(new LocalSidecarDaemon.Config(
            "sidecar/image", "data/image-models", "imagegen.local", 9527, 180,
            "imagegen", "image-sidecar", "image sidecar",
            "the first launch installs ~GB of Python deps (torch/diffusers); pre-warm it from Settings",
            ImageGenerationException::new));

    // Short-timeout client for the progress poll — must never block the chat on a wedged sidecar.
    // Derived from the shared general client (reuses its pool/dispatcher) with a tight 2s call timeout.
    private static final OkHttpClient PROGRESS_CLIENT = HttpFactories.general().newBuilder()
            .callTimeout(Duration.ofSeconds(2))
            .build();

    private LocalImageSidecarManager() {}

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
            if (!UvProbe.isAvailable()) {
                throw new ImageGenerationException(
                        "local image generation requires 'uv' on PATH: "
                                + UvProbe.lastResult().reason());
            }
            DAEMON.spawn(ConfigService.get("imagegen.local.model", DEFAULT_MODEL));
            DAEMON.awaitHealthy();
            return DAEMON.baseUrl();
        }
    }

    /**
     * Live step-progress (0..100) of an in-flight local generation, or {@code null} when the sidecar is
     * down or idle. Never spawns the daemon — a progress poll must not launch a sidecar — and never
     * throws: any failure (sidecar down, unreachable, malformed body) reads as "no progress" so the chat
     * bar simply doesn't show. Polled by the chat via {@code GET /api/imagegen/progress}.
     */
    public static Integer currentProgressPercent() {
        var req = new Request.Builder().url(DAEMON.baseUrl() + "/progress").get().build();
        try (var resp = PROGRESS_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            var pct = JsonParser.parseString(resp.body().string()).getAsJsonObject().get("percent");
            return pct == null || pct.isJsonNull() ? null : pct.getAsInt();
        } catch (Exception _) {
            return null; // sidecar down / idle-evicted / unreachable — no bar
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
