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
import java.util.stream.IntStream;

/**
 * The bounded fan-out capture puts a model behind (JCLAW-883): its ceiling, its
 * overlap, suite order, and cancelling the survivors when one case fails.
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
    void aCaseFailingOutsideScoringCancelsTheCasesStillInFlight() {
        var blocked = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var cases = List.of(cases(1).getFirst(), new EvalCase("bomb", "say ok", "boom",
                List.of(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("ok")))));

        // Preemptive: a fan-out that failed to cancel would park forever rather than
        // fail, and a hung test in a 7-minute suite tells nobody anything.
        var thrown = assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                assertThrows(IllegalStateException.class, () ->
                    EvalFanOut.mapCasesBounded(cases, 2, testCase -> {
                        if ("bomb".equals(testCase.id())) {
                            // Let the blocker reach its park first, so there is something
                            // running for the failure to cancel.
                            await(blocked);
                            throw new IllegalStateException("boom");
                        }
                        blocked.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException _) {
                            interrupted.set(true);
                            Thread.currentThread().interrupt();
                        }
                        return "unreachable";
                    })));

        assertEquals("boom", thrown.getCause().getMessage());
        assertTrue(interrupted.get(), "the blocked case must be cancelled, not left running");
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
