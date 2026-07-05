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
     *  detected overlap boundaries are approximate (measured error can
     *  exceed half a second in heated exchanges); a generous margin keeps
     *  clips clear of the other voice's onset/tail. */
    public static final double OVERLAP_PAD_SECONDS = 0.75;

    /** Reference clips are capped at this length (JCLAW-609 round 4,
     *  operator-specified): short windows deny bleed the cover of dilution
     *  — a 0.5s affirmation is ~10% of a 5s window but only ~5% of a 10s
     *  one, and the stem gate's signal scales with that fraction. Each
     *  piece gets its own verdict, so one backchannel rejects one piece,
     *  not a whole span. */
    public static final double MAX_CLIP_SECONDS = 5.0;

    /** Pieces shorter than this never become reference clips — speaker
     *  embeddings are unreliable below ~2s. Short-span risk (round 2's
     *  sliver lesson) is now carried by the per-piece stem gate rather
     *  than a coarse span floor. */
    public static final double MIN_PIECE_SECONDS = 2.0;

    /** A candidate reference clip must match the anchor voiceprint (the
     *  lineup clip — the audio the operator verifies by ear) at least this
     *  closely, as must each of its halves. Same-speaker WeSpeaker cosines
     *  measure 0.7+ on this pipeline; cross-speaker 0.3-0.5; a half-bled
     *  clip lands between and is rejected. */
    public static final double ANCHOR_GATE = 0.60;

    /** Test seam matching {@link OverlapReattributor.Embedder} — the
     *  extractor stays free of natives; production passes the WeSpeaker
     *  extractor. */
    @FunctionalInterface
    public interface Embedder {
        float[] embed(float[] samples);

        default List<float[]> embedAll(List<float[]> windows) {
            var out = new ArrayList<float[]>(windows.size());
            for (var w : windows) out.add(embed(w));
            return out;
        }
    }

    private SpeakerClipExtractor() {}

    /**
     * Remove (padded) overlap regions from every segment (JCLAW-609),
     * splitting spans into PURE sub-segments that keep their speaker id.
     * Exclusive-mode diarization absorbs cross-talk into the dominant
     * speaker's span, so mid-segment cuts alone can still capture the other
     * voice bleeding through — enrollment clips must come from spans where
     * only one person was speaking. With no overlap data this
     * is the identity.
     */
    public static List<SpeakerSegment> purify(
            List<SpeakerSegment> segments, List<double[]> overlaps) {
        if (overlaps.isEmpty()) return segments;
        var pure = new ArrayList<SpeakerSegment>();
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
                pure.add(new SpeakerSegment(span[0], span[1], seg.speaker()));
            }
        }
        return pure;
    }

    /** As {@link #extract(float[], List, double, double)}, decoding the audio
     *  file first (same ffmpeg path as the rest of the pipeline). */
    public static List<Clip> extract(Path audioFile, List<SpeakerSegment> segments,
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
    public static List<Clip> extract(float[] samples, List<SpeakerSegment> segments,
                                     double targetSeconds, double minSeconds) {
        Map<Integer, SpeakerSegment> longest = new TreeMap<>();
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
                                               List<SpeakerSegment> segments,
                                               int speaker, double targetTotalSeconds,
                                               double lineupSeconds, double minSeconds,
                                               Embedder embedder) {
        return referenceClips(WhisperJniTranscriber.ffmpegToPcmF32(audioFile),
                segments, speaker, targetTotalSeconds, lineupSeconds, minSeconds, embedder);
    }

    public static List<float[]> referenceClips(float[] samples,
                                               List<SpeakerSegment> segments,
                                               int speaker, double targetTotalSeconds,
                                               double lineupSeconds, double minSeconds,
                                               Embedder embedder) {
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
        // The anchor voiceprint: the operator verifies this exact audio by
        // ear, and it comes from the deepest interior of the longest pure
        // span — every harvested clip must acoustically match it
        // (JCLAW-609 round 2: detector-based purity alone is not enough;
        // undetected echo/backchannel contaminated harvested slivers).
        float[] anchor = embedder.embed(lineup);

        // Harvest candidates: remaining spans + the longest span's remnants
        // around the lineup window, longest first — each SLICED into
        // consecutive pieces of at most MAX_CLIP_SECONDS (round 4: purity
        // over length; every piece stands or falls on its own gates).
        var candidates = new ArrayList<double[]>();
        for (int i = 1; i < own.size(); i++) {
            candidates.add(new double[]{own.get(i).start(), own.get(i).end()});
        }
        candidates.add(new double[]{longest.start(), lineupStart});
        candidates.add(new double[]{lineupStart + lineupDuration, longest.end()});
        candidates.sort((a, b) -> Double.compare(b[1] - b[0], a[1] - a[0]));

        // JCLAW-634: two-phase — cut every candidate piece first, anchor-gate
        // them in ONE batched embed call (previously three embeds per piece),
        // then accept in order until the budget fills. Cutting more pieces
        // than the budget needs is cheap; the embeds were the cost.
        var pieces = new ArrayList<float[]>();
        var pieceAt = new ArrayList<Double>();
        for (var span : candidates) {
            for (double at = span[0]; span[1] - at >= MIN_PIECE_SECONDS; at += MAX_CLIP_SECONDS) {
                var clip = cut(samples, at, Math.min(MAX_CLIP_SECONDS, span[1] - at));
                if (clip == null) continue;
                pieces.add(clip);
                pieceAt.add(at);
            }
        }
        var verdicts = anchorVerdicts(pieces, anchor, embedder);
        for (int i = 0; i < pieces.size(); i++) {
            if (total >= targetTotalSeconds) break;
            if (!verdicts.get(i)) {
                play.Logger.info("SpeakerClipExtractor: rejected %.1fs piece at %.1fs — "
                        + "voiceprint does not match the anchor clip",
                        pieces.get(i).length / (double) SAMPLE_RATE, pieceAt.get(i));
                continue;
            }
            clips.add(pieces.get(i));
            total += pieces.get(i).length / (double) SAMPLE_RATE;
        }
        return clips;
    }

    /** Anchor-gate a whole piece list with one batched embed call: each
     *  piece contributes itself plus (when long enough) both halves. */
    static List<Boolean> anchorVerdicts(List<float[]> pieces, float[] anchor, Embedder embedder) {
        var windows = new ArrayList<float[]>();
        var counts = new int[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            var clip = pieces.get(i);
            windows.add(clip);
            counts[i] = 1;
            if (clip.length >= 2 * SAMPLE_RATE) {
                windows.add(Arrays.copyOfRange(clip, 0, clip.length / 2));
                windows.add(Arrays.copyOfRange(clip, clip.length / 2, clip.length));
                counts[i] = 3;
            }
        }
        var embs = embedder.embedAll(windows);
        var verdicts = new ArrayList<Boolean>(pieces.size());
        int k = 0;
        for (int count : counts) {
            boolean ok = true;
            for (int j = 0; j < count; j++) {
                if (OverlapReattributor.cosine(embs.get(k + j), anchor) < ANCHOR_GATE) ok = false;
            }
            verdicts.add(ok);
            k += count;
        }
        return verdicts;
    }

    /** Anchor gate: the whole clip AND each half must match the anchor
     *  voiceprint — a clip whose second half is another voice scores fine
     *  on average but fails the half check. Public for the rule-table test. */
    public static boolean matchesAnchor(float[] clip, float[] anchor, Embedder embedder) {
        if (OverlapReattributor.cosine(embedder.embed(clip), anchor) < ANCHOR_GATE) return false;
        if (clip.length < 2 * SAMPLE_RATE) return true; // halves under 1s embed unreliably
        var first = Arrays.copyOfRange(clip, 0, clip.length / 2);
        var second = Arrays.copyOfRange(clip, clip.length / 2, clip.length);
        return OverlapReattributor.cosine(embedder.embed(first), anchor) >= ANCHOR_GATE
                && OverlapReattributor.cosine(embedder.embed(second), anchor) >= ANCHOR_GATE;
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
