import agents.AgentRunner;
import org.junit.jupiter.api.*;
import play.test.UnitTest;

/**
 * Unit tests for {@link AgentRunner#sanitizeToolCallId(String)} (JCLAW-119).
 *
 * <p>The sanitizer exists to keep cross-provider /model switches from
 * breaking conversations whose history contains provider-specific tool_call
 * IDs. Real-world offender: Gemini / some open-weight model servers emit
 * IDs like {@code "functions.web_search:7"} — Ollama Cloud's kimi-k2.6
 * endpoint rejects those on replay.
 */
class AgentRunnerToolCallIdTest extends UnitTest {

    @Test
    void sanitizeReplacesGeminiStyleFunctionIndexedIds() {
        // Production offender from the 2026-04-22 incident.
        assertEquals("functions_web_search_7",
                AgentRunner.sanitizeToolCallId("functions.web_search:7"));
    }

    @Test
    void sanitizeReplacesColonsAndDots() {
        assertEquals("functions_filesystem_11",
                AgentRunner.sanitizeToolCallId("functions.filesystem:11"));
    }

    @Test
    void sanitizePreservesAlreadySafeIds() {
        assertEquals("call_3e02a31cec3241a8b0ab5f0f",
                AgentRunner.sanitizeToolCallId("call_3e02a31cec3241a8b0ab5f0f"));
        assertEquals("tool_browser_SL4V1uU2ktF5IwFBNDZI",
                AgentRunner.sanitizeToolCallId("tool_browser_SL4V1uU2ktF5IwFBNDZI"));
    }

    @Test
    void sanitizePreservesHyphensAndUnderscores() {
        // Hyphens are in the safe set — must not get mangled.
        assertEquals("call-abc_123",
                AgentRunner.sanitizeToolCallId("call-abc_123"));
    }

    @Test
    void sanitizeReplacesWhitespaceAndPunctuation() {
        assertEquals("ab_cd_ef_", AgentRunner.sanitizeToolCallId("ab cd!ef@"));
    }

    @Test
    void sanitizeHandlesNull() {
        assertNull(AgentRunner.sanitizeToolCallId(null));
    }

    @Test
    void sanitizeHandlesEmptyString() {
        // Empty is already "safe" (nothing to replace). Return as-is.
        assertEquals("", AgentRunner.sanitizeToolCallId(""));
    }

    @Test
    void sanitizeIsDeterministicSoPairingSurvives() {
        // The key property: the same input always produces the same output,
        // so normalizing both the assistant's tool_calls[].id and the
        // tool-row's tool_call_id independently still results in a matching
        // pair.
        var original = "functions.web_search:42";
        assertEquals(
                AgentRunner.sanitizeToolCallId(original),
                AgentRunner.sanitizeToolCallId(original));
    }
}
