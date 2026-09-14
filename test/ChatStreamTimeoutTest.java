import controllers.ApiChatController;
import llm.LlmResilience;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;

import java.time.Duration;

/**
 * JCLAW-1192: the web chat's SSE ceiling is derived from the first-chunk budget, so the
 * abandonment a silent stream ends with reaches the browser instead of a client disconnect
 * that landed a few seconds earlier.
 *
 * <p>The config table is shared and play1 runs test classes concurrently, so each
 * override here lives for one read and is removed in a finally block.
 */
class ChatStreamTimeoutTest extends UnitTest {

    private static final String KEY = "llm.breaker.first-chunk-seconds";

    @Test
    void theCeilingOutlivesTheFirstChunkBudgetByAMargin() {
        var ceiling = ApiChatController.chatStreamTimeout();
        assertTrue(ceiling.compareTo(LlmResilience.firstChunkBudget().plusMinutes(2)) >= 0,
                "a ceiling under the budget cuts the client before the abandonment: " + ceiling);
        assertTrue(ceiling.compareTo(Duration.ofMinutes(10)) >= 0, "never under the ten minutes it always was");
    }

    @Test
    void raisingTheBudgetRaisesTheCeiling() {
        ConfigService.set(KEY, "1200");
        try {
            assertEquals(Duration.ofSeconds(1320), ApiChatController.chatStreamTimeout());
        } finally {
            ConfigService.delete(KEY);
        }
    }

    @Test
    void aBudgetUnderTheFloorKeepsTheTenMinutes() {
        ConfigService.set(KEY, "60");
        try {
            assertEquals(Duration.ofMinutes(10), ApiChatController.chatStreamTimeout());
        } finally {
            ConfigService.delete(KEY);
        }
    }
}
