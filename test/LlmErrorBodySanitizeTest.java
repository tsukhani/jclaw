import llm.LlmProvider;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Method;

/**
 * JCLAW-730 regression: upstream provider error bodies must be scrubbed of the
 * API key and length-capped before they enter exception messages / event logs.
 * Covers both surfacing sites — {@code LlmProvider.sanitizeErrorBody} (the
 * single-shot 4xx/5xx throw path) and {@code OkHttpLlmHttpDriver.sanitizeErrorBody}
 * (the streaming non-200 failure path). Both helpers are private, so we reach
 * them by reflection, matching {@code OkHttpLlmHttpDriverTest}/{@code LlmProviderTest}.
 */
class LlmErrorBodySanitizeTest extends UnitTest {

    private static final int MAX = 500;

    // ── LlmProvider.sanitizeErrorBody(body, apiKey) ──

    @Test
    void providerScrubsTheApiKey() throws Exception {
        var key = "sk-super-secret-key";
        var out = providerSanitize("{\"error\":\"bad request for " + key + "\"}", key);
        assertFalse(out.contains(key), "the API key must not survive into the message: " + out);
        assertTrue(out.contains("<redacted>"), "the key should be replaced with a marker: " + out);
    }

    @Test
    void providerTruncatesOversizeBodies() throws Exception {
        var body = "x".repeat(5000);
        var out = providerSanitize(body, "sk-unused");
        assertTrue(out.length() <= MAX + 1, "body must be length-capped: " + out.length());
        assertTrue(out.endsWith("…"), "a truncated body carries the ellipsis marker");
    }

    @Test
    void providerToleratesNullBodyAndKey() throws Exception {
        assertEquals("", providerSanitize(null, "sk-x"));
        assertEquals("plain body", providerSanitize("plain body", null));
    }

    // ── OkHttpLlmHttpDriver.sanitizeErrorBody(body, authHeader) ──

    @Test
    void driverScrubsBearerKeyFromBody() throws Exception {
        var key = "sk-stream-secret";
        var out = driverSanitize("upstream said " + key + " is invalid", "Bearer " + key);
        assertFalse(out.contains(key), "the bearer key must not survive: " + out);
        assertTrue(out.contains("<redacted>"));
    }

    @Test
    void driverTruncatesOversizeBodies() throws Exception {
        var out = driverSanitize("y".repeat(5000), "Bearer sk-x");
        assertTrue(out.length() <= MAX + 1, "body must be length-capped: " + out.length());
        assertTrue(out.endsWith("…"));
    }

    @Test
    void driverToleratesNullAuthHeader() throws Exception {
        assertEquals("body only", driverSanitize("body only", null));
    }

    private static String providerSanitize(String body, String secret) throws Exception {
        Method m = LlmProvider.class.getDeclaredMethod("sanitizeErrorBody", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, body, secret);
    }

    private static String driverSanitize(String body, String authHeader) throws Exception {
        var cls = Class.forName("llm.OkHttpLlmHttpDriver");
        Method m = cls.getDeclaredMethod("sanitizeErrorBody", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, body, authHeader);
    }
}
