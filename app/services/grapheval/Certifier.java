package services.grapheval;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
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
 * several runs must each certify. Pure: no I/O, no clock.
 */
public final class Certifier {

    public static final double LIMIT = 0.05;
    public static final double CONFIDENCE_TAIL = 0.05;
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

    public record Adjudication(String caseId, String record, String verdict, String note) {}

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
     * The smallest p at which seeing at most {@code wrong} wrong of {@code written} has probability 5%: the one-sided
     * 95% Clopper-Pearson upper bound. 1.0 when nothing was written.
     */
    public static double upperBound(int wrong, int written) {
        if (written <= 0 || wrong >= written) return 1.0;
        double lo = (double) wrong / written;
        double hi = 1.0;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (logCdf(wrong, written, mid) > Math.log(CONFIDENCE_TAIL)) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /** Whether the bound is within {@link #LIMIT}; decided on the CDF itself, so the boundary is exact. */
    public static boolean boundPasses(int wrong, int written) {
        return boundPasses(wrong, written, LIMIT);
    }

    /** Whether the bound on {@code k} wrong of {@code n} is within {@code limit}. */
    public static boolean boundPasses(int k, int n, double limit) {
        return n > 0 && k < n && logCdf(k, n, limit) <= Math.log(CONFIDENCE_TAIL);
    }

    /**
     * Walks a class from the highest threshold with n at least {@link #MIN_CLASS_N} down while each step is certified
     * or provisional; its threshold is the lowest of that run. Disabled when no step is evaluable or the first fails.
     */
    public static ClassWalk classWalk(String name, List<ClassStep> steps) {
        var ordered = new ArrayList<>(steps);
        ordered.sort((a, b) -> Double.compare(b.t(), a.t()));
        ClassStep first = null;
        ClassStep last = null;
        String state = DISABLED;
        for (var step : ordered) {
            if (first == null) {
                if (step.n() < MIN_CLASS_N) continue;
                first = step;
            }
            var passed = classState(step);
            if (passed == null) break;
            last = step;
            state = passed;
        }
        if (last == null) {
            var at = first;
            return new ClassWalk(name, null, DISABLED, at == null ? 0 : at.n(), at == null ? 0 : at.k(),
                    at == null ? 1.0 : upperBound(at.k(), at.n()), ordered);
        }
        return new ClassWalk(name, last.t(), state, last.n(), last.k(), upperBound(last.k(), last.n()), ordered);
    }

    /** {@link #CLASS_CERTIFIED} or {@link #PROVISIONAL} when the step passes, else null. */
    private static @Nullable String classState(ClassStep s) {
        if (s.n() >= CERTIFIED_CLASS_N) return boundPasses(s.k(), s.n(), LIMIT) ? CLASS_CERTIFIED : null;
        if (s.n() >= MIN_CLASS_N && s.k() <= PROVISIONAL_MAX_WRONG && boundPasses(s.k(), s.n(), PROVISIONAL_LIMIT)) {
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

    /** log P(X <= x) for X ~ Binomial(n, p), summed in log space. */
    static double logCdf(int x, int n, double p) {
        double logP = Math.log(p);
        double logQ = Math.log1p(-p);
        double max = Double.NEGATIVE_INFINITY;
        var terms = new double[x + 1];
        for (int k = 0; k <= x; k++) {
            terms[k] = logChoose(n, k) + k * logP + (n - k) * logQ;
            max = Math.max(max, terms[k]);
        }
        double sum = 0;
        for (double term : terms) sum += Math.exp(term - max);
        return max + Math.log(sum);
    }

    private static double logChoose(int n, int k) {
        double out = 0;
        for (int i = 1; i <= k; i++) out += Math.log(n - k + i) - Math.log(i);
        return out;
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
            double bound = upperBound(p.gWrong(), denominator);
            boolean boundOk = boundPasses(p.gWrong(), denominator);
            double trapBound = upperBound(p.trapViolations(), p.trapGold());
            boolean trapOk = boundPasses(p.trapViolations(), p.trapGold());
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
                                        List<WrongRecord> wrongAtThreshold, List<Adjudication> adjudications,
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
            var key = List.of(a.caseId(), a.record());
            if (!current.contains(key)) continue;
            if (a.verdict().equals(LABEL_ERROR)) labelError = true;
            else if (a.verdict().equals(WRONG)) wrongVerdicts.add(key);
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

    /**
     * The verdicts in {@code adjudications.json}.
     *
     * @throws IllegalArgumentException on a malformed file or an unknown verdict
     */
    public static List<Adjudication> parseAdjudications(String json) {
        var out = new ArrayList<Adjudication>();
        try {
            var root = JsonParser.parseString(json);
            if (!root.isJsonArray()) throw new IllegalArgumentException("adjudications: the document must be an array");
            int i = 0;
            for (var e : root.getAsJsonArray()) {
                var where = "adjudication #" + i++;
                if (!e.isJsonObject()) throw new IllegalArgumentException(where + ": must be an object");
                var o = e.getAsJsonObject();
                var verdict = GraphCases.text(o, "verdict", where);
                if (!verdict.equals(WRONG) && !verdict.equals(LABEL_ERROR)) {
                    throw new IllegalArgumentException(where + ": verdict must be 'wrong' or 'label-error'");
                }
                var note = o.has("note") && o.get("note").isJsonPrimitive() ? o.get("note").getAsString() : "";
                out.add(new Adjudication(GraphCases.text(o, "caseId", where), GraphCases.text(o, "record", where),
                        verdict, note));
            }
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("adjudications are not valid JSON: " + e.getMessage(), e);
        }
        return List.copyOf(out);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
