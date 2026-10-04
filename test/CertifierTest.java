import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.Certifier;
import services.grapheval.Certifier.Adjudication;
import services.grapheval.Certifier.ClassStep;
import services.grapheval.Certifier.Walk;
import services.grapheval.GraphEvalScorer;
import services.grapheval.GraphEvalScorer.ClassTally;
import services.grapheval.GraphEvalScorer.Point;
import services.grapheval.GraphEvalScorer.WrongRecord;

import java.util.List;

/** JCLAW-1356: the Clopper-Pearson bound, the threshold walk and the certification preconditions, per the I/O matrix. */
class CertifierTest extends UnitTest {

    private static final int[][] BOUNDARY = {{0, 59}, {1, 93}, {2, 124}, {3, 153}, {5, 208}};

    private static Point point(double t, int written, int wrong, double recall) {
        return point(t, written, wrong, 0, recall, 0, TRAPS, 0);
    }

    /** A base-only point with {@code trapGold} trap relations of which {@code violations} were written wrongly. */
    private static Point point(double t, int written, int wrong, int noise, double recall, int failures,
                               int trapGold, int violations) {
        var share = written - noise == 0 ? null : (double) wrong / (written - noise);
        return new Point(t, written, written - noise - wrong, wrong, wrong, 0, 0, 0, noise, 100, recall, share,
                failures, 0, 0, 0, 0, ClassTally.EMPTY, ClassTally.EMPTY, ClassTally.EMPTY, ClassTally.EMPTY,
                written - noise, wrong, trapGold, violations, 0, 0);
    }

    /** The smallest trap set that can pass with no violation: 0.95^59 is below 0.05. */
    private static final int TRAPS = 59;

    /** A grid passing every threshold at or above {@code lowest} and failing on the bound below it. */
    private static List<Point> passingDownTo(double lowest) {
        return GraphEvalScorer.THRESHOLDS.stream()
                .map(t -> t >= lowest - 1e-9 ? point(t, 200, 0, 0.9) : point(t, 200, 40, 0.9)).toList();
    }

    private static Walk walkDownTo(double lowest) {
        return Certifier.walk(passingDownTo(lowest), Certifier.DEFAULT_RECALL_FLOOR);
    }

    private static final WrongRecord RECORD = new WrongRecord("c007", "term:Meridian:Project", GraphEvalScorer.MATCH);

    @Test
    void theBoundaryRowsPass() {
        for (var row : BOUNDARY) {
            assertTrue(Certifier.boundPasses(row[0], row[1]), row[0] + " of " + row[1]);
            assertTrue(Certifier.upperBound(row[0], row[1]) <= Certifier.LIMIT + 1e-9, row[0] + " of " + row[1]);
        }
    }

    @Test
    void oneFewerWrittenFailsEachBoundaryRow() {
        for (var row : BOUNDARY) {
            assertFalse(Certifier.boundPasses(row[0], row[1] - 1), row[0] + " of " + (row[1] - 1));
            assertTrue(Certifier.upperBound(row[0], row[1] - 1) > Certifier.LIMIT, row[0] + " of " + (row[1] - 1));
        }
    }

    @Test
    void nothingWrittenBoundsAtOneAndNeverPasses() {
        assertEquals(1.0, Certifier.upperBound(0, 0));
        assertFalse(Certifier.boundPasses(0, 0));
        var walk = Certifier.walk(List.of(point(0.95, 0, 0, 0.9)), 0.5);
        assertNull(walk.threshold());
    }

    @Test
    void theBoundMatchesTheKnownZeroWrongClosedForm() {
        // With no wrong records the bound is 1 - 0.05^(1/n).
        assertEquals(1 - Math.pow(0.05, 1.0 / 59), Certifier.upperBound(0, 59), 1e-9);
    }

    @Test
    void theWalkStopsAtTheFirstFailure() {
        var grid = GraphEvalScorer.THRESHOLDS.stream()
                .map(t -> Math.abs(t - 0.70) < 1e-9 ? point(t, 200, 40, 0.9) : point(t, 200, 0, 0.9)).toList();
        var walk = Certifier.walk(grid, 0.5);
        assertEquals(0.75, walk.threshold());
        assertNull(walk.failure());
        assertFalse(walk.steps().stream().filter(s -> s.threshold() < 0.70).anyMatch(Certifier.Step::passes),
                "nothing below the first failure passes");
    }

    @Test
    void recallBelowTheFloorAtTheTopCertifiesNothingAndSaysRecall() {
        var grid = GraphEvalScorer.THRESHOLDS.stream().map(t -> point(t, 200, 0, t > 0.9 ? 0.4 : 0.9)).toList();
        var walk = Certifier.walk(grid, 0.5);
        assertNull(walk.threshold());
        assertTrue(walk.failure().contains("recall"), walk.failure());
        var c = Certifier.certify(List.of(walk, walk), false, true, List.of(), List.of(), null);
        assertEquals(Certifier.NOT_CERTIFIED, c.status());
        assertTrue(c.reasons().stream().anyMatch(r -> r.contains("recall")), c.reasons().toString());
    }

    @Test
    void combiningRunsTakesTheHigherThreshold() {
        var combined = Certifier.combine(List.of(walkDownTo(0.70), walkDownTo(0.80)));
        assertEquals(0.80, combined.threshold());
        assertEquals(List.of(), combined.reasons());
    }

    @Test
    void aSecondRunThatFailsCertifiesNothing() {
        var failing = Certifier.walk(List.of(point(0.95, 200, 40, 0.9)), 0.5);
        var c = Certifier.certify(List.of(walkDownTo(0.70), failing), false, true, List.of(), List.of(), null);
        assertEquals(Certifier.NOT_CERTIFIED, c.status());
        assertNull(c.threshold());
        assertTrue(c.reasons().contains(Certifier.SECOND_RUN_FAILED), c.reasons().toString());
    }

    @Test
    void noBlindLabelsIsPendingAgreement() {
        var c = Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, false, List.of(), List.of(), null);
        assertEquals(Certifier.PENDING_AGREEMENT, c.status());
        assertEquals(0.5, c.threshold());
    }

    @Test
    void anUnadjudicatedWrongRecordIsPendingAdjudicationAndListed() {
        var c = Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, true, List.of(RECORD), List.of(), null);
        assertEquals(Certifier.PENDING_ADJUDICATION, c.status());
        assertEquals(List.of(RECORD), c.unadjudicated());
    }

    @Test
    void everyWrongRecordAdjudicatedWrongCertifies() {
        var verdict = new Adjudication("c007", RECORD.record(), Certifier.WRONG, "a real miss");
        var c = Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, true, List.of(RECORD), List.of(verdict), null);
        assertEquals(Certifier.CERTIFIED, c.status());
        assertEquals(0.5, c.threshold());
        assertEquals(List.of(), c.unadjudicated());
    }

    @Test
    void aLabelErrorVerdictMeansTheLabelsNeedFixing() {
        var verdict = new Adjudication("c007", RECORD.record(), Certifier.LABEL_ERROR, "gold missed an alias");
        var c = Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, true, List.of(RECORD), List.of(verdict), null);
        assertEquals(Certifier.NOT_CERTIFIED, c.status());
        assertTrue(c.reasons().contains(Certifier.LABELS_NEED_FIXING), c.reasons().toString());
        assertNull(c.threshold());
    }

    @Test
    void oneRunWithoutItsSpotCheckCertifiesNothing() {
        var c = Certifier.certify(List.of(walkDownTo(0.5)), false, true, List.of(), List.of(), null);
        assertEquals(Certifier.NOT_CERTIFIED, c.status());
        assertNull(c.threshold());
        assertTrue(c.reasons().contains(Certifier.NEEDS_SPOT_CHECK), c.reasons().toString());
    }

    @Test
    void oneRunWhoseSpotCheckAgreesCertifies() {
        var c = Certifier.certify(List.of(walkDownTo(0.5)), false, true, List.of(), List.of(),
                new Certifier.SpotCheck(14, 300, 0));
        assertEquals(Certifier.CERTIFIED, c.status());
        assertEquals(0.5, c.threshold());
    }

    @Test
    void oneRunWhoseSpotCheckDiffersCertifiesNothingAndAsksForTwoRuns() {
        var c = Certifier.certify(List.of(walkDownTo(0.5)), false, true, List.of(), List.of(),
                new Certifier.SpotCheck(14, 300, 2));
        assertEquals(Certifier.NOT_CERTIFIED, c.status());
        assertNull(c.threshold());
        assertTrue(c.reasons().stream().anyMatch(r -> r.contains("2 of 300") && r.contains("two full runs")),
                c.reasons().toString());
    }

    @Test
    void aRunWithAFailedDecisionPassesNowhere() {
        var grid = passingDownTo(0.5).stream()
                .map(p -> point(p.threshold(), p.written(), p.wrong(), 0, 0.9, 3, TRAPS, 0)).toList();
        var walk = Certifier.walk(grid, Certifier.DEFAULT_RECALL_FLOOR);
        assertNull(walk.threshold());
        assertTrue(walk.failure().startsWith("3 decisions failed"), walk.failure());
        assertFalse(walk.steps().stream().anyMatch(Certifier.Step::passes), "a failed decision fails every threshold");
    }

    @Test
    void aChangedMemoryCertifiesNothing() {
        var c = Certifier.certify(List.of(walkDownTo(0.95), walkDownTo(0.95)), true, true, List.of(), List.of(), null);
        assertEquals(Certifier.NOT_CERTIFIED, c.status());
        assertNull(c.threshold());
    }

    @Test
    void adjudicationsParseAndRefuseAnUnknownVerdict() {
        var parsed = Certifier.parseAdjudications(
                "[{\"caseId\":\"c007\",\"record\":\"term:Meridian:Project\",\"verdict\":\"wrong\",\"note\":\"n\"}]");
        assertEquals(List.of(new Adjudication("c007", "term:Meridian:Project", "wrong", "n")), parsed);
        var e = assertThrows(IllegalArgumentException.class, () -> Certifier.parseAdjudications(
                "[{\"caseId\":\"c007\",\"record\":\"r\",\"verdict\":\"maybe\"}]"));
        assertTrue(e.getMessage().contains("verdict"), e.getMessage());
    }

    private static List<ClassStep> classSteps(int... nk) {
        var out = new java.util.ArrayList<ClassStep>();
        for (int i = 0; i < nk.length / 2; i++) {
            out.add(new ClassStep(GraphEvalScorer.THRESHOLDS.get(i), nk[2 * i], nk[2 * i + 1]));
        }
        return out;
    }

    @Test
    void twentyNineValuesWithNoWrongPassProvisionalAndTwentyEightAreNotEvaluable() {
        assertTrue(Certifier.boundPasses(0, 29, Certifier.PROVISIONAL_LIMIT));
        assertFalse(Certifier.boundPasses(0, 28, Certifier.PROVISIONAL_LIMIT));
        var walk = Certifier.classWalk("status", classSteps(29, 0));
        assertEquals(Certifier.PROVISIONAL, walk.state());
        assertEquals(0.95, walk.threshold());
        assertEquals(29, walk.n());
        var none = Certifier.classWalk("status", classSteps(28, 0));
        assertEquals(Certifier.DISABLED, none.state(), "no evaluable threshold");
        assertNull(none.threshold());
    }

    @Test
    void aClassWalkStartsAtTheHighestEvaluableThresholdAndTakesTheLowestOfItsRun() {
        var walk = Certifier.classWalk("time", classSteps(10, 0, 20, 0, 40, 0, 60, 1, 80, 9, 100, 0));
        assertEquals(0.80, walk.threshold(), "0.95 and 0.90 are not evaluable; 0.75 has k > 6");
        assertEquals(Certifier.PROVISIONAL, walk.state());
        assertEquals(60, walk.n());
        assertEquals(1, walk.k());
        assertEquals(Certifier.upperBound(1, 60), walk.bound());
    }

    @Test
    void aClassCertifiesFromTwoHundredFiftyValuesWithinFivePercent() {
        var walk = Certifier.classWalk("negation", classSteps(250, 2, 300, 3, 400, 30));
        assertEquals(Certifier.CLASS_CERTIFIED, walk.state());
        assertEquals(0.90, walk.threshold());
        var loose = Certifier.classWalk("negation", classSteps(250, 10));
        assertEquals(Certifier.DISABLED, loose.state(), "at n >= 250 only the 5% bound counts");
    }

    @Test
    void aClassFailingAtItsFirstEvaluableThresholdIsDisabled() {
        var walk = Certifier.classWalk("status", classSteps(5, 0, 40, 7, 60, 0));
        assertEquals(Certifier.DISABLED, walk.state());
        assertNull(walk.threshold());
        assertEquals(40, walk.n(), "reported at the first evaluable step");
        assertEquals(7, walk.k());
    }

    @Test
    void classWalksCombineOnTheHighestThresholdAndADisabledRunDisables() {
        var low = Certifier.classWalk("status", classSteps(29, 0, 40, 0));
        var high = Certifier.classWalk("status", classSteps(29, 0, 40, 6));
        assertEquals(0.90, low.threshold());
        assertEquals(0.95, high.threshold());
        assertEquals(0.95, Certifier.combineClass(List.of(low, high)).threshold());
        var disabled = Certifier.classWalk("status", classSteps(5, 0));
        assertEquals(Certifier.DISABLED, Certifier.combineClass(List.of(low, disabled, high)).state());
    }

    @Test
    void gWrittenLeavesNoiseOutOfItsDenominator() {
        var noisy = Certifier.walk(List.of(point(0.95, 64, 0, 5, 0.9, 0, TRAPS, 0)), 0.5);
        assertEquals(59, noisy.steps().getFirst().written() - noisy.steps().getFirst().noise());
        assertEquals(Certifier.upperBound(0, 59), noisy.steps().getFirst().upperBound());
        assertEquals(0.95, noisy.threshold());
        var tooNoisy = Certifier.walk(List.of(point(0.95, 64, 0, 6, 0.9, 0, TRAPS, 0)), 0.5);
        assertNull(tooNoisy.threshold(), "58 non-noise items cannot pass");
        assertTrue(tooNoisy.failure().contains("wrong-share"), tooNoisy.failure());
    }

    @Test
    void gTrapBoundsTheViolationsOverTheTrapSet() {
        var passing = Certifier.walk(List.of(point(0.95, 200, 0, 0, 0.9, 0, 59, 0)), 0.5);
        assertEquals(0.95, passing.threshold());
        assertEquals(Certifier.upperBound(0, 59), passing.steps().getFirst().trapBound());
        var violated = Certifier.walk(List.of(point(0.95, 200, 0, 0, 0.9, 0, 59, 1)), 0.5);
        assertNull(violated.threshold());
        assertTrue(violated.failure().contains("trap upper bound") && violated.failure().contains("1 violations of 59"),
                violated.failure());
        var empty = Certifier.walk(List.of(point(0.95, 200, 0, 0, 0.9, 0, 0, 0)), 0.5);
        assertNull(empty.threshold(), "an empty trap set bounds at 1.0");
        assertEquals(1.0, empty.steps().getFirst().trapBound());
    }

    @Test
    void theCertificateCheckNamesTheStampThatDiffers() {
        var cert = new Certifier.Certificate(0.8, List.of(Certifier.classWalk("status", classSteps(29, 0))),
                "v3@aaa", "x@111");
        assertNull(Certifier.check(cert, "v3@aaa", "x@111"));
        assertEquals("schema stamp v3@aaa differs from running v3@bbb", Certifier.check(cert, "v3@bbb", "x@111"));
        assertEquals("extraction stamp x@111 differs from running x@222", Certifier.check(cert, "v3@aaa", "x@222"));
    }
}
