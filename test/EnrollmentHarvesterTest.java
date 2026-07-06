import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.EnrollmentHarvester;
import services.transcription.SpeakerClipExtractor;
import services.transcription.SpeakerSegment;

import java.util.Arrays;
import java.util.List;

/**
 * JCLAW-609/653: anchor-gated enrollment harvesting for the simple
 * whisper+pyannote tier. Voices are constant fill levels; the fake
 * embedder maps a clip to a 2-dim "voiceprint" by mean level, so clips of
 * one speaker match one another.
 */
class EnrollmentHarvesterTest extends UnitTest {

    private static final int SR = 16_000;

    private static final SpeakerClipExtractor.Embedder LEVEL = samples -> {
        double sum = 0;
        for (float v : samples) sum += Math.abs(v);
        return sum / samples.length >= 0.5 ? new float[]{1, 0} : new float[]{0, 1};
    };

    /** 60s recording: speaker 0 = loud fill on [0,30), speaker 1 = quiet
     *  fill on [30,60). */
    private static float[] pcm() {
        var pcm = new float[60 * SR];
        Arrays.fill(pcm, 0, 30 * SR, 0.9f);
        Arrays.fill(pcm, 30 * SR, 60 * SR, 0.2f);
        return pcm;
    }

    private static List<SpeakerSegment> segments() {
        return List.of(new SpeakerSegment(0, 30, 0), new SpeakerSegment(30, 60, 1));
    }

    @Test
    void harvest_trimsToTheTargetBudget_perSpeaker() {
        var out = EnrollmentHarvester.harvest(pcm(), segments(), List.of(0, 1),
                20.0, 8.0, 3.0, LEVEL);

        for (var speaker : List.of(0, 1)) {
            double total = out.get(speaker).stream()
                    .mapToDouble(c -> c.length / (double) SR).sum();
            assertTrue(total <= 20.5, "speaker " + speaker + " over budget: " + total);
            assertTrue(total >= 8.0, "speaker " + speaker + " under-harvested: " + total);
            assertFalse(out.get(speaker).isEmpty());
        }
    }

    @Test
    void clusterVoiceprints_unitScalesChunksBeforeAveraging() {
        var prints = EnrollmentHarvester.clusterVoiceprints(pcm(), segments(), LEVEL);
        assertEquals(2, prints.size());
        // Loud speaker's print points at [1,0]; quiet at [0,1].
        assertTrue(prints.get(0)[0] > prints.get(0)[1]);
        assertTrue(prints.get(1)[1] > prints.get(1)[0]);
    }
}
