package services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import play.Logger;
import play.Play;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Shared host-capability probe for the local generation sidecars (image + video). Runs a sidecar's
 * one-shot {@code uv run serve.py --probe} on a background virtual thread to detect the GPU + free VRAM,
 * caches the verdict, and exposes it to the Settings "can this machine run it?" gate. The per-domain
 * probes ({@link services.imagegen.ImageCapabilityProbe}, {@link services.videogen.VideoCapabilityProbe})
 * are thin facades over an instance of this — the same split as {@link LocalSidecarDaemon} ↔ the
 * per-domain sidecar managers.
 *
 * <p>The first probe pays the one-time {@code uv} dependency install (minutes); later probes reuse the
 * venv and return in seconds, so the result is cached and re-probed only on demand. The probe never loads
 * a model — it's pure host detection. Two instances (image, video) hold independent state.
 */
public final class SidecarCapabilityProbe {

    public enum State { NEEDS_PROBE, PROBING, READY, UNAVAILABLE, ERROR }

    /** {@code capability} is the parsed sidecar {@code --probe} payload (null until READY). */
    public record Snapshot(boolean uvAvailable, String uvReason, State state, JsonObject capability, String error) {}

    private final String sidecarRelDir; // e.g. "sidecar/video"
    private final String threadName;    // probe virtual-thread name
    private final String logLabel;      // e.g. "videogen" — only used in the failure log line

    private final Object lock = new Object();
    private volatile State state = State.NEEDS_PROBE;
    private volatile JsonObject capability;
    private volatile String error;

    public SidecarCapabilityProbe(String sidecarRelDir, String threadName, String logLabel) {
        this.sidecarRelDir = sidecarRelDir;
        this.threadName = threadName;
        this.logLabel = logLabel;
    }

    /** Snapshot for the Settings UI. Reports UNAVAILABLE when {@code uv} (the sidecar prerequisite) is absent. */
    public Snapshot snapshot() {
        var uv = UvProbe.lastResult();
        // Force one probe if the cache is still on the UNRUN sentinel (mirrors the controller state path).
        if (!uv.available() && uv.reason() != null && uv.reason().startsWith("uv probe has not run")) {
            uv = UvProbe.probe();
        }
        var st = uv.available() ? state : State.UNAVAILABLE;
        return new Snapshot(uv.available(), uv.reason(), st, capability, error);
    }

    /** Kick off a background probe if one isn't already running. Idempotent — concurrent calls from the
     *  polling UI collapse onto the single in-flight probe. */
    public void probe() {
        if (!UvProbe.isAvailable()) return; // snapshot() then reports UNAVAILABLE
        synchronized (lock) {
            if (state == State.PROBING) return;
            state = State.PROBING;
            error = null;
        }
        Thread.ofVirtual().name(threadName).start(this::runProbe);
    }

    private void runProbe() {
        try {
            var sidecarDir = new File(Play.applicationPath, sidecarRelDir);
            var proc = new ProcessBuilder(List.of("uv", "run", "serve.py", "--probe"))
                    .directory(sidecarDir).start();
            // Drain stderr concurrently so a chatty torch/uv import can't fill the pipe and deadlock the
            // stdout read (the classic ProcessBuilder pitfall).
            var errBuf = new StringBuilder();
            var errDrain = Thread.ofVirtual().start(() -> {
                try (var r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) errBuf.append(line).append('\n');
                } catch (IOException _) {
                    /* process closed */
                }
            });
            var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = proc.waitFor();
            errDrain.join(1000);
            if (code != 0 || out.isEmpty()) {
                fail("capability probe exited %d: %s".formatted(code, tail(errBuf.toString(), 300)));
                return;
            }
            capability = JsonParser.parseString(out).getAsJsonObject();
            state = State.READY;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            fail("capability probe interrupted");
        } catch (Exception e) {
            Logger.warn(e, "%s capability probe failed", logLabel);
            fail("capability probe error: " + e.getMessage());
        }
    }

    private void fail(String message) {
        error = message;
        state = State.ERROR;
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
