package services.grapheval;

import memory.graph.GraphView;
import services.grapheval.Certifier.ClassCounts;
import services.grapheval.Certifier.ClassStep;
import services.grapheval.Certifier.ClassWalk;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.GateBounds.MemoryCounts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Scores a sequence run (JCLAW-1367): the timeline probes answered against their labels, and the lineage class walked
 * over the run's lineage decisions. Pure: counts in, counts out.
 */
public final class SequenceScorer {

    public static final String REPORTED = "reported";
    public static final String FAILED = "failed";
    public static final String PASSED = "passed";
    public static final String UNCONFIRMED = "unconfirmed";
    public static final String LINEAGE = "lineage";
    /** A timeline gates from this many probes with a definite label. */
    public static final int TIMELINE_GATE_N = Certifier.CERTIFIED_CLASS_N;

    private SequenceScorer() {}

    /**
     * One variant's probes. {@code definiteWrong} counts a definite answer that differs from a definite label;
     * {@code incomplete} an UNKNOWN answer to a definite label, which is not wrong; {@code definiteWhereGoldUnknown} a
     * definite answer to an UNKNOWN label, outside the share; {@code assumedAgreement} the probes whose answer's
     * {@code assumed} matches the label's.
     */
    public record Timeline(int probes, int definiteGold, int definiteWrong, double wrongShare, double bound,
                           int incomplete, int definiteWhereGoldUnknown, int assumedAgreement) {}

    /** A probe's label beside the answer it got. */
    public record Answered(GraphView.Truth gold, boolean goldAssumed, GraphView.Truth answer, boolean answerAssumed) {}

    /** A lineage decision beside its gold lineage, lower-case. */
    public record Judged(Decision decision, String gold) {}

    public static Timeline timeline(List<Answered> answers) {
        int definiteGold = 0;
        int wrong = 0;
        int incomplete = 0;
        int unknownGold = 0;
        int assumed = 0;
        for (var a : answers) {
            if (a.goldAssumed() == a.answerAssumed()) assumed++;
            boolean definiteAnswer = a.answer() != GraphView.Truth.UNKNOWN;
            if (a.gold() == GraphView.Truth.UNKNOWN) {
                if (definiteAnswer) unknownGold++;
                continue;
            }
            definiteGold++;
            if (!definiteAnswer) incomplete++;
            else if (a.answer() != a.gold()) wrong++;
        }
        return timeline(answers.size(), definiteGold, wrong, incomplete, unknownGold, assumed);
    }

    /** A timeline from its counts; with no definite label the share is 0 and the bound 1. */
    public static Timeline timeline(int probes, int definiteGold, int definiteWrong, int incomplete,
                                    int definiteWhereGoldUnknown, int assumedAgreement) {
        double share = definiteGold == 0 ? 0.0 : (double) definiteWrong / definiteGold;
        return new Timeline(probes, definiteGold, definiteWrong, share,
                Certifier.upperBound(definiteWrong, definiteGold), incomplete, definiteWhereGoldUnknown,
                assumedAgreement);
    }

    /**
     * Below {@link #TIMELINE_GATE_N} definite labels, {@code reported}; from there {@code failed} when the bound
     * exceeds 5%, else {@code passed}, which a run with a failed decision can only reach as {@code reported}.
     */
    public static String result(Timeline endToEnd, boolean anyFailed) {
        if (endToEnd.definiteGold() < TIMELINE_GATE_N) return REPORTED;
        if (!Certifier.boundPasses(endToEnd.definiteWrong(), endToEnd.definiteGold(), Certifier.LIMIT)) return FAILED;
        return anyFailed ? REPORTED : PASSED;
    }

    /** Failed in any run is failed; else reported in any is reported; else passed. */
    public static String overall(List<String> results) {
        if (results.contains(FAILED)) return FAILED;
        if (results.contains(REPORTED)) return REPORTED;
        return PASSED;
    }

    /** At each grid threshold: n lineage decisions that write there, k of them choosing other than gold. */
    public static List<ClassStep> lineageSteps(List<Judged> judged) {
        var steps = new ArrayList<ClassStep>();
        for (double t : GraphEvalScorer.THRESHOLDS) {
            int n = 0;
            int k = 0;
            for (var j : judged) {
                if (!j.decision().writes(t)) continue;
                n++;
                var choice = Objects.requireNonNull(j.decision().choice()).toLowerCase(Locale.ROOT);
                if (!choice.equals(j.gold())) k++;
            }
            steps.add(new ClassStep(t, n, k));
        }
        return steps;
    }

    /** One run's lineage walk; a run with any failed decision is disabled whatever its counts. */
    public static ClassWalk lineageWalk(List<Judged> judged, boolean anyFailed) {
        return lineageWalk(judged, anyFailed, GraphEvalScorer.THRESHOLDS.getFirst());
    }

    /** {@link #lineageWalk(List, boolean)} over the thresholds at or below {@code start} only. */
    public static ClassWalk lineageWalk(List<Judged> judged, boolean anyFailed, double start) {
        return lineageWalk(judged, anyFailed, start, RecordBounds.INSTANCE);
    }

    /** {@link #lineageWalk(List, boolean, double)} with every bound through {@code bounds}. */
    public static ClassWalk lineageWalk(List<Judged> judged, boolean anyFailed, double start, GateBounds bounds) {
        var steps = lineageSteps(judged).stream().filter(s -> s.t() <= start + 1e-9)
                .map(s -> new ClassCounts(s.t(), List.of(new MemoryCounts("", s.n(), s.k(), 0, 0, 0)))).toList();
        var walk = Certifier.classWalk(LINEAGE, steps, bounds);
        if (!anyFailed) return walk;
        return new ClassWalk(LINEAGE, null, Certifier.DISABLED, walk.n(), walk.k(), walk.bound(), walk.steps());
    }

    /** Several runs' walks: {@link Certifier#combineClass}'s threshold, at the lowest state any run reached. */
    public static ClassWalk combineLineage(List<ClassWalk> runs) {
        var combined = Certifier.combineClass(runs);
        var state = runs.stream().map(ClassWalk::state).min((a, b) -> Integer.compare(rank(a), rank(b)))
                .orElseThrow();
        if (state.equals(Certifier.DISABLED)) {
            return new ClassWalk(combined.name(), null, state, combined.n(), combined.k(), combined.bound(),
                    combined.steps());
        }
        return new ClassWalk(combined.name(), combined.threshold(), state, combined.n(), combined.k(),
                combined.bound(), combined.steps());
    }

    private static int rank(String state) {
        return switch (state) {
            case Certifier.CLASS_CERTIFIED -> 2;
            case Certifier.PROVISIONAL -> 1;
            default -> 0;
        };
    }
}
