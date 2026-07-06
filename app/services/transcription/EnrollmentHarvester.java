package services.transcription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anchor-gated enrollment-clip harvesting (JCLAW-609; simplified for the
 * whisper+pyannote tier in JCLAW-653). Candidates come from the purified
 * exclusive segments and pass the anchor voiceprint gate inside
 * {@link SpeakerClipExtractor#referenceClips} — candidates whose embedding
 * does not match the operator-verified lineup clip are rejected there. The
 * retired separation stem gate (MossFormer bleed detection) went with the
 * correction stack; quality-critical transcription now lives on the
 * audio-native LLM tier instead.
 */
public final class EnrollmentHarvester {

    private static final int SAMPLE_RATE = 16_000;
    private static final int CHUNK_SAMPLES = 5 * SAMPLE_RATE;

    private EnrollmentHarvester() {}

    /**
     * Harvest anchor-gated reference clips for every speaker in
     * {@code speakerIds}. The first clip per speaker is the anchor/lineup
     * cut (the audio the operator verifies by ear). Returns clips in
     * harvest order, trimmed to {@code targetSeconds} total per speaker.
     */
    public static Map<Integer, List<float[]>> harvest(
            Path audioFile, List<SpeakerSegment> purifiedSegments,
            List<Integer> speakerIds, double targetSeconds, double lineupSeconds,
            double minSeconds, SpeakerClipExtractor.Embedder embedder) {
        float[] pcm = WhisperJniTranscriber.ffmpegToPcmF32(audioFile);
        return harvest(pcm, purifiedSegments, speakerIds, targetSeconds, lineupSeconds,
                minSeconds, embedder);
    }

    /** Decoded-samples core — the testable path. */
    public static Map<Integer, List<float[]>> harvest(
            float[] pcm, List<SpeakerSegment> purifiedSegments,
            List<Integer> speakerIds, double targetSeconds, double lineupSeconds,
            double minSeconds, SpeakerClipExtractor.Embedder embedder) {
        var out = new LinkedHashMap<Integer, List<float[]>>();
        for (var speaker : speakerIds) {
            var candidates = SpeakerClipExtractor.referenceClips(
                    pcm, purifiedSegments, speaker, targetSeconds,
                    lineupSeconds, minSeconds, embedder);
            var kept = new ArrayList<float[]>();
            double total = 0;
            for (int i = 0; i < candidates.size(); i++) {
                var clip = candidates.get(i);
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
            out.put(speaker, kept);
        }
        return out;
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
                emb = VoiceMath.l2normalize(emb);
                if (avg == null) avg = new float[emb.length];
                for (int i = 0; i < emb.length; i++) avg[i] += emb[i];
            }
            for (int i = 0; i < avg.length; i++) avg[i] /= e.getValue().size();
            out.put(e.getKey(), avg);
        }
        return out;
    }
}
