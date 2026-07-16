package services;

import com.google.gson.JsonParser;
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

    /** JCLAW-626: after a spawn failure, fail fast for a cooldown instead
     *  of letting every caller pay the full startup timeout in a row.
     *  Cleared by a successful spawn or config change won't rescue it —
     *  the memo simply expires. */
    private volatile long spawnFailedUntil = 0;
    private volatile String spawnFailureMessage = null;
    private static final long SPAWN_FAILURE_COOLDOWN_MS = 60_000;

    /** As {@link #spawn(String, String)}, reading the HF token from the
     *  domain's own {@code <prefix>.hfToken} config key. */
    public void spawn(String model) {
        spawn(model, ConfigService.get(cfg.configPrefix() + ".hfToken"));
    }

    /**
     * Spawn the sidecar serving {@code model}, passing {@code hfToken} (when
     * non-blank) to the child as {@code HF_TOKEN}. The token parameter exists
     * so a facade can pass an explicit value (or null to force no token —
     * the ASR sidecar's weights are ungated and need none). The caller must hold {@link #lock()}.
     */
    public void spawn(String model, String hfToken) {
        if (System.currentTimeMillis() < spawnFailedUntil) {
            throw cfg.fail().apply(
                    "%s recently failed to start (%s) — retrying automatically in under a minute"
                            .formatted(cfg.displayName(), spawnFailureMessage), null);
        }
        try {
            spawnNow(model, hfToken);
            spawnFailedUntil = 0;
            spawnFailureMessage = null;
        } catch (RuntimeException e) {
            spawnFailedUntil = System.currentTimeMillis() + SPAWN_FAILURE_COOLDOWN_MS;
            spawnFailureMessage = e.getMessage();
            throw e;
        }
    }

    private void spawnNow(String model, String hfToken) {
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
            // JCLAW-641: the sidecar's subprocess ceilings derive from the
            // JVM's timeout knob (minus a margin so the sidecar errors
            // before the JVM's socket gives up). Sidecars without the
            // concept ignore the env var.
            int jvmTimeout = ConfigService.getInt(cfg.configPrefix() + ".timeoutSeconds", 1800);
            pb.environment().put("SIDECAR_REQUEST_TIMEOUT_SEC",
                    String.valueOf(Math.max(60, jvmTimeout - 60)));
            // Optional HF token → HF_TOKEN env. huggingface_hub reads it automatically,
            // so the sidecar needs no change; the secret stays scoped to the child
            // process and is never written to a token file under data/.
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
        return isHealthy(null);
    }

    /**
     * As {@link #isHealthy()}, additionally validating sidecar IDENTITY
     * (JCLAW-637): a sidecar that survived a JVM crash is adopted by the
     * restarted JVM via this fast path, and if the configured model changed
     * between runs the orphan would serve the OLD model while the cache
     * fingerprints results with the NEW name — mislabeled entries surviving
     * the very model bump the fingerprint exists to invalidate. On mismatch
     * the orphan is evicted via POST /shutdown (no Process handle exists
     * for an adopted process) and this reports unhealthy so the caller
     * respawns with the right model.
     */
    public boolean isHealthy(String expectedModel) {
        var call = HttpFactories.general().newCall(
                new Request.Builder().url(baseUrl() + "/health").get().build());
        call.timeout().timeout(5, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            // Drain the body so the sidecar finishes its write — closing the connection
            // early races its socket write and spams its stderr with BrokenPipe tracebacks
            // (the body is tiny, so this is cheap).
            var body = new String(resp.body().bytes(), StandardCharsets.UTF_8);
            if (!resp.isSuccessful()) return false;
            if (expectedModel == null || expectedModel.isBlank()) return true;
            var served = healthModel(body);
            if (served == null || served.equals(expectedModel)) return true;
            Logger.warn("%s: adopted sidecar serves model '%s' but config wants '%s' — evicting",
                    cfg.displayName(), served, expectedModel);
            evict();
            return false;
        } catch (IOException _) {
            return false;
        }
    }

    /** The "model" field of a /health JSON body, or null if unparseable. */
    public static String healthModel(String healthJson) {
        try {
            var root = JsonParser.parseString(healthJson).getAsJsonObject();
            return root.has("model") ? root.get("model").getAsString() : null;
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** Best-effort POST /shutdown for a process we hold no handle to. */
    private void evict() {
        var call = HttpFactories.general().newCall(new Request.Builder()
                .url(baseUrl() + "/shutdown")
                .post(okhttp3.RequestBody.create(new byte[0]))
                .build());
        call.timeout().timeout(5, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            resp.body().bytes();
        } catch (IOException _) {
            // it may have died mid-response; the respawn path handles the rest
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
