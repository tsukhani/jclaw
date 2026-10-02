package services.graphspike;

import memory.MemoryProvenance;
import memory.MemoryStoreFactory;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.EventLogger;
import services.Tx;
import services.graphspike.ExtractionPipeline.CaseRun;
import services.graphspike.ExtractionPipeline.Decider;
import services.graphspike.GraphCases.Case;
import services.graphspike.GraphSpikeScorer.CurvePoint;
import services.graphspike.GraphSpikeScorer.ModelVerdict;
import services.graphspike.GraphSpikeScorer.PairingRun;
import services.graphspike.GraphSpikeScorer.PairingScore;
import services.graphspike.MentionProposer.Proposal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs every proposer × decision model pairing over the labelled cases (JCLAW-1344). Each case is stored as a real
 * memory of the requested agent for the run, and the harness proves the extraction left every row as it found it.
 * Graph records exist only in the returned report; nothing writes one anywhere.
 */
public final class GraphSpikeHarness {

    private static final String CATEGORY = "graphspike";
    private static final String MEMORY_CATEGORY = "fact";
    private static final double IMPORTANCE = 0.5;

    private GraphSpikeHarness() {}

    public record NamedProposer(String name, MentionProposer proposer) {}

    /** A decision model, or why it cannot run (its pairings are reported skipped and never allowed). */
    public record DecisionModel(String name, @Nullable Decider decider, @Nullable String skipped) {
        public static DecisionModel of(String name, Decider decider) {
            return new DecisionModel(name, decider, null);
        }

        public static DecisionModel skipped(String name, String reason) {
            return new DecisionModel(name, null, reason);
        }
    }

    /**
     * Whether every case memory was byte-for-byte unchanged after the run, by case id; the {@code degraded} fields
     * repeat it for the memories that saw an abstention or a failure.
     */
    public record MemoryIntegrity(int checked, int unchanged, List<String> changed, int degradedChecked,
                                  int degradedUnchanged, List<String> degradedChanged) {}

    /** The whole result. It carries no timings, so a rerun with the same answers is identical. */
    public record Report(double threshold, List<PairingScore> pairings, List<CurvePoint> curve,
                         List<ModelVerdict> models, List<String> allowed, MemoryIntegrity memoryIntegrity,
                         List<PairingRun> runs) {}

    /** The columns a decision must never touch. */
    private record Snapshot(String text, @Nullable String retrievalKey, Instant updatedAt,
                            @Nullable Instant supersededAt, @Nullable Long supersededById) {}

    public static Report run(String agentId, List<Case> cases, OntologySchema schema, List<NamedProposer> proposers,
                             List<DecisionModel> models, double threshold, int concurrency) {
        var store = MemoryStoreFactory.get();
        var provenance = new MemoryProvenance(null, null, MemoryProvenance.process(CATEGORY),
                MemoryAuthorType.AGENT_SYNTHESIZED, List.of());
        var memoryIds = new LinkedHashMap<String, String>();
        try {
            for (var c : cases) {
                memoryIds.put(c.id(), Tx.run(() -> store.storeDeferred(agentId, c.text(), MEMORY_CATEGORY, IMPORTANCE,
                        null, provenance)));
            }
            var before = snapshot(memoryIds);

            var proposalTasks = new ArrayList<Callable<Proposal>>();
            for (var p : proposers) {
                for (var c : cases) proposalTasks.add(() -> propose(p.proposer(), c.text()));
            }
            var proposalList = fanOut(proposalTasks, concurrency);
            var proposals = new HashMap<String, Proposal>();
            int i = 0;
            for (var p : proposers) {
                for (var c : cases) proposals.put(p.name() + "\n" + c.id(), proposalList.get(i++));
            }

            var runTasks = new ArrayList<Callable<CaseRun>>();
            for (var p : proposers) {
                for (var m : models) {
                    var decider = m.decider();
                    if (decider == null) continue;
                    for (var c : cases) {
                        var proposal = Objects.requireNonNull(proposals.get(p.name() + "\n" + c.id()));
                        runTasks.add(() -> ExtractionPipeline.run(schema, c.id(), c.text(), proposal, m.name(), decider,
                                threshold));
                    }
                }
            }
            var caseRuns = fanOut(runTasks, concurrency);

            var runs = new ArrayList<PairingRun>();
            int r = 0;
            for (var p : proposers) {
                for (var m : models) {
                    if (m.decider() == null) {
                        runs.add(new PairingRun(p.name(), m.name(), m.skipped(), List.of()));
                        continue;
                    }
                    var forPairing = new ArrayList<>(caseRuns.subList(r, r + cases.size()));
                    r += cases.size();
                    forPairing.sort(Comparator.comparing(CaseRun::caseId));
                    runs.add(new PairingRun(p.name(), m.name(), null, forPairing));
                }
            }
            runs.sort(Comparator.comparing(PairingRun::proposer).thenComparing(PairingRun::model));

            var integrity = integrity(before, snapshot(memoryIds), degraded(runs));
            var score = GraphSpikeScorer.score(cases, runs, threshold);
            var verdicts = score.models();
            var allowed = score.allowed();
            if (!integrity.changed().isEmpty()) {
                // A run that touched a memory has not degraded safely, whatever its wrong share.
                verdicts = verdicts.stream().map(v -> new ModelVerdict(v.model(), false, v.worstWrongShare(),
                        List.of(), null)).toList();
                allowed = List.of();
            }
            return new Report(threshold, score.pairings(), score.curve(), verdicts, allowed, integrity, runs);
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
    }

    private static Proposal propose(MentionProposer proposer, String text) {
        try {
            return proposer.propose(text);
        } catch (RuntimeException e) {
            return Proposal.failed("proposer threw " + e.getClass().getSimpleName());
        }
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
            throw new IllegalStateException("graph spike interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("graph spike task failed", e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("graph spike task failed", e);
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

    /** Case ids whose memory saw a proposer failure, a failed decision or an abstention in any pairing. */
    private static Set<String> degraded(List<PairingRun> runs) {
        var out = new TreeSet<String>();
        for (var run : runs) {
            for (var c : run.cases()) {
                if (c.proposerFailure() != null || c.decisions().stream().anyMatch(d -> !d.outcome().equals(
                        ExtractionPipeline.WRITTEN) && !d.outcome().equals(ExtractionPipeline.DECLINED))) {
                    out.add(c.caseId());
                }
            }
        }
        return out;
    }

    private static MemoryIntegrity integrity(Map<String, @Nullable Snapshot> before,
                                             Map<String, @Nullable Snapshot> after, Set<String> degraded) {
        var changed = new TreeSet<String>();
        before.forEach((caseId, snapshot) -> {
            if (snapshot == null || !snapshot.equals(after.get(caseId))) changed.add(caseId);
        });
        var degradedChanged = changed.stream().filter(degraded::contains).toList();
        int degradedChecked = (int) before.keySet().stream().filter(degraded::contains).count();
        return new MemoryIntegrity(before.size(), before.size() - changed.size(), List.copyOf(changed),
                degradedChecked, degradedChecked - degradedChanged.size(), degradedChanged);
    }
}
