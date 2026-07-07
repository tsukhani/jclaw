import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.LatencyMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.Play;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;
import utils.LatencyStats;
import utils.LatencyTrace;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;

/**
 * Functional tests for ApiMetricsController. Drives the LatencyTrace/LatencyStats
 * pipeline directly rather than through a real LLM call — the goal is to assert
 * the instrumentation plumbing, not provider behavior.
 */
class ApiMetricsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        LatencyStats.reset();
    }

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    /**
     * Build a Request that exercises the LoadtestAuthCheck guard. Sets a
     * loopback remoteAddress (FunctionalTest's newRequest leaves it null,
     * which the guard correctly rejects) and optionally injects the
     * X-Loadtest-Auth header. Pass {@code null} for headerValue to omit
     * the header entirely; pass {@code "non-loopback"} for the
     * remoteAddress to exercise the network-origin gate.
     */
    private Http.Request loadtestRequest(String remoteAddress, String headerValue) {
        var req = newRequest();
        req.remoteAddress = remoteAddress;
        if (req.headers == null) {
            req.headers = new HashMap<>();
        }
        if (headerValue != null) {
            req.headers.put("x-loadtest-auth",
                    new Http.Header("x-loadtest-auth", headerValue));
        }
        return req;
    }

    /** Convenience: loopback origin, valid auth header from configured application.secret. */
    private Http.Request authedLoadtestRequest() {
        return loadtestRequest("127.0.0.1",
                Play.configuration.getProperty("application.secret"));
    }

    @Test
    void latencyRequiresAuth() {
        var response = GET("/api/metrics/latency");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void latencyReturnsEmptyJsonWhenNoSamples() {
        login();
        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertEquals("{}", getContent(response));
    }

    @Test
    void endedTraceRecordsAllSegments() throws Exception {
        login();
        var trace = new LatencyTrace();
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        Thread.sleep(3);
        trace.mark(LatencyTrace.FIRST_TOKEN);
        Thread.sleep(3);
        trace.mark(LatencyTrace.STREAM_BODY_END);
        trace.mark(LatencyTrace.PERSIST_DONE);
        trace.addToolRound(2);
        trace.end();

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"total\""), body);
        assertTrue(body.contains("\"prologue\""), body);
        assertTrue(body.contains("\"ttft\""), body);
        assertTrue(body.contains("\"stream_body\""), body);
        assertTrue(body.contains("\"persist\""), body);
        assertTrue(body.contains("\"tool_exec\""), body);
        assertTrue(body.contains("\"tool_round_count\""), body);
        assertTrue(body.contains("\"p50_ms\""), body);
        assertTrue(body.contains("\"p99_ms\""), body);
        // Log-bucket distribution is emitted so the dashboard can render a PDF.
        assertTrue(body.contains("\"buckets\""), body);
        assertTrue(body.contains("\"le_ms\""), body);
    }

    @Test
    void prologueSubSegmentsAreRecordedWhenMarksPresent() throws Exception {
        login();
        var trace = new LatencyTrace();
        Thread.sleep(2);
        trace.mark(LatencyTrace.PROLOGUE_REQUEST_PARSED);
        Thread.sleep(2);
        trace.mark(LatencyTrace.PROLOGUE_CONV_RESOLVED);
        Thread.sleep(2);
        trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);
        Thread.sleep(2);
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        Thread.sleep(2);
        trace.mark(LatencyTrace.FIRST_TOKEN);
        trace.mark(LatencyTrace.STREAM_BODY_END);
        trace.mark(LatencyTrace.PERSIST_DONE);
        Thread.sleep(2);
        trace.mark(LatencyTrace.TERMINAL_SENT);
        trace.end();

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        var body = getContent(response);
        // The four derived prologue sub-segments must sum (roughly) to `prologue`.
        assertTrue(body.contains("\"prologue_parse\""), body);
        assertTrue(body.contains("\"prologue_conv\""), body);
        assertTrue(body.contains("\"prologue_prompt\""), body);
        assertTrue(body.contains("\"prologue_tools\""), body);
        // terminal_tail covers post-persist → terminal-SSE-frame-flushed.
        assertTrue(body.contains("\"terminal_tail\""), body);
    }

    @Test
    void prologueSubSegmentsSkippedWhenMarksMissing() {
        login();
        // Only PROLOGUE_DONE set — none of the three finer marks.
        var trace = new LatencyTrace();
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        var body = getContent(response);
        // Backward-compat: missing finer marks don't break anything and don't
        // create empty sub-segments.
        assertTrue(body.contains("\"prologue\""), body);
        assertFalse(body.contains("\"prologue_parse\""), body);
        assertFalse(body.contains("\"prologue_conv\""), body);
        assertFalse(body.contains("\"prologue_prompt\""), body);
        assertFalse(body.contains("\"prologue_tools\""), body);
        assertFalse(body.contains("\"terminal_tail\""), body);
    }

    @Test
    void earlyExitTraceWithoutPrologueRecordsNothing() {
        login();
        new LatencyTrace().end();
        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        assertEquals("{}", getContent(response));
    }

    @Test
    void endIsIdempotent() {
        login();
        var trace = new LatencyTrace();
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();
        trace.end();

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"total\""));
        // count should be 1 — the second end() must be a no-op
        assertTrue(body.contains("\"count\":1"), body);
    }

    @Test
    void queueWaitSegmentIsRecordedWhenAcceptedStampPresent() {
        login();
        // Simulate the PlayHandler stamp by writing acceptedAtNanos to the
        // current request's args before building the trace from it.
        long simulatedAcceptNs = System.nanoTime() - 4_000_000L; // 4ms ago
        play.mvc.Http.Request.current().args.put("acceptedAtNanos", simulatedAcceptNs);

        var trace = LatencyTrace.forTurn("web", LatencyTrace.acceptedAtNsFromCurrentRequest());
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.mark(LatencyTrace.FIRST_TOKEN);
        trace.mark(LatencyTrace.STREAM_BODY_END);
        trace.mark(LatencyTrace.PERSIST_DONE);
        trace.end();

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"queue_wait\""), body);
    }

    @Test
    void queueWaitAbsentWhenNoAcceptedStamp() {
        login();
        play.mvc.Http.Request.current().args.remove("acceptedAtNanos");

        var trace = LatencyTrace.forTurn("web", LatencyTrace.acceptedAtNsFromCurrentRequest());
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        var body = getContent(response);
        assertFalse(body.contains("\"queue_wait\""), body);
    }

    @Test
    void forTurnWithNullProducesPopulatedTrace() throws Exception {
        // Channels without a pre-runner timestamp (Telegram polling, TaskPoller)
        // pass null acceptedAtNs to forTurn. All segments except queue_wait
        // should still be recorded — that's the fix that makes non-web channels
        // show up in the Chat Performance dashboard.
        login();

        var trace = LatencyTrace.forTurn("telegram", null);
        trace.mark(LatencyTrace.PROLOGUE_REQUEST_PARSED);
        Thread.sleep(2);
        trace.mark(LatencyTrace.PROLOGUE_CONV_RESOLVED);
        Thread.sleep(2);
        trace.mark(LatencyTrace.PROLOGUE_PROMPT_ASSEMBLED);
        Thread.sleep(2);
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        Thread.sleep(2);
        trace.mark(LatencyTrace.FIRST_TOKEN);
        Thread.sleep(2);
        trace.mark(LatencyTrace.STREAM_BODY_END);
        trace.mark(LatencyTrace.TERMINAL_SENT);
        trace.end();

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        var body = getContent(response);
        // The telegram channel section should carry the segments, and queue_wait
        // should be absent for a null-accept-stamp caller.
        assertTrue(body.contains("\"telegram\""), body);
        assertTrue(body.contains("\"total\""), body);
        assertTrue(body.contains("\"prologue\""), body);
        assertTrue(body.contains("\"ttft\""), body);
        assertTrue(body.contains("\"stream_body\""), body);
        assertFalse(body.contains("\"queue_wait\""), "no queue_wait without an accept stamp: " + body);
    }

    // ---- JCLAW-515: end-to-end verification of GET /api/metrics/latency/rows ----
    // Unique "verify*" channel/segment markers isolate these rows from any latency
    // samples a concurrent agent-turn test might persist into the shared table.

    private static void commitInFreshTx(Runnable block) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try { services.Tx.run(block); } catch (Throwable ex) { err.set(ex); }
        });
        try { t.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    private static void seedLatency(String agentId, String channel, String segment,
                                    Instant createdAt, long... valuesMs) {
        commitInFreshTx(() -> {
            for (long v : valuesMs) {
                var m = new LatencyMetric();
                m.agentId = agentId;
                m.channel = channel;
                m.segment = segment;
                m.latencyMs = v;
                m.createdAt = createdAt;
                m.save();
            }
        });
    }

    private static long[] range(int from, int to) {
        var a = new long[to - from + 1];
        for (int i = 0; i < a.length; i++) a[i] = from + i;
        return a;
    }

    private static JsonObject segments(String body) {
        return JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("segments");
    }

    private static void assertPercentile(JsonObject seg, String key, long expected, long tol) {
        long actual = seg.get(key).getAsLong();
        assertTrue(Math.abs(actual - expected) <= tol,
                key + " expected ~" + expected + " (±" + tol + "), was " + actual);
    }

    @Test
    void latencyRowsReturnsAccuratePercentiles() {
        login();
        var now = Instant.now();
        // Known distribution: 1..1000 ms.
        seedLatency("7", "verifyweb", "verifytotal", now, range(1, 1000));

        var since = now.minus(1, ChronoUnit.DAYS).toString();
        var response = GET("/api/metrics/latency/rows?agentId=7&channel=verifyweb&since=" + since);
        assertIsOk(response);
        var total = segments(getContent(response)).getAsJsonObject("verifytotal");

        assertEquals(1000L, total.get("count").getAsLong());
        assertEquals(1L, total.get("min_ms").getAsLong());
        assertEquals(1000L, total.get("max_ms").getAsLong());
        // HdrHistogram recomputes percentiles from the raw samples, accurate to ~0.1%:
        // the 500th / 900th / 990th / 999th values of 1..1000.
        assertPercentile(total, "p50_ms", 500, 3);
        assertPercentile(total, "p90_ms", 900, 3);
        assertPercentile(total, "p99_ms", 990, 3);
        assertPercentile(total, "p999_ms", 999, 3);
    }

    @Test
    void latencyRowsAppliesWindowAgentAndChannelFilters() {
        login();
        var now = Instant.now();
        seedLatency("7", "verifyweb", "verifytotal", now, 100, 200, 300);   // agent 7 / verifyweb (3)
        seedLatency("9", "verifytel", "verifytotal", now, 500, 600);        // agent 9 / verifytel (2)
        seedLatency("7", "verifyweb", "verifytotal",
                now.minus(60, ChronoUnit.DAYS), 9999);                      // 60d-old → excluded by window

        var since = now.minus(7, ChronoUnit.DAYS).toString();

        // (a) All channels, windowed: both agents' recent rows; the 60d-old row excluded.
        var all = getContent(GET("/api/metrics/latency/rows?since=" + since));
        assertEquals(5L, segments(all).getAsJsonObject("verifytotal").get("count").getAsLong());
        var channels = JsonParser.parseString(all).getAsJsonObject().getAsJsonArray("channels").toString();
        assertTrue(channels.contains("verifyweb") && channels.contains("verifytel"), channels);

        // (b) Agent filter: only agent 7's rows aggregate.
        var byAgent = segments(getContent(GET("/api/metrics/latency/rows?since=" + since + "&agentId=7")))
                .getAsJsonObject("verifytotal");
        assertEquals(3L, byAgent.get("count").getAsLong());
        assertEquals(100L, byAgent.get("min_ms").getAsLong());
        assertEquals(300L, byAgent.get("max_ms").getAsLong());

        // (c) Channel filter: only verifytel rows aggregate; the channels list still lists both.
        var byChannel = getContent(GET("/api/metrics/latency/rows?since=" + since + "&channel=verifytel"));
        var chTotal = segments(byChannel).getAsJsonObject("verifytotal");
        assertEquals(2L, chTotal.get("count").getAsLong());
        assertEquals(500L, chTotal.get("min_ms").getAsLong());
        assertEquals(600L, chTotal.get("max_ms").getAsLong());
        assertTrue(JsonParser.parseString(byChannel).getAsJsonObject()
                .getAsJsonArray("channels").toString().contains("verifyweb"), byChannel);
    }

    @Test
    void channelsStaySeparateInSnapshot() {
        // JCLAW-102: a web turn and a telegram turn should land in distinct
        // channel sections rather than averaging their distributions.
        login();

        var webTrace = LatencyTrace.forTurn("web", null);
        webTrace.mark(LatencyTrace.PROLOGUE_DONE);
        webTrace.end();

        var telegramTrace = LatencyTrace.forTurn("telegram", null);
        telegramTrace.mark(LatencyTrace.PROLOGUE_DONE);
        telegramTrace.end();

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"web\""), body);
        assertTrue(body.contains("\"telegram\""), body);
    }

    // ─────── LoadtestAuthCheck gate ───────────────────────────────────────────
    // JCLAW-181 replaced @With(AuthCheck.class) on the loadtest endpoints with
    // @With(LoadtestAuthCheck.class), which requires both a loopback origin
    // and the X-Loadtest-Auth header carrying application.secret. The four
    // tests below cover the AC matrix from the story: missing header, wrong
    // header, non-loopback origin, and the correct combination.

    @Test
    void loadtestRejectsMissingAuthHeader() {
        // Loopback origin but no X-Loadtest-Auth header → 403, not 401.
        var req = loadtestRequest("127.0.0.1", null);
        var response = POST(req, "/api/metrics/loadtest", "application/json", "{}");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void loadtestRejectsWrongAuthHeader() {
        // Header present but wrong value → 403. Constant-time compare in the
        // guard means a near-miss must reject the same way as a wholly
        // unrelated value; both are exercised here.
        var nearMiss = Play.configuration.getProperty("application.secret") + "x";
        var resp1 = POST(loadtestRequest("127.0.0.1", nearMiss),
                "/api/metrics/loadtest", "application/json", "{}");
        assertEquals(403, resp1.status.intValue());

        var resp2 = POST(loadtestRequest("127.0.0.1", "obviously-wrong"),
                "/api/metrics/loadtest", "application/json", "{}");
        assertEquals(403, resp2.status.intValue());
    }

    @Test
    void loadtestRejectsNonLoopbackOrigin() {
        // Correct header but request origin is not loopback → 403. Loadtest
        // is bound to same-host operation by design.
        var req = loadtestRequest("192.168.1.100",
                Play.configuration.getProperty("application.secret"));
        var response = POST(req, "/api/metrics/loadtest", "application/json", "{}");
        assertEquals(403, response.status.intValue());
    }

    /**
     * Loadtest body-validation rejections — every one of these payloads must
     * fail validation with 400 once the auth gate is satisfied. Body
     * branches: invalid concurrency, invalid turns, prompts-shorter-than-
     * turns, conflicting prompts+userMessage, provider/model XOR, helper
     * type-coercion paths (non-int concurrency, non-bool compress, non-array
     * prompts).
     */
    @ParameterizedTest(name = "loadtestRejects[{0}]")
    @CsvSource(delimiter = '|', value = {
            "InvalidConcurrency             | {\"concurrency\":0,\"turns\":1}",
            "InvalidTurns                   | {\"concurrency\":1,\"turns\":9999}",
            "PromptsShorterThanTurns        | {\"concurrency\":1,\"turns\":3,\"prompts\":[\"q1\",\"q2\"]}",
            "PromptsAndUserMessageTogether  | {\"concurrency\":1,\"turns\":1,\"prompts\":[\"q\"],\"userMessage\":\"hi\"}",
            "ProviderWithoutModel           | {\"concurrency\":1,\"turns\":1,\"provider\":\"openrouter\"}",
            "ModelWithoutProvider           | {\"concurrency\":1,\"turns\":1,\"model\":\"gpt-4.1\"}",
            "NonIntegerConcurrency          | {\"concurrency\":\"abc\",\"turns\":1}",
            "NonBoolCompress                | {\"concurrency\":1,\"turns\":1,\"compress\":[1,2,3]}",
            "NonArrayPrompts                | {\"concurrency\":1,\"turns\":1,\"prompts\":\"not-an-array\"}"
    })
    void loadtestRejectsInvalidBody(String label, String body) {
        var response = POST(authedLoadtestRequest(),
                "/api/metrics/loadtest", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    void stopLoadtestEndpointAlwaysSucceeds() {
        var response = DELETE(authedLoadtestRequest(), "/api/metrics/loadtest");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"status\":\"stopped\""));
    }

    @Test
    void stopLoadtestRequiresLoadtestAuth() {
        // Verify the DELETE endpoint enforces the same gate as POST.
        var response = DELETE(loadtestRequest("127.0.0.1", null),
                "/api/metrics/loadtest");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void resetClearsHistograms() {
        login();
        var trace = new LatencyTrace();
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();

        var deleteResp = DELETE("/api/metrics/latency");
        assertIsOk(deleteResp);
        assertTrue(getContent(deleteResp).contains("\"status\":\"reset\""));

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        assertEquals("{}", getContent(response));
    }

    @Test
    void cleanLoadtestDataDoesNotTouchHistograms() {
        // /api/metrics/latency and /api/metrics/loadtest/data have orthogonal
        // scopes: the former clears runtime statistics, the latter clears DB
        // artifacts from load-test runs. Purging one should leave the other
        // untouched. Mixed auth: latency endpoints use AuthCheck (login),
        // loadtest endpoints use LoadtestAuthCheck (header).
        login();
        var trace = new LatencyTrace();
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();

        var cleanResp = DELETE(authedLoadtestRequest(), "/api/metrics/loadtest/data");
        assertIsOk(cleanResp);
        assertTrue(getContent(cleanResp).contains("\"status\":\"cleaned\""));

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        // Histograms must survive the loadtest-data cleanup.
        assertTrue(getContent(response).contains("\"total\""), getContent(response));
    }

    @Test
    void cleanLoadtestRequiresLoadtestAuth() {
        // The data-cleanup DELETE is also under LoadtestAuthCheck.
        var response = DELETE(loadtestRequest("127.0.0.1", null),
                "/api/metrics/loadtest/data");
        assertEquals(403, response.status.intValue());
    }

    // ─────── Cost endpoint (JCLAW-28) ─────────────────────────────────────────
    // Aggregates Message.usageJson across conversations grouped by agent and
    // channel. Backend returns raw rows; aggregation lives in the frontend
    // usage-cost util so the cost math stays single-sourced.

    @Test
    void costRequiresAuth() {
        var response = GET("/api/metrics/cost");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void costReturnsEmptyRowsWhenNoMessages() {
        login();
        var response = GET("/api/metrics/cost");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"rows\":[]"), "expected empty rows array, got: " + body);
        // since defaults to 30 days ago — just check that the field is present
        assertTrue(body.contains("\"since\":"), "expected since field, got: " + body);
    }

    @Test
    void costReturnsAssistantMessagesWithUsage() {
        login();
        long agentId = seedAgentWithUsage("cost-test-1", "web", "alice",
                "{\"prompt\":100,\"completion\":50,\"total\":150,\"reasoning\":0,\"cached\":0,"
                        + "\"durationMs\":1234,\"promptPrice\":0.5,\"completionPrice\":1.0,"
                        + "\"modelProvider\":\"openrouter\",\"modelId\":\"gpt-4.1\"}");

        var response = GET("/api/metrics/cost");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"agentId\":" + agentId), body);
        assertTrue(body.contains("\"channelType\":\"web\""), body);
        // The full usageJson string should round-trip verbatim so the frontend
        // TS types deserialize it without conversion.
        assertTrue(body.contains("\\\"prompt\\\":100"), body);
        assertTrue(body.contains("\\\"modelId\\\":\\\"gpt-4.1\\\""), body);
    }

    @Test
    void costFiltersByAgentId() {
        login();
        long agentA = seedAgentWithUsage("cost-test-a", "web", "alice",
                "{\"prompt\":10,\"completion\":5,\"total\":15,\"reasoning\":0,\"cached\":0,"
                        + "\"durationMs\":100,\"promptPrice\":0.5,\"completionPrice\":1.0}");
        long agentB = seedAgentWithUsage("cost-test-b", "web", "bob",
                "{\"prompt\":20,\"completion\":10,\"total\":30,\"reasoning\":0,\"cached\":0,"
                        + "\"durationMs\":200,\"promptPrice\":0.5,\"completionPrice\":1.0}");

        var response = GET("/api/metrics/cost?agentId=" + agentA);
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"agentId\":" + agentA),
                "agent A's row must appear: " + body);
        assertFalse(body.contains("\"agentId\":" + agentB),
                "agent B's row must be filtered out: " + body);
    }

    @Test
    void costFiltersByChannelType() {
        login();
        seedAgentWithUsage("cost-test-web", "web", "alice",
                "{\"prompt\":10,\"completion\":5,\"total\":15,\"reasoning\":0,\"cached\":0,"
                        + "\"durationMs\":100,\"promptPrice\":0.5,\"completionPrice\":1.0}");
        seedAgentWithUsage("cost-test-tg", "telegram", "12345",
                "{\"prompt\":20,\"completion\":10,\"total\":30,\"reasoning\":0,\"cached\":0,"
                        + "\"durationMs\":200,\"promptPrice\":0.5,\"completionPrice\":1.0}");

        var response = GET("/api/metrics/cost?channelType=telegram");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"channelType\":\"telegram\""),
                "telegram row must appear: " + body);
        assertFalse(body.contains("\"channelType\":\"web\""),
                "web row must be filtered out: " + body);
    }

    @Test
    void costFiltersByTimeWindow() {
        login();
        // Seed two messages, one within the default 30-day window and one
        // outside it. Use an explicit since param tighter than 30d to verify
        // the bound is honored.
        seedAgentWithUsage("cost-test-window", "web", "carol",
                "{\"prompt\":10,\"completion\":5,\"total\":15,\"reasoning\":0,\"cached\":0,"
                        + "\"durationMs\":100,\"promptPrice\":0.5,\"completionPrice\":1.0}");

        // Future "since" returns nothing (no messages from after now).
        var farFuture = java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.DAYS);
        var response = GET("/api/metrics/cost?since=" + farFuture);
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"rows\":[]"),
                "future since must yield empty rows: " + getContent(response));
    }

    @Test
    void costRejectsInvalidSince() {
        login();
        var response = GET("/api/metrics/cost?since=not-an-instant");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void costRejectsInvalidAgentId() {
        login();
        var response = GET("/api/metrics/cost?agentId=not-numeric");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void costExcludesUserAndToolMessages() {
        // Non-assistant rows have null usageJson; the SQL filter
        // `m.usageJson IS NOT NULL` should exclude them so the operator's
        // turn count and token totals match the assistant turn count.
        login();
        seedConversationWithMixedMessages("cost-test-mixed", "web", "dave");

        var response = GET("/api/metrics/cost");
        assertIsOk(response);
        var body = getContent(response);
        // Two assistant messages were seeded, both with usageJson; user and
        // tool messages have null usageJson and must be filtered.
        int rowCount = body.split("\\\"timestamp\\\":", -1).length - 1;
        assertEquals(2, rowCount,
                "expected 2 assistant rows (user/tool excluded), got " + rowCount + " in " + body);
    }

    /**
     * Seed an agent with one assistant message carrying the given usageJson.
     * Returns the agent's id so callers can assert on filter behavior.
     * Uses commitInFreshTx pattern from JCLAW-FunctionalTest carrier-thread
     * isolation: inline writes from the carrier thread don't commit before
     * the HTTP request reads them, so spawn a virtual thread to commit first.
     */
    private long seedAgentWithUsage(String agentName, String channel, String peer, String usageJson) {
        var ref = new java.util.concurrent.atomic.AtomicReference<Long>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var agent = new models.Agent();
                    agent.name = agentName;
                    agent.modelProvider = "openrouter";
                    agent.modelId = "gpt-4.1";
                    agent.enabled = true;
                    agent.save();
                    var conv = services.ConversationService.create(agent, channel, peer);
                    services.ConversationService.appendAssistantMessage(conv, "test response", null, usageJson);
                    ref.set(agent.id);
                    return null;
                });
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    /**
     * Seed a conversation with mixed message roles: one user, two assistant
     * (with usageJson), one tool. Used to verify the cost endpoint filters
     * to assistant-only rows by way of {@code usageJson IS NOT NULL}.
     */
    private void seedConversationWithMixedMessages(String agentName, String channel, String peer) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var agent = new models.Agent();
                    agent.name = agentName;
                    agent.modelProvider = "openrouter";
                    agent.modelId = "gpt-4.1";
                    agent.enabled = true;
                    agent.save();
                    var conv = services.ConversationService.create(agent, channel, peer);
                    services.ConversationService.appendUserMessage(conv, "first prompt");
                    var usageJson = "{\"prompt\":10,\"completion\":5,\"total\":15,\"reasoning\":0,"
                            + "\"cached\":0,\"durationMs\":100,\"promptPrice\":0.5,\"completionPrice\":1.0}";
                    services.ConversationService.appendAssistantMessage(conv, "first reply", null, usageJson);
                    services.ConversationService.appendUserMessage(conv, "second prompt");
                    services.ConversationService.appendAssistantMessage(conv, "second reply", null, usageJson);
                    return null;
                });
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    // --- loadtest body-validation branches (provider/model XOR + helper catch paths) ---

    @Test
    void loadtestAcceptsNullValuedOptionalField() {
        // body has "compress": null → readBool's isJsonNull branch returns the
        // default (false), so the request proceeds past helper validation. We
        // only assert the request didn't get rejected at the helper stage
        // (anything other than 400 from those branches is acceptable; loadtest
        // run may still hit other code paths).
        var response = POST(authedLoadtestRequest(),
                "/api/metrics/loadtest", "application/json",
                "{\"concurrency\":1,\"turns\":1,\"compress\":null,\"prompts\":null}");
        // Either a successful 200 OR an unrelated error — what matters is the
        // null-valued helper path was exercised. Reject only on 400 from
        // helpers (would echo "Invalid <type> for ...").
        if (response.status.intValue() == 400) {
            var body = getContent(response);
            assertFalse(body.contains("Invalid boolean for"),
                    "compress:null must NOT trip readBool's catch: " + body);
        }
    }
}
