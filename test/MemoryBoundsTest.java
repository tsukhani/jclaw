import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.Certifier;
import services.grapheval.GateBounds.MemoryCounts;
import services.grapheval.MemoryBounds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** JCLAW-1369: the memory-clustered bound behind the {@code GateBounds} seam. */
class MemoryBoundsTest extends UnitTest {

    private static final MemoryBounds BOUNDS = MemoryBounds.INSTANCE;
    private static final double LIMIT = Certifier.LIMIT;
    private static final double TAIL = Certifier.CONFIDENCE_TAIL;

    /** {@code n} writing memories, each one clean record, with {@code extra} entries replacing the first ones. */
    private static List<MemoryCounts> memories(int n, MemoryCounts... extra) {
        var out = new ArrayList<MemoryCounts>();
        for (var e : extra) out.add(e);
        for (int i = extra.length; i < n; i++) out.add(new MemoryCounts("m" + i, 1, 0, 0, 0, 0));
        return out;
    }

    @Test
    void theWeightedRuleCountsOneUnmatchedWrongAndOneSampledWrongAtAFifthAsSix() {
        var writing = memories(60, new MemoryCounts("a", 3, 1, 0, 0, 0), new MemoryCounts("b", 2, 0, 5.0, 0, 0));
        assertEquals(60, MemoryBounds.n(writing));
        assertEquals(6, MemoryBounds.k(writing));
        double upper = BOUNDS.upper(writing, TAIL);
        assertEquals(Certifier.upperBound(6, 60, TAIL), upper);
        assertEquals(0.18786, upper, 5e-5);
    }

    @Test
    void theBoundIsTheExactClopperPearsonBoundOverMemories() {
        double upper = BOUNDS.upper(memories(60, new MemoryCounts("a", 1, 1, 0, 0, 0),
                new MemoryCounts("b", 1, 0, 5.0, 0, 0)), TAIL);
        assertEquals(TAIL, Math.exp(logCdf(6, 60, upper)), 1e-9);
    }

    @Test
    void aWeightInAMemoryAlreadyWrongAddsNothing() {
        var writing = memories(60, new MemoryCounts("a", 4, 2, 5.0, 0, 0));
        assertEquals(1, MemoryBounds.k(writing));
    }

    @Test
    void weightsAreRoundedUpWithTheRecordBoundsSlack() {
        double w = 1 / 0.3;
        var writing = memories(60, new MemoryCounts("a", 1, 0, w, 0, 0), new MemoryCounts("b", 1, 0, w, 0, 0),
                new MemoryCounts("c", 1, 0, w, 0, 0));
        assertEquals(10, MemoryBounds.k(writing), "three weights of 1 / 0.3 make 10, not 11");
        assertEquals(1, MemoryBounds.k(memories(60, new MemoryCounts("a", 1, 0, 0.5, 0, 0))));
    }

    @Test
    void kIsCappedAtTheWritingMemories() {
        var writing = memories(3, new MemoryCounts("a", 1, 0, 50.0, 0, 0));
        assertEquals(3, MemoryBounds.k(writing));
        assertEquals(1.0, BOUNDS.upper(writing, TAIL));
    }

    @Test
    void zeroWrongGivesTheExactZeroWrongBoundNeverZero() {
        for (int n : new int[] {1, 29, 60, 130}) {
            assertEquals(1 - Math.pow(TAIL, 1.0 / n), BOUNDS.upper(memories(n), TAIL), 1e-9, n + " memories");
        }
        assertEquals(0.048703, BOUNDS.upper(memories(60), TAIL), 5e-7);
        assertTrue(BOUNDS.upper(memories(60), TAIL) > 0);
    }

    @Test
    void anEmptyGateNeverPasses() {
        assertFalse(BOUNDS.passes(List.of(), LIMIT, TAIL));
        assertEquals(1.0, BOUNDS.upper(List.of(), TAIL));
        assertEquals(0.0, BOUNDS.power(List.of(), 0.01, LIMIT, TAIL));
    }

    @Test
    void entriesThatWroteNothingCountInNeitherNNorK() {
        var writing = memories(60, new MemoryCounts("x", 0, 1, 5.0, 3, 1), new MemoryCounts("y", 0, 0, 0, 2, 2));
        assertEquals(58, MemoryBounds.n(writing));
        assertEquals(0, MemoryBounds.k(writing));
    }

    @Test
    void memoriesNeededAtZeroWrongForEveryScope() {
        assertEquals(59, BOUNDS.recordsNeeded(0, LIMIT, 0.05));
        assertEquals(80, BOUNDS.recordsNeeded(0, LIMIT, 0.05 / 3));
        assertEquals(109, BOUNDS.recordsNeeded(0, LIMIT, 0.05 / 13));
        assertEquals(130, BOUNDS.recordsNeeded(0, LIMIT, 0.05 / 39));
        assertEquals(0.05 / 39, Certifier.BASE_GATE_TAIL);
    }

    @Test
    void memoriesNeededAreTheBoundaryRowsAtBothTails() {
        int[] wrong = {0, 1, 2, 3, 5};
        int[] corrected = {130, 176, 215, 251, 317};
        int[] uncorrected = {59, 93, 124, 153, 208};
        for (int i = 0; i < wrong.length; i++) {
            boundary(wrong[i], corrected[i], Certifier.BASE_GATE_TAIL);
            boundary(wrong[i], uncorrected[i], TAIL);
        }
    }

    private static void boundary(int wrong, int needed, double tail) {
        assertEquals(needed, BOUNDS.recordsNeeded(wrong, LIMIT, tail), wrong + " wrong at " + tail);
        assertTrue(BOUNDS.passes(withWrong(needed, wrong), LIMIT, tail), wrong + " of " + needed);
        assertFalse(BOUNDS.passes(withWrong(needed - 1, wrong), LIMIT, tail), wrong + " of " + (needed - 1));
    }

    private static List<MemoryCounts> withWrong(int n, int wrong) {
        var out = new ArrayList<MemoryCounts>();
        for (int i = 0; i < n; i++) out.add(new MemoryCounts("m" + i, 2, i < wrong ? 2 : 0, 0, 0, 0));
        return out;
    }

    @Test
    void powerIsTheChanceOfAtMostTheLargestPassingKOverMemories() {
        assertEquals(Math.pow(0.99, 130), BOUNDS.power(memories(130), 0.01, LIMIT, Certifier.BASE_GATE_TAIL), 1e-12);
        assertEquals(0.0, BOUNDS.power(memories(129), 0.01, LIMIT, Certifier.BASE_GATE_TAIL));
    }

    private static List<MemoryCounts> labelled(int seed, int count) {
        var random = new Random(seed);
        var out = new ArrayList<MemoryCounts>();
        for (int i = 0; i < count; i++) {
            int gold = 1 + random.nextInt(5);
            out.add(new MemoryCounts("m" + i, 0, 0, 0, gold, random.nextInt(gold + 1)));
        }
        return out;
    }

    private static double point(List<MemoryCounts> labelled) {
        return (double) labelled.stream().mapToInt(MemoryCounts::right).sum()
                / labelled.stream().mapToInt(MemoryCounts::gold).sum();
    }

    @Test
    void recallIsDeterministicInAnyOrderAndAtMostThePointEstimate() {
        var labelled = labelled(7, 80);
        double lower = BOUNDS.recallLower(labelled, TAIL);
        assertEquals(lower, BOUNDS.recallLower(labelled, TAIL));
        var shuffled = new ArrayList<>(labelled);
        Collections.shuffle(shuffled, new Random(3));
        assertEquals(lower, BOUNDS.recallLower(shuffled, TAIL));
        assertTrue(lower <= point(labelled), lower + " vs " + point(labelled));
        assertTrue(lower > point(labelled) - 0.2, "a bootstrap bound, not a floor: " + lower);
        assertEquals(10_000, MemoryBounds.RESAMPLES);
    }

    @Test
    void recallIsTheEstimateWhenEveryMemoryIsRightAndZeroWithNothingLabelled() {
        var all = new ArrayList<MemoryCounts>();
        for (int i = 0; i < 40; i++) all.add(new MemoryCounts("m" + i, 0, 0, 0, 3, 3));
        assertEquals(1.0, BOUNDS.recallLower(all, TAIL));
        assertEquals(0.0, BOUNDS.recallLower(List.of(), TAIL));
        assertEquals(0.0, BOUNDS.recallLower(List.of(new MemoryCounts("m", 2, 0, 0, 0, 0)), TAIL));
    }

    /** log P(X <= x) for X ~ Binomial(n, p), summed directly. */
    private static double logCdf(int x, int n, double p) {
        double sum = 0;
        for (int k = 0; k <= x; k++) {
            double logChoose = 0;
            for (int i = 1; i <= k; i++) logChoose += Math.log((double) n - k + i) - Math.log(i);
            sum += Math.exp(logChoose + k * Math.log(p) + (n - k) * Math.log1p(-p));
        }
        return Math.log(sum);
    }
}
