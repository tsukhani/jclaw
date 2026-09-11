import mcp.CallToolResult;
import mcp.McpConnectionManager;
import mcp.McpException;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.CircuitBreaker;
import utils.CircuitBreakers;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * JCLAW-1168: the per-server tool-call breaker in {@link McpConnectionManager}.
 *
 * <p>Drives {@link McpConnectionManager#guardedCall} directly rather than through a
 * live fixture server: the production tuning waits 30 s before probing, which no test
 * can afford, and the interesting outcomes — a request that times out, a tool that
 * reports its own error — are the ones a fixture server makes hardest to stage.
 */
class McpCircuitBreakerTest extends UnitTest {

    /** Monotonic source under the test's control, so the cooldown needs no sleep. */
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

    private static final CallToolResult OK = new CallToolResult("ok", false);

    @Test
    void timedOutCallsOpenTheBreakerAndLaterCallsNeverReachTheServer() {
        var breaker = freshBreaker(new FakeNanos());
        driveFailures(breaker, 2, () -> {
            throw new McpException("Request tools/call timed out after PT30S");
        });
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(), "two timeouts is a minute of a turn");
        driveFailures(breaker, 1, () -> {
            throw new McpException("Request tools/call timed out after PT30S");
        });
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(),
                "the third opens it: a turn rarely makes ten calls to one server, so ten was never reached");

        var invocations = new AtomicInteger();
        assertThrows(McpException.class, () ->
                McpConnectionManager.guardedCall(breaker, "hung", () -> {
                    invocations.incrementAndGet();
                    return OK;
                }));
        assertEquals(0, invocations.get());
    }

    @Test
    void aTransportIoFailureIsServerEvidenceToo() {
        var breaker = freshBreaker(new FakeNanos());
        assertThrows(IOException.class, () -> McpConnectionManager.guardedCall(
                breaker, "hung", () -> { throw new IOException("broken pipe"); }));
        driveFailures(breaker, 9, () -> { throw new IOException("broken pipe"); });
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }

    @Test
    void aToolThatReportsItsOwnErrorIsNotAServerFailure() throws Exception {
        var breaker = freshBreaker(new FakeNanos());
        var errored = new CallToolResult("file not found", true);
        for (int i = 0; i < 20; i++) {
            assertSame(errored, McpConnectionManager.guardedCall(breaker, "healthy", () -> errored));
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void theBreakerClosesOnceBothHalfOpenProbesSucceed() throws Exception {
        var nanos = new FakeNanos();
        var breaker = freshBreaker(nanos);
        driveFailures(breaker, 10, () -> { throw new McpException("hung"); });
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        nanos.advanceMillis(29_999);
        assertThrows(McpException.class,
                () -> McpConnectionManager.guardedCall(breaker, "hung", () -> OK));

        nanos.advanceMillis(1);
        McpConnectionManager.guardedCall(breaker, "hung", () -> OK);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());  // two probes are configured
        McpConnectionManager.guardedCall(breaker, "hung", () -> OK);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void anUnclassifiedProbeFailureHandsItsPermitBack() throws Exception {
        var nanos = new FakeNanos();
        var breaker = freshBreaker(nanos);
        driveFailures(breaker, 3, () -> { throw new McpException("hung"); });
        nanos.advanceMillis(30_000);

        // JCLAW-1185: a reply that arrived but did not parse is neither of the two exceptions
        // the gate classifies. Before the fix its permit stayed out, and with two permits the
        // breaker could gather at most one success — never the two it needs to close.
        assertThrows(IllegalStateException.class, () -> McpConnectionManager.guardedCall(
                breaker, "hung", () -> { throw new IllegalStateException("unparseable result"); }));
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());

        McpConnectionManager.guardedCall(breaker, "hung", () -> OK);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(),
                "the server answered the first probe, so it counted as one; the second closes it");
    }

    @Test
    void aManualTripIsReportedAsTheOperatorsDecision() {
        var breaker = freshBreaker(new FakeNanos());
        breaker.trip();

        var isolated = assertThrows(McpException.ManuallyIsolated.class,
                () -> McpConnectionManager.guardedCall(breaker, "github", () -> OK));
        assertTrue(isolated.getMessage().contains("isolated by the operator"), isolated.getMessage());

        // The distinction lasts as long as the trip does: opened on evidence, it says so.
        breaker.reset();
        driveFailures(breaker, 3, () -> { throw new McpException("hung"); });
        var tripped = assertThrows(McpException.class,
                () -> McpConnectionManager.guardedCall(breaker, "github", () -> OK));
        assertFalse(tripped instanceof McpException.ManuallyIsolated);
        assertTrue(tripped.getMessage().contains("failing fast"), tripped.getMessage());
    }

    @Test
    void aReconnectClearsAnAutonomousOpenButNotAnOperatorsTrip() {
        var breaker = freshBreaker(new FakeNanos());
        driveFailures(breaker, 3, () -> { throw new McpException("hung"); });
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        McpConnectionManager.clearUnlessIsolated(breaker);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(),
                "the dead client's failures are no evidence about the fresh one");

        breaker.trip();
        McpConnectionManager.clearUnlessIsolated(breaker);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(), "the operator's trip is the operator's to lift");
        assertEquals(CircuitBreaker.Reason.MANUAL_TRIP, breaker.stats().reason());
    }

    @Test
    void oneServersBreakerLeavesEveryOtherServerAlone() throws Exception {
        // play1 runs test classes concurrently in one JVM — own these names, then drop them.
        var broken = "McpCircuitBreakerTest-broken";
        var healthy = "McpCircuitBreakerTest-healthy";
        try {
            var brokenBreaker = McpConnectionManager.breaker(broken);
            var healthyBreaker = McpConnectionManager.breaker(healthy);
            assertNotSame(brokenBreaker, healthyBreaker);
            assertSame(brokenBreaker, McpConnectionManager.breaker(broken));

            brokenBreaker.trip();
            assertEquals(CircuitBreaker.State.OPEN, brokenBreaker.state());
            assertEquals(CircuitBreaker.State.CLOSED, healthyBreaker.state());

            assertThrows(McpException.class,
                    () -> McpConnectionManager.guardedCall(brokenBreaker, broken, () -> OK));
            assertSame(OK, McpConnectionManager.guardedCall(healthyBreaker, healthy, () -> OK));
        } finally {
            CircuitBreakers.remove("mcp:" + broken);
            CircuitBreakers.remove("mcp:" + healthy);
        }
    }

    @Test
    void theBreakerIsRegisteredUnderTheServerNameWithTheTicketsTuning() {
        var name = "McpCircuitBreakerTest-lifecycle";
        try {
            var config = McpConnectionManager.breaker(name).config();
            assertTrue(CircuitBreakers.find("mcp:" + name).isPresent());
            assertEquals(10, config.windowSize());
            assertEquals(3, config.minVolume());
            assertEquals(30_000L, config.cooldownMillis());
            assertEquals(2, config.halfOpenPermits());
            assertEquals(3, config.consecutiveFailures());
        } finally {
            // What McpConnectionManager.stop() does when a server is deleted or reconfigured.
            assertTrue(CircuitBreakers.remove("mcp:" + name));
        }
        assertTrue(CircuitBreakers.find("mcp:" + name).isEmpty());
    }

    private static CircuitBreaker freshBreaker(FakeNanos nanos) {
        return new CircuitBreaker(McpConnectionManager.BREAKER_CONFIG, nanos);
    }

    private static void driveFailures(CircuitBreaker breaker, int times, McpConnectionManager.McpCall failing) {
        for (int i = 0; i < times; i++) {
            assertThrows(Exception.class, () -> McpConnectionManager.guardedCall(breaker, "hung", failing));
        }
    }
}
