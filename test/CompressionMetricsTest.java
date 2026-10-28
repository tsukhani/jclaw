import models.CompressionMetric;
import models.CompressionMetric.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.CompressionMetrics;

/**
 * JCLAW-467: CompressionMetrics recording + reset. Aggregation is done client-side
 * over the raw rows the metrics endpoint returns (like the Chat Cost dashboard),
 * so the service only writes and clears — that's what this exercises.
 */
class CompressionMetricsTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void recordsCompressionEventsWithAgentAndChannel() {
        CompressionMetrics.recordCompression("1", "web", "gpt-4o", "JSON", "json-smartcrush", 1000, 100);
        CompressionMetrics.recordInflationGuard("1", "telegram", "gpt-4o", "TEXT", 300, 320);

        assertEquals(2L, CompressionMetric.count());
        CompressionMetric json = CompressionMetric.<CompressionMetric>find("kind = ?1", Kind.COMPRESSION).first();
        assertEquals("1", json.agentId);
        assertEquals("web", json.channel, "the conversation channel is recorded");
        assertEquals(900, json.tokensBefore - json.tokensAfter);
    }

    @Test
    void agentlessEventsAreNotRecorded() {
        CompressionMetrics.recordCompression(null, "web", "gpt-4o", "JSON", "json-smartcrush", 1000, 100);
        assertEquals(0L, CompressionMetric.count());
    }

    @Test
    void ccrRetrievalRecordsHitAndMissWithTheAgentAndTheFetchedTool() {
        CompressionMetrics.recordCcrRetrieval(AGENT, "web_fetch", true);
        CompressionMetrics.recordCcrRetrieval(AGENT, null, false);

        assertEquals(2L, CompressionMetric.count("kind = ?1 and agentId = ?2", Kind.CCR_RETRIEVAL, AGENT));
        CompressionMetric hit = ccrRow(true);
        assertEquals("web_fetch", hit.toolName);
        assertNull(ccrRow(false).toolName);
    }

    @Test
    void aMissNeverCarriesATool() {
        CompressionMetrics.recordCcrRetrieval(AGENT, "web_fetch", false);

        assertNull(ccrRow(false).toolName);
    }

    /** Concurrent classes record retrievals for their own agents, so rows are read by this class's agent id. */
    private static final String AGENT = "compression-metrics-test";

    private static CompressionMetric ccrRow(boolean hit) {
        return CompressionMetric.find("kind = ?1 and agentId = ?2 and ccrHit = ?3", Kind.CCR_RETRIEVAL, AGENT, hit)
                .first();
    }

    @Test
    void resetClearsAllMetrics() {
        CompressionMetrics.recordCompression("1", "web", "m", "JSON", "json-smartcrush", 1000, 100);
        CompressionMetrics.recordCcrRetrieval("1", "exec", true);
        assertEquals(2L, CompressionMetric.count());

        assertEquals(2L, CompressionMetrics.reset());
        assertEquals(0L, CompressionMetric.count());
    }
}
