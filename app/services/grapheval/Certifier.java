package services.grapheval;

import org.jspecify.annotations.Nullable;
import services.grapheval.GateBounds.MemoryCounts;
import services.grapheval.GraphEvalScorer.Point;
import services.grapheval.GraphEvalScorer.WrongRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Certifies a decision model from its end-to-end grids (JCLAW-1356, JCLAW-1366): a threshold passes when the one-sided
 * 95% Clopper-Pearson upper bound is at most 5% both on its written items' wrong share ({@code G_written}) and on its
 * trap violations ({@code G_trap}), and its recall meets the floor. Each qualifier class first walks its own
 * threshold. One run certifies when a spot-check that re-asks a share of its cases gets the same decisions back;
 * several runs must each certify. Protocol v2 is {@link Sequencer}'s; the bounds are {@link ClopperPearson}'s. Pure: no
 * I/O, no clock.
 */
public final class Certifier {

    public static final double LIMIT = ClopperPearson.LIMIT;
    public static final double CONFIDENCE_TAIL = ClopperPearson.CONFIDENCE_TAIL;
    public static final double DEFAULT_RECALL_FLOOR = 0.50;

    public static final String CERTIFIED = "certified";
    public static final String NOT_CERTIFIED = "not-certified";
    public static final String PENDING_AGREEMENT = "pending-agreement";
    public static final String PENDING_ADJUDICATION = "pending-adjudication";

    public static final String WRONG = "wrong";
    public static final String LABEL_ERROR = "label-error";
    public static final String SECOND_RUN_FAILED = "second run did not certify";
    public static final String LABELS_NEED_FIXING = "labels need fixing";
    public static final String NEEDS_SPOT_CHECK = "a single run needs its spot-check";
    public static final double SPOT_CHECK_SHARE = 0.10;

    public static final String CLASS_CERTIFIED = "certified";
    public static final String PROVISIONAL = "provisional";
    public static final String DISABLED = "disabled";
    /** A class threshold needs this many values to be evaluable at all. */
    public static final int MIN_CLASS_N = 29;
    /** From this many values a class can certify; below it, at best provisional. */
    public static final int CERTIFIED_CLASS_N = 250;
    public static final double PROVISIONAL_LIMIT = 0.10;
    public static final int PROVISIONAL_MAX_WRONG = 6;

    private Certifier() {}

    /**
     * One threshold of a walk: the grid's counts plus the bounds and whether it passed. {@code wrong} is
     * {@code G_written}'s, over the written items but noise; {@code trapBound} is {@code G_trap}'s.
     */
    public record Step(double threshold, int written, int wrong, int noise, @Nullable Double recall,
                       double upperBound, boolean passes, int trapGold, int trapViolations, double trapBound) {}

    /** One threshold of a class walk: {@code n} values written on base-right parents, {@code k} of them wrong. */
    public record ClassStep(double t, int n, int k) {}

    /**
     * One qualifier class walked: {@code threshold} is null when {@code state} is {@link #DISABLED}; {@code n},
     * {@code k} and {@code bound} are those at the threshold, else at the first evaluable step (0, 0, 1.0 with none).
     */
    public record ClassWalk(String name, @Nullable Double threshold, String state, int n, int k, double bound,
                            List<ClassStep> steps) {
        public ClassWalk {
            steps = List.copyOf(steps);
        }
    }

    /**
     * What a walked model certified, stamped with the schema and extraction fingerprints it was measured under:
     * {@code threshold} is {@code t*}, null when nothing certified.
     */
    public record Certificate(@Nullable Double threshold, List<ClassWalk> classes, String schema, String extraction) {
        public Certificate {
            classes = List.copyOf(classes);
        }
    }

    /** One run's walk; {@code threshold} is where it certified, or null with {@code failure} saying why. */
    public record Walk(List<Step> steps, @Nullable Double threshold, @Nullable String failure) {
        public Walk {
            steps = List.copyOf(steps);
        }
    }

    /** Several runs combined: the highest threshold, or null when any run failed. */
    public record Combined(@Nullable Double threshold, List<String> reasons) {
        public Combined {
            reasons = List.copyOf(reasons);
        }
    }

    /** A single run's cases asked again: how many decisions came back, and how many of them differ. */
    public record SpotCheck(int cases, int decisions, int differing) {}

    /**
     * The verdict. {@code unadjudicated} lists the wrong records at {@code threshold} with no {@code wrong} verdict;
     * {@code noiseRate} is noise / written there, in the run that set the threshold.
     */
    public record Certification(String status, @Nullable Double threshold, List<String> reasons,
                                List<WrongRecord> unadjudicated, @Nullable Double noiseRate) {
        public Certification {
            reasons = List.copyOf(reasons);
            unadjudicated = List.copyOf(unadjudicated);
        }
    }

    /**
     * Walks a class from the highest threshold with n at least {@link #MIN_CLASS_N} down while each step is certified
     * or provisional; its threshold is the lowest of that run. Disabled when no step is evaluable or the first fails.
     */
    public static ClassWalk classWalk(String name, List<ClassStep> steps) {
        return classWalk(name, steps.stream().map(s -> new ClassCounts(s.t(),
                List.of(new MemoryCounts("", s.n(), s.k(), 0, 0, 0)))).toList(), RecordBounds.INSTANCE);
    }

    /** One threshold of a class walk as each memory's counts. */
    public record ClassCounts(double t, List<MemoryCounts> counts) {
        public ClassCounts {
            counts = List.copyOf(counts);
        }
    }

    /** {@link #classWalk(String, List)} over memory counts, every bound through {@code bounds}. */
    public static ClassWalk classWalk(String name, List<ClassCounts> steps, GateBounds bounds) {
        var ordered = new ArrayList<>(steps);
        ordered.sort((a, b) -> Double.compare(b.t(), a.t()));
        ClassCounts first = null;
        ClassCounts last = null;
        String state = DISABLED;
        for (var step : ordered) {
            if (first == null) {
                if (RecordBounds.n(step.counts()) < MIN_CLASS_N) continue;
                first = step;
            }
            var passed = classState(step.counts(), bounds);
            if (passed == null) break;
            last = step;
            state = passed;
        }
        var plain = ordered.stream().map(c -> new ClassStep(c.t(), RecordBounds.n(c.counts()),
                RecordBounds.k(c.counts()))).toList();
        if (last == null) {
            var at = first;
            return new ClassWalk(name, null, DISABLED, at == null ? 0 : RecordBounds.n(at.counts()),
                    at == null ? 0 : RecordBounds.k(at.counts()),
                    at == null ? 1.0 : bounds.upper(at.counts(), CONFIDENCE_TAIL), plain);
        }
        return new ClassWalk(name, last.t(), state, RecordBounds.n(last.counts()), RecordBounds.k(last.counts()),
                bounds.upper(last.counts(), CONFIDENCE_TAIL), plain);
    }

    /** {@link #CLASS_CERTIFIED} or {@link #PROVISIONAL} when the step passes, else null. */
    private static @Nullable String classState(List<MemoryCounts> counts, GateBounds bounds) {
        int n = RecordBounds.n(counts);
        if (n >= CERTIFIED_CLASS_N) return bounds.passes(counts, LIMIT, CONFIDENCE_TAIL) ? CLASS_CERTIFIED : null;
        if (n >= MIN_CLASS_N && RecordBounds.k(counts) <= PROVISIONAL_MAX_WRONG
                && bounds.passes(counts, PROVISIONAL_LIMIT, CONFIDENCE_TAIL)) {
            return PROVISIONAL;
        }
        return null;
    }

    /**
     * One class's walks across runs: the highest threshold wins, with that run's n, k and bound; a class disabled in
     * any run is disabled.
     */
    public static ClassWalk combineClass(List<ClassWalk> runs) {
        if (runs.isEmpty()) throw new IllegalArgumentException("no class walks to combine");
        var best = runs.getFirst();
        for (var w : runs) {
            var t = w.threshold();
            if (t == null) return w;
            var top = best.threshold();
            if (top == null || t > top) best = w;
        }
        return best;
    }

    /** Null when both stamps match the running fingerprints, else the reason naming the stamp that differs. */
    public static @Nullable String check(Certificate certificate, String schemaFingerprint,
                                         String extractionFingerprint) {
        if (!certificate.schema().equals(schemaFingerprint)) {
            return "schema stamp %s differs from running %s".formatted(certificate.schema(), schemaFingerprint);
        }
        if (!certificate.extraction().equals(extractionFingerprint)) {
            return "extraction stamp %s differs from running %s".formatted(certificate.extraction(),
                    extractionFingerprint);
        }
        return null;
    }

    /**
     * Walks {@code grid} from 0.95 down, certifying at the lowest threshold where it and every one above it pass
     * {@code G_written}, {@code G_trap} and the recall floor, and stopping at the first that does not. A run with a
     * failed decision passes nowhere: what it would have written is unknown.
     */
    public static Walk walk(List<Point> grid, double recallFloor) {
        var ordered = new ArrayList<>(grid);
        ordered.sort((a, b) -> Double.compare(b.threshold(), a.threshold()));
        var steps = new ArrayList<Step>();
        Double certified = null;
        int failed = grid.isEmpty() ? 0 : grid.getFirst().failures();
        String failure = failed == 0 ? null : "%d decisions failed, and a run with a failed decision does not certify"
                .formatted(failed);
        for (var p : ordered) {
            int denominator = p.gWritten();
            double bound = ClopperPearson.upperBound(p.gWrong(), denominator);
            boolean boundOk = ClopperPearson.boundPasses(p.gWrong(), denominator);
            double trapBound = ClopperPearson.upperBound(p.trapViolations(), p.trapGold());
            boolean trapOk = ClopperPearson.boundPasses(p.trapViolations(), p.trapGold());
            var recall = p.recall();
            boolean recallOk = recall != null && recall >= recallFloor;
            boolean passes = failure == null && boundOk && trapOk && recallOk;
            steps.add(new Step(p.threshold(), p.written(), p.gWrong(), p.noise(), recall, bound, passes,
                    p.trapGold(), p.trapViolations(), trapBound));
            if (failure != null) continue;
            if (passes) {
                certified = p.threshold();
            } else if (!boundOk) {
                failure = "at %s the wrong-share upper bound %s exceeds %s (%d wrong of %d)".formatted(
                        fmt(p.threshold()), fmt(bound), fmt(LIMIT), p.gWrong(), denominator);
            } else if (!trapOk) {
                failure = "at %s the trap upper bound %s exceeds %s (%d violations of %d)".formatted(
                        fmt(p.threshold()), fmt(trapBound), fmt(LIMIT), p.trapViolations(), p.trapGold());
            } else {
                failure = "at %s recall %s is below the floor %s".formatted(fmt(p.threshold()),
                        recall == null ? "n/a" : fmt(recall), fmt(recallFloor));
            }
        }
        return new Walk(steps, certified, certified == null ? failure : null);
    }

    /** The higher threshold of the runs; any run that did not certify fails the whole. */
    public static Combined combine(List<Walk> runs) {
        var reasons = new ArrayList<String>();
        Double threshold = null;
        for (int i = 0; i < runs.size(); i++) {
            var run = runs.get(i);
            var t = run.threshold();
            if (t == null) {
                reasons.add(i == 1 ? SECOND_RUN_FAILED : "run %d did not certify".formatted(i + 1));
                if (run.failure() != null) reasons.add("run %d: %s".formatted(i + 1, run.failure()));
            } else if (threshold == null || t > threshold) {
                threshold = t;
            }
        }
        if (runs.isEmpty()) reasons.add("no runs");
        return new Combined(reasons.isEmpty() ? threshold : null, reasons);
    }

    /**
     * Applies the preconditions to the combined walk. {@code wrongAtThreshold} are the wrong records of every run at
     * the combined threshold; it is ignored when nothing certified. {@code spotCheck} is required of a single run and
     * ignored for several.
     */
    public static Certification certify(List<Walk> runs, boolean memoryChanged, boolean agreementCovered,
                                        List<WrongRecord> wrongAtThreshold, List<Adjudications.Verdict> adjudications,
                                        @Nullable SpotCheck spotCheck) {
        var combined = combine(runs);
        var threshold = combined.threshold();
        var reasons = new ArrayList<>(combined.reasons());
        boolean unconfirmed = false;
        if (runs.size() == 1) {
            if (spotCheck == null) {
                reasons.add(NEEDS_SPOT_CHECK);
                unconfirmed = true;
            } else if (spotCheck.differing() > 0) {
                reasons.add("spot-check: %d of %d decisions differ when asked again; certify with two full runs"
                        .formatted(spotCheck.differing(), spotCheck.decisions()));
                unconfirmed = true;
            }
        }
        if (memoryChanged) reasons.addFirst("a case memory changed during the run");
        if (memoryChanged || threshold == null || unconfirmed) {
            return new Certification(NOT_CERTIFIED, null, reasons, List.of(), null);
        }
        var noiseRate = noiseRate(runs, threshold);

        var wrongVerdicts = new HashSet<List<String>>();
        boolean labelError = false;
        Set<List<String>> current = new HashSet<>();
        wrongAtThreshold.forEach(w -> current.add(List.of(w.caseId(), w.record())));
        for (var a : adjudications) {
            if (a.side() != null && !a.side().equals(Adjudications.UNMATCHED)) continue;
            var key = List.of(a.caseId(), a.record());
            if (!current.contains(key)) continue;
            var verdict = a.on(false);
            if (verdict.equals(LABEL_ERROR)) labelError = true;
            else if (verdict.equals(WRONG)) wrongVerdicts.add(key);
        }
        var unadjudicated = wrongAtThreshold.stream()
                .filter(w -> !wrongVerdicts.contains(List.of(w.caseId(), w.record()))).toList();

        if (labelError) {
            reasons.add(LABELS_NEED_FIXING);
            return new Certification(NOT_CERTIFIED, null, reasons, unadjudicated, noiseRate);
        }
        if (!agreementCovered) {
            reasons.add("blind second labels do not cover the selected cases");
            if (!unadjudicated.isEmpty()) reasons.add("%d wrong records await adjudication".formatted(unadjudicated.size()));
            return new Certification(PENDING_AGREEMENT, threshold, reasons, unadjudicated, noiseRate);
        }
        if (!unadjudicated.isEmpty()) {
            reasons.add("%d wrong records await adjudication".formatted(unadjudicated.size()));
            return new Certification(PENDING_ADJUDICATION, threshold, reasons, unadjudicated, noiseRate);
        }
        return new Certification(CERTIFIED, threshold, reasons, List.of(), noiseRate);
    }

    private static @Nullable Double noiseRate(List<Walk> runs, double threshold) {
        for (var run : runs) {
            if (!Double.valueOf(threshold).equals(run.threshold())) continue;
            for (var step : run.steps()) {
                if (step.threshold() == threshold) return step.written() == 0 ? null : (double) step.noise() / step.written();
            }
        }
        return null;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
