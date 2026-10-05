package services.grapheval;

import memory.TemporalExpressions;
import memory.TemporalExpressions.DateSpan;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import services.grapheval.ExactMatchResolver.Mention;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.ExtractionPipeline.Overlap;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphCases.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
     * spans, the relations of its gold terms, and the qualifier stages asked on gold: {@code tense} on its labelled
     * dates, {@code negation} on its terms, {@code occurs} on its events and dates, {@code status} and {@code slot} on
     * its relations.
     */
    public record StageRun(String caseId, List<Overlap> overlap, List<Decision> typing, List<Decision> relations,
                           List<Decision> tense, List<Decision> negation, List<Decision> occurs,
                           List<Decision> status, List<Decision> slot) {
        public StageRun {
            overlap = List.copyOf(overlap);
            typing = List.copyOf(typing);
            relations = List.copyOf(relations);
            tense = List.copyOf(tense);
            negation = List.copyOf(negation);
            occurs = List.copyOf(occurs);
            status = List.copyOf(status);
            slot = List.copyOf(slot);
        }

        public StageRun(String caseId, List<Overlap> overlap, List<Decision> typing, List<Decision> relations) {
            this(caseId, overlap, typing, relations, List.of(), List.of(), List.of(), List.of(), List.of());
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
     * one half; {@code noRelation} the pairs with no {@code holds} or admissible {@code ended} label whose kept
     * relation has yes below one half. {@code dateRecall} is the labelled dates the finder found, {@code normalizer}
     * those found with a reading equal to the label; {@code tense}, {@code occurs}, {@code status} and {@code slot}
     * count answers equal to the gold one; {@code negationYes} is yes on denied labels, {@code negationNo} no on
     * positive ones, and {@code vetoRate} {@code unstated} on {@code holds} or admissible {@code ended} labels.
     */
    public record Stages(Ratio candidateRecall, Ratio overlap, Ratio typing, Ratio rejection, Ratio relation, Ratio noRelation,
                         Resolution resolution, int failures, Ratio dateRecall, Ratio normalizer, Ratio tense,
                         Ratio occurs, Ratio status, Ratio slot, Ratio negationYes, Ratio negationNo,
                         Ratio vetoRate) {}

    /**
     * The spans the typing stage is asked about, in question order: gold mentions, the named owner's included, then
     * negatives. Only a rule-written operator is left out.
     */
    public static List<String> typingSpans(Case c) {
        var out = new ArrayList<String>();
        for (var e : c.entities()) {
            var mention = e.mention();
            if (!e.ruleWritten() && mention != null) out.add(mention);
        }
        out.addAll(c.negatives());
        return out;
    }

    /** The gold terms the relation stage is asked about: every entity, the implicit operator included. */
    public static List<ExtractionPipeline.Typed> relationTerms(Case c) {
        return c.entities().stream().map(e -> new ExtractionPipeline.Typed(e.span(), e.type())).toList();
    }

    /**
     * Every stage over {@code runs}; {@code ownerName} is the set's declared owner, null when it declares none.
     * {@code schema} decides which {@code ended} labels are admissible and which relations are symmetric.
     */
    public static Stages score(List<Case> cases, List<StageRun> runs, @Nullable String ownerName,
                               OntologySchema schema) {
        var symmetric = schema.symmetricSet();
        var q = new Qualifiers();
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
                if (!d.stage().equals(ExtractionPipeline.TERM)) continue;
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
                var forward = c.positive(from.id(), to.id(), symmetric);
                var gold = forward != null ? forward : c.positive(to.id(), from.id(), symmetric);
                boolean yes = !d.failed() && d.confidence() >= HALF;
                if (gold != null && c.holdsOrEnded(gold, schema)) {
                    relationTotal++;
                    if (yes && forward != null && gold.type().equals(d.choice())) relationRight++;
                } else {
                    noneTotal++;
                    if (!d.failed() && !yes) noneRight++;
                }
            }
            failures += q.add(c, run, schema);
        }
        return new Stages(candidateRecall(cases, ownerName), Ratio.of(overlapRight, overlapTotal),
                Ratio.of(typedRight, typedTotal), Ratio.of(rejected, negatives), Ratio.of(relationRight, relationTotal),
                Ratio.of(noneRight, noneTotal), resolution(cases, ownerName), failures, dateRecall(cases),
                normalizer(cases), q.tense.done(), q.occurs.done(), q.status.done(), q.slot.done(), q.yes.done(),
                q.no.done(), q.veto.done());
    }

    private static final class Count {
        int hit;
        int total;

        void add(boolean right) {
            total++;
            if (right) hit++;
        }

        Ratio done() {
            return Ratio.of(hit, total);
        }
    }

    /** The gold-fed qualifier stages' tallies. */
    private static final class Qualifiers {
        final Count tense = new Count();
        final Count occurs = new Count();
        final Count status = new Count();
        final Count slot = new Count();
        final Count yes = new Count();
        final Count no = new Count();
        final Count veto = new Count();

        /** Tallies {@code run}'s qualifier decisions against {@code c}; returns how many failed. */
        int add(Case c, StageRun run, OntologySchema schema) {
            var symmetric = schema.symmetricSet();
            int failed = 0;
            var found = new HashMap<String, DateSpan>();
            TemporalExpressions.find(c.text(), c.capturedAt()).found().forEach(d -> found.putIfAbsent(d.span(), d));
            for (var d : run.tense()) {
                if (d.failed()) failed++;
                var label = label(c, d.subject());
                var date = found.get(d.subject());
                if (label == null || date == null || date.readings().size() != 2) continue;
                int index = readingIndex(d.choice());
                tense.add(index >= 0 && date.readings().get(index).toString().equals(label.toString()));
            }
            for (var d : run.negation()) {
                if (d.failed()) failed++;
                var ids = ids(c, d);
                var type = d.choice();
                if (ids == null || type == null) continue;
                boolean said = !d.failed() && d.confidence() >= HALF;
                var denied = c.denied(ids[0], ids[1], symmetric);
                var positive = c.positive(ids[0], ids[1], symmetric);
                if (denied != null && denied.type().equals(type)) {
                    yes.add(said);
                } else if (positive != null && positive.type().equals(type) && c.holdsOrEnded(positive, schema)) {
                    no.add(!d.failed() && !said);
                }
            }
            for (var d : run.occurs()) {
                if (d.failed()) failed++;
                var event = d.from() == null ? null : c.entityAt(d.from());
                if (event == null || d.to() == null || !hasLabel(c, d.to())) continue;
                var label = label(c, d.to());
                var gold = event.occurs();
                boolean expected = label != null && gold != null
                        && label.toString().equals(EdtfInterval.parse(gold).toString());
                occurs.add(!d.failed() && (d.confidence() >= HALF) == expected);
            }
            for (var d : run.status()) {
                if (d.failed()) failed++;
                var gold = goldOf(c, d, d.subject(), symmetric);
                if (gold == null) continue;
                var scored = c.scoredStatus(gold, schema);
                boolean holding = scored.equals(GraphCases.HOLDS) || scored.equals(GraphCases.ENDED);
                status.add(Objects.equals(d.choice(), holding ? scored : ExtractionPipeline.UNSTATED));
                if (holding) veto.add(ExtractionPipeline.UNSTATED.equals(d.choice()));
            }
            for (var d : run.slot()) {
                if (d.failed()) failed++;
                int at = d.subject().lastIndexOf(" @ ");
                if (at < 0) continue;
                var span = d.subject().substring(at + 3);
                var gold = goldOf(c, d, d.subject().substring(0, at), symmetric);
                if (gold == null || gold.denied() || !hasLabel(c, span)) continue;
                slot.add(Objects.equals(d.choice(), slotAnswer(label(c, span), gold.valid())));
            }
            return failed;
        }

        private static int readingIndex(@Nullable String choice) {
            if (ExtractionPipeline.PAST.equals(choice)) return 0;
            return ExtractionPipeline.UPCOMING.equals(choice) ? 1 : -1;
        }

        /** The label a {@code from -type-> to} question was asked on: the positive of that type, else the denial. */
        private static GraphCases.@Nullable Relation goldOf(Case c, Decision d, String triple, Set<String> symmetric) {
            var ids = ids(c, d);
            var from = d.from();
            var to = d.to();
            if (ids == null || from == null || to == null) return null;
            var head = from + " -";
            var tail = "-> " + to;
            if (!triple.startsWith(head) || !triple.endsWith(tail) || triple.length() < head.length() + tail.length()) {
                return null;
            }
            var type = triple.substring(head.length(), triple.length() - tail.length());
            var positive = c.positive(ids[0], ids[1], symmetric);
            if (positive != null && positive.type().equals(type)) return positive;
            var denied = c.denied(ids[0], ids[1], symmetric);
            return denied != null && denied.type().equals(type) ? denied : null;
        }

        private static boolean hasLabel(Case c, String span) {
            return c.dates().stream().anyMatch(d -> d.span().equals(span));
        }

        /** The parsed label of a dated span, or null when it is unlabelled or labelled excluded. */
        private static @Nullable EdtfInterval label(Case c, String span) {
            for (var d : c.dates()) {
                var value = d.value();
                if (d.span().equals(span) && value != null) return EdtfInterval.parse(value);
            }
            return null;
        }

        /** The gold ids of a decision's From and To, or null when either is no gold entity. */
        private static String @Nullable [] ids(Case c, Decision d) {
            var from = d.from() == null ? null : c.entityAt(d.from());
            var to = d.to() == null ? null : c.entityAt(d.to());
            return from == null || to == null ? null : new String[] {from.id(), to.id()};
        }
    }

    /** The answer the slot question has for a date read as {@code date} against a gold {@code valid}. */
    static String slotAnswer(@Nullable EdtfInterval date, @Nullable String valid) {
        if (date == null || valid == null) return ExtractionPipeline.NEITHER;
        var gold = EdtfInterval.parse(valid);
        if (!date.single()) {
            return date.start().equals(gold.start()) && date.end().equals(gold.end()) ? ExtractionPipeline.DURING
                    : ExtractionPipeline.NEITHER;
        }
        if (date.start().equals(gold.start())) return ExtractionPipeline.FROM;
        if (date.start().equals(gold.end())) return ExtractionPipeline.TO;
        return ExtractionPipeline.NEITHER;
    }

    /** The labelled dates (non-null value) whose span the finder found at the case's anchor. */
    public static Ratio dateRecall(List<Case> cases) {
        var count = new Count();
        for (var c : cases) {
            var spans = new HashSet<String>();
            TemporalExpressions.find(c.text(), c.capturedAt()).found().forEach(d -> spans.add(d.span()));
            for (var d : c.dates()) {
                if (d.value() != null) count.add(spans.contains(d.span()));
            }
        }
        return count.done();
    }

    /** Of the labelled dates the finder found, those with some reading equal to the label. */
    public static Ratio normalizer(List<Case> cases) {
        var count = new Count();
        for (var c : cases) {
            var found = TemporalExpressions.find(c.text(), c.capturedAt()).found();
            for (var d : c.dates()) {
                var value = d.value();
                if (value == null) continue;
                var label = EdtfInterval.parse(value).toString();
                var spans = found.stream().filter(f -> f.span().equals(d.span())).toList();
                if (spans.isEmpty()) continue;
                count.add(spans.stream().anyMatch(f -> f.readings().stream().anyMatch(r -> r.toString().equals(label))));
            }
        }
        return count.done();
    }

    private static boolean gold(Case c, String span) {
        var e = c.entityAt(span);
        return e != null && !e.noise();
    }

    /**
     * Gold non-implicit entities whose mention or an alias is one of the case's candidates, generated with
     * {@code ownerName} as a known name.
     */
    public static Ratio candidateRecall(List<Case> cases, @Nullable String ownerName) {
        var known = ownerName == null ? List.<String>of() : List.of(ownerName);
        int hit = 0;
        int total = 0;
        for (var c : cases) {
            var spans = new HashSet<String>();
            CandidateGenerator.generate(c.text(), known).forEach(k -> spans.add(k.span()));
            for (var e : c.entities()) {
                if (e.implicit()) continue;
                total++;
                if (spans.stream().anyMatch(e::answersTo)) hit++;
            }
        }
        return Ratio.of(hit, total);
    }

    /** {@link ExactMatchResolver} over every gold entity's mention, scored against the gold ids. */
    public static Resolution resolution(List<Case> cases, @Nullable String ownerName) {
        var mentions = new ArrayList<Mention>();
        var gold = new HashMap<String, String>();
        for (var c : cases) {
            for (Entity e : c.entities()) {
                var ref = c.id() + "\u0000" + e.id();
                mentions.add(new Mention(ref, e.span(), e.type(), e.ruleWritten()));
                gold.put(ref, e.id());
            }
        }
        var clusters = ExactMatchResolver.resolve(mentions, ownerName);
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
