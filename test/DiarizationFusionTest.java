import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizationFusion;
import services.transcription.DiarizeSidecarClient.Emotion;
import services.transcription.DiarizeSidecarClient.Turn;
import services.transcription.WhisperTranscriber.Segment;

import java.util.List;

/**
 * JCLAW-565 revival: segment-level fusion of ASR text with pyannote turns.
 * Each transcript segment inherits the majority-overlap speaker; consecutive
 * same-speaker segments collapse into one line.
 */
class DiarizationFusionTest extends UnitTest {

    @Test
    void attributesEachSegmentToOverlappingSpeaker() {
        var segments = List.of(
                new Segment(0, 2000, "hello there"),
                new Segment(2000, 4000, "hi back"));
        var turns = List.of(
                new Turn(0, 2000, "SPEAKER_00"),
                new Turn(2000, 4000, "SPEAKER_01"));

        assertEquals("SPEAKER_00: hello there\nSPEAKER_01: hi back",
                DiarizationFusion.fuse(segments, turns));
    }

    @Test
    void groupsConsecutiveSameSpeakerIntoOneLine() {
        var segments = List.of(
                new Segment(0, 1000, "one"),
                new Segment(1000, 2000, "two"),
                new Segment(2000, 3000, "three"));
        var turns = List.of(new Turn(0, 3000, "SPEAKER_00"));

        assertEquals("SPEAKER_00: one two three",
                DiarizationFusion.fuse(segments, turns));
    }

    @Test
    void majorityOverlapWinsWhenSegmentStraddlesTurns() {
        // Segment [0,1000) overlaps SPEAKER_00 for 800ms, SPEAKER_01 for 200ms.
        var segments = List.of(new Segment(0, 1000, "mostly mine"));
        var turns = List.of(
                new Turn(0, 800, "SPEAKER_00"),
                new Turn(800, 2000, "SPEAKER_01"));

        assertEquals("SPEAKER_00: mostly mine",
                DiarizationFusion.fuse(segments, turns));
    }

    @Test
    void segmentWithNoOverlappingTurnIsMarkedUnknown() {
        var segments = List.of(new Segment(5000, 6000, "orphan"));
        var turns = List.of(new Turn(0, 1000, "SPEAKER_00"));

        assertEquals("?: orphan", DiarizationFusion.fuse(segments, turns));
    }

    @Test
    void emitsEmotionLabelAndSplitsLineWhenEmotionChanges() {
        var segments = List.of(
                new Segment(0, 1000, "calm bit"),
                new Segment(1000, 2000, "then heated"));
        var turns = List.of(
                new Turn(0, 1000, "SPEAKER_00", new Emotion("neutral", 0.8, 0.5, 0.4, 0.5)),
                new Turn(1000, 2000, "SPEAKER_00", new Emotion("angry", 0.7, 0.4, 0.85, 0.8)));

        // Same speaker, but the emotion change starts a new line — one delivery per line.
        assertEquals("SPEAKER_00 (neutral): calm bit\nSPEAKER_00 (angry): then heated",
                DiarizationFusion.fuse(segments, turns));
    }

    @Test
    void skipsBlankSegmentsAndHandlesEmptyInput() {
        assertEquals("", DiarizationFusion.fuse(List.of(), List.of()));
        assertEquals("", DiarizationFusion.fuse(
                List.of(new Segment(0, 1000, "   ")),
                List.of(new Turn(0, 1000, "SPEAKER_00"))));
    }
}
