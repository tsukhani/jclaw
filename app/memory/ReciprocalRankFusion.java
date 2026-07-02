package memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Reciprocal Rank Fusion (JCLAW-555, shared with JCLAW-527): combines two or
 * more ranked id lists into one ranking without comparing raw scores. Each
 * list contributes {@code 1 / (k + rank)} for every id it contains (rank is
 * 1-based); ids appearing in multiple lists accumulate. This sidesteps the
 * incomparable-score-scales problem the old weighted sum had (unbounded
 * ts_rank vs bounded cosine on Postgres; BM25 vs cosine on Lucene).
 *
 * <p>Deliberately backend-agnostic and pure: the H2 path fuses Lucene FTS +
 * Lucene KNN legs, and the Postgres retrieval rework (JCLAW-527) fuses
 * ts_rank + pgvector legs through this same helper — fusion is written once,
 * above the backend seam, so dev/test exercise the same logic as production.
 */
public final class ReciprocalRankFusion {

    /** The conventional RRF constant; dampens the impact of top ranks. */
    public static final int DEFAULT_K = 60;

    /** One fused hit: the id and its top-normalized fused score in (0, 1]. */
    public record Ranked(long id, double score) {}

    private ReciprocalRankFusion() {}

    /**
     * Fuse ranked id lists (each best-first) into a single best-first ranking.
     * Scores are normalized against the top fused score so the strongest hit
     * is {@code 1.0} — matching the relevance contract of
     * {@link MemoryStore.MemoryEntry#relevance()} (JCLAW-532). Empty input
     * lists contribute nothing; an entirely empty input yields an empty result.
     */
    @SafeVarargs
    public static List<Ranked> fuse(int k, List<Long>... rankedLists) {
        var scores = new HashMap<Long, Double>();
        for (var list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                scores.merge(list.get(i), 1.0 / (k + i + 1), Double::sum);
            }
        }
        if (scores.isEmpty()) return List.of();
        var fused = new ArrayList<>(scores.entrySet());
        // Descending by fused score; ties broken by id for deterministic output.
        fused.sort((a, b) -> {
            int byScore = Double.compare(b.getValue(), a.getValue());
            return byScore != 0 ? byScore : Long.compare(a.getKey(), b.getKey());
        });
        double top = fused.get(0).getValue();
        var out = new ArrayList<Ranked>(fused.size());
        for (var e : fused) {
            out.add(new Ranked(e.getKey(), e.getValue() / top));
        }
        return out;
    }
}
