import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.TransientRetryInterceptor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transient-failure retry on the scrape/fetch lane (JCLAW-1099).
 *
 * <p>Follows the {@code WebFetchToolTest} idiom: a terminal interceptor returns
 * pre-enqueued responses without opening a socket, so no MockWebServer and no port.
 *
 * <p>What matters here is the scope, not the mechanism. The corpus said 30 of 45
 * failures were {@code 403} and 4 were {@code 401}; retrying those would have tripled
 * our request volume against origins that had already refused, to gain nothing.
 */
class TransientRetryInterceptorTest extends UnitTest {

    private final AtomicInteger attempts = new AtomicInteger();
    private final Deque<Object> canned = new ArrayDeque<>();

    /** A canned response that also carries a {@code Retry-After} header. */
    private record Canned(int status, String retryAfter) {}

    private OkHttpClient client() {
        return new OkHttpClient.Builder()
                .addInterceptor(new TransientRetryInterceptor())
                .addInterceptor(chain -> {
                    attempts.incrementAndGet();
                    var next = canned.poll();
                    if (next instanceof IOException e) throw e;
                    int code = switch (next) {
                        case null -> 200;
                        case Canned c -> c.status();
                        default -> (int) next;
                    };
                    var built = new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message("stub " + code)
                            .body(ResponseBody.create("body", MediaType.get("text/plain")));
                    if (next instanceof Canned c && c.retryAfter() != null) {
                        built.header("Retry-After", c.retryAfter());
                    }
                    return built.build();
                })
                .build();
    }

    private Response get() throws IOException {
        return client().newCall(
                new Request.Builder().url("https://example.test/").get().build()).execute();
    }

    @Test
    void aTransientStatusIsRetriedAndTheRetrySucceeds() throws IOException {
        canned.add(503);
        canned.add(200);
        try (var r = get()) {
            assertEquals(200, r.code());
        }
        assertEquals(2, attempts.get(), "503 should have been retried exactly once here");
    }

    @Test
    void aGuardRefusalIsSurfacedWithoutRetrying() {
        // SsrfGuard signals a blocked address by throwing UnknownHostException, which is
        // an IOException and so was indistinguishable from a transient connect failure:
        // the guard's own refusal was re-issued three times with 1.5s of sleep behind
        // it, and every NXDOMAIN link on a crawl frontier paid the same.
        canned.add(new java.net.UnknownHostException("blocked by SsrfGuard"));
        assertThrows(java.net.UnknownHostException.class, this::get);
        assertEquals(1, attempts.get(), "a blocked address must not be retried");
    }

    @Test
    void aTlsFailureIsSurfacedWithoutRetrying() {
        // A handshake the peer refused is a decision about that peer, not a blip.
        canned.add(new javax.net.ssl.SSLHandshakeException("cert rejected"));
        assertThrows(javax.net.ssl.SSLException.class, this::get);
        assertEquals(1, attempts.get(), "a TLS failure must not be retried");
    }

    @Test
    void everyRetryableStatusIsActuallyRetried() throws IOException {
        // The Javadoc names 429/503/504; only 503 was covered, so dropping either of
        // the others from RETRYABLE failed nothing.
        for (int status : new int[] {429, 503, 504}) {
            attempts.set(0);
            canned.clear();
            canned.add(status);
            canned.add(200);
            try (var r = get()) {
                assertEquals(200, r.code(), "status " + status);
            }
            assertEquals(2, attempts.get(), status + " should have been retried");
        }
    }

    @Test
    void aRetryAfterOfZeroStillWaits() throws IOException {
        // Honoring it literally means retrying instantly against a limiter that has
        // just refused us — the outcome the class's own scoping rationale rules out.
        canned.add(new Canned(429, "0"));
        canned.add(200);
        long t0 = System.nanoTime();
        try (var r = get()) {
            assertEquals(200, r.code());
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs >= 400,
                "Retry-After: 0 must be floored to the base backoff, waited " + elapsedMs + "ms");
    }

    @Test
    void aLongRetryAfterEndsTheRetriesRatherThanSpendingTheCallTimeout() throws IOException {
        // An origin asking for an hour gets the cap, and two caps in a row would leave
        // too little of the 30s call timeout for the attempts themselves. A call that
        // expires classifies as TIMEOUT, which no rung escalates — so the status is
        // handed back instead, still readable as TRUST_BLOCK.
        canned.add(new Canned(503, "3600"));
        canned.add(new Canned(503, "3600"));
        canned.add(200);
        long t0 = System.nanoTime();
        try (var r = get()) {
            assertEquals(503, r.code(), "the second 503 should be returned, not retried again");
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 12_000, "waited " + elapsedMs + "ms — the total cap did not hold");
        assertEquals(2, attempts.get(), "the third attempt must not have been made");
    }

    @Test
    void aNonIdempotentMethodIsNeverReplayed() throws IOException {
        canned.add(503);
        canned.add(200);
        try (var r = client().newCall(new Request.Builder().url("https://example.test/")
                .post(okhttp3.RequestBody.create("x", MediaType.get("text/plain")))
                .build()).execute()) {
            assertEquals(503, r.code(), "a POST must be handed back, not replayed");
        }
        assertEquals(1, attempts.get());
    }

    @Test
    void aRefusalIsNotRetried() throws IOException {
        // The whole point of the scoping. 30 of 45 corpus failures were 403; retrying
        // them is volume aimed at origins that already said no.
        canned.add(403);
        try (var r = get()) {
            assertEquals(403, r.code());
        }
        assertEquals(1, attempts.get(), "403 must not be retried");
    }

    @Test
    void anAuthRefusalIsNotRetried() throws IOException {
        canned.add(401);
        try (var r = get()) {
            assertEquals(401, r.code());
        }
        assertEquals(1, attempts.get(), "401 must not be retried");
    }

    @Test
    void retriesAreBoundedAndTheLastResponseIsReturned() throws IOException {
        canned.add(503);
        canned.add(503);
        canned.add(503);
        try (var r = get()) {
            assertEquals(503, r.code(), "the caller sees the origin's own status, not an error");
        }
        assertEquals(3, attempts.get(), "three attempts total, not unbounded");
    }

    @Test
    void aConnectionFailureIsRetriedAndThenSurfaced() {
        canned.add(new IOException("connect failed 1"));
        canned.add(new IOException("connect failed 2"));
        canned.add(new IOException("connect failed 3"));
        var boom = assertThrows(IOException.class, this::get);
        assertEquals("connect failed 3", boom.getMessage(), "the last failure is what surfaces");
        assertEquals(3, attempts.get());
    }

    @Test
    void aTransientFailureFollowedByASuccessRecovers() throws IOException {
        canned.add(new IOException("connect failed"));
        canned.add(200);
        try (var r = get()) {
            assertEquals(200, r.code());
        }
        assertEquals(2, attempts.get());
    }
}
