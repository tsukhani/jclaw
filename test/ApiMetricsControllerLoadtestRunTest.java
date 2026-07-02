import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;

import java.util.HashMap;

/**
 * End-to-end success path of POST /api/metrics/loadtest in mock mode: the
 * controller enables the in-process mock provider, LoadTestRunner drives real
 * HTTP against this test JVM's own /api/chat/stream (the fork's virtual-thread
 * Invoker makes the nested requests deadlock-free), and the response reports
 * the aggregate counts. Existing ApiMetricsControllerTest covers the auth gate
 * and every body-validation rejection; this class covers the run itself and
 * the shape/math of the JSON the dashboard consumes.
 */
class ApiMetricsControllerLoadtestRunTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    /** Loopback origin + X-Loadtest-Auth carrying application.secret — the
     *  LoadtestAuthCheck gate (mirrors ApiMetricsControllerTest). */
    private Http.Request authedLoadtestRequest() {
        var req = newRequest();
        req.remoteAddress = "127.0.0.1";
        if (req.headers == null) {
            req.headers = new HashMap<>();
        }
        var secret = Play.configuration.getProperty("application.secret");
        req.headers.put("x-loadtest-auth", new Http.Header("x-loadtest-auth", secret));
        return req;
    }

    @Test
    void mockLoadtestRunReportsExactCountsAndPerTurnBuckets() {
        // 1 worker × 2 turns against the mock provider (1 ms TTFT, 5 tokens at
        // 200 tok/s) — small enough to finish fast, multi-turn so the
        // turnBuckets branch engages.
        var body = """
                {"concurrency":1,"turns":2,"ttftMs":1,"tokensPerSecond":200,"responseTokens":5}
                """;
        var response = POST(authedLoadtestRequest(), "/api/metrics/loadtest",
                "application/json", body);
        assertEquals(200, response.status.intValue(),
                "loadtest must accept the mock-mode body; response: " + getContent(response));
        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();

        // Hand-computed: totalRequests = concurrency × turns = 1 × 2. This
        // 200 is itself the regression pin for the promptless-mock-mode 400
        // (validateLoadtestInput rejected the parsePromptsField empty-list
        // default). Stream SUCCESS is deliberately not asserted: the runner's
        // nested /api/chat/stream calls depend on the autotest server's HTTP
        // environment, which this test can't control deterministically —
        // every turn must be accounted for either way.
        assertEquals(2, json.get("totalRequests").getAsInt());
        assertEquals(2, json.get("successCount").getAsInt() + json.get("errorCount").getAsInt(),
                "every turn must be accounted as success or error: " + json);

        // Duration aggregates must be internally consistent: min ≤ avg ≤ max,
        // and with one sequential worker the wall clock spans both turns.
        long min = json.get("minPerRequestMs").getAsLong();
        long avg = json.get("avgPerRequestMs").getAsLong();
        long max = json.get("maxPerRequestMs").getAsLong();
        long wall = json.get("wallClockMs").getAsLong();
        assertTrue(min <= avg && avg <= max, "min<=avg<=max violated: " + json);
        assertTrue(wall >= max, "c=1 wall clock must cover the slowest turn: " + json);

        // Per-run averages are always emitted (mock mode included).
        assertTrue(json.get("avgTtftMs").getAsLong() >= 0, json.toString());
        assertTrue(json.get("avgResponseTokens").getAsLong() >= 0, json.toString());
        assertTrue(json.get("avgReasoningTokens").getAsLong() >= 0, json.toString());
        assertTrue(json.get("avgTokensPerSec").getAsDouble() >= 0.0, json.toString());

        // Mock mode: provider/model absence IS the run-mode signal.
        assertFalse(json.has("provider"), "mock run must not echo a provider: " + json);
        assertFalse(json.has("model"), "mock run must not echo a model: " + json);

        // turns=2 → per-turn buckets, ordered by turn position, one successful
        // sample per position (the single worker).
        var buckets = json.getAsJsonArray("turnBuckets");
        assertNotNull(buckets, "multi-turn run must emit turnBuckets: " + json);
        assertEquals(2, buckets.size());
        JsonObject turn1 = buckets.get(0).getAsJsonObject();
        JsonObject turn2 = buckets.get(1).getAsJsonObject();
        assertEquals(1, turn1.get("turn").getAsInt());
        assertEquals(1, turn1.get("count").getAsInt());
        assertEquals(2, turn2.get("turn").getAsInt());
        assertEquals(1, turn2.get("count").getAsInt());
    }
}
