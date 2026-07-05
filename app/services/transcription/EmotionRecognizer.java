package services.transcription;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import play.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Speech emotion recognition (JCLAW-563, model swapped in JCLAW-564):
 * classifies how each diarized turn was spoken — the prosody/affect layer
 * on top of "what was said" (whisper) and "who said it" (the diarizer). The
 * emotion2vec+ base backbone runs in-process over ONNX Runtime's Java API,
 * so the whole pipeline stays sidecar-free. Self-supervised multilingual
 * pretraining is the point of the JCLAW-564 swap: labels stay coherent on
 * non-English speech (validated on Malay/English debate audio), where the
 * previous English-only wav2vec2-base model oscillated randomly.
 *
 * <p>The ONNX graph emits 768-d frame embeddings; classification is
 * FunASR's utterance pipeline replicated in plain Java: mean-pool the
 * frames, apply the exported 9×768 linear head, softmax. The two
 * non-emotions in emotion2vec's label set ({@code other}, {@code unknown})
 * map to "no annotation" rather than a made-up label.
 *
 * <p>Lifecycle mirrors the diarizer: the ONNX session is expensive
 * to build, so one instance is cached for the JVM's lifetime and released
 * from {@link jobs.ShutdownJob}; inference serializes under
 * {@link #inferenceLock}. Input is the pipeline-wide audio shape (PCM float
 * mono 16 kHz) with emotion2vec's only preprocessing — layer-norming the
 * raw waveform — done inline.
 */
public final class EmotionRecognizer {

    /** emotion2vec's label order — index into the classifier's logit rows.
     *  Matches the export's {@code labels.txt} and FunASR's token list. */
    public static final String[] LABELS =
            {"angry", "disgusted", "fearful", "happy", "neutral", "other", "sad", "surprised", "unknown"};

    /** What each label renders as in transcripts; {@code null} = leave the
     *  turn unannotated ("other"/"unknown" carry no affect information).
     *  Adjectives match the JCLAW-563 vocabulary (disgust, fear) so the
     *  model swap doesn't churn the output format. */
    public static final String[] DISPLAY_LABELS =
            {"angry", "disgust", "fear", "happy", "neutral", null, "sad", "surprised", null};

    /** Below this a window carries too little acoustic evidence to classify. */
    public static final double MIN_SECONDS = 0.5;
    /** Truncation cap: self-attention is quadratic in sequence length, and
     *  half a minute of one turn is ample evidence for a single label. */
    public static final double MAX_SECONDS = 30.0;

    private static final int SAMPLE_RATE = 16_000;
    private static final int EMBEDDING_DIM = 768;

    /** One classified window: the display label ({@code null} when the model
     *  said other/unknown) and the winning class's softmax probability. */
    public record Emotion(String label, double confidence) {}

    /** The exported linear head: {@code logits = weights · embedding + bias}. */
    public record Classifier(float[][] weights, float[] bias) {}

    private static final Object inferenceLock = new Object();
    // Guarded by inferenceLock (single native session, concurrent tool calls).
    private static OrtSession session = null;
    private static String inputName = null;
    private static Classifier classifier = null;

    private EmotionRecognizer() {}

    /**
     * Attach an emotion label to every transcript entry long enough to
     * classify. Best-effort by contract: emotion is an annotation on the
     * diarized transcript, never a reason to lose it — any failure (model
     * download, native load, inference) logs a warning and returns the
     * entries unchanged. Blocking and CPU-bound — run off the request
     * thread. Downloads the ONNX model on first use (~356 MB).
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

    /** Best-effort with pre-decoded PCM (JCLAW-640): same skip conditions
     *  and swallow-and-warn posture as the Path wrapper. */
    public static List<DiarizedTranscript.Entry> annotateBestEffort(
            float[] pcm, List<DiarizedTranscript.Entry> entries) {
        if (entries.stream().noneMatch(e -> e.end() - e.start() >= MIN_SECONDS)) {
            return entries;
        }
        try {
            return annotate(pcm, entries);
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
            annotated.add(emotion.label() == null ? e : new DiarizedTranscript.Entry(
                    e.speaker(), e.start(), e.end(), e.text(), emotion.label(), e.crossTalk(),
                    e.underSpeech(), e.noSpeakerEvidence()));
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
                float[][][] frames = (float[][][]) results.get(0).getValue();
                return classifyEmbedding(meanPool(frames[0]), classifier);
            } catch (OrtException e) {
                throw new TranscriptionException(
                        "emotion recognition inference failed: " + e.getMessage(), e);
            }
        }
    }

    /** FunASR's utterance-level head in plain Java: linear layer over the
     *  pooled embedding, softmax, argmax → display label. */
    public static Emotion classifyEmbedding(float[] embedding, Classifier c) {
        var logits = new float[c.weights().length];
        for (int r = 0; r < logits.length; r++) {
            double sum = c.bias()[r];
            for (int i = 0; i < embedding.length; i++) {
                sum += c.weights()[r][i] * embedding[i];
            }
            logits[r] = (float) sum;
        }
        float[] probs = softmax(logits);
        int best = 0;
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > probs[best]) best = i;
        }
        return new Emotion(DISPLAY_LABELS[best], probs[best]);
    }

    /** Mean over the graph's frame axis: {@code [frames][768]} → {@code [768]}. */
    public static float[] meanPool(float[][] frames) {
        var out = new float[frames[0].length];
        for (float[] frame : frames) {
            for (int i = 0; i < out.length; i++) out[i] += frame[i] / frames.length;
        }
        return out;
    }

    /**
     * Parse the exported classifier head: little-endian
     * {@code [int32 rows][int32 cols][rows×cols float32 weights][rows float32 bias]}.
     * Rows must match {@link #LABELS} and cols the backbone's embedding width —
     * a mismatch means the two downloaded artifacts are from different exports.
     */
    public static Classifier parseClassifier(byte[] bytes) {
        var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int rows = buf.getInt();
        int cols = buf.getInt();
        if (rows != LABELS.length || cols != EMBEDDING_DIM
                || bytes.length != 8 + 4 * rows * cols + 4 * rows) {
            throw new TranscriptionException(
                    "emotion classifier has unexpected shape %dx%d (%d bytes) — expected %dx%d"
                            .formatted(rows, cols, bytes.length, LABELS.length, EMBEDDING_DIM));
        }
        var weights = new float[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) weights[r][c] = buf.getFloat();
        }
        var bias = new float[rows];
        for (int r = 0; r < rows; r++) bias[r] = buf.getFloat();
        return new Classifier(weights, bias);
    }

    /** Caller must hold {@link #inferenceLock}. */
    private static void ensureSession() {
        if (session != null) return;
        var modelPath = EmotionModelManager.ensureAvailable(
                EmotionModelManager.EmotionModel.EMBEDDING);
        var classifierPath = EmotionModelManager.ensureAvailable(
                EmotionModelManager.EmotionModel.CLASSIFIER);
        try {
            classifier = parseClassifier(Files.readAllBytes(classifierPath));
            var env = OrtEnvironment.getEnvironment();
            // JCLAW-638 EP decision: default CPU provider stays. CoreML EP
            // needs per-model op-coverage validation (wav2vec2 SER uses ops
            // CoreML historically mishandles) and the CUDA EP needs the
            // GPU onnxruntime artifact — both are a spike, not a config
            // flip. Revisit if this pass re-enters the JFR top hotspots.
            session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
            inputName = session.getInputNames().iterator().next();
            Logger.info("EmotionRecognizer: emotion2vec+ loaded from %s (input=%s)",
                    modelPath, inputName);
        } catch (java.io.IOException | OrtException | RuntimeException e) {
            classifier = null;
            throw new TranscriptionException(
                    "failed to initialise the emotion recognition model: " + e.getMessage(), e);
        } catch (@SuppressWarnings("java:S1181") UnsatisfiedLinkError e) {
            classifier = null;
            throw new TranscriptionException(
                    "onnxruntime native library unavailable on this platform — "
                            + "emotion analysis needs an onnxruntime build for this OS/arch", e);
        }
    }

    /** emotion2vec's preprocessing: layer-norm the raw waveform (FunASR's
     *  {@code F.layer_norm}, eps 1e-5). */
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
        double std = Math.sqrt(variance + 1e-5);
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
                classifier = null;
            }
        }
    }

    /** Test-only: drop the cached session so tests don't bleed. */
    public static void resetForTest() {
        shutdown();
    }
}
