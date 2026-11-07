import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.CompressionMetric;
import models.LatencyMetric;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import tools.JClawApiTool;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Functional tests for ApiMetricsController's persisted-row endpoints: the Chat
 * Compression totals read (GET /api/metrics/compression), its raw rows paged by a
 * cursor (GET /api/metrics/compression/rows, JF-139), the compression reset, and
 * DELETE /api/metrics/latency/rows (JCLAW-515).
 *
 * <p>Each test seeds its own agent ids and channels inside a window in the year 2100,
 * so the exact totals cannot pick up a row any other test or the running server
 * writes "now".
 */
class ApiMetricsControllerCompressionRowsTest extends FunctionalTest {

    private static final Instant WINDOW = Instant.parse("2100-01-01T00:00:00Z");
    private static final String SINCE = "?since=" + WINDOW;

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

    /** Saves the rows in one transaction, in order, so their ids rise in the order given. */
    private static List<Long> seedAll(CompressionMetric... rows) {
        commitInFreshTx(() -> {
            for (var m : rows) m.save();
        });
        return Arrays.stream(rows).map(m -> m.id).toList();
    }

    private static CompressionMetric compression(String agentId, @Nullable String channel, @Nullable String contentType,
                                                 @Nullable String algorithm, int tokensBefore, int tokensAfter,
                                                 @Nullable String toolName, @Nullable String resultHash, int minute) {
        var m = new CompressionMetric();
        m.kind = CompressionMetric.Kind.COMPRESSION;
        m.agentId = agentId;
        m.channel = channel;
        m.contentType = contentType;
        m.algorithm = algorithm;
        m.tokensBefore = tokensBefore;
        m.tokensAfter = tokensAfter;
        m.toolName = toolName;
        m.resultHash = resultHash;
        m.createdAt = WINDOW.plus(minute, ChronoUnit.MINUTES);
        return m;
    }

    private static CompressionMetric guard(String agentId, String channel, String contentType, int tokens, int minute) {
        var m = new CompressionMetric();
        m.kind = CompressionMetric.Kind.INFLATION_GUARD;
        m.agentId = agentId;
        m.channel = channel;
        m.contentType = contentType;
        m.tokensBefore = tokens;
        m.tokensAfter = tokens;
        m.createdAt = WINDOW.plus(minute, ChronoUnit.MINUTES);
        return m;
    }

    /** A fetch-back as recorded: no channel, and a tool and result only on a hit. */
    private static CompressionMetric retrieval(@Nullable String agentId, boolean hit, @Nullable String toolName,
                                               @Nullable String resultHash, int minute) {
        var m = new CompressionMetric();
        m.kind = CompressionMetric.Kind.CCR_RETRIEVAL;
        m.agentId = agentId;
        m.algorithm = "ccr";
        m.ccrHit = hit;
        m.toolName = toolName;
        m.resultHash = resultHash;
        m.createdAt = WINDOW.plus(minute, ChronoUnit.MINUTES);
        return m;
    }

    private static JsonObject read(String pathAndQuery) {
        var response = GET(pathAndQuery);
        assertIsOk(response);
        assertContentType("application/json", response);
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    /**
     * Two agents on two channels: compressions with and without a tool, two guards, and
     * fetch-backs from each agent and from one saved before retrievals named their agent,
     * plus a compression older than the window.
     */
    private static void seedMixedWindow(String p) {
        var older = compression(p + "-a", p + "-web", "JSON", "json-smartcrush", 99_999, 1, "exec", "h0", 0);
        older.createdAt = WINDOW.minus(1, ChronoUnit.DAYS);
        seedAll(older,
                compression(p + "-a", p + "-web", "JSON", "json-smartcrush", 10_000, 600, "exec", "h1", 1),
                compression(p + "-a", p + "-web", "CODE", "code-structural", 2_000, 180, "exec", "h2", 2),
                compression(p + "-a", p + "-tg", "TEXT", "text-statistical", 1_000, 700, "web_fetch", "h3", 3),
                compression(p + "-b", p + "-web", "JSON", "json-smartcrush", 500, 100, null, null, 4),
                guard(p + "-a", p + "-web", "JSON", 300, 5),
                guard(p + "-b", p + "-tg", "CODE", 400, 6),
                retrieval(p + "-a", true, "exec", "h1", 7),
                retrieval(p + "-a", false, null, null, 8),
                retrieval(p + "-b", true, "web_fetch", "h3", 9),
                retrieval(null, true, "exec", "h2", 10));
    }

    // ──────── GET /api/metrics/compression ────────

    @Test
    void compressionRequiresAuth() {
        assertEquals(401, GET("/api/metrics/compression").status.intValue());
        assertEquals(401, GET("/api/metrics/compression/rows").status.intValue());
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
    void totalsCoverEveryAgentAndChannelAndListTheWindowsAgentsAndChannels() {
        login();
        seedMixedWindow("jf139-all");

        assertEquals(json("""
                {"since": "2100-01-01T00:00:00Z",
                 "agents": ["jf139-all-a", "jf139-all-b"],
                 "channels": ["jf139-all-tg", "jf139-all-web"],
                 "compressions": 4, "tokensBefore": 13500, "tokensAfter": 1580,
                 "inflationGuards": 2, "ccrRetrievals": 4, "ccrHits": 3,
                 "byType": [
                   {"contentType": "CODE", "tokensBefore": 2000, "tokensAfter": 180},
                   {"contentType": "JSON", "tokensBefore": 10500, "tokensAfter": 700},
                   {"contentType": "TEXT", "tokensBefore": 1000, "tokensAfter": 700}],
                 "byAlgorithm": [
                   {"algorithm": "code-structural", "count": 1, "tokensBefore": 2000, "tokensAfter": 180},
                   {"algorithm": "json-smartcrush", "count": 2, "tokensBefore": 10500, "tokensAfter": 700},
                   {"algorithm": "text-statistical", "count": 1, "tokensBefore": 1000, "tokensAfter": 700}],
                 "byTool": [
                   {"tool": "exec", "compressed": 2, "fetched": 2},
                   {"tool": "web_fetch", "compressed": 1, "fetched": 1}]}
                """), read("/api/metrics/compression" + SINCE));
    }

    @Test
    void anAgentFilterLimitsEverythingFetchBacksIncludedButNotTheSelects() {
        login();
        seedMixedWindow("jf139-agent");

        assertEquals(json("""
                {"since": "2100-01-01T00:00:00Z",
                 "agents": ["jf139-agent-a", "jf139-agent-b"],
                 "channels": ["jf139-agent-tg", "jf139-agent-web"],
                 "compressions": 3, "tokensBefore": 13000, "tokensAfter": 1480,
                 "inflationGuards": 1, "ccrRetrievals": 2, "ccrHits": 1,
                 "byType": [
                   {"contentType": "CODE", "tokensBefore": 2000, "tokensAfter": 180},
                   {"contentType": "JSON", "tokensBefore": 10000, "tokensAfter": 600},
                   {"contentType": "TEXT", "tokensBefore": 1000, "tokensAfter": 700}],
                 "byAlgorithm": [
                   {"algorithm": "code-structural", "count": 1, "tokensBefore": 2000, "tokensAfter": 180},
                   {"algorithm": "json-smartcrush", "count": 1, "tokensBefore": 10000, "tokensAfter": 600},
                   {"algorithm": "text-statistical", "count": 1, "tokensBefore": 1000, "tokensAfter": 700}],
                 "byTool": [
                   {"tool": "exec", "compressed": 2, "fetched": 1},
                   {"tool": "web_fetch", "compressed": 1, "fetched": 0}]}
                """), read("/api/metrics/compression" + SINCE + "&agentId=jf139-agent-a"));
    }

    @Test
    void aChannelFilterLimitsCompressionsAndGuardsButNotFetchBacks() {
        login();
        seedMixedWindow("jf139-chan");

        assertEquals(json("""
                {"since": "2100-01-01T00:00:00Z",
                 "agents": ["jf139-chan-a", "jf139-chan-b"],
                 "channels": ["jf139-chan-tg", "jf139-chan-web"],
                 "compressions": 3, "tokensBefore": 12500, "tokensAfter": 880,
                 "inflationGuards": 1, "ccrRetrievals": 4, "ccrHits": 3,
                 "byType": [
                   {"contentType": "CODE", "tokensBefore": 2000, "tokensAfter": 180},
                   {"contentType": "JSON", "tokensBefore": 10500, "tokensAfter": 700}],
                 "byAlgorithm": [
                   {"algorithm": "code-structural", "count": 1, "tokensBefore": 2000, "tokensAfter": 180},
                   {"algorithm": "json-smartcrush", "count": 2, "tokensBefore": 10500, "tokensAfter": 700}],
                 "byTool": [{"tool": "exec", "compressed": 2, "fetched": 2}]}
                """), read("/api/metrics/compression" + SINCE + "&channel=jf139-chan-web"));

        // Both filters: agent a's fetch-backs, matched to agent a's telegram compressions.
        assertEquals(json("""
                {"since": "2100-01-01T00:00:00Z",
                 "agents": ["jf139-chan-a", "jf139-chan-b"],
                 "channels": ["jf139-chan-tg", "jf139-chan-web"],
                 "compressions": 1, "tokensBefore": 1000, "tokensAfter": 700,
                 "inflationGuards": 0, "ccrRetrievals": 2, "ccrHits": 1,
                 "byType": [{"contentType": "TEXT", "tokensBefore": 1000, "tokensAfter": 700}],
                 "byAlgorithm": [{"algorithm": "text-statistical", "count": 1, "tokensBefore": 1000, "tokensAfter": 700}],
                 "byTool": [{"tool": "web_fetch", "compressed": 1, "fetched": 0}]}
                """), read("/api/metrics/compression" + SINCE + "&agentId=jf139-chan-a&channel=jf139-chan-tg"));
    }

    @Test
    void aResultCompressedInTwoRoundsAndFetchedBackOnceCountsOnceForItsTool() {
        login();
        seedAll(compression("jf139-twice", "jf139-twice-web", "JSON", "json-smartcrush", 900, 100, "exec", "h1", 1),
                compression("jf139-twice", "jf139-twice-web", "JSON", "json-smartcrush", 900, 100, "exec", "h1", 2),
                retrieval("jf139-twice", true, "exec", "h1", 3));

        var totals = read("/api/metrics/compression" + SINCE);
        assertEquals(json("""
                [{"tool": "exec", "compressed": 1, "fetched": 1}]
                """), totals.get("byTool"));
        assertEquals(2, totals.get("compressions").getAsLong());
    }

    @Test
    void aFetchBackBeforeTheWindowDoesNotCountForAResultCompressedAgainInsideIt() {
        login();
        var earlier = retrieval("jf139-late", true, "exec", "h1", 0);
        earlier.createdAt = WINDOW.minus(1, ChronoUnit.DAYS);
        seedAll(earlier, compression("jf139-late", "jf139-late-web", "JSON", "json-smartcrush", 900, 100, "exec", "h1", 1));

        for (var filter : List.of("", "&agentId=jf139-late")) {
            var totals = read("/api/metrics/compression" + SINCE + filter);
            assertEquals(json("""
                    [{"tool": "exec", "compressed": 1, "fetched": 0}]
                    """), totals.get("byTool"), "filter '" + filter + "'");
            assertEquals(0, totals.get("ccrRetrievals").getAsLong(), "filter '" + filter + "'");
        }
    }

    @Test
    void rowsSavedWithoutTheirToolOrResultCountInTheTotalsButNotThePerToolRates() {
        login();
        seedAll(compression("jf139-old", "jf139-old-web", "JSON", "json-smartcrush", 1_000, 100, null, null, 1),
                compression("jf139-old", "jf139-old-web", "JSON", "json-smartcrush", 1_000, 100, "exec", null, 2),
                compression("jf139-old", "jf139-old-web", "JSON", "json-smartcrush", 1_000, 100, null, "h9", 3),
                retrieval("jf139-old", true, "exec", null, 4),
                retrieval("jf139-old", true, null, null, 5));

        assertEquals(json("""
                {"since": "2100-01-01T00:00:00Z",
                 "agents": ["jf139-old"], "channels": ["jf139-old-web"],
                 "compressions": 3, "tokensBefore": 3000, "tokensAfter": 300,
                 "inflationGuards": 0, "ccrRetrievals": 2, "ccrHits": 2,
                 "byType": [{"contentType": "JSON", "tokensBefore": 3000, "tokensAfter": 300}],
                 "byAlgorithm": [{"algorithm": "json-smartcrush", "count": 3, "tokensBefore": 3000, "tokensAfter": 300}],
                 "byTool": []}
                """), read("/api/metrics/compression" + SINCE));
    }

    @Test
    void aNullTypeOrAlgorithmIsItsOwnGroup() {
        login();
        seedAll(compression("jf139-null", "jf139-null-web", null, null, 800, 200, null, null, 1),
                compression("jf139-null", "jf139-null-web", "JSON", "json-smartcrush", 500, 100, null, null, 2));

        var totals = read("/api/metrics/compression" + SINCE);
        assertEquals(json("""
                [{"contentType": null, "tokensBefore": 800, "tokensAfter": 200},
                 {"contentType": "JSON", "tokensBefore": 500, "tokensAfter": 100}]
                """), totals.get("byType"));
        assertEquals(json("""
                [{"algorithm": null, "count": 1, "tokensBefore": 800, "tokensAfter": 200},
                 {"algorithm": "json-smartcrush", "count": 1, "tokensBefore": 500, "tokensAfter": 100}]
                """), totals.get("byAlgorithm"));
    }

    @Test
    void anEmptyWindowReturnsZeroTotalsAndEmptyLists() {
        login();
        seedMixedWindow("jf139-empty");

        assertEquals(json("""
                {"since": "2200-01-01T00:00:00Z", "agents": [], "channels": [],
                 "compressions": 0, "tokensBefore": 0, "tokensAfter": 0,
                 "inflationGuards": 0, "ccrRetrievals": 0, "ccrHits": 0,
                 "byType": [], "byAlgorithm": [], "byTool": []}
                """), read("/api/metrics/compression?since=2200-01-01T00:00:00Z"));
    }

    @Test
    void theDefaultWindowStartsThirtyDaysBack() {
        login();
        var before = Instant.now().minus(30, ChronoUnit.DAYS);
        var since = Instant.parse(read("/api/metrics/compression").get("since").getAsString());
        var after = Instant.now().minus(30, ChronoUnit.DAYS);
        assertFalse(since.isBefore(before.truncatedTo(ChronoUnit.MILLIS)), "since " + since + " before " + before);
        assertFalse(since.isAfter(after), "since " + since + " after " + after);
    }

    @Test
    void compressionRejectsInvalidSince() {
        login();
        assertEquals(400, GET("/api/metrics/compression?since=not-an-instant").status.intValue());
        assertEquals(400, GET("/api/metrics/compression/rows?since=not-an-instant").status.intValue());
    }

    // ──────── GET /api/metrics/compression/rows ────────

    private static List<Long> ids(JsonObject page) {
        var out = new ArrayList<Long>();
        for (var el : page.getAsJsonArray("rows")) out.add(el.getAsJsonObject().get("id").getAsLong());
        return out;
    }

    private static List<Long> newestFirst(List<Long> ids, int from, int to) {
        return ids.subList(from, to).reversed();
    }

    @Test
    void rowsComeNewestFirstWithEveryFieldAndStayInTheWindow() {
        login();
        var older = compression("jf139-rows", "jf139-rows-web", "JSON", "json-smartcrush", 99_999, 1, null, null, 0);
        older.createdAt = WINDOW.minus(1, ChronoUnit.SECONDS);
        var ids = seedAll(older,
                compression("jf139-rows", "jf139-rows-web", "JSON", "json-smartcrush", 900, 100,
                        "exec", "0123456789abcdef", 60),
                guard("jf139-rows", "jf139-rows-web", "CODE", 300, 120),
                retrieval("jf139-rows", true, "exec", "0123456789abcdef", 180));

        assertEquals(json("""
                {"since": "2100-01-01T00:00:00Z", "nextBefore": null, "rows": [
                  {"id": %d, "timestamp": "2100-01-01T03:00:00Z", "agentId": "jf139-rows", "channel": null,
                   "contentType": null, "algorithm": "ccr", "tokensBefore": 0, "tokensAfter": 0,
                   "kind": "CCR_RETRIEVAL", "ccrHit": true, "toolName": "exec", "resultHash": "0123456789abcdef"},
                  {"id": %d, "timestamp": "2100-01-01T02:00:00Z", "agentId": "jf139-rows", "channel": "jf139-rows-web",
                   "contentType": "CODE", "algorithm": null, "tokensBefore": 300, "tokensAfter": 300,
                   "kind": "INFLATION_GUARD", "ccrHit": null, "toolName": null, "resultHash": null},
                  {"id": %d, "timestamp": "2100-01-01T01:00:00Z", "agentId": "jf139-rows", "channel": "jf139-rows-web",
                   "contentType": "JSON", "algorithm": "json-smartcrush", "tokensBefore": 900, "tokensAfter": 100,
                   "kind": "COMPRESSION", "ccrHit": null, "toolName": "exec", "resultHash": "0123456789abcdef"}]}
                """.formatted(ids.get(3), ids.get(2), ids.get(1))), read("/api/metrics/compression/rows" + SINCE));
    }

    @Test
    void theCursorWalksEveryRowOnceAndEndsWhenNoneRemain() {
        login();
        var seeded = new CompressionMetric[6];
        for (int i = 0; i < seeded.length; i++) {
            seeded[i] = compression("jf139-page", "jf139-page-web", "JSON", "json-smartcrush", 100 + i, 10, null, null, i);
        }
        var ids = seedAll(seeded);

        // Six rows in pages of three: the second page is full and nothing follows it.
        var first = read("/api/metrics/compression/rows" + SINCE + "&limit=3");
        assertEquals(newestFirst(ids, 3, 6), ids(first));
        assertEquals(ids.get(3).longValue(), first.get("nextBefore").getAsLong());
        var second = read("/api/metrics/compression/rows" + SINCE + "&limit=3&before=" + ids.get(3));
        assertEquals(newestFirst(ids, 0, 3), ids(second));
        assertTrue(second.get("nextBefore").isJsonNull(), "no cursor once every row was returned: " + second);

        // Pages of five: one row is left for the second page.
        var five = read("/api/metrics/compression/rows" + SINCE + "&limit=5");
        assertEquals(newestFirst(ids, 1, 6), ids(five));
        assertEquals(ids.get(1).longValue(), five.get("nextBefore").getAsLong());
        var rest = read("/api/metrics/compression/rows" + SINCE + "&limit=5&before=" + ids.get(1));
        assertEquals(List.of(ids.get(0)), ids(rest));
        assertTrue(rest.get("nextBefore").isJsonNull(), "no cursor on the last page: " + rest);
    }

    @Test
    void theLimitIsClampedToFiftyAndDefaultsToTwentyFiveWhenMissingOrNotPositive() {
        login();
        var seeded = new CompressionMetric[51];
        for (int i = 0; i < seeded.length; i++) {
            seeded[i] = compression("jf139-limit", "jf139-limit-web", "JSON", "json-smartcrush", 100, 10, null, null, i);
        }
        var ids = seedAll(seeded);

        var atMax = read("/api/metrics/compression/rows" + SINCE + "&limit=50");
        assertEquals(newestFirst(ids, 1, 51), ids(atMax));
        assertEquals(ids.get(1).longValue(), atMax.get("nextBefore").getAsLong());
        var overMax = read("/api/metrics/compression/rows" + SINCE + "&limit=51");
        assertEquals(newestFirst(ids, 1, 51), ids(overMax));
        assertEquals(ids.get(1).longValue(), overMax.get("nextBefore").getAsLong());

        for (var limit : List.of("", "&limit=0", "&limit=-1")) {
            var page = read("/api/metrics/compression/rows" + SINCE + limit);
            assertEquals(newestFirst(ids, 26, 51), ids(page), "limit '" + limit + "'");
            assertEquals(ids.get(26).longValue(), page.get("nextBefore").getAsLong(), "limit '" + limit + "'");
        }
    }

    @Test
    void aFullPageOfTheLongestRowsFitsUnderJClawApisCap() {
        login();
        var seeded = new CompressionMetric[51];
        for (int i = 0; i < seeded.length; i++) {
            var m = new CompressionMetric();
            m.kind = CompressionMetric.Kind.INFLATION_GUARD;
            m.agentId = "a".repeat(255);
            m.channel = "c".repeat(32);
            m.contentType = "t".repeat(20);
            m.algorithm = "g".repeat(40);
            m.tokensBefore = Integer.MIN_VALUE;
            m.tokensAfter = Integer.MIN_VALUE;
            m.ccrHit = false;
            m.toolName = "x".repeat(255);
            m.resultHash = "f".repeat(16);
            m.createdAt = WINDOW.plusNanos(123_456_789L + i);
            seeded[i] = m;
        }
        seedAll(seeded);

        var response = GET("/api/metrics/compression/rows" + SINCE + "&limit=50");
        assertIsOk(response);
        var body = getContent(response);
        var rows = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("rows");
        assertEquals(50, rows.size());
        // The fields reached the page at full length, so the measurement is of the worst case.
        var row = rows.get(0).getAsJsonObject();
        assertEquals(255, row.get("agentId").getAsString().length());
        assertEquals(255, row.get("toolName").getAsString().length());
        assertTrue(body.length() < JClawApiTool.MAX_RESPONSE_CHARS,
                "a page of " + body.length() + " characters is cut off at " + JClawApiTool.MAX_RESPONSE_CHARS);
    }

    @Test
    void aMalformedCursorOrLimitIsRefusedAndABlankCursorStartsAtTheNewest() {
        login();
        var ids = seedAll(compression("jf139-cursor", "jf139-cursor-web", "JSON", "json-smartcrush", 100, 10, null, null, 1));

        assertEquals(400, GET("/api/metrics/compression/rows" + SINCE + "&before=abc").status.intValue());
        assertEquals(400, GET("/api/metrics/compression/rows" + SINCE + "&limit=abc").status.intValue());
        assertEquals(ids, ids(read("/api/metrics/compression/rows" + SINCE + "&before=")));
    }

    // ──────── DELETE /api/metrics/compression ────────

    @Test
    void resetCompressionDeletesRecordedRows() {
        login();
        seedCompression(CompressionMetric.Kind.COMPRESSION, "jf139-reset", "jf139-reset-web", "JSON",
                "verify-gzip", 500, 100, null, WINDOW.plus(1, ChronoUnit.MINUTES));
        assertEquals(1, read("/api/metrics/compression" + SINCE).get("compressions").getAsLong());

        var deleteResp = DELETE("/api/metrics/compression");
        assertIsOk(deleteResp);
        assertEquals(json("{\"status\": \"reset\"}"), json(getContent(deleteResp)));

        assertEquals(0, read("/api/metrics/compression" + SINCE).get("compressions").getAsLong(),
                "reset must delete the recorded compression rows");
        assertEquals(new JsonArray(), read("/api/metrics/compression/rows" + SINCE).get("rows"));
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
