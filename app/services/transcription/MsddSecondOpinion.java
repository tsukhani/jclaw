package services.transcription;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MSDD second opinion (JCLAW-612). NeMo's multi-scale diarization decoder
 * (TS-VAD lineage) tracks each speaker profile frame-by-frame with temporal
 * continuity, which resolves soft overlapped speech that isolated-window
 * embeddings misjudge — the JCLAW-610 spike measured it natively correct on
 * every turn the embedding stack found adversarial. Its complementary
 * weakness is brief interjections (coarse frame grid), so its verdict is
 * only adopted when it heard SUSTAINED speech in the window:
 *
 * <ul>
 *   <li>{@link #MIN_ACTIVE_SECONDS}: the spike's wrong flips all had
 *       ≤ 0.82s of MSDD-active speech in the window, the correct ones
 *       ≥ 1.28s — 1.0s sits in the measured gap.</li>
 *   <li>{@link #DOMINANCE}: the winning label must carry at least this
 *       fraction of the window's MSDD-active time (correct flips measured
 *       0.84-1.00).</li>
 * </ul>
 *
 * Pure functions over segment lists — no I/O, no natives.
 */
public final class MsddSecondOpinion {

    static final double MIN_ACTIVE_SECONDS = 1.0;
    static final double DOMINANCE = 0.8;

    private MsddSecondOpinion() {}

    /**
     * Map MSDD's speaker indices to transcript labels by majority
     * time-overlap with the UNCONTESTED turns (contested turns are the ones
     * under dispute — using them would let the disputed labels vote).
     */
    public static Map<Integer, String> mapSpeakers(
            List<DiarizedTranscript.Entry> entries, Set<Integer> contestedIndexes,
            List<SpeakerSegment> msdd) {
        var votes = new HashMap<Integer, Map<String, Double>>();
        for (int i = 0; i < entries.size(); i++) {
            if (contestedIndexes.contains(i)) continue;
            var entry = entries.get(i);
            for (var seg : msdd) {
                double ov = Math.min(seg.end(), entry.end()) - Math.max(seg.start(), entry.start());
                if (ov <= 0) continue;
                votes.computeIfAbsent(seg.speaker(), _ -> new HashMap<>())
                        .merge(entry.speaker(), ov, Double::sum);
            }
        }
        var mapping = new LinkedHashMap<Integer, String>();
        for (var e : votes.entrySet()) {
            mapping.put(e.getKey(), e.getValue().entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElseThrow().getKey());
        }
        return mapping;
    }

    /**
     * MSDD's verdict for one contested turn: the label whose mapped tracks
     * dominate the window's active time — or null when MSDD's evidence is
     * below the sustained-speech thresholds (its interjection blind spot).
     */
    public static String verdict(DiarizedTranscript.Entry entry,
                                 List<SpeakerSegment> msdd,
                                 Map<Integer, String> mapping) {
        var active = new HashMap<String, Double>();
        for (var seg : msdd) {
            double ov = Math.min(seg.end(), entry.end()) - Math.max(seg.start(), entry.start());
            if (ov <= 0) continue;
            var label = mapping.get(seg.speaker());
            if (label != null) active.merge(label, ov, Double::sum);
        }
        if (active.isEmpty()) return null;
        var best = active.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        double total = active.values().stream().mapToDouble(Double::doubleValue).sum();
        if (best.getValue() < MIN_ACTIVE_SECONDS || best.getValue() / total < DOMINANCE) {
            return null;
        }
        return best.getKey();
    }
}
