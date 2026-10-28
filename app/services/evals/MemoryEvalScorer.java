package services.evals;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores a {@link MemoryEvalSuite} against retrieval results (JCLAW-529).
 *
 * <p>Pure: the caller supplies each case's ranked retrieval, so scoring is deterministic
 * and testable without a store, an index or a model. The retrieval itself comes from
 * {@code SystemPromptAssembler.recall} (JCLAW-937), which is the same pipeline a turn's
 * prompt uses — so a score describes production, not a reimplementation of it.
 *
 * <p>Two metrics, because they answer different questions. <b>Recall</b> asks whether the
 * answer was found at all, which is retrieval quality. <b>Coverage</b> asks what fraction
 * of the distinct facts a question needs actually reached the block — which recall
 * structurally cannot see, since a block holding one fact three times and a block holding
 * three different facts score identically on it.
 */
public final class MemoryEvalScorer {

    private MemoryEvalScorer() {}

    /**
     * One case's outcome.
     *
     * @param rank            1-based position of the first gold memory, or 0 if none was retrieved
     * @param groupsCovered   distinct gold facts with at least one member in the retrieval
     * @param groupsTotal     distinct gold facts the question needs
     */
    public record CaseResult(String caseId, String shape, int rank, int groupsCovered,
                             int groupsTotal, int retrievedCount) {
        public boolean hit(int k) {
            return rank > 0 && rank <= k;
        }
    }

    /**
     * Aggregate over a suite.
     *
     * @param recallAt1   fraction of cases whose top retrieval was already correct
     * @param mrr         mean reciprocal rank — rewards ranking the answer higher, which
     *                    recall@k alone cannot see
     * @param coverageAtK mean fraction of a case's distinct gold facts present in the
     *                    retrieval. 1.0 means every question's facts all reached the block
     * @param missed      cases where no gold memory was retrieved at any depth: retrieval
     *                    failures, as distinct from ranking ones
     */
    public record Report(String suiteId, String fingerprint, int cases,
                         double recallAt1, double recallAt5, double recallAt10,
                         double mrr, double coverageAtK, double meanGoldGroups,
                         List<String> missed, List<ShapeReport> byShape, List<CaseResult> perCase) {}

    /**
     * The aggregate restricted to one case shape (JCLAW-943).
     *
     * <p>Reported alongside the aggregate rather than instead of it, because at these suite
     * sizes a shape-specific regression disappears into the mean: a change that lifts
     * single-fact recall and breaks multi-hop nets out to roughly nothing, and the epic's
     * remaining stories are judged on exactly that distinction.
     */
    public record ShapeReport(String shape, int cases,
                              double recallAt1, double recallAt5, double recallAt10,
                              double mrr, double coverageAtK, int missed) {}

    /** Rank of the first gold id in {@code retrieved}, 1-based; 0 when absent. */
    public static int firstGoldRank(List<Long> retrieved, List<Long> gold) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (gold.contains(retrieved.get(i))) return i + 1;
        }
        return 0;
    }

    /** Distinct gold facts with at least one member present. Within a group, any member counts. */
    public static int groupsCovered(List<Long> retrieved, List<List<Long>> goldGroups) {
        int covered = 0;
        for (var group : goldGroups) {
            if (group.stream().anyMatch(retrieved::contains)) covered++;
        }
        return covered;
    }

    /**
     * @param retrievals ranked memory ids per case, in the same order as
     *                   {@code suite.cases()} — best-first, and long enough to answer the
     *                   deepest k being reported, or recall@10 is measuring the limit
     *                   rather than the retrieval
     */
    public static Report score(MemoryEvalSuite suite, List<List<Long>> retrievals) {
        if (retrievals.size() != suite.cases().size()) {
            throw new IllegalArgumentException(
                    "Expected one retrieval per case: %d cases, %d retrievals"
                            .formatted(suite.cases().size(), retrievals.size()));
        }
        var perCase = new ArrayList<CaseResult>(suite.cases().size());
        var missed = new ArrayList<String>();
        double groupSum = 0;
        for (int i = 0; i < suite.cases().size(); i++) {
            var c = suite.cases().get(i);
            var retrieved = retrievals.get(i);
            int rank = firstGoldRank(retrieved, c.allGoldIds());
            int covered = groupsCovered(retrieved, c.goldGroups());
            int total = c.goldGroups().size();
            perCase.add(new CaseResult(c.id(), c.shape(), rank, covered, total, retrieved.size()));
            groupSum += total;
            if (rank == 0) missed.add(c.id());
        }
        var all = aggregate(perCase);
        // Shapes in first-appearance order, so a report reads in the order the suite was
        // built rather than alphabetically.
        var byShape = perCase.stream().map(CaseResult::shape).distinct()
                .map(shape -> {
                    var forShape = perCase.stream().filter(r -> shape.equals(r.shape())).toList();
                    var agg = aggregate(forShape);
                    return new ShapeReport(shape, forShape.size(), agg.recallAt1(), agg.recallAt5(),
                            agg.recallAt10(), agg.mrr(), agg.coverageAtK(), agg.missed());
                })
                .toList();
        int n = Math.max(1, suite.cases().size());
        return new Report(suite.id(), suite.fingerprint(), suite.cases().size(),
                all.recallAt1(), all.recallAt5(), all.recallAt10(),
                all.mrr(), all.coverageAtK(), groupSum / n,
                List.copyOf(missed), byShape, List.copyOf(perCase));
    }

    /** Metrics over a subset of results. */
    private record Agg(double recallAt1, double recallAt5, double recallAt10,
                       double mrr, double coverageAtK, int missed) {}

    /**
     * The one place the metrics are computed, so the aggregate and every per-shape row are
     * the same arithmetic over different subsets. Two implementations would be free to
     * disagree, and a breakdown that does not reconcile with the total it sits under is
     * worse than no breakdown.
     */
    private static Agg aggregate(List<CaseResult> results) {
        double rrSum = 0;
        double coverageSum = 0;
        int at1 = 0;
        int at5 = 0;
        int at10 = 0;
        int missed = 0;
        for (var r : results) {
            if (r.groupsTotal() > 0) coverageSum += (double) r.groupsCovered() / r.groupsTotal();
            if (r.rank() == 0) {
                missed++;
                continue;
            }
            rrSum += 1.0 / r.rank();
            if (r.rank() <= 1) at1++;
            if (r.rank() <= 5) at5++;
            if (r.rank() <= 10) at10++;
        }
        int n = Math.max(1, results.size());
        return new Agg((double) at1 / n, (double) at5 / n, (double) at10 / n,
                rrSum / n, coverageSum / n, missed);
    }
}
