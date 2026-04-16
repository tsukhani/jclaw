package controllers;

import com.google.gson.JsonObject;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.LoadTestHarness;
import services.LoadTestRunner;
import utils.LatencyStats;

import static utils.GsonHolder.INSTANCE;

/**
 * Runtime observability endpoints. In-memory only — histograms reset
 * on JVM restart or via {@link #resetLatency()}. Includes an auth-gated
 * load-test harness that uses an in-process mock LLM provider.
 */
@With(AuthCheck.class)
public class ApiMetricsController extends Controller {

    /** GET /api/metrics/latency — JSON snapshot of segment histograms. */
    public static void latency() {
        renderJSON(LatencyStats.snapshot().toString());
    }

    /** DELETE /api/metrics/latency — reset histograms. */
    public static void resetLatency() {
        LatencyStats.reset();
        renderJSON("{\"status\":\"reset\"}");
    }

    /**
     * POST /api/metrics/loadtest — run a synchronous load test against
     * /api/chat/stream using the in-process mock provider.
     *
     * <p>Request body (all fields optional, shown with defaults):
     * <pre>{
     *   "concurrency": 10,
     *   "iterations": 5,
     *   "ttftMs": 100,
     *   "tokensPerSecond": 50,
     *   "responseTokens": 40
     * }</pre>
     *
     * <p>Returns the aggregate counts + wall-clock. Use GET
     * /api/metrics/latency afterwards for per-segment histograms.
     */
    public static void loadtest() {
        var body = JsonBodyReader.readJsonBody();
        int concurrency = readInt(body, "concurrency", 10);
        int iterations = readInt(body, "iterations", 5);
        int ttftMs = readInt(body, "ttftMs", 100);
        int tokensPerSecond = readInt(body, "tokensPerSecond", 50);
        int responseTokens = readInt(body, "responseTokens", 40);
        int simulatedToolCalls = readInt(body, "simulatedToolCalls", 0);
        int toolSleepMs = readInt(body, "toolSleepMs", 200);

        int maxConcurrency = ConfigService.getInt("provider.loadtest-mock.maxConcurrency", 100);
        int maxIterations = ConfigService.getInt("provider.loadtest-mock.maxIterations", 50);
        if (concurrency < 1 || concurrency > maxConcurrency) {
            error(400, "concurrency must be between 1 and " + maxConcurrency);
        }
        if (iterations < 1 || iterations > maxIterations) {
            error(400, "iterations must be between 1 and " + maxIterations);
        }

        // Enable the mock provider in its own transaction so it's committed
        // before the loadtest requests fire (they use separate connections).
        try {
            JPA.withTransaction("default", false,
                    (play.libs.F.Function0<Void>) () -> {
                        ConfigService.setWithSideEffects("provider.loadtest-mock.enabled", "true");
                        return null;
                    });
        } catch (Throwable t) {
            error(500, "Failed to enable mock provider: " + t.getMessage());
        }

        try {
            var result = LoadTestRunner.run(new LoadTestRunner.Request(
                    concurrency, iterations,
                    new LoadTestHarness.Scenario(ttftMs, tokensPerSecond, responseTokens,
                            simulatedToolCalls, toolSleepMs)));

            LoadTestHarness.stop();
            LoadTestRunner.disable();

            var out = new JsonObject();
            out.addProperty("totalRequests", result.totalRequests());
            out.addProperty("successCount", result.successCount());
            out.addProperty("errorCount", result.errorCount());
            out.addProperty("wallClockMs", result.wallClockMs());
            out.addProperty("avgPerRequestMs", result.avgPerRequestMs());
            out.addProperty("minPerRequestMs", result.minPerRequestMs());
            out.addProperty("maxPerRequestMs", result.maxPerRequestMs());
            out.addProperty("mockPort", result.mockPort());
            out.addProperty("agentId", result.agentId());
            renderJSON(INSTANCE.toJson(out));
        } catch (play.mvc.results.Result r) {
            throw r;
        } catch (Exception e) {
            LoadTestHarness.stop();
            LoadTestRunner.disable();
            error(500, "Load test failed: " + e.getMessage());
        }
    }

    /** DELETE /api/metrics/loadtest — stop the embedded mock provider. */
    public static void stopLoadtest() {
        LoadTestHarness.stop();
        renderJSON("{\"status\":\"stopped\"}");
    }

    /** DELETE /api/metrics/loadtest/data — delete loadtest conversations, messages, and events. */
    public static void cleanLoadtest() {
        LoadTestRunner.cleanupConversations();
        renderJSON("{\"status\":\"cleaned\"}");
    }

    private static int readInt(com.google.gson.JsonObject body, String key, int defaultValue) {
        if (body == null || !body.has(key) || body.get(key).isJsonNull()) return defaultValue;
        try {
            return body.get(key).getAsInt();
        } catch (Exception _) {
            error(400, "Invalid integer for '" + key + "'");
            return defaultValue; // unreachable
        }
    }
}
