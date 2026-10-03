package services.graphspike;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import services.graphspike.GraphSpikeScorer.Point;
import services.graphspike.GraphSpikeScorer.WrongRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Certifies a decision model from its end-to-end grids (JCLAW-1356): a threshold passes when the one-sided 95%
 * Clopper-Pearson upper bound on its wrong share is at most 5% and its recall meets the floor. Pure: no I/O, no clock.
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

    private Certifier() {}

    /** One threshold of a walk: the grid's counts plus the bound and whether it passed. */
    public record Step(double threshold, int written, int wrong, int noise, @Nullable Double recall,
                       double upperBound, boolean passes) {}

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
        return written > 0 && wrong < written && logCdf(wrong, written, LIMIT) <= Math.log(CONFIDENCE_TAIL);
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
     * Walks {@code grid} from 0.95 down, certifying at the lowest threshold where it and every one above it pass,
     * and stopping at the first that does not.
     */
    public static Walk walk(List<Point> grid, double recallFloor) {
        var ordered = new ArrayList<>(grid);
        ordered.sort((a, b) -> Double.compare(b.threshold(), a.threshold()));
        var steps = new ArrayList<Step>();
        Double certified = null;
        String failure = null;
        for (var p : ordered) {
            int denominator = p.written() - p.noise();
            double bound = upperBound(p.wrong(), denominator);
            boolean boundOk = boundPasses(p.wrong(), denominator);
            var recall = p.recall();
            boolean recallOk = recall != null && recall >= recallFloor;
            boolean passes = failure == null && boundOk && recallOk;
            steps.add(new Step(p.threshold(), p.written(), p.wrong(), p.noise(), recall, bound, passes));
            if (failure != null) continue;
            if (passes) {
                certified = p.threshold();
            } else {
                failure = boundOk
                        ? "at %s recall %s is below the floor %s".formatted(fmt(p.threshold()),
                                recall == null ? "n/a" : fmt(recall), fmt(recallFloor))
                        : "at %s the wrong-share upper bound %s exceeds %s (%d wrong of %d)".formatted(
                                fmt(p.threshold()), fmt(bound), fmt(LIMIT), p.wrong(), denominator);
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
     * the combined threshold; it is ignored when nothing certified.
     */
    public static Certification certify(List<Walk> runs, boolean memoryChanged, boolean agreementCovered,
                                        List<WrongRecord> wrongAtThreshold, List<Adjudication> adjudications) {
        var combined = combine(runs);
        var threshold = combined.threshold();
        var reasons = new ArrayList<>(combined.reasons());
        if (memoryChanged) reasons.addFirst("a case memory changed during the run");
        if (memoryChanged || threshold == null) {
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
            return new Certification(NOT_CERTIFIED, threshold, reasons, unadjudicated, noiseRate);
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
