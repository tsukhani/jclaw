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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Supplier;

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
 * manager facade. Concurrency (JCLAW-830) splits the two jobs the old coarse
 * monitor conflated:
 * <ul>
 *   <li><b>Single-flight</b> — {@link #singleFlight(Supplier)} runs a
 *       spawn + {@link #awaitHealthy()} sequence under {@code startLock} so at
 *       most one thread spawns on the fixed port at a time. A second concurrent
 *       starter blocks there, then its own health re-check short-circuits it to a
 *       no-op (a double-spawn on the fixed port would poison the
 *       {@link #spawnFailedUntil} cooldown for the healthy sidecar).</li>
 *   <li><b>Safe publication</b> — {@link #process} and the drain threads are
 *       {@code volatile} and every compound read/write is guarded by the
 *       short-held {@code procLock}, never across the blocking startup poll. So
 *       {@link #stop()} can observe and terminate a process another thread is
 *       still spawning without stalling behind the multi-second (minutes on first
 *       launch) health-await.</li>
 * </ul>
 *
 * <p>{@link #stop()} deliberately does <em>not</em> take {@code startLock}: an
 * idle-respawn, shutdown, or engine-switch stop never waits on an in-flight
 * spawn. A stop that races a spawn is made orphan-free by the {@code stopGeneration}
 * hand-off (see {@code spawnNow} and {@link #stop()}).
 *
 * <p>{@link #lock()} is retained only for the diarization facade
 * ({@code services.transcription.DiarizeSidecarManager}), which still serializes
 * its own {@code ensureRunning} on that monitor; it is orthogonal to {@code startLock}
 * and is not the lock {@code stop()} uses.
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

    /** JCLAW-830 single-flight: only one thread spawns on the fixed port at a
     *  time. Held across spawn + {@link #awaitHealthy()} so a second concurrent
     *  starter waits for the in-flight spawn then re-checks health and no-ops,
     *  rather than double-spawning. Deliberately NOT the lock {@link #stop()}
     *  uses, so a stop never stalls behind the startup poll. */
    private final ReentrantLock startLock = new ReentrantLock();

    /** JCLAW-830 short-held publication guard for {@link #process} and the drain
     *  threads: held only around field access, never across the spawn or the
     *  health poll, so {@link #stop()} can observe/terminate a spawning process
     *  without blocking on startup. */
    private final Object procLock = new Object();

    /** JCLAW-830: bumped by {@link #stop()} under {@link #procLock}. A spawn whose
     *  generation changed between launching its child and publishing it aborts
     *  (kills the just-launched orphan) — closing the stop-before-publish race.
     *  Volatile so the {@link #awaitHealthy()} poll can read it lock-free. */
    private volatile long stopGeneration;

    private volatile Process process;
    private volatile Thread outDrain;
    private volatile Thread errDrain;

    public LocalSidecarDaemon(Config cfg) {
        this.cfg = cfg;
    }

    /** Retained for the diarization facade
     *  ({@code services.transcription.DiarizeSidecarManager}), which still
     *  serializes its own {@code ensureRunning} on this monitor. The four other
     *  facades use {@link #singleFlight(Supplier)} instead (JCLAW-830). This
     *  monitor is orthogonal to {@code startLock} and is not the lock
     *  {@link #stop()} uses. */
    public Object lock() {
        return lock;
    }

    /**
     * Run {@code action} — a health re-check + spawn + {@link #awaitHealthy()}
     * sequence — under the single-flight {@code startLock} (JCLAW-830). At most
     * one thread spawns on the fixed port at a time; a second concurrent caller
     * blocks here until the in-flight spawn finishes, then {@code action}'s own
     * health re-check short-circuits it to a no-op. {@link #stop()} does not take
     * {@code startLock}, so it never stalls behind the startup poll.
     */
    public <T> T singleFlight(Supplier<T> action) {
        startLock.lock();
        try {
            return action.get();
        } finally {
            startLock.unlock();
        }
    }

    public int port() {
        return ConfigService.getInt(cfg.configPrefix() + ".port", cfg.defaultPort());
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** Whether a process handle exists (alive or not) — drives the engine-switch
     *  restart. Volatile read; safe without a lock (JCLAW-830). */
    public boolean hasProcess() {
        return process != null;
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
     * the ASR sidecar's weights are ungated and need none). Invoke inside
     * {@link #singleFlight(Supplier)} (or, for the diarization facade, while
     * holding {@link #lock()}) so only one spawn runs on the fixed port at a time.
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
        } catch (StartCancelledException e) {
            // JCLAW-830: a concurrent stop() cancelled this launch — that is a
            // deliberate cancellation, not a startup failure, so do NOT poison the
            // cooldown. Surface it as the domain exception for the caller.
            throw cfg.fail().apply(e.getMessage(), null);
        } catch (RuntimeException e) {
            spawnFailedUntil = System.currentTimeMillis() + SPAWN_FAILURE_COOLDOWN_MS;
            spawnFailureMessage = e.getMessage();
            throw e;
        }
    }

    /** JCLAW-830: internal marker that {@code spawnNow} raced a {@link #stop()}
     *  and abandoned its just-launched child. Distinct from a real launch failure
     *  so {@link #spawn(String, String)} skips the {@code spawnFailedUntil} cooldown. */
    private static final class StartCancelledException extends RuntimeException {
        StartCancelledException(String message) {
            super(message);
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
        // JCLAW-830: snapshot the stop generation before launching. If stop()
        // bumps it while the child is starting, the publish below aborts and
        // kills the orphan so stop()'s "no running process" intent holds.
        long genAtLaunch = stopGeneration;

        Process proc;
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
            proc = pb.start();
        } catch (IOException e) {
            throw cfg.fail().apply("failed to launch %s: %s".formatted(cfg.displayName(), e.getMessage()), e);
        }

        // Publish the child (and start its drains) atomically w.r.t. stop(). If a
        // stop() slipped in between the snapshot and here, abandon and kill the
        // just-launched process instead of publishing it — otherwise stop() would
        // have returned seeing no process while this leaves one running.
        synchronized (procLock) {
            if (stopGeneration != genAtLaunch) {
                proc.destroyForcibly();
                throw new StartCancelledException(
                        "%s start was cancelled by a concurrent stop".formatted(cfg.displayName()));
            }
            process = proc;
            // The sidecar writes operational lines (listening, request logs, idle-exit)
            // to stderr; stdout carries nothing (payloads go over the HTTP socket). Drain
            // both on VTs so a full pipe never blocks the child.
            outDrain = Thread.ofVirtual().name(cfg.threadPrefix() + "-stdout")
                    .start(() -> drain(proc.getInputStream(), false));
            errDrain = Thread.ofVirtual().name(cfg.threadPrefix() + "-stderr")
                    .start(() -> drain(proc.getErrorStream(), true));
        }
        EventLogger.info(cfg.logChannel(),
                "%s starting (model=%s port=%d)".formatted(cfg.displayName(), model, port()));
    }

    /** Block until {@code /health} responds or the startup deadline elapses. Runs
     *  without holding any monitor {@link #stop()} needs; invoke from inside the
     *  single-flight section that launched the process ({@link #singleFlight(Supplier)},
     *  or {@link #lock()} for the diarization facade). */
    public void awaitHealthy() {
        int timeoutS = ConfigService.getInt(cfg.configPrefix() + ".startupTimeoutSeconds", cfg.defaultStartupTimeoutS());
        // JCLAW-830: if stop() intervenes while we poll, abort promptly instead of
        // burning the full timeout on a process it already terminated.
        long genAtStart = stopGeneration;
        long deadline = System.nanoTime() + timeoutS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (stopGeneration != genAtStart) {
                throw cfg.fail().apply(
                        "%s startup was cancelled by a concurrent stop".formatted(cfg.displayName()), null);
            }
            Process p = process; // volatile snapshot — stop() may null it under us
            if (p != null && !p.isAlive()) {
                throw cfg.fail().apply(
                        "%s exited during startup (exit %d) — check the logs"
                                .formatted(cfg.displayName(), p.exitValue()), null);
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
     * {@code destroyForcibly()} if it doesn't go within 2s.
     *
     * <p>JCLAW-830: takes only the short {@link #procLock} to bump
     * {@code stopGeneration} and unpublish the process fields, then does the
     * (up-to-2s) terminate <em>outside</em> the lock. It never touches
     * {@code startLock}, so it never stalls behind an in-flight spawn's health
     * poll. The generation bump makes a spawn racing this stop orphan-free: a
     * spawn that has not yet published sees the changed generation and kills its
     * own child; one that has published is the process we terminate here.
     */
    public void stop() {
        Process p;
        Thread out;
        Thread err;
        synchronized (procLock) {
            stopGeneration++;
            p = process;
            out = outDrain;
            err = errDrain;
            process = null;
            outDrain = null;
            errDrain = null;
        }
        // p == null: nothing published. Either nothing was running, or a spawn is
        // mid-launch — the generation bump above makes it abort and kill its child.
        if (p == null) return;
        p.destroy();
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        if (out != null) out.interrupt();
        if (err != null) err.interrupt();
    }
}
