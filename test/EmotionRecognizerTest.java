import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizedTranscript;
import services.transcription.EmotionRecognizer;
import services.transcription.TranscriptionException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * JCLAW-563/564: the pure layers of the emotion recognizer — window
 * arithmetic, emotion2vec's waveform layer-norm, the external classifier
 * head (parse, mean-pool, linear+softmax, label mapping), and the
 * annotate() skip paths. Everything below the ONNX session boundary needs
 * the ~356 MB backbone on disk and is exercised by UAT (suite section 16),
 * not here — same split as the sherpa and whisper tests.
 */
class EmotionRecognizerTest extends UnitTest {

    private static final int SR = 16_000;
    private static final int ROWS = EmotionRecognizer.LABELS.length;
    private static final int COLS = 768;

    // ---- window() -------------------------------------------------------

    @Test
    void window_mapsSecondsToSampleIndices() {
        int[] w = EmotionRecognizer.window(1.0, 3.0, 10 * SR);
        assertArrayEquals(new int[]{SR, 3 * SR}, w);
    }

    @Test
    void window_rejectsSegmentsShorterThanMinimum() {
        assertNull(EmotionRecognizer.window(1.0, 1.2, 10 * SR),
                "sub-half-second windows carry too little acoustic evidence");
    }

    @Test
    void window_truncatesAtMaxSeconds() {
        int[] w = EmotionRecognizer.window(0.0, 90.0, 100 * SR);
        assertEquals(0, w[0]);
        assertEquals((int) (EmotionRecognizer.MAX_SECONDS * SR), w[1],
                "a 90s turn is classified from its first 30s");
    }

    @Test
    void window_clampsToDecodedAudio_andRejectsWhenNothingRemains() {
        // Diarized times overrun the decoded audio (container duration lies):
        // clamp what's clampable, null when the overlap is too short to use.
        int[] w = EmotionRecognizer.window(8.0, 12.0, 9 * SR);
        assertArrayEquals(new int[]{8 * SR, 9 * SR}, w, "end clamps to the decoded length");
        assertNull(EmotionRecognizer.window(9.5, 12.0, 9 * SR),
                "window entirely past the decoded audio must be rejected");
    }

    // ---- normalize() ----------------------------------------------------

    @Test
    void normalize_producesZeroMeanUnitVariance() {
        var samples = new float[]{0.1f, 0.5f, -0.3f, 0.9f, -0.7f, 0.2f};
        var out = EmotionRecognizer.normalize(samples, 0, samples.length);

        double mean = 0;
        for (float f : out) mean += f;
        mean /= out.length;
        double variance = 0;
        for (float f : out) variance += (f - mean) * (f - mean);
        variance /= out.length;

        assertEquals(0.0, mean, 1e-5);
        assertEquals(1.0, variance, 1e-3);
    }

    @Test
    void normalize_survivesSilence() {
        // All-zero window: variance 0, only the epsilon keeps the division
        // finite — must yield zeros, not NaN/Infinity fed to the model.
        var out = EmotionRecognizer.normalize(new float[]{0f, 0f, 0f, 0f}, 0, 4);
        for (float f : out) assertEquals(0.0f, f, 1e-9);
    }

    @Test
    void normalize_respectsWindowBounds() {
        var samples = new float[]{100f, 1f, 2f, 3f, 100f};
        var out = EmotionRecognizer.normalize(samples, 1, 4);
        assertEquals(3, out.length, "only the window is normalized");
        assertEquals(0.0f, out[1], 1e-6, "window midpoint is the window mean");
    }

    // ---- softmax() ------------------------------------------------------

    @Test
    void softmax_sumsToOne_andPreservesArgmax() {
        var probs = EmotionRecognizer.softmax(new float[]{1f, 3f, 2f, -1f, 0f, 1.5f});
        double sum = 0;
        int best = 0;
        for (int i = 0; i < probs.length; i++) {
            sum += probs[i];
            if (probs[i] > probs[best]) best = i;
        }
        assertEquals(1.0, sum, 1e-5);
        assertEquals(1, best, "index of the largest logit wins");
    }

    @Test
    void softmax_stableUnderLargeLogits() {
        var probs = EmotionRecognizer.softmax(new float[]{500f, 400f});
        assertEquals(1.0f, probs[0], 1e-5);
        assertFalse(Float.isNaN(probs[1]));
    }

    // ---- classifier head (JCLAW-564) -------------------------------------

    @Test
    void labels_matchEmotion2vecOrder_andDisplayMappingDropsNonEmotions() {
        assertArrayEquals(new String[]{"angry", "disgusted", "fearful", "happy",
                "neutral", "other", "sad", "surprised", "unknown"}, EmotionRecognizer.LABELS);
        assertEquals(EmotionRecognizer.LABELS.length, EmotionRecognizer.DISPLAY_LABELS.length);
        assertNull(EmotionRecognizer.DISPLAY_LABELS[5], "'other' must not surface as an emotion");
        assertNull(EmotionRecognizer.DISPLAY_LABELS[8], "'unknown' must not surface as an emotion");
        assertEquals("disgust", EmotionRecognizer.DISPLAY_LABELS[1],
                "display vocabulary keeps the JCLAW-563 adjectives");
    }

    @Test
    void parseClassifier_readsHeaderWeightsAndBias() {
        var c = EmotionRecognizer.parseClassifier(syntheticClassifier(2, 0.5f));

        assertEquals(ROWS, c.weights().length);
        assertEquals(COLS, c.weights()[0].length);
        assertEquals(0.5f, c.weights()[2][0], 1e-6, "favored row carries the weight");
        assertEquals(0.0f, c.weights()[0][0], 1e-6);
        assertEquals(1.0f, c.bias()[2], 1e-6);
    }

    @Test
    void parseClassifier_rejectsWrongShape() {
        var bad = ByteBuffer.allocate(8 + 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(3).putInt(1).putFloat(1f).array();
        var e = assertThrows(TranscriptionException.class,
                () -> EmotionRecognizer.parseClassifier(bad));
        assertTrue(e.getMessage().contains("unexpected shape"), e.getMessage());
    }

    @Test
    void classifyEmbedding_picksFavoredRow_andMapsDisplayLabel() {
        // Row 3 = "happy": weights of 1 on every embedding dim, bias 1.
        var c = EmotionRecognizer.parseClassifier(syntheticClassifier(3, 1f));
        var emb = new float[COLS];
        java.util.Arrays.fill(emb, 0.1f);

        var emotion = EmotionRecognizer.classifyEmbedding(emb, c);

        assertEquals("happy", emotion.label());
        assertTrue(emotion.confidence() > 0.9, "one hot row should dominate the softmax");
    }

    @Test
    void classifyEmbedding_returnsNullLabel_forOtherAndUnknown() {
        // Row 5 = "other", row 8 = "unknown": both map to no annotation.
        for (int row : new int[]{5, 8}) {
            var c = EmotionRecognizer.parseClassifier(syntheticClassifier(row, 1f));
            var emb = new float[COLS];
            java.util.Arrays.fill(emb, 0.1f);
            assertNull(EmotionRecognizer.classifyEmbedding(emb, c).label(),
                    "row " + row + " must not annotate the transcript");
        }
    }

    @Test
    void meanPool_averagesFrames() {
        var pooled = EmotionRecognizer.meanPool(new float[][]{{1f, 4f}, {3f, 8f}});
        assertArrayEquals(new float[]{2f, 6f}, pooled, 1e-6f);
    }

    // ---- annotate skip paths ---------------------------------------------

    @Test
    void annotate_leavesShortEntriesUnlabeled_withoutTouchingModelOrNetwork() {
        // Entries all below MIN_SECONDS: annotate() must return them
        // unchanged without ever reaching classify() — no model on disk and
        // no MockWebServer here, so any classify() attempt would try a real
        // download and throw out of this (deliberately un-caught) overload.
        var entries = List.of(
                new DiarizedTranscript.Entry("SPEAKER_00", 0.0, 0.3, "hm"),
                new DiarizedTranscript.Entry("SPEAKER_01", 0.3, 0.6, "yes"));

        var out = EmotionRecognizer.annotate(new float[SR], entries);

        assertEquals(2, out.size());
        assertNull(out.get(0).emotion());
        assertNull(out.get(1).emotion());
        assertEquals("hm", out.get(0).text(), "entries pass through untouched");
    }

    /** Classifier bytes where {@code favoredRow} has weight {@code w} on
     *  every dim and bias 1; all other rows are zero. */
    private static byte[] syntheticClassifier(int favoredRow, float w) {
        var buf = ByteBuffer.allocate(8 + 4 * ROWS * COLS + 4 * ROWS)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(ROWS).putInt(COLS);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) buf.putFloat(r == favoredRow ? w : 0f);
        }
        for (int r = 0; r < ROWS; r++) buf.putFloat(r == favoredRow ? 1f : 0f);
        return buf.array();
    }
}
