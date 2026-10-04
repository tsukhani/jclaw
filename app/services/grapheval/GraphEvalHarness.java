package services.grapheval;

import memory.MemoryProvenance;
import memory.MemoryStoreFactory;
import memory.TemporalExpressions;
import memory.graph.GraphStore;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.EventLogger;
import services.Tx;
import services.grapheval.Certifier.Adjudication;
import services.grapheval.Certifier.Certificate;
import services.grapheval.Certifier.Certification;
import services.grapheval.Certifier.ClassStep;
import services.grapheval.Certifier.ClassWalk;
import services.grapheval.Certifier.Combined;
import services.grapheval.Certifier.SpotCheck;
import services.grapheval.Certifier.Walk;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decider;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphEvalScorer.Point;
import services.grapheval.GraphEvalScorer.WrongRecord;
import services.grapheval.StageScorer.Ratio;
import services.grapheval.StageScorer.StageRun;
import services.grapheval.StageScorer.Stages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Certifies decision models for graph extraction (JCLAW-1344, JCLAW-1356): every stage and the end-to-end pipeline
 * over the labelled cases, {@code runs} times per model. A synthetic case is stored as a real memory of the requested
 * agent for the run; a held-out one is read where it lives. Either way the harness proves every row was left as it
 * was found. Graph records exist only in the returned report; nothing writes one anywhere.
 */
public final class GraphEvalHarness {

    public static final int DEFAULT_RUNS = 1;
    /** A single run's spot-check asks every tenth case again. */
    public static final int SPOT_CHECK_STRIDE = 10;
    public static final double LISTING_FLOOR = 0.50;
    private static final String CATEGORY = "grapheval";
    private static final String MEMORY_CATEGORY = "fact";
    private static final double IMPORTANCE = 0.5;

    private GraphEvalHarness() {}

    public record DecisionModel(String name, Decider decider) {}

    /** Whether every case memory was unchanged after the run, by case id, and deleted after it. */
    public record MemoryIntegrity(int checked, int unchanged, List<String> changed, int deleted) {}

    /** The held-out rows: how many were checked, unchanged and still present. Counts only. */
    public record HeldOutIntegrity(int checked, int unchanged, int present) {}

    /** The reweighting strata of the base wrong share, in the order a case is assigned to the first it carries. */
    public static final List<String> STRATA = List.of(GraphCases.NEGATED, GraphCases.UNASSERTED, GraphCases.DATED);
    /** The weights of {@link #STRATA}, then of the rest. */
    public static final List<Double> STRATUM_WEIGHTS = List.of(0.115, 0.107, 0.11, 0.668);

    /** One qualifier class at the certified configuration: its walked state and what it wrote there. */
    public record ClassAt(String name, String state, int n, int k, double bound) {}

    /**
     * One run's counts at the certified configuration. {@code questionsByStage} sums the end-to-end questions;
     * {@code qualifySent} is the share of memories that sent the qualify request; {@code statusCoverage} is the gold
     * relations that take a status written with one; {@code ruleWritten} and {@code ruleStatus} are the records left
     * out of every count for being written by rule. {@code reweightedWrongShare} is information only; so are
     * {@code cueRecall} (denied labels whose memory has a negation cue), {@code timeError} and {@code valenceError}.
     */
    public record Counts(Map<String, Integer> questionsByStage, @Nullable Double questionsPerMemory,
                         @Nullable Double qualifySent, int conflict, int vetoed, Ratio vetoRate, List<ClassAt> classes,
                         Ratio statusCoverage, Ratio dateRecall, Ratio normalizer, Ratio valenceAccuracy,
                         @Nullable Double noiseRate, int ruleWritten, int ruleStatus,
                         @Nullable Double reweightedWrongShare, Ratio cueRecall, @Nullable Double timeError,
                         @Nullable Double valenceError) {}

    /**
     * One run of one model: the gold-fed stages, its class walks, the end-to-end grid at the combined classes, the
     * certification walk over it and its counts at the model's certified configuration.
     */
    public record RunReport(int run, Stages stages, List<ClassWalk> classWalks, List<Point> grid, Walk walk,
                            Counts counts) {}

    /**
     * One model over the committed set. {@code spotCheck} is a single run's cases asked again, null for several runs.
     * {@code wrongRecords} are every run's wrong records at the certified threshold, or at {@code wrongRecordsAt} 0.50
     * when nothing certified, for adjudication. {@code certificate} carries the combined class walks.
     */
    public record ModelReport(String model, List<RunReport> runs, @Nullable SpotCheck spotCheck,
                              Certification certification, double wrongRecordsAt, List<WrongRecord> wrongRecords,
                              Certificate certificate) {}

    /**
     * The committed set's whole result. It carries no timings, so a rerun with the same answers is identical.
     * {@code schema} is the seed's {@link OntologySchema#fingerprint()} and {@code extraction} the questions'
     * {@link ExtractionPipeline#fingerprint(OntologySchema)}: a certificate is void under any other.
     */
    public record Report(String set, String schema, String extraction, boolean pairFilter, int cases, int runs,
                         double recallFloor,
                         Agreement.Result agreement, List<ModelReport> models, MemoryIntegrity memoryIntegrity) {}

    /** One model over the held-out set; the walks are information only, since only the committed set certifies. */
    public record HeldOutModel(String model, List<RunReport> runs, Combined walk, Certificate certificate) {}

    /** The held-out set's result: aggregate counts only, never an id, a text or a span. */
    public record HeldOutReport(String set, String schema, String extraction, boolean pairFilter, int cases,
                                int unlabelled, int runs, double recallFloor,
                                List<HeldOutModel> models, HeldOutIntegrity memoryIntegrity) {}

    /** The columns a decision must never touch. */
    private record Snapshot(String text, @Nullable String retrievalKey, Instant updatedAt,
                            @Nullable Instant supersededAt, @Nullable Long supersededById) {}

    private record CaseResult(StageRun stages, CaseRun e2e) {}

    private record RunData(Stages stages, List<CaseRun> e2e, List<ClassWalk> classWalks) {}

    /** A model's runs scored: each run's report, the combined class walks and the configuration they give. */
    private record Scoring(List<RunReport> reports, List<ClassWalk> classes, Statements.Classes config,
                           @Nullable Double threshold, double listedAt, Certificate certificate) {}

    /**
     * Runs the committed set. {@code ownerName} is the set's declared owner, a known name to the candidates, or null;
     * {@code secondLabels} are the blind second labeller's cases, empty when there are none; {@code adjudications}
     * are the verdicts on earlier wrong records.
     */
    public static Report run(String agentId, List<Case> cases, @Nullable String ownerName, OntologySchema schema,
                             List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                             List<Case> secondLabels, List<Adjudication> adjudications) {
        return run(agentId, cases, ownerName, schema, models, runs, recallFloor, concurrency, secondLabels,
                adjudications, EvalProgress.none());
    }

    /** {@link #run} reporting each case to {@code progress}; the report is the same either way. */
    public static Report run(String agentId, List<Case> cases, @Nullable String ownerName, OntologySchema schema,
                             List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                             List<Case> secondLabels, List<Adjudication> adjudications, EvalProgress progress) {
        return run(agentId, cases, ownerName, schema, models, runs, recallFloor, concurrency, secondLabels,
                adjudications, progress, false);
    }

    /** {@link #run} with the extraction pair filter (JCLAW-1380) on or off. */
    public static Report run(String agentId, List<Case> cases, @Nullable String ownerName, OntologySchema schema,
                             List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                             List<Case> secondLabels, List<Adjudication> adjudications, EvalProgress progress,
                             boolean pairFilter) {
        return run(agentId, cases, ownerName, schema, models, runs, recallFloor, concurrency, secondLabels,
                adjudications, progress, pairFilter, null);
    }

    /**
     * {@link #run} with {@code secondLabelsReason}, set when the second-label file could not be read under v3 and
     * {@code secondLabels} is therefore empty.
     */
    public static Report run(String agentId, List<Case> cases, @Nullable String ownerName, OntologySchema schema,
                             List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                             List<Case> secondLabels, List<Adjudication> adjudications, EvalProgress progress,
                             boolean pairFilter, @Nullable String secondLabelsReason) {
        var store = MemoryStoreFactory.get();
        var provenance = new MemoryProvenance(null, null, MemoryProvenance.process(CATEGORY),
                MemoryAuthorType.AGENT_SYNTHESIZED, List.of());
        var memoryIds = new LinkedHashMap<String, String>();
        Map<String, @Nullable Snapshot> before;
        Map<String, @Nullable Snapshot> after;
        Map<String, List<RunData>> measured;
        Map<String, SpotCheck> spotChecks = new HashMap<>();
        try {
            for (var c : cases) {
                memoryIds.put(c.id(), Tx.run(() -> store.storeDeferred(agentId, c.text(), MEMORY_CATEGORY, IMPORTANCE,
                        null, provenance)));
            }
            before = snapshot(memoryIds);
            var known = new ArrayList<>(knownNames(agentId));
            if (ownerName != null) known.add(ownerName);
            measured = measure(cases, schema, models, runs, concurrency, known, ownerName, progress, pairFilter,
                    Map.of());
            if (runs == 1) {
                for (var m : models) {
                    var data = measured.getOrDefault(m.name(), List.of());
                    if (!data.isEmpty()) {
                        spotChecks.put(m.name(), spotCheck(cases, schema, m, data.getFirst().e2e(), known,
                                ownerName, pairFilter, concurrency));
                    }
                }
            }
            after = snapshot(memoryIds);
        } finally {
            for (var id : memoryIds.values()) {
                try {
                    Tx.run(() -> store.delete(id));
                } catch (RuntimeException e) {
                    EventLogger.warn(CATEGORY, "could not delete case memory %s: %s"
                            .formatted(id, e.getClass().getSimpleName()));
                }
            }
        }
        var changed = new TreeSet<String>();
        before.forEach((caseId, snapshot) -> {
            if (snapshot == null || !snapshot.equals(after.get(caseId))) changed.add(caseId);
        });
        int deleted = (int) snapshot(memoryIds).values().stream().filter(s -> s == null).count();
        var integrity = new MemoryIntegrity(before.size(), before.size() - changed.size(), List.copyOf(changed),
                deleted);

        var symmetric = schema.symmetricSet();
        var agreement = Agreement.compare(cases, secondLabels, symmetric).withReason(secondLabelsReason);
        var reports = new ArrayList<ModelReport>();
        for (var m : models) {
            var data = measured.getOrDefault(m.name(), List.of());
            var scoring = scoring(cases, data, schema, recallFloor);
            var walks = scoring.reports().stream().map(RunReport::walk).toList();
            var threshold = scoring.threshold();
            var wrong = GraphEvalScorer.union(data.stream().map(d -> GraphEvalScorer.score(cases, d.e2e(), symmetric,
                    scoring.listedAt(), scoring.config()).wrong()).toList());
            var spot = spotChecks.get(m.name());
            var certification = Certifier.certify(walks, !changed.isEmpty(), agreement.complete(),
                    threshold == null ? List.of() : wrong, adjudications, spot);
            reports.add(new ModelReport(m.name(), scoring.reports(), spot, certification, scoring.listedAt(), wrong,
                    scoring.certificate()));
        }
        return new Report("cases", schema.fingerprint(), ExtractionPipeline.fingerprint(schema), pairFilter,
                cases.size(), runs, recallFloor, agreement, reports, integrity);
    }

    /**
     * Runs the held-out set over the memories where they live: nothing is stored, edited or deleted. {@code ownerName}
     * is the owner their agent's USER.md names, a known name to the candidates, or null.
     */
    public static HeldOutReport runHeldOut(HeldOut.Loaded loaded, @Nullable String ownerName, OntologySchema schema,
                                           List<DecisionModel> models, int runs, double recallFloor, int concurrency) {
        return runHeldOut(loaded, ownerName, schema, models, runs, recallFloor, concurrency, EvalProgress.none());
    }

    /** {@link #runHeldOut} reporting each case to {@code progress}; the report is the same either way. */
    public static HeldOutReport runHeldOut(HeldOut.Loaded loaded, @Nullable String ownerName, OntologySchema schema,
                                           List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                                           EvalProgress progress) {
        return runHeldOut(loaded, ownerName, schema, models, runs, recallFloor, concurrency, progress, false);
    }

    /** {@link #runHeldOut} with the extraction pair filter (JCLAW-1380) on or off. */
    public static HeldOutReport runHeldOut(HeldOut.Loaded loaded, @Nullable String ownerName, OntologySchema schema,
                                           List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                                           EvalProgress progress, boolean pairFilter) {
        var cases = loaded.cases().stream().map(HeldOut.HeldCase::labels).toList();
        var memoryIds = new LinkedHashMap<String, String>();
        var held = new HashMap<String, HeldOut.HeldCase>();
        loaded.cases().forEach(h -> {
            memoryIds.put(h.labels().id(), String.valueOf(h.memoryId()));
            held.put(h.labels().id(), h);
        });
        var before = snapshot(memoryIds);
        var known = ownerName == null ? List.<String>of() : List.of(ownerName);
        var measured = measure(cases, schema, models, runs, concurrency, known, ownerName, progress, pairFilter,
                held);
        var after = snapshot(memoryIds);
        int unchanged = 0;
        int present = 0;
        for (var entry : before.entrySet()) {
            var now = after.get(entry.getKey());
            if (now != null) present++;
            if (entry.getValue() != null && entry.getValue().equals(now)) unchanged++;
        }
        var reports = new ArrayList<HeldOutModel>();
        for (var m : models) {
            var scoring = scoring(cases, measured.getOrDefault(m.name(), List.of()), schema, recallFloor);
            reports.add(new HeldOutModel(m.name(), scoring.reports(),
                    Certifier.combine(scoring.reports().stream().map(RunReport::walk).toList()),
                    scoring.certificate()));
        }
        return new HeldOutReport("heldout", schema.fingerprint(), ExtractionPipeline.fingerprint(schema), pairFilter,
                cases.size(), loaded.unlabelled(), runs, recallFloor,
                reports, new HeldOutIntegrity(before.size(), unchanged, present));
    }

    /**
     * Every model's runs over {@code cases}, by model name, each with its class walks. {@code held} maps a held-out
     * case's id to its sample; it is empty for the committed set.
     */
    private static Map<String, List<RunData>> measure(List<Case> cases, OntologySchema schema,
                                                      List<DecisionModel> models, int runs, int concurrency,
                                                      List<String> knownNames, @Nullable String ownerName,
                                                      EvalProgress progress, boolean pairFilter,
                                                      Map<String, HeldOut.HeldCase> held) {
        progress.plan(models.stream().map(DecisionModel::name).toList(), runs, cases.size());
        var tasks = new ArrayList<Callable<CaseResult>>();
        int pass = 0;
        for (int r = 0; r < runs; r++) {
            for (var m : models) {
                int p = pass++;
                for (var c : cases) {
                    tasks.add(() -> {
                        progress.caseStarted(p);
                        var result = askCase(schema, c, m, knownNames,
                                inputs(c, ownerName, pairFilter, held.get(c.id())));
                        progress.caseFinished(p, failures(result));
                        return result;
                    });
                }
            }
        }
        var results = fanOut(tasks, concurrency);
        var out = new HashMap<String, List<RunData>>();
        int i = 0;
        for (int r = 0; r < runs; r++) {
            for (var m : models) {
                var slice = results.subList(i, i + cases.size());
                i += cases.size();
                var e2e = slice.stream().map(CaseResult::e2e).toList();
                var stages = StageScorer.score(cases, slice.stream().map(CaseResult::stages).toList(), ownerName,
                        schema);
                out.computeIfAbsent(m.name(), _ -> new ArrayList<>()).add(
                        new RunData(stages, e2e, classWalks(cases, e2e, schema.symmetricSet())));
            }
        }
        return out;
    }

    /**
     * One run's class walks at the base threshold {@link ExtractionPipeline#KEPT}: status, then time with status at
     * its walked threshold, then negation with both.
     */
    static List<ClassWalk> classWalks(List<Case> cases, List<CaseRun> e2e, Set<String> symmetric) {
        var status = classWalk(GraphEvalScorer.STATUS, cases, e2e, symmetric,
                t -> new Statements.Classes(t, null, null, null), GraphEvalScorer.Point::status);
        var time = classWalk(GraphEvalScorer.TIME, cases, e2e, symmetric,
                t -> new Statements.Classes(status.threshold(), t, null, null), GraphEvalScorer.Point::time);
        var negation = classWalk(GraphEvalScorer.NEGATIVE, cases, e2e, symmetric,
                t -> new Statements.Classes(status.threshold(), time.threshold(), t, null),
                GraphEvalScorer.Point::negation);
        return List.of(status, time, negation);
    }

    private static ClassWalk classWalk(String name, List<Case> cases, List<CaseRun> e2e, Set<String> symmetric,
                                       DoubleFunction<Statements.Classes> at,
                                       Function<Point, GraphEvalScorer.ClassTally> tally) {
        var steps = new ArrayList<ClassStep>();
        for (var t : GraphEvalScorer.THRESHOLDS) {
            var c = tally.apply(GraphEvalScorer.score(cases, e2e, symmetric, ExtractionPipeline.KEPT, at.apply(t))
                    .point());
            steps.add(new ClassStep(t, c.n(), c.wrong()));
        }
        return Certifier.classWalk(name, steps);
    }

    /**
     * A model's runs scored: the class walks combined across runs, then each run's base grid and walk at that
     * configuration, its counts at the certified one, and the certificate.
     */
    private static Scoring scoring(List<Case> cases, List<RunData> data, OntologySchema schema, double recallFloor) {
        var symmetric = schema.symmetricSet();
        var classes = new ArrayList<ClassWalk>();
        if (!data.isEmpty()) {
            int count = data.getFirst().classWalks().size();
            for (int i = 0; i < count; i++) {
                int k = i;
                classes.add(Certifier.combineClass(data.stream().map(d -> d.classWalks().get(k)).toList()));
            }
        }
        var config = new Statements.Classes(threshold(classes, GraphEvalScorer.STATUS),
                threshold(classes, GraphEvalScorer.TIME), threshold(classes, GraphEvalScorer.NEGATIVE), null);
        var grids = data.stream().map(d -> GraphEvalScorer.grid(cases, d.e2e(), symmetric, config)).toList();
        var walks = grids.stream().map(g -> Certifier.walk(g, recallFloor)).toList();
        var threshold = Certifier.combine(walks).threshold();
        double listedAt = threshold == null ? LISTING_FLOOR : threshold;
        var reports = new ArrayList<RunReport>();
        for (int r = 0; r < data.size(); r++) {
            var d = data.get(r);
            reports.add(new RunReport(r + 1, d.stages(), d.classWalks(), grids.get(r), walks.get(r),
                    counts(cases, d, symmetric, listedAt, config, classes)));
        }
        var certificate = new Certificate(threshold, classes, schema.fingerprint(),
                ExtractionPipeline.fingerprint(schema));
        return new Scoring(reports, classes, config, threshold, listedAt, certificate);
    }

    private static @Nullable Double threshold(List<ClassWalk> classes, String name) {
        return classes.stream().filter(c -> c.name().equals(name)).findFirst().map(ClassWalk::threshold).orElse(null);
    }

    /** One run's counts at base threshold {@code t} and {@code config}. Counts only: no id, text or span. */
    private static Counts counts(List<Case> cases, RunData d, Set<String> symmetric, double t,
                                 Statements.Classes config, List<ClassWalk> walks) {
        var point = GraphEvalScorer.score(cases, d.e2e(), symmetric, t, config).point();
        var questions = new TreeMap<String, Integer>();
        int sentQualify = 0;
        for (var run : d.e2e()) {
            run.questionsByStage().forEach((stage, n) -> questions.merge(stage, n, Integer::sum));
            if (run.sent().contains(ExtractionPipeline.Request.QUALIFY)) sentQualify++;
        }
        int memories = d.e2e().size();
        int total = questions.values().stream().mapToInt(Integer::intValue).sum();
        var classes = new ArrayList<ClassAt>();
        for (var w : walks) {
            var tally = switch (w.name()) {
                case GraphEvalScorer.STATUS -> point.status();
                case GraphEvalScorer.TIME -> point.time();
                case GraphEvalScorer.NEGATIVE -> point.negation();
                default -> throw new IllegalStateException("no tally for class " + w.name());
            };
            classes.add(new ClassAt(w.name(), w.state(), tally.n(), tally.wrong(),
                    Certifier.upperBound(tally.wrong(), tally.n())));
        }
        int denied = 0;
        int cued = 0;
        for (var c : cases) {
            boolean cue = !TemporalExpressions.negationCueRanges(c.text()).isEmpty();
            for (var r : c.relations()) {
                if (!r.denied()) continue;
                denied++;
                if (cue) cued++;
            }
        }
        var stages = d.stages();
        return new Counts(questions, memories == 0 ? null : (double) total / memories,
                memories == 0 ? null : (double) sentQualify / memories, point.conflict(), point.vetoed(),
                stages.vetoRate(), classes, Ratio.of(point.status().n(), point.status().gold()), stages.dateRecall(),
                stages.normalizer(), Ratio.of(point.valence().right(), point.valence().n()),
                point.written() == 0 ? null : (double) point.noise() / point.written(), point.ruleWritten(),
                point.ruleStatus(), reweighted(cases, d.e2e(), symmetric, t, config), Ratio.of(cued, denied),
                error(point.time()), error(point.valence()));
    }

    private static @Nullable Double error(GraphEvalScorer.ClassTally tally) {
        return tally.n() == 0 ? null : (double) tally.wrong() / tally.n();
    }

    /**
     * The base wrong share reweighted over {@link #STRATA} and the rest: a case counts in the first stratum tag it
     * carries; a stratum with no cases, or nothing written, drops out and the other weights are rescaled.
     */
    static @Nullable Double reweighted(List<Case> cases, List<CaseRun> e2e, Set<String> symmetric, double t,
                                       Statements.Classes config) {
        var groups = new ArrayList<List<Case>>();
        for (int i = 0; i <= STRATA.size(); i++) groups.add(new ArrayList<>());
        for (var c : cases) {
            int at = STRATA.size();
            for (int i = 0; i < STRATA.size(); i++) {
                if (c.tags().contains(STRATA.get(i))) {
                    at = i;
                    break;
                }
            }
            groups.get(at).add(c);
        }
        double sum = 0;
        double weights = 0;
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            if (group.isEmpty()) continue;
            var ids = new HashSet<String>();
            group.forEach(c -> ids.add(c.id()));
            var runs = e2e.stream().filter(r -> ids.contains(r.caseId())).toList();
            var share = GraphEvalScorer.score(group, runs, symmetric, t, config).point().wrongShare();
            if (share == null) continue;
            sum += STRATUM_WEIGHTS.get(i) * share;
            weights += STRATUM_WEIGHTS.get(i);
        }
        return weights == 0 ? null : sum / weights;
    }

    /**
     * Asks every {@link #SPOT_CHECK_STRIDE}th case again, from the first, and counts the decisions that differ from
     * {@code first}, the run's own: a different answer, a failure, or a decision only one of the two made.
     */
    private static SpotCheck spotCheck(List<Case> cases, OntologySchema schema, DecisionModel m, List<CaseRun> first,
                                       List<String> knownNames, @Nullable String ownerName, boolean pairFilter,
                                       int concurrency) {
        var sample = new ArrayList<Case>();
        for (int i = 0; i < cases.size(); i += SPOT_CHECK_STRIDE) sample.add(cases.get(i));
        var tasks = new ArrayList<Callable<CaseRun>>();
        for (var c : sample) {
            tasks.add(() -> ExtractionPipeline.run(schema, c.id(), c.text(),
                    CandidateGenerator.generate(c.text(), knownNames), m.name(), m.decider(),
                    inputs(c, ownerName, pairFilter, null)));
        }
        var byId = new HashMap<String, CaseRun>();
        first.forEach(r -> byId.put(r.caseId(), r));
        int decisions = 0;
        int differing = 0;
        for (var again : fanOut(tasks, concurrency)) {
            var before = byId.get(again.caseId());
            var a = before == null ? List.<ExtractionPipeline.Decision>of() : before.decisions();
            var b = again.decisions();
            int n = Math.max(a.size(), b.size());
            decisions += n;
            for (int k = 0; k < n; k++) {
                if (k >= a.size() || k >= b.size() || !a.get(k).equals(b.get(k))) differing++;
            }
        }
        return new SpotCheck(sample.size(), decisions, differing);
    }

    /** Every decision the case asked that failed, across the stages and the end-to-end pipeline. */
    private static int failures(CaseResult result) {
        var stages = result.stages();
        return (int) (stages.overlap().stream().filter(o -> o.decision().failed()).count()
                + Stream.of(stages.typing(), stages.relations(), stages.tense(), stages.negation(), stages.occurs(),
                        stages.status(), stages.slot()).flatMap(List::stream).filter(Decision::failed).count()
                + result.e2e().decisions().stream().filter(ExtractionPipeline.Decision::failed).count());
    }

    /** The agent's known spans: each Term's name followed by its aliases. Read, never written. */
    private static List<String> knownNames(String agentId) {
        try {
            return GraphStore.get().read(Long.parseLong(agentId)).stream()
                    .filter(r -> r instanceof OntologyRecord.Term)
                    .map(r -> (OntologyRecord.Term) r)
                    .flatMap(t -> Stream.concat(Stream.of(t.name()), t.aliases().stream())).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The overlap stage over generated candidates, the gold-fed typing, relation, tense, negation, occurs, status and
     * slot stages, then the end-to-end pipeline from generated candidates.
     */
    private static CaseResult askCase(OntologySchema schema, Case c, DecisionModel m, List<String> knownNames,
                                      ExtractionPipeline.Inputs inputs) {
        var text = c.text();
        var candidates = CandidateGenerator.generate(text, knownNames);
        var overlap = ExtractionPipeline.settle(text,
                candidates.stream().filter(k -> !k.operator()).toList(), m.name(), m.decider());
        var typing = ExtractionPipeline.type(schema, text, StageScorer.typingSpans(c), m.name(), m.decider());
        var terms = StageScorer.relationTerms(c);
        var relations = ExtractionPipeline.relate(schema, text, terms, m.name(), m.decider());

        var labelled = new HashSet<String>();
        c.dates().forEach(d -> {
            if (d.value() != null) labelled.add(d.span());
        });
        var dates = TemporalExpressions.find(text, c.capturedAt()).found().stream()
                .filter(d -> labelled.contains(d.span())).toList();
        var tense = ExtractionPipeline.tense(text, dates, m.name(), m.decider());
        var negation = TemporalExpressions.negationCueRanges(text).isEmpty() ? List.<Decision>of()
                : ExtractionPipeline.negation(schema, text, terms, m.name(), m.decider());
        var events = c.entities().stream().filter(e -> {
            var type = schema.termTypes().get(e.type());
            return type != null && type.dated();
        }).map(GraphCases.Entity::span).toList();
        var occurs = ExtractionPipeline.occurs(text, events, dates, m.name(), m.decider());

        var types = new HashMap<String, String>();
        terms.forEach(t -> types.putIfAbsent(t.span(), t.type()));
        var asked = new ArrayList<Decision>();
        var holding = new ArrayList<Decision>();
        var statuses = new ArrayList<Decision>();
        for (var r : c.relations()) {
            var from = c.entity(r.from());
            var to = c.entity(r.to());
            if (from == null || to == null) continue;
            var relation = new Decision(ExtractionPipeline.RELATION, from.span() + " -> " + to.span(), from.span(),
                    to.span(), r.type(), 1.0, false, null);
            asked.add(relation);
            if (!c.holdsOrEnded(r, schema)) continue;
            holding.add(relation);
            statuses.add(new Decision(ExtractionPipeline.STATUS, from.span() + " -" + r.type() + "-> " + to.span(),
                    from.span(), to.span(), c.scoredStatus(r, schema), 1.0, false, null));
        }
        var status = ExtractionPipeline.status(schema, text, ExtractionPipeline.kept(asked, types), inputs.voice(),
                m.name(), m.decider());
        var slot = ExtractionPipeline.slot(schema, text, ExtractionPipeline.kept(holding, types), dates, statuses,
                m.name(), m.decider());
        var e2e = ExtractionPipeline.run(schema, c.id(), text, candidates, m.name(), m.decider(), inputs);
        return new CaseResult(new StageRun(c.id(), overlap, typing, relations.decisions(), tense, negation, occurs,
                status, slot), e2e);
    }

    /**
     * A case's run inputs at its anchor with no predecessors: a held-out case in its sampled author's voice with its
     * memory id, a committed one as a guest turn when tagged {@code guest}, else a human turn.
     */
    public static ExtractionPipeline.Inputs inputs(Case c, @Nullable String ownerName, boolean pairFilter,
                                                    HeldOut.@Nullable HeldCase held) {
        if (held != null) {
            return ExtractionPipeline.Inputs.of(c.text(), ownerName, held.authorType(), c.capturedAt(), List.of(),
                    pairFilter, held.memoryId());
        }
        var author = c.tags().contains(GraphCases.GUEST) ? MemoryAuthorType.GUEST_TURN : MemoryAuthorType.HUMAN_TURN;
        return ExtractionPipeline.Inputs.of(c.text(), ownerName, author, c.capturedAt(), List.of(), pairFilter);
    }

    /** Runs {@code tasks} on at most {@code concurrency} threads, returning results in task order. */
    private static <T> List<T> fanOut(List<Callable<T>> tasks, int concurrency) {
        var results = new ArrayList<T>(tasks.size());
        try {
            if (concurrency <= 1) {
                for (var task : tasks) results.add(task.call());
                return results;
            }
            try (var pool = Executors.newFixedThreadPool(concurrency)) {
                var futures = new ArrayList<Future<T>>();
                for (var task : tasks) futures.add(pool.submit(task));
                for (var future : futures) results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("graph eval interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("graph eval task failed", e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("graph eval task failed", e);
        }
    }

    /** Each row's guarded columns read straight from the table; a missing row maps to null. */
    private static Map<String, @Nullable Snapshot> snapshot(Map<String, String> memoryIds) {
        return Tx.run(() -> {
            var out = new HashMap<String, @Nullable Snapshot>();
            memoryIds.forEach((caseId, id) -> {
                var rows = JPA.em().createQuery("SELECT m.text, m.retrievalKey, m.updatedAt, m.supersededAt, "
                                + "m.supersededById FROM Memory m WHERE m.id = :id", Object[].class)
                        .setParameter("id", Long.parseLong(id)).getResultList();
                if (rows.isEmpty()) {
                    out.put(caseId, null);
                } else {
                    var row = rows.getFirst();
                    out.put(caseId, new Snapshot((String) row[0], (String) row[1], (Instant) row[2],
                            (Instant) row[3], (Long) row[4]));
                }
            });
            return out;
        });
    }
}
