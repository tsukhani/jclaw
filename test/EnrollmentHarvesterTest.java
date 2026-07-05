import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.EnrollmentHarvester;
import services.transcription.OverlapReattributor;
import services.transcription.SpeakerSegment;
import services.transcription.SpeakerClipExtractor;

import java.util.Arrays;
import java.util.List;

/**
 * JCLAW-609 round 3: the separation stem gate, driven through the
 * Embedder/Separator seams. Voices are constant fill levels; the fake
 * embedder maps loud fills to one one-hot vector and quiet fills to
 * another, and the fake separator decides what the "second stem" of each
 * window contains — a real other voice (bleed) or near-silence (pure).
 */
class EnrollmentHarvesterTest extends UnitTest {

    private static final int SR = 16_000;

    private static final SpeakerClipExtractor.Embedder VOICE_EMBEDDER = samples -> {
        double sum = 0;
        for (float v : samples) sum += Math.abs(v);
        double mean = sum / samples.length;
        if (mean < 0.01) return new float[]{0, 0, 1};  // silence/residue
        return mean >= 0.5 ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
    };

    /** Speaker 0 talks 0-30s at level 0.8; speaker 1 talks 35-65s at 0.1. */
    private static float[] pcm() {
        var pcm = new float[70 * SR];
        Arrays.fill(pcm, 0, 30 * SR, 0.8f);
        Arrays.fill(pcm, 35 * SR, 65 * SR, 0.1f);
        return pcm;
    }

    private static final List<SpeakerSegment> SEGMENTS = List.of(
            new SpeakerSegment(0, 30, 0),
            new SpeakerSegment(35, 65, 1));

    /** Separator whose second stem is the OTHER voice for windows of the
     *  given speaker — simulating simultaneous bleed under that speaker. */
    private static OverlapReattributor.Separator bleedingFor(float bleedingLevel) {
        return windows -> windows.stream().map(window -> {
            var second = new float[window.length];
            Arrays.fill(second, bleedingLevel);
            return List.of(window.clone(), second);
        }).toList();
    }

    private static final OverlapReattributor.Separator CLEAN_SEPARATOR = windows ->
            windows.stream().map(w -> List.of(w.clone(), new float[w.length])).toList();

    @Test
    void harvest_keepsBudget_whenAllCandidatesArePure() {
        var out = EnrollmentHarvester.harvest(pcm(), SEGMENTS, List.of(0, 1),
                20.0, 5.0, 1.0, VOICE_EMBEDDER, CLEAN_SEPARATOR);

        for (int speaker : new int[]{0, 1}) {
            double total = out.get(speaker).stream().mapToInt(c -> c.length).sum() / (double) SR;
            assertEquals(20.0, total, 0.2, "pure candidates fill the budget for speaker " + speaker);
        }
    }

    @Test
    void harvest_rejectsBledCandidates_viaTheStemGate() {
        // Every non-anchor window of BOTH speakers "separates" into the
        // window itself plus a stem carrying speaker 1's voice (0.1 fill).
        // For speaker 0 that minor stem matches speaker 1's voiceprint →
        // bleed → rejected; the anchor always survives.
        var out = EnrollmentHarvester.harvest(pcm(), SEGMENTS, List.of(0, 1),
                20.0, 5.0, 1.0, VOICE_EMBEDDER, bleedingFor(0.1f));

        double speaker0Total = out.get(0).stream().mapToInt(c -> c.length).sum() / (double) SR;
        assertEquals(5.0, speaker0Total, 0.2,
                "only the anchor survives when every candidate window is bled");
        // Speaker 1's windows: minor stem = the window itself is voice 1...
        // its OTHER stem carries 0.1 too, matching its OWN voiceprint. Minor
        // stem selection picks the stem LESS like the owner; here both match
        // the owner equally, so the gate sees the clone — own voice is not
        // "another speaker", but max cosine over ALL voiceprints includes
        // the owner's. The rule is deliberately conservative: a minor stem
        // that still sounds like a strong voice is suspicious either way.
        double speaker1Total = out.get(1).stream().mapToInt(c -> c.length).sum() / (double) SR;
        assertEquals(5.0, speaker1Total, 0.2);
    }

    @Test
    void harvest_skipsStemGate_withoutSeparator_sherpaPath() {
        var out = EnrollmentHarvester.harvest(pcm(), SEGMENTS, List.of(0),
                20.0, 5.0, 1.0, VOICE_EMBEDDER, null);

        double total = out.get(0).stream().mapToInt(c -> c.length).sum() / (double) SR;
        assertEquals(20.0, total, 0.2, "no separator: anchor-gated harvest stands");
    }

    @Test
    void harvest_survivesSeparatorFailure_bestEffort() {
        OverlapReattributor.Separator broken = windows -> {
            throw new services.transcription.TranscriptionException("sidecar down");
        };
        var out = EnrollmentHarvester.harvest(pcm(), SEGMENTS, List.of(0),
                20.0, 5.0, 1.0, VOICE_EMBEDDER, broken);

        double total = out.get(0).stream().mapToInt(c -> c.length).sum() / (double) SR;
        assertEquals(20.0, total, 0.2, "a broken separator must not break enrollment");
    }

    @Test
    void harvest_skipsStemGate_onThreeSpeakerRecordings() {
        // JCLAW-618: with 3+ speakers a MossFormer stem is a two-voice
        // mixture — bleed verdicts are meaningless and must not reject.
        var threeSpeakers = List.of(
                new SpeakerSegment(0, 30, 0),
                new SpeakerSegment(35, 65, 1),
                new SpeakerSegment(66, 69, 2));
        var out = EnrollmentHarvester.harvest(pcm(), threeSpeakers, List.of(0),
                20.0, 5.0, 1.0, VOICE_EMBEDDER, bleedingFor(0.1f));

        double total = out.get(0).stream().mapToInt(c -> c.length).sum() / (double) SR;
        assertEquals(20.0, total, 0.2,
                "the bleeding separator is never consulted on 3+ speakers");
    }

    @Test
    void clusterVoiceprints_averagePerSpeaker() {
        var prints = EnrollmentHarvester.clusterVoiceprints(pcm(), SEGMENTS, VOICE_EMBEDDER);
        assertEquals(2, prints.size());
        assertEquals(1f, prints.get(0)[0], 1e-6, "speaker 0 is the loud voice");
        assertEquals(1f, prints.get(1)[1], 1e-6, "speaker 1 is the quiet voice");
    }
}
