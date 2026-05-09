package services.transcription;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import play.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Whisper.cpp JNI wrapper for offline audio transcription. The
 * {@link WhisperJNI} runtime and its native libs are loaded once per JVM,
 * and a single {@link WhisperContext} is held for the active model — both
 * are expensive to construct and stable across requests, so reusing them
 * is the difference between sub-second and multi-second per-call latency.
 *
 * <p>Threading: whisper.cpp's {@code whisper_full} mutates the context, so
 * {@link #transcribe} serializes inference under {@link #inferenceLock}.
 * Concurrent callers queue. Acceptable because the typical jclaw
 * transcription workload is one voice message at a time per user.
 *
 * <p>ffmpeg integration: whisper.cpp wants raw PCM float32 little-endian at
 * 16 kHz mono. We shell out to ffmpeg to coerce arbitrary container/codec
 * input into that exact shape and pipe stdout straight into a {@code float[]}.
 * If ffmpeg isn't on PATH we throw {@link TranscriptionException} with a
 * clear "install ffmpeg" message; {@link FfmpegProbe} caches that state
 * for the Settings UI to read.
 */
public final class WhisperJniTranscriber {

    /** Hard ceiling on ffmpeg conversion. PCM float32 16 kHz mono is roughly
     *  64 KB/sec; ten minutes of audio takes a fraction of a second to
     *  convert on any modern CPU. Five-minute timeout is a defensive ceiling
     *  for the pathological case where ffmpeg hangs on a malformed input. */
    private static final long FFMPEG_TIMEOUT_SECONDS = 300;

    private static final Object inferenceLock = new Object();
    // All access to these fields is guarded by inferenceLock — the
    // synchronized blocks already provide the happens-before ordering
    // volatile would give, plus mutual exclusion, so volatile would be
    // redundant.
    private static WhisperJNI jni = null;
    private static WhisperContext activeContext = null;
    private static WhisperModel activeModel = null;

    private WhisperJniTranscriber() {}

    /**
     * Transcribe an audio file using the named whisper model. Blocks the
     * caller; long-running and CPU-bound. Caller is responsible for running
     * this off the request thread (via a virtual thread or job).
     *
     * <p>Model file must already be on disk — the writer (JCLAW-165) is
     * expected to call {@link WhisperModelManager#ensureAvailable} first.
     * If not present, throws so the caller can surface a clear "model not
     * downloaded" error.
     */
    public static String transcribe(Path audioFile, WhisperModel model) {
        if (!FfmpegProbe.isAvailable()) {
            throw new TranscriptionException(
                    "ffmpeg is not available on PATH — install ffmpeg to enable local transcription");
        }
        if (!WhisperModelManager.availableLocally(model)) {
            throw new TranscriptionException(
                    "Whisper model %s is not downloaded — call WhisperModelManager.ensureAvailable first"
                            .formatted(model.id()));
        }

        float[] samples = ffmpegToPcmF32(audioFile);

        synchronized (inferenceLock) {
            ensureContextLoaded(model);
            var params = new WhisperFullParams();
            int rc = jni.full(activeContext, params, samples, samples.length);
            if (rc != 0) {
                throw new TranscriptionException("whisper_full returned non-zero status %d".formatted(rc));
            }
            int segCount = jni.fullNSegments(activeContext);
            var sb = new StringBuilder();
            for (int i = 0; i < segCount; i++) {
                sb.append(jni.fullGetSegmentText(activeContext, i));
            }
            return sb.toString().trim();
        }
    }

    /** Free the active native context on JVM shutdown. Wired from {@link jobs.ShutdownJob}. */
    public static void shutdown() {
        synchronized (inferenceLock) {
            if (activeContext != null) {
                try {
                    activeContext.close();
                } catch (Throwable t) {
                    Logger.warn(t, "WhisperJniTranscriber: error closing active context");
                }
                activeContext = null;
                activeModel = null;
            }
        }
    }

    /**
     * Lazy-load the JNI runtime, then ensure the active context matches the
     * requested model. Caller must hold {@link #inferenceLock}.
     */
    private static void ensureContextLoaded(WhisperModel model) {
        if (jni == null) {
            try {
                WhisperJNI.loadLibrary();
            } catch (IOException e) {
                throw new TranscriptionException("failed to load whisper-jni native libraries", e);
            }
            jni = new WhisperJNI();
        }
        if (activeModel == model && activeContext != null) return;

        // Model swap — close the old context first to free native memory
        // before allocating the new one. whisper.cpp keeps the model weights
        // in the context's RAM, so without this we'd hold both during the
        // swap (potentially 1+ GB on medium models).
        if (activeContext != null) {
            try { activeContext.close(); } catch (Throwable ignored) {}
            activeContext = null;
            activeModel = null;
        }

        var modelPath = WhisperModelManager.localPath(model);
        try {
            activeContext = jni.init(modelPath);
            activeModel = model;
            Logger.info("WhisperJniTranscriber: loaded model %s from %s", model.id(), modelPath);
        } catch (IOException e) {
            throw new TranscriptionException(
                    "failed to load whisper model %s from %s".formatted(model.id(), modelPath), e);
        }
    }

    /**
     * Spawn ffmpeg, decode the input to PCM float32 little-endian at 16 kHz
     * mono, return the samples. Stderr is captured separately so it can be
     * surfaced in error messages without polluting the sample stream.
     */
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
                var samples = new float[sampleCount];
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
                return samples;
            } finally {
                Files.deleteIfExists(stderrFile);
            }
        } catch (IOException e) {
            throw new TranscriptionException("ffmpeg invocation failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TranscriptionException("interrupted while running ffmpeg");
        }
    }

    /** Test-only: drop static state so tests don't bleed. */
    public static void resetForTest() {
        synchronized (inferenceLock) {
            if (activeContext != null) {
                try { activeContext.close(); } catch (Throwable ignored) {}
            }
            jni = null;
            activeContext = null;
            activeModel = null;
        }
    }
}
