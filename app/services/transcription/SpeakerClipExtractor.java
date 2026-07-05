package services.transcription;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cuts one short reference clip per diarized speaker out of a recording
 * (JCLAW-562) — the raw material for the "who is voice N?" enrollment flow:
 * the operator uploads any multi-speaker recording, listens to the lineup,
 * and names the voices, instead of having to produce a clean per-person
 * sample.
 *
 * <p>Pure sample-array functions — no I/O, no natives — so clip selection
 * and the WAV encoding are unit-testable. All audio is the pipeline-wide
 * shape: PCM float mono at 16 kHz.
 */
public final class SpeakerClipExtractor {

    public static final int SAMPLE_RATE = 16_000;

    /**
     * One extracted reference clip.
     *
     * @param label   stable handle for the enrollment follow-up ({@code voice-1}, …)
     * @param speaker diarized speaker index the clip belongs to
     * @param start   where the clip begins in the source recording, seconds
     * @param samples the clip's PCM float samples
     */
    public record Clip(String label, int speaker, double start, float[] samples) {
        public double duration() {
            return samples.length / (double) SAMPLE_RATE;
        }
    }

    /** Padding subtracted around each overlap region when purifying —
     *  detected overlap boundaries are approximate; a margin keeps the
     *  clip clear of the other voice's onset/tail. */
    public static final double OVERLAP_PAD_SECONDS = 0.3;

    private SpeakerClipExtractor() {}

    /**
     * Remove (padded) overlap regions from every segment (JCLAW-609),
     * splitting spans into PURE sub-segments that keep their speaker id.
     * Exclusive-mode diarization absorbs cross-talk into the dominant
     * speaker's span, so mid-segment cuts alone can still capture the other
     * voice bleeding through — enrollment clips must come from spans where
     * only one person was speaking. With no overlap data (sherpa path) this
     * is the identity.
     */
    public static List<SherpaDiarizer.SpeakerSegment> purify(
            List<SherpaDiarizer.SpeakerSegment> segments, List<double[]> overlaps) {
        if (overlaps.isEmpty()) return segments;
        var pure = new ArrayList<SherpaDiarizer.SpeakerSegment>();
        for (var seg : segments) {
            var spans = new ArrayList<double[]>();
            spans.add(new double[]{seg.start(), seg.end()});
            for (var region : overlaps) {
                double lo = region[0] - OVERLAP_PAD_SECONDS;
                double hi = region[1] + OVERLAP_PAD_SECONDS;
                var next = new ArrayList<double[]>();
                for (var span : spans) {
                    if (hi <= span[0] || lo >= span[1]) {
                        next.add(span);              // no intersection
                        continue;
                    }
                    if (span[0] < lo) next.add(new double[]{span[0], lo});
                    if (hi < span[1]) next.add(new double[]{hi, span[1]});
                }
                spans = next;
            }
            for (var span : spans) {
                pure.add(new SherpaDiarizer.SpeakerSegment(span[0], span[1], seg.speaker()));
            }
        }
        return pure;
    }

    /** As {@link #extract(float[], List, double, double)}, decoding the audio
     *  file first (same ffmpeg path as the rest of the pipeline). */
    public static List<Clip> extract(Path audioFile, List<SherpaDiarizer.SpeakerSegment> segments,
                                     double targetSeconds, double minSeconds) {
        return extract(WhisperJniTranscriber.ffmpegToPcmF32(audioFile), segments, targetSeconds, minSeconds);
    }

    /**
     * One clip per distinct speaker: up to {@code targetSeconds} cut from the
     * <b>middle</b> of that speaker's longest diarized segment (mid-speech
     * avoids the turn-boundary bleed both diarization pipelines exhibit).
     * Speakers whose longest segment is shorter than {@code minSeconds} are
     * skipped — sub-second snippets make unreliable voice references.
     * Labels number the clips {@code voice-1..N} in speaker-index order.
     */
    public static List<Clip> extract(float[] samples, List<SherpaDiarizer.SpeakerSegment> segments,
                                     double targetSeconds, double minSeconds) {
        Map<Integer, SherpaDiarizer.SpeakerSegment> longest = new TreeMap<>();
        for (var s : segments) {
            var current = longest.get(s.speaker());
            if (current == null || s.end() - s.start() > current.end() - current.start()) {
                longest.put(s.speaker(), s);
            }
        }

        var clips = new ArrayList<Clip>();
        int n = 1;
        for (var e : longest.entrySet()) {
            var seg = e.getValue();
            double segLength = seg.end() - seg.start();
            if (segLength < minSeconds) continue;
            double duration = Math.min(segLength, targetSeconds);
            double start = seg.start() + (segLength - duration) / 2;
            int durSamples = (int) Math.round(duration * SAMPLE_RATE);
            // Slide the window back rather than dropping the speaker when the
            // diarized times overrun the decoded audio (clock drift, container
            // duration lies) — some clip beats no clip.
            int from = Math.clamp(Math.round(start * SAMPLE_RATE), 0,
                    Math.max(0, samples.length - durSamples));
            int to = Math.min(from + durSamples, samples.length);
            if (to <= from) continue;
            clips.add(new Clip("voice-" + n++, e.getKey(), start,
                    Arrays.copyOfRange(samples, from, to)));
        }
        return clips;
    }

    /**
     * Reference clips for one speaker (JCLAW-606, duration-driven since
     * JCLAW-609): the first clip is the 5s-style lineup cut (identical to
     * what {@link #extract} stages, so the operator enrolls exactly the
     * audio they identified), then additional clips are harvested until the
     * running total reaches {@code targetTotalSeconds} of the speaker's
     * PURE speech — or it runs out. Harvest candidates are the speaker's
     * other segments plus the remnants of the longest segment to the left
     * and right of the lineup window (pure by construction), longest first;
     * each clip is centered in its span and sized to the remaining need, so
     * a single long monologue can satisfy the whole budget. Callers pass
     * purified segments ({@link #purify}); with 20s of references this
     * matches the weight of the in-recording centroids the matcher builds.
     */
    public static List<float[]> referenceClips(Path audioFile,
                                               List<SherpaDiarizer.SpeakerSegment> segments,
                                               int speaker, double targetTotalSeconds,
                                               double lineupSeconds, double minSeconds) {
        return referenceClips(WhisperJniTranscriber.ffmpegToPcmF32(audioFile),
                segments, speaker, targetTotalSeconds, lineupSeconds, minSeconds);
    }

    public static List<float[]> referenceClips(float[] samples,
                                               List<SherpaDiarizer.SpeakerSegment> segments,
                                               int speaker, double targetTotalSeconds,
                                               double lineupSeconds, double minSeconds) {
        var own = segments.stream()
                .filter(s -> s.speaker() == speaker)
                .sorted((a, b) -> Double.compare(b.end() - b.start(), a.end() - a.start()))
                .toList();
        var clips = new ArrayList<float[]>();
        if (own.isEmpty()) return clips;

        // Clip 1: the lineup cut — centered in the longest span.
        var longest = own.get(0);
        double longestLength = longest.end() - longest.start();
        if (longestLength < minSeconds) return clips;
        double lineupDuration = Math.min(longestLength, lineupSeconds);
        double lineupStart = longest.start() + (longestLength - lineupDuration) / 2;
        var lineup = cut(samples, lineupStart, lineupDuration);
        if (lineup == null) return clips;
        clips.add(lineup);
        double total = lineupDuration;

        // Harvest candidates: remaining spans + the longest span's remnants
        // around the lineup window, longest first.
        var candidates = new ArrayList<double[]>();
        for (int i = 1; i < own.size(); i++) {
            candidates.add(new double[]{own.get(i).start(), own.get(i).end()});
        }
        candidates.add(new double[]{longest.start(), lineupStart});
        candidates.add(new double[]{lineupStart + lineupDuration, longest.end()});
        candidates.sort((a, b) -> Double.compare(b[1] - b[0], a[1] - a[0]));

        for (var span : candidates) {
            if (total >= targetTotalSeconds) break;
            double spanLength = span[1] - span[0];
            if (spanLength < minSeconds) continue;
            double duration = Math.min(spanLength, targetTotalSeconds - total);
            if (duration < minSeconds) break;
            var clip = cut(samples, span[0] + (spanLength - duration) / 2, duration);
            if (clip == null) continue;
            clips.add(clip);
            total += duration;
        }
        return clips;
    }

    /** Centered cut with the same clock-drift clamping {@link #extract}
     *  uses; null when the window falls outside the decoded audio. */
    private static float[] cut(float[] samples, double start, double duration) {
        int durSamples = (int) Math.round(duration * SAMPLE_RATE);
        int from = Math.clamp(Math.round(start * SAMPLE_RATE), 0,
                Math.max(0, samples.length - durSamples));
        int to = Math.min(from + durSamples, samples.length);
        return to <= from ? null : Arrays.copyOfRange(samples, from, to);
    }

    /** Encode PCM float mono 16 kHz samples as a 16-bit WAV file. */
    public static byte[] toWavPcm16(float[] samples) {
        int dataSize = samples.length * 2;
        var buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes())
                .putInt(36 + dataSize)
                .put("WAVE".getBytes())
                .put("fmt ".getBytes())
                .putInt(16)                       // fmt chunk size
                .putShort((short) 1)              // PCM
                .putShort((short) 1)              // mono
                .putInt(SAMPLE_RATE)
                .putInt(SAMPLE_RATE * 2)          // byte rate
                .putShort((short) 2)              // block align
                .putShort((short) 16)             // bits per sample
                .put("data".getBytes())
                .putInt(dataSize);
        for (float f : samples) {
            buf.putShort((short) Math.round(Math.clamp(f, -1.0f, 1.0f) * Short.MAX_VALUE));
        }
        return buf.array();
    }
}
