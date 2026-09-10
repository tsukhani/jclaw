import io.opentelemetry.sdk.metrics.data.MetricData;
import llm.LlmProvider;
import llm.LlmProvider.LlmException;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ProviderConfig;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.BreakerAlarms;
import services.telemetry.BreakerMetrics;
import services.telemetry.OtelRuntime;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.HttpFactories;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-1170: a breaker's state change is the thing worth alarming on, and an operator's
 * decision must never read back as the subsystem failing by itself.
 *
 * <p>Every test mints its own breaker name and removes it afterwards — the registry is
 * process-global and {@code play1} runs test classes concurrently.
 */
class BreakerObservabilityTest extends UnitTest {

    private static final String UNROUTABLE = "http://breaker-observability.invalid/v1";

    private static final String GOOD_BODY = """
            {"id":"r1","model":"m","choices":[{"index":0,\
            "message":{"role":"assistant","content":"secondary answered"},"finish_reason":"stop"}]}""";

    /** Answers every request with one canned 200, counting what actually reached the wire. */
    private static final class Canned implements Interceptor {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override public Response intercept(Chain chain) {
            attempts.incrementAndGet();
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("canned")
                    .body(ResponseBody.create(GOOD_BODY, null))
                    .build();
        }
    }

    private static CircuitBreaker.Transition transition(CircuitBreaker.State to, CircuitBreaker.Reason reason,
                                                        int samples, int failures, int slowCalls) {
        return new CircuitBreaker.Transition(CircuitBreaker.State.CLOSED, to,
                reason, new CircuitBreaker.Stats(to, samples, failures, slowCalls, reason));
    }

    private static void forget(String providerName) {
        CircuitBreakers.remove(LlmResilience.breakerName(providerName));
    }

    private static List<String> transitionAttributes(Collection<MetricData> metrics, String breaker) {
        return metrics.stream()
                .filter(m -> m.getName().equals(BreakerMetrics.TRANSITIONS))
                .flatMap(m -> m.getLongSumData().getPoints().stream())
                .filter(p -> breaker.equals(p.getAttributes().get(BreakerMetrics.BREAKER)))
                .map(p -> p.getAttributes().get(BreakerMetrics.STATE)
                        + "/" + p.getAttributes().get(BreakerMetrics.REASON))
                .sorted()
                .toList();
    }

    @Test
    void anOperatorsTripReadsAsADecisionAndAnAutonomousOneAsEvidence() {
        var forced = BreakerAlarms.describe("LLM provider 'acme'",
                transition(CircuitBreaker.State.OPEN, CircuitBreaker.Reason.MANUAL_TRIP, 0, 0, 0));
        assertTrue(forced.contains("isolated by the operator"), forced);
        assertFalse(forced.contains("failed"),
                () -> "an operator's isolation must not report a failure count: " + forced);

        var autonomous = BreakerAlarms.describe("LLM provider 'acme'",
                transition(CircuitBreaker.State.OPEN, CircuitBreaker.Reason.FAILURE_RATE, 20, 12, 0));
        assertTrue(autonomous.contains("tripped on FAILURE_RATE"), autonomous);
        assertTrue(autonomous.contains("12 of the last 20"), autonomous);
        assertFalse(autonomous.contains("operator"), autonomous);
    }

    @Test
    void anOperatorsResetReadsAsADecisionAndARecoveryAsEvidence() {
        assertTrue(BreakerAlarms.describe("MCP server 'files'",
                        transition(CircuitBreaker.State.CLOSED, CircuitBreaker.Reason.MANUAL_RESET, 0, 0, 0))
                .contains("restored by the operator"));
        assertTrue(BreakerAlarms.describe("MCP server 'files'",
                        transition(CircuitBreaker.State.CLOSED, CircuitBreaker.Reason.PROBE_SUCCEEDED, 0, 0, 0))
                .contains("its probes succeeded"));
    }

    @Test
    void aProbeWindowIsAlarmedToo() {
        // The half-open window is the one an operator most needs to see: the breaker is about
        // to decide, on three calls, whether the outage continues for another cooldown.
        assertTrue(BreakerAlarms.describe("LLM provider 'acme'",
                        transition(CircuitBreaker.State.HALF_OPEN, CircuitBreaker.Reason.COOLDOWN_ELAPSED, 0, 0, 0))
                .contains("being probed"));
    }

    @Test
    void everyStateChangeReachesTheMetricExporter() {
        var name = "jclaw1170-metric-" + System.nanoTime();
        var breaker = new CircuitBreaker(CircuitBreaker.Config.of(4, 0.5, 1, 0L));
        breaker.setTransitionListener(BreakerAlarms.listener(name, "LLM provider 'acme'"));
        TelemetryTestSync.acquire();
        try {
            OtelRuntime.init();
            var metrics = OtelRuntime.captureMetricsForTest(() -> {
                breaker.trip();
                breaker.allowRequest();   // the cooldown is zero, so this is the half-open move
                breaker.recordSuccess();
                breaker.recordFailure();
            });
            assertEquals(
                    List.of("CLOSED/PROBE_SUCCEEDED", "HALF_OPEN/COOLDOWN_ELAPSED",
                            "OPEN/FAILURE_RATE", "OPEN/MANUAL_TRIP"),
                    transitionAttributes(metrics, name),
                    "each state change is its own series, so an alarm can key on OPEN alone");
        } finally {
            TelemetryTestSync.release();
        }
    }

    @Test
    void withExportOffAStateChangeRecordsNoPoint() {
        var name = "jclaw1170-metric-off-" + System.nanoTime();
        var breaker = new CircuitBreaker(CircuitBreaker.Config.of(4, 0.5, 1, 60_000L));
        breaker.setTransitionListener(BreakerAlarms.listener(name, "LLM provider 'acme'"));
        TelemetryTestSync.acquire();
        try {
            OtelRuntime.init();
            breaker.trip();
            assertTrue(transitionAttributes(OtelRuntime.captureMetricsForTest(() -> { }), name).isEmpty(),
                    "the disabled cost must stay one volatile read");
        } finally {
            TelemetryTestSync.release();
        }
    }

    @Test
    void anIsolatedProviderFailsWithADistinctExceptionFromOneThatBroke() {
        var isolated = "jclaw1170-isolated";
        var broken = "jclaw1170-broken";
        try {
            LlmResilience.breakerFor(isolated).trip();
            assertInstanceOf(LlmException.ManuallyIsolated.class,
                    LlmResilience.openBreakerFailure(isolated),
                    "an operator's decision must not surface as the provider having failed");

            var breaker = LlmResilience.breakerFor(broken);
            for (var i = 0; i < 20; i++) breaker.recordFailure();
            assertEquals(CircuitBreaker.State.OPEN, breaker.state());
            var failure = LlmResilience.openBreakerFailure(broken);
            assertInstanceOf(LlmException.ServerError.class, failure);
            assertFalse(failure instanceof LlmException.ManuallyIsolated,
                    "the breaker tripped on its own evidence, so nothing may blame the operator");
        } finally {
            forget(isolated);
            forget(broken);
        }
    }

    @Test
    void anIsolatedPrimaryStillReachesTheSecondary() {
        // The drill this story asked for as a scheduled job, run as a test instead: the
        // failover path is what an isolated primary must exercise, and asserting it here costs
        // nothing where a synthetic model call would cost tokens on every scheduled tick.
        var primaryName = "jclaw1170-failover-primary";
        var secondaryName = "jclaw1170-failover-secondary";
        try {
            LlmResilience.breakerFor(primaryName).trip();

            var transport = new Canned();
            var client = new OkHttpClient.Builder().addInterceptor(transport).build();
            var response = HttpFactories.callWith(client, () -> LlmProvider.chatWithFailover(
                    LlmProvider.forConfig(new ProviderConfig(primaryName, UNROUTABLE, "k", List.of())),
                    LlmProvider.forConfig(new ProviderConfig(secondaryName, UNROUTABLE, "k", List.of())),
                    "m", List.of(ChatMessage.user("hi")), List.of(), 16, null, "test"));

            assertEquals(1, transport.attempts.get(),
                    "exactly one wire call: the isolated primary never reached it, the secondary did");
            assertEquals("secondary answered", response.choices().getFirst().message().content());
            assertEquals(CircuitBreaker.State.CLOSED, LlmResilience.breakerFor(secondaryName).state(),
                    "the primary's isolation must not be charged to the provider that covered for it");
        } finally {
            forget(primaryName);
            forget(secondaryName);
        }
    }
}
