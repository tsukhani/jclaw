import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import utils.LatencyStats;
import utils.LatencyTrace;

/**
 * Functional tests for ApiMetricsController. Drives the LatencyTrace/LatencyStats
 * pipeline directly rather than through a real LLM call — the goal is to assert
 * the instrumentation plumbing, not provider behavior.
 */
public class ApiMetricsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        LatencyStats.reset();
    }

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    @Test
    public void latencyRequiresAuth() {
        var response = GET("/api/metrics/latency");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void latencyReturnsEmptyJsonWhenNoSamples() {
        login();
        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertEquals("{}", getContent(response));
    }

    @Test
    public void endedTraceRecordsAllSegments() throws Exception {
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
    public void prologueSubSegmentsAreRecordedWhenMarksPresent() throws Exception {
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
    public void prologueSubSegmentsSkippedWhenMarksMissing() {
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
    public void earlyExitTraceWithoutPrologueRecordsNothing() {
        login();
        new LatencyTrace().end();
        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        assertEquals("{}", getContent(response));
    }

    @Test
    public void endIsIdempotent() {
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
    public void queueWaitSegmentIsRecordedWhenAcceptedStampPresent() throws Exception {
        login();
        // Simulate the PlayHandler stamp by writing acceptedAtNanos to the
        // current request's args before building the trace from it.
        long simulatedAcceptNs = System.nanoTime() - 4_000_000L; // 4ms ago
        play.mvc.Http.Request.current().args.put("acceptedAtNanos", simulatedAcceptNs);

        var trace = LatencyTrace.fromCurrentRequest();
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
    public void queueWaitAbsentWhenNoAcceptedStamp() throws Exception {
        login();
        play.mvc.Http.Request.current().args.remove("acceptedAtNanos");

        var trace = LatencyTrace.fromCurrentRequest();
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        var body = getContent(response);
        assertFalse(body.contains("\"queue_wait\""), body);
    }

    @Test
    public void loadtestRequiresAuth() {
        var response = POST("/api/metrics/loadtest", "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void loadtestRejectsInvalidConcurrency() {
        login();
        var response = POST("/api/metrics/loadtest", "application/json",
                "{\"concurrency\":0,\"iterations\":1}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void loadtestRejectsInvalidIterations() {
        login();
        var response = POST("/api/metrics/loadtest", "application/json",
                "{\"concurrency\":1,\"iterations\":9999}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void stopLoadtestEndpointAlwaysSucceeds() {
        login();
        var response = DELETE("/api/metrics/loadtest");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"status\":\"stopped\""));
    }

    @Test
    public void resetClearsHistograms() {
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
    public void cleanLoadtestDataDoesNotTouchHistograms() {
        // /api/metrics/latency and /api/metrics/loadtest/data have orthogonal
        // scopes: the former clears runtime statistics, the latter clears DB
        // artifacts from load-test runs. Purging one should leave the other
        // untouched.
        login();
        var trace = new LatencyTrace();
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();

        var cleanResp = DELETE("/api/metrics/loadtest/data");
        assertIsOk(cleanResp);
        assertTrue(getContent(cleanResp).contains("\"status\":\"cleaned\""));

        var response = GET("/api/metrics/latency");
        assertIsOk(response);
        // Histograms must survive the loadtest-data cleanup.
        assertTrue(getContent(response).contains("\"total\""), getContent(response));
    }
}
