package services.imagegen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import play.Logger;
import play.Play;
import services.UvProbe;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Host-capability probe for local image generation — the imagegen analogue of
 * {@link services.videogen.VideoCapabilityProbe}. Runs the image sidecar's one-shot
 * {@code uv run serve.py --probe} to detect the GPU + free VRAM and decide whether THIS machine can run
 * Flux locally, which gates the Settings "Self-Hosted" radio (disabled when no capable hardware).
 *
 * <p>The first probe pays the one-time {@code uv} dependency install (minutes); later probes reuse the
 * venv and return in seconds, so the result is cached and re-probed only on demand. The probe never loads
 * a model — it's pure host detection.
 *
 * <p>(Mirrors {@code VideoCapabilityProbe}; the two could share a parameterised base if a third sidecar
 * ever needs one.)
 */
public final class ImageCapabilityProbe {

    public enum State { NEEDS_PROBE, PROBING, READY, UNAVAILABLE, ERROR }

    /** {@code capability} is the parsed sidecar {@code --probe} payload (null until READY). */
    public record Snapshot(boolean uvAvailable, String uvReason, State state, JsonObject capability, String error) {}

    private static final Object LOCK = new Object();
    private static volatile State state = State.NEEDS_PROBE;
    private static volatile JsonObject capability;
    private static volatile String error;

    private ImageCapabilityProbe() {}

    /** Snapshot for the Settings UI. Reports UNAVAILABLE when {@code uv} (the sidecar prerequisite) is absent. */
    public static Snapshot snapshot() {
        var uv = UvProbe.lastResult();
        if (!uv.available() && uv.reason() != null && uv.reason().startsWith("uv probe has not run")) {
            uv = UvProbe.probe();
        }
        var st = uv.available() ? state : State.UNAVAILABLE;
        return new Snapshot(uv.available(), uv.reason(), st, capability, error);
    }

    /** Kick off a background probe if one isn't already running. Idempotent — concurrent calls from the
     *  polling UI collapse onto the single in-flight probe. */
    public static void probe() {
        if (!UvProbe.isAvailable()) return; // snapshot() then reports UNAVAILABLE
        synchronized (LOCK) {
            if (state == State.PROBING) return;
            state = State.PROBING;
            error = null;
        }
        Thread.ofVirtual().name("imagegen-capability-probe").start(ImageCapabilityProbe::runProbe);
    }

    private static void runProbe() {
        try {
            var sidecarDir = new File(Play.applicationPath, "sidecar/image");
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
            Logger.warn(e, "imagegen capability probe failed");
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
