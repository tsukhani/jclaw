package services.transcription;

import java.util.List;

/**
 * Shared voiceprint math for the diarization pipeline: the embedder seam
 * plus the cosine/normalization primitives used by speaker naming,
 * enrollment harvesting and lineup-clip extraction (JCLAW-653 — extracted
 * from the retired correction stack; these primitives are load-bearing for
 * identification, which the simple whisper+pyannote tier keeps).
 */
public final class VoiceMath {

    private VoiceMath() {}

    /** Embedding seam. Production is {@link SidecarEmbedder#INSTANCE},
     *  whose override batches windows into one sidecar call (JCLAW-630);
     *  fakes get it for free via the default. */
    @FunctionalInterface
    public interface Embedder {
        float[] embed(float[] samples);

        default List<float[]> embedAll(List<float[]> windows) {
            var out = new java.util.ArrayList<float[]>(windows.size());
            for (var w : windows) out.add(embed(w));
            return out;
        }
    }

    public static double cosine(float[] a, float[] b) {
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

    /** Unit-scale a vector in place; centroid averaging depends on
     *  per-chunk weighting, not the final scale (JCLAW-623). */
    public static float[] l2normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm < 1e-12) return v;
        for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
        return v;
    }
}
