package services.transcription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

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
        String curSpeaker = null;
        String curEmotion = null;
        var buffer = new ArrayList<String>();
        for (var seg : segments) {
            var text = seg.text() == null ? "" : seg.text().strip();
            if (text.isEmpty()) continue;
            var turn = turnFor(seg.startMs(), seg.endMs(), turns);
            var speaker = turn != null ? turn.speaker() : "?";
            var emotion = turn != null && turn.emotion() != null ? turn.emotion().label() : null;
            // A new line whenever the speaker OR the emotion changes — so a turn
            // carries one delivery, matching the cloud "(delivery)" line shape.
            if (!speaker.equals(curSpeaker) || !Objects.equals(emotion, curEmotion)) {
                flush(sb, curSpeaker, curEmotion, buffer);
                curSpeaker = speaker;
                curEmotion = emotion;
                buffer.clear();
            }
            buffer.add(text);
        }
        flush(sb, curSpeaker, curEmotion, buffer);
        return sb.toString();
    }

    private static void flush(StringBuilder sb, String speaker, String emotion, List<String> buffer) {
        if (speaker == null || buffer.isEmpty()) return;
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(speaker);
        if (emotion != null) sb.append(" (").append(emotion).append(')');
        sb.append(": ").append(String.join(" ", buffer));
    }

    /** The turn with the most time-overlap with {@code [startMs,endMs)}; the
     *  earliest such turn wins ties, null when no turn overlaps. */
    private static DiarizeSidecarClient.Turn turnFor(long startMs, long endMs,
                                                     List<DiarizeSidecarClient.Turn> turns) {
        DiarizeSidecarClient.Turn best = null;
        long bestOverlap = 0;
        for (var t : turns) {
            long overlap = Math.min(endMs, t.endMs()) - Math.max(startMs, t.startMs());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = t;
            }
        }
        return best;
    }

    /**
     * Whether the diarization looks degenerate — one speaker swallowing a
     * multi-turn recording. This is the failure mode on short/narrowband audio
     * with similar voices: acoustic separation can't split them, so everything
     * clusters onto one label. The caller warns the agent to attribute
     * speakers from conversational context instead of trusting the labels.
     * Fewer than 3 turns is too little to judge (a real short monologue).
     */
    public static boolean isDegenerate(List<DiarizeSidecarClient.Turn> turns) {
        if (turns.size() < 3) return false;
        var perSpeaker = new HashMap<String, Integer>();
        for (var t : turns) perSpeaker.merge(t.speaker(), 1, Integer::sum);
        if (perSpeaker.size() <= 1) return true;
        long max = perSpeaker.values().stream().mapToLong(Integer::longValue).max().orElse(0);
        return max >= turns.size() * 0.85;  // one speaker dominates ≥85% of turns
    }
}
