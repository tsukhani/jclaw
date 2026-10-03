package services.grapheval;

import org.jspecify.annotations.Nullable;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.GraphCases.Case;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Strict end-to-end scoring of extraction runs against the case labels (JCLAW-1356). A written term is right only
 * when its span is the mention or an alias of a labelled entity, of the labelled type, written once; a written
 * relation is right only when the labels state it between the entities its endpoints wrote. An implicit operator or
 * one mentioned as "The user" is written by rule rather than decided, so it counts in neither written nor gold, only
 * in {@code ruleWritten}; its span still stands as a relation endpoint. An owner mentioned by name is decided and
 * counted like any other term. Pure: no I/O, no clock.
 */
public final class GraphEvalScorer {

    /** Relations the labels may give in either direction. */
    public static final Set<String> SYMMETRIC = Set.of("same_as", "family_of");
    /** The certification walk's thresholds, highest first: 0.95 to 0.50 by 0.05. */
    public static final List<Double> THRESHOLDS = List.of(0.95, 0.90, 0.85, 0.80, 0.75, 0.70, 0.65, 0.60, 0.55, 0.50);

    public static final String MATCH = "match";
    public static final String TYPE = "type";
    public static final String DUPLICATE = "duplicate";
    public static final String RELATION = "relation";

    private GraphEvalScorer() {}

    /**
     * One wrong record. {@code record} is the adjudication key, {@code term:<span>:<type>} or
     * {@code rel:<from>:<type>:<to>} over the written spans; {@code kind} is match, type, duplicate or relation.
     */
    public record WrongRecord(String caseId, String record, String kind) {}

    /**
     * Every case scored at one threshold. {@code wrongShare} is wrong / (written - noise), null when that is zero;
     * {@code recall} is right / gold, gold counting only non-noise labels. {@code ruleWritten} counts the
     * rule-written operator terms left out of both.
     */
    public record Point(double threshold, int written, int right, int wrong, int wrongMatch, int wrongType,
                        int wrongDuplicate, int wrongRelation, int noise, int gold, @Nullable Double recall,
                        @Nullable Double wrongShare, int failures, int ruleWritten) {}

    public record Scored(Point point, List<WrongRecord> wrong) {
        public Scored {
            wrong = List.copyOf(wrong);
        }
    }

    /** The grid: one point per {@link #THRESHOLDS} entry, in that order. */
    public static List<Point> grid(List<Case> cases, List<CaseRun> runs) {
        return THRESHOLDS.stream().map(t -> score(cases, runs, t).point()).toList();
    }

    /** Every run scored against the case of the same id at threshold {@code t}. */
    public static Scored score(List<Case> cases, List<CaseRun> runs, double t) {
        var byId = new HashMap<String, Case>();
        cases.forEach(c -> byId.put(c.id(), c));
        var tally = new Tally();
        for (var c : cases) tally.gold += gold(c);
        for (var run : runs) {
            var c = byId.get(run.caseId());
            if (c != null) scoreCase(c, run, t, tally);
        }
        int denominator = tally.written - tally.noise;
        var point = new Point(t, tally.written, tally.right, tally.wrong.size(), tally.count(MATCH),
                tally.count(TYPE), tally.count(DUPLICATE), tally.count(RELATION), tally.noise, tally.gold,
                tally.gold == 0 ? null : (double) tally.right / tally.gold,
                denominator == 0 ? null : (double) tally.wrong.size() / denominator, tally.failures,
                tally.ruleWritten);
        return new Scored(point, tally.wrong);
    }

    /** The non-noise labels in {@code c}, terms other than a rule-written operator plus relations. */
    public static int gold(Case c) {
        return (int) (c.entities().stream().filter(e -> !e.noise() && !e.ruleWritten()).count()
                + c.relations().stream().filter(r -> !r.noise()).count());
    }

    private static final class Tally {
        int gold;
        int written;
        int right;
        int noise;
        int failures;
        int ruleWritten;
        final List<WrongRecord> wrong = new ArrayList<>();

        int count(String kind) {
            return (int) wrong.stream().filter(w -> w.kind().equals(kind)).count();
        }
    }

    private static void scoreCase(Case c, CaseRun run, double t, Tally tally) {
        // The written span -> the entity id it rightly wrote; a wrong term writes a span with no id.
        var writtenSpans = new HashSet<String>();
        var ids = new HashMap<String, String>();
        var seen = new HashSet<String>();
        for (var d : run.decisions()) {
            if (d.failed()) tally.failures++;
            if (!d.stage().equals(ExtractionPipeline.TERM) || !d.writes(t)) continue;
            var type = d.choice();
            if (type == null || !writtenSpans.add(d.subject())) continue;
            if (d.operator()) {
                tally.ruleWritten++;
                ids.put(d.subject(), GraphCases.OPERATOR);
                seen.add(GraphCases.OPERATOR);
                continue;
            }
            tally.written++;
            var key = "term:" + d.subject() + ":" + type;
            var entity = c.entityAt(d.subject());
            if (entity == null) {
                tally.wrong.add(new WrongRecord(c.id(), key, MATCH));
            } else if (!seen.add(entity.id())) {
                tally.wrong.add(new WrongRecord(c.id(), key, DUPLICATE));
            } else if (!entity.type().equals(type)) {
                tally.wrong.add(new WrongRecord(c.id(), key, TYPE));
            } else {
                ids.put(d.subject(), entity.id());
                if (entity.noise()) tally.noise++;
                else tally.right++;
            }
        }

        var counted = new HashSet<List<String>>();
        var matched = new HashSet<Object>();
        for (var d : run.decisions()) {
            if (!d.stage().equals(ExtractionPipeline.RELATION) || !d.writes(t)) continue;
            var from = d.from();
            var to = d.to();
            var type = d.choice();
            if (from == null || to == null || type == null) continue;
            if (!writtenSpans.contains(from) || !writtenSpans.contains(to)) continue;
            boolean symmetric = SYMMETRIC.contains(type);
            if (symmetric && counted.contains(List.of(to, type, from))) continue;
            if (!counted.add(List.of(from, type, to))) continue;
            tally.written++;
            var key = "rel:" + from + ":" + type + ":" + to;
            var fromId = ids.get(from);
            var toId = ids.get(to);
            var gold = fromId == null || toId == null ? null : c.relation(fromId, toId);
            if (gold == null || !gold.type().equals(type) || !matched.add(gold)) {
                tally.wrong.add(new WrongRecord(c.id(), key, RELATION));
            } else if (gold.noise()) {
                tally.noise++;
            } else {
                tally.right++;
            }
        }
    }

    /** Wrong records keyed by case and record, deduplicated in first-seen order. */
    public static List<WrongRecord> union(List<List<WrongRecord>> lists) {
        var out = new LinkedHashMap<List<String>, WrongRecord>();
        for (var list : lists) {
            for (var w : list) out.putIfAbsent(List.of(w.caseId(), w.record()), w);
        }
        return List.copyOf(out.values());
    }
}
