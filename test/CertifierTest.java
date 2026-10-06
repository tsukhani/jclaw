import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.Adjudications;
import services.grapheval.Certifier;
import services.grapheval.Certifier.ClassStep;
import services.grapheval.Certifier.Evaluation;
import services.grapheval.Certifier.GateCounts;
import services.grapheval.Certifier.Walk;
import services.grapheval.Configuration;
import services.grapheval.GateBounds;
import services.grapheval.GateBounds.MemoryCounts;
import services.grapheval.GraphEvalScorer;
import services.grapheval.GraphEvalScorer.ClassTally;
import services.grapheval.GraphEvalScorer.Point;
import services.grapheval.GraphEvalScorer.WrongRecord;
import services.grapheval.RecordBounds;
import services.grapheval.SequenceScorer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleFunction;

/**
 * JCLAW-1356, JCLAW-1368: the Clopper-Pearson bound, the threshold walk, the certification preconditions and the v2
 * gate sequencing, per the I/O matrices.
 */
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

    private static Adjudications.Verdict unmatched(String record, String verdict) {
        return new Adjudications.Verdict("c007", record, Adjudications.UNMATCHED, verdict, "guide@000000000000",
                Adjudications.OPERATOR, null, null, "");
    }

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
        var verdict = unmatched(RECORD.record(), Certifier.WRONG);
        var c = Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, true, List.of(RECORD), List.of(verdict), null);
        assertEquals(Certifier.CERTIFIED, c.status());
        assertEquals(0.5, c.threshold());
        assertEquals(List.of(), c.unadjudicated());
    }

    @Test
    void aLabelErrorVerdictMeansTheLabelsNeedFixing() {
        var verdict = unmatched(RECORD.record(), Certifier.LABEL_ERROR);
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

    // ---- Protocol v2 (JCLAW-1368) ----

    private static final double START = 0.95;

    /** A synthetic world: each gate's {written, wrong, gold, right} at threshold t, the trap set and its violators. */
    private static final class World {
        DoubleFunction<int[]> terms = _ -> new int[] {200, 0, 200, 200};
        final Map<String, int[]> relations = new LinkedHashMap<>();
        int trapGold = 100;
        final Map<String, Integer> violations = new HashMap<>();
        int extraWrong;
        final List<Configuration> seen = new ArrayList<>();

        World relation(String name, int written, int wrong, int gold, int right) {
            relations.put(name, new int[] {written, wrong, gold, right});
            return this;
        }

        private static GateCounts counts(int[] w) {
            var mc = new MemoryCounts("m1", w[0], w[1], 0, w[2], w[3]);
            return new GateCounts(w[0] > 0 ? List.of(mc) : List.of(), w[2] > 0 ? List.of(mc) : List.of());
        }

        Evaluation evaluate(Configuration c) {
            seen.add(c);
            var gates = new HashMap<String, GateCounts>();
            var t = terms.apply(c.terms());
            gates.put(Certifier.TERMS, counts(t));
            int written = t[0];
            int wrong = t[1] + extraWrong;
            int violated = 0;
            for (var r : c.relations().keySet()) {
                var w = relations.get(r);
                gates.put(r, counts(w));
                written += w[0];
                wrong += w[1];
                violated += violations.getOrDefault(r, 0);
            }
            return new Evaluation(gates, Map.of(), List.of(new MemoryCounts("m1", written, wrong, 0, 0, 0)),
                    List.of(new MemoryCounts("m1", trapGold, violated, 0, 0, 0)));
        }

        Certifier.Sequencing sequence(double start, GateBounds bounds) {
            return Certifier.sequence(bounds, start, List.copyOf(relations.keySet()), Certifier.DEFAULT_RECALL_FLOOR,
                    this::evaluate);
        }

        Certifier.Sequencing sequence() {
            return sequence(START, RecordBounds.INSTANCE);
        }
    }

    private static Certifier.Gate gate(Certifier.Sequencing s, String name) {
        return s.relations().stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }

    private static final Certifier.VerdictInputs CLEAN = new Certifier.VerdictInputs(false, 0, false, false,
            SequenceScorer.REPORTED, true, 0, 0);

    @Test
    void aFailingTermGateEvaluatesNoRelationAndEnablesNothing() {
        var world = new World().relation("uses", 200, 0, 200, 200);
        world.terms = _ -> new int[] {58, 0, 100, 100};
        var s = world.sequence();
        assertEquals(Certifier.OFF, s.terms().state());
        assertEquals(List.of(), s.relations());
        assertTrue(world.seen.stream().allMatch(c -> c.relations().isEmpty()), "no relation gate was evaluated");
        assertEquals(Map.of(), s.reached().relations());
        assertFalse(s.passed());
        var v = Certifier.verdict(s, CLEAN);
        assertEquals(Certifier.NOT_CERTIFIED, v.status());
        assertTrue(v.reasons().getFirst().contains("Terms") && v.reasons().getFirst().contains("0 wrong of 58"),
                v.reasons().toString());

        var passing = new World().relation("uses", 200, 0, 200, 200);
        passing.terms = _ -> new int[] {59, 0, 100, 100};
        assertEquals(Certifier.ON, passing.sequence().terms().state(), "59 Terms with none wrong pass");
    }

    @Test
    void aTermGateWhoseRecallLowerBoundIsUnderTheFloorIsNotCertified() {
        var world = new World().relation("uses", 200, 0, 200, 200);
        world.terms = _ -> new int[] {200, 0, 200, 80};
        var s = world.sequence();
        assertEquals(Certifier.OFF, s.terms().state());
        assertTrue(s.terms().recallLower() < Certifier.DEFAULT_RECALL_FLOOR);
        assertTrue(s.terms().reason().contains("recall lower bound"), s.terms().reason());
        assertEquals(Certifier.NOT_CERTIFIED, Certifier.verdict(s, CLEAN).status());
        world.terms = _ -> new int[] {200, 0, 200, 140};
        assertEquals(Certifier.ON, world.sequence().terms().state(), "a recall lower bound over the floor passes");
    }

    @Test
    void aRelationOfFiftyEightRecordsStaysOffAndFiftyNineSwitchOn() {
        var s = new World().relation("kind_of", 58, 0, 58, 58).relation("uses", 59, 0, 59, 59).sequence();
        assertEquals(Certifier.OFF, gate(s, "kind_of").state());
        assertNull(gate(s, "kind_of").threshold());
        assertEquals(Certifier.ON, gate(s, "uses").state());
        assertEquals(0.50, gate(s, "uses").threshold());
        assertEquals(Map.of("uses", 0.50), s.reached().relations());
        assertTrue(s.passed());
    }

    @Test
    void oneFailingRelationLeavesEveryOtherAsItWas() {
        var failing = new World().relation("a", 100, 0, 100, 100).relation("b", 100, 10, 100, 100)
                .relation("c", 100, 0, 100, 100);
        failing.trapGold = 200;
        var passing = new World().relation("a", 100, 0, 100, 100).relation("b", 100, 0, 100, 100)
                .relation("c", 100, 0, 100, 100);
        passing.trapGold = 200;
        var s = failing.sequence();
        var t = passing.sequence();
        assertEquals(Certifier.OFF, gate(s, "b").state());
        assertEquals(Certifier.ON, gate(t, "b").state());
        for (var name : List.of("a", "c")) assertEquals(gate(t, name), gate(s, name), name);
        assertEquals(List.of(), s.backOff());
    }

    @Test
    void aRelationWhoseRecallLowerBoundIsUnderTheFloorIsOffAndTheRestUnchanged() {
        var s = new World().relation("a", 100, 0, 100, 20).relation("b", 100, 0, 100, 100).sequence();
        assertEquals(Certifier.OFF, gate(s, "a").state());
        assertTrue(gate(s, "a").reason().contains("recall lower bound"), gate(s, "a").reason());
        assertNotNull(gate(s, "a").recallLower());
        assertEquals(Certifier.ON, gate(s, "b").state());
        assertTrue(s.passed());
    }

    @Test
    void theLastEnabledRelationThatPushesGTrapOverIsBackedOffAndTheEarlierStayOn() {
        var world = new World().relation("a", 100, 0, 100, 100).relation("b", 100, 0, 100, 100)
                .relation("c", 100, 0, 100, 100);
        world.violations.put("c", 5);
        var s = world.sequence();
        assertEquals(Certifier.ON, gate(s, "a").state());
        assertEquals(Certifier.ON, gate(s, "b").state());
        assertEquals(Certifier.OFF, gate(s, "c").state());
        assertTrue(gate(s, "c").reason().contains("back-off"), gate(s, "c").reason());
        assertEquals(1, s.backOff().size());
        var step = s.backOff().getFirst();
        assertEquals("c", step.relation());
        assertEquals(Certifier.G_TRAP, step.gate());
        assertEquals(100, step.n());
        assertEquals(5, step.k());
        assertTrue(s.passed());
        assertTrue(s.trap().passes());

        world.violations.clear();
        var none = world.sequence();
        assertEquals(List.of(), none.backOff(), "no violation, no back-off");
        assertEquals(Certifier.ON, gate(none, "c").state());
    }

    @Test
    void termsAloneFailingGWrittenAreNotCertifiedNamingTheGateAndItsCounts() {
        var world = new World();
        world.extraWrong = 20;
        var s = world.sequence();
        assertEquals(Certifier.ON, s.terms().state());
        assertFalse(s.passed());
        var v = Certifier.verdict(s, CLEAN);
        assertEquals(Certifier.NOT_CERTIFIED, v.status());
        assertEquals(List.of("G_written fails with no relation on: 20 wrong of 200, bound "
                + String.format(Locale.ROOT, "%.3f", Certifier.upperBound(20, 200))), v.reasons());
        world.extraWrong = 0;
        assertTrue(world.sequence().passed(), "with no wrong, Terms alone pass the pooled gates");
    }

    @Test
    void noWalkEvaluatesAThresholdAboveTheStartOrARelationBelowTheTerms() {
        var world = new World().relation("a", 100, 0, 100, 100).relation("b", 100, 0, 100, 100);
        world.terms = t -> t >= 0.80 - 1e-9 ? new int[] {200, 0, 200, 200} : new int[] {200, 30, 200, 200};
        var s = world.sequence(0.85, RecordBounds.INSTANCE);
        assertEquals(0.80, s.terms().threshold());
        assertEquals(0.85, s.terms().steps().getFirst().t(), "the walk starts at the start");
        for (var c : world.seen) {
            assertTrue(c.terms() <= 0.85 + 1e-9, c.toString());
            for (var t : c.relations().values()) assertTrue(t <= 0.85 + 1e-9 && t >= 0.80 - 1e-9, c.toString());
            for (var cls : c.classes().values()) {
                assertTrue(cls.threshold() == null || cls.threshold() <= 0.85 + 1e-9, c.toString());
            }
        }
        assertEquals(0.80, gate(s, "a").threshold(), "a relation never walks below the Term threshold");
    }

    /** Passes everything: a stand-in that shows every verdict reads the seam, not the record bound. */
    private static final GateBounds LENIENT = new GateBounds() {
        @Override
        public boolean passes(List<MemoryCounts> writing, double limit, double tail) {
            return true;
        }

        @Override
        public double upper(List<MemoryCounts> writing, double tail) {
            return 0;
        }

        @Override
        public double recallLower(List<MemoryCounts> labelled, double tail) {
            return 1;
        }

        @Override
        public double power(List<MemoryCounts> writing, double trueWrongShare, double limit, double tail) {
            return 1;
        }

        @Override
        public int recordsNeeded(int wrong, double limit, double tail) {
            return 1;
        }
    };

    @Test
    void swappingTheBoundsChangesTheVerdictWithoutTouchingACaller() {
        var world = new World().relation("a", 10, 5, 10, 5);
        world.terms = _ -> new int[] {10, 5, 10, 5};
        var strict = world.sequence(START, RecordBounds.INSTANCE);
        var lenient = world.sequence(START, LENIENT);
        assertEquals(Certifier.NOT_CERTIFIED, Certifier.verdict(strict, CLEAN).status());
        assertEquals(Certifier.CERTIFIED, Certifier.verdict(lenient, CLEAN).status());
        assertEquals(Certifier.ON, gate(lenient, "a").state());
    }

    @Test
    void theVerdictTakesTheFirstPreconditionThatApplies() {
        var good = new World().relation("a", 100, 0, 100, 100).sequence();
        var bad = new World();
        bad.terms = _ -> new int[] {10, 0, 10, 10};
        var off = bad.sequence();
        assertEquals(Certifier.CERTIFIED, Certifier.verdict(good, CLEAN).status());
        var everything = new Certifier.VerdictInputs(true, 3, true, true, SequenceScorer.FAILED, false, 4, 2);
        assertEquals(List.of("a case memory changed during the run"), Certifier.verdict(good, everything).reasons());
        var failed = new Certifier.VerdictInputs(false, 3, true, true, SequenceScorer.FAILED, false, 4, 2);
        var voided = Certifier.verdict(null, failed);
        assertEquals(Certifier.NOT_CERTIFIED, voided.status());
        assertEquals(Certifier.VOID_RUN, voided.reasons().getFirst());
        var spot = new Certifier.VerdictInputs(false, 0, true, true, SequenceScorer.FAILED, false, 4, 2);
        assertEquals(Certifier.NEEDS_SECOND_RUN, Certifier.verdict(good, spot).reasons().getFirst());
        var label = new Certifier.VerdictInputs(false, 0, false, true, SequenceScorer.FAILED, false, 4, 2);
        assertEquals(List.of(Certifier.LABELS_NEED_FIXING), Certifier.verdict(good, label).reasons());
        var timeline = new Certifier.VerdictInputs(false, 0, false, false, SequenceScorer.FAILED, false, 4, 2);
        assertTrue(Certifier.verdict(off, timeline).reasons().getFirst().startsWith("Terms gate"));
        assertEquals(List.of("the sequence timeline failed"), Certifier.verdict(good, timeline).reasons());
        var agreement = new Certifier.VerdictInputs(false, 0, false, false, SequenceScorer.REPORTED, false, 4, 2);
        assertEquals(Certifier.PENDING_AGREEMENT, Certifier.verdict(good, agreement).status());
        var unjudged = new Certifier.VerdictInputs(false, 0, false, false, SequenceScorer.PASSED, true, 4, 0);
        assertEquals(Certifier.PENDING_ADJUDICATION, Certifier.verdict(good, unjudged).status());
        var unchecked = new Certifier.VerdictInputs(false, 0, false, false, SequenceScorer.PASSED, true, 0, 1);
        assertEquals(Certifier.PENDING_ADJUDICATION, Certifier.verdict(good, unchecked).status());
        assertEquals(List.of("1 model verdicts await an operator check"), Certifier.verdict(good, unchecked).reasons());
    }

    @Test
    void everyGateReportsItsPowerBesideItsVerdict() {
        var s = new World().relation("uses", 59, 0, 59, 59).sequence();
        var g = gate(s, "uses");
        assertEquals(59, g.n());
        assertEquals(0, g.k());
        assertEquals(0.553, g.power().at1(), 5e-4);
        assertTrue(g.power().at3() < g.power().at2() && g.power().at2() < g.power().at1());
        assertNotNull(s.written());
        assertTrue(s.written().power().at1() > 0);
    }
}
