package services.transcription;

import play.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The ASR facade (JCLAW-650): every transcription goes through the sidecar's
 * host-relevant GPU engine — mlx-whisper on Apple silicon, faster-whisper on
 * CUDA/CPU hosts — same Whisper weights everywhere. The in-process
 * whisper.cpp JNI engine this class was named for is retired (614 pattern:
 * one best implementation, actionable error when its prerequisite — just
 * {@code uv}; whisper weights are ungated — is missing). The name survives
 * because {@link Segment} and {@link #ffmpegToPcmF32} are load-bearing
 * vocabulary across the pipeline and tests, the same call as
 * {@link DiarizationRouter}.
 *
 * <p>ffmpeg integration: the DIARIZATION pipeline wants raw PCM float32 at
 * 16 kHz mono ({@link #ffmpegToPcmF32}); the ASR sidecar reads the audio
 * file directly and needs no ffmpeg.
 */
public final class WhisperJniTranscriber {

    /** Hard ceiling on ffmpeg conversion. PCM float32 16 kHz mono is roughly
     *  64 KB/sec; ten minutes of audio takes a fraction of a second to
     *  convert on any modern CPU. Five-minute timeout is a defensive ceiling
     *  for the pathological case where ffmpeg hangs on a malformed input. */
    private static final long FFMPEG_TIMEOUT_SECONDS = 300;

    private WhisperJniTranscriber() {}

    /**
     * One recognised segment with whisper.cpp's native timestamps converted
     * to milliseconds ({@code whisper_full_get_segment_t0/t1} report
     * centiseconds; we multiply by 10 so callers never see the odd unit).
     */
    /** One transcribed word with its own clock (JCLAW-651 round 2): the
     *  engines' word timestamps replace the fragile per-segment clock that
     *  misplaced interjection words in echo. */
    public record Word(long startMs, long endMs, String text) {}

    /** JCLAW-635: the confidence triple (whisper's standard hallucination
     *  gates) rides on every segment; legacy callers get neutral defaults
     *  and no words via the compat constructors. */
    public record Segment(long startMs, long endMs, String text,
                          double noSpeechProb, double avgLogprob, double compressionRatio,
                          List<Word> words) {
        public Segment(long startMs, long endMs, String text) {
            this(startMs, endMs, text, 0.0, 0.0, 1.0, List.of());
        }

        public Segment(long startMs, long endMs, String text,
                       double noSpeechProb, double avgLogprob, double compressionRatio) {
            this(startMs, endMs, text, noSpeechProb, avgLogprob, compressionRatio, List.of());
        }
    }

    /**
     * Transcribe an audio file using the named whisper model. Blocks the
     * caller; long-running and CPU-bound. Caller is responsible for running
     * this off the request thread (via a virtual thread or job).
     *
     * <p>Weights download on first use in the sidecar (or ahead of time via
     * the Settings page, which provisions the host engine's artifact).
     */
    public static String transcribe(Path audioFile, WhisperModel model) {
        var sb = new StringBuilder();
        for (var segment : transcribeSegments(audioFile, model, null)) {
            sb.append(segment.text());
        }
        return sb.toString().trim();
    }

    /**
     * Segment-level transcription for the diarization pipeline (JCLAW-556).
     * Same blocking/preconditions contract as {@link #transcribe}; returns
     * one {@link Segment} per whisper.cpp segment, in order.
     *
     * <p>{@code language}: an ISO 639-1 code ({@code "en"}, {@code "ms"}, …)
     * forces that language. Null/blank means auto-detect on multilingual
     * models and whisper-jni's {@code "en"} default on English-only models
     * (whisper.cpp rejects detection on {@code .en} models).
     */
    public static List<Segment> transcribeSegments(Path audioFile, WhisperModel model, String language) {
        // JCLAW-650: sidecar-or-error, the JCLAW-614 pattern. The engine is
        // host-relevant (mlx-whisper on Apple silicon, faster-whisper on
        // CUDA/CPU) and its only prerequisite is uv — whisper weights are
        // NOT gated, unlike the diarization model.
        if (!services.UvProbe.isAvailable()) {
            throw new TranscriptionException(
                    "transcription requires the 'uv' launcher on PATH (it runs the GPU ASR sidecar; "
                            + "./jclaw.sh setup installs it): " + services.UvProbe.lastResult().reason());
        }
        return new PyannoteDiarizationClient().transcribe(audioFile, model.id(), language);
    }

    /** Decode ceiling: 2 hours of 16kHz mono floats is ~440MB alone, and
     *  the overlap pipeline multiplies that several-fold. */
    static final int MAX_DECODE_SECONDS = 2 * 60 * 60;

    static float[] ffmpegToPcmF32(Path audioFile) {
        try {
            var stderrFile = Files.createTempFile("ffmpeg-stderr", ".log");
            try {
                var pb = new ProcessBuilder(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel", "error",
                        "-i", audioFile.toString(),
                        "-f", "f32le",
                        "-acodec", "pcm_f32le",
                        "-ar", "16000",
                        "-ac", "1",
                        "-");
                pb.redirectError(stderrFile.toFile());
                var proc = pb.start();

                // Drain stdout fully BEFORE waitFor so a large output can't
                // backpressure ffmpeg into hanging on a full stdout pipe.
                byte[] pcm;
                try (InputStream stdout = proc.getInputStream()) {
                    pcm = stdout.readAllBytes();
                }

                boolean exited = proc.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!exited) {
                    proc.destroyForcibly();
                    throw new TranscriptionException(
                            "ffmpeg did not finish within %d seconds".formatted(FFMPEG_TIMEOUT_SECONDS));
                }
                int rc = proc.exitValue();
                if (rc != 0) {
                    var stderr = Files.readString(stderrFile).trim();
                    throw new TranscriptionException(
                            "ffmpeg exited %d: %s".formatted(rc, stderr.isEmpty() ? "(no stderr)" : stderr));
                }

                int sampleCount = pcm.length / 4;
                // JCLAW-626: the diarization pipeline holds several copies of
                // this array at once (mixed PCM, separation windows, stems, a
                // staged WAV for MSDD) — a multi-hour upload would OOM the
                // JVM opaquely. A clear ceiling beats a heap dump.
                if (sampleCount > MAX_DECODE_SECONDS * 16_000) {
                    throw new TranscriptionException(
                            "Audio is longer than the %d-minute processing ceiling (%.0f minutes) — "
                                    + "split the recording and process the parts separately."
                                    .formatted(MAX_DECODE_SECONDS / 60,
                                            sampleCount / 16_000.0 / 60.0));
                }
                var samples = new float[sampleCount];
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
                return samples;
            } finally {
                Files.deleteIfExists(stderrFile);
            }
        } catch (IOException e) {
            throw new TranscriptionException("ffmpeg invocation failed", e);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new TranscriptionException("interrupted while running ffmpeg");
        }
    }

}
