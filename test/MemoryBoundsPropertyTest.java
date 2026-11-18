import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.GateBounds.MemoryCounts;
import services.grapheval.MemoryBounds;

import java.util.LinkedHashMap;
import java.util.List;

class MemoryBoundsPropertyTest extends UnitTest {

    /** A memory with 1-5 gold records, any number of them right. */
    private static final Generator<MemoryCounts> MEMORY = Generator.from(env -> {
        int id = env.generate(Generator.integers(0, 999));
        int gold = env.generate(Generator.integers(1, 5));
        int right = env.generate(Generator.integers(0, gold));
        return new MemoryCounts("m" + id, 0, 0, 0, gold, right);
    });

    /** 1-40 memories with distinct ids: a repeated id keeps its first draw. */
    private static final Generator<List<MemoryCounts>> LABELED_MEMORIES =
            Generator.listsOf(IntDistribution.uniform(1, 40), MEMORY).map(MemoryBoundsPropertyTest::distinctIds);

    // 30 iterations: two 10,000-resample bootstraps over at most 40 memories each, about 2 ms.
    @Test
    void theClusteredRecallBoundIsRepeatableAndNeverAboveTheEstimate() {
        PropertyInputs.check(30, LABELED_MEMORIES, labeled -> {
            double lower = MemoryBounds.INSTANCE.recallLower(labeled, 0.05);
            double point = (double) labeled.stream().mapToInt(MemoryCounts::right).sum()
                    / labeled.stream().mapToInt(MemoryCounts::gold).sum();
            assertTrue(lower <= point, () -> "lower " + lower + " above the estimate " + point + " for " + labeled);
            assertEquals(lower, MemoryBounds.INSTANCE.recallLower(labeled, 0.05), () -> "a repeat differed for " + labeled);
        });
    }

    private static List<MemoryCounts> distinctIds(List<MemoryCounts> memories) {
        var byId = new LinkedHashMap<String, MemoryCounts>();
        memories.forEach(m -> byId.putIfAbsent(m.memoryId(), m));
        return List.copyOf(byId.values());
    }
}
