package services.grapheval;

import org.jspecify.annotations.Nullable;
import services.grapheval.Certifier.ClassCounts;
import services.grapheval.Certifier.ClassStep;
import services.grapheval.Configuration.ClassSetting;
import services.grapheval.GateBounds.MemoryCounts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import static services.grapheval.Certifier.CERTIFIED;
import static services.grapheval.Certifier.CONFIDENCE_TAIL;
import static services.grapheval.Certifier.LABELS_NEED_FIXING;
import static services.grapheval.Certifier.LIMIT;
import static services.grapheval.Certifier.MIN_CLASS_N;
import static services.grapheval.Certifier.NOT_CERTIFIED;
import static services.grapheval.Certifier.PENDING_ADJUDICATION;
import static services.grapheval.Certifier.PENDING_AGREEMENT;
import static services.grapheval.Certifier.PROVISIONAL;
import static services.grapheval.Certifier.classWalk;

/**
 * Protocol v2 (JCLAW-1368): {@link #sequence} certifies Terms first, then each relation type by its own gate, then the
 * classes and pooled gates over what is enabled, with every bound read through {@link GateBounds}; {@link #verdict}
 * reads the result. Pure: no I/O, no clock.
 */
public final class Sequencer {

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
    /** Bonferroni over Terms and the 12 relation gates for each of the 3 candidate models: 39 tests (JCLAW-1369). */
    public static final double BASE_GATE_TAIL = CONFIDENCE_TAIL / 39;
    /** The wrong counts each {@link Requirement} gives the records and memories needed at. */
    public static final List<Integer> NEEDED_WRONG = List.of(0, 1, 2, 3, 5);

    private Sequencer() {}

    /** P(pass) at the gate's n when 1%, 2% and 3% of its records are truly wrong. */
    public record Power(double at1, double at2, double at3) {
        static Power of(GateBounds bounds, List<MemoryCounts> writing) {
            return of(bounds, writing, CONFIDENCE_TAIL);
        }

        static Power of(GateBounds bounds, List<MemoryCounts> writing, double tail) {
            return new Power(bounds.power(writing, 0.01, LIMIT, tail), bounds.power(writing, 0.02, LIMIT, tail),
                    bounds.power(writing, 0.03, LIMIT, tail));
        }
    }

    /** The units a bound counts n and k in: writing memories for {@link MemoryBounds}, records for any other. */
    private record Units(ToIntFunction<List<MemoryCounts>> n, ToIntFunction<List<MemoryCounts>> k, boolean byMemory) {
        static final Units RECORDS = new Units(RecordBounds::n, RecordBounds::k, false);
        static final Units MEMORIES = new Units(MemoryBounds::n, MemoryBounds::k, true);

        static Units of(GateBounds bounds) {
            return bounds instanceof MemoryBounds ? MEMORIES : RECORDS;
        }
    }

    /** The set that certifies {@code gate} (JCLAW-1369): synthetic strata for the classes and G_trap, else live. */
    public static String certifyingSet(String gate) {
        return V2_CLASSES.contains(gate) || gate.equals(G_TRAP) ? CertificationSplit.SET_CASES
                : CertificationSplit.SET_HELDOUT;
    }

    static String notEvaluable(String gate) {
        return "%s not evaluable: its certifying set %s has no data in this run".formatted(gate, certifyingSet(gate));
    }

    /**
     * One gate's counts at one configuration: {@code writing} the memories that wrote at least one of its records,
     * {@code labeled} every memory with gold for it.
     */
    public record GateCounts(List<MemoryCounts> writing, List<MemoryCounts> labeled) {
        public static final GateCounts EMPTY = new GateCounts(List.of(), List.of());

        public GateCounts {
            writing = List.copyOf(writing);
            labeled = List.copyOf(labeled);
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

    /** What passes with {@code wrong} wrong: {@code memories} is null for a record-level gate. */
    public record Needed(int wrong, int records, @Nullable Integer memories) {}

    /**
     * One gate on its certifying {@code set} at its {@code tail}, for JCLAW-1375 to size the live sample from: what it
     * observed where its n and k are read, and what it needs at each of {@link #NEEDED_WRONG}. {@code reachable} says
     * whether its memories with gold reach the zero-wrong minimum, null for a record-level gate.
     */
    public record Requirement(String name, String set, double tail, int writtenRecords, int writingMemories,
                              int goldMemories, List<Needed> needed, @Nullable Boolean reachable) {
        public Requirement {
            needed = List.copyOf(needed);
        }
    }

    /**
     * The v2 sequencing. {@code reached} is the configuration it ends at: Terms at the starting threshold alone when
     * the Term gate is off. {@code written} and {@code trap} are null when the Term gate is off, since nothing after
     * it is evaluated. {@code passed} is false when the Term gate is off, a pooled gate is not evaluable, or a pooled
     * gate fails with no relation on. {@code requirements} has one entry per gate, always every gate: Terms, the
     * relations in order, the classes, G_written and G_trap.
     */
    public record Sequencing(double startingThreshold, Gate terms, List<Gate> relations, List<ClassGate> classes,
                             @Nullable Pooled written, @Nullable Pooled trap, List<BackOff> backOff,
                             Configuration reached, boolean passed, List<String> reasons,
                             List<Requirement> requirements) {
        public Sequencing {
            relations = List.copyOf(relations);
            classes = List.copyOf(classes);
            backOff = List.copyOf(backOff);
            reasons = List.copyOf(reasons);
            requirements = List.copyOf(requirements);
        }
    }

    /** The grid thresholds from {@code start} down to {@code floor}, highest first; never one above {@code start}. */
    static List<Double> grid(double start, double floor) {
        return GraphEvalScorer.THRESHOLDS.stream().filter(t -> t <= start + 1e-9 && t >= floor - 1e-9).toList();
    }

    /** {@link #sequence(GateBounds, String, double, List, double, Function)} for a development run: nothing routed. */
    public static Sequencing sequence(GateBounds bounds, double start, List<String> relationOrder, double recallFloor,
                                      Function<Configuration, Evaluation> evaluate) {
        return sequence(bounds, null, start, relationOrder, recallFloor, evaluate);
    }

    /**
     * Sequences the gates: Terms walked from {@code start} down while their bound passes at {@link #BASE_GATE_TAIL}
     * and recall-checked once at the bottom of that run; each relation in {@code relationOrder} walked the same way,
     * never below the Term threshold; then the classes from the highest threshold at or below {@code start} with n of
     * at least {@link Certifier#MIN_CLASS_N}, and {@code G_written} and {@code G_trap}. While a pooled gate fails, the relation
     * latest in {@code relationOrder} among those on is switched off and the classes and pooled gates evaluated again.
     * {@code set} is the run's certifying set, null for a development run: with one, a Term or relation gate with no
     * writing memory is off and a pooled gate with no data fails at once, each as not evaluable. {@code evaluate} is
     * pure: it re-scores stored decisions at a configuration.
     */
    public static Sequencing sequence(GateBounds bounds, @Nullable String set, double start,
                                      List<String> relationOrder, double recallFloor,
                                      Function<Configuration, Evaluation> evaluate) {
        boolean routed = set != null;
        var reasons = new ArrayList<String>();
        var requirements = new ArrayList<Requirement>();
        var termsWalk = walkGate(TERMS, grid(start, 0), t -> gateAt(evaluate, new Configuration(t, new TreeMap<>(),
                new TreeMap<>()), TERMS), bounds, recallFloor, routed);
        var terms = termsWalk.gate();
        requirements.add(termsWalk.requirement());
        if (terms.state().equals(OFF)) {
            reasons.add("Terms gate: " + terms.reason());
            requirements.addAll(unwalkedRequirements(bounds, start, relationOrder, evaluate));
            return new Sequencing(start, terms, List.of(), List.of(), null, null, List.of(),
                    new Configuration(start, new TreeMap<>(), new TreeMap<>()), false, reasons, requirements);
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
            var walked = walkGate(type, grid(start, termsAt),
                    t -> relationsAt.apply(t).gates().getOrDefault(type, GateCounts.EMPTY), bounds, recallFloor, routed);
            relations.add(walked.gate());
            requirements.add(walked.requirement());
        }
        var backOff = new ArrayList<BackOff>();
        while (true) {
            var enabled = new TreeMap<String, Double>();
            for (var g : relations) {
                if (g.state().equals(ON)) enabled.put(g.name(), Objects.requireNonNull(g.threshold()));
            }
            var base = new Configuration(termsAt, enabled, new TreeMap<>());
            var walkedClasses = walkClasses(base, grid(start, 0), evaluate);
            var classes = walkedClasses.gates();
            var settings = new TreeMap<String, ClassSetting>();
            for (var c : classes) settings.put(c.name(), new ClassSetting(c.state(), c.threshold()));
            var reached = new Configuration(termsAt, enabled, settings);
            var at = evaluate.apply(reached);
            var written = pooled(G_WRITTEN, at.written(), bounds);
            var trap = pooled(G_TRAP, at.trap(), RecordBounds.INSTANCE);
            var all = new ArrayList<>(requirements);
            all.addAll(walkedClasses.requirements());
            all.addAll(pooledRequirements(at, bounds));
            if (routed && (written.n() == 0 || trap.n() == 0)) {
                // No back-off: switching a relation off cannot give a pooled gate data.
                for (var p : List.of(written, trap)) {
                    if (p.n() == 0) reasons.add(notEvaluable(p.name()));
                    else if (!p.passes()) reasons.add("%s fails: %d wrong of %d, bound %s".formatted(p.name(), p.k(),
                            p.n(), fmt(p.bound())));
                }
                return new Sequencing(start, terms, relations, classes, written, trap, backOff, reached, false,
                        reasons, all);
            }
            if (written.passes() && trap.passes()) {
                return new Sequencing(start, terms, relations, classes, written, trap, backOff, reached, true, reasons,
                        all);
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
                        reasons, all);
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

    private record Walked(Gate gate, Requirement requirement) {}

    /** A Term or relation gate: bound, pass, steps and power at {@link #BASE_GATE_TAIL}, recall at 0.05. */
    private static Walked walkGate(String name, List<Double> grid, DoubleFunction<GateCounts> at, GateBounds bounds,
                                   double recallFloor, boolean routed) {
        var units = Units.of(bounds);
        double tail = BASE_GATE_TAIL;
        @Nullable GateBounds memoryLevel = memoryLevel(bounds);
        var steps = new ArrayList<GateStep>();
        GateCounts first = null;
        GateCounts last = null;
        Double threshold = null;
        for (var t : grid) {
            var c = at.apply(t);
            boolean passes = bounds.passes(c.writing(), LIMIT, tail);
            steps.add(new GateStep(t, units.n().applyAsInt(c.writing()), units.k().applyAsInt(c.writing()),
                    bounds.upper(c.writing(), tail), passes));
            if (first == null) {
                first = c;
                if (routed && MemoryBounds.n(c.writing()) == 0) break;
            }
            if (!passes) break;
            threshold = t;
            last = c;
        }
        if (first == null) {
            return new Walked(new Gate(name, OFF, null, 0, 0, 1.0, null, Power.of(bounds, List.of(), tail), steps,
                    "no threshold at or below the starting threshold to evaluate"),
                    requirement(name, tail, List.of(), 0, memoryLevel));
        }
        var atFirst = requirement(name, tail, first.writing(), goldMemories(first.labeled()), memoryLevel);
        if (routed && MemoryBounds.n(first.writing()) == 0) {
            var step = steps.getFirst();
            return new Walked(new Gate(name, OFF, null, step.n(), step.k(), step.bound(), null,
                    Power.of(bounds, List.of(), tail), steps, notEvaluable(name)), atFirst);
        }
        if (threshold == null || last == null) {
            var step = steps.getFirst();
            return new Walked(new Gate(name, OFF, null, step.n(), step.k(), step.bound(),
                    bounds.recallLower(first.labeled(), CONFIDENCE_TAIL), Power.of(bounds, first.writing(), tail),
                    steps, "%s fails its bound at %s: %d wrong of %d, bound %s".formatted(name, fmt(step.t()),
                            step.k(), step.n(), fmt(step.bound()))), atFirst);
        }
        double recall = bounds.recallLower(last.labeled(), CONFIDENCE_TAIL);
        var step = steps.get(steps.size() - (steps.getLast().passes() ? 1 : 2));
        var power = Power.of(bounds, last.writing(), tail);
        var atLast = requirement(name, tail, last.writing(), goldMemories(last.labeled()), memoryLevel);
        if (recall < recallFloor) {
            return new Walked(new Gate(name, OFF, null, step.n(), step.k(), step.bound(), recall, power, steps,
                    "%s recall lower bound %s is below the floor %s at %s (%d right of %d)".formatted(name,
                            fmt(recall), fmt(recallFloor), fmt(threshold), right(last.labeled()),
                            gold(last.labeled()))), atLast);
        }
        return new Walked(new Gate(name, ON, threshold, step.n(), step.k(), step.bound(), recall, power, steps, null),
                atLast);
    }

    /**
     * The requirements of every gate after Terms when the Term gate is off, so none was walked: each observed with
     * Terms at {@code start}, the relations with every relation at {@code start}, the classes as well with each
     * provisional at {@code start}, the pooled gates with nothing else enabled.
     */
    private static List<Requirement> unwalkedRequirements(GateBounds bounds, double start, List<String> relationOrder,
                                                          Function<Configuration, Evaluation> evaluate) {
        var out = new ArrayList<Requirement>();
        var all = new TreeMap<String, Double>();
        relationOrder.forEach(r -> all.put(r, start));
        var atStart = evaluate.apply(new Configuration(start, all, new TreeMap<>()));
        for (var type : relationOrder) {
            var g = atStart.gates().getOrDefault(type, GateCounts.EMPTY);
            out.add(requirement(type, BASE_GATE_TAIL, g.writing(), goldMemories(g.labeled()), memoryLevel(bounds)));
        }
        var provisional = new TreeMap<String, ClassSetting>();
        V2_CLASSES.forEach(name -> provisional.put(name, new ClassSetting(PROVISIONAL, start)));
        var classesAtStart = evaluate.apply(new Configuration(start, all, provisional));
        for (var name : V2_CLASSES) {
            var c = classesAtStart.classes().getOrDefault(name, List.of());
            out.add(requirement(name, CONFIDENCE_TAIL, c, goldMemories(c), null));
        }
        out.addAll(pooledRequirements(evaluate.apply(new Configuration(start, new TreeMap<>(), new TreeMap<>())),
                bounds));
        return out;
    }

    /** G_written's and G_trap's: a trap entry's {@code written} is its gold trap relations, so its memories hold gold. */
    private static List<Requirement> pooledRequirements(Evaluation at, GateBounds bounds) {
        return List.of(requirement(G_WRITTEN, CONFIDENCE_TAIL, at.written(), goldMemories(at), memoryLevel(bounds)),
                requirement(G_TRAP, CONFIDENCE_TAIL, at.trap(), MemoryBounds.n(at.trap()), null));
    }

    private static @Nullable GateBounds memoryLevel(GateBounds bounds) {
        return Units.of(bounds).byMemory() ? bounds : null;
    }

    /** {@code memoryLevel} is the gate's bounds when they count memories, null for a record-level gate. */
    private static Requirement requirement(String name, double tail, List<MemoryCounts> writing, int goldMemories,
                                           @Nullable GateBounds memoryLevel) {
        var needed = new ArrayList<Needed>();
        for (int wrong : NEEDED_WRONG) {
            needed.add(new Needed(wrong, RecordBounds.INSTANCE.recordsNeeded(wrong, LIMIT, tail),
                    memoryLevel == null ? null : memoryLevel.recordsNeeded(wrong, LIMIT, tail)));
        }
        var atZero = needed.getFirst().memories();
        return new Requirement(name, certifyingSet(name), tail, RecordBounds.n(writing), MemoryBounds.n(writing),
                goldMemories, needed, atZero == null ? null : goldMemories >= atZero);
    }

    private static int goldMemories(List<MemoryCounts> counts) {
        return (int) counts.stream().filter(m -> m.gold() > 0).count();
    }

    /** G_written's memories with gold: every memory with gold for any gate. */
    private static int goldMemories(Evaluation at) {
        var ids = new HashSet<String>();
        at.gates().values().forEach(g -> g.labeled().forEach(m -> {
            if (m.gold() > 0) ids.add(m.memoryId());
        }));
        return ids.size();
    }

    private static int right(List<MemoryCounts> labeled) {
        return labeled.stream().mapToInt(MemoryCounts::right).sum();
    }

    private static int gold(List<MemoryCounts> labeled) {
        return labeled.stream().mapToInt(MemoryCounts::gold).sum();
    }

    private record ClassesWalked(List<ClassGate> gates, List<Requirement> requirements) {}

    /**
     * Status, then time with status at its walked threshold, then negation with both, over {@code base}; each
     * record-level at 0.05.
     */
    private static ClassesWalked walkClasses(Configuration base, List<Double> grid,
                                             Function<Configuration, Evaluation> evaluate) {
        var bounds = RecordBounds.INSTANCE;
        var settings = new TreeMap<String, ClassSetting>();
        var out = new ArrayList<ClassGate>();
        var requirements = new ArrayList<Requirement>();
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
            // Observed where the walk reads n and k: its threshold, else its first evaluable step.
            var observed = at != null ? counts.getOrDefault(at, List.of()) : steps.stream()
                    .filter(s -> RecordBounds.n(s.counts()) >= MIN_CLASS_N)
                    .max((a, b) -> Double.compare(a.t(), b.t())).map(ClassCounts::counts).orElse(List.of());
            requirements.add(requirement(name, CONFIDENCE_TAIL, observed, goldMemories(observed), null));
        }
        return new ClassesWalked(out, requirements);
    }

    private static Pooled pooled(String name, List<MemoryCounts> counts, GateBounds bounds) {
        var units = Units.of(bounds);
        return new Pooled(name, units.n().applyAsInt(counts), units.k().applyAsInt(counts),
                bounds.upper(counts, CONFIDENCE_TAIL), bounds.passes(counts, LIMIT, CONFIDENCE_TAIL),
                Power.of(bounds, counts));
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
     * relation on, a failed timeline, missing second labels ({@link Certifier#PENDING_AGREEMENT}), and any unjudged record or
     * unchecked marked model verdict ({@link Certifier#PENDING_ADJUDICATION}); otherwise {@link Certifier#CERTIFIED}. {@code sequencing}
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
