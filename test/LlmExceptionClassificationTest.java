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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.CircuitBreakers;
import utils.HttpFactories;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-1166: an {@link LlmException} names where the fault lies, so a circuit breaker
 * layered on provider calls records the provider's outages and ignores our own bad
 * requests. These tests pin the mapping at the boundary a breaker would wrap — what
 * {@code chat()} throws — rather than at {@code attemptRequest}, which is private and
 * whose retryable branches never throw at all.
 *
 * <p>The transport is substituted through {@link HttpFactories#runWith}, so the base URL
 * points at {@code .invalid}: a leak in the seam surfaces as a failed call, never as a
 * live request. {@code %test.llm.retry.backoff-percent=0} collapses the waits, so the
 * four-attempt cases cost no wall time.
 */
class LlmExceptionClassificationTest extends UnitTest {

    /** MAX_RETRIES (3) plus the initial attempt — what a fully retried branch spends. */
    private static final int FULL_RETRY_ATTEMPTS = 4;

    private static final String UNROUTABLE = "http://llm-classification.invalid/v1";

    private static final String PROVIDER = "jclaw1166-classify";

    private static LlmProvider provider() {
        return LlmProvider.forConfig(
                new ProviderConfig(PROVIDER, UNROUTABLE, "test-key", List.of()));
    }

    /** Every case here is an exhausted chat, and three in a row open the provider's breaker
     *  (JCLAW-1167), which would answer the fourth case before the canned transport does. */
    @BeforeEach
    void forgetTheBreaker() {
        CircuitBreakers.remove(LlmResilience.breakerName(PROVIDER));
    }

    /** Drive one {@code chat()} against a canned status/body; returns the failure and the attempt count. */
    private static Attempted chat(int status, String body) {
        var attempts = new AtomicInteger();
        Interceptor canned = chain -> {
            attempts.incrementAndGet();
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("canned")
                    .body(ResponseBody.create(body, null))
                    .build();
        };
        return new Attempted(failureUnder(canned), attempts.get());
    }

    private static LlmException failureUnder(Interceptor transport) {
        var client = new OkHttpClient.Builder().addInterceptor(transport).build();
        return HttpFactories.callWith(client, () -> assertThrows(LlmException.class, () ->
                provider().chat("m", List.of(ChatMessage.user("hi")), List.of(), 16, null, "test")));
    }

    private record Attempted(LlmException failure, int attempts) {}

    @Test
    void a4xxIsAClientErrorAndSpendsOneAttempt() {
        for (var status : List.of(400, 401, 403, 404, 422)) {
            var result = chat(status, "{\"error\":{\"message\":\"nope\"}}");
            assertInstanceOf(LlmException.ClientError.class, result.failure(),
                    status + " is our request, not the provider's health");
            assertEquals(1, result.attempts(),
                    "a thrown LlmException aborts the retry loop — " + status + " must not be retried");
        }
    }

    @Test
    void aPermanentQuota429IsAClientError() {
        var result = chat(429, "{\"error\":{\"code\":\"insufficient_quota\"}}");
        assertInstanceOf(LlmException.ClientError.class, result.failure(),
                "an exhausted balance never clears by waiting, so it must not count against the provider");
        assertEquals(1, result.attempts());
    }

    @Test
    void aRetryable429IsRateLimited() {
        var result = chat(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        assertInstanceOf(LlmException.RateLimited.class, result.failure());
        assertInstanceOf(LlmException.RateLimited.class, result.failure().getCause(),
                "the exhaustion wrapper keeps the last attempt as its cause");
        assertEquals(FULL_RETRY_ATTEMPTS, result.attempts());
    }

    @Test
    void a5xxIsAServerError() {
        for (var status : List.of(500, 502, 503)) {
            var result = chat(status, "upstream is unwell");
            assertInstanceOf(LlmException.ServerError.class, result.failure(), status + " is a provider fault");
            assertEquals(FULL_RETRY_ATTEMPTS, result.attempts());
        }
    }

    @Test
    void aDriverIoFailureIsTransport() {
        var attempts = new AtomicInteger();
        Interceptor refusing = _ -> {
            attempts.incrementAndGet();
            throw new IOException("connect refused");
        };
        var failure = failureUnder(refusing);

        assertInstanceOf(LlmException.Transport.class, failure);
        assertEquals(FULL_RETRY_ATTEMPTS, attempts.get());
        assertTrue(causeChainHolds(failure, IOException.class),
                "the driver's IOException must survive as a cause, not be flattened into a message");
    }

    @Test
    void everyClassificationStaysCatchableAsAnLlmException() {
        // The retry-abort in executeWithRetry and the chatWithFailover trigger are both
        // catch (LlmException); a subclass that escaped that would silently disable both.
        assertInstanceOf(LlmException.class, new LlmException.ClientError("x"));
        assertInstanceOf(LlmException.class, new LlmException.ServerError("x"));
        assertInstanceOf(LlmException.class, new LlmException.RateLimited("x"));
        assertInstanceOf(LlmException.class, new LlmException.Transport("x", new IOException()));
    }

    @Test
    void theExhaustionMessageIsUnchangedByTheClassification() {
        var failure = chat(500, "boom").failure();
        assertTrue(failure.getMessage().startsWith("All retries exhausted for jclaw1166-classify"),
                "classification changes the exception's type, never what an operator reads: "
                        + failure.getMessage());
    }

    private static boolean causeChainHolds(Throwable t, Class<?> type) {
        for (var cur = t; cur != null; cur = cur.getCause()) {
            if (type.isInstance(cur)) return true;
        }
        return false;
    }
}
