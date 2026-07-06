package services.transcription;

import play.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Word-granular interjection re-attribution (JCLAW-651). The diarizer HEARS
 * brief interjections — the overlap-aware raw annotation registered the true
 * speaker at 4/4 human-verified error sites — but its exclusive projection
 * collapses them (sometimes to zero-length segments), so whisper's words
 * land on the dominant voice. This pass carves them back out:
 *
 * <p>For each brief secondary activation (a raw-annotation span of speaker B
 * inside an entry attributed to A): CTC-align the entry's words, take the
 * words whose aligned midpoints fall inside the activation, and re-attribute
 * that fragment to B — IF a voiceprint guard agrees (the fragment's
 * embedding must favor B over A by a margin; raw activations alone also
 * fire on echo, and an unguarded carve would trade one error class for
 * another). The third granularity in the correction family: MSDD flips
 * whole turns, under-speech recovery inserts whole regions, this moves
 * words.
 */
public final class InterjectionCarver {

    /** Activation length window: below the floor is echo/noise even for the
     *  raw annotation; above the ceiling it is a real turn the exclusive
     *  output should have (and MSDD adjudicates). */
    static final double MIN_ACTIVATION_SECONDS = 0.3;
    static final double MAX_ACTIVATION_SECONDS = 3.5;
    /** Aligned-word capture pad around the activation. */
    static final double CAPTURE_PAD_SECONDS = 0.15;
    /** Stem guard margin: on the SEPARATED stem that best matches the
     *  claimed speaker, that speaker must beat the current owner by this
     *  cosine margin. Mixed-audio embeddings fail in echo (the JCLAW-612
     *  lesson — the first guard rejected all four human-verified carves at
     *  margins -0.10 to -0.16); separation isolates the voices first. */
    static final double GUARD_MARGIN = 0.04;
    /** Padding around a fragment when separating its window. */
    static final double STEM_PAD_SECONDS = 1.25;
    /** Minimum carved fragment length — a lone sub-300ms word embeds too
     *  unreliably to guard. */
    static final double MIN_FRAGMENT_SECONDS = 0.3;

    private static final int SAMPLE_RATE = 16_000;

    /** Test seam, mirroring SegmentWordSplitter's: words of a window in,
     *  absolute [start,end] seconds per word out. */
    @FunctionalInterface
    public interface Aligner {
        List<double[]> alignWords(double startSeconds, double endSeconds, List<String> words);
    }

    private InterjectionCarver() {}

    /** Production entry point: raw activations from the diarization, labels
     *  via the naming map, word alignment (the ASR's OWN word stamps when
     *  available — exact; CTC fallback otherwise), MossFormer stems for the
     *  guard and the sidecar embedder. */
    public static List<DiarizedTranscript.Entry> carve(
            List<DiarizedTranscript.Entry> entries,
            DiarizationRouter.Result diarization,
            Map<Integer, String> names,
            float[] pcm) {
        return carve(entries, diarization, names, pcm, List.of());
    }

    /** As above with the raw transcript for exact word-stamp alignment
     *  (JCLAW-651 round 2 — CTC boundaries wobble in echo; the engine's
     *  word clock does not). */
    public static List<DiarizedTranscript.Entry> carve(
            List<DiarizedTranscript.Entry> entries,
            DiarizationRouter.Result diarization,
            Map<Integer, String> names,
            float[] pcm,
            List<WhisperJniTranscriber.Segment> transcript) {
        if (diarization.rawSegments().isEmpty()) return entries;
        try {
            var references = OverlapReattributor.references(
                    entries, diarization.overlaps(), pcm, SidecarEmbedder.INSTANCE);
            var stamps = new ArrayList<WhisperJniTranscriber.Word>();
            for (var seg : transcript) stamps.addAll(seg.words());
            Aligner ctc = (start, end, words) -> CtcForcedAligner.alignWords(
                    pcm,
                    (int) Math.clamp(Math.round(start * SAMPLE_RATE), 0, pcm.length),
                    (int) Math.clamp(Math.round(end * SAMPLE_RATE), 0, pcm.length),
                    words);
            Aligner aligner = stamps.isEmpty() ? ctc
                    : (start, end, words) -> stampAlign(stamps, start, end, words, ctc);
            return carve(entries, diarization.rawSegments(), names, pcm, aligner,
                    OverlapReattributor::separateViaSidecar,
                    SidecarEmbedder.INSTANCE, references);
        } catch (RuntimeException e) {
            Logger.warn("InterjectionCarver: carving skipped: %s", e.getMessage());
            return entries;
        }
    }

    /** Exact alignment from the engine's word stamps: the entry's words map
     *  1:1 onto the stamps inside its window (same normalization filter);
     *  any mismatch falls back to CTC. */
    static List<double[]> stampAlign(List<WhisperJniTranscriber.Word> stamps,
                                     double start, double end, List<String> normalizedWords,
                                     Aligner ctcFallback) {
        var inWindow = new ArrayList<WhisperJniTranscriber.Word>();
        for (var w : stamps) {
            double mid = (w.startMs() + w.endMs()) / 2000.0;
            if (mid >= start - 0.05 && mid <= end + 0.05
                    && !CtcForcedAligner.normalizeWord(w.text()).isEmpty()) {
                inWindow.add(w);
            }
        }
        if (inWindow.size() != normalizedWords.size()) {
            return ctcFallback.alignWords(start, end, normalizedWords);
        }
        var out = new ArrayList<double[]>(inWindow.size());
        for (var w : inWindow) {
            out.add(new double[]{w.startMs() / 1000.0, w.endMs() / 1000.0});
        }
        return out;
    }

    /** Seam-based core. */
    public static List<DiarizedTranscript.Entry> carve(
            List<DiarizedTranscript.Entry> entries,
            List<SpeakerSegment> rawSegments,
            Map<Integer, String> names,
            float[] pcm,
            Aligner aligner,
            OverlapReattributor.Separator separator,
            OverlapReattributor.Embedder embedder,
            Map<String, float[]> references) {
        var out = new ArrayList<>(entries);
        int carved = 0;
        for (int i = 0; i < out.size(); i++) {
            var entry = out.get(i);
            var best = bestActivation(entry, rawSegments, names);
            if (best == null) continue;
            var claimed = names.getOrDefault(best.speaker(),
                    DiarizedTranscript.speakerLabel(best.speaker()));
            var ownRef = references.get(entry.speaker());
            var claimedRef = references.get(claimed);
            if (ownRef == null || claimedRef == null) continue;

            var words = List.of(entry.text().split("\\s+"));
            var normalized = new ArrayList<String>();
            var originals = new ArrayList<String>();
            for (var w : words) {
                var n = CtcForcedAligner.normalizeWord(w);
                if (!n.isEmpty()) {
                    normalized.add(n);
                    originals.add(w);
                }
            }
            if (normalized.size() < 2) continue; // nothing to carve out of one word
            List<double[]> times;
            try {
                times = aligner.alignWords(entry.start(), entry.end(), normalized);
            } catch (RuntimeException e) {
                continue; // best-effort per entry
            }

            int from = -1;
            int to = -1;
            for (int w = 0; w < times.size(); w++) {
                double mid = (times.get(w)[0] + times.get(w)[1]) / 2;
                if (mid >= best.start() - CAPTURE_PAD_SECONDS
                        && mid <= best.end() + CAPTURE_PAD_SECONDS) {
                    if (from < 0) from = w;
                    to = w;
                }
            }
            if (from < 0 || from == 0 && to == times.size() - 1) continue; // nothing / everything
            double fragStart = times.get(from)[0];
            double fragEnd = times.get(to)[1];
            if (fragEnd - fragStart < MIN_FRAGMENT_SECONDS) continue;

            // STEM guard: separate the fragment's padded window and demand
            // the claimed speaker win on the isolated voices — mixed audio
            // embeds echo, stems do not (the under-speech recovery lesson).
            double margin = stemGuardMargin(pcm, fragStart, fragEnd, separator,
                    embedder, claimedRef, ownRef);
            if (margin < GUARD_MARGIN) {
                Logger.info("InterjectionCarver: stem guard rejected \"%s\" for %s (margin %.3f)",
                        preview(originals, from, to), claimed, margin);
                continue;
            }

            // Split: [head A][fragment B][tail A], preserving flags on parts.
            var replacement = new ArrayList<DiarizedTranscript.Entry>(3);
            if (from > 0) {
                replacement.add(new DiarizedTranscript.Entry(entry.speaker(), entry.start(),
                        fragStart, String.join(" ", originals.subList(0, from)),
                        entry.emotion(), entry.crossTalk(), entry.underSpeech(),
                        entry.noSpeakerEvidence()));
            }
            replacement.add(new DiarizedTranscript.Entry(claimed, fragStart, fragEnd,
                    String.join(" ", originals.subList(from, to + 1)), null, false, false, false));
            if (to < originals.size() - 1) {
                replacement.add(new DiarizedTranscript.Entry(entry.speaker(), fragEnd,
                        entry.end(), String.join(" ", originals.subList(to + 1, originals.size())),
                        entry.emotion(), entry.crossTalk(), entry.underSpeech(),
                        entry.noSpeakerEvidence()));
            }
            Logger.info("InterjectionCarver: \"%s\" %s -> %s (margin %.3f)",
                    preview(originals, from, to), entry.speaker(), claimed, margin);
            out.remove(i);
            out.addAll(i, replacement);
            i += replacement.size() - 1;
            carved++;
        }
        if (carved > 0) Logger.info("InterjectionCarver: carved %d interjection(s)", carved);
        return out;
    }

    /**
     * Best stem evidence for the claim: separate the padded fragment window,
     * embed both stems' fragment slices, and return the margin by which the
     * claimed speaker beats the current owner on the MOST-CLAIMED stem.
     * Negative-infinity when no usable stem exists.
     */
    static double stemGuardMargin(float[] pcm, double fragStart, double fragEnd,
                                  OverlapReattributor.Separator separator,
                                  OverlapReattributor.Embedder embedder,
                                  float[] claimedRef, float[] ownRef) {
        double winStart = Math.max(0, fragStart - STEM_PAD_SECONDS);
        double winEnd = Math.min(pcm.length / (double) SAMPLE_RATE, fragEnd + STEM_PAD_SECONDS);
        int a = (int) (winStart * SAMPLE_RATE);
        int b = (int) (winEnd * SAMPLE_RATE);
        if (b - a < SAMPLE_RATE) return Double.NEGATIVE_INFINITY;
        var window = new float[b - a];
        System.arraycopy(pcm, a, window, 0, window.length);
        List<float[]> stems;
        try {
            stems = separator.separate(List.of(window)).get(0);
        } catch (RuntimeException e) {
            return Double.NEGATIVE_INFINITY;
        }
        int sFrom = (int) Math.clamp(Math.round((fragStart - winStart) * SAMPLE_RATE), 0, window.length);
        int sTo = (int) Math.clamp(Math.round((fragEnd - winStart) * SAMPLE_RATE), sFrom, window.length);
        var slices = new ArrayList<float[]>();
        for (var stem : stems) {
            if (stem.length < sTo) continue;
            var slice = new float[sTo - sFrom];
            System.arraycopy(stem, sFrom, slice, 0, slice.length);
            if (OverlapReattributor.rms(slice) < 0.008) continue;
            slices.add(slice);
        }
        if (slices.isEmpty()) return Double.NEGATIVE_INFINITY;
        double best = Double.NEGATIVE_INFINITY;
        for (var emb : embedder.embedAll(slices)) {
            double claimedScore = OverlapReattributor.cosine(emb, claimedRef);
            double ownScore = OverlapReattributor.cosine(emb, ownRef);
            best = Math.max(best, claimedScore - ownScore);
        }
        return best;
    }

    /** The strongest interior secondary activation for this entry, or null.
     *  "Interior": majority of the activation lies inside the entry, and the
     *  activation's speaker maps to a DIFFERENT label than the entry's. */
    private static SpeakerSegment bestActivation(
            DiarizedTranscript.Entry entry, List<SpeakerSegment> rawSegments,
            Map<Integer, String> names) {
        SpeakerSegment best = null;
        double bestLen = 0;
        for (var r : rawSegments) {
            double len = r.end() - r.start();
            if (len < MIN_ACTIVATION_SECONDS || len > MAX_ACTIVATION_SECONDS) continue;
            var label = names.getOrDefault(r.speaker(),
                    DiarizedTranscript.speakerLabel(r.speaker()));
            if (label.equals(entry.speaker())) continue;
            double inside = Math.min(r.end(), entry.end()) - Math.max(r.start(), entry.start());
            if (inside < len * 0.6) continue;
            if (inside > bestLen) {
                bestLen = inside;
                best = r;
            }
        }
        return best;
    }

    private static String preview(List<String> words, int from, int to) {
        var s = String.join(" ", words.subList(from, Math.min(to + 1, words.size())));
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
