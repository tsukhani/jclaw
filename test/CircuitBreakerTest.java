import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.CircuitBreaker;
import utils.CircuitBreakers;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

class CircuitBreakerTest extends UnitTest {

    /** Monotonic source under the test's control, so a cooldown needs no {@code Thread.sleep}. */
    private static final class FakeNanos implements LongSupplier {
        private long nanos;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advanceMillis(long millis) {
            nanos += millis * 1_000_000L;
        }
    }

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

    @Test
    void theLegacyConstructorKeepsOneProbeAndNoSlowCallDetection() {
        var cb = new CircuitBreaker(20, 0.5, 5, 30_000L);
        assertEquals(1, cb.config().halfOpenPermits());
        assertFalse(cb.config().slowCallsEnabled());
    }

    // --- half-open permits ---------------------------------------------------

    @Test
    void halfOpenAdmitsExactlyThePermittedNumberOfProbes() {
        var nanos = new FakeNanos();
        var cb = openedBreaker(CircuitBreaker.Config.of(4, 0.5, 1, 1_000).withHalfOpenPermits(3), nanos);

        nanos.advanceMillis(1_000);
        assertTrue(cb.allowRequest());   // probe 1, OPEN → HALF_OPEN
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        assertTrue(cb.allowRequest());   // probe 2
        assertTrue(cb.allowRequest());   // probe 3
        assertFalse(cb.allowRequest());  // permits exhausted while all three are in flight
    }

    @Test
    void halfOpenClosesOnlyAfterEveryProbeSucceeds() {
        var nanos = new FakeNanos();
        var cb = openedBreaker(CircuitBreaker.Config.of(4, 0.5, 1, 1_000).withHalfOpenPermits(3), nanos);

        nanos.advanceMillis(1_000);
        assertTrue(cb.allowRequest());
        assertTrue(cb.allowRequest());
        assertTrue(cb.allowRequest());
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void halfOpenReopensOnTheFirstProbeFailure() {
        var nanos = new FakeNanos();
        var cb = openedBreaker(CircuitBreaker.Config.of(4, 0.5, 1, 1_000).withHalfOpenPermits(3), nanos);

        nanos.advanceMillis(1_000);
        assertTrue(cb.allowRequest());
        assertTrue(cb.allowRequest());
        cb.recordSuccess();
        cb.recordFailure();  // one failure re-opens no matter how many probes already passed
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowRequest());  // the cooldown restarted
    }

    // --- slow calls ----------------------------------------------------------

    @Test
    void slowCallsTripIndependentlyOfFailures() {
        var nanos = new FakeNanos();
        // failureRateThreshold 1.0: only an all-failure window could trip on failures.
        var cb = new CircuitBreaker(
                CircuitBreaker.Config.of(4, 1.0, 4, 60_000).withSlowCalls(2_000L, 0.5), nanos);

        cb.recordSuccess(10L);
        cb.recordSuccess(10L);
        cb.recordSuccess(5_000L);
        cb.recordSuccess(5_000L);  // 2 slow / 4 = 0.5 >= 0.5, minVolume met → OPEN

        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertEquals(0.0, cb.stats().failureRate(), 0.0);  // tripped on slowness, not failure
        assertEquals(0.5, cb.stats().slowCallRate(), 0.0);
    }

    @Test
    void aSlowCallDoesNotCountAsAFailure() {
        var nanos = new FakeNanos();
        var cb = new CircuitBreaker(
                CircuitBreaker.Config.of(4, 0.5, 4, 60_000).withSlowCalls(2_000L, 0.9), nanos);

        cb.recordSuccess(10L);
        cb.recordSuccess(5_000L);
        cb.recordSuccess(5_000L);
        cb.recordSuccess(5_000L);  // 3 slow / 4 = 0.75 — over the 0.5 failure bar, under the 0.9 slow bar

        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertEquals(0, cb.stats().failures());
    }

    @Test
    void slowCallDetectionIsOffUnlessConfigured() {
        var cb = new CircuitBreaker(CircuitBreaker.Config.of(2, 0.5, 1, 60_000));
        cb.recordSuccess(999_999L);
        cb.recordSuccess(999_999L);
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertEquals(0, cb.stats().slowCalls());
    }

    @Test
    void aSlowProbeReopensTheBreaker() {
        var nanos = new FakeNanos();
        var cb = openedBreaker(
                CircuitBreaker.Config.of(4, 0.5, 1, 1_000).withSlowCalls(2_000L, 0.5), nanos);

        nanos.advanceMillis(1_000);
        assertTrue(cb.allowRequest());
        cb.recordSuccess(5_000L);
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    // --- listener ------------------------------------------------------------

    @Test
    void listenerReceivesEveryTransition() {
        var nanos = new FakeNanos();
        var cb = new CircuitBreaker(CircuitBreaker.Config.of(1, 1.0, 1, 1_000), nanos);
        var seen = new ArrayList<CircuitBreaker.Transition>();
        cb.setTransitionListener(seen::add);

        cb.recordFailure();
        nanos.advanceMillis(1_000);
        assertTrue(cb.allowRequest());
        cb.recordSuccess();

        assertEquals(3, seen.size());
        assertEquals(CircuitBreaker.State.CLOSED, seen.get(0).from());
        assertEquals(CircuitBreaker.State.OPEN, seen.get(0).to());
        assertEquals(CircuitBreaker.Reason.FAILURE_RATE, seen.get(0).reason());
        assertEquals(1.0, seen.get(0).stats().failureRate(), 0.0);
        assertEquals(CircuitBreaker.Reason.COOLDOWN_ELAPSED, seen.get(1).reason());
        assertEquals(CircuitBreaker.State.HALF_OPEN, seen.get(1).to());
        assertEquals(CircuitBreaker.Reason.PROBE_SUCCEEDED, seen.get(2).reason());
        assertEquals(CircuitBreaker.State.CLOSED, seen.get(2).to());
    }

    @Test
    void listenerIsDispatchedWithoutHoldingTheMonitor() throws InterruptedException {
        // The listener writes to the database (EventLogger); holding the monitor across
        // that would block every allowRequest() on the protected path.
        var cb = new CircuitBreaker(CircuitBreaker.Config.of(1, 1.0, 1, 60_000));
        var observed = new AtomicReference<CircuitBreaker.State>();
        var probed = new CountDownLatch(1);
        cb.setTransitionListener(t -> {
            var probe = new Thread(() -> {
                observed.set(cb.state());
                probed.countDown();
            });
            probe.start();
            try {
                probed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        cb.recordFailure();

        assertEquals(0L, probed.getCount());
        assertEquals(CircuitBreaker.State.OPEN, observed.get());
    }

    @Test
    void aThrowingListenerDoesNotBreakTheProtectedPath() {
        var cb = new CircuitBreaker(CircuitBreaker.Config.of(1, 1.0, 1, 60_000));
        cb.setTransitionListener(t -> {
            throw new IllegalStateException("listener blew up");
        });
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    // --- ops trip / reset ----------------------------------------------------

    @Test
    void tripForcesOpenAndResetForcesClosed() {
        var nanos = new FakeNanos();
        var cb = new CircuitBreaker(CircuitBreaker.Config.of(4, 0.5, 4, 1_000), nanos);
        var seen = new ArrayList<CircuitBreaker.Transition>();
        cb.setTransitionListener(seen::add);

        cb.recordFailure();  // below minVolume — would not have tripped on its own
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());

        cb.trip();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowRequest());

        cb.reset();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowRequest());
        assertEquals(0, cb.stats().samples());  // reset clears the window

        assertEquals(CircuitBreaker.Reason.MANUAL_TRIP, seen.get(0).reason());
        assertEquals(CircuitBreaker.Reason.MANUAL_RESET, seen.get(1).reason());
    }

    @Test
    void trippingRestartsTheCooldown() {
        var nanos = new FakeNanos();
        var cb = new CircuitBreaker(CircuitBreaker.Config.of(4, 0.5, 4, 1_000), nanos);
        cb.trip();
        nanos.advanceMillis(999);
        assertFalse(cb.allowRequest());
        nanos.advanceMillis(1);
        assertTrue(cb.allowRequest());
    }

    // --- registry ------------------------------------------------------------

    @Test
    void registryIsolatesBreakersByName() {
        // play1 runs test classes concurrently in one JVM — own these names, then drop them.
        var llm = "CircuitBreakerTest:llm";
        var mcp = "CircuitBreakerTest:mcp";
        var tuning = CircuitBreaker.Config.of(1, 1.0, 1, 60_000);
        try {
            var first = CircuitBreakers.get(llm, tuning);
            assertSame(first, CircuitBreakers.get(llm, CircuitBreaker.Config.of(9, 0.1, 9, 9)));
            assertEquals(1, first.config().windowSize());  // the later config is ignored

            var other = CircuitBreakers.get(mcp, tuning);
            assertNotSame(first, other);

            first.recordFailure();
            assertEquals(CircuitBreaker.State.OPEN, first.state());
            assertEquals(CircuitBreaker.State.CLOSED, other.state());

            var snapshot = CircuitBreakers.snapshot();
            assertEquals(CircuitBreaker.State.OPEN, snapshot.get(llm).state());
            assertEquals(CircuitBreaker.State.CLOSED, snapshot.get(mcp).state());
            assertTrue(CircuitBreakers.find(llm).isPresent());
        } finally {
            assertTrue(CircuitBreakers.remove(llm));
            assertTrue(CircuitBreakers.remove(mcp));
        }
        assertTrue(CircuitBreakers.find(llm).isEmpty());
    }

    /** A breaker tuned by {@code config} and already OPEN, with the cooldown just started. */
    private static CircuitBreaker openedBreaker(CircuitBreaker.Config config, FakeNanos nanos) {
        var cb = new CircuitBreaker(config, nanos);
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        return cb;
    }
}
