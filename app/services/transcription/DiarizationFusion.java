package services.transcription;

import java.util.ArrayList;
import java.util.List;

/**
 * Fuses ASR segments (WHAT + WHEN) with diarization turns (WHO + WHEN) into a
 * speaker-attributed transcript. Each transcript segment inherits the speaker
 * of the turn it overlaps most in time; consecutive same-speaker segments
 * collapse into one line {@code "SPEAKER: text ..."}.
 *
 * <p>v1 is SEGMENT-level: a Whisper segment that straddles a speaker change is
 * attributed whole to its majority-overlap speaker. Word-level attribution
 * (forced alignment of the transcript, the WhisperX principle) is a later
 * phase — it sharpens boundaries but needs word timestamps the mlx engine
 * doesn't emit reliably (JCLAW-651).
 */
public final class DiarizationFusion {

    private DiarizationFusion() {}

    public static String fuse(List<WhisperTranscriber.Segment> segments,
                              List<DiarizeSidecarClient.Turn> turns) {
        var sb = new StringBuilder();
        String currentSpeaker = null;
        var buffer = new ArrayList<String>();
        for (var seg : segments) {
            var text = seg.text() == null ? "" : seg.text().strip();
            if (text.isEmpty()) continue;
            var speaker = speakerFor(seg.startMs(), seg.endMs(), turns);
            if (!speaker.equals(currentSpeaker)) {
                flush(sb, currentSpeaker, buffer);
                currentSpeaker = speaker;
                buffer.clear();
            }
            buffer.add(text);
        }
        flush(sb, currentSpeaker, buffer);
        return sb.toString();
    }

    private static void flush(StringBuilder sb, String speaker, List<String> buffer) {
        if (speaker == null || buffer.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(speaker).append(": ").append(String.join(" ", buffer));
    }

    /** Speaker of the turn with the most time-overlap with {@code [startMs,endMs)};
     *  the earliest such turn wins ties, {@code "?"} when no turn overlaps. */
    private static String speakerFor(long startMs, long endMs,
                                     List<DiarizeSidecarClient.Turn> turns) {
        String best = null;
        long bestOverlap = 0;
        for (var t : turns) {
            long overlap = Math.min(endMs, t.endMs()) - Math.max(startMs, t.startMs());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = t.speaker();
            }
        }
        return best != null ? best : "?";
    }
}
