import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizedTranscript;
import services.transcription.OverlapReattributor;
import services.transcription.UnderSpeechRecovery;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-613: under-speech recovery gates and insertion, via seams. Voices
 * are constant fills; the one-hot embedder makes voiceprint identity exact
 * and the fake transcriber controls whisper's output.
 */
class UnderSpeechRecoveryTest extends UnitTest {

    private static final int SR = 16_000;

    private static final OverlapReattributor.Embedder VOICE_EMBEDDER = samples -> {
        double sum = 0;
        for (float v : samples) sum += Math.abs(v);
        double mean = sum / samples.length;
        if (mean < 0.01) return new float[]{0, 0, 1};
        return mean >= 0.5 ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
    };

    private static final Map<String, float[]> REFS = Map.of(
            "Podcaster", new float[]{1, 0, 0},   // loud voice
            "Firdaus", new float[]{0, 1, 0});    // quiet voice

    private static DiarizedTranscript.Entry entry(String speaker, double start, double end) {
        return new DiarizedTranscript.Entry(speaker, start, end, "t", null);
    }

    /** One 30s window at t=0; stem 0 = Podcaster (0.8), stem 1 = Firdaus (0.1). */
    private static List<List<float[]>> stems() {
        var podcaster = new float[30 * SR];
        Arrays.fill(podcaster, 0.8f);
        var firdaus = new float[30 * SR];
        Arrays.fill(firdaus, 0.1f);
        return List.of(List.of(podcaster, firdaus));
    }

    private static final List<float[]> WINDOWS = List.of(new float[30 * SR]);
    private static final List<Double> STARTS = List.of(0.0);

    @Test
    void recover_insertsBackchannel_fromTheUnderSpeakersStem() {
        var entries = List.of(entry("Podcaster", 0, 20), entry("Firdaus", 20, 25));
        var overlaps = List.<double[]>of(new double[]{10.0, 11.0}); // backchannel scale

        var out = UnderSpeechRecovery.recover(entries, overlaps, WINDOWS, STARTS,
                stems(), REFS, VOICE_EMBEDDER, slice -> "Yeah.");

        assertEquals(3, out.size(), "the backchannel becomes its own turn");
        var inserted = out.get(1);
        assertEquals("Firdaus", inserted.speaker(), "attributed to the under-speaker");
        assertEquals("Yeah.", inserted.text());
        assertEquals(10.0, inserted.start(), 1e-9);
        assertFalse(inserted.crossTalk());
        assertTrue(inserted.underSpeech(), "recovered turns are marked as under-speech");
        assertEquals("Podcaster", out.get(0).speaker(), "existing turns untouched");
    }

    @Test
    void recover_capsProtectAgainstHallucination_andScale() {
        var entries = List.of(entry("Podcaster", 0, 20), entry("Firdaus", 20, 25));
        var overlaps = List.<double[]>of(new double[]{10.0, 11.0});

        assertEquals(entries, UnderSpeechRecovery.recover(entries, overlaps, WINDOWS, STARTS,
                        stems(), REFS, VOICE_EMBEDDER,
                        slice -> "thanks for watching and do not forget to subscribe to the channel"),
                "long whisper output is hallucination-scale: never inserted");
        assertEquals(entries, UnderSpeechRecovery.recover(entries, overlaps, WINDOWS, STARTS,
                        stems(), REFS, VOICE_EMBEDDER, slice -> "  "),
                "blank output: nothing inserted");
        assertEquals(entries, UnderSpeechRecovery.recover(entries, overlaps, WINDOWS, STARTS,
                        stems(), REFS, VOICE_EMBEDDER, slice -> "-"),
                "punctuation-only output (live artifact): nothing inserted");
        assertEquals(entries, UnderSpeechRecovery.recover(entries, overlaps, WINDOWS, STARTS,
                        stems(), REFS, VOICE_EMBEDDER, slice -> "so what did you do"),
                "5 words in a 1s region exceeds plausible speech density");
        assertEquals(entries, UnderSpeechRecovery.recover(entries,
                        List.of(new double[]{10.0, 14.5}), WINDOWS, STARTS,
                        stems(), REFS, VOICE_EMBEDDER, slice -> "Yeah."),
                "regions beyond backchannel scale are re-attribution territory");
        assertEquals(entries, UnderSpeechRecovery.recover(entries, overlaps, WINDOWS, STARTS,
                        stems(), REFS, VOICE_EMBEDDER, null),
                "no transcriber (config off / sherpa): no-op");
    }

    @Test
    void recover_standsDown_withThreeReferences() {
        // JCLAW-618: "the other speaker" is undefined with 3 voices.
        var refs = Map.of("Podcaster", new float[]{1, 0, 0},
                "Firdaus", new float[]{0, 1, 0},
                "Third", new float[]{0, 0, 1});
        var entries = List.of(entry("Podcaster", 0, 20), entry("Firdaus", 20, 25));

        var out = UnderSpeechRecovery.recover(entries,
                List.of(new double[]{10.0, 11.0}), WINDOWS, STARTS,
                stems(), refs, VOICE_EMBEDDER, slice -> "Yeah.");

        assertEquals(entries, out);
    }

    @Test
    void recover_skipsWhenNoStemMatchesTheUnderSpeaker() {
        // Both stems carry the OWNER's voice (bad separation) — the relative
        // voiceprint gate must reject the region.
        var podcaster = new float[30 * SR];
        Arrays.fill(podcaster, 0.8f);
        var alsoPodcaster = new float[30 * SR];
        Arrays.fill(alsoPodcaster, 0.9f);
        var entries = List.of(entry("Podcaster", 0, 20), entry("Firdaus", 20, 25));

        var out = UnderSpeechRecovery.recover(entries,
                List.of(new double[]{10.0, 11.0}), WINDOWS, STARTS,
                List.of(List.of(podcaster, alsoPodcaster)), REFS, VOICE_EMBEDDER, slice -> "Yeah.");

        assertEquals(entries, out);
    }

    @Test
    void recover_dedupes_whenTheUnderSpeakerAlreadyHasTheTurn() {
        // The word-splitter already produced a Firdaus turn over the tail
        // of the region (realistic split shape: it abuts rather than
        // centers, so the region midpoint still belongs to the owner).
        var entries = List.of(entry("Podcaster", 0, 10.6),
                entry("Firdaus", 10.6, 11.0), entry("Podcaster", 11, 20));

        var out = UnderSpeechRecovery.recover(entries,
                List.of(new double[]{10.0, 11.0}), WINDOWS, STARTS,
                stems(), REFS, VOICE_EMBEDDER, slice -> "Yeah.");

        assertEquals(entries, out, "existing coverage wins — no duplicate turn");
    }
}
