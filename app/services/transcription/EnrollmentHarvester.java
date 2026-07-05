package services.transcription;

import play.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gated enrollment-clip harvesting (JCLAW-609, round 3). Composes every
 * purity instrument the pipeline has, because each one alone has a blind
 * spot the UAT found the hard way:
 *
 * <ul>
 *   <li><b>Overlap purification</b> (caller passes purified segments) —
 *       removes <i>detected</i> cross-talk, but the detector cannot mark a
 *       voice it cannot hear (quiet backchannel under the dominant
 *       speaker).</li>
 *   <li><b>Anchor voiceprint gate</b> (inside
 *       {@link SpeakerClipExtractor#referenceClips}) — rejects candidates
 *       whose embedding does not match the operator-verified lineup clip;
 *       catches sequential turn-mixing but scores the dominant voice, so
 *       simultaneous bleed passes.</li>
 *   <li><b>Separation stem gate</b> (this class) — MossFormer2 splits each
 *       candidate window into two stems; on a pure window the second stem
 *       is incoherent residue (measured cosine ≤ 0.39 against every
 *       speaker), on a bled window it IS the other voice (measured
 *       0.61-0.75). Rejects candidates whose minor stem matches any
 *       speaker voiceprint at {@link #BLEED_THRESHOLD} or above.</li>
 * </ul>
 *
 * The harvest over-provisions candidates, gates them, and trims to the
 * per-speaker duration budget. Best-effort: with no separator (sherpa
 * path, sidecar down) the stem gate is skipped and the anchor-gated
 * harvest stands.
 */
public final class EnrollmentHarvester {

    /** A candidate is bled when its minor separation stem matches ANY
     *  speaker voiceprint at least this closely. Calibrated on the debate
     *  benchmark with 5s-capped windows: pure minor stems measure at most
     *  0.31; gross bleed 0.61-0.75; sparse backchannel (a short affirmation
     *  under the dominant voice) lands between and slid under the original
     *  0.5 gate by dilution in long windows (a 10.7s clip scored 0.391 and
     *  the operator heard the bleed). With clips capped at 5s the fraction
     *  concentrates, and 0.40 keeps clear margin over the pure band. */
    static final double BLEED_THRESHOLD = 0.40;

    /** Harvest overshoot factor: candidates gathered beyond the budget so
     *  gate rejections can be replaced. */
    static final double OVERPROVISION = 1.5;

    private static final int SAMPLE_RATE = 16_000;
    private static final int CHUNK_SAMPLES = 5 * SAMPLE_RATE;

    private EnrollmentHarvester() {}

    /**
     * Harvest gated reference clips for every speaker in {@code speakerIds}.
     * The first clip per speaker is the anchor/lineup cut (never gated away
     * — it is the audio the operator verifies by ear). Returns clips in
     * harvest order, trimmed to {@code targetSeconds} total per speaker.
     *
     * @param separator batch separator ({@link OverlapReattributor.Separator})
     *                  or null to skip the stem gate (no sidecar available)
     */
    public static Map<Integer, List<float[]>> harvest(
            Path audioFile, List<SpeakerSegment> purifiedSegments,
            List<Integer> speakerIds, double targetSeconds, double lineupSeconds,
            double minSeconds, SpeakerClipExtractor.Embedder embedder,
            OverlapReattributor.Separator separator) {
        float[] pcm = WhisperJniTranscriber.ffmpegToPcmF32(audioFile);
        return harvest(pcm, purifiedSegments, speakerIds, targetSeconds, lineupSeconds,
                minSeconds, embedder, separator);
    }

    /** Decoded-samples core — the testable path. */
    public static Map<Integer, List<float[]>> harvest(
            float[] pcm, List<SpeakerSegment> purifiedSegments,
            List<Integer> speakerIds, double targetSeconds, double lineupSeconds,
            double minSeconds, SpeakerClipExtractor.Embedder embedder,
            OverlapReattributor.Separator separator) {
        // Over-provisioned, anchor-gated candidates per speaker.
        var candidates = new LinkedHashMap<Integer, List<float[]>>();
        for (var speaker : speakerIds) {
            candidates.put(speaker, SpeakerClipExtractor.referenceClips(
                    pcm, purifiedSegments, speaker, targetSeconds * OVERPROVISION,
                    lineupSeconds, minSeconds, embedder));
        }

        // JCLAW-618: MossFormer2 emits exactly two stems; with 3+ speakers a
        // stem is a two-voice mixture and the bleed verdicts are meaningless.
        long distinctSpeakers = purifiedSegments.stream()
                .map(SpeakerSegment::speaker).distinct().count();
        var verdicts = separator == null || distinctSpeakers != 2
                ? null
                : stemGateVerdicts(pcm, purifiedSegments, candidates, embedder, separator);

        var out = new LinkedHashMap<Integer, List<float[]>>();
        for (var e : candidates.entrySet()) {
            var kept = new ArrayList<float[]>();
            double total = 0;
            int rejected = 0;
            var speakerVerdicts = verdicts == null ? null : verdicts.get(e.getKey());
            for (int i = 0; i < e.getValue().size(); i++) {
                var clip = e.getValue().get(i);
                // The anchor (i == 0) is never gated away — it is the
                // operator-verified audio and the deepest-interior cut.
                if (i > 0 && speakerVerdicts != null && !speakerVerdicts.get(i)) {
                    rejected++;
                    continue;
                }
                if (total >= targetSeconds) break;
                double remaining = targetSeconds - total;
                if (clip.length / (double) SAMPLE_RATE > remaining && i > 0) {
                    int keep = (int) Math.round(remaining * SAMPLE_RATE);
                    if (keep < SAMPLE_RATE) break; // sub-second trim: stop
                    int from = (clip.length - keep) / 2;
                    clip = Arrays.copyOfRange(clip, from, from + keep);
                }
                kept.add(clip);
                total += clip.length / (double) SAMPLE_RATE;
            }
            if (rejected > 0) {
                Logger.info("EnrollmentHarvester: speaker %d — %d candidate(s) rejected by the "
                        + "stem gate, %.1fs kept", e.getKey(), rejected, total);
            }
            out.put(e.getKey(), kept);
        }
        return out;
    }

    /**
     * Batch-separate every non-anchor candidate and judge each: pure when
     * the minor stem (the one less like the clip's own speaker) matches no
     * speaker voiceprint at {@link #BLEED_THRESHOLD}. Returns per-speaker
     * verdict lists aligned with the candidate lists (index 0 = anchor,
     * always true). Best-effort: any failure passes everything.
     */
    private static Map<Integer, List<Boolean>> stemGateVerdicts(
            float[] pcm, List<SpeakerSegment> purifiedSegments,
            Map<Integer, List<float[]>> candidates,
            SpeakerClipExtractor.Embedder embedder, OverlapReattributor.Separator separator) {
        try {
            var voiceprints = clusterVoiceprints(pcm, purifiedSegments, embedder);
            var windows = new ArrayList<float[]>();
            var owners = new ArrayList<int[]>(); // {speakerId, candidateIndex}
            for (var e : candidates.entrySet()) {
                for (int i = 1; i < e.getValue().size(); i++) {
                    windows.add(e.getValue().get(i));
                    owners.add(new int[]{e.getKey(), i});
                }
            }
            var verdicts = new LinkedHashMap<Integer, List<Boolean>>();
            for (var e : candidates.entrySet()) {
                var list = new ArrayList<Boolean>();
                for (int i = 0; i < e.getValue().size(); i++) list.add(true);
                verdicts.put(e.getKey(), list);
            }
            if (windows.isEmpty()) return verdicts;

            var stemsPerWindow = separator.separate(windows);
            for (int w = 0; w < windows.size(); w++) {
                int speaker = owners.get(w)[0];
                var own = voiceprints.get(speaker);
                if (own == null) continue;
                var stems = stemsPerWindow.get(w);
                if (stems == null || stems.size() < 2) continue;
                var emb0 = embedder.embed(stems.get(0));
                var emb1 = embedder.embed(stems.get(1));
                var minor = OverlapReattributor.cosine(emb0, own)
                        >= OverlapReattributor.cosine(emb1, own) ? emb1 : emb0;
                double bestMatch = Double.NEGATIVE_INFINITY;
                for (var ref : voiceprints.values()) {
                    bestMatch = Math.max(bestMatch, OverlapReattributor.cosine(minor, ref));
                }
                if (bestMatch >= BLEED_THRESHOLD) {
                    verdicts.get(speaker).set(owners.get(w)[1], false);
                    Logger.info("EnrollmentHarvester: candidate %d of speaker %d is bled — "
                                    + "minor stem matches a voice at %.2f",
                            owners.get(w)[1], speaker, bestMatch);
                }
            }
            return verdicts;
        } catch (RuntimeException e) {
            Logger.warn("EnrollmentHarvester: stem gate skipped (%s)", e.getMessage());
            return null;
        }
    }

    /** Chunk-averaged voiceprint per speaker from the purified segments —
     *  the same reference scheme the matcher uses. */
    public static Map<Integer, float[]> clusterVoiceprints(
            float[] pcm, List<SpeakerSegment> segments,
            SpeakerClipExtractor.Embedder embedder) {
        var bySpeaker = new LinkedHashMap<Integer, List<float[]>>();
        var collected = new LinkedHashMap<Integer, Integer>();
        int cap = 4 * CHUNK_SAMPLES;
        for (var seg : segments) {
            int have = collected.getOrDefault(seg.speaker(), 0);
            if (have >= cap) continue;
            int from = (int) Math.clamp(Math.round(seg.start() * SAMPLE_RATE), 0, pcm.length);
            int to = (int) Math.clamp(Math.round(seg.end() * SAMPLE_RATE), from, pcm.length);
            for (int at = from; at + SAMPLE_RATE <= to && have < cap; at += CHUNK_SAMPLES) {
                int len = Math.min(CHUNK_SAMPLES, to - at);
                if (len < SAMPLE_RATE) break;
                bySpeaker.computeIfAbsent(seg.speaker(), _ -> new ArrayList<>())
                        .add(Arrays.copyOfRange(pcm, at, at + len));
                have += len;
            }
            collected.put(seg.speaker(), have);
        }
        var out = new LinkedHashMap<Integer, float[]>();
        for (var e : bySpeaker.entrySet()) {
            float[] avg = null;
            // JCLAW-630: one batched embed call per speaker; JCLAW-623:
            // unit-scale each chunk before averaging.
            for (var emb : embedder.embedAll(e.getValue())) {
                emb = OverlapReattributor.l2normalize(emb);
                if (avg == null) avg = new float[emb.length];
                for (int i = 0; i < emb.length; i++) avg[i] += emb[i];
            }
            for (int i = 0; i < avg.length; i++) avg[i] /= e.getValue().size();
            out.put(e.getKey(), avg);
        }
        return out;
    }
}
