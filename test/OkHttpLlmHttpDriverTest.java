import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.Optional;

/**
 * Branch coverage for OkHttpLlmHttpDriver.parseRetryAfter — a private static
 * helper inside a package-private class. The class isn't directly importable
 * from the default test package, so we look it up via Class.forName and
 * invoke via reflection. The streamSse / send paths need a live OkHttp
 * stack and are exercised by ChatStreamSseTest.
 */
class OkHttpLlmHttpDriverTest extends UnitTest {

    @SuppressWarnings("unchecked")
    private Optional<Long> parseRetryAfter(String value) throws Exception {
        var cls = Class.forName("llm.OkHttpLlmHttpDriver");
        var m = cls.getDeclaredMethod("parseRetryAfter", String.class);
        m.setAccessible(true);
        return (Optional<Long>) m.invoke(null, value);
    }

    @Test
    void parseRetryAfterAcceptsValidLong() throws Exception {
        var result = parseRetryAfter("42");
        assertTrue(result.isPresent());
        assertEquals(42L, result.get());
    }

    @Test
    void parseRetryAfterReturnsEmptyOnNonNumeric() throws Exception {
        // The catch path — NumberFormatException → Optional.empty().
        // RFC 7231 also allows HTTP-date format for Retry-After, which this
        // driver doesn't support; that maps to "give up parsing".
        var result = parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseRetryAfterReturnsEmptyOnEmptyString() throws Exception {
        var result = parseRetryAfter("");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseRetryAfterAcceptsLargeNumber() throws Exception {
        var result = parseRetryAfter("999999999");
        assertTrue(result.isPresent());
        assertEquals(999999999L, result.get());
    }
}
