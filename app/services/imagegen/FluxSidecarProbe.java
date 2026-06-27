package services.imagegen;

import services.ExecutableProbeSupport;
import services.ProbeCache;

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

    private static final ProbeCache<ProbeResult> CACHE =
            new ProbeCache<>(new ProbeResult(false, "uv probe has not run yet"));

    private FluxSidecarProbe() {}

    /** Run the probe (idempotent), update cache, return result. */
    public static ProbeResult probe() {
        var r = ExecutableProbeSupport.probeOnPath("uv", "--version", "FluxSidecarProbe",
                " — install uv to enable local image generation");
        return CACHE.set(new ProbeResult(r.available(), r.reason()));
    }

    /** Most recent probe result; returns the unrun sentinel until {@link #probe} has been called. */
    public static ProbeResult lastResult() {
        return CACHE.get();
    }

    /**
     * Cached probe — runs once on first call. Threadsafe via the cache;
     * concurrent callers may both run the probe but they return the same answer.
     */
    public static boolean isAvailable() {
        var cached = CACHE.get();
        if (CACHE.isUnrun(cached)) cached = probe();
        return cached.available();
    }

    public static void setForTest(ProbeResult forced) {
        CACHE.setForTest(forced);
    }
}
