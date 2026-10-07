import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.Adjudications;
import services.grapheval.CertificationSplit;
import services.grapheval.Certifier;
import services.grapheval.Certifier.ClassStep;
import services.grapheval.Certifier.Evaluation;
import services.grapheval.Certifier.GateCounts;
import services.grapheval.Certifier.Walk;
import services.grapheval.Configuration;
import services.grapheval.GateBounds;
import services.grapheval.GateBounds.MemoryCounts;
import services.grapheval.GraphEvalHarness;
import services.grapheval.GraphEvalScorer;
import services.grapheval.GraphEvalScorer.ClassTally;
import services.grapheval.GraphEvalScorer.Point;
import services.grapheval.GraphEvalScorer.WrongRecord;
import services.grapheval.MemoryBounds;
import services.grapheval.RecordBounds;
import services.grapheval.SequenceScorer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
    void aBlindVerdictTakesTheUnmatchedSide() {
        var blindWrong = new Adjudications.Verdict("c007", RECORD.record(), null, Certifier.WRONG, "guide@000000000000",
                Adjudications.OPERATOR, null, null, "");
        assertEquals(Certifier.CERTIFIED, Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, true,
                List.of(RECORD), List.of(blindWrong), null).status());

        var blindRight = new Adjudications.Verdict("c007", RECORD.record(), null, Adjudications.RIGHT,
                "guide@000000000000", Adjudications.OPERATOR, null, null, "");
        var c = Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, true, List.of(RECORD),
                List.of(blindRight), null);
        assertTrue(c.reasons().contains(Certifier.LABELS_NEED_FIXING), "right on an unmatched record is a label error");
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

    // ---- Protocol v2 (JCLAW-1368, JCLAW-1369) ----

    private static final double START = 0.95;
    private static final double BASE_TAIL = Certifier.BASE_GATE_TAIL;

    /**
     * A synthetic world: each gate's {written, wrong, gold, right} at threshold t, spread one per memory (memory i
     * writes {@code perMemory} records, wrong when i < wrong, and holds one gold record, right when i < right); the
     * trap set and its violators; the class, written and trap records {@code packed} per memory; and, when
     * {@code set} is given, the gates the other set certifies emptied as the harness routes them.
     */
    private static final class World {
        DoubleFunction<int[]> terms = _ -> new int[] {200, 0, 200, 200};
        final Map<String, int[]> relations = new LinkedHashMap<>();
        int trapGold = 100;
        final Map<String, Integer> violations = new HashMap<>();
        int extraWrong;
        int perMemory = 1;
        int packed = 1;
        int classValues;
        String set;
        final List<Configuration> seen = new ArrayList<>();

        World relation(String name, int written, int wrong, int gold, int right) {
            relations.put(name, new int[] {written, wrong, gold, right});
            return this;
        }

        private GateCounts counts(int[] w) {
            var writing = new ArrayList<MemoryCounts>();
            var labelled = new ArrayList<MemoryCounts>();
            for (int i = 0; i < Math.max(w[0], w[2]); i++) {
                var mc = new MemoryCounts("m" + i, i < w[0] ? perMemory : 0, i < w[1] ? 1 : 0, 0, i < w[2] ? 1 : 0,
                        i < w[3] ? 1 : 0);
                if (mc.written() > 0) writing.add(mc);
                if (mc.gold() > 0) labelled.add(mc);
            }
            return new GateCounts(writing, labelled);
        }

        /** {@code n} records, the first {@code wrong} of them wrong, {@link #packed} to a memory. */
        private List<MemoryCounts> spread(int n, int wrong) {
            var out = new ArrayList<MemoryCounts>();
            for (int first = 0; first < n; first += packed) {
                int written = Math.min(packed, n - first);
                int bad = Math.clamp(wrong - first, 0, written);
                out.add(new MemoryCounts("m" + first / packed, written, bad, 0, 0, 0));
            }
            return out;
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
            var classes = new HashMap<String, List<MemoryCounts>>();
            for (var name : Certifier.V2_CLASSES) classes.put(name, spread(classValues, 0));
            boolean live = set == null || set.equals(CertificationSplit.HELDOUT);
            boolean synthetic = set == null || set.equals(CertificationSplit.CASES);
            return new Evaluation(live ? gates : Map.of(), synthetic ? classes : Map.of(),
                    live ? spread(written, wrong) : List.of(), synthetic ? spread(trapGold, violated) : List.of());
        }

        Certifier.Sequencing sequence(double start, GateBounds bounds) {
            return Certifier.sequence(bounds, set, start, List.copyOf(relations.keySet()),
                    Certifier.DEFAULT_RECALL_FLOOR, this::evaluate);
        }

        Certifier.Sequencing sequence() {
            return sequence(START, MemoryBounds.INSTANCE);
        }
    }

    private static Certifier.Gate gate(Certifier.Sequencing s, String name) {
        return s.relations().stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }

    private static Certifier.Requirement requirement(Certifier.Sequencing s, String name) {
        return s.requirements().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static final Certifier.VerdictInputs CLEAN = new Certifier.VerdictInputs(false, 0, false, false,
            SequenceScorer.REPORTED, true, 0, 0);

    @Test
    void aFailingTermGateEvaluatesNoRelationAndEnablesNothing() {
        var world = new World().relation("uses", 200, 0, 200, 200);
        world.terms = _ -> new int[] {129, 0, 200, 200};
        var s = world.sequence();
        assertEquals(Certifier.OFF, s.terms().state());
        assertEquals(List.of(), s.relations());
        assertTrue(world.seen.stream().allMatch(c -> c.relations().values().stream().allMatch(t -> t == START)),
                "no relation gate was walked; the relations are only observed at the start for their requirements");
        assertEquals(Map.of(), s.reached().relations());
        assertFalse(s.passed());
        var v = Certifier.verdict(s, CLEAN);
        assertEquals(Certifier.NOT_CERTIFIED, v.status());
        assertTrue(v.reasons().getFirst().contains("Terms") && v.reasons().getFirst().contains("0 wrong of 129"),
                v.reasons().toString());

        var passing = new World().relation("uses", 200, 0, 200, 200);
        passing.terms = _ -> new int[] {130, 0, 200, 200};
        assertEquals(Certifier.ON, passing.sequence().terms().state(), "130 Term memories with none wrong pass");
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
    void aRelationOfOneHundredTwentyNineMemoriesStaysOffAndOneHundredThirtySwitchOn() {
        var s = new World().relation("kind_of", 129, 0, 129, 129).relation("uses", 130, 0, 130, 130).sequence();
        assertEquals(Certifier.OFF, gate(s, "kind_of").state());
        assertNull(gate(s, "kind_of").threshold());
        assertEquals(Certifier.ON, gate(s, "uses").state());
        assertEquals(0.50, gate(s, "uses").threshold());
        assertEquals(Map.of("uses", 0.50), s.reached().relations());
        assertTrue(s.passed());
    }

    @Test
    void severalRecordsInOneMemoryCountAsOneMemory() {
        var world = new World();
        world.perMemory = 3;
        world.terms = _ -> new int[] {129, 0, 200, 200};
        var s = world.sequence();
        assertEquals(129, s.terms().n(), "n is writing memories, not the 387 records");
        assertEquals(Certifier.OFF, s.terms().state());
        assertEquals(Certifier.ON, world.sequence(START, RecordBounds.INSTANCE).terms().state(),
                "pooled, the same records would overstate n and pass");
    }

    @Test
    void oneFailingRelationLeavesEveryOtherAsItWas() {
        var failing = new World().relation("a", 200, 0, 200, 200).relation("b", 200, 10, 200, 200)
                .relation("c", 200, 0, 200, 200);
        failing.trapGold = 200;
        var passing = new World().relation("a", 200, 0, 200, 200).relation("b", 200, 0, 200, 200)
                .relation("c", 200, 0, 200, 200);
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
        var s = new World().relation("a", 200, 0, 200, 40).relation("b", 200, 0, 200, 200).sequence();
        assertEquals(Certifier.OFF, gate(s, "a").state());
        assertTrue(gate(s, "a").reason().contains("recall lower bound"), gate(s, "a").reason());
        assertNotNull(gate(s, "a").recallLower());
        assertEquals(Certifier.ON, gate(s, "b").state());
        assertTrue(s.passed());
    }

    @Test
    void theLastEnabledRelationThatPushesGTrapOverIsBackedOffAndTheEarlierStayOn() {
        var world = new World().relation("a", 200, 0, 200, 200).relation("b", 200, 0, 200, 200)
                .relation("c", 200, 0, 200, 200);
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
        var world = new World().relation("a", 200, 0, 200, 200).relation("b", 200, 0, 200, 200);
        world.terms = t -> t >= 0.80 - 1e-9 ? new int[] {200, 0, 200, 200} : new int[] {200, 30, 200, 200};
        var s = world.sequence(0.85, MemoryBounds.INSTANCE);
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
        var strict = world.sequence(START, MemoryBounds.INSTANCE);
        var lenient = world.sequence(START, LENIENT);
        assertEquals(Certifier.NOT_CERTIFIED, Certifier.verdict(strict, CLEAN).status());
        assertEquals(Certifier.CERTIFIED, Certifier.verdict(lenient, CLEAN).status());
        assertEquals(Certifier.ON, gate(lenient, "a").state());
    }

    @Test
    void theVerdictTakesTheFirstPreconditionThatApplies() {
        var good = new World().relation("a", 200, 0, 200, 200).sequence();
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
        var s = new World().relation("uses", 130, 0, 130, 130).sequence();
        var g = gate(s, "uses");
        assertEquals(130, g.n());
        assertEquals(0, g.k());
        assertEquals(Math.pow(0.99, 130), g.power().at1(), 1e-12, "at 0.05 / 39, 130 memories allow none wrong");
        assertTrue(g.power().at3() < g.power().at2() && g.power().at2() < g.power().at1());
        assertNotNull(s.written());
        assertTrue(s.written().power().at1() > 0);
    }

    @Test
    void baseGatesAreBoundedAtTheCorrectedTailAndThePooledGatesAtFivePercent() {
        var world = new World().relation("uses", 200, 1, 200, 200);
        world.terms = _ -> new int[] {200, 1, 200, 200};
        var s = world.sequence();
        var step = s.terms().steps().getFirst();
        assertEquals(Certifier.upperBound(1, 200, BASE_TAIL), step.bound());
        assertEquals(Certifier.upperBound(1, 200, BASE_TAIL), gate(s, "uses").bound());
        var written = s.written();
        assertNotNull(written);
        assertEquals(Certifier.upperBound(written.k(), written.n(), Certifier.CONFIDENCE_TAIL), written.bound());
        assertEquals(Certifier.upperBound(0, 100, Certifier.CONFIDENCE_TAIL), s.trap().bound());
    }

    @Test
    void aHeldOutRunDisablesTheClassesAndFailsAtOnceOnAnUnevaluableTrapGate() {
        var world = new World().relation("a", 200, 0, 200, 200).relation("b", 200, 0, 200, 200);
        world.classValues = 300;
        world.violations.put("b", 50);
        world.set = CertificationSplit.HELDOUT;
        var s = world.sequence();
        assertEquals(Certifier.ON, s.terms().state());
        assertEquals(Certifier.ON, gate(s, "a").state());
        assertEquals(Certifier.ON, gate(s, "b").state(), "no back-off: switching b off gives G_trap no data");
        for (var c : s.classes()) assertEquals(Certifier.DISABLED, c.state(), c.name());
        assertFalse(s.passed());
        assertEquals(List.of(), s.backOff());
        var reason = "G_trap not evaluable: its certifying set cases has no data in this run";
        assertEquals(List.of(reason), s.reasons());
        var v = Certifier.verdict(s, CLEAN);
        assertEquals(Certifier.NOT_CERTIFIED, v.status());
        assertEquals(List.of(reason), v.reasons());

        world.set = null;
        var development = world.sequence();
        assertTrue(development.classes().stream().allMatch(c -> c.state().equals(Certifier.CLASS_CERTIFIED)),
                "a development run routes nothing");
        assertEquals(1, development.backOff().size());
    }

    @Test
    void aCasesRunTurnsTheTermGateOffAsNotEvaluable() {
        var world = new World().relation("a", 200, 0, 200, 200);
        world.classValues = 300;
        world.set = CertificationSplit.CASES;
        var s = world.sequence();
        assertEquals(Certifier.OFF, s.terms().state());
        assertEquals("terms not evaluable: its certifying set heldout has no data in this run", s.terms().reason());
        assertNull(s.terms().recallLower());
        assertEquals(List.of("Terms gate: terms not evaluable: its certifying set heldout has no data in this run"),
                Certifier.verdict(s, CLEAN).reasons());
    }

    @Test
    void aRelationWithNoWritingMemoryOnItsSetIsNotEvaluable() {
        var world = new World().relation("a", 0, 0, 50, 0).relation("b", 200, 0, 200, 200);
        world.set = CertificationSplit.HELDOUT;
        var s = world.sequence();
        assertEquals(Certifier.OFF, gate(s, "a").state());
        assertEquals("a not evaluable: its certifying set heldout has no data in this run", gate(s, "a").reason());
        assertEquals(Certifier.ON, gate(s, "b").state());
    }

    @Test
    void everyGateListsItsSetTailObservedCountsNeedsAndReachability() {
        var world = new World().relation("uses", 130, 0, 130, 130).relation("kind_of", 200, 0, 129, 129);
        world.perMemory = 2;
        world.classValues = 40;
        var s = world.sequence();
        assertEquals(List.of(Certifier.TERMS, "uses", "kind_of", "status", "time", "negation", Certifier.G_WRITTEN,
                Certifier.G_TRAP), s.requirements().stream().map(Certifier.Requirement::name).toList());

        var terms = requirement(s, Certifier.TERMS);
        assertEquals(CertificationSplit.HELDOUT, terms.set());
        assertEquals(BASE_TAIL, terms.tail());
        assertEquals(400, terms.writtenRecords());
        assertEquals(200, terms.writingMemories());
        assertEquals(200, terms.goldMemories());
        assertEquals(List.of(new Certifier.Needed(0, 130, 130), new Certifier.Needed(1, 176, 176),
                new Certifier.Needed(2, 215, 215), new Certifier.Needed(3, 251, 251),
                new Certifier.Needed(5, 317, 317)), terms.needed());
        assertEquals(true, terms.reachable());

        var uses = requirement(s, "uses");
        assertEquals(130, uses.goldMemories());
        assertEquals(true, uses.reachable(), "130 memories with gold reach the zero-wrong minimum");
        var kindOf = requirement(s, "kind_of");
        assertEquals(Certifier.ON, gate(s, "kind_of").state());
        assertEquals(129, kindOf.goldMemories());
        assertEquals(false, kindOf.reachable(), "129 do not");

        var written = requirement(s, Certifier.G_WRITTEN);
        assertEquals(CertificationSplit.HELDOUT, written.set());
        assertEquals(Certifier.CONFIDENCE_TAIL, written.tail());
        assertEquals(List.of(59, 93, 124, 153, 208),
                written.needed().stream().map(Certifier.Needed::memories).toList());
        assertEquals(200, written.goldMemories(), "every memory with gold for any gate");

        for (var name : List.of("status", "time", "negation", Certifier.G_TRAP)) {
            var r = requirement(s, name);
            assertEquals(CertificationSplit.CASES, r.set(), name);
            assertEquals(Certifier.CONFIDENCE_TAIL, r.tail(), name);
            assertNull(r.reachable(), name + " is record-level");
            assertEquals(List.of(59, 93, 124, 153, 208),
                    r.needed().stream().map(Certifier.Needed::records).toList(), name);
            assertTrue(r.needed().stream().allMatch(n -> n.memories() == null), name);
        }
        assertEquals(40, requirement(s, "status").writtenRecords());
        assertEquals(100, requirement(s, Certifier.G_TRAP).writtenRecords());
    }

    @Test
    void theRecordLevelGatesCountRecordsAndTheMemoryLevelGatesMemories() {
        var world = new World().relation("a", 200, 0, 200, 200);
        world.perMemory = 3;
        world.packed = 4;
        world.classValues = 300;
        var s = world.sequence();
        assertEquals(200, s.terms().n(), "Terms counts writing memories, not the 600 records");
        assertNotNull(s.written());
        assertEquals(100, s.written().n(), "G_written counts the 100 memories its 400 records sit in");
        assertNotNull(s.trap());
        assertEquals(100, s.trap().n(), "G_trap counts its 100 records, not their 25 memories");
        assertEquals(300, s.classes().getFirst().n(), "a class counts its 300 values, not their 75 memories");

        // One memory with 50 records, one wrong, against 20 memories of one record, one wrong.
        var clustered = List.of(new MemoryCounts("m0", 50, 1, 0, 0, 0));
        var spread = new ArrayList<MemoryCounts>();
        for (int i = 0; i < 20; i++) spread.add(new MemoryCounts("m" + i, 1, i == 0 ? 1 : 0, 0, 0, 0));
        var a = new Evaluation(Map.of(Certifier.TERMS, new GateCounts(clustered, List.of())),
                Map.of("status", clustered), clustered, clustered);
        var b = new Evaluation(Map.of(Certifier.TERMS, new GateCounts(spread, List.of())),
                Map.of("status", spread), spread, spread);
        var worst = GraphEvalHarness.worst(List.of(a, b), MemoryBounds.INSTANCE);
        assertEquals(clustered, worst.gates().get(Certifier.TERMS).writing(), "1 of 1 memories beats 1 of 20");
        assertEquals(clustered, worst.written());
        assertEquals(spread, worst.trap(), "1 of 20 records beats 1 of 50");
        assertEquals(spread, worst.classes().get("status"));
    }

    @Test
    void aTermGateOffStillListsARequirementForEveryGateObservedAtTheStart() {
        var cases = new World().relation("a", 200, 0, 200, 200).relation("b", 150, 0, 150, 150);
        cases.classValues = 300;
        cases.set = CertificationSplit.CASES;
        var c = cases.sequence();
        assertEquals(Certifier.OFF, c.terms().state());
        var order = List.of(Certifier.TERMS, "a", "b", "status", "time", "negation", Certifier.G_WRITTEN,
                Certifier.G_TRAP);
        assertEquals(order, c.requirements().stream().map(Certifier.Requirement::name).toList());
        assertEquals(0, requirement(c, "a").writtenRecords(), "a cases run reads no live counts");
        assertEquals(false, requirement(c, "a").reachable());
        assertEquals(100, requirement(c, Certifier.G_TRAP).writtenRecords());
        assertEquals(0, requirement(c, Certifier.G_WRITTEN).writtenRecords());

        var heldout = new World().relation("a", 200, 0, 200, 200).relation("b", 150, 0, 150, 150);
        heldout.terms = _ -> new int[] {129, 0, 200, 200};
        heldout.perMemory = 2;
        heldout.set = CertificationSplit.HELDOUT;
        var h = heldout.sequence();
        assertEquals(Certifier.OFF, h.terms().state());
        assertTrue(h.terms().reason().contains("fails its bound"), h.terms().reason());
        assertEquals(order, h.requirements().stream().map(Certifier.Requirement::name).toList());
        var a = requirement(h, "a");
        assertEquals(400, a.writtenRecords());
        assertEquals(200, a.writingMemories());
        assertEquals(200, a.goldMemories());
        assertEquals(true, a.reachable());
        var b = requirement(h, "b");
        assertEquals(150, b.writingMemories());
        assertEquals(true, b.reachable());
        var all = new TreeMap<String, Double>();
        all.put("a", START);
        all.put("b", START);
        assertTrue(heldout.seen.contains(new Configuration(START, all, new TreeMap<>())),
                "the relations are observed with every relation at the start");
        assertEquals(129, requirement(h, Certifier.G_WRITTEN).writtenRecords(), "Terms alone at the start");
        assertEquals(0, requirement(h, "status").writtenRecords(), "a held-out run reads no class");
        assertEquals(0, requirement(h, Certifier.G_TRAP).writtenRecords());
    }

    @Test
    void anUnevaluableTrapGateBesideAFailingWrittenGateListsBothAndBacksNothingOff() {
        var world = new World().relation("a", 200, 0, 200, 200);
        world.extraWrong = 30;
        world.set = CertificationSplit.HELDOUT;
        var s = world.sequence();
        assertEquals(Certifier.ON, gate(s, "a").state());
        assertNotNull(s.written());
        assertFalse(s.written().passes());
        assertFalse(s.passed());
        assertEquals(List.of(), s.backOff());
        assertEquals(2, s.reasons().size(), s.reasons().toString());
        assertTrue(s.reasons().getFirst().startsWith("G_written fails: 30 wrong of 400, bound "), s.reasons().toString());
        assertEquals("G_trap not evaluable: its certifying set cases has no data in this run", s.reasons().get(1));
    }
}
