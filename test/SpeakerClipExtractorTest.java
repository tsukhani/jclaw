import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.SpeakerSegment;
import services.transcription.SpeakerClipExtractor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * JCLAW-562: clip selection, lineup montage, and WAV encoding — all pure
 * sample math, exercised without audio files or natives.
 */
class SpeakerClipExtractorTest extends UnitTest {

    private static final int SR = SpeakerClipExtractor.SAMPLE_RATE;

    private static SpeakerSegment seg(double start, double end, int speaker) {
        return new SpeakerSegment(start, end, speaker);
    }

    /** Voice-discriminating fake embedder: loud fills (mean >= 0.05) embed
     *  as one voice, quiet fills as another — cosine 1 within a voice,
     *  0 across. Uniform fixtures therefore always pass the anchor gate. */
    private static final SpeakerClipExtractor.Embedder VOICE_EMBEDDER = samples -> {
        double sum = 0;
        for (float v : samples) sum += v;
        return sum / samples.length >= 0.05 ? new float[]{1, 0} : new float[]{0, 1};
    };

    /** Samples where sample[i] encodes the second it belongs to (i / SR),
     *  so a clip's provenance is checkable from its first value. */
    private static float[] rampSamples(double seconds) {
        var samples = new float[(int) (seconds * SR)];
        for (int i = 0; i < samples.length; i++) samples[i] = i / (float) SR / 1000f;
        return samples;
    }

    @Test
    void extract_picksLongestSegmentPerSpeaker_middleWindow() {
        var samples = rampSamples(60);
        // Speaker 0: 3s and 20s segments — the 20s one (t=20..40) must win,
        // and a 5s clip from its middle starts at 20 + (20-5)/2 = 27.5s.
        var segments = List.of(
                seg(0, 3, 0),
                seg(20, 40, 0),
                seg(45, 48, 1));

        var clips = SpeakerClipExtractor.extract(samples, segments, 5.0, 1.0);

        assertEquals(2, clips.size());
        var clip0 = clips.get(0);
        assertEquals("voice-1", clip0.label());
        assertEquals(0, clip0.speaker());
        assertEquals(27.5, clip0.start(), 0.01);
        assertEquals(5.0, clip0.duration(), 0.01);
        assertEquals(27.5f / 1000f, clip0.samples()[0], 1e-4,
                "clip samples must come from the middle of the longest segment");

        var clip1 = clips.get(1);
        assertEquals("voice-2", clip1.label());
        assertEquals(3.0, clip1.duration(), 0.01, "shorter-than-target segments are taken whole");
    }

    @Test
    void extract_skipsSpeakersWithOnlySubMinimumSegments() {
        var samples = rampSamples(20);
        var segments = List.of(
                seg(0, 0.5, 0),   // too short — skipped
                seg(2, 9, 1));

        var clips = SpeakerClipExtractor.extract(samples, segments, 5.0, 1.0);

        assertEquals(1, clips.size());
        assertEquals(1, clips.get(0).speaker());
        assertEquals("voice-1", clips.get(0).label(), "labels renumber over the kept speakers");
    }

    @Test
    void extract_clampsSegmentsBeyondSampleEnd() {
        var samples = rampSamples(5);
        // Diarization end beyond the actual audio must not overflow.
        var clips = SpeakerClipExtractor.extract(samples, List.of(seg(2, 30, 0)), 5.0, 1.0);
        assertEquals(1, clips.size());
        assertTrue(clips.get(0).samples().length <= samples.length);
    }

    @Test
    void toWavPcm16_writesValidHeader() {
        var wav = SpeakerClipExtractor.toWavPcm16(new float[SR]); // 1s silence

        assertEquals(44 + SR * 2, wav.length);
        var buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        var riff = new byte[4];
        buf.get(riff);
        assertEquals("RIFF", new String(riff));
        assertEquals(36 + SR * 2, buf.getInt(), "RIFF chunk size");
        buf.position(22);
        assertEquals(1, buf.getShort(), "mono");
        assertEquals(SR, buf.getInt(), "sample rate");
        buf.position(40);
        assertEquals(SR * 2, buf.getInt(), "data chunk size");
    }

    @Test
    void referenceClips_harvestUntilTargetTotal_acrossSpansAndRemnants() {
        var samples = new float[60 * SpeakerClipExtractor.SAMPLE_RATE];
        for (int i = 0; i < samples.length; i++) samples[i] = (i % 100) / 100f;
        var segments = java.util.List.of(
                new services.transcription.SpeakerSegment(0, 8, 0),    // longest
                new services.transcription.SpeakerSegment(20, 26, 0),  // second
                new services.transcription.SpeakerSegment(40, 43, 0),  // third
                new services.transcription.SpeakerSegment(50, 50.4, 0), // below min
                new services.transcription.SpeakerSegment(10, 18, 1)); // other speaker

        var refs = SpeakerClipExtractor.referenceClips(samples, segments, 0, 20.0, 5.0, 1.0, VOICE_EMBEDDER);

        assertEquals(5 * SpeakerClipExtractor.SAMPLE_RATE, refs.get(0).length,
                "first clip is the 5s lineup cut");
        double total = refs.stream().mapToInt(r -> r.length).sum()
                / (double) SpeakerClipExtractor.SAMPLE_RATE;
        // Harvestable (5s pieces, 2s piece floor): 5s lineup + the 6s span
        // (5s + a dropped 1s tail) + the 3s span = 13s. The 8s span's 1.5s
        // remnants and the 0.4s span fall under the 2s piece floor;
        // speaker 1's audio never contributes.
        assertEquals(13.0, total, 0.1, "pieces cap at 5s; sub-2s pieces dropped");
        assertEquals(3, refs.size());
        for (var r : refs) {
            assertTrue(r.length <= 5 * SpeakerClipExtractor.SAMPLE_RATE + 1,
                    "no reference clip may exceed the 5s cap");
        }
    }

    @Test
    void referenceClips_singleLongMonologue_reachesTargetViaRemnants() {
        var samples = new float[60 * SpeakerClipExtractor.SAMPLE_RATE];
        java.util.Arrays.fill(samples, 0.1f);
        var segments = java.util.List.of(
                new services.transcription.SpeakerSegment(5, 45, 0)); // one 40s span

        var refs = SpeakerClipExtractor.referenceClips(samples, segments, 0, 20.0, 5.0, 1.0, VOICE_EMBEDDER);

        double total = refs.stream().mapToInt(r -> r.length).sum()
                / (double) SpeakerClipExtractor.SAMPLE_RATE;
        assertEquals(20.0, total, 0.3, "remnants around the lineup cut fill the 20s budget");
        assertEquals(5 * SpeakerClipExtractor.SAMPLE_RATE, refs.get(0).length);
        for (var r : refs) {
            assertTrue(r.length <= 5 * SpeakerClipExtractor.SAMPLE_RATE + 1,
                    "budget reached through multiple 5s-capped pieces, never one long clip");
        }
    }

    @Test
    void referenceClips_firstClipMatchesTheLineupCut() {
        // The lineup clip is the mid-cut of the longest segment; the first
        // reference clip must be the identical cut so enroll(voice-N) files
        // the exact clip the operator listened to.
        var samples = new float[40 * SpeakerClipExtractor.SAMPLE_RATE];
        for (int i = 0; i < samples.length; i++) samples[i] = (float) Math.sin(i * 0.01);
        var segments = java.util.List.of(
                new services.transcription.SpeakerSegment(2, 12, 0),
                new services.transcription.SpeakerSegment(20, 24, 0));

        var lineup = SpeakerClipExtractor.extract(samples, segments, 5.0, 1.0);
        var refs = SpeakerClipExtractor.referenceClips(samples, segments, 0, 20.0, 5.0, 1.0, VOICE_EMBEDDER);

        assertArrayEquals(lineup.get(0).samples(), refs.get(0), 0f);
    }

    @Test
    void purify_splitsSegmentsAroundPaddedOverlaps_andKeepsSpeaker() {
        var segments = java.util.List.of(
                new services.transcription.SpeakerSegment(0, 10, 0),
                new services.transcription.SpeakerSegment(12, 14, 1));
        // Overlap 4-6s inside speaker 0's span; pad 0.75s each side.
        var overlaps = java.util.List.<double[]>of(new double[]{4, 6});

        var pure = SpeakerClipExtractor.purify(segments, overlaps);

        assertEquals(3, pure.size());
        assertEquals(0.0, pure.get(0).start(), 1e-9);
        assertEquals(3.25, pure.get(0).end(), 1e-9);
        assertEquals(6.75, pure.get(1).start(), 1e-9);
        assertEquals(10.0, pure.get(1).end(), 1e-9);
        assertEquals(0, pure.get(1).speaker(), "sub-spans keep their speaker id");
        assertEquals(12.0, pure.get(2).start(), 1e-9, "untouched segment passes through");
    }

    @Test
    void purify_dropsFullyOverlappedSpans_andIsIdentityWithoutOverlaps() {
        var segments = java.util.List.of(
                new services.transcription.SpeakerSegment(5, 6, 0));
        assertTrue(SpeakerClipExtractor.purify(segments,
                        java.util.List.<double[]>of(new double[]{4.9, 6.0})).isEmpty(),
                "a span living entirely inside cross-talk yields nothing");
        assertEquals(segments, SpeakerClipExtractor.purify(segments, java.util.List.of()),
                "no overlap data (sherpa path) must be the identity");
    }

    @Test
    void extract_prefersShorterPureClip_overContaminatedFiveSeconds() {
        // Speaker 0 talks 0-10s but 3-7s is cross-talk: the longest pure span
        // is 7.3-10s (2.7s) — the clip must shrink rather than include it.
        var samples = new float[12 * SpeakerClipExtractor.SAMPLE_RATE];
        java.util.Arrays.fill(samples, 0.1f);
        var segments = java.util.List.of(
                new services.transcription.SpeakerSegment(0, 10, 0));
        var pure = SpeakerClipExtractor.purify(segments,
                java.util.List.<double[]>of(new double[]{3, 7}));

        var clips = SpeakerClipExtractor.extract(samples, pure, 5.0, 1.0);

        assertEquals(1, clips.size());
        assertTrue(clips.get(0).duration() < 5.0, "clip shrinks to the pure span");
        double clipStart = clips.get(0).start();
        double clipEnd = clipStart + clips.get(0).duration();
        assertTrue(clipEnd <= 2.7 + 1e-6 || clipStart >= 7.3 - 1e-6,
                "clip lies wholly inside a pure span: " + clipStart + "-" + clipEnd);
    }

    @Test
    void referenceClips_rejectsCandidates_whoseVoiceprintFailsTheAnchorGate() {
        // Speaker 0's longest span (0-10s) is genuinely their voice (loud
        // fill); the second "pure" span (20-26s) actually carries the OTHER
        // voice (quiet fill) — undetected bleed. The gate must reject it.
        var samples = new float[30 * SR];
        java.util.Arrays.fill(samples, 0, 10 * SR, 0.8f);
        java.util.Arrays.fill(samples, 20 * SR, 26 * SR, 0.001f);
        var segments = java.util.List.of(seg(0, 10, 0), seg(20, 26, 0));

        var refs = SpeakerClipExtractor.referenceClips(samples, segments, 0, 20.0, 5.0, 1.0, VOICE_EMBEDDER);

        double total = refs.stream().mapToInt(r -> r.length).sum() / (double) SR;
        assertEquals(10.0, total, 0.1,
                "anchor plus the two 2.5s pure remnants survive; every piece of "
                        + "the bled span is rejected by the anchor gate");
        for (var r : refs) {
            double mean = 0;
            for (float v : r) mean += v;
            assertTrue(mean / r.length >= 0.05, "no rejected-span audio in the set");
        }
    }

    @Test
    void matchesAnchor_failsHalfBledClips() {
        var anchorClip = new float[2 * SR];
        java.util.Arrays.fill(anchorClip, 0.8f);
        var anchor = VOICE_EMBEDDER.embed(anchorClip);

        var halfBled = new float[4 * SR];
        java.util.Arrays.fill(halfBled, 0, 2 * SR, 0.8f);   // speaker's voice
        java.util.Arrays.fill(halfBled, 2 * SR, 4 * SR, 0.001f); // other voice
        assertFalse(SpeakerClipExtractor.matchesAnchor(halfBled, anchor, VOICE_EMBEDDER),
                "a clip whose second half is another voice must fail");

        var clean = new float[4 * SR];
        java.util.Arrays.fill(clean, 0.8f);
        assertTrue(SpeakerClipExtractor.matchesAnchor(clean, anchor, VOICE_EMBEDDER));
    }
}
