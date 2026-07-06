package services.transcription;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import play.Logger;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-643 alignment-model decision: this stays the LibriSpeech English
 * wav2vec2-base-960h char-CTC checkpoint, DELIBERATELY, for now. The
 * deployment is English/Malay code-switched; Malay is Latin-script and
 * phonetically regular, so char-level CTC alignment degrades gracefully
 * (approximate boundaries, never failures). The measured instrument for
 * revisiting is eval.py's cpWER mode against the committed gold transcript
 * (sidecar/diarize/eval/gold/haram-debate-transcript.json): if boundary
 * cuts start mis-attributing words at speaker changes, cpWER shows it.
 * The upgrade path is an MMS-style multilingual char-CTC checkpoint with
 * the same interface — swap MODEL_URL, re-measure cpWER, done.
 *
 * CTC forced alignment (JCLAW-603, the WhisperX technique in-process):
 * given audio and the text whisper already produced for it, find when each
 * word was spoken. Runs the wav2vec2-base-960h character-CTC model over the
 * segment's audio via ONNX Runtime, then Viterbi-aligns the known character
 * sequence against the CTC log-probabilities — a constrained shortest path,
 * exact and deterministic, no decoding involved.
 *
 * <p>The character vocabulary of the export is fixed (A-Z, apostrophe,
 * {@code |} as the word separator, {@code <pad>}=0 as the CTC blank), so it
 * is hardcoded here rather than downloaded. Frames stride 20ms (320 samples
 * at 16kHz).
 *
 * <p>Lifecycle mirrors {@link EmotionRecognizer}: one cached ONNX session
 * per JVM, inference serialized under {@link #inferenceLock}, released from
 * {@link jobs.ShutdownJob}. Throws {@link TranscriptionException} on any
 * failure — {@link SegmentWordSplitter} treats that as "don't split".
 */
public final class CtcForcedAligner {

    /** vocab.json of the export, inverted: index = CTC class id. */
    private static final int BLANK = 0;
    private static final int WORD_SEP = 4;
    private static final String CHARS = "'ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int[] CHAR_IDS = {
            27, 7, 24, 19, 14, 5, 20, 21, 11, 10, 29, 26, 15, 17, 9, 8, 23, 30, 13, 12, 6, 16, 25, 18, 28, 22, 31};

    /** Model frame stride: 320 samples at 16 kHz = 20 ms per output frame. */
    private static final double FRAME_SECONDS = 0.02;
    private static final int SAMPLE_RATE = 16_000;

    private static final Object inferenceLock = new Object();
    // Guarded by inferenceLock, same argument as EmotionRecognizer's fields.
    private static OrtSession session = null;
    private static String inputName = null;

    private CtcForcedAligner() {}

    /**
     * Align {@code normalizedWords} (already uppercased, A-Z/apostrophe only,
     * non-empty — see {@link #normalizeWord}) against the audio window
     * {@code [from, to)} of the pipeline-wide PCM float 16 kHz samples.
     * Returns one {@code [startSeconds, endSeconds]} pair per word, absolute
     * (window offset included).
     */
    /**
     * Stamp every segment's words with CTC-aligned times (JCLAW-651 r2):
     * whisper's text within a modestly padded window of its own segment —
     * the pad lets words escape the segment-clock lie in echo, while the
     * acoustic Viterbi anchor keeps them honest. Returns null on any
     * alignment failure so the caller takes the legacy path.
     */
    public static List<WhisperJniTranscriber.Segment> stampTranscript(
            List<WhisperJniTranscriber.Segment> transcript, float[] pcm) {
        try {
            var out = new java.util.ArrayList<WhisperJniTranscriber.Segment>(transcript.size());
            for (var seg : transcript) {
                var originals = new java.util.ArrayList<String>();
                var normalized = new java.util.ArrayList<String>();
                for (var w : seg.text().split("\\s+")) {
                    var n = normalizeWord(w);
                    if (!n.isEmpty()) {
                        originals.add(w);
                        normalized.add(n);
                    }
                }
                if (normalized.isEmpty()) {
                    out.add(seg);
                    continue;
                }
                int from = (int) Math.clamp(
                        Math.round((seg.startMs() / 1000.0 - 0.3) * 16_000), 0, pcm.length);
                int to = (int) Math.clamp(
                        Math.round((seg.endMs() / 1000.0 + 1.2) * 16_000), from, pcm.length);
                var times = alignWords(pcm, from, to, normalized);
                var words = new java.util.ArrayList<WhisperJniTranscriber.Word>(originals.size());
                for (int i = 0; i < originals.size(); i++) {
                    words.add(new WhisperJniTranscriber.Word(
                            Math.round(times.get(i)[0] * 1000),
                            Math.round(times.get(i)[1] * 1000), originals.get(i)));
                }
                out.add(new WhisperJniTranscriber.Segment(seg.startMs(), seg.endMs(), seg.text(),
                        seg.noSpeechProb(), seg.avgLogprob(), seg.compressionRatio(), words));
            }
            return out;
        } catch (RuntimeException e) {
            play.Logger.warn("CtcForcedAligner: transcript stamping failed (%s) — legacy path",
                    e.getMessage());
            return null;
        }
    }

    public static List<double[]> alignWords(float[] samples, int from, int to,
                                            List<String> normalizedWords) {
        if (normalizedWords.isEmpty()) {
            throw new TranscriptionException("nothing to align: no words");
        }
        int[] tokens = tokenize(normalizedWords);

        float[][] logProbs;
        synchronized (inferenceLock) {
            ensureSession();
            var normalized = EmotionRecognizer.normalize(samples, from, to);
            try (var tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
                         FloatBuffer.wrap(normalized), new long[]{1, normalized.length});
                 var results = session.run(Map.of(inputName, tensor))) {
                float[][][] logits = (float[][][]) results.get(0).getValue();
                logProbs = logSoftmax(logits[0]);
            } catch (OrtException e) {
                throw new TranscriptionException(
                        "word-alignment inference failed: " + e.getMessage(), e);
            }
        }

        int[][] tokenSpans = trellisAlign(logProbs, tokens);
        return toWordTimes(tokenSpans, tokens, normalizedWords.size(), from / (double) SAMPLE_RATE);
    }

    /**
     * Uppercase and strip a word down to the aligner's alphabet. Returns an
     * empty string for words with no alignable characters (pure punctuation,
     * digits, emoji) — {@link SegmentWordSplitter} folds those into their
     * neighbor so word counts stay in lockstep with the original text.
     */
    public static String normalizeWord(String word) {
        var sb = new StringBuilder(word.length());
        for (char c : word.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (c == '\'' || (c >= 'A' && c <= 'Z')) sb.append(c);
        }
        return sb.toString();
    }

    /** Words → CTC class-id sequence with {@code |} separators between words. */
    static int[] tokenize(List<String> normalizedWords) {
        var ids = new ArrayList<Integer>();
        for (int w = 0; w < normalizedWords.size(); w++) {
            if (w > 0) ids.add(WORD_SEP);
            for (char c : normalizedWords.get(w).toCharArray()) {
                int idx = CHARS.indexOf(c);
                if (idx < 0) {
                    throw new TranscriptionException("unalignable character '%c'".formatted(c));
                }
                ids.add(CHAR_IDS[idx]);
            }
        }
        var out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    /**
     * Viterbi forced alignment (the torchaudio trellis): find, for each token
     * of the known sequence, the frame span it occupies on the best path
     * through the CTC log-probabilities. Returns {@code [firstFrame, lastFrame]}
     * per token. Public so tests drive it with synthetic log-probs — no
     * model, no natives.
     */
    public static int[][] trellisAlign(float[][] logProbs, int[] tokens) {
        int frames = logProbs.length;
        int n = tokens.length;
        if (n == 0 || frames < n) {
            throw new TranscriptionException(
                    "audio too short to align: %d frames for %d tokens".formatted(frames, n));
        }

        // trellis[t][j] = best log-prob of consuming tokens[0..j] by frame t.
        var trellis = new float[frames][n];
        trellis[0][0] = logProbs[0][tokens[0]];
        for (int j = 1; j < n; j++) trellis[0][j] = Float.NEGATIVE_INFINITY;
        for (int t = 1; t < frames; t++) {
            for (int j = 0; j < n; j++) {
                // Stay on token j: emit blank or re-emit the token itself.
                float stay = trellis[t - 1][j]
                        + Math.max(logProbs[t][BLANK], logProbs[t][tokens[j]]);
                // Advance from token j-1 by emitting token j.
                float advance = j > 0
                        ? trellis[t - 1][j - 1] + logProbs[t][tokens[j]]
                        : Float.NEGATIVE_INFINITY;
                trellis[t][j] = Math.max(stay, advance);
            }
        }

        // Backtrack: walk from (frames-1, n-1) recording each token's span.
        var spans = new int[n][2];
        int j = n - 1;
        spans[j][1] = frames - 1;
        for (int t = frames - 1; t > 0; t--) {
            float advance = j > 0
                    ? trellis[t - 1][j - 1] + logProbs[t][tokens[j]]
                    : Float.NEGATIVE_INFINITY;
            float stay = trellis[t - 1][j]
                    + Math.max(logProbs[t][BLANK], logProbs[t][tokens[j]]);
            if (advance > stay) {
                spans[j][0] = t;
                j--;
                spans[j][1] = t - 1;
            }
        }
        spans[0][0] = 0;
        if (j != 0) {
            throw new TranscriptionException("alignment backtrack failed (stuck at token " + j + ")");
        }
        return spans;
    }

    /** Collapse per-token spans into per-word absolute time ranges. */
    private static List<double[]> toWordTimes(int[][] spans, int[] tokens, int wordCount,
                                              double offsetSeconds) {
        var times = new ArrayList<double[]>(wordCount);
        int word = 0;
        double start = -1;
        double end = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i] == WORD_SEP) {
                times.add(new double[]{offsetSeconds + start, offsetSeconds + end});
                word++;
                start = -1;
            } else {
                if (start < 0) start = spans[i][0] * FRAME_SECONDS;
                end = (spans[i][1] + 1) * FRAME_SECONDS;
            }
        }
        times.add(new double[]{offsetSeconds + start, offsetSeconds + end});
        if (times.size() != wordCount) {
            throw new TranscriptionException(
                    "alignment produced %d words, expected %d".formatted(times.size(), wordCount));
        }
        return times;
    }

    public static float[][] logSoftmax(float[][] logits) {
        var out = new float[logits.length][];
        for (int t = 0; t < logits.length; t++) {
            float max = Float.NEGATIVE_INFINITY;
            for (float v : logits[t]) max = Math.max(max, v);
            double sum = 0;
            for (float v : logits[t]) sum += Math.exp(v - max);
            float logSum = (float) (max + Math.log(sum));
            out[t] = new float[logits[t].length];
            for (int c = 0; c < logits[t].length; c++) out[t][c] = logits[t][c] - logSum;
        }
        return out;
    }

    /** Caller must hold {@link #inferenceLock}. */
    private static void ensureSession() {
        if (session != null) return;
        var modelPath = AlignmentModelManager.ensureAvailable();
        try {
            var env = OrtEnvironment.getEnvironment();
            // JCLAW-650 GPU sweep: CoreML EP with per-node CPU fallback.
            // Alignment consumes frame ARGMAX over logits (Viterbi), so
            // fp16 drift moves boundaries at most one 20ms frame — unlike
            // the cosine thresholds that keep embeddings on CPU.
            var opts = new OrtSession.SessionOptions();
            try {
                opts.addCoreML();
                Logger.info("CtcForcedAligner: CoreML execution provider registered");
            } catch (OrtException | UnsatisfiedLinkError e) {
                Logger.info("CtcForcedAligner: CoreML EP unavailable (%s) — CPU provider", e.getMessage());
            }
            session = env.createSession(modelPath.toString(), opts);
            inputName = session.getInputNames().iterator().next();
            Logger.info("CtcForcedAligner: alignment model loaded from %s", modelPath);
        } catch (OrtException | RuntimeException e) {
            throw new TranscriptionException(
                    "failed to initialise the word-alignment model: " + e.getMessage(), e);
        } catch (@SuppressWarnings("java:S1181") UnsatisfiedLinkError e) {
            throw new TranscriptionException(
                    "onnxruntime native library unavailable on this platform — "
                            + "word alignment needs an onnxruntime build for this OS/arch", e);
        }
    }

    /** Free the ONNX session on JVM shutdown. Wired from {@link jobs.ShutdownJob}. */
    public static void shutdown() {
        synchronized (inferenceLock) {
            if (session != null) {
                try {
                    session.close();
                } catch (@SuppressWarnings("java:S1181") Throwable t) {
                    // Native close can surface native errors; shutdown must continue
                    Logger.warn(t, "CtcForcedAligner: error releasing session");
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
