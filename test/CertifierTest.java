import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.graphspike.Certifier;
import services.graphspike.Certifier.Adjudication;
import services.graphspike.Certifier.Walk;
import services.graphspike.GraphSpikeScorer;
import services.graphspike.GraphSpikeScorer.Point;
import services.graphspike.GraphSpikeScorer.WrongRecord;

import java.util.List;

/** JCLAW-1356: the Clopper-Pearson bound, the threshold walk and the certification preconditions, per the I/O matrix. */
class CertifierTest extends UnitTest {

    private static final int[][] BOUNDARY = {{0, 59}, {1, 93}, {2, 124}, {3, 153}, {5, 208}};

    private static Point point(double t, int written, int wrong, double recall) {
        return new Point(t, written, written - wrong, wrong, wrong, 0, 0, 0, 0, 100, recall,
                written == 0 ? null : (double) wrong / written, 0, 0);
    }

    /** A grid passing every threshold at or above {@code lowest} and failing on the bound below it. */
    private static List<Point> passingDownTo(double lowest) {
        return GraphSpikeScorer.THRESHOLDS.stream()
                .map(t -> t >= lowest - 1e-9 ? point(t, 200, 0, 0.9) : point(t, 200, 40, 0.9)).toList();
    }

    private static Walk walkDownTo(double lowest) {
        return Certifier.walk(passingDownTo(lowest), Certifier.DEFAULT_RECALL_FLOOR);
    }

    private static final WrongRecord RECORD = new WrongRecord("c007", "term:Meridian:Project", GraphSpikeScorer.MATCH);

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
        var grid = GraphSpikeScorer.THRESHOLDS.stream()
                .map(t -> Math.abs(t - 0.70) < 1e-9 ? point(t, 200, 40, 0.9) : point(t, 200, 0, 0.9)).toList();
        var walk = Certifier.walk(grid, 0.5);
        assertEquals(0.75, walk.threshold());
        assertNull(walk.failure());
        assertFalse(walk.steps().stream().filter(s -> s.threshold() < 0.70).anyMatch(Certifier.Step::passes),
                "nothing below the first failure passes");
    }

    @Test
    void recallBelowTheFloorAtTheTopCertifiesNothingAndSaysRecall() {
        var grid = GraphSpikeScorer.THRESHOLDS.stream().map(t -> point(t, 200, 0, t > 0.9 ? 0.4 : 0.9)).toList();
        var walk = Certifier.walk(grid, 0.5);
        assertNull(walk.threshold());
        assertTrue(walk.failure().contains("recall"), walk.failure());
        var c = Certifier.certify(List.of(walk, walk), false, true, List.of(), List.of());
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
        var c = Certifier.certify(List.of(walkDownTo(0.70), failing), false, true, List.of(), List.of());
        assertEquals(Certifier.NOT_CERTIFIED, c.status());
        assertNull(c.threshold());
        assertTrue(c.reasons().contains(Certifier.SECOND_RUN_FAILED), c.reasons().toString());
    }

    @Test
    void noBlindLabelsIsPendingAgreement() {
        var c = Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, false, List.of(), List.of());
        assertEquals(Certifier.PENDING_AGREEMENT, c.status());
        assertEquals(0.5, c.threshold());
    }

    @Test
    void anUnadjudicatedWrongRecordIsPendingAdjudicationAndListed() {
        var c = Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, true, List.of(RECORD), List.of());
        assertEquals(Certifier.PENDING_ADJUDICATION, c.status());
        assertEquals(List.of(RECORD), c.unadjudicated());
    }

    @Test
    void everyWrongRecordAdjudicatedWrongCertifies() {
        var verdict = new Adjudication("c007", RECORD.record(), Certifier.WRONG, "a real miss");
        var c = Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, true, List.of(RECORD), List.of(verdict));
        assertEquals(Certifier.CERTIFIED, c.status());
        assertEquals(0.5, c.threshold());
        assertEquals(List.of(), c.unadjudicated());
    }

    @Test
    void aLabelErrorVerdictMeansTheLabelsNeedFixing() {
        var verdict = new Adjudication("c007", RECORD.record(), Certifier.LABEL_ERROR, "gold missed an alias");
        var c = Certifier.certify(List.of(walkDownTo(0.5), walkDownTo(0.5)), false, true, List.of(RECORD), List.of(verdict));
        assertEquals(Certifier.NOT_CERTIFIED, c.status());
        assertTrue(c.reasons().contains(Certifier.LABELS_NEED_FIXING), c.reasons().toString());
        assertNull(c.threshold());
    }

    @Test
    void oneRunCertifiesNothing() {
        var c = Certifier.certify(List.of(walkDownTo(0.5)), false, true, List.of(), List.of());
        assertEquals(Certifier.NOT_CERTIFIED, c.status());
        assertNull(c.threshold());
        assertTrue(c.reasons().contains(Certifier.NEEDS_TWO_RUNS), c.reasons().toString());
    }

    @Test
    void aChangedMemoryCertifiesNothing() {
        var c = Certifier.certify(List.of(walkDownTo(0.95), walkDownTo(0.95)), true, true, List.of(), List.of());
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
}
