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
     *   "turns": 5,
     *   "ttftMs": 100,
     *   "tokensPerSecond": 50,
     *   "responseTokens": 40,
     *   "compress": false
     * }</pre>
     *
     * <p>{@code turns} is the number of sequential chat requests each worker
     * sends *within the same conversation* — turn 1 starts a fresh conversation,
     * turn 2..N reuse the {@code conversationId} the server returned for turn
     * 1. This shape exercises growing-history behavior (system-prompt
     * assembly cost, provider prompt-cache hits, model recall on repeated
     * questions) under load. To simulate N independent fresh-conversation
     * starts instead, set {@code turns=1} and crank {@code concurrency}.
     *
     * <p>{@code prompts} (optional) is an array of per-turn user messages.
     * When provided, turn t sends {@code prompts[t]} instead of replaying
     * {@code userMessage}; the array must contain at least {@code turns}
     * entries. Mutually exclusive with a non-blank {@code userMessage}. Use
     * to drive a varied question sequence inside a growing conversation —
     * separates "model recall on repeated questions" (single-message mode)
     * from "model behavior across a topic flow" (varied mode).
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
        int turns = readInt(body, "turns", 5);
        int ttftMs = readInt(body, "ttftMs", 100);
        int tokensPerSecond = readInt(body, "tokensPerSecond", 50);
        int responseTokens = readInt(body, "responseTokens", 40);
        int simulatedToolCalls = readInt(body, "simulatedToolCalls", 0);
        int toolSleepMs = readInt(body, "toolSleepMs", 200);
        boolean compress = readBool(body, "compress", false);

        // Real-provider mode is implied by both `provider` and `model` being
        // set (non-blank). Operators don't pass a separate `real` flag — its
        // joint presence is the signal. Mismatched half-set state is rejected
        // below so callers can't accidentally fall through to mock mode by
        // forgetting one of the two fields.
        String provider = readString(body, "provider", null);
        String model = readString(body, "model", null);
        boolean providerSet = provider != null && !provider.isBlank();
        boolean modelSet = model != null && !model.isBlank();
        if (providerSet != modelSet) {
            error(400, "provider and model must be set together (or both omitted for mock mode)");
        }
        boolean real = providerSet;  // both set ⇔ real-provider run
        // Optional per-run user message override. Default lives in
        // LoadTestRunner so the constant has one home.
        String userMessage = readString(body, "userMessage", null);

        // Optional varied-prompts mode: an array of per-turn user messages.
        // When present, turn t sends prompts[t] instead of replaying
        // userMessage. Mutually exclusive with a non-blank userMessage so the
        // wire format never carries two conflicting per-turn message
        // strategies. Validated against `turns` below so workers can index
        // the array directly without bounds-checking.
        java.util.List<String> prompts = null;
        if (body != null && body.has("prompts") && !body.get("prompts").isJsonNull()) {
            try {
                var arr = body.getAsJsonArray("prompts");
                prompts = new java.util.ArrayList<>(arr.size());
                for (var el : arr) prompts.add(el.getAsString());
            } catch (Exception _) {
                error(400, "Invalid 'prompts' — must be an array of strings");
            }
            if (userMessage != null && !userMessage.isBlank()) {
                error(400, "userMessage and prompts are mutually exclusive");
            }
        }

        int maxConcurrency = ConfigService.getInt("provider.loadtest-mock.maxConcurrency", 100);
        int maxTurns = ConfigService.getInt("provider.loadtest-mock.maxTurns", 50);
        if (concurrency < 1 || concurrency > maxConcurrency) {
            error(400, "concurrency must be between 1 and " + maxConcurrency);
        }
        if (turns < 1 || turns > maxTurns) {
            error(400, "turns must be between 1 and " + maxTurns);
        }
        if (prompts != null && prompts.size() < turns) {
            error(400, "prompts array has " + prompts.size() + " entries but turns=" + turns
                    + "; provide at least one prompt per turn");
        }

        // Enable the mock provider in its own transaction so it's committed
        // before the loadtest requests fire (they use separate connections).
        // Skip in real-provider mode — that path uses an existing registered
        // provider seeded by DefaultConfigJob or the operator at boot.
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

        // Auto-bump the LLM dispatcher cap if --concurrency would saturate it.
        // The default 64-per-host (or auto-tuned 8*cores) is sized for steady
        // production traffic; loadtest at higher concurrency against one host
        // (mock or single real provider) would otherwise queue at the
        // dispatcher and inflate the ttft segment by the queue time. Snapshot
        // here, restore in finally so the cap returns to its operator-tuned
        // value after the test.
        int origPerHost = utils.HttpFactories.llmDispatcherMaxRequestsPerHost();
        int origMax = utils.HttpFactories.llmDispatcherMaxRequests();
        boolean dispatcherBumped = concurrency > origPerHost;
        if (dispatcherBumped) {
            int newPerHost = concurrency + 16;
            int newMax = Math.max(origMax, newPerHost * 2);
            utils.HttpFactories.setLlmDispatcherCapTransient(newPerHost, newMax);
        }

        try {
            var result = LoadTestRunner.run(new LoadTestRunner.Request(
                    concurrency, turns, compress,
                    new LoadTestHarness.Scenario(ttftMs, tokensPerSecond, responseTokens,
                            simulatedToolCalls, toolSleepMs),
                    real, provider, model, userMessage, prompts));

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
            // Per-run averages — useful in both modes. For mock runs they
            // confirm the harness honored the requested scenario; for real-
            // provider runs they're the only reliable view of provider
            // performance (the mock-shape ttftMs / tokensPerSecond inputs are
            // ignored by the runner once it routes to a real provider).
            out.addProperty("avgTtftMs", result.avgTtftMs());
            out.addProperty("avgResponseTokens", result.avgResponseTokens());
            // Reasoning tokens are surfaced separately so cross-model
            // comparisons see the full picture: a "70 visible / 3000 reasoning"
            // model is doing very different work from a "70 visible / 0
            // reasoning" model even when their visible-token rates match.
            out.addProperty("avgReasoningTokens", result.avgReasoningTokens());
            out.addProperty("avgTokensPerSec", round1(result.avgTokensPerSec()));
            // Provider/model are echoed only in real-provider mode. Their
            // presence (vs absence) IS the run-mode signal — no separate
            // `realProvider` field needed.
            if (real) {
                out.addProperty("provider", provider);
                out.addProperty("model", model);
            }
            // Per-turn breakdown only when turns > 1 (LoadTestRunner returns
            // null otherwise). Renders as an array of {turn, count, ttftMeanMs,
            // ttftP50Ms, ...} objects, ordered by turn position.
            if (result.turnBuckets() != null) {
                out.add("turnBuckets", INSTANCE.toJsonTree(result.turnBuckets()));
            }
            // Server-side segment breakdown for this run only — see
            // LoadTestRunner.SegmentBreakdown. Always present (single-turn
            // runs still benefit from the segment view).
            if (result.serverSegments() != null && !result.serverSegments().isEmpty()) {
                out.add("serverSegments", INSTANCE.toJsonTree(result.serverSegments()));
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
        } finally {
            if (dispatcherBumped) {
                utils.HttpFactories.setLlmDispatcherCapTransient(origPerHost, origMax);
            }
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

    /** Round to one decimal so tokens-per-second prints as 47.3 instead of 47.27272727. */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
