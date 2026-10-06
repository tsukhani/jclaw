package services.grapheval;

import org.jspecify.annotations.Nullable;
import services.grapheval.Configuration.ClassSetting;
import services.grapheval.GateBounds.MemoryCounts;
import services.grapheval.GraphEvalScorer.Point;
import services.grapheval.GraphEvalScorer.WrongRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/**
 * Certifies a decision model from its end-to-end grids (JCLAW-1356, JCLAW-1366): a threshold passes when the one-sided
 * 95% Clopper-Pearson upper bound is at most 5% both on its written items' wrong share ({@code G_written}) and on its
 * trap violations ({@code G_trap}), and its recall meets the floor. Each qualifier class first walks its own
 * threshold. One run certifies when a spot-check that re-asks a share of its cases gets the same decisions back;
 * several runs must each certify. Protocol v2 (JCLAW-1368), {@link #sequence} and {@link #verdict}, certifies Terms
 * first, then each relation type by its own gate, then the classes and pooled gates over what is enabled, with every
 * bound read through {@link GateBounds}. Pure: no I/O, no clock.
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
        return upperBound(wrong, written, CONFIDENCE_TAIL);
    }

    /** The one-sided Clopper-Pearson upper bound at {@code tail}: the p at which P(X <= wrong) is {@code tail}. */
    public static double upperBound(int wrong, int written, double tail) {
        if (written <= 0 || wrong >= written) return 1.0;
        double lo = (double) wrong / written;
        double hi = 1.0;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (logCdf(wrong, written, mid) > Math.log(tail)) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /**
     * The one-sided Clopper-Pearson lower bound at {@code tail} on {@code right} of {@code total}: the p at which
     * P(X >= right) is {@code tail}. 0 when nothing is right or nothing is labelled.
     */
    public static double lowerBound(int right, int total, double tail) {
        if (total <= 0 || right <= 0) return 0.0;
        if (right >= total) return Math.pow(tail, 1.0 / total);
        double lo = 0.0;
        double hi = (double) right / total;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            // P(X >= right) is P(Y <= total - right) for Y = total - X ~ Binomial(total, 1 - p), rising in p.
            if (logCdf(total - right, total, 1 - mid) < Math.log(tail)) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** Whether the bound is within {@link #LIMIT}; decided on the CDF itself, so the boundary is exact. */
    public static boolean boundPasses(int wrong, int written) {
        return boundPasses(wrong, written, LIMIT, CONFIDENCE_TAIL);
    }

    /** Whether the bound on {@code k} wrong of {@code n} is within {@code limit}. */
    public static boolean boundPasses(int k, int n, double limit) {
        return boundPasses(k, n, limit, CONFIDENCE_TAIL);
    }

    /** Whether the bound at {@code tail} on {@code k} wrong of {@code n} is within {@code limit}. */
    public static boolean boundPasses(int k, int n, double limit, double tail) {
        return n > 0 && k < n && logCdf(k, n, limit) <= Math.log(tail);
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

    /** log P(X <= x) for X ~ Binomial(n, p), summed in log space; the binomial coefficient is built up term by term. */
    static double logCdf(int x, int n, double p) {
        double logP = Math.log(p);
        double logQ = Math.log1p(-p);
        double max = Double.NEGATIVE_INFINITY;
        var terms = new double[x + 1];
        double logChoose = 0;
        for (int k = 0; k <= x; k++) {
            if (k > 0) logChoose += Math.log((double) n - k + 1) - Math.log(k);
            terms[k] = logChoose + k * logP + (n - k) * logQ;
            max = Math.max(max, terms[k]);
        }
        double sum = 0;
        for (double term : terms) sum += Math.exp(term - max);
        return max + Math.log(sum);
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
            if (!a.side().equals(Adjudications.UNMATCHED)) continue;
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

    // ---- Protocol v2 (JCLAW-1368): Terms first, one gate per relation type, then the classes and pooled gates. ----

    public static final String TERMS = GraphEvalScorer.TERMS;
    public static final String G_WRITTEN = "G_written";
    public static final String G_TRAP = "G_trap";
    public static final String ON = "on";
    public static final String OFF = "off";
    public static final String VOID_RUN = "void run";
    public static final String NEEDS_SECOND_RUN = "needs second run";
    /** The qualifier classes v2 walks over the enabled configuration, in order, under {@link Configuration}'s names. */
    public static final List<String> V2_CLASSES = List.of(Configuration.STATUS, Configuration.TIME,
            Configuration.NEGATION);

    /** P(pass) at the gate's n when 1%, 2% and 3% of its records are truly wrong. */
    public record Power(double at1, double at2, double at3) {
        static Power of(GateBounds bounds, List<MemoryCounts> writing) {
            return new Power(bounds.power(writing, 0.01, LIMIT, CONFIDENCE_TAIL),
                    bounds.power(writing, 0.02, LIMIT, CONFIDENCE_TAIL),
                    bounds.power(writing, 0.03, LIMIT, CONFIDENCE_TAIL));
        }
    }

    /**
     * One gate's counts at one configuration: {@code writing} the memories that wrote at least one of its records,
     * {@code labelled} every memory with gold for it.
     */
    public record GateCounts(List<MemoryCounts> writing, List<MemoryCounts> labelled) {
        public static final GateCounts EMPTY = new GateCounts(List.of(), List.of());

        public GateCounts {
            writing = List.copyOf(writing);
            labelled = List.copyOf(labelled);
        }
    }

    /**
     * Everything the evaluator scores at one configuration: each gate ({@link #TERMS} and every relation type), each
     * qualifier class, and the two pooled gates. An off relation writes into none of them.
     */
    public record Evaluation(Map<String, GateCounts> gates, Map<String, List<MemoryCounts>> classes,
                             List<MemoryCounts> written, List<MemoryCounts> trap) {
        public Evaluation {
            gates = Map.copyOf(gates);
            classes = Map.copyOf(classes);
            written = List.copyOf(written);
            trap = List.copyOf(trap);
        }
    }

    public record GateStep(double t, int n, int k, double bound, boolean passes) {}

    /**
     * A Term or relation gate walked: {@code threshold} is null when it is {@link #OFF}; {@code n}, {@code k},
     * {@code bound}, {@code recallLower} and {@code power} are at its final threshold, else at its first step.
     */
    public record Gate(String name, String state, @Nullable Double threshold, int n, int k, double bound,
                       @Nullable Double recallLower, Power power, List<GateStep> steps, @Nullable String reason) {
        public Gate {
            steps = List.copyOf(steps);
        }

        Gate backedOff(String why) {
            return new Gate(name, OFF, null, n, k, bound, recallLower, power, steps, why);
        }
    }

    public record ClassGate(String name, String state, @Nullable Double threshold, int n, int k, double bound,
                            Power power, List<ClassStep> steps) {
        public ClassGate {
            steps = List.copyOf(steps);
        }
    }

    public record Pooled(String name, int n, int k, double bound, boolean passes, Power power) {}

    /** One back-off step: {@code relation} switched off because {@code gate} failed with {@code k} of {@code n}. */
    public record BackOff(int step, String relation, String gate, int n, int k, double bound) {}

    /**
     * The v2 sequencing. {@code reached} is the configuration it ends at: Terms at the starting threshold alone when
     * the Term gate is off. {@code written} and {@code trap} are null when the Term gate is off, since nothing after
     * it is evaluated. {@code passed} is false when the Term gate is off or a pooled gate fails with no relation on.
     */
    public record Sequencing(double startingThreshold, Gate terms, List<Gate> relations, List<ClassGate> classes,
                             @Nullable Pooled written, @Nullable Pooled trap, List<BackOff> backOff,
                             Configuration reached, boolean passed, List<String> reasons) {
        public Sequencing {
            relations = List.copyOf(relations);
            classes = List.copyOf(classes);
            backOff = List.copyOf(backOff);
            reasons = List.copyOf(reasons);
        }
    }

    /** The grid thresholds from {@code start} down to {@code floor}, highest first; never one above {@code start}. */
    static List<Double> grid(double start, double floor) {
        return GraphEvalScorer.THRESHOLDS.stream().filter(t -> t <= start + 1e-9 && t >= floor - 1e-9).toList();
    }

    /**
     * Sequences the gates: Terms walked from {@code start} down while their bound passes and recall-checked once at
     * the bottom of that run; each relation in {@code relationOrder} walked the same way, never below the Term
     * threshold; then the classes from the highest threshold at or below {@code start} with n of at least
     * {@link #MIN_CLASS_N}, and {@code G_written} and {@code G_trap}. While a pooled gate fails, the relation latest in
     * {@code relationOrder} among those on is switched off and the classes and pooled gates evaluated again.
     * {@code evaluate} is pure: it re-scores stored decisions at a configuration.
     */
    public static Sequencing sequence(GateBounds bounds, double start, List<String> relationOrder, double recallFloor,
                                      Function<Configuration, Evaluation> evaluate) {
        var reasons = new ArrayList<String>();
        var terms = walkGate(TERMS, grid(start, 0), t -> gateAt(evaluate, new Configuration(t, new TreeMap<>(),
                new TreeMap<>()), TERMS), bounds, recallFloor);
        if (terms.state().equals(OFF)) {
            reasons.add("Terms gate: " + terms.reason());
            return new Sequencing(start, terms, List.of(), List.of(), null, null, List.of(),
                    new Configuration(start, new TreeMap<>(), new TreeMap<>()), false, reasons);
        }
        double termsAt = Objects.requireNonNull(terms.threshold());
        // A relation gate counts only its own type's records, so one evaluation at t serves every relation.
        var byThreshold = new HashMap<Double, Evaluation>();
        DoubleFunction<Evaluation> relationsAt = t -> byThreshold.computeIfAbsent(t, x -> {
            var all = new TreeMap<String, Double>();
            relationOrder.forEach(r -> all.put(r, x));
            return evaluate.apply(new Configuration(termsAt, all, new TreeMap<>()));
        });
        var relations = new ArrayList<Gate>();
        for (var type : relationOrder) {
            relations.add(walkGate(type, grid(start, termsAt),
                    t -> relationsAt.apply(t).gates().getOrDefault(type, GateCounts.EMPTY), bounds, recallFloor));
        }
        var backOff = new ArrayList<BackOff>();
        while (true) {
            var enabled = new TreeMap<String, Double>();
            for (var g : relations) {
                if (g.state().equals(ON)) enabled.put(g.name(), Objects.requireNonNull(g.threshold()));
            }
            var base = new Configuration(termsAt, enabled, new TreeMap<>());
            var classes = walkClasses(base, grid(start, 0), evaluate, bounds);
            var settings = new TreeMap<String, ClassSetting>();
            for (var c : classes) settings.put(c.name(), new ClassSetting(c.state(), c.threshold()));
            var reached = new Configuration(termsAt, enabled, settings);
            var at = evaluate.apply(reached);
            var written = pooled(G_WRITTEN, at.written(), bounds);
            var trap = pooled(G_TRAP, at.trap(), bounds);
            if (written.passes() && trap.passes()) {
                return new Sequencing(start, terms, relations, classes, written, trap, backOff, reached, true, reasons);
            }
            var failing = written.passes() ? trap : written;
            String last = null;
            for (int i = relationOrder.size() - 1; i >= 0 && last == null; i--) {
                if (enabled.containsKey(relationOrder.get(i))) last = relationOrder.get(i);
            }
            if (last == null) {
                reasons.add("%s fails with no relation on: %d wrong of %d, bound %s".formatted(failing.name(),
                        failing.k(), failing.n(), fmt(failing.bound())));
                return new Sequencing(start, terms, relations, classes, written, trap, backOff, reached, false,
                        reasons);
            }
            backOff.add(new BackOff(backOff.size() + 1, last, failing.name(), failing.n(), failing.k(),
                    failing.bound()));
            for (int i = 0; i < relations.size(); i++) {
                if (relations.get(i).name().equals(last)) {
                    relations.set(i, relations.get(i).backedOff("switched off by the back-off: %s %d wrong of %d"
                            .formatted(failing.name(), failing.k(), failing.n())));
                }
            }
        }
    }

    private static GateCounts gateAt(Function<Configuration, Evaluation> evaluate, Configuration c, String gate) {
        return evaluate.apply(c).gates().getOrDefault(gate, GateCounts.EMPTY);
    }

    private static Gate walkGate(String name, List<Double> grid, DoubleFunction<GateCounts> at, GateBounds bounds,
                                 double recallFloor) {
        var steps = new ArrayList<GateStep>();
        GateCounts first = null;
        GateCounts last = null;
        Double threshold = null;
        for (var t : grid) {
            var c = at.apply(t);
            boolean passes = bounds.passes(c.writing(), LIMIT, CONFIDENCE_TAIL);
            steps.add(new GateStep(t, RecordBounds.n(c.writing()), RecordBounds.k(c.writing()),
                    bounds.upper(c.writing(), CONFIDENCE_TAIL), passes));
            if (first == null) first = c;
            if (!passes) break;
            threshold = t;
            last = c;
        }
        if (first == null) {
            return new Gate(name, OFF, null, 0, 0, 1.0, null, Power.of(bounds, List.of()), steps,
                    "no threshold at or below the starting threshold to evaluate");
        }
        if (threshold == null || last == null) {
            var step = steps.getFirst();
            return new Gate(name, OFF, null, step.n(), step.k(), step.bound(),
                    bounds.recallLower(first.labelled(), CONFIDENCE_TAIL), Power.of(bounds, first.writing()), steps,
                    "%s fails its bound at %s: %d wrong of %d, bound %s".formatted(name, fmt(step.t()), step.k(),
                            step.n(), fmt(step.bound())));
        }
        double recall = bounds.recallLower(last.labelled(), CONFIDENCE_TAIL);
        var step = steps.get(steps.size() - (steps.getLast().passes() ? 1 : 2));
        var power = Power.of(bounds, last.writing());
        if (recall < recallFloor) {
            return new Gate(name, OFF, null, step.n(), step.k(), step.bound(), recall, power, steps,
                    "%s recall lower bound %s is below the floor %s at %s (%d right of %d)".formatted(name,
                            fmt(recall), fmt(recallFloor), fmt(threshold), right(last.labelled()),
                            gold(last.labelled())));
        }
        return new Gate(name, ON, threshold, step.n(), step.k(), step.bound(), recall, power, steps, null);
    }

    private static int right(List<MemoryCounts> labelled) {
        return labelled.stream().mapToInt(MemoryCounts::right).sum();
    }

    private static int gold(List<MemoryCounts> labelled) {
        return labelled.stream().mapToInt(MemoryCounts::gold).sum();
    }

    /** Status, then time with status at its walked threshold, then negation with both, over {@code base}. */
    private static List<ClassGate> walkClasses(Configuration base, List<Double> grid,
                                               Function<Configuration, Evaluation> evaluate, GateBounds bounds) {
        var settings = new TreeMap<String, ClassSetting>();
        var out = new ArrayList<ClassGate>();
        for (var name : V2_CLASSES) {
            var steps = new ArrayList<ClassCounts>();
            var counts = new HashMap<Double, List<MemoryCounts>>();
            for (var t : grid) {
                var with = new TreeMap<>(settings);
                with.put(name, new ClassSetting(PROVISIONAL, t));
                var c = evaluate.apply(new Configuration(base.terms(), base.relations(), with)).classes()
                        .getOrDefault(name, List.of());
                steps.add(new ClassCounts(t, c));
                counts.put(t, c);
            }
            var walk = classWalk(name, steps, bounds);
            var at = walk.threshold();
            var power = Power.of(bounds, at == null ? List.of() : counts.getOrDefault(at, List.of()));
            out.add(new ClassGate(name, walk.state(), at, walk.n(), walk.k(), walk.bound(), power, walk.steps()));
            settings.put(name, new ClassSetting(walk.state(), at));
        }
        return out;
    }

    private static Pooled pooled(String name, List<MemoryCounts> counts, GateBounds bounds) {
        return new Pooled(name, RecordBounds.n(counts), RecordBounds.k(counts), bounds.upper(counts, CONFIDENCE_TAIL),
                bounds.passes(counts, LIMIT, CONFIDENCE_TAIL), Power.of(bounds, counts));
    }

    /**
     * What the v2 verdict reads besides the sequencing. {@code spotCheckDiffered} is a single run's; {@code timeline}
     * the sequence harness's overall result; {@code unjudged} the records in scope with no verdict and
     * {@code uncheckedMarked} the model verdicts marked for an operator check that have none.
     */
    public record VerdictInputs(boolean memoryChanged, int failedDecisions, boolean spotCheckDiffered,
                                boolean labelError, @Nullable String timeline, boolean agreementCovered, int unjudged,
                                int uncheckedMarked) {}

    public record Verdict(String status, List<String> reasons) {
        public Verdict {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * The v2 verdict, first match wins: a changed memory, any failed decision ({@link #VOID_RUN}), a single run's
     * differing spot-check, a {@code label-error} in scope, the Term gate off or a pooled gate failing with no
     * relation on, a failed timeline, missing second labels ({@link #PENDING_AGREEMENT}), and any unjudged record or
     * unchecked marked model verdict ({@link #PENDING_ADJUDICATION}); otherwise {@link #CERTIFIED}. {@code sequencing}
     * is null on a void run.
     */
    public static Verdict verdict(@Nullable Sequencing sequencing, VerdictInputs in) {
        if (in.memoryChanged()) return new Verdict(NOT_CERTIFIED, List.of("a case memory changed during the run"));
        if (in.failedDecisions() > 0 || sequencing == null) {
            return new Verdict(NOT_CERTIFIED, List.of(VOID_RUN, "%d decisions failed".formatted(in.failedDecisions())));
        }
        if (in.spotCheckDiffered()) {
            return new Verdict(NOT_CERTIFIED, List.of(NEEDS_SECOND_RUN,
                    "the spot-check heard different answers; certify with runs 2"));
        }
        if (in.labelError()) return new Verdict(NOT_CERTIFIED, List.of(LABELS_NEED_FIXING));
        if (!sequencing.passed()) return new Verdict(NOT_CERTIFIED, sequencing.reasons());
        if (SequenceScorer.FAILED.equals(in.timeline())) {
            return new Verdict(NOT_CERTIFIED, List.of("the sequence timeline failed"));
        }
        if (!in.agreementCovered()) {
            return new Verdict(PENDING_AGREEMENT, List.of("blind second labels do not cover the split's blind subset"));
        }
        if (in.unjudged() > 0 || in.uncheckedMarked() > 0) {
            var reasons = new ArrayList<String>();
            if (in.unjudged() > 0) reasons.add("%d records await adjudication".formatted(in.unjudged()));
            if (in.uncheckedMarked() > 0) {
                reasons.add("%d model verdicts await an operator check".formatted(in.uncheckedMarked()));
            }
            return new Verdict(PENDING_ADJUDICATION, reasons);
        }
        return new Verdict(CERTIFIED, List.of());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
