import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import controllers.LoginRateLimiter;

/**
 * JCLAW-741: unit coverage for the failed-login throttle. Runs safely under the
 * concurrent unit+functional lanes by keying every case on a distinctive,
 * test-private IP string and never touching global state it does not own — no
 * clear(), no assertion on another key's count (mirrors SubagentRegistryStateTest).
 */
class LoginRateLimiterTest extends UnitTest {

    @Test
    void allowsAnUnknownSource() {
        assertTrue(LoginRateLimiter.allow("test-unknown-1", 3, 60));
    }

    @Test
    void locksOutAfterMaxFailures() {
        String ip = "test-lockout-1";
        for (int i = 0; i < 3; i++) {
            assertTrue(LoginRateLimiter.allow(ip, 3, 60), "attempt " + i + " should be allowed");
            LoginRateLimiter.recordFailure(ip, 60);
        }
        assertFalse(LoginRateLimiter.allow(ip, 3, 60), "should be locked out after 3 failures");
    }

    @Test
    void successClearsTheCounter() {
        String ip = "test-success-1";
        for (int i = 0; i < 5; i++) LoginRateLimiter.recordFailure(ip, 60);
        assertFalse(LoginRateLimiter.allow(ip, 3, 60));
        LoginRateLimiter.recordSuccess(ip);
        assertTrue(LoginRateLimiter.allow(ip, 3, 60), "a successful login must reset the source");
    }

    @Test
    void staleWindowNeverLocksOut() {
        // windowSeconds=0 → every check sees an already-elapsed window, so
        // failures roll over instead of accumulating. Exercises the rollover
        // branch deterministically without sleeping.
        String ip = "test-stale-1";
        for (int i = 0; i < 10; i++) LoginRateLimiter.recordFailure(ip, 0);
        assertTrue(LoginRateLimiter.allow(ip, 3, 0));
    }

    @Test
    void sourcesAreIndependent() {
        String a = "test-indep-a";
        String b = "test-indep-b";
        for (int i = 0; i < 3; i++) LoginRateLimiter.recordFailure(a, 60);
        assertFalse(LoginRateLimiter.allow(a, 3, 60));
        assertTrue(LoginRateLimiter.allow(b, 3, 60), "one locked-out source must not affect another");
    }
}
