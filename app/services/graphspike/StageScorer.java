package services.graphspike;

import org.jspecify.annotations.Nullable;
import services.graphspike.ExactMatchResolver.Mention;
import services.graphspike.ExtractionPipeline.Decision;
import services.graphspike.ExtractionPipeline.Overlap;
import services.graphspike.GraphCases.Case;
import services.graphspike.GraphCases.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Scores each extraction stage with gold swapped in for the stages before it (JCLAW-1356), so a stage's score is its
 * own and never the echo of an earlier miss. Pure: no I/O, no clock.
 */
public final class StageScorer {

    /** The yes probability a kept relation needs to count as answered yes. */
    private static final double HALF = 0.5;

    private StageScorer() {}

    /** {@code hit} of {@code total}; {@code rate} is null when {@code total} is zero. */
    public record Ratio(int hit, int total, @Nullable Double rate) {
        public static Ratio of(int hit, int total) {
            return new Ratio(hit, total, total == 0 ? null : (double) hit / total);
        }
    }

    /**
     * One case's answers to the stage questions: the settling of its overlapping candidates, the typing of its gold
     * spans, the relations of its gold terms.
     */
    public record StageRun(String caseId, List<Overlap> overlap, List<Decision> typing, List<Decision> relations) {
        public StageRun {
            overlap = List.copyOf(overlap);
            typing = List.copyOf(typing);
            relations = List.copyOf(relations);
        }
    }

    /**
     * Exact-match resolution over the gold mentions: {@code falseMerges} counts clusters spanning more than one gold
     * id; precision and recall are null when nothing could be measured.
     */
    public record Resolution(int mentions, int clusters, int goldEntities, int falseMerges,
                             @Nullable Double bcubedPrecision, @Nullable Double bcubedRecall,
                             @Nullable Double pairwisePrecision, @Nullable Double pairwiseRecall) {}

    /**
     * {@code overlap} counts the overlap groups settled on a gold span, or on neither when no span is gold;
     * {@code relation} the labelled pairs whose kept relation is the label, in its direction, with yes at least
     * one half; {@code noRelation} the unlabelled pairs whose kept relation has yes below one half.
     */
    public record Stages(Ratio candidateRecall, Ratio overlap, Ratio typing, Ratio rejection, Ratio relation, Ratio noRelation,
                         Resolution resolution, int failures) {}

    /** The non-operator spans the typing stage is asked about, in question order: gold mentions, then negatives. */
    public static List<String> typingSpans(Case c) {
        var out = new ArrayList<String>();
        for (var e : c.entities()) {
            var mention = e.mention();
            if (!e.operator() && mention != null) out.add(mention);
        }
        out.addAll(c.negatives());
        return out;
    }

    /** The gold terms the relation stage is asked about: every entity, the implicit operator included. */
    public static List<ExtractionPipeline.Typed> relationTerms(Case c) {
        return c.entities().stream().map(e -> new ExtractionPipeline.Typed(e.span(), e.type())).toList();
    }

    public static Stages score(List<Case> cases, List<StageRun> runs) {
        var byId = new HashMap<String, Case>();
        cases.forEach(c -> byId.put(c.id(), c));
        int typedRight = 0;
        int typedTotal = 0;
        int rejected = 0;
        int negatives = 0;
        int relationRight = 0;
        int relationTotal = 0;
        int noneRight = 0;
        int noneTotal = 0;
        int failures = 0;
        int overlapRight = 0;
        int overlapTotal = 0;
        for (var run : runs) {
            var c = byId.get(run.caseId());
            if (c == null) continue;
            for (var o : run.overlap()) {
                var d = o.decision();
                if (d.failed()) failures++;
                overlapTotal++;
                boolean anyGold = o.spans().stream().anyMatch(span -> gold(c, span));
                var choice = d.choice();
                if (anyGold ? choice != null && !d.declined() && gold(c, choice)
                        : ExtractionPipeline.NEITHER.equals(choice)) {
                    overlapRight++;
                }
            }
            for (var d : run.typing()) {
                if (d.failed()) failures++;
                var entity = c.entityAt(d.subject());
                if (entity != null && d.subject().equals(entity.mention())) {
                    typedTotal++;
                    if (entity.type().equals(d.choice())) typedRight++;
                } else if (c.negatives().contains(d.subject())) {
                    negatives++;
                    if (ExtractionPipeline.NOT_AN_ENTITY.equals(d.choice())) rejected++;
                }
            }
            for (var d : run.relations()) {
                if (d.failed()) failures++;
                var from = d.from() == null ? null : c.entityAt(d.from());
                var to = d.to() == null ? null : c.entityAt(d.to());
                if (from == null || to == null) continue;
                var forward = c.relation(from.id(), to.id());
                var gold = forward != null ? forward : c.relation(to.id(), from.id());
                boolean yes = !d.failed() && d.confidence() >= HALF;
                if (gold != null) {
                    relationTotal++;
                    boolean directed = forward != null || GraphSpikeScorer.SYMMETRIC.contains(gold.type());
                    if (yes && directed && gold.type().equals(d.choice())) relationRight++;
                } else {
                    noneTotal++;
                    if (!d.failed() && !yes) noneRight++;
                }
            }
        }
        return new Stages(candidateRecall(cases), Ratio.of(overlapRight, overlapTotal), Ratio.of(typedRight, typedTotal), Ratio.of(rejected, negatives),
                Ratio.of(relationRight, relationTotal), Ratio.of(noneRight, noneTotal), resolution(cases), failures);
    }

    private static boolean gold(Case c, String span) {
        var e = c.entityAt(span);
        return e != null && !e.noise();
    }

    /** Gold non-implicit entities whose mention or an alias is one of the case's candidates. */
    public static Ratio candidateRecall(List<Case> cases) {
        int hit = 0;
        int total = 0;
        for (var c : cases) {
            var spans = new HashSet<String>();
            CandidateGenerator.generate(c.text()).forEach(k -> spans.add(k.span()));
            for (var e : c.entities()) {
                if (e.implicit()) continue;
                total++;
                if (spans.stream().anyMatch(e::answersTo)) hit++;
            }
        }
        return Ratio.of(hit, total);
    }

    /** {@link ExactMatchResolver} over every gold entity's mention, scored against the gold ids. */
    public static Resolution resolution(List<Case> cases) {
        var mentions = new ArrayList<Mention>();
        var gold = new HashMap<String, String>();
        for (var c : cases) {
            for (Entity e : c.entities()) {
                var ref = c.id() + "\u0000" + e.id();
                mentions.add(new Mention(ref, e.span(), e.type(), e.operator()));
                gold.put(ref, e.id());
            }
        }
        var clusters = ExactMatchResolver.resolve(mentions);
        var goldSize = new HashMap<String, Integer>();
        gold.values().forEach(id -> goldSize.merge(id, 1, Integer::sum));

        int falseMerges = 0;
        double precision = 0;
        double recall = 0;
        long samePredicted = 0;
        long sameBoth = 0;
        for (var cluster : clusters) {
            var counts = new HashMap<String, Integer>();
            cluster.forEach(ref -> counts.merge(gold.getOrDefault(ref, ""), 1, Integer::sum));
            if (counts.size() > 1) falseMerges++;
            samePredicted += pairs(cluster.size());
            for (var entry : counts.entrySet()) {
                int overlap = entry.getValue();
                sameBoth += pairs(overlap);
                precision += (double) overlap * overlap / cluster.size();
                recall += (double) overlap * overlap / goldSize.getOrDefault(entry.getKey(), 1);
            }
        }
        long sameGold = goldSize.values().stream().mapToLong(StageScorer::pairs).sum();
        int n = mentions.size();
        return new Resolution(n, clusters.size(), goldSize.size(), falseMerges,
                n == 0 ? null : precision / n, n == 0 ? null : recall / n,
                samePredicted == 0 ? null : (double) sameBoth / samePredicted,
                sameGold == 0 ? null : (double) sameBoth / sameGold);
    }

    private static long pairs(long n) {
        return n * (n - 1) / 2;
    }
}
