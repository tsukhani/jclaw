import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.CompressionMetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-467: CompressionMetrics recording and aggregation. Verifies token-savings
 * rollups, per-type ratios, algorithm usage, inflation-guard tracking, the global
 * CCR hit rate, and the threshold alerts.
 */
class CompressionMetricsTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void recordsAndAggregatesTokenSavings() {
        CompressionMetrics.recordCompression("1", "gpt-4o", "JSON", "json-smartcrush", 1000, 100);
        CompressionMetrics.recordCompression("1", "gpt-4o", "CODE", "code-structural", 500, 200);

        var s = CompressionMetrics.summary("1");
        assertEquals(1200, s.tokensSaved30d(), "900 (JSON) + 300 (CODE) saved");
        assertEquals(1200, s.tokensSaved24h(), "both within the last 24h");
        assertEquals(2, s.ratioByType().size(), "JSON and CODE buckets");
    }

    @Test
    void agentlessEventsAreNotRecorded() {
        // The pure pipeline test seam passes a null agentId — nothing to attribute.
        CompressionMetrics.recordCompression(null, "gpt-4o", "JSON", "json-smartcrush", 1000, 100);
        assertEquals(0, CompressionMetrics.summary("1").tokensSaved30d());
    }

    @Test
    void inflationGuardIsTrackedAndAlertsWhenFrequent() {
        // 1 compression + 1 guard -> 50% guard rate (> 5% threshold) -> alert.
        CompressionMetrics.recordCompression("2", "m", "TEXT", "text-statistical", 300, 250);
        CompressionMetrics.recordInflationGuard("2", "m", "TEXT", 300, 320);

        var s = CompressionMetrics.summary("2");
        assertEquals(1, s.inflationGuardCount());
        assertTrue(s.alerts().stream().anyMatch(a -> a.contains("Inflation-guard rate")),
                "a high guard rate should alert: " + s.alerts());
    }

    @Test
    void ccrHitRateIsTrackedGloballyAndAlertsWhenLow() {
        CompressionMetrics.recordCcrRetrieval("abc", true);
        CompressionMetrics.recordCcrRetrieval("def", false);
        CompressionMetrics.recordCcrRetrieval("ghi", false);

        var s = CompressionMetrics.summary("anything"); // CCR is global, agent id irrelevant
        assertEquals(3, s.ccrRetrievals());
        assertEquals(1, s.ccrHits());
        assertTrue(s.ccrHitRate() < 0.5);
        assertTrue(s.alerts().stream().anyMatch(a -> a.contains("CCR cache hit rate")),
                "a low hit rate should alert: " + s.alerts());
    }

    @Test
    void algorithmUsageCountsPerAlgorithm() {
        CompressionMetrics.recordCompression("4", "m", "JSON", "json-smartcrush", 1000, 100);
        CompressionMetrics.recordCompression("4", "m", "JSON", "json-smartcrush", 800, 80);
        CompressionMetrics.recordCompression("4", "m", "CODE", "code-structural", 500, 200);

        var s = CompressionMetrics.summary("4");
        var json = s.algorithmUsage().stream()
                .filter(a -> a.algorithm().equals("json-smartcrush")).findFirst().orElseThrow();
        assertEquals(2, json.count(), "two json-smartcrush events");
    }
}
