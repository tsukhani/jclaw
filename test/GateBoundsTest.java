import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.Certifier;
import services.grapheval.GateBounds.MemoryCounts;
import services.grapheval.RecordBounds;

import java.util.List;

/** JCLAW-1368: the record-level placeholder behind the {@code GateBounds} seam. */
class GateBoundsTest extends UnitTest {

    private static final RecordBounds BOUNDS = RecordBounds.INSTANCE;
    private static final double LIMIT = Certifier.LIMIT;
    private static final double TAIL = Certifier.CONFIDENCE_TAIL;
    private static final int[][] NEEDED = {{0, 59}, {1, 93}, {2, 124}, {3, 153}, {5, 208}};

    private static List<MemoryCounts> written(int n, int wrong) {
        return List.of(new MemoryCounts("m1", n, wrong, 0, 0, 0));
    }

    @Test
    void recordsNeededAtFivePercentAreTheBoundaryRows() {
        for (var row : NEEDED) {
            assertEquals(row[1], BOUNDS.recordsNeeded(row[0], LIMIT, TAIL), row[0] + " wrong");
            assertTrue(BOUNDS.passes(written(row[1], row[0]), LIMIT, TAIL), row[0] + " of " + row[1]);
            assertFalse(BOUNDS.passes(written(row[1] - 1, row[0]), LIMIT, TAIL), row[0] + " of " + (row[1] - 1));
        }
    }

    @Test
    void powerAtOnePercentIsPinned() {
        assertEquals(0.553, BOUNDS.power(written(59, 0), 0.01, LIMIT, TAIL), 5e-4);
        assertEquals(Math.pow(0.99, 59), BOUNDS.power(written(59, 0), 0.01, LIMIT, TAIL), 1e-12);
        assertEquals(0.0, BOUNDS.power(written(58, 0), 0.01, LIMIT, TAIL), "58 records pass at no k");
        assertEquals(0.872, BOUNDS.power(written(124, 0), 0.01, LIMIT, TAIL), 5e-4);
        assertTrue(BOUNDS.power(written(123, 0), 0.01, LIMIT, TAIL) < 0.872 - 0.05, "123 records allow only 1 wrong");
        assertTrue(BOUNDS.power(written(124, 0), 0.03, LIMIT, TAIL) < BOUNDS.power(written(124, 0), 0.01, LIMIT, TAIL));
    }

    @Test
    void nAndKPoolEveryMemoryAndRoundTheAgreedWeightUp() {
        var counts = List.of(new MemoryCounts("m1", 60, 1, 0, 0, 0), new MemoryCounts("m2", 40, 0, 5.0, 0, 0));
        assertEquals(100, RecordBounds.n(counts));
        assertEquals(6, RecordBounds.k(counts), "one wrong plus one sampled wrong at inclusion 0.2");
        assertEquals(Certifier.upperBound(6, 100), BOUNDS.upper(counts, TAIL));
        var thirds = List.of(new MemoryCounts("m1", 100, 0, 1 / 0.3 * 3, 0, 0));
        assertEquals(10, RecordBounds.k(thirds), "three weights of 1 / 0.3 make 10, not 11");
        assertEquals(1, RecordBounds.k(List.of(new MemoryCounts("m1", 100, 0, 0.5, 0, 0))));
    }

    @Test
    void aTailOtherThanFivePercentGoesThroughTheNewOverloads() {
        assertEquals(1 - Math.pow(0.10, 1.0 / 59), Certifier.upperBound(0, 59, 0.10), 1e-9);
        assertEquals(1 - Math.pow(0.10, 1.0 / 59), BOUNDS.upper(written(59, 0), 0.10), 1e-9);
        assertTrue(Certifier.boundPasses(0, 45, LIMIT, 0.10));
        assertFalse(Certifier.boundPasses(0, 44, LIMIT, 0.10));
        assertEquals(45, BOUNDS.recordsNeeded(0, LIMIT, 0.10));
        assertEquals(Certifier.boundPasses(1, 93), Certifier.boundPasses(1, 93, LIMIT, TAIL));
        assertEquals(Certifier.boundPasses(1, 92, 0.05), Certifier.boundPasses(1, 92, 0.05, TAIL));
    }

    /** P(X >= x) for X ~ Binomial(n, p), summed directly. */
    private static double upperTail(int x, int n, double p) {
        double sum = 0;
        for (int k = x; k <= n; k++) {
            double logChoose = 0;
            for (int i = 1; i <= k; i++) logChoose += Math.log((double) n - k + i) - Math.log(i);
            sum += Math.exp(logChoose + k * Math.log(p) + (n - k) * Math.log1p(-p));
        }
        return sum;
    }

    @Test
    void recallLowerIsTheOneSidedClopperPearsonBound() {
        var labelled = List.of(new MemoryCounts("m1", 0, 0, 0, 60, 30), new MemoryCounts("m2", 0, 0, 0, 40, 20));
        double lower = BOUNDS.recallLower(labelled, TAIL);
        assertTrue(lower < 0.5 && lower > 0.40, String.valueOf(lower));
        assertEquals(TAIL, upperTail(50, 100, lower), 1e-6);
        assertEquals(Math.pow(TAIL, 1.0 / 20), BOUNDS.recallLower(List.of(new MemoryCounts("m", 0, 0, 0, 20, 20)), TAIL),
                1e-12);
        assertEquals(0.0, BOUNDS.recallLower(List.of(), TAIL));
        assertEquals(0.0, BOUNDS.recallLower(List.of(new MemoryCounts("m", 0, 0, 0, 20, 0)), TAIL));
    }
}
