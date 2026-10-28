package services.grapheval;

import memory.TemporalExpressions;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import services.grapheval.CaseMeasurement.DecisionModel;
import services.grapheval.CaseMeasurement.MemoryIntegrity;
import services.grapheval.CaseMeasurement.RunData;
import services.grapheval.Certifier.Certificate;
import services.grapheval.Certifier.Certification;
import services.grapheval.Certifier.ClassStep;
import services.grapheval.Certifier.ClassWalk;
import services.grapheval.Certifier.Combined;
import services.grapheval.Certifier.SpotCheck;
import services.grapheval.Certifier.Walk;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphEvalScorer.Point;
import services.grapheval.GraphEvalScorer.WrongRecord;
import services.grapheval.StageScorer.Ratio;
import services.grapheval.StageScorer.StageRun;
import services.grapheval.StageScorer.Stages;
import services.grapheval.V2Scoring.DevOptions;
import services.grapheval.V2Scoring.Development;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/**
 * Certifies decision models for graph extraction (JCLAW-1344, JCLAW-1356): every stage and the end-to-end pipeline
 * over the labeled cases, {@code runs} times per model. A synthetic case is stored as a real memory of the requested
 * agent for the run; a held-out one is read where it lives. Either way the harness proves every row was left as it
 * was found. Graph records exist only in the returned report; nothing writes one anywhere. The asking is
 * {@link CaseMeasurement}'s, the v2 sequencing {@link V2Scoring}'s and a frozen split's run {@link CertificationRun}'s.
 */
public final class GraphEvalHarness {

    public static final int DEFAULT_RUNS = 1;
    public static final double LISTING_FLOOR = 0.50;

    private GraphEvalHarness() {}

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
        var stored = CaseMeasurement.measureStored(agentId, cases, ownerName, schema, models, runs, concurrency, progress, pairFilter);
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
            var v2 = V2Scoring.development(cases, data.stream().map(RunData::e2e).toList(), schema, adjudications,
                    options, recallFloor);
            reports.add(new ModelReport(m.name(), scoring.reports(), spot, certification, scoring.listedAt(), wrong,
                    scoring.certificate(), v2));
        }
        return new Report("cases", schema.fingerprint(), ExtractionPipeline.fingerprint(schema), pairFilter,
                cases.size(), runs, recallFloor, agreement, reports, integrity, options.excludedSplitIds());
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
        var before = CaseMeasurement.snapshot(memoryIds);
        var known = ownerName == null ? List.<String>of() : List.of(ownerName);
        var measured = CaseMeasurement.measure(cases, schema, models, runs, concurrency, known, ownerName, progress, pairFilter,
                held);
        var after = CaseMeasurement.snapshot(memoryIds);
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
            var v2 = V2Scoring.development(cases, data.stream().map(RunData::e2e).toList(), schema, adjudications,
                    options, recallFloor);
            reports.add(new HeldOutModel(m.name(), scoring.reports(),
                    Certifier.combine(scoring.reports().stream().map(RunReport::walk).toList()),
                    scoring.certificate(), v2));
        }
        return new HeldOutReport("heldout", schema.fingerprint(), ExtractionPipeline.fingerprint(schema), pairFilter,
                cases.size(), loaded.unlabeled(), runs, recallFloor,
                reports, new HeldOutIntegrity(before.size(), unchanged, present), options.excludedSplitIds(),
                new HeldOutCoverage(CoverageReport.sections(loaded.cases(), questions, schema),
                        CoverageReport.confusions(loaded.cases(), typing)));
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
        var runWalks = data.stream().map(d -> classWalks(cases, d.e2e(), symmetric)).toList();
        var classes = new ArrayList<ClassWalk>();
        if (!runWalks.isEmpty()) {
            int count = runWalks.getFirst().size();
            for (int i = 0; i < count; i++) {
                int k = i;
                classes.add(Certifier.combineClass(runWalks.stream().map(w -> w.get(k)).toList()));
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
            reports.add(new RunReport(r + 1, d.stages(), runWalks.get(r), grids.get(r), walks.get(r),
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
}
