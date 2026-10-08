package services.grapheval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import memory.MemoryProvenance;
import memory.MemoryStoreFactory;
import memory.TemporalExpressions;
import memory.graph.GraphStore;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.EventLogger;
import services.TimezoneResolver;
import services.Tx;
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
import services.grapheval.GateBounds.MemoryCounts;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphEvalScorer.Point;
import services.grapheval.GraphEvalScorer.WrongRecord;
import services.grapheval.StageScorer.Ratio;
import services.grapheval.StageScorer.StageRun;
import services.grapheval.StageScorer.Stages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static utils.GsonHolder.GSON;

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
     * when nothing certified, for adjudication. {@code certificate} carries the combined class walks. {@code v2} is
     * the protocol v2 sequencing at the development start, information only: a development run never certifies.
     */
    public record ModelReport(String model, List<RunReport> runs, @Nullable SpotCheck spotCheck,
                              Certification certification, double wrongRecordsAt, List<WrongRecord> wrongRecords,
                              Certificate certificate, Development v2) {}

    /**
     * The committed set's whole result. It carries no timings, so a rerun with the same answers is identical.
     * {@code schema} is the seed's {@link OntologySchema#fingerprint()} and {@code extraction} the questions'
     * {@link ExtractionPipeline#fingerprint(OntologySchema)}: a certificate is void under any other.
     * {@code excludedSplitIds} counts the cases left out because a frozen split holds them.
     */
    public record Report(String set, String schema, String extraction, boolean pairFilter, int cases, int runs,
                         double recallFloor,
                         Agreement.Result agreement, List<ModelReport> models, MemoryIntegrity memoryIntegrity,
                         int excludedSplitIds) {}

    /** One model over the held-out set; the walks are information only: a held-out set certifies only through a split. */
    public record HeldOutModel(String model, List<RunReport> runs, Combined walk, Certificate certificate,
                               Development v2) {}

    /**
     * The held-out set's result: aggregate counts only, never an id, a text or a span. {@code coverage} holds the label
     * sections once and one confusion matrix per model (JCLAW-1374).
     */
    public record HeldOutReport(String set, String schema, String extraction, boolean pairFilter, int cases,
                                int unlabelled, int runs, double recallFloor,
                                List<HeldOutModel> models, HeldOutIntegrity memoryIntegrity, int excludedSplitIds,
                                HeldOutCoverage coverage) {}

    /** The coverage report over a held-out run: the label sections, and each model's typing confusions. */
    public record HeldOutCoverage(CoverageReport.Sections sections, List<CoverageReport.Confusion> confusions) {
        public HeldOutCoverage {
            confusions = List.copyOf(confusions);
        }
    }

    /** The columns a decision must never touch. */
    private record Snapshot(String text, @Nullable String retrievalKey, Instant updatedAt,
                            @Nullable Instant supersededAt, @Nullable Long supersededById) {}

    private record CaseResult(StageRun stages, CaseRun e2e) {}

    private record RunData(Stages stages, List<CaseRun> e2e, List<ClassWalk> classWalks, List<StageRun> stageRuns) {}

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
                             List<Case> secondLabels, List<Adjudications.Verdict> adjudications) {
        return run(agentId, cases, ownerName, schema, models, runs, recallFloor, concurrency, secondLabels,
                adjudications, EvalProgress.none());
    }

    /** {@link #run} reporting each case to {@code progress}; the report is the same either way. */
    public static Report run(String agentId, List<Case> cases, @Nullable String ownerName, OntologySchema schema,
                             List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                             List<Case> secondLabels, List<Adjudications.Verdict> adjudications, EvalProgress progress) {
        return run(agentId, cases, ownerName, schema, models, runs, recallFloor, concurrency, secondLabels,
                adjudications, progress, false);
    }

    /** {@link #run} with the extraction pair filter (JCLAW-1380) on or off. */
    public static Report run(String agentId, List<Case> cases, @Nullable String ownerName, OntologySchema schema,
                             List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                             List<Case> secondLabels, List<Adjudications.Verdict> adjudications, EvalProgress progress,
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
                             List<Case> secondLabels, List<Adjudications.Verdict> adjudications, EvalProgress progress,
                             boolean pairFilter, @Nullable String secondLabelsReason) {
        return run(agentId, cases, ownerName, schema, models, runs, recallFloor, concurrency, secondLabels,
                adjudications, progress, pairFilter, secondLabelsReason, DevOptions.NONE);
    }

    /** {@link #run} with the v2 development options: the guide, the agreed sample and the split ids left out. */
    public static Report run(String agentId, List<Case> cases, @Nullable String ownerName, OntologySchema schema,
                             List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                             List<Case> secondLabels, List<Adjudications.Verdict> adjudications, EvalProgress progress,
                             boolean pairFilter, @Nullable String secondLabelsReason, DevOptions options) {
        var stored = measureStored(agentId, cases, ownerName, schema, models, runs, concurrency, progress, pairFilter);
        var measured = stored.measured();
        var spotChecks = stored.spotChecks();
        var integrity = stored.integrity();
        var changed = integrity.changed();
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
                    scoring.certificate(), development(cases, data, schema, adjudications, options, recallFloor)));
        }
        return new Report("cases", schema.fingerprint(), ExtractionPipeline.fingerprint(schema), pairFilter,
                cases.size(), runs, recallFloor, agreement, reports, integrity, options.excludedSplitIds());
    }

    /** What a committed-set measurement leaves: every run's data by model, a single run's spot-checks, and integrity. */
    private record MeasuredCases(Map<String, List<RunData>> measured, Map<String, SpotCheck> spotChecks,
                                 MemoryIntegrity integrity) {}

    /**
     * Stores each case as a memory of the agent, runs every model over them, spot-checks a single run, then deletes
     * them and checks every row was unchanged.
     */
    private static MeasuredCases measureStored(String agentId, List<Case> cases, @Nullable String ownerName,
                                               OntologySchema schema, List<DecisionModel> models, int runs,
                                               int concurrency, EvalProgress progress, boolean pairFilter) {
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
                                ownerName, pairFilter, concurrency, Map.of()));
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
        return new MeasuredCases(measured, spotChecks, new MemoryIntegrity(before.size(),
                before.size() - changed.size(), List.copyOf(changed), deleted));
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
        return runHeldOut(loaded, ownerName, schema, models, runs, recallFloor, concurrency, progress, pairFilter,
                List.of(), DevOptions.NONE);
    }

    /** {@link #runHeldOut} with the held-out verdicts and the v2 development options, and no competency questions. */
    public static HeldOutReport runHeldOut(HeldOut.Loaded loaded, @Nullable String ownerName, OntologySchema schema,
                                           List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                                           EvalProgress progress, boolean pairFilter,
                                           List<Adjudications.Verdict> adjudications, DevOptions options) {
        return runHeldOut(loaded, ownerName, schema, models, runs, recallFloor, concurrency, progress, pairFilter,
                adjudications, options, null);
    }

    /** {@link #runHeldOut} with the operator's competency questions, null when there is no question file. */
    public static HeldOutReport runHeldOut(HeldOut.Loaded loaded, @Nullable String ownerName, OntologySchema schema,
                                           List<DecisionModel> models, int runs, double recallFloor, int concurrency,
                                           EvalProgress progress, boolean pairFilter,
                                           List<Adjudications.Verdict> adjudications, DevOptions options,
                                           @Nullable List<CompetencyQuestions.Question> questions) {
        // Cases go by memory id, as held-out verdicts and the agreed draw do; the report carries none of them.
        var cases = new ArrayList<Case>();
        var memoryIds = new LinkedHashMap<String, String>();
        var held = new HashMap<String, HeldOut.HeldCase>();
        loaded.cases().forEach(h -> {
            var id = String.valueOf(h.memoryId());
            var c = h.labels();
            cases.add(new Case(id, c.tags(), c.text(), c.entities(), c.relations(), c.negatives(), c.capturedAt(),
                    c.dates()));
            memoryIds.put(id, id);
            held.put(id, h);
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
        var typing = new LinkedHashMap<String, List<StageRun>>();
        for (var m : models) {
            var data = measured.getOrDefault(m.name(), List.<RunData>of());
            typing.put(m.name(), data.stream().flatMap(d -> d.stageRuns().stream()).toList());
            var scoring = scoring(cases, data, schema, recallFloor);
            reports.add(new HeldOutModel(m.name(), scoring.reports(),
                    Certifier.combine(scoring.reports().stream().map(RunReport::walk).toList()),
                    scoring.certificate(), development(cases, data, schema, adjudications, options, recallFloor)));
        }
        return new HeldOutReport("heldout", schema.fingerprint(), ExtractionPipeline.fingerprint(schema), pairFilter,
                cases.size(), loaded.unlabelled(), runs, recallFloor,
                reports, new HeldOutIntegrity(before.size(), unchanged, present), options.excludedSplitIds(),
                new HeldOutCoverage(CoverageReport.sections(loaded.cases(), questions, schema),
                        CoverageReport.confusions(loaded.cases(), typing)));
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
                        new RunData(stages, e2e, classWalks(cases, e2e, schema.symmetricSet()),
                                slice.stream().map(CaseResult::stages).toList()));
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
                                       int concurrency, Map<String, HeldOut.HeldCase> held) {
        var sample = new ArrayList<Case>();
        for (int i = 0; i < cases.size(); i += SPOT_CHECK_STRIDE) sample.add(cases.get(i));
        var tasks = new ArrayList<Callable<CaseRun>>();
        for (var c : sample) {
            tasks.add(() -> ExtractionPipeline.run(schema, c.id(), c.text(),
                    CandidateGenerator.generate(c.text(), knownNames, ownerName), m.name(), m.decider(),
                    inputs(c, ownerName, pairFilter, held.get(c.id()))));
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
            return KnownNames.of(GraphStore.get(), Long.parseLong(agentId));
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
        var candidates = CandidateGenerator.generate(text, knownNames, inputs.ownerName());
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

    // ---- Protocol v2 (JCLAW-1368): gate sequencing, frozen splits, two-sided adjudication, certificates. ----

    public static final long DEFAULT_AGREED_SEED = 1356;
    /** Where a development run starts its v2 walk until a development run sets a starting threshold. */
    public static final double DEVELOPMENT_START = 0.95;
    public static final String SHEETS = "sheets";
    public static final String CERTIFICATION = "certification";
    private static final List<String> QUESTION_BUCKETS = List.of("1-10", "11-20", "21-40", ">40");

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
    public record Development(double startingThreshold, Certifier.@Nullable Sequencing sequencing, Tallies tallies,
                              AgreedSample agreedSample, Map<String, Integer> unjudgedByGate, Checks checks) {}

    /** Stored decisions scored the v2 way; {@code sequencing} is null on a void run. */
    private record V2(Certifier.@Nullable Sequencing sequencing, Tallies tallies, AgreedSample agreedSample,
                      Adjudications.Judgement judgement, List<GraphEvalScorer.GateRecord> inScope) {}

    private static Development development(List<Case> cases, List<RunData> data, OntologySchema schema,
                                           List<Adjudications.Verdict> adjudications, DevOptions options,
                                           double recallFloor) {
        var book = new Adjudications.Book(adjudications, options.guide(), options.agreedSeed(), options.agreedShare(),
                options.checkShare());
        var v2 = v2(cases, data.stream().map(RunData::e2e).toList(), schema, book, DEVELOPMENT_START,
                CertificationSplit.relationOrder(cases, schema), recallFloor, MemoryBounds.INSTANCE, null);
        return new Development(DEVELOPMENT_START, v2.sequencing(), v2.tallies(), v2.agreedSample(),
                v2.judgement().unjudgedByGate(), checks(book, v2.judgement()));
    }

    private static Checks checks(Adjudications.Book book, Adjudications.Judgement j) {
        return new Checks(book.checkShare(), j.marked(), j.checked(), j.disagreed(), j.disagreementRate());
    }

    /**
     * Sequences {@code runs}' stored decisions over {@code cases} through a memoized pure evaluator; several runs are
     * read gate by gate at the run with the highest bound (the lowest recall bound for recall), so every run must pass.
     * {@code set} is the split's certifying set, null for a development run: a gate certified by the other set reads
     * no counts.
     */
    private static V2 v2(List<Case> cases, List<List<CaseRun>> runs, OntologySchema schema, Adjudications.Book book,
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
        Certifier.Sequencing sequencing = null;
        Configuration reached = new Configuration(start, new TreeMap<>(), new TreeMap<>());
        if (failed == 0 && !runs.isEmpty()) {
            sequencing = Certifier.sequence(bounds, set, start, relationOrder, recallFloor,
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
    public static Certifier.Evaluation evaluation(GraphEvalScorer.Configured cf, Adjudications.Book book) {
        var weights = new HashMap<List<String>, Double>();
        var memoryWeight = new HashMap<String, Double>();
        for (var r : cf.records()) {
            double w = book.agreedWrongWeight(r);
            if (w <= 0) continue;
            weights.merge(List.of(r.caseId(), r.gate()), w, Double::sum);
            memoryWeight.merge(r.caseId(), w, Double::sum);
        }
        var writing = new TreeMap<String, List<MemoryCounts>>();
        var labelled = new TreeMap<String, List<MemoryCounts>>();
        var classes = new TreeMap<String, List<MemoryCounts>>();
        var written = new ArrayList<MemoryCounts>();
        var trap = new ArrayList<MemoryCounts>();
        for (var m : cf.memories()) {
            m.gates().forEach((gate, t) -> {
                var mc = new MemoryCounts(m.caseId(), t.written(), t.wrong(),
                        weights.getOrDefault(List.of(m.caseId(), gate), 0.0), t.gold(), t.right());
                if (t.written() > 0) writing.computeIfAbsent(gate, _ -> new ArrayList<>()).add(mc);
                if (t.gold() > 0) labelled.computeIfAbsent(gate, _ -> new ArrayList<>()).add(mc);
            });
            for (var name : Certifier.V2_CLASSES) {
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
        var gates = new HashMap<String, Certifier.GateCounts>();
        var names = new TreeSet<>(writing.keySet());
        names.addAll(labelled.keySet());
        for (var g : names) {
            gates.put(g, new Certifier.GateCounts(writing.getOrDefault(g, List.of()),
                    labelled.getOrDefault(g, List.of())));
        }
        return new Certifier.Evaluation(gates, classes, written, trap);
    }

    /** The evaluation as {@code set} certifies it: the gates the other set certifies read no counts. */
    private static Certifier.Evaluation routed(Certifier.Evaluation e, @Nullable String set) {
        if (set == null) return e;
        Predicate<String> ours = gate -> Certifier.certifyingSet(gate).equals(set);
        var gates = new HashMap<String, Certifier.GateCounts>();
        e.gates().forEach((g, c) -> {
            if (ours.test(g)) gates.put(g, c);
        });
        var classes = new HashMap<String, List<MemoryCounts>>();
        e.classes().forEach((c, l) -> {
            if (ours.test(c)) classes.put(c, l);
        });
        return new Certifier.Evaluation(gates, classes, ours.test(Certifier.G_WRITTEN) ? e.written() : List.of(),
                ours.test(Certifier.G_TRAP) ? e.trap() : List.of());
    }

    /**
     * Each gate at the run whose bound at that gate's tail is highest, its recall at the run whose recall bound is
     * lowest.
     */
    public static Certifier.Evaluation worst(List<Certifier.Evaluation> runs, GateBounds bounds) {
        if (runs.size() == 1) return runs.getFirst();
        var tail = Certifier.CONFIDENCE_TAIL;
        var names = new TreeSet<String>();
        runs.forEach(e -> names.addAll(e.gates().keySet()));
        var gates = new HashMap<String, Certifier.GateCounts>();
        for (var g : names) {
            var counts = runs.stream().map(e -> e.gates().getOrDefault(g, Certifier.GateCounts.EMPTY)).toList();
            var labelled = counts.stream().map(Certifier.GateCounts::labelled)
                    .min((a, b) -> Double.compare(bounds.recallLower(a, tail), bounds.recallLower(b, tail)))
                    .orElse(List.of());
            gates.put(g, new Certifier.GateCounts(highest(counts.stream().map(Certifier.GateCounts::writing).toList(),
                    bounds, Certifier.BASE_GATE_TAIL), labelled));
        }
        var classes = new HashMap<String, List<MemoryCounts>>();
        for (var name : Certifier.V2_CLASSES) {
            classes.put(name, highest(runs.stream().map(e -> e.classes().getOrDefault(name, List.of())).toList(),
                    RecordBounds.INSTANCE, tail));
        }
        return new Certifier.Evaluation(gates, classes,
                highest(runs.stream().map(Certifier.Evaluation::written).toList(), bounds, tail),
                highest(runs.stream().map(Certifier.Evaluation::trap).toList(), RecordBounds.INSTANCE, tail));
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

    /**
     * One certification invocation's inputs. {@code agentId} holds the committed cases as memories for the run and is
     * null for a held-out split, whose cases {@code held} maps by memory id; {@code digests} is each model's Ollama
     * digest.
     */
    public record CertifyRequest(Path root, CertificationSplit split, CertificationSplit.Source source,
                                 OntologySchema schema, @Nullable String agentId, @Nullable String ownerName,
                                 Map<String, HeldOut.HeldCase> held, Sequences sequences, List<DecisionModel> models,
                                 Map<String, String> digests, int runs, long agreedSeed, double agreedShare,
                                 double checkShare, double recallFloor, int concurrency, List<Case> secondLabels,
                                 @Nullable String secondLabelsReason, List<Adjudications.Verdict> adjudications) {
        public CertifyRequest {
            held = Map.copyOf(held);
            models = List.copyOf(models);
            digests = Map.copyOf(digests);
            secondLabels = List.copyOf(secondLabels);
            adjudications = List.copyOf(adjudications);
        }
    }

    /**
     * One model's certification result. {@code stages} are each run's gold-fed stage scores as stored;
     * {@code sequencing} and {@code sequences} are null on a void run; {@code certificate} is the written
     * certificate's id when the status is certified.
     */
    public record CertifiedModel(String model, String digest, int runs, int memoriesChanged, int failedDecisions,
                                 @Nullable SpotCheck spotCheck,
                                 SequenceHarness.@Nullable SequenceSpotCheck sequenceSpotCheck,
                                 List<JsonElement> stages, Certifier.@Nullable Sequencing sequencing, Tallies tallies,
                                 AgreedSample agreedSample, Map<String, Integer> unjudgedByGate, Checks checks,
                                 SequenceHarness.@Nullable ModelReport sequences,
                                 Certifier.@Nullable Power lineagePower, Certifier.Verdict verdict,
                                 @Nullable String certificate, List<Certifier.Requirement> requirements) {
        public CertifiedModel {
            requirements = List.copyOf(requirements);
        }
    }

    /**
     * A certification run's or a re-score's report: counts and fingerprints only, never a timing.
     * {@code bootstrapSeed} and {@code bootstrapResamples} are the recall bootstrap's.
     */
    public record CertificationReport(String kind, String set, String split, String schema, String extraction,
                                      String cases, String sequences, String guide, double startingThreshold,
                                      List<String> relationOrder, int memories, double recallFloor,
                                      Agreement.Result agreement, String sequencesNote, long bootstrapSeed,
                                      int bootstrapResamples, List<CertifiedModel> models) {}

    /** One model scored from its stored run, with the blind sheet and, when certified, the certificate. */
    private record Scored(CertifiedModel model, JsonObject sheet, @Nullable CertificateDocument certificate) {}

    /**
     * Refuses a certification run before it asks anything: a split hash or the sequences fingerprint drifted, or a
     * model's ledger state does not admit {@code runs}.
     *
     * @throws IllegalArgumentException naming the drifted id, or quoting the refusing ledger line
     */
    public static void admit(CertifyRequest req) {
        var drift = req.split().verify(req.source());
        if (drift != null) throw new IllegalArgumentException(drift);
        try {
            for (var m : req.models()) {
                SplitUses.admit(req.root(), req.split().split(), m.name(), digest(req, m), req.runs());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String digest(CertifyRequest req, DecisionModel m) {
        var digest = req.digests().get(m.name());
        if (digest == null) throw new IllegalArgumentException("no digest for model " + m.name());
        return digest;
    }

    public static CertificationReport certify(CertifyRequest req, EvalProgress progress) {
        return certify(req, progress, MemoryBounds.INSTANCE);
    }

    /**
     * One certification invocation: refuses a reuse or a drift, runs the split's set (gold-fed stages, end to end and
     * a single run's spot-check), then the sequence answers with theirs, stores each model's run and appends its
     * ledger line, and scores the stored run: gates, classes and pooled gates with the back-off, the sequences at the
     * configuration reached, the verdict, the blind sheet and, when certified, the certificate.
     */
    public static CertificationReport certify(CertifyRequest req, EvalProgress progress, GateBounds bounds) {
        admit(req);
        var split = req.split();
        var schema = req.schema();
        var cases = split.casesFrom(req.source());
        var measured = split.set().equals(CertificationSplit.SET_HELDOUT)
                ? measureHeld(cases, req.held(), req.ownerName(), schema, req.models(), req.runs(), req.concurrency(),
                        progress)
                : measureStored(Objects.requireNonNull(req.agentId(), "the cases set needs an agent"), cases,
                        req.ownerName(), schema, req.models(), req.runs(), req.concurrency(), progress, false);
        var zone = TimezoneResolver.appZone();
        var answers = SequenceHarness.answers(req.sequences(), schema, req.models(), req.runs(), req.concurrency(),
                progress, zone);
        var agreement = Agreement.compare(cases, req.secondLabels(), schema.symmetricSet())
                .withReason(req.secondLabelsReason());
        var guide = req.source().guide();
        var models = new ArrayList<CertifiedModel>();
        try {
            for (var m : req.models()) {
                var data = measured.measured().getOrDefault(m.name(), List.of());
                var chains = Objects.requireNonNull(answers.get(m.name()));
                var seqSpot = req.runs() == 1 ? SequenceHarness.spotCheck(schema, req.sequences(), m,
                        chains.getFirst(), req.concurrency(), zone) : null;
                var passes = new ArrayList<StoredRun.Pass>();
                for (int r = 0; r < data.size(); r++) {
                    passes.add(new StoredRun.Pass(data.get(r).e2e(), chains.get(r), GSON.toJsonTree(data.get(r).stages())));
                }
                StoredRun.write(req.root(), new StoredRun(split.split(), m.name(), digest(req, m), schema.fingerprint(),
                        ExtractionPipeline.fingerprint(schema), req.agreedSeed(), req.agreedShare(), passes,
                        measured.spotChecks().get(m.name()), seqSpot, measured.integrity().checked()
                        - measured.integrity().unchanged(), req.recallFloor()));
                var stored = StoredRun.read(req.root(), split.split(), m.name(), schema);
                SplitUses.append(req.root(), split.split(), m.name(), digest(req, m), ledgerState(stored));
                var scored = scoreStored(stored, split, cases, req.sequences(), schema, guide, req.adjudications(),
                        req.checkShare(), req.recallFloor(), agreement, bounds, zone);
                writeOutputs(req.root(), split, scored);
                models.add(scored.model());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return report(split, schema, guide, cases.size(), req.recallFloor(), agreement, models);
    }

    private static String ledgerState(StoredRun stored) {
        if (stored.failedDecisions() > 0) return SplitUses.VOID;
        return spotCheckDiffered(stored) ? SplitUses.NEEDS_SECOND_RUN : SplitUses.USED;
    }

    private static boolean spotCheckDiffered(StoredRun stored) {
        var spot = stored.spotCheck();
        var seq = stored.sequenceSpotCheck();
        return stored.passes().size() == 1
                && (spot != null && spot.differing() > 0 || seq != null && seq.differing() > 0);
    }

    /**
     * Scores a stored certification run again under the verdicts on file and its own recall floor, asking no model
     * and redrawing nothing.
     *
     * @throws IllegalArgumentException when a split hash drifted or the stored schema or extraction fingerprint is
     *                                  not the running one
     */
    public static CertificationReport rescore(Path root, CertificationSplit split, CertificationSplit.Source source,
                                              OntologySchema schema, String model, Sequences sequences,
                                              double checkShare, List<Case> secondLabels,
                                              @Nullable String secondLabelsReason,
                                              List<Adjudications.Verdict> adjudications) {
        var drift = split.verify(source);
        if (drift != null) throw new IllegalArgumentException(drift);
        try {
            var stored = StoredRun.read(root, split.split(), model, schema);
            if (!stored.schema().equals(schema.fingerprint())) {
                throw new IllegalArgumentException("the stored run's schema %s differs from the running %s"
                        .formatted(stored.schema(), schema.fingerprint()));
            }
            var extraction = ExtractionPipeline.fingerprint(schema);
            if (!stored.extraction().equals(extraction)) {
                throw new IllegalArgumentException("the stored run's extraction %s differs from the running %s"
                        .formatted(stored.extraction(), extraction));
            }
            var cases = split.casesFrom(source);
            var agreement = Agreement.compare(cases, secondLabels, schema.symmetricSet()).withReason(secondLabelsReason);
            var scored = scoreStored(stored, split, cases, sequences, schema, source.guide(), adjudications,
                    checkShare, stored.recallFloor(), agreement, MemoryBounds.INSTANCE, TimezoneResolver.appZone());
            writeOutputs(root, split, scored);
            return report(split, schema, source.guide(), cases.size(), stored.recallFloor(), agreement,
                    List.of(scored.model()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CertificationReport report(CertificationSplit split, OntologySchema schema, String guide,
                                              int memories, double recallFloor, Agreement.Result agreement,
                                              List<CertifiedModel> models) {
        return new CertificationReport(CERTIFICATION, split.set(), split.split(), schema.fingerprint(),
                ExtractionPipeline.fingerprint(schema), split.cases(), split.sequences(), guide,
                split.startingThreshold(), split.relationOrder(), memories, recallFloor, agreement,
                SequenceHarness.DEVELOPMENT_NOTE, MemoryBounds.BOOTSTRAP_SEED, MemoryBounds.RESAMPLES, models);
    }

    /** The one scoring path both a full run and a re-score take, from the stored form. */
    private static Scored scoreStored(StoredRun stored, CertificationSplit split, List<Case> cases, Sequences sequences,
                                      OntologySchema schema, String guide, List<Adjudications.Verdict> adjudications,
                                      double checkShare, double recallFloor, Agreement.Result agreement,
                                      GateBounds bounds, ZoneId zone) {
        var book = new Adjudications.Book(adjudications, guide, stored.agreedSeed(), stored.agreedShare(), checkShare);
        var start = split.startingThreshold();
        var v2 = v2(cases, stored.passes().stream().map(StoredRun.Pass::cases).toList(), schema, book, start,
                split.relationOrder(), recallFloor, bounds, split.set());
        int failed = stored.failedDecisions();
        var sequencing = failed > 0 ? null : v2.sequencing();
        SequenceHarness.ModelReport seq = null;
        if (sequencing != null) {
            seq = SequenceHarness.scoreAt(schema, sequences.chains(), stored.model(),
                    stored.passes().stream().map(StoredRun.Pass::chains).toList(), sequencing.reached(), start,
                    stored.sequenceSpotCheck(), zone, RecordBounds.INSTANCE);
        }
        var judgement = v2.judgement();
        var verdict = Certifier.verdict(sequencing, new Certifier.VerdictInputs(stored.memoriesChanged() > 0, failed,
                spotCheckDiffered(stored), judgement.labelError(), seq == null ? null : seq.timeline(),
                agreement.complete(), judgement.unjudgedCount(), judgement.uncheckedMarked()));
        CertificateDocument certificate = null;
        if (verdict.status().equals(Certifier.CERTIFIED) && sequencing != null && seq != null) {
            var timeline = seq.runs().stream().map(SequenceHarness.RunReport::endToEnd)
                    .max((a, b) -> Double.compare(a.bound(), b.bound())).orElseThrow();
            certificate = CertificateDocument.of(stored.model(), stored.digest(), verdict.status(), split, guide,
                    stored.schema(), stored.extraction(), sequencing, seq.lineage(), timeline, seq.timeline(),
                    recallFloor);
        }
        var model = new CertifiedModel(stored.model(), stored.digest(), stored.passes().size(),
                stored.memoriesChanged(), failed, stored.spotCheck(), stored.sequenceSpotCheck(),
                stored.passes().stream().map(StoredRun.Pass::stages).toList(), sequencing, v2.tallies(),
                v2.agreedSample(), judgement.unjudgedByGate(), checks(book, judgement), seq,
                seq == null ? null : lineagePower(seq.lineage()), verdict,
                certificate == null ? null : certificate.id(),
                sequencing == null ? List.of() : sequencing.requirements());
        return new Scored(model, sheet(split, guide, book, cases, v2.inScope()), certificate);
    }

    /** Lineage's power at its walked n, as {@link Certifier} gives every other class's; 0 when disabled. */
    private static Certifier.Power lineagePower(ClassWalk lineage) {
        var at = lineage.threshold() == null ? List.<MemoryCounts>of()
                : List.of(new MemoryCounts("", lineage.n(), lineage.k(), 0, 0, 0));
        return Certifier.Power.of(RecordBounds.INSTANCE, at);
    }

    /**
     * The blind sheet: every unmatched written record and every sampled agreed record at the configuration reached,
     * each with its memory's text, sorted by case and record. Nothing on it says which side a record is on or which
     * model wrote it; re-scoring takes each record's side and inclusion from the stored run, so a blind verdict needs
     * neither.
     */
    private static JsonObject sheet(CertificationSplit split, String guide, Adjudications.Book book, List<Case> cases,
                                    List<GraphEvalScorer.GateRecord> inScope) {
        var texts = new HashMap<String, String>();
        cases.forEach(c -> texts.put(c.id(), c.text()));
        var records = new ArrayList<GraphEvalScorer.GateRecord>();
        for (var r : inScope) {
            if (!r.agreed() || book.sampled(r)) records.add(r);
        }
        records.sort((a, b) -> a.caseId().equals(b.caseId()) ? a.record().compareTo(b.record())
                : a.caseId().compareTo(b.caseId()));
        var out = new JsonArray();
        for (var r : records) {
            var o = new JsonObject();
            o.addProperty("caseId", r.caseId());
            o.addProperty("record", r.record());
            o.addProperty("text", texts.get(r.caseId()));
            out.add(o);
        }
        var sheet = new JsonObject();
        sheet.addProperty("split", split.split());
        sheet.addProperty("guide", guide);
        sheet.addProperty("agreedSeed", book.seed());
        sheet.addProperty("agreedShare", book.share());
        sheet.addProperty("drawnAt", Adjudications.DRAWN_AT);
        sheet.add("records", out);
        return sheet;
    }

    public static Path sheetPath(Path root, String split, String model) {
        return root.resolve(SHEETS).resolve(split + "-" + StoredRun.fileName(model) + ".json");
    }

    private static void writeOutputs(Path root, CertificationSplit split, Scored scored) throws IOException {
        var sheet = sheetPath(root, split.split(), scored.model().model());
        Files.createDirectories(sheet.getParent());
        Files.writeString(sheet, GSON.toJson(scored.sheet()));
        var certificate = scored.certificate();
        if (certificate != null) {
            certificate.write(root);
            return;
        }
        var stale = CertificateDocument.path(root, scored.model().model());
        if (!Files.exists(stale)) return;
        try {
            var held = JsonParser.parseString(Files.readString(stale));
            var heldSplit = held.isJsonObject() ? held.getAsJsonObject().get("split") : null;
            if (heldSplit != null && heldSplit.isJsonPrimitive() && heldSplit.getAsString().equals(split.split())) {
                Files.delete(stale);
            }
        } catch (JsonParseException _) {
            // Not a certificate this split wrote; leave it for the reader to refuse.
        }
    }

    /** A held-out split's memories measured where they live, with a single run's spot-check. */
    private static MeasuredCases measureHeld(List<Case> cases, Map<String, HeldOut.HeldCase> held,
                                             @Nullable String ownerName, OntologySchema schema,
                                             List<DecisionModel> models, int runs, int concurrency,
                                             EvalProgress progress) {
        var memoryIds = new LinkedHashMap<String, String>();
        cases.forEach(c -> memoryIds.put(c.id(), c.id()));
        var before = snapshot(memoryIds);
        var known = ownerName == null ? List.<String>of() : List.of(ownerName);
        var measured = measure(cases, schema, models, runs, concurrency, known, ownerName, progress, false, held);
        var spotChecks = new HashMap<String, SpotCheck>();
        if (runs == 1) {
            for (var m : models) {
                var data = measured.getOrDefault(m.name(), List.of());
                if (!data.isEmpty()) {
                    spotChecks.put(m.name(), spotCheck(cases, schema, m, data.getFirst().e2e(), known, ownerName,
                            false, concurrency, held));
                }
            }
        }
        var after = snapshot(memoryIds);
        int unchanged = 0;
        for (var entry : before.entrySet()) {
            var was = entry.getValue();
            if (was != null && was.equals(after.get(entry.getKey()))) unchanged++;
        }
        return new MeasuredCases(measured, spotChecks, new MemoryIntegrity(before.size(), unchanged, List.of(), 0));
    }

    /** Runs {@code tasks} on at most {@code concurrency} threads, returning results in task order. */
    static <T> List<T> fanOut(List<Callable<T>> tasks, int concurrency) {
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
