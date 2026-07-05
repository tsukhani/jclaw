import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizedTranscript;
import services.transcription.MsddSecondOpinion;
import services.transcription.SherpaDiarizer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JCLAW-612: MSDD id-to-label mapping and the sustained-speech verdict
 * thresholds, pinned to the JCLAW-610 bench measurements — correct flips
 * carried >= 1.28s of MSDD-active speech, wrong ones (brief interjections)
 * <= 0.82s.
 */
class MsddSecondOpinionTest extends UnitTest {

    private static DiarizedTranscript.Entry entry(String speaker, double start, double end) {
        return new DiarizedTranscript.Entry(speaker, start, end, "t", null);
    }

    private static SherpaDiarizer.SpeakerSegment seg(double s, double e, int spk) {
        return new SherpaDiarizer.SpeakerSegment(s, e, spk);
    }

    @Test
    void mapSpeakers_majorityOverUncontestedTurnsOnly() {
        var entries = List.of(
                entry("Podcaster", 0, 10),
                entry("Firdaus", 10, 20),
                entry("Firdaus", 20, 22));           // contested — must not vote
        var msdd = List.of(seg(0, 9, 1), seg(10, 19, 0), seg(20, 22, 1));

        var mapping = MsddSecondOpinion.mapSpeakers(entries, Set.of(2), msdd);

        assertEquals("Podcaster", mapping.get(1));
        assertEquals("Firdaus", mapping.get(0));
    }

    @Test
    void verdict_adoptsSustainedSpeech_rejectsInterjectionEvidence() {
        var mapping = Map.of(0, "Firdaus", 1, "Podcaster");
        // The bench thank-you shape: 2.32s of Podcaster within the window.
        assertEquals("Podcaster", MsddSecondOpinion.verdict(
                entry("Firdaus", 7.08, 9.40), List.of(seg(7.08, 9.40, 1)), mapping));
        // The bench "Sorry?" shape: only 0.42s active — below the sustained
        // floor, MSDD's interjection blind spot: no verdict.
        assertNull(MsddSecondOpinion.verdict(
                entry("Firdaus", 73.76, 74.32), List.of(seg(73.9, 74.32, 1)), mapping));
        // Split evidence under the dominance bar: no verdict.
        assertNull(MsddSecondOpinion.verdict(
                entry("Firdaus", 0, 4), List.of(seg(0, 2, 1), seg(2, 3.6, 0)), mapping));
        // Sustained + dominant + agreeing with the current label: confirmed.
        assertEquals("Firdaus", MsddSecondOpinion.verdict(
                entry("Firdaus", 0, 3), List.of(seg(0, 2.8, 0)), mapping));
        // Unmapped speakers contribute nothing.
        assertNull(MsddSecondOpinion.verdict(
                entry("Firdaus", 0, 3), List.of(seg(0, 2.8, 7)), mapping));
    }
}
