package services.transcription;

import services.ExecutableProbeSupport;
import services.ProbeCache;

/**
 * Detect whether {@code ffmpeg} is on PATH. JCLAW-163's transcription pipeline
 * shells out to ffmpeg to coerce arbitrary audio containers into PCM float32
 * 16 kHz mono before handing samples to whisper-jni — without ffmpeg the
 * transcriber can't accept anything but raw 16 kHz PCM input.
 *
 * <p>Probe lazily on first call and cache the result; the binary doesn't
 * appear or disappear at runtime in any realistic single-process scenario.
 * The Settings UI (separate story) reads {@link #lastResult} to surface a
 * "ffmpeg missing" banner; {@link WhisperJniTranscriber} reads it to throw
 * a clear error before attempting to spawn the process.
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
