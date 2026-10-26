package services.transcription;

import services.ExecutableProbeSupport;
import services.ProbeCache;

/**
 * Detect whether {@code ffmpeg} is on PATH. Video frame sampling and
 * voice-note encoding shell out to it.
 *
 * <p>Probe lazily on first call and cache the result; the binary doesn't
 * appear or disappear at runtime in any realistic single-process scenario.
 * The Settings UI (separate story) reads {@link #lastResult} to surface a
 * "ffmpeg missing" banner; {@code FrameSampler} and {@code VoiceNoteEncoder}
 * read it before spawning the process.
 */
public final class FfmpegProbe {

    public record ProbeResult(boolean available, String reason) {}

    private static final ProbeCache<ProbeResult> CACHE =
            new ProbeCache<>(new ProbeResult(false, "ffmpeg probe has not run yet"));

    private FfmpegProbe() {}

    /** Run the probe (idempotent), update cache, return result. */
    public static ProbeResult probe() {
        var r = ExecutableProbeSupport.probeOnPath("ffmpeg", "-version", "FfmpegProbe", "");
        return CACHE.set(new ProbeResult(r.available(), r.reason()));
    }

    /** Most recent probe result; returns the unrun sentinel until {@link #probe} has been called. */
    public static ProbeResult lastResult() {
        return CACHE.get();
    }

    /**
     * Cached probe — runs once on first call. Threadsafe via the cache;
     * concurrent callers may both run the probe but the winner's result wins,
     * which is fine because both probes return the same answer.
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
