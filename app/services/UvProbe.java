package services;

/**
 * Detect whether {@code uv} (the Astral Python package/venv manager) is on PATH.
 * jclaw's local image and video sidecars are both launched with {@code uv run
 * serve.py}, and {@code uv} bootstraps the Python interpreter and the isolated
 * venv (torch/diffusers) — so {@code uv} is the single shared prerequisite jclaw
 * needs to verify, mirroring {@link services.transcription.FfmpegProbe}'s role for
 * ffmpeg.
 *
 * <p>Probe lazily on first call and cache the result; the binary doesn't appear
 * or disappear at runtime in any realistic single-process scenario. The Settings
 * UI reads {@link #lastResult} to surface a "uv missing" banner, and the sidecar
 * managers ({@link services.imagegen.LocalImageSidecarManager},
 * {@link services.videogen.LocalVideoSidecarManager}) read it to fail fast with a
 * clear error before attempting to spawn a daemon.
 */
public final class UvProbe {

    public record ProbeResult(boolean available, String reason) {}

    private static final ProbeCache<ProbeResult> CACHE =
            new ProbeCache<>(new ProbeResult(false, "uv probe has not run yet"));

    private UvProbe() {}

    /** Run the probe (idempotent), update cache, return result. */
    public static ProbeResult probe() {
        var r = ExecutableProbeSupport.probeOnPath("uv", "--version", "UvProbe",
                " — install uv to enable local image and video generation");
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
