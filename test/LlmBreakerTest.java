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
import play.Play;
import play.test.UnitTest;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.HttpFactories;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * JCLAW-1167: the per-provider circuit breaker on the synchronous chat path.
 *
 * <p>Every test mints its own provider name, so its breaker is its own registry entry and
 * {@code play1}'s concurrent test classes cannot see each other's outcomes; each removes it
 * afterwards. The transport is substituted through {@link HttpFactories#runWith}, and the base
 * URL points at {@code .invalid} so a leak in that seam fails rather than reaching a network.
 */
class LlmBreakerTest extends UnitTest {

    private static final String UNROUTABLE = "http://llm-breaker.invalid/v1";

    /** MAX_RETRIES (3) plus the initial attempt — what one exhausted chat spends on the wire. */
    private static final int FULL_RETRY_ATTEMPTS = 4;

    private static final String GOOD_BODY = """
            {"id":"r1","model":"m","choices":[{"index":0,\
            "message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""";

    /** A 200 whose body is not a chat completion: a provider fault chat() reports without retrying. */
    private static final String GARBAGE_BODY = "<html>upstream ate the response</html>";

    /** Answers every request with one canned status/body, counting what actually reached the wire. */
    private static final class Canned implements Interceptor {
        private final int status;
        private final String body;
        private final AtomicInteger attempts = new AtomicInteger();

        Canned(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override public Response intercept(Chain chain) {
            attempts.incrementAndGet();
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("canned")
                    .body(ResponseBody.create(body, null))
                    .build();
        }
    }

    private static void chat(String providerName, Canned transport) {
        var client = new OkHttpClient.Builder().addInterceptor(transport).build();
        HttpFactories.runWith(client, () -> LlmProvider
                .forConfig(new ProviderConfig(providerName, UNROUTABLE, "test-key", List.of()))
                .chat("m", List.of(ChatMessage.user("hi")), List.of(), 16, null, "test"));
    }

    private static LlmException failingChat(String providerName, Canned transport) {
        return assertThrows(LlmException.class, () -> chat(providerName, transport));
    }

    private static void forget(String providerName) {
        CircuitBreakers.remove(LlmResilience.breakerName(providerName));
    }

    @Test
    void anExhaustedChatIsOneFailureNotOnePerAttempt() {
        var name = "jclaw1167-exhausted";
        try {
            var transport = new Canned(500, "upstream is unwell");
            assertInstanceOf(LlmException.ServerError.class, failingChat(name, transport));

            assertEquals(FULL_RETRY_ATTEMPTS, transport.attempts.get(), "the retry loop is unchanged");
            var stats = LlmResilience.breakerFor(name).stats();
            assertEquals(1, stats.samples(),
                    "the breaker wraps the whole retry loop; one per attempt would quarter the threshold");
            assertEquals(1, stats.failures());
        } finally {
            forget(name);
        }
    }

    @Test
    void aClientErrorIsNeitherSuccessNorFailure() {
        var name = "jclaw1167-clienterror";
        try {
            assertInstanceOf(LlmException.ClientError.class,
                    failingChat(name, new Canned(400, "{\"error\":{\"message\":\"nope\"}}")));

            var stats = LlmResilience.breakerFor(name).stats();
            assertEquals(0, stats.samples(),
                    "a request this codebase got wrong says nothing about the provider's health");
            assertEquals(0, stats.failures());
        } finally {
            forget(name);
        }
    }

    @Test
    void aServedChatRecordsASuccess() {
        var name = "jclaw1167-success";
        try {
            chat(name, new Canned(200, GOOD_BODY));

            var stats = LlmResilience.breakerFor(name).stats();
            assertEquals(1, stats.samples());
            assertEquals(0, stats.failures());
        } finally {
            forget(name);
        }
    }

    @Test
    void aLowTrafficProviderReachesTheMinimumAndTrips() {
        var name = "jclaw1167-lowtraffic";
        try {
            // A garbage 200 is a provider fault chat() raises after a single attempt, so this
            // drives exactly one outcome per chat without spending the retry loop 20 times.
            for (var i = 0; i < 19; i++) {
                assertInstanceOf(LlmException.class, failingChat(name, new Canned(200, GARBAGE_BODY)));
            }
            assertEquals(CircuitBreaker.State.CLOSED, LlmResilience.breakerFor(name).state(),
                    "19 outcomes is under the 20-call minimum, so no rate is evaluated yet");

            failingChat(name, new Canned(200, GARBAGE_BODY));
            assertEquals(CircuitBreaker.State.OPEN, LlmResilience.breakerFor(name).state(),
                    "a provider nobody calls often must still be able to trip");
        } finally {
            forget(name);
        }
    }

    @Test
    void anOpenBreakerFailsBeforeTheWire() {
        var name = "jclaw1167-open";
        try {
            LlmResilience.breakerFor(name).trip();

            // The transport would have answered 200: what fails the call is the breaker, and
            // the untouched attempt counter is the evidence it failed before the retry loop.
            var transport = new Canned(200, GOOD_BODY);
            var failure = failingChat(name, transport);

            assertEquals(0, transport.attempts.get());
            assertInstanceOf(LlmException.ServerError.class, failure,
                    "chatWithFailover triggers on LlmException, so the fail-fast must be one");
        } finally {
            forget(name);
        }
    }

    @Test
    void oneProviderTrippingLeavesAnotherServing() {
        var slow = "jclaw1167-isolated-slow";
        var healthy = "jclaw1167-isolated-healthy";
        try {
            LlmResilience.breakerFor(slow).trip();

            var transport = new Canned(200, GOOD_BODY);
            chat(healthy, transport);

            assertEquals(1, transport.attempts.get());
            assertEquals(CircuitBreaker.State.CLOSED, LlmResilience.breakerFor(healthy).state());
        } finally {
            forget(slow);
            forget(healthy);
        }
    }

    @Test
    void aClientErrorProbeDoesNotStrandItsHalfOpenPermit() {
        var name = "jclaw1167-halfopen";
        var breaker = breakerWith(name, Map.of("llm.breaker.wait-seconds", "0"));
        try {
            breaker.trip();

            // Each 4xx is admitted as a probe. Recording nothing for it — the rule in CLOSED —
            // would hold its permit for good, and a HALF_OPEN breaker that admits nothing never
            // leaves HALF_OPEN.
            for (var i = 0; i < 3; i++) {
                assertInstanceOf(LlmException.ClientError.class, failingChat(name, new Canned(400, "{}")));
            }

            assertEquals(CircuitBreaker.State.CLOSED, breaker.state(),
                    "three answered probes close it; a stranded permit would leave it HALF_OPEN for good");
        } finally {
            forget(name);
        }
    }

    @Test
    void theDefaultsAreTheOnesApplicationConfDocuments() {
        var config = LlmResilience.config();

        assertEquals(100, config.windowSize());
        assertEquals(0.5, config.failureRateThreshold(), 1e-9);
        assertEquals(20, config.minVolume());
        assertEquals(60_000L, config.cooldownMillis());
        assertEquals(3, config.halfOpenPermits());
        assertEquals(30_000L, config.slowCallDurationMillis());
        assertEquals(0.5, config.slowCallRateThreshold(), 1e-9);
    }

    @Test
    void configuredValuesOverrideThoseDefaults() {
        var config = withKeys(Map.of(
                "llm.breaker.window", "13",
                "llm.breaker.failure-rate", "97",
                "llm.breaker.min-calls", "999",
                "llm.breaker.wait-seconds", "7",
                "llm.breaker.half-open-probes", "5",
                "llm.breaker.stall-seconds", "11",
                "llm.breaker.slow-rate", "80"), LlmResilience::config);

        assertEquals(13, config.windowSize());
        assertEquals(0.97, config.failureRateThreshold(), 1e-9);
        assertEquals(999, config.minVolume());
        assertEquals(7_000L, config.cooldownMillis());
        assertEquals(5, config.halfOpenPermits());
        assertEquals(11_000L, config.slowCallDurationMillis());
        assertEquals(0.8, config.slowCallRateThreshold(), 1e-9);
    }

    private static CircuitBreaker breakerWith(String providerName, Map<String, String> keys) {
        return withKeys(keys, () -> LlmResilience.breakerFor(providerName));
    }

    /**
     * Run {@code body} with {@code keys} set on the static configuration. Play.configuration is
     * process-global and play1 runs test classes concurrently, so the window is one read and the
     * values are chosen to be harmless: a breaker some other class happened to mint inside it
     * would only be tuned never to open.
     */
    private static <T> T withKeys(Map<String, String> keys, Supplier<T> body) {
        keys.forEach(Play.configuration::setProperty);
        try {
            return body.get();
        } finally {
            keys.keySet().forEach(Play.configuration::remove);
        }
    }
}
