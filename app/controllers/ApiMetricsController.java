package controllers;

import com.google.gson.JsonObject;
import play.db.jpa.JPA;
import play.mvc.Before;
import play.mvc.Controller;
import services.ConfigService;
import services.LoadTestHarness;
import services.LoadTestRunner;
import utils.LatencyStats;

import static utils.GsonHolder.INSTANCE;

/**
 * Runtime observability endpoints. In-memory only — histograms reset
 * on JVM restart or via {@link #resetLatency()}. Includes an auth-gated
 * load-test harness that uses an in-process mock LLM provider.
 *
 * <p>Two distinct auth guards apply, scoped via {@link Before}{@code (only=...)}
 * filters because Play 1.x's {@code @With} annotation is class-level only
 * (cannot be applied per-action):
 * <ul>
 *   <li>{@link AuthCheck#checkAuthentication} on the latency endpoints —
 *       standard admin session check, same as the rest of the API.</li>
 *   <li>{@link LoadtestAuthCheck#checkLoadtestAuth} on the loadtest
 *       endpoints — loopback origin plus X-Loadtest-Auth header
 *       containing application.secret. Required because the loadtest
 *       pipeline has no plaintext admin password to authenticate with
 *       after commit caf9422 (JCLAW-181).</li>
 * </ul>
 *
 * <p>The interceptor methods below delegate to the canonical static
 * checks so the gating logic stays single-sourced; this controller is
 * just selecting which gate runs for which action.
 */
public class ApiMetricsController extends Controller {

    @Before(only = {"latency", "resetLatency"})
    static void requireAdminSession() {
        AuthCheck.checkAuthentication();
    }

    @Before(only = {"loadtest", "stopLoadtest", "cleanLoadtest"})
    static void requireLoadtestAuth() {
        LoadtestAuthCheck.checkLoadtestAuth();
    }

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
     *   "responseTokens": 40,
     *   "compress": false
     * }</pre>
     *
     * <p>{@code compress=true} adds {@code Accept-Encoding: br, gzip} to each
     * harness request so the pipeline's {@link io.netty.handler.codec.http.HttpContentCompressor}
     * (when wired) actually engages on the response. Without it, Java's
     * {@code HttpClient} sends no Accept-Encoding and the compressor passes
     * traffic through as identity — so loadtest results reflect the controller
     * hot path, not the encoding path.
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
        boolean compress = readBool(body, "compress", false);

        // real=true swaps the mock harness for a registered provider plus the
        // supplied model. provider defaults to ollama-local; pass
        // ollama-cloud, openrouter, openai, etc. to drive cloud baselines.
        // The AC3 perf baseline needs real network and real SSE timing,
        // which the mock can't reproduce.
        boolean real = readBool(body, "real", false);
        String provider = readString(body, "provider", null);
        String model = readString(body, "model", null);

        int maxConcurrency = ConfigService.getInt("provider.loadtest-mock.maxConcurrency", 100);
        int maxIterations = ConfigService.getInt("provider.loadtest-mock.maxIterations", 50);
        if (concurrency < 1 || concurrency > maxConcurrency) {
            error(400, "concurrency must be between 1 and " + maxConcurrency);
        }
        if (iterations < 1 || iterations > maxIterations) {
            error(400, "iterations must be between 1 and " + maxIterations);
        }
        if (real && (model == null || model.isBlank())) {
            error(400, "model is required when real=true");
        }

        // Enable the mock provider in its own transaction so it's committed
        // before the loadtest requests fire (they use separate connections).
        // Skip when real=true — that path uses ollama-local, which the
        // DefaultConfigJob already seeded at boot.
        if (!real) {
            try {
                JPA.withTransaction("default", false,
                        (play.libs.F.Function0<Void>) () -> {
                            ConfigService.setWithSideEffects("provider.loadtest-mock.enabled", "true");
                            return null;
                        });
            } catch (Throwable t) {
                error(500, "Failed to enable mock provider: " + t.getMessage());
            }
        }

        try {
            var result = LoadTestRunner.run(new LoadTestRunner.Request(
                    concurrency, iterations, compress,
                    new LoadTestHarness.Scenario(ttftMs, tokensPerSecond, responseTokens,
                            simulatedToolCalls, toolSleepMs),
                    real, provider, model));

            if (!real) {
                LoadTestHarness.stop();
                LoadTestRunner.disable();
            }

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
            out.addProperty("realProvider", real);
            if (real) {
                out.addProperty("provider",
                        provider == null || provider.isBlank()
                                ? LoadTestRunner.DEFAULT_REAL_PROVIDER
                                : provider);
                out.addProperty("model", model);
            }
            renderJSON(INSTANCE.toJson(out));
        } catch (play.mvc.results.Result r) {
            throw r;
        } catch (Exception e) {
            if (!real) {
                LoadTestHarness.stop();
                LoadTestRunner.disable();
            }
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

    private static boolean readBool(com.google.gson.JsonObject body, String key, boolean defaultValue) {
        if (body == null || !body.has(key) || body.get(key).isJsonNull()) return defaultValue;
        try {
            return body.get(key).getAsBoolean();
        } catch (Exception _) {
            error(400, "Invalid boolean for '" + key + "'");
            return defaultValue; // unreachable
        }
    }

    private static String readString(com.google.gson.JsonObject body, String key, String defaultValue) {
        if (body == null || !body.has(key) || body.get(key).isJsonNull()) return defaultValue;
        try {
            return body.get(key).getAsString();
        } catch (Exception _) {
            error(400, "Invalid string for '" + key + "'");
            return defaultValue; // unreachable
        }
    }
}
