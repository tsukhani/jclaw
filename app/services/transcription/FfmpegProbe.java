package services.transcription;

import play.Logger;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

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

    private static final ProbeResult UNRUN = new ProbeResult(false, "ffmpeg probe has not run yet");
    private static final AtomicReference<ProbeResult> result = new AtomicReference<>(UNRUN);

    private FfmpegProbe() {}

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
     * Cached probe — runs once on first call. Threadsafe via the
     * AtomicReference; concurrent callers may both run the probe but the
     * winner's result wins, which is fine because both probes return the
     * same answer.
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
            var pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            var p = pb.start();
            // -version prints to stdout and exits within milliseconds; bound
            // the wait anyway so a hung binary can't stall startup forever.
            boolean exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return new ProbeResult(false, "ffmpeg -version did not exit within 5s");
            }
            int code = p.exitValue();
            if (code != 0) {
                return new ProbeResult(false, "ffmpeg -version exited %d".formatted(code));
            }
            Logger.info("FfmpegProbe: ffmpeg available on PATH");
            return new ProbeResult(true, "available");
        } catch (IOException e) {
            return new ProbeResult(false, "ffmpeg not found on PATH (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "interrupted while probing ffmpeg");
        }
    }
}
