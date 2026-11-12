import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.EvalCase;
import services.evals.EvalCheck;
import services.evals.EvalFanOut;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * The bounded fan-out capture puts a model behind (JCLAW-883): its ceiling, its
 * overlap, suite order, and what one failing case does to the others (JF-332).
 */
class EvalFanOutTest extends UnitTest {

    private static List<EvalCase> cases(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> new EvalCase("case-" + i, "say ok", "the agent repeats the token",
                        List.of(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("ok")))))
                .toList();
    }

    /** Peak cases in flight when each one holds its slot for {@code holdMs}. */
    private static int peakInFlight(int caseCount, int maxConcurrency, long holdMs) {
        var inFlight = new AtomicInteger();
        var peak = new AtomicInteger();
        var ids = EvalFanOut.mapCasesBounded(cases(caseCount), maxConcurrency, testCase -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(holdMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                inFlight.decrementAndGet();
            }
            return testCase.id();
        });
        assertEquals(cases(caseCount).stream().map(EvalCase::id).toList(), ids,
                "results keep suite order whatever order the cases finish in");
        return peak.get();
    }

    @Test
    void fanOutRespectsTheConcurrencyCeiling() {
        // Six cases, ceiling of 2: the semaphore must keep the peak at 2 no matter
        // how many virtual threads the pool creates.
        var peak = peakInFlight(6, 2, 50);

        assertTrue(peak <= 2, "expected at most 2 cases in flight, peaked at " + peak);
    }

    @Test
    void aCeilingOfOneRunsTheCasesOneAtATime() {
        assertEquals(1, peakInFlight(4, 1, 20));
    }

    @Test
    void aCeilingBelowOneIsTreatedAsOne() {
        assertEquals(1, peakInFlight(3, 0, 20));
    }

    @Test
    void casesRunConcurrently() {
        // Overlap, not wall time: a loaded host delays every wakeup, while only a serial run peaks at 1.
        var peak = peakInFlight(8, EvalFanOut.DEFAULT_CONCURRENCY, 200);

        assertTrue(peak > 1, "expected cases in flight together, peaked at " + peak);
        assertTrue(peak <= EvalFanOut.DEFAULT_CONCURRENCY, "peaked above the default ceiling at " + peak);
    }

    @Test
    void everyForkedCaseSeesTheCallersTraceContext() {
        // Capture's model calls must stay under the span that started the sweep.
        var key = ContextKey.<String>named("eval-fan-out-test");
        List<String> seen;
        try (var _ = Context.current().with(key, "sweep-span").makeCurrent()) {
            seen = EvalFanOut.mapCasesBounded(cases(3), 2, testCase -> Context.current().get(key));
        }

        assertEquals(List.of("sweep-span", "sweep-span", "sweep-span"), seen);
    }

    @Test
    void anEmptyCaseListMapsToNothing() {
        assertEquals(List.of(), EvalFanOut.mapCasesBounded(List.of(), 2, EvalCase::id));
    }

    @Test
    void aCaseWaitingForASlotWhenAnotherFailsNeverStarts() {
        // Preemptive: a fan-out that never frees its slot would park forever rather than
        // fail, and a hung test tells nobody anything.
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            // The freed slot races the failure's record, so one pass proves little.
            for (int run = 0; run < 100; run++) {
                var calls = new AtomicInteger();
                var boom = new IllegalStateException("boom");
                var thrown = assertThrows(IllegalStateException.class, () ->
                        EvalFanOut.mapCasesBounded(cases(3), 1, testCase -> {
                            // Whichever case the scheduler lets into the one slot first throws.
                            if (calls.incrementAndGet() == 1) throw boom;
                            return testCase.id();
                        }));

                assertSame(boom, thrown.getCause(), "run " + run + ": the cause must be the case that failed");
                assertEquals(1, calls.get(), "run " + run + ": the cases queued behind the failure must never start");
            }
        });
    }

    @Test
    void aCaseAlreadyRunningWhenAnotherFailsFinishesUninterrupted() {
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var bombThread = new AtomicReference<Thread>();
        var bombing = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var finished = new AtomicBoolean();
        var cases = List.of(cases(1).getFirst(), new EvalCase("bomb", "say ok", "boom",
                List.of(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("ok")))));
        // The bomb's thread ends only once its failure has reached the scope, so joining it
        // releases the running case strictly after the bomb has thrown.
        var releaser = Thread.ofPlatform().daemon().start(() -> {
            await(bombing);
            try {
                bombThread.get().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                release.countDown();
            }
        });

        var thrown = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try {
                return assertThrows(IllegalStateException.class, () ->
                    EvalFanOut.mapCasesBounded(cases, 2, testCase -> {
                        if ("bomb".equals(testCase.id())) {
                            await(running);
                            bombThread.set(Thread.currentThread());
                            bombing.countDown();
                            throw new IllegalStateException("boom");
                        }
                        running.countDown();
                        try {
                            release.await();
                            // A release that wins the race returns with the interrupt still pending.
                            interrupted.set(Thread.currentThread().isInterrupted());
                        } catch (InterruptedException _) {
                            interrupted.set(true);
                            Thread.currentThread().interrupt();
                        }
                        finished.set(true);
                        return testCase.id();
                    }));
            } finally {
                // A red run must not leave the running case parked, nor wait on a stuck releaser.
                release.countDown();
                releaser.join(Duration.ofSeconds(5));
            }
        });

        assertEquals("boom", thrown.getCause().getMessage());
        assertFalse(interrupted.get(), "a case already running must not be interrupted when another fails");
        assertTrue(finished.get(), "the fan-out must not return before the running case ends");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
