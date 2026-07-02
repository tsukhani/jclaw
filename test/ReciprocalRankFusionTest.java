import memory.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/**
 * JCLAW-555: pure unit coverage for the shared RRF helper. The fusion contract
 * (k = 60, rank-based, top-normalized) is what both vector backends rely on —
 * Lucene FTS + KNN here, ts_rank + pgvector on Postgres (JCLAW-527).
 */
class ReciprocalRankFusionTest extends UnitTest {

    @Test
    void idInBothListsOutranksSingleListIds() {
        // 2 is rank-2 in list A and rank-1 in list B: 1/62 + 1/61.
        // 1 is rank-1 in A only: 1/61. 3 is rank-2 in B only: 1/62.
        var fused = ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K,
                List.of(1L, 2L), List.of(2L, 3L));
        assertEquals(3, fused.size());
        assertEquals(2L, fused.get(0).id(), "the id present in both lists must fuse to the top");
        assertEquals(1L, fused.get(1).id(), "rank-1-in-one-list beats rank-2-in-one-list");
        assertEquals(3L, fused.get(2).id());
    }

    @Test
    void scoresAreTopNormalized() {
        var fused = ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K,
                List.of(1L, 2L), List.of(2L, 3L));
        assertEquals(1.0, fused.get(0).score(), 1e-9, "top hit must normalize to 1.0");
        // score(1) / score(2) = (1/61) / (1/61 + 1/62)
        double expected = (1.0 / 61) / (1.0 / 61 + 1.0 / 62);
        assertEquals(expected, fused.get(1).score(), 1e-9);
        assertTrue(fused.get(2).score() < fused.get(1).score());
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        assertTrue(ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K, List.of(), List.of()).isEmpty());
    }

    @Test
    void singleListPreservesOrder() {
        var fused = ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K, List.of(7L, 5L, 9L));
        assertEquals(List.of(7L, 5L, 9L), fused.stream().map(ReciprocalRankFusion.Ranked::id).toList());
    }

    @Test
    void equalScoresTieBreakDeterministicallyById() {
        // 4 and 2 each appear once at rank 1 of their own list — identical score.
        var fused = ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K, List.of(4L), List.of(2L));
        assertEquals(2L, fused.get(0).id(), "ties order by ascending id for stable output");
        assertEquals(4L, fused.get(1).id());
    }
}
