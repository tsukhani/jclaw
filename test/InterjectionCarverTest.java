import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizationRouter;
import services.transcription.DiarizedTranscript;
import services.transcription.InterjectionCarver;
import services.transcription.OverlapReattributor;
import services.transcription.SpeakerSegment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-651: word-granular interjection carving, driven through the
 * Aligner/Embedder seams. Voices are constant fill levels (loud =
 * Podcaster-like [1,0], quiet = Firdaus-like [0,1]); the fake aligner
 * spreads words evenly across the entry; a raw activation window plus the
 * audio's actual fill decide whether the guard lets the carve through.
 */
class InterjectionCarverTest extends UnitTest {

    private static final int SR = 16_000;
    private static final Map<Integer, String> NAMES = Map.of(0, "Podcaster", 1, "Firdaus");
    private static final Map<String, float[]> REFS = Map.of(
            "Podcaster", new float[]{1, 0}, "Firdaus", new float[]{0, 1});

    private static final OverlapReattributor.Embedder VOICE = samples -> {
        double sum = 0;
        for (float v : samples) sum += Math.abs(v);
        return sum / samples.length >= 0.5 ? new float[]{1, 0} : new float[]{0, 1};
    };

    /** Separator whose stems isolate the two fills: stem0 = loud parts only,
     *  stem1 = quiet parts only — perfect separation for the fakes. */
    private static final OverlapReattributor.Separator SPLIT = windows -> {
        var out = new java.util.ArrayList<java.util.List<float[]>>();
        for (var w : windows) {
            var loud = new float[w.length];
            var quiet = new float[w.length];
            for (int i = 0; i < w.length; i++) {
                if (Math.abs(w[i]) >= 0.5) loud[i] = w[i]; else quiet[i] = w[i];
            }
            out.add(java.util.List.of(loud, quiet));
        }
        return out;
    };

    private static final InterjectionCarver.Aligner EVEN = (start, end, words) -> {
        double step = (end - start) / words.size();
        var out = new java.util.ArrayList<double[]>();
        for (int i = 0; i < words.size(); i++) {
            out.add(new double[]{start + i * step, start + (i + 1) * step});
        }
        return out;
    };

    /** 10s recording: Firdaus (quiet 0.2) throughout, except a LOUD
     *  (Podcaster) burst at 4-5s — the interjection. */
    private static float[] pcm() {
        var pcm = new float[10 * SR];
        Arrays.fill(pcm, 0.2f);
        Arrays.fill(pcm, 4 * SR, 5 * SR, 0.9f);
        return pcm;
    }

    private static DiarizedTranscript.Entry entry() {
        return new DiarizedTranscript.Entry("Firdaus", 0.0, 10.0,
                "one two three four PERMISSIBLE-LAH six seven eight nine ten");
    }

    @Test
    void carve_movesTheInterjectionWords_whenTheGuardAgrees() {
        // Raw annotation: Podcaster active 4.0-5.0s inside Firdaus's entry;
        // the audio there IS loud (Podcaster's voice) — guard passes.
        var raw = List.of(new SpeakerSegment(4.0, 5.0, 0));

        var out = InterjectionCarver.carve(List.of(entry()), raw, NAMES, pcm(),
                EVEN, SPLIT, VOICE, REFS);

        assertEquals(3, out.size(), "head + carved fragment + tail");
        assertEquals("Firdaus", out.get(0).speaker());
        assertEquals("Podcaster", out.get(1).speaker());
        assertTrue(out.get(1).text().contains("PERMISSIBLE-LAH"),
                "the word over the activation moves: " + out.get(1).text());
        assertEquals("Firdaus", out.get(2).speaker());
        assertFalse(out.get(0).text().contains("PERMISSIBLE-LAH"));
        assertFalse(out.get(2).text().contains("PERMISSIBLE-LAH"));
    }

    @Test
    void carve_guardRejects_whenTheAudioDoesNotBackTheClaim() {
        // Same activation window, but the audio stays quiet (Firdaus's own
        // voice) — an echo-only raw blip. The guard must refuse.
        var quiet = new float[10 * SR];
        Arrays.fill(quiet, 0.2f);
        var raw = List.of(new SpeakerSegment(4.0, 5.0, 0));

        var out = InterjectionCarver.carve(List.of(entry()), raw, NAMES, quiet,
                EVEN, SPLIT, VOICE, REFS);

        assertEquals(1, out.size(), "echo-only activation must not carve");
        assertEquals("Firdaus", out.get(0).speaker());
    }

    @Test
    void carve_ignoresActivations_outsideTheLengthWindow() {
        // 5s activation: a real turn, MSDD's territory, not a carve.
        var raw = List.of(new SpeakerSegment(2.0, 7.0, 0));
        var out = InterjectionCarver.carve(List.of(entry()), raw, NAMES, pcm(),
                EVEN, SPLIT, VOICE, REFS);
        assertEquals(1, out.size());
    }

    @Test
    void carve_neutralOnEmptyRawAnnotation() {
        var result = new DiarizationRouter.Result(
                List.of(new SpeakerSegment(0, 10, 1)), List.of());
        var out = InterjectionCarver.carve(List.of(entry()), result, NAMES, pcm());
        assertEquals(List.of(entry()), out, "no raw annotation: pass-through");
    }
}
