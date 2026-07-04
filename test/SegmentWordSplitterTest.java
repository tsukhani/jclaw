import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.SegmentWordSplitter;
import services.transcription.SherpaDiarizer;
import services.transcription.TranscriptionException;
import services.transcription.WhisperJniTranscriber;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-603: the word-level splitter's pure logic — boundary derivation,
 * pass-through for non-straddling segments, word-midpoint partitioning with
 * an injected fake aligner, punctuation-word folding, and the best-effort
 * fallback when alignment fails. The real CTC aligner is exercised by
 * {@link CtcForcedAlignerTest} (trellis) and UAT (model inference).
 */
class SegmentWordSplitterTest extends UnitTest {

    private static WhisperJniTranscriber.Segment seg(long startMs, long endMs, String text) {
        return new WhisperJniTranscriber.Segment(startMs, endMs, text);
    }

    private static SherpaDiarizer.SpeakerSegment spk(double start, double end, int speaker) {
        return new SherpaDiarizer.SpeakerSegment(start, end, speaker);
    }

    /** Aligner that spreads words uniformly across the requested window. */
    private static final SegmentWordSplitter.WordAligner UNIFORM = (start, end, words) -> {
        var times = new ArrayList<double[]>();
        double step = (end - start) / words.size();
        for (int i = 0; i < words.size(); i++) {
            times.add(new double[]{start + i * step, start + (i + 1) * step});
        }
        return times;
    };

    @Test
    void speakerChangeBoundaries_midpointsBetweenDifferentSpeakers() {
        var boundaries = SegmentWordSplitter.speakerChangeBoundaries(List.of(
                spk(0, 4, 0), spk(4.2, 6, 1), spk(6, 9, 1), spk(9.4, 12, 0)));
        assertEquals(2, boundaries.size());
        assertEquals(4.1, boundaries.get(0), 1e-9);
        assertEquals(9.2, boundaries.get(1), 1e-9, "same-speaker joints produce no boundary");
    }

    @Test
    void split_passesThrough_whenNoSegmentStraddlesABoundary() {
        var transcript = List.of(seg(0, 3900, " all one speaker"), seg(4300, 8000, " reply"));
        var speakers = List.of(spk(0, 4, 0), spk(4.2, 8, 1));

        var out = SegmentWordSplitter.split(transcript, speakers, (s, e, w) -> {
            throw new AssertionError("aligner must not run when nothing straddles");
        });

        assertEquals(transcript, out, "boundary at 4.1s is outside both segments' interiors");
    }

    @Test
    void split_cutsStraddlingSegment_atTheWordNearestTheBoundary() {
        // One whisper segment 0-6s containing question + answer; speaker
        // changes at 3.0s. Six uniform 1s words → midpoints 0.5..5.5;
        // words with midpoint > 3.0 ("mean kosher accepted") go right.
        var transcript = List.of(seg(0, 6000, " what does it mean kosher accepted"));
        var speakers = List.of(spk(0, 2.9, 0), spk(3.1, 6, 1));

        var out = SegmentWordSplitter.split(transcript, speakers, UNIFORM);

        assertEquals(2, out.size());
        assertEquals("what does it", out.get(0).text().strip());
        assertEquals("mean kosher accepted", out.get(1).text().strip());
        assertEquals(0, out.get(0).startMs(), "first part keeps the original start");
        assertEquals(6000, out.get(1).endMs(), "last part keeps the original end");
        assertTrue(out.get(0).endMs() <= out.get(1).startMs(),
                "parts must not overlap: " + out);
    }

    @Test
    void split_keepsOriginal_whenAlignerFails() {
        var transcript = List.of(seg(0, 6000, " some straddling text here"));
        var speakers = List.of(spk(0, 2.9, 0), spk(3.1, 6, 1));

        var out = SegmentWordSplitter.split(transcript, speakers, (s, e, w) -> {
            throw new TranscriptionException("audio too short to align");
        });

        assertEquals(transcript, out, "alignment failure keeps whole-segment attribution");
    }

    @Test
    void split_keepsOriginal_whenAllWordsFallOnOneSide() {
        var transcript = List.of(seg(0, 6000, " every word early"));
        var speakers = List.of(spk(0, 5.4, 0), spk(5.6, 6, 1));
        // Boundary 5.5s is interior (margin 0.3), but uniform word midpoints
        // (1.0, 3.0, 5.0) all precede it.
        var out = SegmentWordSplitter.split(transcript, speakers, UNIFORM);
        assertEquals(1, out.size());
        assertEquals(transcript.get(0), out.get(0));
    }

    @Test
    void groupAlignableWords_foldsPunctuationIntoNeighbors() {
        var groups = SegmentWordSplitter.groupAlignableWords(
                List.of("-", "Sorry?", "…", "123", "okay"));
        assertEquals(2, groups.size());
        assertEquals("- Sorry? … 123", groups.get(0).original(),
                "leading and trailing unalignables fold into the first alignable word");
        assertEquals("SORRY", groups.get(0).normalized());
        assertEquals("okay", groups.get(1).original());
    }

    @Test
    void split_handlesMultipleBoundaries_inOneSegment() {
        // A-B-A exchange inside one 9s segment: boundaries at 3.0 and 6.0.
        var transcript = List.of(seg(0, 9000, " one two three four five six seven eight nine"));
        var speakers = List.of(spk(0, 2.9, 0), spk(3.1, 5.9, 1), spk(6.1, 9, 0));

        var out = SegmentWordSplitter.split(transcript, speakers, UNIFORM);

        assertEquals(3, out.size());
        assertEquals("one two three", out.get(0).text().strip());
        assertEquals("four five six", out.get(1).text().strip());
        assertEquals("seven eight nine", out.get(2).text().strip());
    }
}
