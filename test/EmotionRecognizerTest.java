import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizedTranscript;
import services.transcription.EmotionRecognizer;

import java.util.List;

/**
 * JCLAW-563: the pure layers of the emotion recognizer — window arithmetic,
 * wav2vec2 normalization, softmax, and the annotate() skip paths. Everything
 * below the ONNX session boundary needs the 95 MB model on disk and is
 * exercised by UAT (suite section 16), not here — same split as the sherpa
 * and whisper tests.
 */
class EmotionRecognizerTest extends UnitTest {

    private static final int SR = 16_000;

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
        // Max-subtraction keeps exp() from overflowing — quantized models
        // can emit large logits.
        var probs = EmotionRecognizer.softmax(new float[]{500f, 400f});
        assertEquals(1.0f, probs[0], 1e-5);
        assertFalse(Float.isNaN(probs[1]));
    }

    // ---- labels / annotate skip paths -----------------------------------

    @Test
    void labels_matchTheOnnxExportsIdToLabelOrder() {
        assertArrayEquals(new String[]{"sad", "angry", "disgust", "fear", "happy", "neutral"},
                EmotionRecognizer.LABELS);
    }

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
}
