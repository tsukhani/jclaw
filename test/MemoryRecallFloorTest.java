import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.search.DirectLuceneMessageSearchRepository;
import services.search.MessageSearchRepository.ScoredId;

import java.util.List;

/**
 * JCLAW-532: the agent-recall relevance floor keeps only hits within a fraction
 * of the top match's score, so a long user message can't pull loosely-related
 * memories into the prompt; {@link DirectLuceneMessageSearchRepository#normalizeByTop}
 * then scales the survivors to a real {@code [0,1]} relevance the recall blend
 * can use instead of rank position. Pure logic tests — no Lucene index required.
 */
class MemoryRecallFloorTest extends UnitTest {

    private static int keep(float[] scores, double ratio) {
        return DirectLuceneMessageSearchRepository.recallFloorCount(scores, ratio);
    }

    @Test
    void ratioZeroOrNegativeKeepsEverything() {
        assertEquals(4, keep(new float[]{5f, 4f, 1f, 0.5f}, 0.0));
        assertEquals(4, keep(new float[]{5f, 4f, 1f, 0.5f}, -1.0));
    }

    @Test
    void dropsHitsBelowTheFloor() {
        // floor = 5 * 0.2 = 1.0 -> keep 5,4,1 (>= 1.0); drop 0.5
        assertEquals(3, keep(new float[]{5f, 4f, 1f, 0.5f}, 0.2));
    }

    @Test
    void highRatioKeepsOnlyTheTopTier() {
        // floor = 5 * 0.99 = 4.95 -> only the top hit survives
        assertEquals(1, keep(new float[]{5f, 4f, 1f}, 0.99));
    }

    @Test
    void tiesAtTopAllSurvive() {
        // identical scores (near-duplicate docs) all clear any floor <= 1.0
        assertEquals(3, keep(new float[]{2f, 2f, 2f}, 0.5));
    }

    @Test
    void emptyScoresKeepsZero() {
        assertEquals(0, keep(new float[]{}, 0.2));
    }

    @Test
    void singleHitAlwaysSurvives() {
        assertEquals(1, keep(new float[]{0.01f}, 0.2));
    }

    @Test
    void normalizeByTopScalesToBestHit() {
        var norm = DirectLuceneMessageSearchRepository.normalizeByTop(
                List.of(new ScoredId(1, 8.0), new ScoredId(2, 4.0), new ScoredId(3, 2.0)));
        assertEquals(1.0, norm.get(0).score(), 1e-9);
        assertEquals(0.5, norm.get(1).score(), 1e-9);
        assertEquals(0.25, norm.get(2).score(), 1e-9);
        // ids and order preserved
        assertEquals(List.of(1L, 2L, 3L), norm.stream().map(ScoredId::id).toList());
    }

    @Test
    void normalizeByTopHandlesEmptyAndNonPositiveTop() {
        assertTrue(DirectLuceneMessageSearchRepository.normalizeByTop(List.of()).isEmpty());
        // A non-positive top score degrades to uniform 1.0 rather than dividing by zero.
        var uniform = DirectLuceneMessageSearchRepository.normalizeByTop(
                List.of(new ScoredId(1, 0.0), new ScoredId(2, 0.0)));
        assertEquals(1.0, uniform.get(0).score(), 1e-9);
        assertEquals(1.0, uniform.get(1).score(), 1e-9);
    }
}
