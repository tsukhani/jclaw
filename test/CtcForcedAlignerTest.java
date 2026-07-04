import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.CtcForcedAligner;
import services.transcription.TranscriptionException;

import java.util.Arrays;

/**
 * JCLAW-603: the pure layers of the CTC forced aligner — word normalization
 * and the Viterbi trellis, driven with synthetic log-probabilities so no
 * model or natives are involved. The model-inference path is covered by UAT
 * on the debate benchmark, same split as the emotion and sherpa tests.
 */
class CtcForcedAlignerTest extends UnitTest {

    @Test
    void normalizeWord_uppercasesAndStripsToAlphabet() {
        assertEquals("SORRY", CtcForcedAligner.normalizeWord("Sorry?"));
        assertEquals("DON'T", CtcForcedAligner.normalizeWord("don't"));
        assertEquals("HALAL", CtcForcedAligner.normalizeWord("\"halal\","));
        assertEquals("", CtcForcedAligner.normalizeWord("—"));
        assertEquals("", CtcForcedAligner.normalizeWord("100%"));
    }

    @Test
    void trellisAlign_recoversObviousSpans_fromSyntheticLogProbs() {
        // 32-class frames; token ids: A=7, B=24, separator=4, blank=0.
        // Plant: frames 0-2 → A, 3-4 → blank, 5 → separator, 6-8 → B.
        int[] tokens = {7, 4, 24}; // "A | B"
        var logProbs = new float[9][32];
        for (float[] frame : logProbs) Arrays.fill(frame, -10f);
        for (int t = 0; t <= 2; t++) logProbs[t][7] = -0.1f;
        for (int t = 3; t <= 4; t++) logProbs[t][0] = -0.1f;
        logProbs[5][4] = -0.1f;
        for (int t = 6; t <= 8; t++) logProbs[t][24] = -0.1f;

        int[][] spans = CtcForcedAligner.trellisAlign(logProbs, tokens);

        assertEquals(0, spans[0][0], "A starts at frame 0");
        assertTrue(spans[0][1] >= 2, "A occupies through at least frame 2: " + Arrays.deepToString(spans));
        assertTrue(spans[2][0] >= 5, "B must not start before the separator: " + Arrays.deepToString(spans));
        assertEquals(8, spans[2][1], "B runs to the final frame");
    }

    @Test
    void trellisAlign_rejectsAudioShorterThanTheText() {
        var logProbs = new float[2][32]; // 2 frames for 3 tokens
        var e = assertThrows(TranscriptionException.class,
                () -> CtcForcedAligner.trellisAlign(logProbs, new int[]{7, 4, 24}));
        assertTrue(e.getMessage().contains("too short"), e.getMessage());
    }

    @Test
    void logSoftmax_normalizesEachFrame() {
        var out = CtcForcedAligner.logSoftmax(new float[][]{{1f, 2f, 3f}});
        double sum = 0;
        for (float v : out[0]) sum += Math.exp(v);
        assertEquals(1.0, sum, 1e-5);
        assertTrue(out[0][2] > out[0][0], "ordering preserved");
    }
}
