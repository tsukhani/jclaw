package services.transcription;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static utils.GsonHolder.INSTANCE;

/**
 * Merges whisper transcript segments with sherpa speaker segments into a
 * speaker-attributed transcript, and renders it in the four JCLAW-556
 * output formats (JSON, TXT, SRT, VTT). Pure functions over the two
 * segment lists — no I/O, no native calls — so the merge and every
 * formatter are unit-testable without models on disk.
 */
public final class DiarizedTranscript {

    /** One speaker-attributed transcript segment; times in seconds. */
    public record Entry(String speaker, double start, double end, String text) {}

    private DiarizedTranscript() {}

    /**
     * Assign each whisper segment the speaker whose diarized span overlaps
     * it the most (ties resolve to the earliest-listed span). A segment
     * overlapping no span at all — whisper transcribed through a stretch
     * the diarizer called non-speech — borrows the temporally nearest
     * span's speaker rather than inventing an UNKNOWN label; with an empty
     * speaker list everything lands on SPEAKER_00, matching the
     * single-voice reading of "the diarizer found no boundaries".
     */
    public static List<Entry> merge(List<WhisperJniTranscriber.Segment> transcript,
                                    List<SherpaDiarizer.SpeakerSegment> speakers) {
        var entries = new ArrayList<Entry>(transcript.size());
        for (var seg : transcript) {
            double start = seg.startMs() / 1000.0;
            double end = seg.endMs() / 1000.0;
            entries.add(new Entry(speakerLabel(speakerFor(start, end, speakers)),
                    start, end, seg.text().strip()));
        }
        return entries;
    }

    private static int speakerFor(double start, double end, List<SherpaDiarizer.SpeakerSegment> speakers) {
        int best = 0;
        double bestOverlap = 0;
        double bestDistance = Double.MAX_VALUE;
        for (var s : speakers) {
            double overlap = Math.min(end, s.end()) - Math.max(start, s.start());
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = s.speaker();
            }
            if (bestOverlap <= 0) {
                // No overlapping span seen yet — track the nearest one as fallback.
                double distance = Math.max(s.start() - end, start - s.end());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = s.speaker();
                }
            }
        }
        return best;
    }

    static String speakerLabel(int speaker) {
        return "SPEAKER_%02d".formatted(speaker);
    }

    /** Render {@code entries} as {@code format} (case-insensitive: json,
     *  txt, srt, vtt). Empty for an unrecognised format name. */
    public static Optional<String> format(List<Entry> entries, String format) {
        if (format == null) return Optional.empty();
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "json" -> Optional.of(toJson(entries));
            case "txt" -> Optional.of(toText(entries));
            case "srt" -> Optional.of(toSrt(entries));
            case "vtt" -> Optional.of(toVtt(entries));
            default -> Optional.empty();
        };
    }

    public static String toJson(List<Entry> entries) {
        return INSTANCE.toJson(entries);
    }

    /** Conversation-style text: consecutive entries by the same speaker
     *  collapse into one "SPEAKER_NN: …" line. */
    public static String toText(List<Entry> entries) {
        var sb = new StringBuilder();
        String currentSpeaker = null;
        for (var e : entries) {
            if (e.text().isEmpty()) continue;
            if (e.speaker().equals(currentSpeaker)) {
                sb.append(' ').append(e.text());
            } else {
                if (currentSpeaker != null) sb.append('\n');
                sb.append(e.speaker()).append(": ").append(e.text());
                currentSpeaker = e.speaker();
            }
        }
        return sb.toString();
    }

    public static String toSrt(List<Entry> entries) {
        var sb = new StringBuilder();
        int cue = 1;
        for (var e : entries) {
            if (e.text().isEmpty()) continue;
            sb.append(cue++).append('\n')
              .append(timestamp(e.start(), ',')).append(" --> ").append(timestamp(e.end(), ','))
              .append('\n')
              .append(e.speaker()).append(": ").append(e.text())
              .append("\n\n");
        }
        return sb.toString();
    }

    public static String toVtt(List<Entry> entries) {
        var sb = new StringBuilder("WEBVTT\n\n");
        for (var e : entries) {
            if (e.text().isEmpty()) continue;
            sb.append(timestamp(e.start(), '.')).append(" --> ").append(timestamp(e.end(), '.'))
              .append('\n')
              .append(e.speaker()).append(": ").append(e.text())
              .append("\n\n");
        }
        return sb.toString();
    }

    /** {@code HH:MM:SS<sep>mmm} — SRT wants a comma before the millis, VTT a dot. */
    static String timestamp(double seconds, char millisSeparator) {
        long totalMs = Math.round(seconds * 1000);
        long h = totalMs / 3_600_000;
        long m = (totalMs % 3_600_000) / 60_000;
        long s = (totalMs % 60_000) / 1000;
        long ms = totalMs % 1000;
        return "%02d:%02d:%02d%c%03d".formatted(h, m, s, millisSeparator, ms);
    }
}
