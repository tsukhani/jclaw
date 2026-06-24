package services.imagegen;

import play.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detect whether {@code uv} (the Astral Python package/venv manager) is on PATH.
 * The local Flux sidecar (JCLAW-226) is launched with {@code uv run serve.py},
 * and {@code uv} bootstraps both the Python interpreter and the isolated venv
 * with torch/diffusers — so {@code uv} is the single prerequisite jclaw needs to
 * verify, mirroring {@link services.transcription.FfmpegProbe}'s role for ffmpeg.
 *
 * <p>Probe lazily on first call and cache the result; the binary doesn't appear
 * or disappear at runtime in any realistic single-process scenario. The Settings
 * UI (next story) reads {@link #lastResult} to surface a "uv missing" banner, and
 * {@link LocalFluxSidecarManager} reads it to fail fast with a clear error before
 * attempting to spawn the daemon.
 */
public final class FluxSidecarProbe {

    public record ProbeResult(boolean available, String reason) {}

    private static final ProbeResult UNRUN = new ProbeResult(false, "uv probe has not run yet");
    private static final AtomicReference<ProbeResult> result = new AtomicReference<>(UNRUN);

    private FluxSidecarProbe() {}

    /** Run the probe (idempotent), update cache, return result. */
    public static ProbeResult probe() {
        var r = doProbe();
        result.set(r);
        return r;
    }

    /** Most recent probe result; returns {@link #UNRUN} until {@link #probe} has been called. */
    public static ProbeResult lastResult() {
        return result.get();
    }

    /**
     * Cached probe — runs once on first call. Threadsafe via the AtomicReference;
     * concurrent callers may both run the probe but they return the same answer.
     */
    public static boolean isAvailable() {
        var cached = result.get();
        if (cached == UNRUN) cached = probe();
        return cached.available();
    }

    public static void setForTest(ProbeResult forced) {
        result.set(forced == null ? UNRUN : forced);
    }

    private static ProbeResult doProbe() {
        try {
            var pb = new ProcessBuilder("uv", "--version");
            pb.redirectErrorStream(true);
            var p = pb.start();
            // --version prints and exits within milliseconds; bound the wait
            // anyway so a hung binary can't stall startup forever.
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return new ProbeResult(false, "uv --version did not exit within 5s");
            }
            int code = p.exitValue();
            if (code != 0) {
                return new ProbeResult(false, "uv --version exited %d".formatted(code));
            }
            Logger.info("FluxSidecarProbe: uv available on PATH");
            return new ProbeResult(true, "available");
        } catch (IOException e) {
            return new ProbeResult(false,
                    "uv not found on PATH (" + e.getMessage() + ") — install uv to enable local image generation");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "interrupted while probing uv");
        }
    }
}
