package services.videogen;

import okhttp3.Request;
import play.Logger;
import play.Play;
import services.ConfigService;
import services.EventLogger;
import services.imagegen.FluxSidecarProbe;
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
 * Lifecycle owner for the local video-generation Python sidecar (JCLAW-232/233) — the async analogue of
 * {@link services.imagegen.LocalFluxSidecarManager}, whose start/drain/graceful-close discipline this
 * mirrors. DEDICATED (not the Flux process): a video model can't share VRAM with Flux and the Mac path
 * differs (SV-3). One sidecar per JVM serving one engine ({@code ltx} / {@code wan-5b} / {@code wan-14b})
 * over {@code 127.0.0.1:<videogen.local.port>}; switching the active engine restarts it.
 *
 * <p>{@link #ensureRunning(String)} is the single entry point: it returns fast when the daemon is
 * already healthy on the requested engine, otherwise it preflights {@code uv}, spawns
 * {@code uv run serve.py}, and blocks until {@code /health} responds. The daemon self-evicts after its
 * idle timeout, so callers must always go through {@code ensureRunning}. Registered for graceful
 * shutdown via {@code jobs.ShutdownJob}.
 */
public final class LocalVideoSidecarManager {

    private static final int DEFAULT_PORT = 9528;
    private static final Object LOCK = new Object();

    private static Process process;
    private static Thread outDrain;
    private static Thread errDrain;
    private static String runningModel; // the engine the live sidecar is serving

    private LocalVideoSidecarManager() {}

    static int port() {
        return ConfigService.getInt("videogen.local.port", DEFAULT_PORT);
    }

    static String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** Ensure the sidecar is up serving {@code model} and return its base URL; restarts if it's serving
     *  a different engine. Idempotent + single-flight on {@link #LOCK}. */
    public static String ensureRunning(String model) {
        if (model.equals(runningModel) && isHealthy()) return baseUrl();
        synchronized (LOCK) {
            if (model.equals(runningModel) && isHealthy()) return baseUrl();
            if (!FluxSidecarProbe.isAvailable()) { // shared uv-on-PATH probe
                throw new VideoGenerationException(
                        "local video generation requires 'uv' on PATH: " + FluxSidecarProbe.lastResult().reason());
            }
            if (process != null) stop(); // switching engine, or stale -> restart
            spawn(model);
            awaitHealthy();
            runningModel = model;
            return baseUrl();
        }
    }

    private static void spawn(String model) {
        var sidecarDir = new File(Play.applicationPath, "sidecar/video");
        var serve = new File(sidecarDir, "serve.py");
        if (!serve.isFile()) {
            throw new VideoGenerationException("video sidecar script not found at " + serve.getAbsolutePath());
        }
        var cacheDir = new File(Play.applicationPath, "data/video-models").getAbsolutePath();
        int idleMin = ConfigService.getInt("videogen.local.idleTimeoutMinutes", 15);
        var cmd = List.of("uv", "run", "serve.py",
                "--host", "127.0.0.1",
                "--port", String.valueOf(port()),
                "--model", model,
                "--cache-dir", cacheDir,
                "--idle-timeout-min", String.valueOf(idleMin));
        try {
            var pb = new ProcessBuilder(cmd).directory(sidecarDir);
            var hfToken = ConfigService.get("videogen.local.hfToken");
            if (hfToken != null && !hfToken.isBlank()) {
                pb.environment().put("HF_TOKEN", hfToken);
            }
            process = pb.start();
            outDrain = Thread.ofVirtual().name("video-sidecar-stdout")
                    .start(() -> drain(process.getInputStream(), false));
            errDrain = Thread.ofVirtual().name("video-sidecar-stderr")
                    .start(() -> drain(process.getErrorStream(), true));
            EventLogger.info("videogen", "video sidecar starting (model=%s port=%d)".formatted(model, port()));
        } catch (IOException e) {
            throw new VideoGenerationException("failed to launch video sidecar: " + e.getMessage(), e);
        }
    }

    private static void awaitHealthy() {
        // Larger floor than Flux: video deps (torch/diffusers + a multi-GB model) take longer on first launch.
        int timeoutS = ConfigService.getInt("videogen.local.startupTimeoutSeconds", 300);
        long deadline = System.nanoTime() + timeoutS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new VideoGenerationException(
                        "video sidecar exited during startup (exit %d) — check the logs".formatted(process.exitValue()));
            }
            if (isHealthy()) return;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VideoGenerationException("interrupted while waiting for video sidecar", e);
            }
        }
        throw new VideoGenerationException(
                ("video sidecar did not become healthy within %ds — the first launch installs ~GB of Python "
                        + "deps (torch/diffusers) and may pull a multi-GB model; pre-warm it from Settings").formatted(timeoutS));
    }

    /** Cheap liveness check: GET /health with a short per-call deadline. */
    static boolean isHealthy() {
        var call = HttpFactories.general().newCall(
                new Request.Builder().url(baseUrl() + "/health").get().build());
        call.timeout().timeout(5, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            resp.body().bytes(); // drain so the sidecar finishes its write (avoids BrokenPipe spam)
            return resp.isSuccessful();
        } catch (IOException _) {
            return false;
        }
    }

    private static void drain(InputStream in, boolean stderr) {
        try (var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
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

    /** Stop the sidecar if running (releases its GPU memory). Wired into {@code jobs.ShutdownJob}. */
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
            runningModel = null;
        }
    }
}
