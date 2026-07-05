package services.transcription;

import play.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Under-speech recovery (JCLAW-613). Whisper transcribes the dominant
 * voice, so backchannels spoken UNDER it ("Yeah", "Mm", "Exactly") never
 * become transcript turns — but their audio survives in the minor
 * separation stems the re-attribution pass already computes for every
 * overlap window. For backchannel-scale overlap regions, this transcribes
 * the under-speaker's stem slice and inserts it as its own turn — the
 * class of turn the operator's gold reference renders and ours lacked.
 *
 * <p>Separation stems are audibly degraded and whisper hallucinates on
 * residue, so recovery is fenced on all sides: an energy gate, a RELATIVE
 * voiceprint gate (the slice must match the under-speaker better than the
 * owner), interjection caps (never long claims — the JCLAW-605 rule that
 * stems provide ownership, not words, is relaxed only for interjections),
 * and a dedupe guard. Pure decision logic over injected seams.
 */
public final class UnderSpeechRecovery {

    /** Backchannel-scale overlap regions only. Longer overlap is
     *  re-attribution/marker territory. */
    static final double MIN_REGION_SECONDS = 0.4;
    static final double MAX_REGION_SECONDS = 2.5;
    /** Interjection caps — a recovered turn may never be a long claim. */
    static final int MAX_WORDS = 6;
    static final int MAX_CHARS = 40;
    /** Physically plausible speech density; whisper embellishments on
     *  degraded stems exceed it (live: 6 words in a 0.8s region). */
    static final double MAX_WORDS_PER_SECOND = 3.5;
    /** Stem slices quieter than this are separation residue. */
    static final double MIN_RMS = 0.008;
    /** An existing under-speaker turn covering this much of the region
     *  means the words are already in the transcript (word-splitter output
     *  typically abuts the region rather than centering on it, so the
     *  threshold is deliberately low). */
    static final double DEDUPE_COVERAGE = 0.3;

    private static final int SAMPLE_RATE = 16_000;

    /** Test seam: PCM float slice in, transcribed text out ("" for none). */
    @FunctionalInterface
    public interface Transcriber {
        String transcribe(float[] samples);
    }

    private UnderSpeechRecovery() {}

    /**
     * Recover under-speech interjections from the minor stems of already
     * separated windows. Returns the entries with recovered turns inserted
     * in time order; the input list is not modified.
     */
    public static List<DiarizedTranscript.Entry> recover(
            List<DiarizedTranscript.Entry> entries, List<double[]> overlaps,
            List<float[]> windows, List<Double> windowStarts,
            List<List<float[]>> stemsPerWindow, Map<String, float[]> references,
            OverlapReattributor.Embedder embedder, Transcriber transcriber) {
        if (transcriber == null || references.size() < 2) return entries;
        var out = new ArrayList<>(entries);
        int recovered = 0;
        for (var region : overlaps) {
            var turn = recoverRegion(entries, region, windows, windowStarts,
                    stemsPerWindow, references, embedder, transcriber);
            if (turn != null) {
                out.add(turn);
                recovered++;
                Logger.info("UnderSpeechRecovery: +\"%s\" (%s, %.1f-%.1fs, under-speech)",
                        turn.text(), turn.speaker(), turn.start(), turn.end());
            }
        }
        if (recovered == 0) return entries;
        out.sort(Comparator.comparingDouble(DiarizedTranscript.Entry::start));
        return out;
    }

    private static DiarizedTranscript.Entry recoverRegion(
            List<DiarizedTranscript.Entry> entries, double[] region,
            List<float[]> windows, List<Double> windowStarts,
            List<List<float[]>> stemsPerWindow, Map<String, float[]> references,
            OverlapReattributor.Embedder embedder, Transcriber transcriber) {
        double length = region[1] - region[0];
        if (length < MIN_REGION_SECONDS || length > MAX_REGION_SECONDS) return null;

        int w = windowCovering(region, windows, windowStarts);
        if (w < 0 || w >= stemsPerWindow.size()) return null;
        var stems = stemsPerWindow.get(w);
        if (stems == null || stems.size() < 2) return null;

        var owner = ownerAt(entries, (region[0] + region[1]) / 2);
        if (owner == null || !references.containsKey(owner)) return null;
        var under = references.keySet().stream()
                .filter(l -> !l.equals(owner)).findFirst().orElse(null);
        if (under == null) return null;
        if (alreadyCovered(entries, region, under)) return null;

        var slice = underStemSlice(stems, region, windowStarts.get(w),
                references.get(under), references.get(owner), embedder);
        if (slice == null) return null;

        var text = transcriber.transcribe(slice);
        text = text == null ? "" : text.strip();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        if (text.isEmpty() || text.length() > MAX_CHARS || words > MAX_WORDS
                || !text.codePoints().anyMatch(Character::isLetter)
                || words / length > MAX_WORDS_PER_SECOND) {
            return null;
        }
        return new DiarizedTranscript.Entry(under, region[0], region[1], text, null, false, true);
    }

    /** The stem slice belonging to the under-speaker: must clear the energy
     *  floor and match the under-speaker's voiceprint BETTER than the
     *  owner's (relative gate — absolute thresholds drown in stem noise). */
    private static float[] underStemSlice(List<float[]> stems, double[] region,
                                          double windowStart, float[] underRef,
                                          float[] ownerRef,
                                          OverlapReattributor.Embedder embedder) {
        float[] best = null;
        double bestMargin = 0;
        for (var stem : stems) {
            int from = (int) Math.clamp(Math.round((region[0] - 0.2 - windowStart) * SAMPLE_RATE),
                    0, stem.length);
            int to = (int) Math.clamp(Math.round((region[1] + 0.2 - windowStart) * SAMPLE_RATE),
                    from, stem.length);
            if (to - from < SAMPLE_RATE / 4) continue;
            var slice = new float[to - from];
            System.arraycopy(stem, from, slice, 0, slice.length);
            if (OverlapReattributor.rms(slice) < MIN_RMS) continue;
            var emb = embedder.embed(slice);
            double margin = OverlapReattributor.cosine(emb, underRef)
                    - OverlapReattributor.cosine(emb, ownerRef);
            if (margin > bestMargin) {
                bestMargin = margin;
                best = slice;
            }
        }
        return best;
    }

    private static int windowCovering(double[] region, List<float[]> windows,
                                      List<Double> starts) {
        double mid = (region[0] + region[1]) / 2;
        for (int w = 0; w < windows.size(); w++) {
            double end = starts.get(w) + windows.get(w).length / (double) SAMPLE_RATE;
            if (mid >= starts.get(w) && mid <= end) return w;
        }
        return -1;
    }

    private static String ownerAt(List<DiarizedTranscript.Entry> entries, double t) {
        for (var e : entries) {
            if (e.start() <= t && t < e.end()) return e.speaker();
        }
        return null;
    }

    private static boolean alreadyCovered(List<DiarizedTranscript.Entry> entries,
                                          double[] region, String under) {
        double length = region[1] - region[0];
        for (var e : entries) {
            if (!e.speaker().equals(under)) continue;
            double ov = Math.min(e.end(), region[1]) - Math.max(e.start(), region[0]);
            if (ov / length >= DEDUPE_COVERAGE) return true;
        }
        return false;
    }
}
