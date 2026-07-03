package services.transcription;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import play.Logger;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Speech emotion recognition (JCLAW-563): classifies how each diarized turn
 * was spoken — the prosody/affect layer the ticket adds on top of "what was
 * said" (whisper) and "who said it" (sherpa). A wav2vec2-base SER fine-tune
 * runs in-process over ONNX Runtime's Java API, so the whole pipeline stays
 * sidecar-free; the model reads acoustics (tone, pitch, energy), not words,
 * so it degrades gracefully on non-English speech (trained on English —
 * treat labels on e.g. Malay turns as indicative, not authoritative).
 *
 * <p>Lifecycle mirrors {@link SherpaDiarizer}: the ONNX session is expensive
 * to build, so one instance is cached for the JVM's lifetime and released
 * from {@link jobs.ShutdownJob}; inference serializes under
 * {@link #inferenceLock}. Input is the pipeline-wide audio shape (PCM float
 * mono 16 kHz) with the model's only preprocessing — per-window zero-mean /
 * unit-variance normalization — done inline.
 */
public final class EmotionRecognizer {

    /** id2label order of the ONNX export — index into the logits vector. */
    public static final String[] LABELS = {"sad", "angry", "disgust", "fear", "happy", "neutral"};

    /** Below this a window carries too little acoustic evidence to classify. */
    public static final double MIN_SECONDS = 0.5;
    /** Truncation cap: wav2vec2 attention is quadratic in sequence length, and
     *  half a minute of one turn is ample evidence for a single label. */
    public static final double MAX_SECONDS = 30.0;

    private static final int SAMPLE_RATE = 16_000;

    /** One classified window: the winning label and its softmax probability. */
    public record Emotion(String label, double confidence) {}

    private static final Object inferenceLock = new Object();
    // Guarded by inferenceLock, same argument as SherpaDiarizer's fields.
    private static OrtSession session = null;
    private static String inputName = null;

    private EmotionRecognizer() {}

    /**
     * Attach an emotion label to every transcript entry long enough to
     * classify. Best-effort by contract: emotion is an annotation on the
     * diarized transcript, never a reason to lose it — any failure (model
     * download, native load, inference) logs a warning and returns the
     * entries unchanged. Blocking and CPU-bound — run off the request
     * thread. Downloads the ONNX model on first use (~95 MB).
     */
    public static List<DiarizedTranscript.Entry> annotate(
            Path audioFile, List<DiarizedTranscript.Entry> entries) {
        if (entries.stream().noneMatch(e -> e.end() - e.start() >= MIN_SECONDS)) {
            return entries;
        }
        try {
            return annotate(WhisperJniTranscriber.ffmpegToPcmF32(audioFile), entries);
        } catch (RuntimeException e) {
            Logger.warn("EmotionRecognizer: emotion analysis skipped: %s", e.getMessage());
            return entries;
        }
    }

    /** Throwing core of {@link #annotate(Path, List)}; public (like
     *  WhisperJniTranscriber.applyLanguage) so tests reach it from the
     *  default package. */
    public static List<DiarizedTranscript.Entry> annotate(
            float[] samples, List<DiarizedTranscript.Entry> entries) {
        var annotated = new ArrayList<DiarizedTranscript.Entry>(entries.size());
        for (var e : entries) {
            var w = window(e.start(), e.end(), samples.length);
            if (w == null) {
                annotated.add(e);
                continue;
            }
            var emotion = classify(samples, w[0], w[1]);
            annotated.add(new DiarizedTranscript.Entry(
                    e.speaker(), e.start(), e.end(), e.text(), emotion.label()));
        }
        return annotated;
    }

    /**
     * Sample window for an entry spanning {@code [start, end)} seconds:
     * clamped into the decoded audio, truncated to {@value #MAX_SECONDS} s,
     * or {@code null} when less than {@value #MIN_SECONDS} s of audio remains
     * (diarized times can overrun the decoded audio — container duration
     * lies, same caveat as {@link SpeakerClipExtractor}).
     */
    public static int[] window(double start, double end, int totalSamples) {
        int from = (int) Math.clamp(Math.round(start * SAMPLE_RATE), 0, totalSamples);
        int to = (int) Math.clamp(Math.round(Math.min(end, start + MAX_SECONDS) * SAMPLE_RATE),
                0, totalSamples);
        if (to - from < MIN_SECONDS * SAMPLE_RATE) return null;
        return new int[]{from, to};
    }

    /** Classify one window of the pipeline-wide PCM float 16 kHz audio. */
    public static Emotion classify(float[] samples, int from, int to) {
        float[] normalized = normalize(samples, from, to);
        synchronized (inferenceLock) {
            ensureSession();
            try (var tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
                         FloatBuffer.wrap(normalized), new long[]{1, normalized.length});
                 var results = session.run(Map.of(inputName, tensor))) {
                float[][] logits = (float[][]) results.get(0).getValue();
                float[] probs = softmax(logits[0]);
                int best = 0;
                for (int i = 1; i < probs.length; i++) {
                    if (probs[i] > probs[best]) best = i;
                }
                return new Emotion(LABELS[Math.min(best, LABELS.length - 1)], probs[best]);
            } catch (OrtException e) {
                throw new TranscriptionException(
                        "emotion recognition inference failed: " + e.getMessage(), e);
            }
        }
    }

    /** Caller must hold {@link #inferenceLock}. */
    private static void ensureSession() {
        if (session != null) return;
        var modelPath = EmotionModelManager.ensureAvailable();
        try {
            var env = OrtEnvironment.getEnvironment();
            session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
            inputName = session.getInputNames().iterator().next();
            Logger.info("EmotionRecognizer: model loaded from %s (input=%s)", modelPath, inputName);
        } catch (OrtException | RuntimeException e) {
            throw new TranscriptionException(
                    "failed to initialise the emotion recognition model: " + e.getMessage(), e);
        } catch (@SuppressWarnings("java:S1181") UnsatisfiedLinkError e) {
            throw new TranscriptionException(
                    "onnxruntime native library unavailable on this platform — "
                            + "emotion analysis needs an onnxruntime build for this OS/arch", e);
        }
    }

    /** Wav2Vec2FeatureExtractor's zero-mean / unit-variance normalization
     *  ({@code do_normalize: true}), epsilon matching HF's 1e-7. */
    public static float[] normalize(float[] samples, int from, int to) {
        int n = to - from;
        double mean = 0;
        for (int i = from; i < to; i++) mean += samples[i];
        mean /= n;
        double variance = 0;
        for (int i = from; i < to; i++) {
            double d = samples[i] - mean;
            variance += d * d;
        }
        variance /= n;
        double std = Math.sqrt(variance + 1e-7);
        var out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) ((samples[from + i] - mean) / std);
        }
        return out;
    }

    public static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float l : logits) max = Math.max(max, l);
        double sum = 0;
        var out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = (float) Math.exp(logits[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < out.length; i++) out[i] /= (float) sum;
        return out;
    }

    /** Free the ONNX session on JVM shutdown. Wired from {@link jobs.ShutdownJob}. */
    public static void shutdown() {
        synchronized (inferenceLock) {
            if (session != null) {
                try {
                    session.close();
                } catch (@SuppressWarnings("java:S1181") Throwable t) {
                    // Native close can surface native errors; shutdown must continue
                    Logger.warn(t, "EmotionRecognizer: error releasing session");
                }
                session = null;
                inputName = null;
            }
        }
    }

    /** Test-only: drop the cached session so tests don't bleed. */
    public static void resetForTest() {
        shutdown();
    }
}
