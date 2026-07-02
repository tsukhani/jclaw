import memory.MemoryReranker;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/**
 * JCLAW-527: the cross-encoder rerank's pure logic — output parsing/repair and
 * the fail-open contract. Deliberately does NOT install the static
 * {@code setRankCallForTest} override: this class runs concurrently with the
 * store tests (play1 unit + functional lanes), and an installed override makes
 * {@code MemoryReranker.active()} true process-wide. The override-driven
 * end-to-end pass lives in {@code JpaMemoryStoreRerankTest}, which serializes
 * under the LuceneTestSync lock.
 */
class MemoryRerankerTest extends UnitTest {

    // --- parseOrder: repair the model's index array into a full permutation ---

    @Test
    void validPermutationIsTakenVerbatim() {
        assertEquals(List.of(2, 0, 1), MemoryReranker.parseOrder("[2,0,1]", 3));
    }

    @Test
    void codeFencedOutputIsTolerated() {
        assertEquals(List.of(1, 0), MemoryReranker.parseOrder("```json\n[1,0]\n```", 2));
    }

    @Test
    void outOfRangeAndDuplicateIndicesAreDroppedAndOmissionsAppendedInFusedOrder() {
        // 5 is out of range, the second 1 is a duplicate; 2 was omitted by the
        // model so it re-enters last, keeping its fused position relative to
        // nothing the model ranked.
        assertEquals(List.of(1, 0, 2), MemoryReranker.parseOrder("[5,1,1,0]", 3));
    }

    @Test
    void nonJsonAndEmptyOutputsYieldIdentity() {
        assertEquals(List.of(0, 1, 2), MemoryReranker.parseOrder("sure, here is the ranking:", 3));
        assertEquals(List.of(0, 1, 2), MemoryReranker.parseOrder("[]", 3));
        assertEquals(List.of(0, 1, 2), MemoryReranker.parseOrder("{\"order\":[1,2,0]}", 3));
    }

    // --- rerank: fail-open plumbing ---

    @Test
    void singletonShortlistSkipsTheCallEntirely() {
        assertEquals(List.of(0), MemoryReranker.rerank("query", List.of("only candidate")));
    }

    @Test
    void missingProviderOrModelFailsOpenToIdentity() {
        // No override installed and the test environment has no primary LLM
        // provider / no memory.rerank.model — the production call must decline
        // and the fused (identity) order must come back untouched.
        assertEquals(List.of(0, 1), MemoryReranker.rerank("query", List.of("a", "b")));
    }
}
