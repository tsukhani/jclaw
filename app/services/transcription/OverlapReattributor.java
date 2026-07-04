package services.transcription;

import play.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Overlap re-attribution (JCLAW-605). Exclusive-mode diarization resolves
 * cross-talk to the dominant voice, so a quieter speaker's words land on the
 * wrong label — measurably unfixable by any single-label pass (the JCLAW-602
 * spike: the disputed window's voiceprint is a near-tie blend on mixed audio,
 * and flips to the true speaker only after MossFormer2 separation). This pass
 * runs only over the diarizer's detected overlap regions: separate the region
 * into two stems via the sidecar, embed each affected transcript entry's
 * window in each stem with the shared WeSpeaker extractor, compare against
 * per-speaker reference embeddings, and reassign the entry when the evidence
 * is decisive.
 *
 * <p>References are enrolled voices when the transcript already carries
 * enrolled names; otherwise centroids built from each label's <i>clean</i>
 * (non-overlapped) speech in the same recording — self-calibrating, no
 * enrollment required.
 *
 * <p>Conservative by design (the JCLAW-604 lesson: a correction pass must
 * not flip ambiguity): an entry is reassigned only when the winning label
 * differs from the current one, wins by a clear margin, and the winning stem
 * window actually carries speech energy. Best-effort throughout — any
 * failure leaves the transcript exactly as the merge produced it.
 */
public final class OverlapReattributor {

    public static final String ENABLED_KEY = "transcription.diarization.overlapReattribution";

    /** An entry participates when at least this much of it lies in overlap. */
    static final double MIN_INTERSECT_FRACTION = 0.5;
    /** ...or at least this many absolute seconds. */
    static final double MIN_INTERSECT_SECONDS = 1.0;
    /** Turns ADJACENT to a detected overlap participate too: the overlap
     *  detector only marks where both voices registered, but a buried
     *  voice's words trail the visible collision (the JCLAW-605 flagship
     *  case sits 0.6s after its detected region — the detector can't mark
     *  what it can't hear, which is the whole reason separation exists). */
    static final double ADJACENCY_SECONDS = 3.0;
    /** Adjacent turns longer than this are trusted as-is — measured: turns
     *  over 4s were never misattributed. */
    static final double MAX_ADJACENT_TURN_SECONDS = 4.0;
    /** Winner must beat the runner-up by this cosine margin to override.
     *  Whisper-boundary jitter moves a given turn's margin by ~0.02 between
     *  runs (the flagship case measured 0.078-0.097 on identical audio), so
     *  the stem margin alone cannot be the sole guard; the mixed-audio
     *  ambiguity gate below carries the structural discrimination and this
     *  threshold only rejects clear noise. */
    static final double DECISION_MARGIN = 0.07;
    /** A flip is only allowed when the ORIGINAL mixed audio does not
     *  strongly support the current label — the cross-talk signature.
     *  Genuinely buried voices measure as near-ties on mixed audio
     *  (flagship: 0.724 vs 0.751); wrong-flip candidates are clean speech
     *  whose mixed evidence decisively backs the current label. */
    static final double MIXED_SUPPORT_GAP = 0.10;
    /** Stem windows quieter than this RMS are silence — not evidence. */
    static final double MIN_STEM_RMS = 0.008;
    /** Separation window: overlap region padded on both sides. */
    static final double PAD_SECONDS = 1.5;
    /** Minimum separation window. MossFormer2's stem quality depends on
     *  context: the JCLAW-602 spike (which flipped the flagship case) used a
     *  30s window, and production runs with tight region slivers measurably
     *  read the same audio differently. Windows are centered and expanded to
     *  at least this length, then overlapping windows merge. */
    static final double MIN_WINDOW_SECONDS = 30.0;
    /** Clean reference audio collected per speaker, at most. */
    static final int MAX_REFERENCE_SAMPLES = 20 * 16_000;
    /** Reference chunk length: embeddings are computed per chunk and
     *  AVERAGED — the JCLAW-605 experiment showed averaged multi-chunk
     *  references separate right from wrong margins where a single-clip
     *  reference drowns them in noise. */
    static final int REFERENCE_CHUNK_SAMPLES = 5 * 16_000;

    private static final int SAMPLE_RATE = 16_000;

    /** Test seam: windows in, one stem-pair (PCM float arrays) per window
     *  out, aligned with the input order. Batched so the production
     *  implementation loads MossFormer2 once per diarization. */
    @FunctionalInterface
    public interface Separator {
        List<List<float[]>> separate(List<float[]> windows);
    }

    /** Test seam: PCM float window in, speaker embedding out. */
    @FunctionalInterface
    public interface Embedder {
        float[] embed(float[] samples);
    }

    private OverlapReattributor() {}

    /**
     * Re-attribute overlap-region entries of {@code entries} using sidecar
     * separation. Best-effort: returns the input unchanged on any failure.
     * Blocking — the separator's first call downloads MossFormer2 weights.
     */
    public static List<DiarizedTranscript.Entry> reattribute(
            List<DiarizedTranscript.Entry> entries, List<double[]> overlaps, Path audioFile) {
        if (overlaps.isEmpty()) return entries;
        try {
            float[] pcm = WhisperJniTranscriber.ffmpegToPcmF32(audioFile);
            return reattribute(entries, overlaps, pcm,
                    OverlapReattributor::sidecarSeparate, SpeakerNamer::embedWindow);
        } catch (RuntimeException e) {
            Logger.warn("OverlapReattributor: re-attribution skipped: %s", e.getMessage());
            return entries;
        }
    }

    /** Throwing core with injected seams — the testable path. */
    public static List<DiarizedTranscript.Entry> reattribute(
            List<DiarizedTranscript.Entry> entries, List<double[]> overlaps,
            float[] pcm, Separator separator, Embedder embedder) {
        var affected = new ArrayList<Integer>();
        for (int i = 0; i < entries.size(); i++) {
            if (inOverlap(entries.get(i), overlaps)) affected.add(i);
        }
        if (affected.isEmpty()) return entries;

        var references = references(entries, overlaps, pcm, embedder);
        if (references.size() < 2) {
            Logger.info("OverlapReattributor: fewer than 2 clean speaker references — skipping");
            return entries;
        }

        // Stage every region window first, then separate the whole batch in
        // one call — the separator loads its model once per diarization.
        var regionStarts = new ArrayList<Double>();
        var regionEntryIndexes = new ArrayList<List<Integer>>();
        var windows = new ArrayList<float[]>();
        var claimed = new java.util.HashSet<Integer>();
        for (var region : overlaps) {
            var regionEntries = affected.stream()
                    .filter(i -> !claimed.contains(i) && belongsToRegion(entries.get(i), region))
                    .toList();
            if (regionEntries.isEmpty()) continue;
            claimed.addAll(regionEntries);
            // Window must cover the region AND every claimed entry in full.
            double lo = region[0];
            double hi = region[1];
            for (int i : regionEntries) {
                lo = Math.min(lo, entries.get(i).start());
                hi = Math.max(hi, entries.get(i).end());
            }
            double winStart = Math.max(0, lo - PAD_SECONDS);
            double winEnd = Math.min(pcm.length / (double) SAMPLE_RATE, hi + PAD_SECONDS);
            if (winEnd - winStart < MIN_WINDOW_SECONDS) {
                double extend = (MIN_WINDOW_SECONDS - (winEnd - winStart)) / 2;
                winStart = Math.max(0, winStart - extend);
                winEnd = Math.min(pcm.length / (double) SAMPLE_RATE, winEnd + extend);
            }
            int from = (int) (winStart * SAMPLE_RATE);
            int to = (int) (winEnd * SAMPLE_RATE);
            if (to - from < SAMPLE_RATE) continue; // sub-second region: skip
            var window = new float[to - from];
            System.arraycopy(pcm, from, window, 0, window.length);
            windows.add(window);
            regionStarts.add(winStart);
            regionEntryIndexes.add(regionEntries);
        }
        if (windows.isEmpty()) return entries;
        // Coalesce windows that now overlap after expansion (30s minimum
        // windows over adjacent regions would otherwise re-separate the same
        // audio repeatedly).
        coalesce(regionStarts, regionEntryIndexes, windows, pcm);
        Logger.info("OverlapReattributor: checking %d turn(s) across %d overlap window(s)",
                regionEntryIndexes.stream().mapToInt(List::size).sum(), windows.size());
        var stemsPerWindow = separator.separate(windows);

        var out = new ArrayList<>(entries);
        int reassigned = 0;
        for (int w = 0; w < windows.size(); w++) {
            var stems = stemsPerWindow.get(w);
            double winStart = regionStarts.get(w);
            for (int i : regionEntryIndexes.get(w)) {
                var entry = out.get(i);
                if (mixedAudioBacksCurrentLabel(entry, pcm, references, embedder)) {
                    continue; // clean, confidently-attributed speech: not ours to touch
                }
                var scores = stemScores(entry, winStart, stems, references, embedder);
                var winner = decide(entry.speaker(), scores, DECISION_MARGIN);
                if (winner == null && !scores.isEmpty()
                        && !entry.speaker().equals(bestLabel(scores))) {
                    // Near-miss diagnostics: the evidence disagreed with the
                    // label but not decisively — visible without log spam.
                    Logger.info("OverlapReattributor: kept \"%s\" as %s (near-miss, scores %s)",
                            truncate(entry.text()), entry.speaker(), scores);
                }
                if (winner != null) {
                    out.set(i, new DiarizedTranscript.Entry(
                            winner, entry.start(), entry.end(), entry.text(), entry.emotion()));
                    reassigned++;
                    Logger.info("OverlapReattributor: \"%s\" %s -> %s (scores %s)",
                            truncate(entry.text()), entry.speaker(), winner, scores);
                }
            }
        }
        if (reassigned > 0) {
            Logger.info("OverlapReattributor: reassigned %d overlap turn(s)", reassigned);
        }
        return out;
    }

    /**
     * The cross-talk gate: embed the entry's window of the ORIGINAL mixed
     * audio; when the current label wins there by {@link #MIXED_SUPPORT_GAP}
     * or more, the attribution rests on solid evidence and no stem-based
     * flip is permitted. Buried voices cannot produce such support — their
     * mixed windows are near-ties by definition.
     */
    static boolean mixedAudioBacksCurrentLabel(DiarizedTranscript.Entry entry, float[] pcm,
                                               Map<String, float[]> references, Embedder embedder) {
        var current = references.get(entry.speaker());
        if (current == null) return false; // unknown label: no basis to protect it
        int from = (int) Math.clamp(Math.round(entry.start() * SAMPLE_RATE), 0, pcm.length);
        int to = (int) Math.clamp(Math.round(entry.end() * SAMPLE_RATE), from, pcm.length);
        if (to - from < SAMPLE_RATE / 2) return false;
        var window = new float[to - from];
        System.arraycopy(pcm, from, window, 0, window.length);
        var emb = embedder.embed(window);
        double currentScore = cosine(emb, current);
        double bestOther = Double.NEGATIVE_INFINITY;
        for (var ref : references.entrySet()) {
            if (!ref.getKey().equals(entry.speaker())) {
                bestOther = Math.max(bestOther, cosine(emb, ref.getValue()));
            }
        }
        return currentScore - bestOther >= MIXED_SUPPORT_GAP;
    }

    /**
     * Best per-label cosine across all stems for this entry's window. Stem
     * windows below the energy floor are silence and contribute nothing.
     */
    static Map<String, Double> stemScores(DiarizedTranscript.Entry entry, double windowStart,
                                          List<float[]> stems, Map<String, float[]> references,
                                          Embedder embedder) {
        var scores = new HashMap<String, Double>();
        for (var stem : stems) {
            int from = (int) Math.clamp(Math.round((entry.start() - windowStart) * SAMPLE_RATE),
                    0, stem.length);
            int to = (int) Math.clamp(Math.round((entry.end() - windowStart) * SAMPLE_RATE),
                    from, stem.length);
            if (to - from < SAMPLE_RATE / 2) continue;
            var slice = new float[to - from];
            System.arraycopy(stem, from, slice, 0, slice.length);
            if (rms(slice) < MIN_STEM_RMS) continue;
            var emb = embedder.embed(slice);
            for (var ref : references.entrySet()) {
                double c = cosine(emb, ref.getValue());
                scores.merge(ref.getKey(), c, Math::max);
            }
        }
        return scores;
    }

    /**
     * Override decision: the winning label, or {@code null} to keep the
     * current one. Requires a different winner with a clear margin over the
     * runner-up. Public so tests pin the rule table directly.
     */
    public static String decide(String currentLabel, Map<String, Double> scores, double margin) {
        if (scores.size() < 2) return null; // no contest without both references scored
        String best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double second = Double.NEGATIVE_INFINITY;
        for (var e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                second = bestScore;
                bestScore = e.getValue();
                best = e.getKey();
            } else if (e.getValue() > second) {
                second = e.getValue();
            }
        }
        if (best == null || best.equals(currentLabel)) return null;
        return bestScore - second >= margin ? best : null;
    }

    /** Per-label references: averaged multi-chunk embeddings of the label's
     *  clean in-recording speech — the winning scheme of the JCLAW-605
     *  reference experiment (a single enrolled clip measurably drowns the
     *  decision margins in reference noise). Enrolled clips serve only as
     *  fallback for labels with no clean speech in the recording. */
    static Map<String, float[]> references(
            List<DiarizedTranscript.Entry> entries, List<double[]> overlaps,
            float[] pcm, Embedder embedder) {
        var refs = referenceEmbeddings(entries, overlaps, pcm, embedder);
        var labels = entries.stream().map(DiarizedTranscript.Entry::speaker).distinct().toList();
        for (var label : labels) {
            if (!refs.containsKey(label)) {
                var enrolled = enrolledReference(label, embedder);
                if (enrolled != null) refs.put(label, enrolled);
            }
        }
        return refs;
    }

    /** Averaged embedding of ALL the label's enrollment clips, or null when
     *  the label has no readable enrollment (anonymous SPEAKER_NN labels). */
    private static float[] enrolledReference(String label, Embedder embedder) {
        try {
            var dir = SpeakerNamer.enrollmentRoot().resolve(label);
            if (!Files.isDirectory(dir)) return null;
            try (var clips = Files.list(dir)) {
                var files = clips.filter(Files::isRegularFile)
                        .filter(pth -> !pth.getFileName().toString().startsWith("."))
                        .toList();
                if (files.isEmpty()) return null;
                float[] avg = null;
                int used = 0;
                for (var clip : files) {
                    try {
                        var emb = embedder.embed(WhisperJniTranscriber.ffmpegToPcmF32(clip));
                        if (avg == null) avg = new float[emb.length];
                        for (int i = 0; i < emb.length; i++) avg[i] += emb[i];
                        used++;
                    } catch (RuntimeException _) { /* skip unreadable clip */ }
                }
                if (avg == null) return null;
                for (int i = 0; i < avg.length; i++) avg[i] /= used;
                return avg;
            }
        } catch (IOException | RuntimeException e) {
            Logger.warn("OverlapReattributor: unreadable enrollment for %s (%s)", label, e.getMessage());
            return null;
        }
    }

    /** Per-label reference embeddings from each speaker's clean speech —
     *  entries that barely touch any overlap, concatenated up to
     *  {@link #MAX_REFERENCE_SAMPLES}. */
    static Map<String, float[]> referenceEmbeddings(
            List<DiarizedTranscript.Entry> entries, List<double[]> overlaps,
            float[] pcm, Embedder embedder) {
        var perLabel = new LinkedHashMap<String, float[]>();
        var collected = new HashMap<String, Integer>();
        var buffers = new HashMap<String, float[]>();
        for (var entry : entries) {
            if (inOverlap(entry, overlaps)) continue;
            var label = entry.speaker();
            int have = collected.getOrDefault(label, 0);
            if (have >= MAX_REFERENCE_SAMPLES) continue;
            int from = (int) Math.clamp(Math.round(entry.start() * SAMPLE_RATE), 0, pcm.length);
            int to = (int) Math.clamp(Math.round(entry.end() * SAMPLE_RATE), from, pcm.length);
            int take = Math.min(to - from, MAX_REFERENCE_SAMPLES - have);
            if (take <= 0) continue;
            var buf = buffers.computeIfAbsent(label, _ -> new float[MAX_REFERENCE_SAMPLES]);
            System.arraycopy(pcm, from, buf, have, take);
            collected.put(label, have + take);
        }
        for (var e : collected.entrySet()) {
            if (e.getValue() < 2 * SAMPLE_RATE) continue; // <2s: too weak a reference
            // Average per-chunk embeddings rather than embedding one long
            // concatenation — the measured difference between separating
            // margins and drowning them (JCLAW-605 reference experiment).
            var buf = buffers.get(e.getKey());
            float[] avg = null;
            int chunks = 0;
            for (int at = 0; at < e.getValue(); at += REFERENCE_CHUNK_SAMPLES) {
                int len = Math.min(REFERENCE_CHUNK_SAMPLES, e.getValue() - at);
                if (len < SAMPLE_RATE) break; // trailing fragment under 1s
                var chunk = new float[len];
                System.arraycopy(buf, at, chunk, 0, len);
                var emb = embedder.embed(chunk);
                if (avg == null) avg = new float[emb.length];
                for (int i = 0; i < emb.length; i++) avg[i] += emb[i];
                chunks++;
            }
            if (avg == null) continue;
            for (int i = 0; i < avg.length; i++) avg[i] /= chunks;
            perLabel.put(e.getKey(), avg);
        }
        return perLabel;
    }

    static boolean inOverlap(DiarizedTranscript.Entry entry, List<double[]> overlaps) {
        double duration = entry.end() - entry.start();
        if (duration <= 0) return false;
        for (var region : overlaps) {
            double intersect = intersect(entry, region);
            if (intersect >= MIN_INTERSECT_SECONDS
                    || intersect >= MIN_INTERSECT_FRACTION * duration) {
                return true;
            }
            // Adjacency: short turns bordering a detected collision are the
            // buried-voice suspects; long turns are measurably reliable.
            if (duration <= MAX_ADJACENT_TURN_SECONDS) {
                double gap = Math.max(region[0] - entry.end(), entry.start() - region[1]);
                if (gap >= 0 && gap <= ADJACENCY_SECONDS) return true;
            }
        }
        return false;
    }

    /** Merge adjacent/overlapping expanded windows in place: windows are
     *  built in ascending region order, so a linear sweep suffices. */
    private static void coalesce(List<Double> starts, List<List<Integer>> entryIndexes,
                                 List<float[]> windows, float[] pcm) {
        int i = 0;
        while (i + 1 < windows.size()) {
            double endI = starts.get(i) + windows.get(i).length / (double) SAMPLE_RATE;
            if (starts.get(i + 1) <= endI) {
                double endNext = starts.get(i + 1) + windows.get(i + 1).length / (double) SAMPLE_RATE;
                double newEnd = Math.max(endI, endNext);
                int from = (int) (starts.get(i) * SAMPLE_RATE);
                int to = Math.min((int) (newEnd * SAMPLE_RATE), pcm.length);
                var merged = new float[to - from];
                System.arraycopy(pcm, from, merged, 0, merged.length);
                windows.set(i, merged);
                var mergedEntries = new ArrayList<>(entryIndexes.get(i));
                for (var idx : entryIndexes.get(i + 1)) {
                    if (!mergedEntries.contains(idx)) mergedEntries.add(idx);
                }
                entryIndexes.set(i, mergedEntries);
                windows.remove(i + 1);
                starts.remove(i + 1);
                entryIndexes.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    /** Whether the entry belongs to this region's separation batch: it
     *  intersects the region or borders it within the adjacency window. */
    public static boolean belongsToRegion(DiarizedTranscript.Entry entry, double[] region) {
        if (intersect(entry, region) > 0) return true;
        double duration = entry.end() - entry.start();
        if (duration > MAX_ADJACENT_TURN_SECONDS) return false;
        double gap = Math.max(region[0] - entry.end(), entry.start() - region[1]);
        return gap >= 0 && gap <= ADJACENCY_SECONDS;
    }

    private static double intersect(DiarizedTranscript.Entry entry, double[] region) {
        return Math.max(0, Math.min(entry.end(), region[1]) - Math.max(entry.start(), region[0]));
    }

    private static String bestLabel(java.util.Map<String, Double> scores) {
        return scores.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse(null);
    }

    public static double rms(float[] samples) {
        if (samples.length == 0) return 0;
        double sum = 0;
        for (float s : samples) sum += (double) s * s;
        return Math.sqrt(sum / samples.length);
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12);
    }

    /** Production separator: windows → temp WAVs → one sidecar /separate
     *  batch → decoded stems; the whole temp tree is removed afterwards. */
    private static List<List<float[]>> sidecarSeparate(List<float[]> windows) {
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("jclaw-sep-");
            var wavs = new ArrayList<Path>(windows.size());
            for (int i = 0; i < windows.size(); i++) {
                var wav = tmpDir.resolve("window-" + i + ".wav");
                Files.write(wav, SpeakerClipExtractor.toWavPcm16(windows.get(i)));
                wavs.add(wav);
            }
            var stemsPerWindow = new PyannoteDiarizationClient().separate(wavs);
            var decoded = new ArrayList<List<float[]>>(stemsPerWindow.size());
            for (var pair : stemsPerWindow) {
                var d = new ArrayList<float[]>(pair.size());
                for (var stem : pair) d.add(WhisperJniTranscriber.ffmpegToPcmF32(stem));
                decoded.add(d);
            }
            return decoded;
        } catch (IOException e) {
            throw new TranscriptionException("failed to stage separation windows: " + e.getMessage(), e);
        } finally {
            if (tmpDir != null) deleteRecursive(tmpDir);
        }
    }

    private static void deleteRecursive(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException _) {} });
        } catch (IOException _) {
            /* temp cleanup is best-effort */
        }
    }

    private static String truncate(String s) {
        var t = s.strip();
        return t.length() > 50 ? t.substring(0, 50) + "…" : t;
    }
}
