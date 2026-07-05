package services.transcription;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** One speaker-attributed transcript segment; times in seconds.
     *  {@code emotion} is the JCLAW-563 acoustic label ({@code happy},
     *  {@code sad}, …), or null when the segment was too short to classify
     *  or emotion analysis is disabled/unavailable. {@code crossTalk} is
     *  the JCLAW-607 honesty marker: the overlap re-attribution pass
     *  confirmed the turn sits in genuine cross-talk but the separated-stem
     *  evidence could not decide the speaker — the label shown is the
     *  diarizer's best guess and a reviewer should re-listen. */
    public record Entry(String speaker, double start, double end, String text, String emotion,
                        boolean crossTalk, boolean underSpeech, boolean noSpeakerEvidence) {
        public Entry(String speaker, double start, double end, String text) {
            this(speaker, start, end, text, null, false, false, false);
        }

        public Entry(String speaker, double start, double end, String text, String emotion) {
            this(speaker, start, end, text, emotion, false, false, false);
        }

        public Entry(String speaker, double start, double end, String text, String emotion,
                     boolean crossTalk) {
            this(speaker, start, end, text, emotion, crossTalk, false, false);
        }

        public Entry(String speaker, double start, double end, String text, String emotion,
                     boolean crossTalk, boolean underSpeech) {
            this(speaker, start, end, text, emotion, crossTalk, underSpeech, false);
        }

        /** Copy with a different speaker label, all flags preserved. */
        public Entry withSpeaker(String newSpeaker) {
            return new Entry(newSpeaker, start, end, text, emotion, crossTalk,
                    underSpeech, noSpeakerEvidence);
        }
    }

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
                                    List<SpeakerSegment> speakers) {
        return merge(transcript, speakers, Map.of());
    }

    /** As {@link #merge(List, List)}, substituting enrolled display names
     *  (JCLAW-558) for the speaker indices present in {@code names};
     *  unmatched speakers keep their anonymous SPEAKER_NN label. */
    public static List<Entry> merge(List<WhisperJniTranscriber.Segment> transcript,
                                    List<SpeakerSegment> speakers,
                                    Map<Integer, String> names) {
        var entries = new ArrayList<Entry>(transcript.size());
        for (var seg : transcript) {
            double start = seg.startMs() / 1000.0;
            double end = seg.endMs() / 1000.0;
            int speaker = speakerFor(start, end, speakers);
            // JCLAW-622: a segment overlapping NO diarized speech is either
            // real speech the diarizer trimmed or a whisper hallucination on
            // silence/music — the nearest-speaker label is a guess and says
            // so instead of feigning confidence.
            boolean unsupported = !speakers.isEmpty() && !overlapsAny(start, end, speakers);
            entries.add(new Entry(names.getOrDefault(speaker, speakerLabel(speaker)),
                    start, end, seg.text().strip(), null, false, false, unsupported));
        }
        return entries;
    }

    private static boolean overlapsAny(double start, double end, List<SpeakerSegment> speakers) {
        for (var s : speakers) {
            if (Math.min(end, s.end()) - Math.max(start, s.start()) > 0) return true;
        }
        return false;
    }

    private static int speakerFor(double start, double end, List<SpeakerSegment> speakers) {
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
     *  collapse into one "SPEAKER_NN: …" line — or "SPEAKER_NN (happy): …"
     *  when the entry carries an emotion label (JCLAW-563); an emotion
     *  change mid-speaker starts a new line so the label stays truthful
     *  for every word after it. Undecidable overlap turns (JCLAW-607)
     *  render as "SPEAKER_NN (cross-talk?)" / "SPEAKER_NN (happy,
     *  cross-talk?)" so the uncertainty is visible where it matters. */
    public static String toText(List<Entry> entries) {
        var sb = new StringBuilder();
        String currentTag = null;
        for (var e : entries) {
            if (e.text().isEmpty()) continue;
            var qualifiers = new StringBuilder();
            if (e.emotion() != null) qualifiers.append(e.emotion());
            if (e.crossTalk()) {
                if (qualifiers.length() > 0) qualifiers.append(", ");
                qualifiers.append("cross-talk?");
            }
            if (e.underSpeech()) {
                if (qualifiers.length() > 0) qualifiers.append(", ");
                qualifiers.append("under-speech");
            }
            if (e.noSpeakerEvidence()) {
                if (qualifiers.length() > 0) qualifiers.append(", ");
                qualifiers.append("no-speaker-detected?");
            }
            var tag = qualifiers.length() == 0
                    ? e.speaker()
                    : "%s (%s)".formatted(e.speaker(), qualifiers);
            if (tag.equals(currentTag)) {
                sb.append(' ').append(e.text());
            } else {
                if (currentTag != null) sb.append('\n');
                sb.append(tag).append(": ").append(e.text());
                currentTag = tag;
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
