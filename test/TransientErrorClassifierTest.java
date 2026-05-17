import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.TransientErrorClassifier;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Coverage for {@link TransientErrorClassifier}. Two axes:
 * <ul>
 *   <li>Class-name detection — JDK network exception types should
 *       classify transient even with empty messages.</li>
 *   <li>Message-regex detection — the wide pattern set covering
 *       rate limit, overloaded, network/timeout, POSIX codes, 5xx.</li>
 * </ul>
 * Plus negative cases that must NOT classify transient — auth
 * failures, parse errors, NPE, and other "the code is wrong" signals
 * that retries would just waste budget on.
 */
class TransientErrorClassifierTest extends UnitTest {

    // === Class-name detection ===

    @Test
    void socketTimeoutIsTransient() {
        assertTrue(TransientErrorClassifier.isTransient(
                new SocketTimeoutException("connect")));
    }

    @Test
    void connectExceptionIsTransient() {
        assertTrue(TransientErrorClassifier.isTransient(
                new ConnectException("Connection refused")));
    }

    @Test
    void unknownHostIsTransient() {
        // DNS failures can be transient (resolver hiccup) — classify
        // as retry-worthy. False positives cost one retry.
        assertTrue(TransientErrorClassifier.isTransient(
                new UnknownHostException("api.openai.com")));
    }

    @Test
    void classNameDetectionWorksWithNullMessage() {
        assertTrue(TransientErrorClassifier.isTransient(
                new SocketTimeoutException()));
    }

    // === Message-regex detection ===

    @Test
    void rateLimitMessageIsTransient() {
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("Error: rate_limit exceeded")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("HTTP 429 Too Many Requests")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("ThrottlingException: API limit hit")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("quota exceeded for project")));
    }

    @Test
    void overloadedMessageIsTransient() {
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("{\"type\":\"overloaded_error\"}")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("Model is at capacity")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("Service unavailable due to high demand")));
    }

    @Test
    void timeoutMessageIsTransient() {
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("Request timed out")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("deadline exceeded")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("connection reset by peer")));
    }

    @Test
    void posixErrorCodesAreTransient() {
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("getaddrinfo ECONNREFUSED 127.0.0.1:80")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("read ECONNRESET")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("ETIMEDOUT")));
    }

    @Test
    void fiveHundredErrorsAreTransient() {
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("HTTP 500 internal server error")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("HTTP 502 Bad Gateway")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("HTTP 503 Service Unavailable")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("internal_server_error")));
        assertTrue(TransientErrorClassifier.isTransient(
                new RuntimeException("gateway timeout from upstream")));
    }

    // === Negative cases ===

    @Test
    void nullIsNotTransient() {
        assertFalse(TransientErrorClassifier.isTransient(null));
    }

    @Test
    void nullPointerIsNotTransient() {
        assertFalse(TransientErrorClassifier.isTransient(
                new NullPointerException("missing required field")));
    }

    @Test
    void authFailureIsNotTransient() {
        // 401 / 403 / "invalid_api_key" — retrying won't help, the
        // code or config is wrong.
        assertFalse(TransientErrorClassifier.isTransient(
                new RuntimeException("HTTP 401 Unauthorized")));
        assertFalse(TransientErrorClassifier.isTransient(
                new RuntimeException("Invalid API key")));
        assertFalse(TransientErrorClassifier.isTransient(
                new RuntimeException("HTTP 403 Forbidden")));
    }

    @Test
    void badRequestIsNotTransient() {
        assertFalse(TransientErrorClassifier.isTransient(
                new RuntimeException("HTTP 400 Bad Request: malformed JSON")));
        assertFalse(TransientErrorClassifier.isTransient(
                new RuntimeException("invalid_request_error: model not found")));
    }

    @Test
    void plainIllegalArgumentIsNotTransient() {
        assertFalse(TransientErrorClassifier.isTransient(
                new IllegalArgumentException("value must be positive")));
    }

    // === Cause-chain walking ===

    @Test
    void transientInCauseChainIsTransient() {
        // Provider wraps the actual transient cause two levels deep —
        // the classifier must walk through.
        var deep = new SocketTimeoutException("read");
        var middle = new IOException("provider call failed", deep);
        var top = new RuntimeException("LLM call failed", middle);

        assertTrue(TransientErrorClassifier.isTransient(top),
                "must walk cause chain to find the transient class");
    }

    @Test
    void selfReferentialChainTerminates() {
        // Pathological wrapper whose getCause returns itself — must
        // not loop. Java's Throwable doesn't allow this directly
        // (initCause guards), but extending Throwable to override
        // getCause does. Just verify the classifier returns sanely.
        var loopy = new Throwable("loop") {
            @Override public Throwable getCause() { return this; }
        };
        // Returns false (no transient signal) without throwing or hanging.
        assertFalse(TransientErrorClassifier.isTransient(loopy));
    }
}
