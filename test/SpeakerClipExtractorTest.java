import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.SherpaDiarizer;
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

    private static SherpaDiarizer.SpeakerSegment seg(double start, double end, int speaker) {
        return new SherpaDiarizer.SpeakerSegment(start, end, speaker);
    }

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
                new services.transcription.SherpaDiarizer.SpeakerSegment(0, 8, 0),    // longest
                new services.transcription.SherpaDiarizer.SpeakerSegment(20, 26, 0),  // second
                new services.transcription.SherpaDiarizer.SpeakerSegment(40, 43, 0),  // third
                new services.transcription.SherpaDiarizer.SpeakerSegment(50, 50.4, 0), // below min
                new services.transcription.SherpaDiarizer.SpeakerSegment(10, 18, 1)); // other speaker

        var refs = SpeakerClipExtractor.referenceClips(samples, segments, 0, 20.0, 5.0, 1.0);

        assertEquals(5 * SpeakerClipExtractor.SAMPLE_RATE, refs.get(0).length,
                "first clip is the 5s lineup cut");
        double total = refs.stream().mapToInt(r -> r.length).sum()
                / (double) SpeakerClipExtractor.SAMPLE_RATE;
        // Pure speech available: 8 + 6 + 3 = 17s (the 0.4s span is below min,
        // speaker 1's audio never contributes) — everything gets harvested.
        assertEquals(17.0, total, 0.1, "harvest takes all pure speech when under target");
        assertTrue(refs.size() >= 3, "spans plus remnants of the longest segment");
    }

    @Test
    void referenceClips_singleLongMonologue_reachesTargetViaRemnants() {
        var samples = new float[60 * SpeakerClipExtractor.SAMPLE_RATE];
        java.util.Arrays.fill(samples, 0.1f);
        var segments = java.util.List.of(
                new services.transcription.SherpaDiarizer.SpeakerSegment(5, 45, 0)); // one 40s span

        var refs = SpeakerClipExtractor.referenceClips(samples, segments, 0, 20.0, 5.0, 1.0);

        double total = refs.stream().mapToInt(r -> r.length).sum()
                / (double) SpeakerClipExtractor.SAMPLE_RATE;
        assertEquals(20.0, total, 0.1, "remnants around the lineup cut fill the 20s budget");
        assertEquals(5 * SpeakerClipExtractor.SAMPLE_RATE, refs.get(0).length);
    }

    @Test
    void referenceClips_firstClipMatchesTheLineupCut() {
        // The lineup clip is the mid-cut of the longest segment; the first
        // reference clip must be the identical cut so enroll(voice-N) files
        // the exact clip the operator listened to.
        var samples = new float[40 * SpeakerClipExtractor.SAMPLE_RATE];
        for (int i = 0; i < samples.length; i++) samples[i] = (float) Math.sin(i * 0.01);
        var segments = java.util.List.of(
                new services.transcription.SherpaDiarizer.SpeakerSegment(2, 12, 0),
                new services.transcription.SherpaDiarizer.SpeakerSegment(20, 24, 0));

        var lineup = SpeakerClipExtractor.extract(samples, segments, 5.0, 1.0);
        var refs = SpeakerClipExtractor.referenceClips(samples, segments, 0, 20.0, 5.0, 1.0);

        assertArrayEquals(lineup.get(0).samples(), refs.get(0), 0f);
    }

    @Test
    void purify_splitsSegmentsAroundPaddedOverlaps_andKeepsSpeaker() {
        var segments = java.util.List.of(
                new services.transcription.SherpaDiarizer.SpeakerSegment(0, 10, 0),
                new services.transcription.SherpaDiarizer.SpeakerSegment(12, 14, 1));
        // Overlap 4-6s inside speaker 0's span; pad 0.3s each side.
        var overlaps = java.util.List.<double[]>of(new double[]{4, 6});

        var pure = SpeakerClipExtractor.purify(segments, overlaps);

        assertEquals(3, pure.size());
        assertEquals(0.0, pure.get(0).start(), 1e-9);
        assertEquals(3.7, pure.get(0).end(), 1e-9);
        assertEquals(6.3, pure.get(1).start(), 1e-9);
        assertEquals(10.0, pure.get(1).end(), 1e-9);
        assertEquals(0, pure.get(1).speaker(), "sub-spans keep their speaker id");
        assertEquals(12.0, pure.get(2).start(), 1e-9, "untouched segment passes through");
    }

    @Test
    void purify_dropsFullyOverlappedSpans_andIsIdentityWithoutOverlaps() {
        var segments = java.util.List.of(
                new services.transcription.SherpaDiarizer.SpeakerSegment(5, 6, 0));
        assertTrue(SpeakerClipExtractor.purify(segments,
                        java.util.List.<double[]>of(new double[]{4.8, 6.1})).isEmpty(),
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
                new services.transcription.SherpaDiarizer.SpeakerSegment(0, 10, 0));
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
}
