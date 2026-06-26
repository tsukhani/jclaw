package services.videogen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import play.Logger;
import play.Play;
import services.imagegen.FluxSidecarProbe;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Adaptive host-capability probe for local video generation (SV-2 / JCLAW-232/233). Runs the sidecar's
 * one-shot {@code uv run serve.py --probe} to detect the GPU + free VRAM and tier every engine, which
 * drives the Settings dropdown so the operator only sees what THIS host can actually run (and WAN is
 * greyed out off NVIDIA). Mirrors the imagegen model-download state machine
 * ({@code services.imagegen.FluxModelManager}): a POST kicks off a background probe, a GET polls the
 * snapshot, and the UI stops polling once the state settles.
 *
 * <p>The first probe pays the one-time {@code uv} dependency install (minutes); later probes reuse the
 * venv and return in seconds, so the result is cached and re-probed only on demand. The probe never
 * loads a model — it's pure host detection.
 */
public final class VideoCapabilityProbe {

    public enum State { NEEDS_PROBE, PROBING, READY, UNAVAILABLE, ERROR }

    /** {@code capability} is the parsed sidecar {@code --probe} payload (null until READY). */
    public record Snapshot(boolean uvAvailable, String uvReason, State state, JsonObject capability, String error) {}

    private static final Object LOCK = new Object();
    private static volatile State state = State.NEEDS_PROBE;
    private static volatile JsonObject capability;
    private static volatile String error;

    private VideoCapabilityProbe() {}

    /** Snapshot for the Settings UI. Reports UNAVAILABLE when {@code uv} (the sidecar prerequisite) is absent. */
    public static Snapshot snapshot() {
        var uv = FluxSidecarProbe.lastResult();
        // Force one probe if the cache is still on the UNRUN sentinel (mirrors ApiImagegenController.state).
        if (!uv.available() && uv.reason() != null && uv.reason().startsWith("uv probe has not run")) {
            uv = FluxSidecarProbe.probe();
        }
        var st = uv.available() ? state : State.UNAVAILABLE;
        return new Snapshot(uv.available(), uv.reason(), st, capability, error);
    }

    /** Kick off a background probe if one isn't already running. Idempotent — concurrent calls from the
     *  polling UI collapse onto the single in-flight probe. */
    public static void probe() {
        if (!FluxSidecarProbe.isAvailable()) return; // snapshot() then reports UNAVAILABLE
        synchronized (LOCK) {
            if (state == State.PROBING) return;
            state = State.PROBING;
            error = null;
        }
        Thread.ofVirtual().name("videogen-capability-probe").start(VideoCapabilityProbe::runProbe);
    }

    private static void runProbe() {
        try {
            var sidecarDir = new File(Play.applicationPath, "sidecar/video");
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("capability probe interrupted");
        } catch (Exception e) {
            Logger.warn(e, "videogen capability probe failed");
            fail("capability probe error: " + e.getMessage());
        }
    }

    private static void fail(String message) {
        error = message;
        state = State.ERROR;
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
