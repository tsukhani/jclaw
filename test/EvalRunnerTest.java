import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.EvalCase;
import services.evals.EvalCheck;
import services.evals.EvalReport;
import services.evals.EvalRunner;
import services.evals.EvalScorer;
import services.evals.EvalSuite;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Runner and report behaviour (JCLAW-875): fan-out, per-case latency and call
 * counts, failure isolation, and the JSON round-trip that lets one run become the
 * next run's regression baseline.
 */
class EvalRunnerTest extends UnitTest {

    private static EvalCase caseSaying(String id, String expected) {
        return new EvalCase(id, "say " + expected, "the agent repeats the token",
                List.of(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of(expected))));
    }

    private static final EvalSuite SUITE = new EvalSuite("sample", "fixture",
            List.of(caseSaying("alpha", "alpha"), caseSaying("beta", "beta")));

    /** Scores {@link #SUITE} against recorded responses — the shape a replayed run has. */
    private static EvalReport runWith(Map<String, EvalScorer.Response> responses) {
        return EvalRunner.run(SUITE, testCase -> {
            var response = responses.get(testCase.id());
            if (response == null) throw new IllegalStateException("no response recorded");
            return response;
        });
    }

    // ==================== Scoring a run ====================

    @Test
    void reportKeepsSuiteOrderAndCountsCalls() {
        var report = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("beta", List.of(), 2)));

        assertEquals("sample", report.suiteId());
        assertEquals(SUITE.fingerprint(), report.fingerprint(), "the report names which suite content scored it");
        assertEquals(List.of("alpha", "beta"),
                report.results().stream().map(EvalReport.CaseResult::caseId).toList());
        assertEquals(1.0, report.passRate(), 0.0001);
        assertEquals(3, report.totalLlmCalls(), "the sweep's model-call cost is part of the report");
    }

    @Test
    void failingCaseCarriesItsFailureText() {
        var report = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("something else", List.of(), 1)));

        assertEquals(0.5, report.passRate(), 0.0001);
        var beta = report.results().get(1);
        assertFalse(beta.passed());
        assertEquals(1, beta.failures().size());
        assertTrue(beta.failures().getFirst().contains("missing \"beta\""), beta.failures().toString());
    }

    @Test
    void responderFailureCostsOneCaseNotTheRun() {
        // A missing recording (or a responder that throws) must not cost the other
        // verdicts; losing them would make a partial sweep unreadable.
        var report = runWith(Map.of("alpha", new EvalScorer.Response("alpha", List.of(), 1)));

        assertEquals(2, report.results().size());
        assertTrue(report.results().get(0).passed());
        assertFalse(report.results().get(1).passed());
        assertTrue(report.results().get(1).failures().getFirst().contains("no response recorded"),
                report.results().get(1).failures().toString());
    }

    // ==================== Errored vs failed (JCLAW-883) ====================

    @Test
    void aCaseThatNeverAnsweredIsErroredNotFailed() {
        // "the agent errored" and "the agent answered wrongly" are different
        // findings — collapsing them would send someone hunting a prompt bug
        // when the provider was simply down.
        var report = runWith(Map.of("alpha", new EvalScorer.Response("alpha", List.of(), 1)));

        var beta = report.results().get(1);
        assertTrue(beta.errored(), "a missing answer is an error, not a wrong answer");
        assertFalse(beta.passed());
        assertEquals(1, report.errored());
    }

    @Test
    void erroredCasesLeaveThePassRateOverWhatWasActuallyScored() {
        // alpha answered and passed; beta never answered. Scoring beta as a failure
        // would report 50% and understate the agent; dropping it silently would
        // report 100% and overstate confidence. One scored case, one pass.
        var report = runWith(Map.of("alpha", new EvalScorer.Response("alpha", List.of(), 1)));

        assertEquals(1, report.scored());
        assertEquals(1.0, report.passRate(), 0.0001);
        assertTrue(report.summary().contains("1 case(s) errored and were not scored"), report.summary());
        assertTrue(report.summary().contains("ERROR beta"), report.summary());
    }

    @Test
    void aCaptureThatRecordedAnErrorIsNotScoredAgainstTheAgent() {
        // The live-capture shape: the recording exists but carries a reason instead
        // of an answer. Scoring its empty output would manufacture check failures.
        var report = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", EvalScorer.Response.failed("provider timeout after 30s")));

        var beta = report.results().get(1);
        assertTrue(beta.errored());
        assertEquals(List.of("provider timeout after 30s"), beta.failures(),
                "the recorded reason survives to the report rather than being replaced by check output");
    }

    @Test
    void anErroredCaseIsNotARegression() {
        var baseline = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("beta", List.of(), 1)));
        var current = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", EvalScorer.Response.failed("provider timeout")));

        assertTrue(current.regressionsAgainst(baseline).isEmpty(),
                "beta stopped answering, which says nothing about whether its behaviour changed");
    }

    @Test
    void aCaseCannotBothPassAndError() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvalReport.CaseResult("x", true, List.of(), 1, 1, true));
    }

    @Test
    void fanOutRespectsTheConcurrencyCeiling() {
        // Six cases, ceiling of 2: the semaphore must keep the peak at 2 no matter
        // how many virtual threads the pool creates.
        var cases = IntStream.range(0, 6).mapToObj(i -> caseSaying("case-" + i, "ok")).toList();
        var suite = new EvalSuite("bounded", "fixture", cases);
        var inFlight = new AtomicInteger();
        var peak = new AtomicInteger();

        var report = EvalRunner.run(suite, testCase -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
            } finally {
                inFlight.decrementAndGet();
            }
            return new EvalScorer.Response("ok", List.of(), 1);
        }, 2);

        assertEquals(6, report.results().size());
        assertEquals(1.0, report.passRate(), 0.0001);
        assertTrue(peak.get() <= 2, "expected at most 2 cases in flight, peaked at " + peak.get());
    }

    @Test
    void casesRunConcurrently() {
        // Eight cases blocking 200 ms each: serial execution needs 1.6 s, so a run
        // under 800 ms can only come from the fan-out.
        var cases = IntStream.range(0, 8)
                .mapToObj(i -> caseSaying("case-" + i, "ok"))
                .toList();
        var suite = new EvalSuite("slow", "fixture", cases);

        var startNs = System.nanoTime();
        var report = EvalRunner.run(suite, testCase -> {
            Thread.sleep(200);
            return new EvalScorer.Response("ok", List.of(), 1);
        });
        var elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertEquals(8, report.results().size());
        assertEquals(1.0, report.passRate(), 0.0001);
        assertTrue(elapsedMs < 800, "expected concurrent execution, took " + elapsedMs + " ms");
        assertTrue(report.results().getFirst().latencyMs() >= 150,
                "per-case latency should reflect the responder, got " + report.results().getFirst().latencyMs());
    }

    @Test
    void aCaseFailingOutsideScoringCancelsTheCasesStillInFlight() {
        // score() swallows responder exceptions, so this failure mode is only
        // reachable through the shared fan-out — which is the seam EvalCapture uses.
        var blocked = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var cases = List.of(caseSaying("blocker", "ok"), caseSaying("bomb", "ok"));

        // Preemptive: a fan-out that failed to cancel would park forever rather than
        // fail, and a hung test in a 7-minute suite tells nobody anything.
        var thrown = assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                assertThrows(IllegalStateException.class, () ->
                    EvalRunner.mapCasesBounded(cases, 2, testCase -> {
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

    // ==================== Report persistence and regressions ====================

    @Test
    void reportSurvivesAJsonRoundTrip() {
        var report = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("nope", List.of(), 4)));

        var restored = EvalReport.fromJson(report.toJson());

        assertEquals(report.suiteId(), restored.suiteId());
        assertEquals(report.fingerprint(), restored.fingerprint());
        assertEquals(report.passRate(), restored.passRate(), 0.0001);
        assertEquals(report.totalLlmCalls(), restored.totalLlmCalls());
        assertEquals(report.results().get(1).failures(), restored.results().get(1).failures());
    }

    @Test
    void regressionsAreOnlyCasesThatUsedToPass() {
        var baseline = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("nope", List.of(), 1)));
        var current = runWith(Map.of(
                "alpha", new EvalScorer.Response("nope", List.of(), 1),
                "beta", new EvalScorer.Response("nope", List.of(), 1)));

        assertEquals(List.of("alpha"), current.regressionsAgainst(baseline),
                "beta was already failing, so it is a known gap rather than a regression");
    }

    @Test
    void newCasesAreNotRegressions() {
        var baseline = new EvalReport("sample", SUITE.fingerprint(),
                List.of(EvalReport.CaseResult.scored("alpha", true, List.of(), 1, 1)));
        var current = runWith(Map.of("alpha", new EvalScorer.Response("alpha", List.of(), 1)));

        assertTrue(current.regressionsAgainst(baseline).isEmpty(),
                "beta has no history in the baseline, so its failure is not a regression");
    }

    @Test
    void summaryNamesEveryFailedCheck() {
        var report = runWith(Map.of(
                "alpha", new EvalScorer.Response("alpha", List.of(), 1),
                "beta", new EvalScorer.Response("nope", List.of(), 1)));

        var summary = report.summary();

        assertTrue(summary.contains("sample@" + SUITE.fingerprint()), summary);
        assertTrue(summary.contains("FAIL"), summary);
        assertTrue(summary.contains("beta"), summary);
        assertTrue(summary.contains("missing \"beta\""), summary);
        assertTrue(summary.contains("1/2 passed (50%)"), summary);
        assertFalse(summary.contains("errored"), "no case errored, so the totals line stays clean: " + summary);
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
