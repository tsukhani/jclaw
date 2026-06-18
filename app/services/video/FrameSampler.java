package services.video;

import models.MessageAttachment;
import play.Logger;
import services.AgentService;
import services.ConfigService;
import services.transcription.FfmpegProbe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Sample evenly-spaced JPEG frames from a video attachment for the JCLAW-208
 * video-understanding pipeline. Shells out to {@code ffmpeg} (frame extraction)
 * and {@code ffprobe} (duration) exactly as
 * {@link services.transcription.WhisperJniTranscriber} does for audio — same
 * process hygiene: stderr captured to a temp file, stdout drained <em>before</em>
 * {@code waitFor} so a full pipe can't deadlock the child, a bounded timeout, and
 * {@code destroyForcibly} on a hang.
 *
 * <p>The frames feed all three interpretation tiers (JCLAW-220/221/222): Tier-1
 * hands the list to a native video model as a {@code video} content part, Tier-2
 * threads them in as individual {@code image_url} parts for a vision model, and
 * Tier-3 captions each frame and assembles a {@code [hh:mm:ss]} timeline for a
 * text-only model.
 *
 * <p>The frame <em>count</em> is duration-aware (see {@link #frameCountFor}) so a
 * 12-second clip isn't oversampled to the same N as a 10-minute one. No ffmpeg on
 * PATH ⇒ {@link FrameSamplingException}; the orchestrator above catches it and
 * degrades gracefully (the video simply carries no visual summary), mirroring how
 * a missing whisper model leaves a NULL transcript.
 */
public final class FrameSampler {

    private FrameSampler() {}

    /** One sampled frame: the JPEG bytes and the source timestamp it was taken at. */
    public record Frame(byte[] jpeg, double timestampSeconds) {}

    public static class FrameSamplingException extends RuntimeException {
        public FrameSamplingException(String message) { super(message); }
        public FrameSamplingException(String message, Throwable cause) { super(message, cause); }
    }

    // --- duration-aware frame count (JCLAW-219 / JCLAW-208) ---

    /** Density: one sampled frame per this many seconds of video. Config key; clamped to [1,60]. */
    static final String CFG_SECONDS_PER_FRAME = "video.secondsPerFrame";
    static final int DEFAULT_SECONDS_PER_FRAME = 10;
    static final int SECONDS_PER_FRAME_MIN = 1;
    static final int SECONDS_PER_FRAME_MAX = 60;

    /** Hard ceiling on sampled frames regardless of duration. Config key; clamped to [2,32]. */
    static final String CFG_SAMPLE_FRAMES = "video.sampleFrames";
    static final int DEFAULT_SAMPLE_FRAMES = 8;
    static final int SAMPLE_FRAMES_MIN = 2;
    static final int SAMPLE_FRAMES_MAX = 32;

    /** Floor: even a 1-second clip yields at least this many frames (a start and an end). */
    static final int MIN_FRAMES = 2;

    /** Duration ffprobe falls back to when it can't read the container's metadata. */
    static final double FALLBACK_DURATION_SECONDS = 60.0;

    private static final int FFMPEG_FRAME_TIMEOUT_SECONDS = 30;
    private static final int FFPROBE_TIMEOUT_SECONDS = 15;
    // mjpeg -q:v runs 2 (best) .. 31 (worst); 3 ≈ JPEG quality ~90.
    private static final String JPEG_QSCALE = "3";

    /**
     * Target frame count for a clip of the given duration:
     * {@code clamp(round(durationSeconds / secondsPerFrame), MIN_FRAMES, sampleFramesCeiling)}.
     * A 12 s clip at the default 10 s/frame rounds to 1 → floored to {@value #MIN_FRAMES};
     * a 10-minute clip rounds to 60 → capped at the (default 8) ceiling. A non-positive
     * duration (ffprobe gave nothing useful) collapses to the floor.
     */
    public static int frameCountFor(double durationSeconds) {
        if (durationSeconds <= 0) return MIN_FRAMES;
        long raw = Math.round(durationSeconds / secondsPerFrame());
        return Math.clamp(raw, MIN_FRAMES, sampleFramesCeiling());
    }

    static int secondsPerFrame() {
        return Math.clamp(
                ConfigService.getInt(CFG_SECONDS_PER_FRAME, DEFAULT_SECONDS_PER_FRAME),
                SECONDS_PER_FRAME_MIN, SECONDS_PER_FRAME_MAX);
    }

    static int sampleFramesCeiling() {
        return Math.clamp(
                ConfigService.getInt(CFG_SAMPLE_FRAMES, DEFAULT_SAMPLE_FRAMES),
                SAMPLE_FRAMES_MIN, SAMPLE_FRAMES_MAX);
    }

    // --- sampling ---

    /**
     * Probe the attachment's duration, compute the duration-aware frame count, and
     * extract that many evenly-spaced frames. The high-level entry point the video
     * dispatcher (JCLAW-224) calls; resolves the on-disk path the same way the
     * transcription pipeline does ({@code workspaceRoot().resolve(storagePath)}).
     */
    public static List<Frame> sample(MessageAttachment video) {
        if (video == null) throw new FrameSamplingException("attachment is null");
        requireFfmpeg();
        var path = AgentService.workspaceRoot().resolve(video.storagePath);
        if (!Files.isReadable(path)) {
            throw new FrameSamplingException("video file is not readable: " + path);
        }
        double duration = probeDurationSeconds(path);
        return sampleFrames(path, duration, frameCountFor(duration));
    }

    /**
     * Extract {@code frameCount} JPEG frames at timestamps centered in evenly-sized
     * windows across {@code [0, duration)} — i.e. {@code t_i = duration*(i+0.5)/N}.
     * Centering skips the leading title card and trailing credits/black that bookend
     * many clips, sampling a representative mid-window frame from each segment instead.
     */
    static List<Frame> sampleFrames(Path videoFile, double duration, int frameCount) {
        var frames = new ArrayList<Frame>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            double ts = duration * (i + 0.5) / frameCount;
            frames.add(new Frame(extractFrame(videoFile, ts), ts));
        }
        return frames;
    }

    /** One fast input-seek to {@code timestampSeconds}, one frame out as JPEG bytes. */
    static byte[] extractFrame(Path videoFile, double timestampSeconds) {
        try {
            var stderrFile = Files.createTempFile("ffmpeg-frame-stderr", ".log");
            try {
                var pb = new ProcessBuilder(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel", "error",
                        "-ss", formatSeconds(timestampSeconds), // fast input seek (before -i)
                        "-i", videoFile.toString(),
                        "-frames:v", "1",
                        "-q:v", JPEG_QSCALE,
                        "-f", "mjpeg",
                        "-");
                pb.redirectError(stderrFile.toFile());
                var proc = pb.start();

                // Drain stdout fully BEFORE waitFor so a large frame can't backpressure
                // ffmpeg into hanging on a full stdout pipe (mirrors WhisperJniTranscriber).
                byte[] jpeg;
                try (InputStream stdout = proc.getInputStream()) {
                    jpeg = stdout.readAllBytes();
                }

                boolean exited = proc.waitFor(FFMPEG_FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!exited) {
                    proc.destroyForcibly();
                    throw new FrameSamplingException(
                            "ffmpeg frame extract at %.3fs did not finish within %ds"
                                    .formatted(timestampSeconds, FFMPEG_FRAME_TIMEOUT_SECONDS));
                }
                int rc = proc.exitValue();
                if (rc != 0) {
                    var stderr = Files.readString(stderrFile).trim();
                    throw new FrameSamplingException(
                            "ffmpeg exited %d extracting frame at %.3fs: %s"
                                    .formatted(rc, timestampSeconds, stderr.isEmpty() ? "(no stderr)" : stderr));
                }
                if (jpeg.length == 0) {
                    throw new FrameSamplingException(
                            "ffmpeg produced no frame at %.3fs (seek past end?)".formatted(timestampSeconds));
                }
                return jpeg;
            } finally {
                Files.deleteIfExists(stderrFile);
            }
        } catch (IOException e) {
            throw new FrameSamplingException("ffmpeg frame extraction failed", e);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new FrameSamplingException("interrupted while extracting frame");
        }
    }

    /**
     * Read the container duration in seconds via {@code ffprobe}. Returns
     * {@link #FALLBACK_DURATION_SECONDS} when ffprobe is absent, errors, or emits an
     * unparseable value — the sampler still produces a (fixed-count) result rather
     * than failing the whole interpretation on a missing duration tag.
     */
    static double probeDurationSeconds(Path videoFile) {
        try {
            var stderrFile = Files.createTempFile("ffprobe-stderr", ".log");
            try {
                var pb = new ProcessBuilder(
                        "ffprobe",
                        "-v", "error",
                        "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1:nokey=1",
                        videoFile.toString());
                pb.redirectError(stderrFile.toFile());
                var proc = pb.start();

                String out;
                try (InputStream stdout = proc.getInputStream()) {
                    out = new String(stdout.readAllBytes()).trim();
                }

                boolean exited = proc.waitFor(FFPROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!exited) {
                    proc.destroyForcibly();
                    Logger.warn("FrameSampler: ffprobe timed out; using fallback duration " + FALLBACK_DURATION_SECONDS + "s");
                    return FALLBACK_DURATION_SECONDS;
                }
                int rc = proc.exitValue();
                if (rc != 0 || out.isEmpty() || "N/A".equalsIgnoreCase(out)) {
                    Logger.warn("FrameSampler: ffprobe gave no usable duration (rc=" + rc + ", out='" + out
                            + "'); using fallback " + FALLBACK_DURATION_SECONDS + "s");
                    return FALLBACK_DURATION_SECONDS;
                }
                return Double.parseDouble(out);
            } finally {
                Files.deleteIfExists(stderrFile);
            }
        } catch (NumberFormatException e) {
            Logger.warn("FrameSampler: unparseable ffprobe duration; using fallback " + FALLBACK_DURATION_SECONDS + "s");
            return FALLBACK_DURATION_SECONDS;
        } catch (IOException e) {
            Logger.warn("FrameSampler: ffprobe invocation failed (" + e.getMessage()
                    + "); using fallback " + FALLBACK_DURATION_SECONDS + "s");
            return FALLBACK_DURATION_SECONDS;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return FALLBACK_DURATION_SECONDS;
        }
    }

    private static void requireFfmpeg() {
        if (!FfmpegProbe.isAvailable()) {
            throw new FrameSamplingException(
                    "ffmpeg is not available on PATH: " + FfmpegProbe.lastResult().reason());
        }
    }

    /** ffmpeg {@code -ss} takes plain seconds with a decimal fraction; force a dot separator. */
    static String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}
