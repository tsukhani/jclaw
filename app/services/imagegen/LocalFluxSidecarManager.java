package services.imagegen;

import okhttp3.Request;
import play.Logger;
import play.Play;
import services.ConfigService;
import services.EventLogger;
import utils.HttpFactories;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle owner for the local Flux 2 Klein Python sidecar (JCLAW-226). jclaw has
 * no other long-running managed child process; the closest precedent is
 * {@link mcp.transport.McpStdioTransport}, whose start/drain/graceful-close
 * discipline this mirrors (drain the child's streams on virtual threads;
 * {@code destroy} then {@code destroyForcibly} on stop).
 *
 * <p>One sidecar per JVM, reached over {@code 127.0.0.1:<imagegen.local.port>}.
 * {@link #ensureRunning()} is the single entry point: it returns fast when the
 * daemon is already healthy, otherwise it preflights {@link FluxSidecarProbe},
 * spawns {@code uv run serve.py}, and blocks until {@code /health} responds. The
 * daemon may self-evict after its idle timeout, so callers must always go through
 * {@code ensureRunning()} rather than caching the running state.
 *
 * <p>Registered for graceful shutdown via {@code jobs.ShutdownJob}.
 */
public final class LocalFluxSidecarManager {

    private static final String DEFAULT_MODEL = "black-forest-labs/FLUX.2-klein-4B";
    private static final int DEFAULT_PORT = 9527;
    private static final Object LOCK = new Object();

    private static Process process;
    private static Thread outDrain;
    private static Thread errDrain;

    private LocalFluxSidecarManager() {}

    static int port() {
        return ConfigService.getInt("imagegen.local.port", DEFAULT_PORT);
    }

    static String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /**
     * Ensure the sidecar is up and return its base URL. Idempotent and
     * single-flight: concurrent callers serialize on {@link #LOCK} so only one
     * daemon is ever spawned. Throws {@link ImageGenerationException} when uv is
     * absent, the script is missing, or the daemon doesn't become healthy in time.
     */
    public static String ensureRunning() {
        if (isHealthy()) return baseUrl();
        synchronized (LOCK) {
            if (isHealthy()) return baseUrl();
            if (!FluxSidecarProbe.isAvailable()) {
                throw new ImageGenerationException(
                        "local image generation requires 'uv' on PATH: "
                                + FluxSidecarProbe.lastResult().reason());
            }
            spawn();
            awaitHealthy();
            return baseUrl();
        }
    }

    private static void spawn() {
        var sidecarDir = new File(Play.applicationPath, "sidecar/flux");
        var serve = new File(sidecarDir, "serve.py");
        if (!serve.isFile()) {
            throw new ImageGenerationException(
                    "Flux sidecar script not found at " + serve.getAbsolutePath());
        }
        var cacheDir = new File(Play.applicationPath, "data/flux-models").getAbsolutePath();
        var model = ConfigService.get("imagegen.local.model", DEFAULT_MODEL);
        int idleMin = ConfigService.getInt("imagegen.local.idleTimeoutMinutes", 15);
        var cmd = List.of("uv", "run", "serve.py",
                "--host", "127.0.0.1",
                "--port", String.valueOf(port()),
                "--model", model,
                "--cache-dir", cacheDir,
                "--idle-timeout-min", String.valueOf(idleMin));
        try {
            var pb = new ProcessBuilder(cmd).directory(sidecarDir);
            // Optional HF token → HF_TOKEN env. huggingface_hub reads it automatically,
            // so the sidecar needs no change; the secret stays scoped to the child process
            // and is never written to a token file under data/.
            var hfToken = ConfigService.get("imagegen.local.hfToken");
            if (hfToken != null && !hfToken.isBlank()) {
                pb.environment().put("HF_TOKEN", hfToken);
            }
            process = pb.start();
            // The sidecar writes operational lines (listening, request logs,
            // idle-exit) to stderr; stdout carries nothing (image bytes go over
            // the HTTP socket). Drain both on VTs so a full pipe never blocks it.
            outDrain = Thread.ofVirtual().name("flux-sidecar-stdout")
                    .start(() -> drain(process.getInputStream(), false));
            errDrain = Thread.ofVirtual().name("flux-sidecar-stderr")
                    .start(() -> drain(process.getErrorStream(), true));
            EventLogger.info("imagegen",
                    "Flux sidecar starting (model=%s port=%d)".formatted(model, port()));
        } catch (IOException e) {
            throw new ImageGenerationException("failed to launch Flux sidecar: " + e.getMessage(), e);
        }
    }

    private static void awaitHealthy() {
        int timeoutS = ConfigService.getInt("imagegen.local.startupTimeoutSeconds", 180);
        long deadline = System.nanoTime() + timeoutS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new ImageGenerationException(
                        "Flux sidecar exited during startup (exit %d) — check the logs"
                                .formatted(process.exitValue()));
            }
            if (isHealthy()) return;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ImageGenerationException("interrupted while waiting for Flux sidecar", e);
            }
        }
        throw new ImageGenerationException(
                ("Flux sidecar did not become healthy within %ds — the first launch installs "
                        + "~GB of Python deps (torch/diffusers); pre-warm it from Settings").formatted(timeoutS));
    }

    /** Cheap liveness check: GET /health with a short per-call deadline. */
    static boolean isHealthy() {
        var call = HttpFactories.general().newCall(
                new Request.Builder().url(baseUrl() + "/health").get().build());
        call.timeout().timeout(5, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            // Drain the body so the sidecar finishes its write — closing the
            // connection early races its socket write and spams its stderr with
            // BrokenPipe tracebacks (the body is tiny, so this is cheap).
            if (resp.body() != null) resp.body().bytes();
            return resp.isSuccessful();
        } catch (IOException e) {
            return false;
        }
    }

    private static void drain(InputStream in, boolean stderr) {
        try (var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                // The sidecar (and uv/torch/http.server) write normal operational logs to
                // stderr by convention — it's not an error stream here — so drain stderr at
                // INFO and stdout at DEBUG. Log the raw line: the thread name
                // (flux-sidecar-stderr/-stdout) is the attribution, and the sidecar's own
                // lines already carry a [flux-sidecar] marker, so don't add a second one.
                if (stderr) {
                    Logger.info("%s", line);
                } else {
                    Logger.debug("%s", line);
                }
            }
        } catch (IOException _) {
            /* process closed — normal on stop/idle-exit */
        }
    }

    /**
     * Stop the sidecar if running. Close discipline mirrors
     * {@link mcp.transport.McpStdioTransport#close()}: {@code destroy()} for a
     * clean exit, {@code destroyForcibly()} if it doesn't go within 2s. Wired into
     * {@code jobs.ShutdownJob} so the daemon (and its GPU memory) is released on
     * JVM shutdown.
     */
    public static void stop() {
        synchronized (LOCK) {
            if (process == null) return;
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            if (outDrain != null) outDrain.interrupt();
            if (errDrain != null) errDrain.interrupt();
            process = null;
        }
    }
}
