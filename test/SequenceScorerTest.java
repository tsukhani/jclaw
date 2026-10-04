import memory.graph.GraphView.Truth;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.Certifier;
import services.grapheval.Certifier.ClassWalk;
import services.grapheval.ExtractionPipeline;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.SequenceScorer;
import services.grapheval.SequenceScorer.Answered;
import services.grapheval.SequenceScorer.Judged;

import java.util.ArrayList;
import java.util.List;

/** JCLAW-1367: the sequence scorer on synthetic counts. */
class SequenceScorerTest extends UnitTest {

    /** {@code n} lineage decisions written at every grid threshold, the first {@code k} choosing other than gold. */
    private static List<Judged> judged(int n, int k) {
        var out = new ArrayList<Judged>();
        for (int i = 0; i < n; i++) {
            var choice = i < k ? ExtractionPipeline.CORRECTION : ExtractionPipeline.UPDATE;
            out.add(new Judged(new Decision(ExtractionPipeline.LINEAGE, "m" + i + " <- p" + i, "m" + i, "p" + i,
                    choice, 0.99, false, null), "update"));
        }
        return out;
    }

    private static ClassWalk walk(int n, int k) {
        return SequenceScorer.lineageWalk(judged(n, k), false);
    }

    @Test
    void theLineageWalkIsEvaluableFrom29AndGatesFrom250() {
        assertEquals(Certifier.DISABLED, walk(28, 0).state());
        assertNull(walk(28, 0).threshold());
        var evaluable = walk(29, 0);
        assertEquals(Certifier.PROVISIONAL, evaluable.state());
        assertEquals(0.50, evaluable.threshold());
        assertEquals(SequenceScorer.LINEAGE, evaluable.name());
        assertEquals(Certifier.PROVISIONAL, walk(249, 6).state());
        assertEquals(Certifier.DISABLED, walk(249, 7).state());
        assertEquals(Certifier.CLASS_CERTIFIED, walk(250, 6).state());
        assertEquals(Certifier.DISABLED, walk(250, 7).state());
    }

    @Test
    void aFailedDecisionDisablesTheWalkWhateverItsCounts() {
        var walk = SequenceScorer.lineageWalk(judged(250, 0), true);
        assertEquals(Certifier.DISABLED, walk.state());
        assertNull(walk.threshold());
        assertEquals(250, walk.n());
    }

    @Test
    void aDecisionBelowAThresholdIsNotCountedThere() {
        var low = new Judged(new Decision(ExtractionPipeline.LINEAGE, "a <- b", "a", "b", ExtractionPipeline.UPDATE,
                0.80, false, null), "update");
        var steps = SequenceScorer.lineageSteps(List.of(low));
        assertEquals(0, steps.getFirst().n());
        assertEquals(1, steps.getLast().n());
    }

    @Test
    void theTimelineGatesFrom250DefiniteLabelsOnBothSidesOfTheBound() {
        assertEquals(SequenceScorer.REPORTED, SequenceScorer.result(SequenceScorer.timeline(249, 249, 0, 0, 0, 0),
                false));
        assertEquals(SequenceScorer.REPORTED, SequenceScorer.result(SequenceScorer.timeline(249, 249, 30, 0, 0, 0),
                false));
        var passing = SequenceScorer.timeline(250, 250, 6, 0, 0, 0);
        assertTrue(Certifier.boundPasses(6, 250, Certifier.LIMIT));
        assertEquals(SequenceScorer.PASSED, SequenceScorer.result(passing, false));
        assertEquals(SequenceScorer.REPORTED, SequenceScorer.result(passing, true));
        var failing = SequenceScorer.timeline(250, 250, 7, 0, 0, 0);
        assertFalse(Certifier.boundPasses(7, 250, Certifier.LIMIT));
        assertEquals(SequenceScorer.FAILED, SequenceScorer.result(failing, false));
        assertEquals(SequenceScorer.FAILED, SequenceScorer.result(failing, true));
    }

    @Test
    void anIncompleteOrUnknownGoldAnswerStaysOutOfTheWrongShare() {
        var t = SequenceScorer.timeline(List.of(
                new Answered(Truth.YES, false, Truth.YES, false),
                new Answered(Truth.YES, true, Truth.NO, false),
                new Answered(Truth.NO, false, Truth.UNKNOWN, false),
                new Answered(Truth.UNKNOWN, false, Truth.YES, true),
                new Answered(Truth.UNKNOWN, false, Truth.UNKNOWN, false)));
        assertEquals(5, t.probes());
        assertEquals(3, t.definiteGold());
        assertEquals(1, t.definiteWrong());
        assertEquals(1.0 / 3, t.wrongShare(), 1e-9);
        assertEquals(Certifier.upperBound(1, 3), t.bound(), 1e-9);
        assertEquals(1, t.incomplete());
        assertEquals(1, t.definiteWhereGoldUnknown());
        assertEquals(3, t.assumedAgreement());
        var none = SequenceScorer.timeline(List.of(new Answered(Truth.UNKNOWN, false, Truth.UNKNOWN, false)));
        assertEquals(0.0, none.wrongShare());
        assertEquals(1.0, none.bound());
    }

    @Test
    void runsCombineAtTheHighestThresholdAndTheLowestState() {
        var certified = new ClassWalk(SequenceScorer.LINEAGE, 0.80, Certifier.CLASS_CERTIFIED, 300, 2, 0.02, List.of());
        var provisional = new ClassWalk(SequenceScorer.LINEAGE, 0.90, Certifier.PROVISIONAL, 40, 0, 0.07, List.of());
        var combined = SequenceScorer.combineLineage(List.of(certified, provisional));
        assertEquals(0.90, combined.threshold());
        assertEquals(Certifier.PROVISIONAL, combined.state());
        var higherCertified = new ClassWalk(SequenceScorer.LINEAGE, 0.95, Certifier.CLASS_CERTIFIED, 260, 1, 0.02,
                List.of());
        var both = SequenceScorer.combineLineage(List.of(provisional, higherCertified));
        assertEquals(0.95, both.threshold());
        assertEquals(Certifier.PROVISIONAL, both.state());
        var disabled = new ClassWalk(SequenceScorer.LINEAGE, null, Certifier.DISABLED, 10, 0, 0.3, List.of());
        var off = SequenceScorer.combineLineage(List.of(certified, disabled));
        assertEquals(Certifier.DISABLED, off.state());
        assertNull(off.threshold());
    }

    @Test
    void theOverallResultIsTheWorstRun() {
        assertEquals(SequenceScorer.FAILED, SequenceScorer.overall(List.of(SequenceScorer.PASSED,
                SequenceScorer.FAILED, SequenceScorer.REPORTED)));
        assertEquals(SequenceScorer.REPORTED, SequenceScorer.overall(List.of(SequenceScorer.PASSED,
                SequenceScorer.REPORTED)));
        assertEquals(SequenceScorer.PASSED, SequenceScorer.overall(List.of(SequenceScorer.PASSED)));
    }
}
