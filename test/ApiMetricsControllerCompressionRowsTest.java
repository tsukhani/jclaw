import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.CompressionMetric;
import models.LatencyMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Functional tests for ApiMetricsController's persisted-row endpoints:
 * GET/DELETE /api/metrics/compression (JCLAW-467 raw rows for the Chat
 * Compression dashboard) and DELETE /api/metrics/latency/rows (JCLAW-515
 * persisted latency time-series reset). Rows are seeded with unique
 * "verifycmp*" channel markers so assertions are immune to samples any
 * concurrently running test might persist into the shared tables.
 */
class ApiMetricsControllerCompressionRowsTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    /** Commit seed data in a fresh transaction on a separate virtual thread so the
     *  HTTP request (separate connection) can see it — the FunctionalTest carrier
     *  thread is already inside an uncommitted JPA transaction. */
    private static void commitInFreshTx(Runnable block) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try { services.Tx.run(block); } catch (Throwable ex) { err.set(ex); }
        });
        try { t.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    private static void seedCompression(CompressionMetric.Kind kind, String agentId, String channel,
                                        String contentType, String algorithm, int tokensBefore,
                                        int tokensAfter, Boolean ccrHit, Instant createdAt) {
        commitInFreshTx(() -> {
            var m = new CompressionMetric();
            m.kind = kind;
            m.agentId = agentId;
            m.channel = channel;
            m.contentType = contentType;
            m.algorithm = algorithm;
            m.tokensBefore = tokensBefore;
            m.tokensAfter = tokensAfter;
            m.ccrHit = ccrHit;
            m.createdAt = createdAt;
            m.save();
        });
    }

    /** The response rows carrying our unique channel marker, in response order. */
    private static List<JsonObject> rowsForChannel(String body, String channel) {
        var rows = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("rows");
        var out = new ArrayList<JsonObject>();
        for (var el : rows) {
            var o = el.getAsJsonObject();
            if (!o.get("channel").isJsonNull() && channel.equals(o.get("channel").getAsString())) {
                out.add(o);
            }
        }
        return out;
    }

    // ──────── GET /api/metrics/compression ────────

    @Test
    void compressionRequiresAuth() {
        assertEquals(401, GET("/api/metrics/compression").status.intValue());
        assertEquals(401, DELETE("/api/metrics/compression").status.intValue());
    }

    @Test
    void latencyRowsRequireAuth() {
        // Pins the requireAdminSession fix: latencyRows/clearLatencyRows were
        // missing from the @Before(only=...) list and served unauthenticated.
        assertEquals(401, GET("/api/metrics/latency/rows").status.intValue());
        assertEquals(401, DELETE("/api/metrics/latency/rows").status.intValue());
    }

    @Test
    void compressionReturnsEmptyRowsWhenNothingRecorded() {
        login();
        var response = GET("/api/metrics/compression");
        assertIsOk(response);
        assertContentType("application/json", response);
        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals(0, json.getAsJsonArray("rows").size());
        // since defaults to ~30 days ago and must be a parseable ISO instant.
        var since = Instant.parse(json.get("since").getAsString());
        assertTrue(since.isBefore(Instant.now()), "default since must lie in the past: " + since);
    }

    @Test
    void compressionReturnsSeededRowsNewestFirstWithExactValues() {
        login();
        var channel = "verifycmp-order";
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        // Insert oldest-first; the endpoint must return newest-first (order by createdAt desc).
        seedCompression(CompressionMetric.Kind.COMPRESSION, "31", channel, "JSON",
                "verify-gzip", 200, 50, null, now.minus(2, ChronoUnit.HOURS));
        seedCompression(CompressionMetric.Kind.INFLATION_GUARD, "31", channel, "CODE",
                null, 300, 300, null, now.minus(1, ChronoUnit.HOURS));
        seedCompression(CompressionMetric.Kind.COMPRESSION, "32", channel, "TEXT",
                "verify-ccr", 400, 80, null, now);

        var response = GET("/api/metrics/compression");
        assertIsOk(response);
        var mine = rowsForChannel(getContent(response), channel);
        assertEquals(3, mine.size());

        var newest = mine.get(0);
        assertEquals(400, newest.get("tokensBefore").getAsInt());
        assertEquals(80, newest.get("tokensAfter").getAsInt());
        assertEquals("COMPRESSION", newest.get("kind").getAsString());
        assertEquals("verify-ccr", newest.get("algorithm").getAsString());
        assertEquals("TEXT", newest.get("contentType").getAsString());
        assertEquals("32", newest.get("agentId").getAsString());
        assertEquals(now.toEpochMilli(),
                Instant.parse(newest.get("timestamp").getAsString()).toEpochMilli());

        var middle = mine.get(1);
        assertEquals("INFLATION_GUARD", middle.get("kind").getAsString());
        assertEquals(300, middle.get("tokensBefore").getAsInt());
        assertEquals(300, middle.get("tokensAfter").getAsInt());

        var oldest = mine.get(2);
        assertEquals(200, oldest.get("tokensBefore").getAsInt());
        assertEquals(50, oldest.get("tokensAfter").getAsInt());
        assertEquals("verify-gzip", oldest.get("algorithm").getAsString());
    }

    @Test
    void compressionCarriesCcrHitFlag() {
        login();
        var channel = "verifycmp-ccr";
        seedCompression(CompressionMetric.Kind.CCR_RETRIEVAL, null, channel, null,
                "ccr", 0, 0, true, Instant.now());

        var mine = rowsForChannel(getContent(GET("/api/metrics/compression")), channel);
        assertEquals(1, mine.size());
        var row = mine.get(0);
        assertEquals("CCR_RETRIEVAL", row.get("kind").getAsString());
        assertTrue(row.get("ccrHit").getAsBoolean());
        assertTrue(row.get("agentId").isJsonNull(), "CCR retrieval is agent-less");
    }

    @Test
    void compressionHonorsSinceWindow() {
        login();
        var channel = "verifycmp-window";
        var now = Instant.now();
        seedCompression(CompressionMetric.Kind.COMPRESSION, "33", channel, "JSON",
                "verify-gzip", 100, 20, null, now);
        seedCompression(CompressionMetric.Kind.COMPRESSION, "33", channel, "JSON",
                "verify-gzip", 999, 999, null, now.minus(60, ChronoUnit.DAYS));

        var since = now.minus(7, ChronoUnit.DAYS).toString();
        var response = GET("/api/metrics/compression?since=" + since);
        assertIsOk(response);
        var mine = rowsForChannel(getContent(response), channel);
        assertEquals(1, mine.size(), "the 60-day-old row must fall outside the window");
        assertEquals(100, mine.get(0).get("tokensBefore").getAsInt());
    }

    @Test
    void compressionRejectsInvalidSince() {
        login();
        assertEquals(400, GET("/api/metrics/compression?since=not-an-instant").status.intValue());
    }

    // ──────── DELETE /api/metrics/compression ────────

    @Test
    void resetCompressionDeletesRecordedRows() {
        login();
        var channel = "verifycmp-reset";
        seedCompression(CompressionMetric.Kind.COMPRESSION, "34", channel, "JSON",
                "verify-gzip", 500, 100, null, Instant.now());
        assertEquals(1, rowsForChannel(getContent(GET("/api/metrics/compression")), channel).size());

        var deleteResp = DELETE("/api/metrics/compression");
        assertIsOk(deleteResp);
        assertTrue(getContent(deleteResp).contains("\"status\":\"reset\""));

        assertEquals(0, rowsForChannel(getContent(GET("/api/metrics/compression")), channel).size(),
                "reset must delete the recorded compression rows");
    }

    // ──────── DELETE /api/metrics/latency/rows (JCLAW-515) ────────

    @Test
    void clearLatencyRowsDeletesPersistedSamples() {
        login();
        var now = Instant.now();
        commitInFreshTx(() -> {
            for (long v : new long[]{120, 340}) {
                var m = new LatencyMetric();
                m.agentId = "41";
                m.channel = "verifyclr";
                m.segment = "verifyclrtotal";
                m.latencyMs = v;
                m.createdAt = now;
                m.save();
            }
        });

        var since = now.minus(1, ChronoUnit.DAYS).toString();
        var before = JsonParser.parseString(
                        getContent(GET("/api/metrics/latency/rows?channel=verifyclr&since=" + since)))
                .getAsJsonObject();
        assertEquals(2, before.getAsJsonObject("segments")
                .getAsJsonObject("verifyclrtotal").get("count").getAsLong());

        var deleteResp = DELETE("/api/metrics/latency/rows");
        assertIsOk(deleteResp);
        assertTrue(getContent(deleteResp).contains("\"status\":\"reset\""));

        var after = JsonParser.parseString(
                        getContent(GET("/api/metrics/latency/rows?channel=verifyclr&since=" + since)))
                .getAsJsonObject();
        assertFalse(after.getAsJsonObject("segments").has("verifyclrtotal"),
                "cleared rows must no longer aggregate: " + after);
        assertFalse(after.getAsJsonArray("channels").toString().contains("verifyclr"),
                "cleared rows must no longer populate the channel list: " + after);
    }
}
