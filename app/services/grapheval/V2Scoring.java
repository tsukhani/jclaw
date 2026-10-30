package services.grapheval;

import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.GateBounds.MemoryCounts;
import services.grapheval.GraphCases.Case;
import services.grapheval.StageScorer.Ratio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/** Protocol v2 (JCLAW-1368): stored decisions scored gate by gate through the certifier's sequencing. */
public final class V2Scoring {

    public static final long DEFAULT_AGREED_SEED = 1356;
    /** Where a development run starts its v2 walk until a development run sets a starting threshold. */
    public static final double DEVELOPMENT_START = 0.95;
    private static final List<String> QUESTION_BUCKETS = List.of("1-10", "11-20", "21-40", ">40");

    private V2Scoring() {}

    /**
     * What a development run's v2 sequencing reads beside its decisions: the running {@code guide@} the verdicts
     * must carry, the agreed sample's seed and share, the model-verdict check share, and how many cases were left out
     * because a frozen split holds them.
     */
    public record DevOptions(String guide, long agreedSeed, double agreedShare, double checkShare,
                             int excludedSplitIds) {
        public static final DevOptions NONE = new DevOptions("", DEFAULT_AGREED_SEED, Adjudications.DEFAULT_SHARE,
                Adjudications.DEFAULT_CHECK_SHARE, 0);
    }

    /** Memories whose questions fall in one bucket, how many of them had a failed decision, and that share. */
    public record FailureShare(String questions, int memories, int failed, @Nullable Double share) {}

    /**
     * Report-side counts at the configuration reached, summed over runs: questions per memory, the share of memories
     * with any failed decision overall and by questions per memory, per stratum tag the share of memories writing a
     * record the labels do not support (any record, for a memory with no gold), and the rule-written records left out.
     */
    public record Tallies(int memories, @Nullable Double questionsPerMemory, int failedMemories,
                          @Nullable Double failedShare, List<FailureShare> byQuestions,
                          Map<String, Ratio> falsePositiveByTag, int ruleWrittenLeftOut) {}

    /** The agreed sample: drawn by hash at {@code seed} and {@code share} over the agreed records written at 0.50. */
    public record AgreedSample(long seed, double share, double drawnAt, int drawn) {}

    /** Model verdicts marked for an operator check at {@code checkShare}, and how the checked ones came out. */
    public record Checks(double checkShare, int marked, int checked, int disagreed, @Nullable Double disagreementRate) {}

    /**
     * A development run's v2 sequencing, information only: no ledger line and no certificate. {@code sequencing} is
     * null on a run with a failed decision.
     */
    public record Development(double startingThreshold, Sequencer.@Nullable Sequencing sequencing, Tallies tallies,
                              AgreedSample agreedSample, Map<String, Integer> unjudgedByGate, Checks checks) {}

    /** Stored decisions scored the v2 way; {@code sequencing} is null on a void run. */
    record V2(Sequencer.@Nullable Sequencing sequencing, Tallies tallies, AgreedSample agreedSample,
              Adjudications.Judgment judgment, List<GraphEvalScorer.GateRecord> inScope) {}

    static Development development(List<Case> cases, List<List<CaseRun>> runs, OntologySchema schema,
                                   List<Adjudications.Verdict> adjudications, DevOptions options,
                                   double recallFloor) {
        var book = new Adjudications.Book(adjudications, options.guide(), options.agreedSeed(), options.agreedShare(),
                options.checkShare());
        var v2 = v2(cases, runs, schema, book, DEVELOPMENT_START,
                CertificationSplit.relationOrder(cases, schema), recallFloor, MemoryBounds.INSTANCE, null);
        return new Development(DEVELOPMENT_START, v2.sequencing(), v2.tallies(), v2.agreedSample(),
                v2.judgment().unjudgedByGate(), checks(book, v2.judgment()));
    }

    static Checks checks(Adjudications.Book book, Adjudications.Judgment j) {
        return new Checks(book.checkShare(), j.marked(), j.checked(), j.disagreed(), j.disagreementRate());
    }

    /**
     * Sequences {@code runs}' stored decisions over {@code cases} through a memoized pure evaluator; several runs are
     * read gate by gate at the run with the highest bound (the lowest recall bound for recall), so every run must pass.
     * {@code set} is the split's certifying set, null for a development run: a gate certified by the other set reads
     * no counts.
     */
    static V2 v2(List<Case> cases, List<List<CaseRun>> runs, OntologySchema schema, Adjudications.Book book,
                 double start, List<String> relationOrder, double recallFloor, GateBounds bounds,
                 @Nullable String set) {
        var symmetric = schema.symmetricSet();
        var outcomes = new IdentityHashMap<CaseRun, Map<List<Object>, Statements.Outcome>>();
        // Statements.at reads a class only as max(t, its threshold), so classes equal under that share one outcome.
        GraphEvalScorer.StatementsAt at = (run, t, classes) -> outcomes.computeIfAbsent(run, _ -> new HashMap<>())
                .computeIfAbsent(List.of(t, new Statements.Classes(Statements.at(t, classes.status()),
                        Statements.at(t, classes.time()), Statements.at(t, classes.negation()), null)),
                        _ -> Statements.at(run, t, classes));
        var scored = new HashMap<Configuration, List<GraphEvalScorer.Configured>>();
        Function<Configuration, List<GraphEvalScorer.Configured>> scoreAt = c -> {
            var hit = scored.get(c);
            if (hit != null) return hit;
            var out = runs.stream().map(r -> GraphEvalScorer.score(cases, r, symmetric, c, at)).toList();
            scored.put(c, out);
            return out;
        };
        int failed = (int) runs.stream().flatMap(List::stream).flatMap(r -> r.decisions().stream())
                .filter(Decision::failed).count();
        Sequencer.Sequencing sequencing = null;
        Configuration reached = new Configuration(start, new TreeMap<>(), new TreeMap<>());
        if (failed == 0 && !runs.isEmpty()) {
            sequencing = Sequencer.sequence(bounds, set, start, relationOrder, recallFloor,
                    c -> worst(scoreAt.apply(c).stream().map(cf -> routed(evaluation(cf, book), set)).toList(),
                            bounds));
            reached = sequencing.reached();
        }
        var atReached = scoreAt.apply(reached);
        var inScope = new LinkedHashMap<List<String>, GraphEvalScorer.GateRecord>();
        atReached.forEach(cf -> cf.records().forEach(r -> inScope.putIfAbsent(List.of(r.caseId(), r.record()), r)));
        var widest = new TreeMap<String, Double>();
        relationOrder.forEach(r -> widest.put(r, Adjudications.DRAWN_AT));
        var drawn = new HashSet<List<String>>();
        for (var cf : scoreAt.apply(new Configuration(Adjudications.DRAWN_AT, widest, new TreeMap<>()))) {
            for (var r : cf.records()) {
                if (book.sampled(r)) drawn.add(List.of(r.caseId(), r.record()));
            }
        }
        return new V2(sequencing, tallies(atReached),
                new AgreedSample(book.seed(), book.share(), Adjudications.DRAWN_AT, drawn.size()),
                book.judge(List.copyOf(inScope.values())), List.copyOf(inScope.values()));
    }

    /** One run at one configuration as the certifier reads it: each memory's counts per gate, class and pooled gate. */
    public static Sequencer.Evaluation evaluation(GraphEvalScorer.Configured cf, Adjudications.Book book) {
        var weights = new HashMap<List<String>, Double>();
        var memoryWeight = new HashMap<String, Double>();
        for (var r : cf.records()) {
            double w = book.agreedWrongWeight(r);
            if (w <= 0) continue;
            weights.merge(List.of(r.caseId(), r.gate()), w, Double::sum);
            memoryWeight.merge(r.caseId(), w, Double::sum);
        }
        var writing = new TreeMap<String, List<MemoryCounts>>();
        var labeled = new TreeMap<String, List<MemoryCounts>>();
        var classes = new TreeMap<String, List<MemoryCounts>>();
        var written = new ArrayList<MemoryCounts>();
        var trap = new ArrayList<MemoryCounts>();
        for (var m : cf.memories()) {
            m.gates().forEach((gate, t) -> {
                var mc = new MemoryCounts(m.caseId(), t.written(), t.wrong(),
                        weights.getOrDefault(List.of(m.caseId(), gate), 0.0), t.gold(), t.right());
                if (t.written() > 0) writing.computeIfAbsent(gate, _ -> new ArrayList<>()).add(mc);
                if (t.gold() > 0) labeled.computeIfAbsent(gate, _ -> new ArrayList<>()).add(mc);
            });
            for (var name : Sequencer.V2_CLASSES) {
                var c = m.classes().get(name);
                if (c != null && c.n() > 0) {
                    classes.computeIfAbsent(name, _ -> new ArrayList<>())
                            .add(new MemoryCounts(m.caseId(), c.n(), c.wrong(), 0, c.gold(), c.right()));
                }
            }
            if (m.gWritten() > 0) {
                written.add(new MemoryCounts(m.caseId(), m.gWritten(), m.gWrong(),
                        memoryWeight.getOrDefault(m.caseId(), 0.0), 0, 0));
            }
            if (m.trapGold() > 0) trap.add(new MemoryCounts(m.caseId(), m.trapGold(), m.trapViolations(), 0, 0, 0));
        }
        var gates = new HashMap<String, Sequencer.GateCounts>();
        var names = new TreeSet<>(writing.keySet());
        names.addAll(labeled.keySet());
        for (var g : names) {
            gates.put(g, new Sequencer.GateCounts(writing.getOrDefault(g, List.of()),
                    labeled.getOrDefault(g, List.of())));
        }
        return new Sequencer.Evaluation(gates, classes, written, trap);
    }

    /** The evaluation as {@code set} certifies it: the gates the other set certifies read no counts. */
    private static Sequencer.Evaluation routed(Sequencer.Evaluation e, @Nullable String set) {
        if (set == null) return e;
        Predicate<String> ours = gate -> Sequencer.certifyingSet(gate).equals(set);
        var gates = new HashMap<String, Sequencer.GateCounts>();
        e.gates().forEach((g, c) -> {
            if (ours.test(g)) gates.put(g, c);
        });
        var classes = new HashMap<String, List<MemoryCounts>>();
        e.classes().forEach((c, l) -> {
            if (ours.test(c)) classes.put(c, l);
        });
        return new Sequencer.Evaluation(gates, classes, ours.test(Sequencer.G_WRITTEN) ? e.written() : List.of(),
                ours.test(Sequencer.G_TRAP) ? e.trap() : List.of());
    }

    /**
     * Each gate at the run whose bound at that gate's tail is highest, its recall at the run whose recall bound is
     * lowest.
     */
    public static Sequencer.Evaluation worst(List<Sequencer.Evaluation> runs, GateBounds bounds) {
        if (runs.size() == 1) return runs.getFirst();
        var tail = Certifier.CONFIDENCE_TAIL;
        var names = new TreeSet<String>();
        runs.forEach(e -> names.addAll(e.gates().keySet()));
        var gates = new HashMap<String, Sequencer.GateCounts>();
        for (var g : names) {
            var counts = runs.stream().map(e -> e.gates().getOrDefault(g, Sequencer.GateCounts.EMPTY)).toList();
            var labeled = counts.stream().map(Sequencer.GateCounts::labeled)
                    .min((a, b) -> Double.compare(bounds.recallLower(a, tail), bounds.recallLower(b, tail)))
                    .orElse(List.of());
            gates.put(g, new Sequencer.GateCounts(highest(counts.stream().map(Sequencer.GateCounts::writing).toList(),
                    bounds, Sequencer.BASE_GATE_TAIL), labeled));
        }
        var classes = new HashMap<String, List<MemoryCounts>>();
        for (var name : Sequencer.V2_CLASSES) {
            classes.put(name, highest(runs.stream().map(e -> e.classes().getOrDefault(name, List.of())).toList(),
                    RecordBounds.INSTANCE, tail));
        }
        return new Sequencer.Evaluation(gates, classes,
                highest(runs.stream().map(Sequencer.Evaluation::written).toList(), bounds, tail),
                highest(runs.stream().map(Sequencer.Evaluation::trap).toList(), RecordBounds.INSTANCE, tail));
    }

    private static List<MemoryCounts> highest(List<List<MemoryCounts>> lists, GateBounds bounds, double tail) {
        return lists.stream().max((a, b) -> Double.compare(bounds.upper(a, tail), bounds.upper(b, tail)))
                .orElse(List.of());
    }

    private static Tallies tallies(List<GraphEvalScorer.Configured> runs) {
        int memories = 0;
        int questions = 0;
        int failed = 0;
        int ruleWritten = 0;
        var bucketMemories = new int[QUESTION_BUCKETS.size()];
        var bucketFailed = new int[QUESTION_BUCKETS.size()];
        var tagUnsupported = new TreeMap<String, Integer>();
        var tagMemories = new TreeMap<String, Integer>();
        for (var cf : runs) {
            for (var m : cf.memories()) {
                memories++;
                questions += m.questions();
                ruleWritten += m.ruleWritten();
                boolean anyFailed = m.failedDecisions() > 0;
                if (anyFailed) failed++;
                int bucket = questionBucket(m.questions());
                bucketMemories[bucket]++;
                if (anyFailed) bucketFailed[bucket]++;
                for (var tag : m.tags()) {
                    tagMemories.merge(tag, 1, Integer::sum);
                    if (m.unsupported()) tagUnsupported.merge(tag, 1, Integer::sum);
                }
            }
        }
        var byQuestions = new ArrayList<FailureShare>();
        for (int i = 0; i < QUESTION_BUCKETS.size(); i++) {
            byQuestions.add(new FailureShare(QUESTION_BUCKETS.get(i), bucketMemories[i], bucketFailed[i],
                    bucketMemories[i] == 0 ? null : (double) bucketFailed[i] / bucketMemories[i]));
        }
        var falsePositive = new TreeMap<String, Ratio>();
        tagMemories.forEach((tag, n) -> falsePositive.put(tag, Ratio.of(tagUnsupported.getOrDefault(tag, 0), n)));
        return new Tallies(memories, memories == 0 ? null : (double) questions / memories, failed,
                memories == 0 ? null : (double) failed / memories, byQuestions, falsePositive, ruleWritten);
    }

    private static int questionBucket(int questions) {
        if (questions <= 10) return 0;
        if (questions <= 20) return 1;
        return questions <= 40 ? 2 : 3;
    }
}
