package services.transcription;

import services.ConfigService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The one place that owns the diarization stage sequence (JCLAW-628):
 * cached diarization → enrolled-voice naming → (optionally halt for
 * identification) → transcription → word-split → merge → overlap
 * re-attribution/MSDD/under-speech → emotion. The diarize tool and the
 * REST endpoint are thin callers; before this class they hand-sequenced
 * the same seven stages and had already diverged (the endpoint ignored
 * the cache, transcribed BEFORE diarizing, and had no identification-first
 * mode).
 *
 * <p>Identification-first is a caller option: only the tool enables it
 * (it can converse — stage a lineup, ask the user, enroll); the stateless
 * API always produces a transcript.
 */
public final class DiarizationPipeline {

    /** Caller knobs. {@code identificationFirst} halts before transcription
     *  when voices are unknown (tool-only); {@code allowAnonymous} waives
     *  that halt. {@code cacheable} = false for transient inputs (JCLAW-636:
     *  the REST endpoint's multipart temp uploads get a fresh path per
     *  request, so sibling cache files could never HIT and outlived Play's
     *  temp-file cleanup — pure litter). */
    public record Options(Integer numSpeakers, String language, boolean identificationFirst,
                          boolean allowAnonymous, boolean cacheable) {
        public Options(Integer numSpeakers, String language, boolean identificationFirst,
                       boolean allowAnonymous) {
            this(numSpeakers, language, identificationFirst, allowAnonymous, true);
        }
    }

    /** Either a finished transcript or a request to identify voices. */
    public sealed interface Outcome permits Transcript, IdentificationNeeded {}

    public record Transcript(List<DiarizedTranscript.Entry> entries,
                             DiarizationRouter.Result diarization) implements Outcome {}

    /** Voices unknown and the caller wants identification-first: no
     *  transcription was paid; the caller stages the lineup. */
    public record IdentificationNeeded(DiarizationRouter.Result diarization,
                                       Map<Integer, String> names,
                                       List<Integer> unmatched) implements Outcome {}

    private DiarizationPipeline() {}

    public static Outcome run(Path audio, Options opts) {
        var diarization = opts.cacheable()
                ? cachedDiarization(audio, opts.numSpeakers())
                : DiarizationRouter.diarizeRich(audio, opts.numSpeakers() == null ? -1 : opts.numSpeakers());
        var speakers = diarization.segments();
        // JCLAW-640: decode the recording at most ONCE per run and share the
        // array — the stages previously each spawned their own full ffmpeg
        // decode (~3.8 MB/minute of transient heap apiece). Lazy: a run that
        // halts for identification without enrollment never decodes at all.
        var pcmHolder = new float[1][];
        java.util.function.Supplier<float[]> pcm = () -> {
            if (pcmHolder[0] == null) pcmHolder[0] = WhisperJniTranscriber.ffmpegToPcmF32(audio);
            return pcmHolder[0];
        };
        var names = SpeakerNamer.enrollmentPresent()
                ? SpeakerNamer.nameSpeakers(pcm.get(), speakers, (float) ConfigService.getDouble(
                        "transcription.diarization.speakerMatchThreshold", 0.6))
                : Map.<Integer, String>of();

        if (opts.identificationFirst() && !opts.allowAnonymous()) {
            var unmatched = speakers.stream().map(SpeakerSegment::speaker)
                    .distinct().filter(id -> !names.containsKey(id)).toList();
            if (!unmatched.isEmpty()) {
                return new IdentificationNeeded(diarization, names, unmatched);
            }
        }

        // JCLAW-646: launch the MSDD second opinion NOW — its minutes of
        // CPU-only work run concurrently with every GPU stage below and are
        // usually fully hidden by them.
        var msdd = ConfigService.getBoolean(OverlapReattributor.ENABLED_KEY, true)
                ? OverlapReattributor.startMsdd(audio, pcm.get(), diarization)
                : null;

        var model = WhisperModel.byId(ConfigService.get("transcription.localModel"))
                .orElse(WhisperModel.DEFAULT);
        // JCLAW-629: raw ASR is deterministic given (audio, model, language)
        // — cache it beside the diarization so the post-enrollment second
        // round performs no full-recording inference at all.
        var transcript = opts.cacheable()
                ? DiarizationCache.readTranscript(audio, model.id(), opts.language()) : null;
        if (transcript == null) {
            transcript = WhisperJniTranscriber.transcribeSegments(audio, model, opts.language());
            if (opts.cacheable()) {
                DiarizationCache.writeTranscript(audio, model.id(), opts.language(), transcript);
            }
        }
        // JCLAW-603: word-level split of boundary-straddling segments.
        // (JCLAW-651 r2/r3 verdict: full word-level attribution exists
        // dormant — stampTranscript + mergeWords (hard, 28.24 cpWER) +
        // mergeWordsViterbi (smoothed, 28.71) — and is measurably NET
        // NEGATIVE against this path's 24.02, but not for the naive
        // reason: word-shaped entries silently invalidate every
        // downstream calibration (MSDD sustained-speech thresholds, the
        // mixed gates, carver windows were all bench-tuned on
        // segment-merge entry shapes; the flagship MSDD flip stops firing
        // on word-shaped entries). Adopting word-level attribution means
        // recalibrating the whole correction stack through the harnesses
        // — a campaign, not a merge swap. Engine word stamps are
        // separately disqualified: enabling them perturbs the decode.)
        // JCLAW-652: word-native joint decode — one evidence-fusion Viterbi
        // pass replaces merge + the entry-level correction stack. Off by
        // default; flips only when it beats the segment path on the gold
        // benchmark with zero stable-turn regressions (criteria on ticket).
        List<DiarizedTranscript.Entry> entries = null;
        if (ConfigService.getBoolean("transcription.diarization.wordNative", false)) {
            try {
            var stamped = CtcForcedAligner.stampTranscript(transcript, pcm.get());
            if (stamped != null) {
                // E3: MSDD frame coverage joins the emission model — join
                // the second-opinion future (usually already done; its
                // CPU work ran under the GPU stages).
                var msddSegments = msdd == null ? null : msdd.get();
                // E4-A (measured, then unwired): word-span stem evidence
                // scored 25.43 vs E3's 24.80 — stems win at whole-turn
                // windows but are noise at 0.7s word slices (cross-stem
                // bleed swamps the voice signal). buildStemScorer stays as
                // the seam if a longer-slice variant is ever tried.
                entries = DiarizedTranscript.mergeWordsViterbiFused(
                        stamped, speakers, diarization.rawSegments(),
                        msddSegments == null ? java.util.List.of() : msddSegments, names,
                        null);
                if (entries != null) {
                    play.Logger.info("DiarizationPipeline: word-native joint decode (%d entries)",
                            entries.size());
                    // NOTE (E2b, measured): do NOT compose the entry-level
                    // correction stack onto decode-shaped entries — 13 giant
                    // entries made every one contested (969 embed calls vs
                    // ~100 typical) and the run killed the JVM mid
                    // under-speech pass. Long-span voice evidence belongs in
                    // the EMISSIONS (ticket stages E3/E4), not post-hoc.
                    // E4-B (measured, then removed): under-speech recovery
                    // composes mechanically (recoverUnderSpeech: region-sized
                    // windows, no decision loop) but is NET NEGATIVE here —
                    // 24.96 vs 24.80. Its embedding-based speaker pick fails
                    // in the echo zone the decode already attributes
                    // correctly, inserting wrong-speaker duplicate words
                    // ("No, no, no." -> Firdaus at 76.5s, human-verified
                    // Podcaster). A guarded revival needs stem-text
                    // agreement with the decode, not embeddings alone.
                }
            }
            } catch (RuntimeException e) {
                // Word-native is an OPTIMIZED path, never a new failure
                // mode: any error (undecodable audio before the lazy pcm
                // decode, aligner faults, ...) falls back to the segment
                // path, which stays lazy about pcm.
                play.Logger.warn("DiarizationPipeline: word-native decode failed (%s) — segment path",
                        e.getMessage());
                entries = null;
            }
        }
        if (entries == null) {
            transcript = SegmentWordSplitter.split(transcript, speakers, pcm);
            entries = DiarizedTranscript.merge(transcript, speakers, names);
            // JCLAW-605/612/613: overlap re-attribution, MSDD second opinion,
            // under-speech recovery. Best-effort inside reattribute().
            if (ConfigService.getBoolean(OverlapReattributor.ENABLED_KEY, true)) {
                entries = OverlapReattributor.reattribute(entries, diarization, audio, pcm.get(), msdd);
            }
            // JCLAW-651: word-granular interjection carving — brief secondary
            // activations from the raw annotation reclaim words the exclusive
            // projection handed to the dominant voice, voiceprint-guarded.
            // After the turn-level corrections (their flips settle the parent
            // labels), before emotion (fragments get their own labels).
            if (ConfigService.getBoolean(OverlapReattributor.ENABLED_KEY, true)) {
                entries = InterjectionCarver.carve(entries, diarization, names, pcm.get());
            }
        }
        // JCLAW-563: per-turn acoustic emotion labels. Best-effort.
        if (ConfigService.getBoolean("transcription.emotion.enabled", true)) {
            entries = EmotionRecognizer.annotateBestEffort(pcm.get(), entries);
        }
        return new Transcript(entries, diarization);
    }

    /**
     * JCLAW-652 E4-A: word-span stem evidence for the fused decode.
     * Separates the overlap regions once (E2b-safe shape), builds
     * per-cluster voiceprints from exclusive-confident spans, and scores a
     * contested word by the best stem's cosine margin toward each cluster.
     * Null (feature off) when the setup cannot produce two references.
     */
    private static DiarizedTranscript.StemScorer buildStemScorer(
            DiarizationRouter.Result diarization, float[] pcm) {
        try {
            if (diarization.overlaps().isEmpty()) return null;
            var refs = OverlapReattributor.clusterReferences(
                    diarization.segments(), diarization.overlaps(), pcm);
            if (refs.size() != 2) return null;
            var ctx = OverlapReattributor.separatedOverlapWindows(diarization.overlaps(), pcm);
            if (ctx.windows().isEmpty()) return null;
            var clusters = refs.keySet().stream().sorted().toList();
            return (start, end) -> {
                // Find the window containing this word; pad the slice to a
                // usable embed length around the word's center.
                for (int w = 0; w < ctx.windows().size(); w++) {
                    double ws = ctx.starts().get(w);
                    double weEnd = ws + ctx.windows().get(w).length / 16_000.0;
                    if (start < ws || end > weEnd) continue;
                    double mid = (start + end) / 2;
                    double half = Math.max((end - start) / 2 + 0.1, 0.35);
                    int from = (int) Math.clamp(Math.round((mid - half - ws) * 16_000), 0,
                            ctx.windows().get(w).length);
                    int to = (int) Math.clamp(Math.round((mid + half - ws) * 16_000), from,
                            ctx.windows().get(w).length);
                    if (to - from < 8_000) return null;
                    var slices = new java.util.ArrayList<float[]>();
                    for (var stem : ctx.stems().get(w)) {
                        if (stem.length < to) continue;
                        var slice = new float[to - from];
                        System.arraycopy(stem, from, slice, 0, slice.length);
                        slices.add(slice);
                    }
                    if (slices.isEmpty()) return null;
                    var embs = SidecarEmbedder.INSTANCE.embedAll(slices);
                    var out = new double[clusters.size()];
                    for (int c = 0; c < clusters.size(); c++) {
                        var own = refs.get(clusters.get(c));
                        var other = refs.get(clusters.get(1 - c));
                        double best = 0;
                        for (var emb : embs) {
                            best = Math.max(best, OverlapReattributor.cosine(emb, own)
                                    - OverlapReattributor.cosine(emb, other));
                        }
                        out[c] = best;
                    }
                    return out;
                }
                return null;
            };
        } catch (RuntimeException e) {
            play.Logger.warn("DiarizationPipeline: stem scorer unavailable: %s", e.getMessage());
            return null;
        }
    }

    /** Cache-through diarization (JCLAW-611/621): repeated visits to the
     *  same attachment see IDENTICAL segments. */
    public static DiarizationRouter.Result cachedDiarization(Path path, Integer numSpeakers) {
        int n = numSpeakers == null ? -1 : numSpeakers;
        var cached = DiarizationCache.read(path, n);
        if (cached != null) return cached;
        var result = DiarizationRouter.diarizeRich(path, n);
        DiarizationCache.write(path, n, result);
        return result;
    }
}
