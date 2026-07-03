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
}
