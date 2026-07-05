package services.transcription;

import play.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Word-level speaker attribution (JCLAW-603). Whisper segments are the
 * atomic unit of the diarized transcript, so a segment that spans a speaker
 * change hands ALL its words to one speaker — the residual error class of
 * the JCLAW-565 benchmark (every misattribution sat in rapid exchanges;
 * turns over 4s were error-free). This pass splits exactly those segments:
 * for each whisper segment whose time span crosses a speaker-change
 * boundary, {@link CtcForcedAligner} recovers when each word was spoken and
 * the segment is cut at the boundary between words.
 *
 * <p>Self-gating and best-effort: segments that cross no boundary pass
 * through untouched (byte-identical output for the common case, and the
 * alignment model isn't even loaded), and any alignment failure keeps the
 * original segment rather than risking the transcript. Backend-agnostic —
 * boundaries come from the pyannote sidecar diarization.
 */
public final class SegmentWordSplitter {

    /** Ignore boundaries within this margin of a segment edge — max-overlap
     *  attribution already handles edge jitter; splitting there would just
     *  shave fragments off turns. */
    static final double EDGE_MARGIN_SECONDS = 0.3;

    /** Test seam for the alignment call: normalized words in, one absolute
     *  {@code [startSec, endSec]} per word out. */
    @FunctionalInterface
    public interface WordAligner {
        List<double[]> align(double startSeconds, double endSeconds, List<String> normalizedWords);
    }

    private SegmentWordSplitter() {}

    /**
     * Split boundary-straddling whisper segments using the CTC aligner over
     * the recording's audio. Decodes the audio lazily — recordings whose
     * segments cross no speaker change never pay for ffmpeg or the model.
     */
    public static List<WhisperJniTranscriber.Segment> split(
            List<WhisperJniTranscriber.Segment> transcript,
            List<SpeakerSegment> speakers,
            Path audioFile) {
        var boundaries = speakerChangeBoundaries(speakers);
        if (boundaries.isEmpty() || transcript.stream().noneMatch(
                s -> !interiorBoundaries(s, boundaries).isEmpty())) {
            return transcript;
        }
        return split(transcript, speakers, WhisperJniTranscriber.ffmpegToPcmF32(audioFile));
    }

    /** Lazy-decode variant (JCLAW-640): the supplier is consulted only when
     *  a boundary-straddling segment actually exists, preserving this
     *  stage's "no straddle → no ffmpeg, no model" contract while letting
     *  the pipeline share one decoded array across stages. */
    public static List<WhisperJniTranscriber.Segment> split(
            List<WhisperJniTranscriber.Segment> transcript,
            List<SpeakerSegment> speakers,
            java.util.function.Supplier<float[]> samplesSupplier) {
        var boundaries = speakerChangeBoundaries(speakers);
        if (boundaries.isEmpty() || transcript.stream().noneMatch(
                s -> !interiorBoundaries(s, boundaries).isEmpty())) {
            return transcript;
        }
        return split(transcript, speakers, samplesSupplier.get());
    }

    /** As above with pre-decoded PCM (JCLAW-640). Callers keep the laziness
     *  contract: only decode when a straddling segment exists. */
    public static List<WhisperJniTranscriber.Segment> split(
            List<WhisperJniTranscriber.Segment> transcript,
            List<SpeakerSegment> speakers,
            float[] samples) {
        return split(transcript, speakers, (start, end, words) -> CtcForcedAligner.alignWords(
                samples,
                (int) Math.clamp(Math.round(start * 16_000), 0, samples.length),
                (int) Math.clamp(Math.round(end * 16_000), 0, samples.length),
                words));
    }

    /** Testable core: same splitting logic with an injected aligner. */
    public static List<WhisperJniTranscriber.Segment> split(
            List<WhisperJniTranscriber.Segment> transcript,
            List<SpeakerSegment> speakers,
            WordAligner aligner) {
        var boundaries = speakerChangeBoundaries(speakers);
        if (boundaries.isEmpty()) return transcript;

        var out = new ArrayList<WhisperJniTranscriber.Segment>(transcript.size());
        for (var segment : transcript) {
            var interior = interiorBoundaries(segment, boundaries);
            if (interior.isEmpty()) {
                out.add(segment);
                continue;
            }
            try {
                out.addAll(splitOne(segment, interior, aligner));
            } catch (RuntimeException e) {
                // Best-effort: an unalignable segment keeps whole-segment
                // attribution — same failure posture as the emotion layer.
                Logger.warn("SegmentWordSplitter: keeping segment un-split (%s)", e.getMessage());
                out.add(segment);
            }
        }
        return out;
    }

    /** Times where the diarizer says the active speaker changes: midpoint
     *  between consecutive spans with different speaker indices. */
    public static List<Double> speakerChangeBoundaries(List<SpeakerSegment> speakers) {
        var sorted = speakers.stream()
                .sorted(java.util.Comparator.comparingDouble(SpeakerSegment::start))
                .toList();
        var boundaries = new ArrayList<Double>();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).speaker() != sorted.get(i - 1).speaker()) {
                boundaries.add((sorted.get(i - 1).end() + sorted.get(i).start()) / 2.0);
            }
        }
        return boundaries;
    }

    private static List<Double> interiorBoundaries(
            WhisperJniTranscriber.Segment segment, List<Double> boundaries) {
        double start = segment.startMs() / 1000.0 + EDGE_MARGIN_SECONDS;
        double end = segment.endMs() / 1000.0 - EDGE_MARGIN_SECONDS;
        return boundaries.stream().filter(b -> b > start && b < end).toList();
    }

    private static List<WhisperJniTranscriber.Segment> splitOne(
            WhisperJniTranscriber.Segment segment, List<Double> interior, WordAligner aligner) {
        var originalWords = List.of(segment.text().strip().split("\\s+"));
        var groups = groupAlignableWords(originalWords);
        if (groups.size() < 2) return List.of(segment); // nothing to cut between

        var normalized = groups.stream().map(WordGroup::normalized).toList();
        var times = aligner.align(segment.startMs() / 1000.0, segment.endMs() / 1000.0, normalized);

        // Partition word groups at each boundary by word midpoint.
        var parts = new ArrayList<List<WordGroup>>();
        var current = new ArrayList<WordGroup>();
        int b = 0;
        for (int i = 0; i < groups.size(); i++) {
            double mid = (times.get(i)[0] + times.get(i)[1]) / 2.0;
            while (b < interior.size() && mid > interior.get(b)) {
                if (!current.isEmpty()) {
                    parts.add(current);
                    current = new ArrayList<>();
                }
                b++;
            }
            var group = groups.get(i);
            group.start = times.get(i)[0];
            group.end = times.get(i)[1];
            current.add(group);
        }
        parts.add(current);
        if (parts.size() < 2) return List.of(segment); // all words fell on one side
        // Flicker guard: a part with a single word is far more likely a
        // spurious diarizer micro-span than a real interjection (live case:
        // a 0.5s "You" flipped inside a monologue) — real one-word
        // interjections arrive as their own whisper segments and never
        // reach this splitter. Keep whole-segment attribution instead.
        if (parts.stream().anyMatch(p -> p.size() < 2)) return List.of(segment);

        var result = new ArrayList<WhisperJniTranscriber.Segment>(parts.size());
        for (int p = 0; p < parts.size(); p++) {
            var part = parts.get(p);
            // Outer edges keep the original segment bounds; interior cuts use
            // the aligned word times so the diarizer's overlap assignment
            // sees each part where its words actually are.
            long startMs = p == 0 ? segment.startMs() : Math.round(part.get(0).start * 1000);
            long endMs = p == parts.size() - 1 ? segment.endMs()
                    : Math.round(part.get(part.size() - 1).end * 1000);
            var text = String.join(" ", part.stream().map(WordGroup::original).toList());
            result.add(new WhisperJniTranscriber.Segment(startMs, endMs, " " + text));
        }
        Logger.info("SegmentWordSplitter: split \"%s\" into %d parts at speaker boundaries",
                truncate(segment.text()), result.size());
        return result;
    }

    /**
     * Pair each original word with its alignable normalization; words that
     * normalize to nothing (pure punctuation/digits) fold into the previous
     * group (or the next, at the start) so the aligner's word count always
     * matches and no original text is lost.
     */
    public static List<WordGroup> groupAlignableWords(List<String> originalWords) {
        var groups = new ArrayList<WordGroup>();
        var pendingPrefix = new StringBuilder();
        for (var word : originalWords) {
            var normalized = CtcForcedAligner.normalizeWord(word);
            if (normalized.isEmpty()) {
                if (groups.isEmpty()) {
                    pendingPrefix.append(pendingPrefix.isEmpty() ? "" : " ").append(word);
                } else {
                    var last = groups.get(groups.size() - 1);
                    last.originalText = last.originalText + " " + word;
                }
            } else {
                var original = pendingPrefix.isEmpty() ? word : pendingPrefix + " " + word;
                pendingPrefix.setLength(0);
                groups.add(new WordGroup(original, normalized));
            }
        }
        return groups;
    }

    /** One alignable word plus any un-alignable neighbors folded into it. */
    public static final class WordGroup {
        private String originalText;
        private final String normalized;
        private double start;
        private double end;

        WordGroup(String originalText, String normalized) {
            this.originalText = originalText;
            this.normalized = normalized;
        }

        public String original() { return originalText; }
        public String normalized() { return normalized; }
    }

    private static String truncate(String s) {
        var t = s.strip();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }
}
