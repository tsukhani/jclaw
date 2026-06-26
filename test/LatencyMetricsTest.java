import com.google.gson.JsonObject;
import jobs.LatencyMetricCleanupJob;
import models.LatencyMetric;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.LatencyMetricRecorder;
import services.Tx;
import utils.LatencyStats;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-515 coverage for the time-series latency layer: the async recorder persists
 * samples off the turn path, {@code LatencyStats.aggregate} recomputes percentiles per
 * segment from raw samples (the dashboard's server-side aggregation), and the cleanup
 * job prunes rows past the retention TTL. Assertions key on unique channel markers so
 * they're robust against the process-global recorder queue shared with concurrent tests.
 */
class LatencyMetricsTest extends UnitTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        LatencyMetricRecorder.clear();
    }

    @AfterEach
    void tearDown() {
        LatencyMetricRecorder.clear();
    }

    @Test
    void recorderEnqueueAndFlushPersistsSamples() {
        var ch = "lm-test-enqueue";
        LatencyMetricRecorder.enqueue("7", ch, "total", 120);
        LatencyMetricRecorder.enqueue("7", ch, "ttft", 30);
        LatencyMetricRecorder.enqueue(null, ch, "queue_wait", 5);
        LatencyMetricRecorder.flush();

        List<LatencyMetric> rows = Tx.run(() ->
                LatencyMetric.<LatencyMetric>find("channel = ?1", ch).fetch());
        assertEquals(3, rows.size());
        var total = rows.stream().filter(r -> "total".equals(r.segment)).findFirst().orElseThrow();
        assertEquals("7", total.agentId);
        assertEquals(120L, total.latencyMs);
        assertNotNull(total.createdAt);
        var queue = rows.stream().filter(r -> "queue_wait".equals(r.segment)).findFirst().orElseThrow();
        assertNull(queue.agentId, "an agent-less sample persists with a null agentId");
    }

    @Test
    void recordFunnelsToBothHistogramAndPersistence() {
        // The 4-arg LatencyStats.record is the single funnel: live histogram + persisted enqueue.
        var ch = "lm-test-funnel";
        LatencyStats.record(ch, "total", 200, "42");
        LatencyMetricRecorder.flush();

        List<LatencyMetric> rows = Tx.run(() ->
                LatencyMetric.<LatencyMetric>find("channel = ?1", ch).fetch());
        assertEquals(1, rows.size());
        assertEquals("42", rows.getFirst().agentId);
        assertEquals(200L, rows.getFirst().latencyMs);
    }

    @Test
    void aggregateRecomputesPercentilesPerSegment() {
        var totals = new ArrayList<Long>();
        for (long v = 1; v <= 100; v++) totals.add(v);
        Map<String, Iterable<Long>> bySegment = Map.of("total", totals, "ttft", List.of(50L));

        JsonObject json = LatencyStats.aggregate(bySegment);

        var total = json.getAsJsonObject("total");
        assertEquals(100L, total.get("count").getAsLong());
        assertEquals(1L, total.get("min_ms").getAsLong());
        assertEquals(100L, total.get("max_ms").getAsLong());
        long p50 = total.get("p50_ms").getAsLong();
        assertTrue(p50 >= 49 && p50 <= 51, "p50 of 1..100 should be ~50, was " + p50);
        long p99 = total.get("p99_ms").getAsLong();
        assertTrue(p99 >= 98 && p99 <= 100, "p99 of 1..100 should be ~99, was " + p99);
        assertEquals(1L, json.getAsJsonObject("ttft").get("count").getAsLong());
    }

    @Test
    void cleanupDeletesRowsOlderThanRetention() {
        var ch = "lm-test-cleanup";
        persist(ch, Instant.now().minus(40, ChronoUnit.DAYS)); // expired
        persist(ch, Instant.now().minus(1, ChronoUnit.DAYS));  // recent
        var cutoff = Instant.now().minus(14, ChronoUnit.DAYS);

        int deleted = Tx.run(() -> LatencyMetric.delete("channel = ?1 and createdAt < ?2", ch, cutoff));
        assertEquals(1, deleted);
        long remaining = Tx.run(() -> LatencyMetric.count("channel = ?1", ch));
        assertEquals(1L, remaining);
    }

    @Test
    void resolveRetentionDaysParsing() {
        ConfigService.set("latency.metrics.retentionDays", "7");
        assertEquals(7, LatencyMetricCleanupJob.resolveRetentionDays());
        ConfigService.set("latency.metrics.retentionDays", "0");
        assertEquals(LatencyMetricCleanupJob.RETENTION_DISABLED, LatencyMetricCleanupJob.resolveRetentionDays());
        ConfigService.set("latency.metrics.retentionDays", "garbage");
        assertEquals(LatencyMetricCleanupJob.DEFAULT_RETENTION_DAYS, LatencyMetricCleanupJob.resolveRetentionDays());
        ConfigService.delete("latency.metrics.retentionDays");
        assertEquals(LatencyMetricCleanupJob.DEFAULT_RETENTION_DAYS, LatencyMetricCleanupJob.resolveRetentionDays());
    }

    private static void persist(String channel, Instant createdAt) {
        Tx.run((Runnable) () -> {
            var m = new LatencyMetric();
            m.channel = channel;
            m.segment = "total";
            m.latencyMs = 100;
            m.createdAt = createdAt;
            m.save();
        });
    }
}
