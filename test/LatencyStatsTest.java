import org.junit.jupiter.api.*;
import play.test.*;
import utils.LatencyStats;

public class LatencyStatsTest extends UnitTest {

    @BeforeEach
    void setup() {
        LatencyStats.reset();
    }

    @Test
    public void captureResetPointPreservesPriorData() {
        LatencyStats.record("total", 100);
        LatencyStats.record("total", 200);

        var resetPoint = LatencyStats.captureResetPoint();

        // Simulate the load-test warmup: a new sample recorded after the
        // snapshot that should be dropped by the restore.
        LatencyStats.record("total", 9999);

        resetPoint.run();

        var snap = LatencyStats.snapshot();
        var total = snap.getAsJsonObject("total");
        assertEquals(2L, total.get("count").getAsLong());
        assertEquals(300L, total.get("sum_ms").getAsLong());
        assertEquals(100L, total.get("min_ms").getAsLong());
        assertEquals(200L, total.get("max_ms").getAsLong());
    }

    @Test
    public void captureResetPointDropsSegmentsCreatedAfterCapture() {
        LatencyStats.record("total", 100);

        var resetPoint = LatencyStats.captureResetPoint();

        // Warmup on a fresh JVM can populate segments that didn't exist at
        // capture time (e.g. queue_wait, ttft). They should be removed.
        LatencyStats.record("ttft", 50);
        LatencyStats.record("queue_wait", 5);

        resetPoint.run();

        var snap = LatencyStats.snapshot();
        assertTrue(snap.has("total"));
        assertFalse(snap.has("ttft"));
        assertFalse(snap.has("queue_wait"));
    }

    @Test
    public void captureResetPointOnEmptyStateRestoresEmptyState() {
        var resetPoint = LatencyStats.captureResetPoint();

        LatencyStats.record("total", 100);
        LatencyStats.record("ttft", 50);

        resetPoint.run();

        var snap = LatencyStats.snapshot();
        assertEquals(0, snap.keySet().size());
    }

    @Test
    public void captureResetPointSupportsMultipleRunsAccumulating() {
        // Simulate three back-to-back load-test runs, each with a warmup
        // sample that must be dropped but whose workload must accumulate.
        for (int run = 1; run <= 3; run++) {
            var resetPoint = LatencyStats.captureResetPoint();
            LatencyStats.record("total", 9999); // warmup
            resetPoint.run();
            // "Workload": 10 samples per run.
            for (int i = 0; i < 10; i++) {
                LatencyStats.record("total", 100 + i);
            }
        }

        var total = LatencyStats.snapshot().getAsJsonObject("total");
        // 30 workload samples, zero warmup contamination.
        assertEquals(30L, total.get("count").getAsLong());
        assertEquals(100L, total.get("min_ms").getAsLong());
        assertEquals(109L, total.get("max_ms").getAsLong());
    }
}
