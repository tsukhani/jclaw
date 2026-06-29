import org.junit.jupiter.api.*;
import play.test.*;
import utils.CircuitBreaker;

class CircuitBreakerTest extends UnitTest {

    @Test
    void startsClosedAndAllows() {
        var cb = new CircuitBreaker(10, 0.5, 4, 1000);
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
    }

    @Test
    void staysClosedBelowMinVolume() {
        var cb = new CircuitBreaker(10, 0.5, 4, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();  // 3 failures, minVolume 4 → rate not yet evaluated
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void tripsOpenWhenFailureRateExceedsThreshold() {
        var cb = new CircuitBreaker(10, 0.5, 4, 60_000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordFailure();  // 3 fail / 4 total = 0.75 >= 0.5, minVolume met → OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowRequest());  // cooldown not elapsed
    }

    @Test
    void halfOpenProbeSuccessCloses() throws InterruptedException {
        var cb = new CircuitBreaker(2, 0.5, 1, 1);  // 1ms cooldown
        cb.recordFailure();   // 1/1 = 1.0 >= 0.5, minVolume 1 → OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        Thread.sleep(5);      // let the cooldown elapse
        assertTrue(cb.allowRequest());  // → HALF_OPEN, admits one probe
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        cb.recordSuccess();   // probe ok → CLOSED, history cleared
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
    }

    @Test
    void halfOpenProbeFailureReopens() throws InterruptedException {
        var cb = new CircuitBreaker(2, 0.5, 1, 1);
        cb.recordFailure();
        Thread.sleep(5);
        assertTrue(cb.allowRequest());  // HALF_OPEN
        cb.recordFailure();             // probe failed → OPEN again
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void successesKeepRateBelowThreshold() {
        var cb = new CircuitBreaker(4, 0.5, 4, 60_000);
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordSuccess();  // window [F,S,S,S] = 1/4 = 0.25 < 0.5 → stays CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }
}
