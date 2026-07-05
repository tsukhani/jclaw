import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizedTranscript;
import services.transcription.SpeakerSegment;
import services.transcription.WhisperJniTranscriber;

import java.util.List;

/**
 * JCLAW-556: merge + formatting layer of the diarization pipeline. Pure
 * functions — no models, no natives — so every branch is exercised here:
 * overlap-based speaker assignment (including the no-overlap nearest-span
 * fallback and the empty-diarization degenerate case), the consecutive-
 * speaker collapse in TXT, the SRT/VTT timestamp shapes, and the format
 * dispatcher.
 */
class DiarizedTranscriptTest extends UnitTest {

    private static WhisperJniTranscriber.Segment seg(long startMs, long endMs, String text) {
        return new WhisperJniTranscriber.Segment(startMs, endMs, text);
    }

    private static SpeakerSegment spk(double start, double end, int speaker) {
        return new SpeakerSegment(start, end, speaker);
    }

    @Test
    void merge_assignsSpeakerWithMaxOverlap() {
        var transcript = List.of(seg(0, 4000, " hello there"), seg(4000, 8000, " hi yourself"));
        var speakers = List.of(spk(0.0, 3.9, 0), spk(3.9, 8.0, 1));

        var entries = DiarizedTranscript.merge(transcript, speakers);

        assertEquals(2, entries.size());
        assertEquals("SPEAKER_00", entries.get(0).speaker());
        assertEquals("SPEAKER_01", entries.get(1).speaker());
        assertEquals("hello there", entries.get(0).text(), "whisper's leading space is stripped");
        assertEquals(0.0, entries.get(0).start(), 1e-9);
        assertEquals(4.0, entries.get(0).end(), 1e-9, "millisecond inputs surface as seconds");
    }

    @Test
    void merge_straddlingSegment_goesToLargerOverlap() {
        // Whisper segment 2s–6s straddles the 4s speaker boundary: 2s of
        // speaker 0 vs 2.5s of speaker 1 → speaker 1 wins.
        var transcript = List.of(seg(2000, 6500, "straddler"));
        var speakers = List.of(spk(0.0, 4.0, 0), spk(4.0, 9.0, 1));

        var entries = DiarizedTranscript.merge(transcript, speakers);

        assertEquals("SPEAKER_01", entries.get(0).speaker());
    }

    @Test
    void merge_noOverlap_borrowsNearestSpan() {
        // Whisper transcribed 10s–11s but the diarizer only marked speech at
        // 0–2s (speaker 0) and 12–14s (speaker 1). Nearest span is speaker 1.
        var transcript = List.of(seg(10_000, 11_000, "orphan"));
        var speakers = List.of(spk(0.0, 2.0, 0), spk(12.0, 14.0, 1));

        var entries = DiarizedTranscript.merge(transcript, speakers);

        assertEquals("SPEAKER_01", entries.get(0).speaker());
    }

    @Test
    void merge_emptyDiarization_defaultsToSpeakerZero() {
        var entries = DiarizedTranscript.merge(List.of(seg(0, 1000, "solo")), List.of());
        assertEquals("SPEAKER_00", entries.get(0).speaker());
    }

    @Test
    void merge_appliesEnrolledNames_withAnonymousFallback() {
        var transcript = List.of(seg(0, 2000, "hello"), seg(2000, 4000, "hi"));
        var speakers = List.of(spk(0.0, 2.0, 0), spk(2.0, 4.0, 1));

        var entries = DiarizedTranscript.merge(transcript, speakers, java.util.Map.of(0, "Alice"));

        assertEquals("Alice", entries.get(0).speaker(), "matched speaker gets the enrolled name");
        assertEquals("SPEAKER_01", entries.get(1).speaker(), "unmatched speaker keeps the anonymous label");
    }

    @Test
    void toText_collapsesConsecutiveSameSpeaker() {
        var entries = List.of(
                new DiarizedTranscript.Entry("SPEAKER_00", 0, 2, "Good morning."),
                new DiarizedTranscript.Entry("SPEAKER_00", 2, 4, "Thanks for joining."),
                new DiarizedTranscript.Entry("SPEAKER_01", 4, 6, "Happy to be here."));

        assertEquals("""
                SPEAKER_00: Good morning. Thanks for joining.
                SPEAKER_01: Happy to be here.""",
                DiarizedTranscript.toText(entries));
    }

    @Test
    void toSrt_formatsCuesWithCommaMillis() {
        var entries = List.of(
                new DiarizedTranscript.Entry("SPEAKER_00", 0.0, 2.5, "Hello."),
                new DiarizedTranscript.Entry("SPEAKER_01", 3661.25, 3662.0, "Over an hour in."));

        assertEquals("""
                1
                00:00:00,000 --> 00:00:02,500
                SPEAKER_00: Hello.

                2
                01:01:01,250 --> 01:01:02,000
                SPEAKER_01: Over an hour in.

                """,
                DiarizedTranscript.toSrt(entries));
    }

    @Test
    void toVtt_hasHeaderAndDotMillis() {
        var vtt = DiarizedTranscript.toVtt(List.of(
                new DiarizedTranscript.Entry("SPEAKER_00", 0.0, 1.5, "Hi.")));

        assertTrue(vtt.startsWith("WEBVTT\n\n"), "VTT files must open with the WEBVTT header");
        assertTrue(vtt.contains("00:00:00.000 --> 00:00:01.500"),
                "VTT uses a dot millisecond separator: " + vtt);
    }

    @Test
    void formatters_skipEmptyTextSegments() {
        var entries = List.of(
                new DiarizedTranscript.Entry("SPEAKER_00", 0, 1, ""),
                new DiarizedTranscript.Entry("SPEAKER_01", 1, 2, "Real content."));

        assertFalse(DiarizedTranscript.toText(entries).contains("SPEAKER_00"));
        assertTrue(DiarizedTranscript.toSrt(entries).startsWith("1\n00:00:01,000"),
                "SRT numbering must not burn an index on a skipped empty segment");
    }

    @Test
    void toJson_carriesAllFields() {
        var json = DiarizedTranscript.toJson(List.of(
                new DiarizedTranscript.Entry("SPEAKER_00", 0.5, 1.5, "hey")));

        assertTrue(json.contains("\"speaker\""), json);
        assertTrue(json.contains("\"SPEAKER_00\""), json);
        assertTrue(json.contains("\"text\""), json);
        assertTrue(json.contains("\"hey\""), json);
    }

    @Test
    void toText_showsEmotionTag_andSplitsCollapseOnEmotionChange() {
        // JCLAW-563: same speaker throughout, but the emotion flips mid-way —
        // the collapse must break so the label stays truthful per line.
        var entries = List.of(
                new DiarizedTranscript.Entry("SPEAKER_00", 0, 2, "Everything is fine.", "neutral"),
                new DiarizedTranscript.Entry("SPEAKER_00", 2, 4, "Totally fine.", "neutral"),
                new DiarizedTranscript.Entry("SPEAKER_00", 4, 6, "WHO BROKE THE BUILD?", "angry"));

        assertEquals("""
                SPEAKER_00 (neutral): Everything is fine. Totally fine.
                SPEAKER_00 (angry): WHO BROKE THE BUILD?""",
                DiarizedTranscript.toText(entries));
    }

    @Test
    void toText_withoutEmotion_keepsPlainSpeakerTag() {
        // Null emotion (analysis disabled/unavailable) renders exactly the
        // pre-JCLAW-563 shape — no empty parentheses, no format drift.
        var entries = List.of(new DiarizedTranscript.Entry("Alice", 0, 2, "Hello."));
        assertEquals("Alice: Hello.", DiarizedTranscript.toText(entries));
    }

    @Test
    void toJson_carriesEmotionField() {
        var json = DiarizedTranscript.toJson(List.of(
                new DiarizedTranscript.Entry("SPEAKER_00", 0.5, 1.5, "hey", "happy")));

        assertTrue(json.contains("\"emotion\""), json);
        assertTrue(json.contains("\"happy\""), json);
    }

    @Test
    void format_dispatchesCaseInsensitively_andRejectsUnknown() {
        var entries = List.of(new DiarizedTranscript.Entry("SPEAKER_00", 0, 1, "x"));

        assertTrue(DiarizedTranscript.format(entries, "SRT").isPresent());
        assertTrue(DiarizedTranscript.format(entries, "json").isPresent());
        assertTrue(DiarizedTranscript.format(entries, "vtt").isPresent());
        assertTrue(DiarizedTranscript.format(entries, "txt").isPresent());
        assertTrue(DiarizedTranscript.format(entries, "pdf").isEmpty());
        assertTrue(DiarizedTranscript.format(entries, null).isEmpty());
    }

    @Test
    void toText_rendersCrossTalkMarker_aloneAndWithEmotion() {
        var entries = java.util.List.of(
                new DiarizedTranscript.Entry("Podcaster", 0, 2, "Clean turn.", null, false),
                new DiarizedTranscript.Entry("Firdaus", 2, 4, "Contested turn.", null, true),
                new DiarizedTranscript.Entry("Firdaus", 4, 6, "Contested and angry.", "angry", true));

        var text = DiarizedTranscript.toText(entries);

        assertTrue(text.contains("Podcaster: Clean turn."), text);
        assertTrue(text.contains("Firdaus (cross-talk?): Contested turn."), text);
        assertTrue(text.contains("Firdaus (angry, cross-talk?): Contested and angry."), text);
    }

    @Test
    void merge_flagsSegmentsWithNoDiarizationSupport() {
        var transcript = java.util.List.of(
                new services.transcription.WhisperJniTranscriber.Segment(0, 2000, "Real speech."),
                new services.transcription.WhisperJniTranscriber.Segment(10000, 12000, "Thanks for watching!"));
        var speakers = java.util.List.of(
                new services.transcription.SpeakerSegment(0.0, 2.5, 0));

        var entries = DiarizedTranscript.merge(transcript, speakers);

        assertFalse(entries.get(0).noSpeakerEvidence(), "supported speech stays unflagged");
        assertTrue(entries.get(1).noSpeakerEvidence(),
                "a segment overlapping no diarized speech is a nearest-neighbor guess");
        assertTrue(DiarizedTranscript.toText(entries).contains("(no-speaker-detected?)"),
                DiarizedTranscript.toText(entries));
    }

    @Test
    void merge_emptySpeakerList_neverFlags() {
        var transcript = java.util.List.of(
                new services.transcription.WhisperJniTranscriber.Segment(0, 2000, "Solo voice note."));
        var entries = DiarizedTranscript.merge(transcript, java.util.List.of());
        assertFalse(entries.get(0).noSpeakerEvidence(),
                "no diarization at all means the single-voice reading, not a warning");
    }

    @Test
    void toText_rendersUnderSpeechQualifier() {
        var entries = java.util.List.of(
                new DiarizedTranscript.Entry("Firdaus", 1, 2, "Yeah.", null, false, true));
        assertTrue(DiarizedTranscript.toText(entries).contains("Firdaus (under-speech): Yeah."),
                DiarizedTranscript.toText(entries));
    }
}
