package services.grapheval;

import memory.MemoryProvenance;
import memory.MemoryStoreFactory;
import memory.TemporalExpressions;
import memory.graph.GraphStore;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.EventLogger;
import services.Tx;
import services.grapheval.Certifier.SpotCheck;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decider;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.GraphCases.Case;
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
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Asks every model about every case: a synthetic case stored as a memory of the agent for the run and deleted after
 * it, a held-out one read where it lives, with each row's guarded columns compared before and after.
 */
public final class CaseMeasurement {

    /** A single run's spot-check asks every tenth case again. */
    public static final int SPOT_CHECK_STRIDE = 10;
    private static final String CATEGORY = "grapheval";
    private static final String MEMORY_CATEGORY = "fact";
    private static final double IMPORTANCE = 0.5;

    private CaseMeasurement() {}

    public record DecisionModel(String name, Decider decider) {}

    /** Whether every case memory was unchanged after the run, by case id, and deleted after it. */
    public record MemoryIntegrity(int checked, int unchanged, List<String> changed, int deleted) {}

    /** The columns a decision must never touch. */
    record Snapshot(String text, @Nullable String retrievalKey, Instant updatedAt,
                    @Nullable Instant supersededAt, @Nullable Long supersededById) {}

    private record CaseResult(StageRun stages, CaseRun e2e) {}

    record RunData(Stages stages, List<CaseRun> e2e, List<StageRun> stageRuns) {}

    /** What a committed-set measurement leaves: every run's data by model, a single run's spot-checks, and integrity. */
    record MeasuredCases(Map<String, List<RunData>> measured, Map<String, SpotCheck> spotChecks,
                         MemoryIntegrity integrity) {}

    /**
     * Stores each case as a memory of the agent, runs every model over them, spot-checks a single run, then deletes
     * them and checks every row was unchanged.
     */
    static MeasuredCases measureStored(String agentId, List<Case> cases, @Nullable String ownerName,
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
     * Every model's runs over {@code cases}, by model name. {@code held} maps a held-out
     * case's id to its sample; it is empty for the committed set.
     */
    static Map<String, List<RunData>> measure(List<Case> cases, OntologySchema schema,
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
        var results = FanOut.run(tasks, concurrency);
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
                        new RunData(stages, e2e, slice.stream().map(CaseResult::stages).toList()));
            }
        }
        return out;
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
        for (var again : FanOut.run(tasks, concurrency)) {
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

        var labeled = new HashSet<String>();
        c.dates().forEach(d -> {
            if (d.value() != null) labeled.add(d.span());
        });
        var dates = TemporalExpressions.find(text, c.capturedAt()).found().stream()
                .filter(d -> labeled.contains(d.span())).toList();
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

    /** A held-out split's memories measured where they live, with a single run's spot-check. */
    static MeasuredCases measureHeld(List<Case> cases, Map<String, HeldOut.HeldCase> held,
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

    /** Each row's guarded columns read straight from the table; a missing row maps to null. */
    static Map<String, @Nullable Snapshot> snapshot(Map<String, String> memoryIds) {
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
