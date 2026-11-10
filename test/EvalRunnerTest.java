import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.EvalCase;
import services.evals.EvalCheck;
import services.evals.EvalReport;
import services.evals.EvalRunner;
import services.evals.EvalScorer;
import services.evals.EvalSuite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Runner and report behaviour (JCLAW-875): suite order, per-case latency and call
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
    void scoringReplaysTheCasesOneAtATimeInSuiteOrder() {
        // Recorded answers are pure computation, so scoring forks nothing (JF-157).
        var cases = IntStream.range(0, 5).mapToObj(i -> caseSaying("case-" + i, "ok")).toList();
        var suite = new EvalSuite("serial", "fixture", cases);
        var inFlight = new AtomicInteger();
        var peak = new AtomicInteger();
        var order = new ArrayList<String>();
        var caller = Thread.currentThread();

        var report = EvalRunner.run(suite, testCase -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                assertSame(caller, Thread.currentThread(), "a case ran on a forked thread");
                order.add(testCase.id());
                return new EvalScorer.Response("ok", List.of(), 1);
            } finally {
                inFlight.decrementAndGet();
            }
        });

        assertEquals(List.of("case-0", "case-1", "case-2", "case-3", "case-4"), order);
        assertEquals(order, report.results().stream().map(EvalReport.CaseResult::caseId).toList());
        assertEquals(1, peak.get());
        assertEquals(5, report.passed());
    }

    @Test
    void perCaseLatencyReflectsTheResponder() {
        var report = EvalRunner.run(new EvalSuite("slow", "fixture", List.of(caseSaying("slow", "ok"))),
                testCase -> {
                    Thread.sleep(200);
                    return new EvalScorer.Response("ok", List.of(), 1);
                });

        assertTrue(report.results().getFirst().latencyMs() >= 150,
                "per-case latency should reflect the responder, got " + report.results().getFirst().latencyMs());
    }

    @Test
    void anEmptySuiteScoresNoCases() {
        var report = EvalRunner.run(new EvalSuite("empty", "fixture", List.of()), testCase -> {
            throw new AssertionError("no case to answer");
        });

        assertEquals(List.of(), report.results());
        assertEquals(0.0, report.passRate(), 0.0001);
    }

    // ==================== The standalone guarantee jclaw.sh relies on ====================

    /** Every class {@code ./jclaw.sh evals} loads when it validates or scores offline. */
    public static final String OFFLINE_CLASSES =
            "services\\.evals\\.(EvalRunner|EvalDatasetLoader|EvalSuite|EvalCase|EvalCheck|EvalScorer|EvalReport|EvalJson)(\\$.*)?";

    @Test
    void theOfflineScorerStandsAlone() {
        // jclaw.sh runs EvalRunner as a plain java -cp over the compiled classes and the
        // framework's lib/ (for Gson) — nothing from the app's dependency tree. A reference
        // outside that set fails as a NoClassDefFoundError in the operator's command, as
        // TaskScope's OpenTelemetry wrap did for seven weeks (JF-157).
        ArchRule rule = classes().that().haveNameMatching(OFFLINE_CLASSES)
                .should().onlyDependOnClassesThat(
                        resideInAnyPackage("java..", "com.google.gson..", "org.jspecify..")
                                .or(DescribedPredicate.describe("are offline eval classes",
                                        (JavaClass c) -> c.getBaseComponentType().getName().matches(OFFLINE_CLASSES))))
                .because("jclaw.sh runs the offline eval CLI on the framework's lib/ alone (JF-157): keep these "
                        + "classes on the JDK, Gson and JSpecify, and move anything else they need into one of them "
                        + "or out to a caller jclaw.sh does not run");
        rule.check(ArchitectureTest.APP_CLASSES);
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
}
