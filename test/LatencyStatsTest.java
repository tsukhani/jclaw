import org.junit.jupiter.api.*;
import play.test.*;
import utils.LatencyStats;

class LatencyStatsTest extends UnitTest {

    @BeforeEach
    void setup() {
        LatencyStats.reset();
    }

    @Test
    void captureResetPointPreservesPriorData() {
        LatencyStats.record("web", "total", 100);
        LatencyStats.record("web", "total", 200);

        var resetPoint = LatencyStats.captureResetPoint();

        // Simulate the load-test warmup: a new sample recorded after the
        // snapshot that should be dropped by the restore.
        LatencyStats.record("web", "total", 9999);

        resetPoint.run();

        var snap = LatencyStats.snapshot();
        var total = snap.getAsJsonObject("web").getAsJsonObject("total");
        assertEquals(2L, total.get("count").getAsLong());
        assertEquals(300L, total.get("sum_ms").getAsLong());
        assertEquals(100L, total.get("min_ms").getAsLong());
        assertEquals(200L, total.get("max_ms").getAsLong());
    }

    @Test
    void captureResetPointDropsSegmentsCreatedAfterCapture() {
        LatencyStats.record("web", "total", 100);

        var resetPoint = LatencyStats.captureResetPoint();

        // Warmup on a fresh JVM can populate segments that didn't exist at
        // capture time (e.g. queue_wait, ttft). They should be removed.
        LatencyStats.record("web", "ttft", 50);
        LatencyStats.record("web", "queue_wait", 5);

        resetPoint.run();

        var snap = LatencyStats.snapshot();
        var web = snap.getAsJsonObject("web");
        assertTrue(web.has("total"));
        assertFalse(web.has("ttft"));
        assertFalse(web.has("queue_wait"));
    }

    @Test
    void captureResetPointOnEmptyStateRestoresEmptyState() {
        var resetPoint = LatencyStats.captureResetPoint();

        LatencyStats.record("web", "total", 100);
        LatencyStats.record("web", "ttft", 50);

        resetPoint.run();

        var snap = LatencyStats.snapshot();
        assertEquals(0, snap.keySet().size());
    }

    @Test
    void captureResetPointSupportsMultipleRunsAccumulating() {
        // Simulate three back-to-back load-test runs, each with a warmup
        // sample that must be dropped but whose workload must accumulate.
        for (int run = 1; run <= 3; run++) {
            var resetPoint = LatencyStats.captureResetPoint();
            LatencyStats.record("web", "total", 9999); // warmup
            resetPoint.run();
            // "Workload": 10 samples per run.
            for (int i = 0; i < 10; i++) {
                LatencyStats.record("web", "total", 100 + i);
            }
        }

        var total = LatencyStats.snapshot()
                .getAsJsonObject("web")
                .getAsJsonObject("total");
        // 30 workload samples, zero warmup contamination.
        assertEquals(30L, total.get("count").getAsLong());
        assertEquals(100L, total.get("min_ms").getAsLong());
        assertEquals(109L, total.get("max_ms").getAsLong());
    }

    @Test
    void channelsAreIsolated() {
        // JCLAW-102: a sample recorded under "telegram" must not show up
        // under "web" and vice versa.
        LatencyStats.record("web", "total", 100);
        LatencyStats.record("telegram", "total", 5000);

        var snap = LatencyStats.snapshot();
        assertTrue(snap.has("web"));
        assertTrue(snap.has("telegram"));
        var webTotal = snap.getAsJsonObject("web").getAsJsonObject("total");
        var telegramTotal = snap.getAsJsonObject("telegram").getAsJsonObject("total");
        assertEquals(1L, webTotal.get("count").getAsLong());
        assertEquals(1L, telegramTotal.get("count").getAsLong());
        // HdrHistogram at 3 sig digits rounds to ~0.1% resolution — assert
        // proximity rather than exact equality on the raw recorded value.
        assertEquals(100L, webTotal.get("sum_ms").getAsLong());
        assertEquals(5000L, telegramTotal.get("sum_ms").getAsLong());
        long telegramMax = telegramTotal.get("max_ms").getAsLong();
        assertTrue(telegramMax >= 4995L && telegramMax <= 5005L,
                "telegram max should be ~5000ms (got " + telegramMax + ")");
    }

    @Test
    void nullChannelFallsBackToUnknownBucket() {
        // Data never disappears silently — a null or blank channel lands
        // in the "unknown" bucket so the operator can notice and fix.
        LatencyStats.record(null, "total", 42);
        LatencyStats.record("", "ttft", 7);

        var snap = LatencyStats.snapshot();
        assertTrue(snap.has("unknown"));
        var unknown = snap.getAsJsonObject("unknown");
        assertTrue(unknown.has("total"));
        assertTrue(unknown.has("ttft"));
    }

    @Test
    void resetClearsAllChannels() {
        LatencyStats.record("web", "total", 100);
        LatencyStats.record("telegram", "total", 200);
        LatencyStats.record("task", "total", 300);

        LatencyStats.reset();

        assertEquals(0, LatencyStats.snapshot().keySet().size());
    }
}
