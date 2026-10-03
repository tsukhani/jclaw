package services.grapheval;

import memory.MemoryProvenance;
import memory.MemoryStoreFactory;
import memory.graph.GraphStore;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.EventLogger;
import services.Tx;
import services.grapheval.Certifier.Adjudication;
import services.grapheval.Certifier.Certification;
import services.grapheval.Certifier.Combined;
import services.grapheval.Certifier.SpotCheck;
import services.grapheval.Certifier.Walk;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decider;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphEvalScorer.Point;
import services.grapheval.GraphEvalScorer.WrongRecord;
import services.grapheval.StageScorer.StageRun;
import services.grapheval.StageScorer.Stages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    /** One run of one model: the gold-fed stages, the end-to-end grid and the certification walk over it. */
    public record RunReport(int run, Stages stages, List<Point> grid, Walk walk) {}

    /**
     * One model over the committed set. {@code spotCheck} is a single run's cases asked again, null for several runs.
     * {@code wrongRecords} are every run's wrong records at the certified threshold, or at {@code wrongRecordsAt} 0.50
     * when nothing certified, for adjudication.
     */
    public record ModelReport(String model, List<RunReport> runs, @Nullable SpotCheck spotCheck,
                              Certification certification, double wrongRecordsAt, List<WrongRecord> wrongRecords) {}

    /**
     * The committed set's whole result. It carries no timings, so a rerun with the same answers is identical.
     * {@code schema} is the seed's {@link OntologySchema#fingerprint()}: a certificate is void under any other.
     */
    public record Report(String set, String schema, int cases, int runs, double recallFloor,
                         Agreement.Result agreement, List<ModelReport> models, MemoryIntegrity memoryIntegrity) {}

    /** One model over the held-out set; the walk is information only, since only the committed set certifies. */
    public record HeldOutModel(String model, List<RunReport> runs, Combined walk) {}

    /** The held-out set's result: aggregate counts only, never an id, a text or a span. */
    public record HeldOutReport(String set, String schema, int cases, int unlabelled, int runs, double recallFloor,
                                List<HeldOutModel> models, HeldOutIntegrity memoryIntegrity) {}

    /** The columns a decision must never touch. */
    private record Snapshot(String text, @Nullable String retrievalKey, Instant updatedAt,
                            @Nullable Instant supersededAt, @Nullable Long supersededById) {}

    private record CaseResult(StageRun stages, CaseRun e2e) {}

    private record RunData(RunReport report, List<CaseRun> e2e) {}

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
            measured = measure(cases, schema, models, runs, recallFloor, concurrency, known, ownerName, progress);
            if (runs == 1) {
                for (var m : models) {
                    var data = measured.getOrDefault(m.name(), List.of());
                    if (!data.isEmpty()) {
                        spotChecks.put(m.name(), spotCheck(cases, schema, m, data.getFirst().e2e(), known, concurrency));
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

        var agreement = Agreement.compare(cases, secondLabels);
        var reports = new ArrayList<ModelReport>();
        for (var m : models) {
            var data = measured.getOrDefault(m.name(), List.of());
            var walks = data.stream().map(d -> d.report().walk()).toList();
            var threshold = Certifier.combine(walks).threshold();
            double listedAt = threshold == null ? LISTING_FLOOR : threshold;
            var wrong = GraphEvalScorer.union(data.stream()
                    .map(d -> GraphEvalScorer.score(cases, d.e2e(), listedAt).wrong()).toList());
            var spot = spotChecks.get(m.name());
            var certification = Certifier.certify(walks, !changed.isEmpty(), agreement.complete(),
                    threshold == null ? List.of() : wrong, adjudications, spot);
            reports.add(new ModelReport(m.name(), data.stream().map(RunData::report).toList(), spot, certification,
                    listedAt, wrong));
        }
        return new Report("cases", schema.fingerprint(), cases.size(), runs, recallFloor, agreement, reports, integrity);
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
        var cases = loaded.cases().stream().map(HeldOut.HeldCase::labels).toList();
        var memoryIds = new LinkedHashMap<String, String>();
        loaded.cases().forEach(h -> memoryIds.put(h.labels().id(), String.valueOf(h.memoryId())));
        var before = snapshot(memoryIds);
        var known = ownerName == null ? List.<String>of() : List.of(ownerName);
        var measured = measure(cases, schema, models, runs, recallFloor, concurrency, known, ownerName, progress);
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
            var data = measured.getOrDefault(m.name(), List.of());
            var runReports = data.stream().map(RunData::report).toList();
            reports.add(new HeldOutModel(m.name(), runReports,
                    Certifier.combine(runReports.stream().map(RunReport::walk).toList())));
        }
        return new HeldOutReport("heldout", schema.fingerprint(), cases.size(), loaded.unlabelled(), runs, recallFloor,
                reports, new HeldOutIntegrity(before.size(), unchanged, present));
    }

    /** Every model's runs over {@code cases}, by model name. */
    private static Map<String, List<RunData>> measure(List<Case> cases, OntologySchema schema,
                                                      List<DecisionModel> models, int runs, double recallFloor,
                                                      int concurrency, List<String> knownNames,
                                                      @Nullable String ownerName, EvalProgress progress) {
        progress.plan(models.stream().map(DecisionModel::name).toList(), runs, cases.size());
        var tasks = new ArrayList<Callable<CaseResult>>();
        int pass = 0;
        for (int r = 0; r < runs; r++) {
            for (var m : models) {
                int p = pass++;
                for (var c : cases) {
                    tasks.add(() -> {
                        progress.caseStarted(p);
                        var result = askCase(schema, c, m, knownNames);
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
                var stages = StageScorer.score(cases, slice.stream().map(CaseResult::stages).toList(), ownerName);
                var grid = GraphEvalScorer.grid(cases, e2e);
                out.computeIfAbsent(m.name(), _ -> new ArrayList<>()).add(
                        new RunData(new RunReport(r + 1, stages, grid, Certifier.walk(grid, recallFloor)), e2e));
            }
        }
        return out;
    }

    /**
     * Asks every {@link #SPOT_CHECK_STRIDE}th case again, from the first, and counts the decisions that differ from
     * {@code first}, the run's own: a different answer, a failure, or a decision only one of the two made.
     */
    private static SpotCheck spotCheck(List<Case> cases, OntologySchema schema, DecisionModel m, List<CaseRun> first,
                                       List<String> knownNames, int concurrency) {
        var sample = new ArrayList<Case>();
        for (int i = 0; i < cases.size(); i += SPOT_CHECK_STRIDE) sample.add(cases.get(i));
        var tasks = new ArrayList<Callable<CaseRun>>();
        for (var c : sample) {
            tasks.add(() -> ExtractionPipeline.run(schema, c.id(), c.text(),
                    CandidateGenerator.generate(c.text(), knownNames), m.name(), m.decider()));
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
                + stages.typing().stream().filter(ExtractionPipeline.Decision::failed).count()
                + stages.relations().stream().filter(ExtractionPipeline.Decision::failed).count()
                + result.e2e().decisions().stream().filter(ExtractionPipeline.Decision::failed).count());
    }

    /** The agent's Term names: a Term carries no aliases, so its name is the only known span. Read, never written. */
    private static List<String> knownNames(String agentId) {
        try {
            return GraphStore.get().read(Long.parseLong(agentId)).stream()
                    .filter(r -> r instanceof OntologyRecord.Term)
                    .map(r -> ((OntologyRecord.Term) r).name()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The overlap stage over generated candidates, the gold-fed typing and relation stages, then the end-to-end
     * pipeline from generated candidates.
     */
    private static CaseResult askCase(OntologySchema schema, Case c, DecisionModel m, List<String> knownNames) {
        var candidates = CandidateGenerator.generate(c.text(), knownNames);
        var overlap = ExtractionPipeline.settle(c.text(),
                candidates.stream().filter(k -> !k.operator()).toList(), m.name(), m.decider());
        var typing = ExtractionPipeline.type(schema, c.text(), StageScorer.typingSpans(c), m.name(), m.decider());
        var relations = ExtractionPipeline.relate(schema, c.text(), StageScorer.relationTerms(c), m.name(),
                m.decider());
        var e2e = ExtractionPipeline.run(schema, c.id(), c.text(), candidates, m.name(), m.decider());
        return new CaseResult(new StageRun(c.id(), overlap, typing, relations.decisions()), e2e);
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
