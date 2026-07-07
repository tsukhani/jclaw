import channels.TelegramWebhookRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * Unit tests for {@link TelegramWebhookRateLimiter} (M1 webhook ingress
 * hardening). Verifies the fixed-window counter allows under the limit, blocks
 * over it within the window, and recovers once the window elapses.
 * {@code resetForTest()} clears static state between cases.
 */
class TelegramWebhookRateLimiterTest extends UnitTest {

    @BeforeEach
    void reset() {
        TelegramWebhookRateLimiter.resetForTest();
    }

    @Test
    void allowsRequestsUpToTheLimit() {
        long binding = 1L;
        for (int i = 1; i <= 5; i++) {
            assertTrue(TelegramWebhookRateLimiter.allow(binding, 5, 60),
                    "request " + i + " of 5 must be allowed within the window");
        }
    }

    @Test
    void blocksOnceTheLimitIsExceededWithinTheWindow() {
        long binding = 2L;
        for (int i = 0; i < 3; i++) {
            assertTrue(TelegramWebhookRateLimiter.allow(binding, 3, 60));
        }
        assertFalse(TelegramWebhookRateLimiter.allow(binding, 3, 60),
                "the 4th request in a max=3 window must be rejected");
        assertFalse(TelegramWebhookRateLimiter.allow(binding, 3, 60),
                "further requests within the same window stay rejected");
    }

    @Test
    void distinctBindingsHaveIndependentCounters() {
        assertTrue(TelegramWebhookRateLimiter.allow(10L, 1, 60));
        assertFalse(TelegramWebhookRateLimiter.allow(10L, 1, 60),
                "binding 10 is over its max of 1");
        assertTrue(TelegramWebhookRateLimiter.allow(11L, 1, 60),
                "binding 11 has its own independent window");
    }

    @Test
    void windowRolloverAllowsAgain() throws InterruptedException {
        long binding = 3L;
        // window of 1 second, max 1: first allowed, second blocked.
        assertTrue(TelegramWebhookRateLimiter.allow(binding, 1, 1));
        assertFalse(TelegramWebhookRateLimiter.allow(binding, 1, 1),
                "second request in the same 1s window is blocked");

        Thread.sleep(1100);

        assertTrue(TelegramWebhookRateLimiter.allow(binding, 1, 1),
                "after the 1s window elapses the counter rolls over and allows again");
    }
}
