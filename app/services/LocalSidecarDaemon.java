package services;

import okhttp3.Request;
import play.Logger;
import play.Play;
import utils.HttpFactories;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Shared lifecycle mechanism for jclaw's local Python sidecars — the imagegen
 * daemon ({@link services.imagegen.LocalImageSidecarManager}) and the videogen
 * daemon ({@link services.videogen.LocalVideoSidecarManager}). Both
 * spawn {@code uv run serve.py}, drain its streams on virtual threads, poll
 * {@code /health}, and stop with a {@code destroy()} → {@code destroyForcibly()}
 * discipline; only the directories, config keys, labels, and exception type
 * differ — captured in {@link Config}. The closest precedent for the
 * start/drain/graceful-close discipline is {@code mcp.transport.McpStdioTransport}.
 *
 * <p>One instance per managed daemon, held as a static field by the per-domain
 * manager facade. {@link #lock()} is the single-flight monitor the facade
 * synchronizes {@code ensureRunning} on; {@link #spawn(String)} and
 * {@link #awaitHealthy()} assume that lock is held, while {@link #stop()}
 * acquires it (the JVM monitor is reentrant, so a facade may call {@code stop()}
 * from inside the lock — as the videogen engine-switch path does).
 */
public final class LocalSidecarDaemon {

    /**
     * Per-domain configuration. {@code fail} builds the domain's runtime
     * exception (e.g. {@code ImageGenerationException::new}); {@code displayName}
     * and {@code startupHint} template the operator-facing messages verbatim.
     */
    public record Config(
            String sidecarSubdir,
            String cacheSubdir,
            String configPrefix,
            int defaultPort,
            int defaultStartupTimeoutS,
            String logChannel,
            String threadPrefix,
            String displayName,
            String startupHint,
            BiFunction<String, Throwable, RuntimeException> fail) {}

    private final Config cfg;
    private final Object lock = new Object();

    private Process process;
    private Thread outDrain;
    private Thread errDrain;

    public LocalSidecarDaemon(Config cfg) {
        this.cfg = cfg;
    }

    /** Single-flight monitor; the facade synchronizes {@code ensureRunning} on it. */
    public Object lock() {
        return lock;
    }

    public int port() {
        return ConfigService.getInt(cfg.configPrefix() + ".port", cfg.defaultPort());
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** Whether a process handle exists (alive or not) — drives the engine-switch restart. */
    public boolean hasProcess() {
        synchronized (lock) {
            return process != null;
        }
    }

    /** Spawn the sidecar serving {@code model}. The caller must hold {@link #lock()}. */
    public void spawn(String model) {
        var sidecarDir = new File(Play.applicationPath, cfg.sidecarSubdir());
        var serve = new File(sidecarDir, "serve.py");
        if (!serve.isFile()) {
            throw cfg.fail().apply(
                    "%s script not found at %s".formatted(cfg.displayName(), serve.getAbsolutePath()), null);
        }
        var cacheDir = new File(Play.applicationPath, cfg.cacheSubdir()).getAbsolutePath();
        int idleMin = ConfigService.getInt(cfg.configPrefix() + ".idleTimeoutMinutes", 15);
        var cmd = List.of("uv", "run", "serve.py",
                "--host", "127.0.0.1",
                "--port", String.valueOf(port()),
                "--model", model,
                "--cache-dir", cacheDir,
                "--idle-timeout-min", String.valueOf(idleMin));
        try {
            var pb = new ProcessBuilder(cmd).directory(sidecarDir);
            // Optional HF token → HF_TOKEN env. huggingface_hub reads it automatically,
            // so the sidecar needs no change; the secret stays scoped to the child
            // process and is never written to a token file under data/.
            var hfToken = ConfigService.get(cfg.configPrefix() + ".hfToken");
            if (hfToken != null && !hfToken.isBlank()) {
                pb.environment().put("HF_TOKEN", hfToken);
            }
            process = pb.start();
            // The sidecar writes operational lines (listening, request logs, idle-exit)
            // to stderr; stdout carries nothing (payloads go over the HTTP socket). Drain
            // both on VTs so a full pipe never blocks the child.
            outDrain = Thread.ofVirtual().name(cfg.threadPrefix() + "-stdout")
                    .start(() -> drain(process.getInputStream(), false));
            errDrain = Thread.ofVirtual().name(cfg.threadPrefix() + "-stderr")
                    .start(() -> drain(process.getErrorStream(), true));
            EventLogger.info(cfg.logChannel(),
                    "%s starting (model=%s port=%d)".formatted(cfg.displayName(), model, port()));
        } catch (IOException e) {
            throw cfg.fail().apply("failed to launch %s: %s".formatted(cfg.displayName(), e.getMessage()), e);
        }
    }

    /** Block until {@code /health} responds or the startup deadline elapses. Caller holds {@link #lock()}. */
    public void awaitHealthy() {
        int timeoutS = ConfigService.getInt(cfg.configPrefix() + ".startupTimeoutSeconds", cfg.defaultStartupTimeoutS());
        long deadline = System.nanoTime() + timeoutS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (process != null && !process.isAlive()) {
                throw cfg.fail().apply(
                        "%s exited during startup (exit %d) — check the logs"
                                .formatted(cfg.displayName(), process.exitValue()), null);
            }
            if (isHealthy()) return;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw cfg.fail().apply("interrupted while waiting for " + cfg.displayName(), e);
            }
        }
        throw cfg.fail().apply(
                "%s did not become healthy within %ds — %s".formatted(cfg.displayName(), timeoutS, cfg.startupHint()),
                null);
    }

    /** Cheap liveness check: GET /health with a short per-call deadline. */
    public boolean isHealthy() {
        var call = HttpFactories.general().newCall(
                new Request.Builder().url(baseUrl() + "/health").get().build());
        call.timeout().timeout(5, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            // Drain the body so the sidecar finishes its write — closing the connection
            // early races its socket write and spams its stderr with BrokenPipe tracebacks
            // (the body is tiny, so this is cheap).
            resp.body().bytes();
            return resp.isSuccessful();
        } catch (IOException _) {
            return false;
        }
    }

    private void drain(InputStream in, boolean stderr) {
        try (var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                // The sidecar (and uv/torch/http.server) write normal operational logs to
                // stderr by convention — it's not an error stream here — so drain stderr at
                // INFO and stdout at DEBUG. The thread name is the attribution.
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
     * Stop the sidecar if running. {@code destroy()} for a clean exit,
     * {@code destroyForcibly()} if it doesn't go within 2s. Acquires {@link #lock()}.
     */
    public void stop() {
        synchronized (lock) {
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
