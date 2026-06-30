import memory.MemorySafety;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-535: deterministic secret detection for the memory capture path. High-
 * confidence secret shapes are dropped; ordinary facts (including long order
 * numbers and dates) pass through.
 */
class MemorySafetyTest extends UnitTest {

    @Test
    void detectsApiKeyPrefixes() {
        assertTrue(MemorySafety.looksLikeSecret("my key is sk-abcdef0123456789abcdef0123"));
        assertTrue(MemorySafety.looksLikeSecret("AKIAIOSFODNN7EXAMPLE is the access key"));
        assertTrue(MemorySafety.looksLikeSecret("token ghp_0123456789abcdefghij0123456789abcd"));
        assertTrue(MemorySafety.looksLikeSecret("slack xoxb-0123456789-abcdefghij"));
    }

    @Test
    void detectsJwtAndPem() {
        assertTrue(MemorySafety.looksLikeSecret("auth eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM.SflKxwRJSMeKKF2"));
        assertTrue(MemorySafety.looksLikeSecret("-----BEGIN RSA PRIVATE KEY-----\nMIIEvAIBADANBg"));
    }

    @Test
    void detectsAssignmentAndCard() {
        assertTrue(MemorySafety.looksLikeSecret("password = hunter2xyz"));
        assertTrue(MemorySafety.looksLikeSecret("api_key: s3cretValue123"));
        assertTrue(MemorySafety.looksLikeSecret("card 4111 1111 1111 1111"));   // Luhn-valid Visa test number
    }

    @Test
    void passesOrdinaryFacts() {
        assertFalse(MemorySafety.looksLikeSecret("The user lives in Porto, Portugal"));
        assertFalse(MemorySafety.looksLikeSecret("The user's favorite database is PostgreSQL"));
        assertFalse(MemorySafety.looksLikeSecret("Order number 12345 shipped on 2026-06-30"));
        assertFalse(MemorySafety.looksLikeSecret("The user prefers tabs over spaces"));
        assertFalse(MemorySafety.looksLikeSecret(""));
        assertFalse(MemorySafety.looksLikeSecret(null));
    }
}
