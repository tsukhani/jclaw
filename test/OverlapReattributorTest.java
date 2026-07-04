import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizedTranscript;
import services.transcription.OverlapReattributor;
import services.transcription.TranscriptionException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-605: the overlap re-attribution decision machinery, driven through
 * the Separator/Embedder seams — no sidecar, no natives. The fake embedder
 * maps a window to {@code [mean(samples), 1]}, so filling regions/stems with
 * distinct constants makes voice identity fully controllable.
 */
class OverlapReattributorTest extends UnitTest {

    private static final int SR = 16_000;

    private static DiarizedTranscript.Entry entry(String speaker, double start, double end) {
        return new DiarizedTranscript.Entry(speaker, start, end, "text " + speaker, null);
    }

    private static final OverlapReattributor.Embedder MEAN_EMBEDDER = samples -> {
        double sum = 0;
        for (float s : samples) sum += s;
        return new float[]{(float) (sum / samples.length), 1f};
    };

    /** pcm: speaker A speaks 0-8s at level 0.8, B speaks 8-16s at 0.2, the
     *  overlap region 16-20s carries the mixture (0.5). */
    private static float[] pcm() {
        var pcm = new float[20 * SR];
        Arrays.fill(pcm, 0, 8 * SR, 0.8f);
        Arrays.fill(pcm, 8 * SR, 16 * SR, 0.2f);
        Arrays.fill(pcm, 16 * SR, 20 * SR, 0.5f);
        return pcm;
    }

    @Test
    void reattribute_flipsBuriedVoice_whenStemEvidenceIsDecisive() {
        var entries = List.of(
                entry("A", 0, 8),          // clean reference material for A
                entry("B", 8, 16),         // clean reference material for B
                entry("B", 16.5, 19.5));   // disputed: labeled B, actually A
        var overlaps = List.<double[]>of(new double[]{16, 20});
        // Separator returns one stem carrying A's voice (0.8) and one silent.
        OverlapReattributor.Separator separator = windows -> windows.stream().map(window -> {
            var stemA = new float[window.length];
            Arrays.fill(stemA, 0.8f);
            return List.of(stemA, new float[window.length]); // second stem silent
        }).toList();

        var out = OverlapReattributor.reattribute(entries, overlaps, pcm(), separator, MEAN_EMBEDDER);

        assertEquals("A", out.get(2).speaker(), "buried voice must be re-attributed");
        assertEquals("A", out.get(0).speaker(), "clean entries untouched");
        assertEquals("B", out.get(1).speaker());
        assertEquals(entries.get(2).text(), out.get(2).text(), "text and times unchanged");
    }

    @Test
    void reattribute_keepsLabel_whenCurrentSpeakerWins() {
        var entries = List.of(
                entry("A", 0, 8),
                entry("B", 8, 16),
                entry("B", 16.5, 19.5));   // labeled B, and the stem says B too
        var overlaps = List.<double[]>of(new double[]{16, 20});
        OverlapReattributor.Separator separator = windows -> windows.stream().map(window -> {
            var stemB = new float[window.length];
            Arrays.fill(stemB, 0.2f);      // B's voice level
            return List.of(stemB, new float[window.length]);
        }).toList();

        var out = OverlapReattributor.reattribute(entries, overlaps, pcm(), separator, MEAN_EMBEDDER);

        assertEquals(entries, out, "agreeing evidence must change nothing");
    }

    @Test
    void reattribute_isUntouched_withoutOverlapIntersectingEntries() {
        var entries = List.of(entry("A", 0, 8), entry("B", 8, 15));
        var overlaps = List.<double[]>of(new double[]{16, 20}); // nobody there
        OverlapReattributor.Separator separator = windows -> {
            throw new AssertionError("separator must not run when nothing intersects");
        };
        assertEquals(entries,
                OverlapReattributor.reattribute(entries, overlaps, pcm(), separator, MEAN_EMBEDDER));
    }

    @Test
    void reattribute_skips_whenOnlyOneCleanReferenceExists() {
        // All of B's speech is inside the overlap: no clean B reference.
        var entries = List.of(entry("A", 0, 8), entry("B", 16.5, 19.5));
        var overlaps = List.<double[]>of(new double[]{16, 20});
        OverlapReattributor.Separator separator = windows -> {
            throw new AssertionError("no decision is possible with one reference");
        };
        assertEquals(entries,
                OverlapReattributor.reattribute(entries, overlaps, pcm(), separator, MEAN_EMBEDDER));
    }

    @Test
    void reattribute_survivesSeparatorFailure_viaBestEffortWrapper() {
        // The Path-based wrapper catches; the seam-based core throws through —
        // pin the core's contract so callers know which one they hold.
        var entries = List.of(entry("A", 0, 8), entry("B", 8, 16), entry("B", 16.5, 19.5));
        var overlaps = List.<double[]>of(new double[]{16, 20});
        OverlapReattributor.Separator separator = windows -> {
            throw new TranscriptionException("sidecar down");
        };
        assertThrows(TranscriptionException.class, () ->
                OverlapReattributor.reattribute(entries, overlaps, pcm(), separator, MEAN_EMBEDDER));
    }

    @Test
    void reattribute_includesShortTurnsAdjacentToTheOverlap() {
        // The JCLAW-605 flagship shape: the buried reply sits just AFTER the
        // detected collision (the detector can't mark a voice it can't hear).
        var entries = List.of(
                entry("A", 0, 8),
                entry("B", 8, 15.8),
                entry("B", 18.2, 19.8));   // 2.2s after the region ends; labeled B, actually A
        var overlaps = List.<double[]>of(new double[]{15.8, 16.0});
        OverlapReattributor.Separator separator = windows -> windows.stream().map(window -> {
            var stemA = new float[window.length];
            Arrays.fill(stemA, 0.8f);
            return List.of(stemA, new float[window.length]);
        }).toList();

        var out = OverlapReattributor.reattribute(entries, overlaps, pcm(), separator, MEAN_EMBEDDER);

        assertEquals("A", out.get(2).speaker(),
                "short turns bordering a detected overlap must be re-checked");
    }

    @Test
    void belongsToRegion_trustsLongAdjacentTurns() {
        // A 7s turn next to an overlap is measurably reliable — never pulled in.
        var longTurn = entry("A", 17, 24);
        assertFalse(OverlapReattributor.belongsToRegion(longTurn, new double[]{15.8, 16.0}));
        var shortTurn = entry("A", 17, 19);
        assertTrue(OverlapReattributor.belongsToRegion(shortTurn, new double[]{15.8, 16.0}));
    }

    @Test
    void reattribute_neverFlips_whenMixedAudioBacksTheCurrentLabel() {
        // The cross-talk gate (JCLAW-606 hardening of 605): the disputed
        // entry's ORIGINAL audio is pure B (0.2) — decisively the current
        // label — so even unanimous stem evidence for A must not flip it.
        var pcm = new float[20 * SR];
        Arrays.fill(pcm, 0, 8 * SR, 0.8f);          // A clean
        Arrays.fill(pcm, 8 * SR, 20 * SR, 0.2f);    // B clean through the "overlap"
        var entries = List.of(
                entry("A", 0, 8),
                entry("B", 8, 16),
                entry("B", 16.5, 19.5));
        var overlaps = List.<double[]>of(new double[]{16, 20});
        OverlapReattributor.Separator separator = windows -> windows.stream().map(window -> {
            var stemA = new float[window.length];
            Arrays.fill(stemA, 0.8f);
            return List.of(stemA, new float[window.length]);
        }).toList();

        var out = OverlapReattributor.reattribute(entries, overlaps, pcm, separator, MEAN_EMBEDDER);

        assertEquals("B", out.get(2).speaker(),
                "confident mixed-audio attribution is not overridable by stems");
    }

    // ---- decide() rule table ---------------------------------------------

    @Test
    void decide_flipsOnlyOnClearMargin() {
        // Margin rejects only clear noise (0.07); structural discrimination
        // against wrong flips lives in the mixed-audio gate. The flagship's
        // jitter band (0.078-0.097 on identical audio) must always flip.
        assertEquals("A", OverlapReattributor.decide("B", Map.of("A", 0.7177, "B", 0.6396), 0.07),
                "the flagship's low-jitter margin (0.078) must flip");
        assertEquals("A", OverlapReattributor.decide("B", Map.of("A", 0.774, "B", 0.677), 0.07),
                "the flagship's high-jitter margin (0.097) must flip");
        assertNull(OverlapReattributor.decide("B", Map.of("A", 0.68, "B", 0.64), 0.07),
                "sub-margin noise keeps the current label");
        assertNull(OverlapReattributor.decide("A", Map.of("A", 0.774, "B", 0.677), 0.07),
                "agreeing winner is a no-op");
        assertNull(OverlapReattributor.decide("B", Map.of("A", 0.72), 0.07),
                "a single scored reference is no contest");
    }

    @Test
    void rms_andSilenceGate() {
        var loud = new float[SR];
        Arrays.fill(loud, 0.1f);
        assertTrue(OverlapReattributor.rms(loud) > 0.008);
        assertTrue(OverlapReattributor.rms(new float[SR]) < 0.008);
    }
}
