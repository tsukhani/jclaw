package services.grapheval;

import memory.ontology.EdtfInterval;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.ExtractionPipeline.Records;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphCases.Relation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Strict end-to-end scoring of extraction runs against the case labels (JCLAW-1356, JCLAW-1366), from what
 * {@link Statements#at} would write. A written term is right only when its span is the mention or an alias of a
 * labelled entity, of the labelled type, written once; a written positive relation is right only when the labels state
 * it, {@code holds} or an admissible {@code ended}, between the entities its endpoints wrote. Status, valid time,
 * occurrence and valence are scored only on parents right at base; denials are scored against denied labels. Records
 * written by rule — the implicit operator or one mentioned as "The user", and a {@code holds} on a relation that cannot
 * end — count nowhere but in {@code ruleWritten} and {@code ruleStatus}. Pure: no I/O, no clock.
 */
public final class GraphEvalScorer {

    /** The certification walk's thresholds, highest first: 0.95 to 0.50 by 0.05. */
    public static final List<Double> THRESHOLDS = List.of(0.95, 0.90, 0.85, 0.80, 0.75, 0.70, 0.65, 0.60, 0.55, 0.50);

    public static final String MATCH = "match";
    public static final String TYPE = "type";
    public static final String DUPLICATE = "duplicate";
    public static final String RELATION = "relation";
    /** A positive written where the labels deny it. */
    public static final String POLARITY = "polarity";
    /** A positive written where the labels say the text does not assert it, or give an ended it cannot take. */
    public static final String UNASSERTED = "unasserted";
    public static final String STATUS = "status";
    public static final String TIME = "time";
    /** A denial written where the labels deny nothing on that triple. */
    public static final String NEGATIVE = "negative";
    public static final String VALENCE = "valence";

    private GraphEvalScorer() {}

    /**
     * One wrong record. {@code record} is the adjudication key over the written spans: {@code term:<span>:<type>},
     * {@code rel:<from>:<type>:<to>}, {@code status:<from>:<type>:<to>:<value>}, {@code valid:<from>:<type>:<to>:<edtf>},
     * {@code occurs:<span>:<edtf>}, {@code neg:<from>:<type>:<to>} or {@code valence:<from>:<to>:<value>}.
     */
    public record WrongRecord(String caseId, String record, String kind) {}

    /**
     * One qualifier class at a threshold: {@code n} values written on base-right parents, of which {@code right} and
     * {@code wrong}; {@code gold} labelled values; {@code recall} is right / gold, null when gold is zero.
     */
    public record ClassTally(int n, int right, int wrong, int gold, @Nullable Double recall) {
        public static final ClassTally EMPTY = new ClassTally(0, 0, 0, 0, null);
    }

    /**
     * Every case scored at one threshold. {@code wrongShare} is base wrong / (written - noise), null when that is zero;
     * {@code recall} is right / gold, gold counting only non-noise entities and {@code holds} or admissible
     * {@code ended} relations. {@code ruleWritten} counts the rule-written operator terms and {@code ruleStatus} the
     * statuses set by rule, both left out of every count. {@code gWritten} and {@code gWrong} are every written item
     * but noise — terms, positives and denials — and those wrong at base or in any written qualifier;
     * {@code trapGold} counts the labelled ended, denied and unasserted relations, and {@code trapViolations} those
     * written as what they are not.
     */
    public record Point(double threshold, int written, int right, int wrong, int wrongMatch, int wrongType,
                        int wrongDuplicate, int wrongRelation, int noise, int gold, @Nullable Double recall,
                        @Nullable Double wrongShare, int failures, int ruleWritten, int wrongPolarity,
                        int wrongUnasserted, int ruleStatus, ClassTally status, ClassTally time, ClassTally negation,
                        ClassTally valence, int gWritten, int gWrong, int trapGold, int trapViolations, int conflict,
                        int vetoed) {

        /** A base-only point: no qualifier, denial or trap counts, so its trap set is empty. */
        public Point(double threshold, int written, int right, int wrong, int wrongMatch, int wrongType,
                     int wrongDuplicate, int wrongRelation, int noise, int gold, @Nullable Double recall,
                     @Nullable Double wrongShare, int failures, int ruleWritten) {
            this(threshold, written, right, wrong, wrongMatch, wrongType, wrongDuplicate, wrongRelation, noise, gold,
                    recall, wrongShare, failures, ruleWritten, 0, 0, 0, ClassTally.EMPTY, ClassTally.EMPTY,
                    ClassTally.EMPTY, ClassTally.EMPTY, written - noise, wrong, 0, 0, 0, 0);
        }
    }

    public record Scored(Point point, List<WrongRecord> wrong) {
        public Scored {
            wrong = List.copyOf(wrong);
        }
    }

    /** The grid: one point per {@link #THRESHOLDS} entry, in that order, each at {@code classes}. */
    public static List<Point> grid(List<Case> cases, List<CaseRun> runs, Set<String> symmetric,
                                   Statements.Classes classes) {
        return THRESHOLDS.stream().map(t -> score(cases, runs, symmetric, t, classes).point()).toList();
    }

    /** Every run scored against the case of the same id at base threshold {@code t} and {@code classes}. */
    public static Scored score(List<Case> cases, List<CaseRun> runs, Set<String> symmetric, double t,
                               Statements.Classes classes) {
        var byId = new HashMap<String, Case>();
        cases.forEach(c -> byId.put(c.id(), c));
        var schema = runs.stream().map(CaseRun::schema).filter(Objects::nonNull).findFirst().orElse(null);
        var tally = new Tally();
        for (var c : cases) tally.addGold(c, schema);
        for (var run : runs) {
            var c = byId.get(run.caseId());
            if (c != null) scoreCase(c, run, symmetric, t, classes, tally);
        }
        int denominator = tally.written - tally.noise;
        int baseWrong = tally.base.size();
        var point = new Point(t, tally.written, tally.right, baseWrong, tally.count(MATCH), tally.count(TYPE),
                tally.count(DUPLICATE), tally.count(RELATION), tally.noise, tally.gold,
                tally.gold == 0 ? null : (double) tally.right / tally.gold,
                denominator == 0 ? null : (double) baseWrong / denominator, tally.failures, tally.ruleWritten,
                tally.count(POLARITY), tally.count(UNASSERTED), tally.ruleStatus, tally.status.done(),
                tally.time.done(), tally.negation.done(), tally.valence.done(), denominator + tally.negation.n,
                tally.gWrong, tally.trapGold, tally.trapViolations, tally.conflict, tally.vetoed);
        var wrong = new ArrayList<>(tally.base);
        wrong.addAll(tally.qualified);
        return new Scored(point, wrong);
    }

    /**
     * The non-noise base labels in {@code c}: terms other than a rule-written operator, plus {@code holds} and
     * admissible {@code ended} relations. With no schema every {@code ended} counts.
     */
    public static int gold(Case c, @Nullable OntologySchema schema) {
        return (int) (c.entities().stream().filter(e -> !e.noise() && !e.ruleWritten()).count()
                + c.relations().stream().filter(r -> !r.noise() && baseGold(c, r, schema)).count());
    }

    private static boolean baseGold(Case c, Relation r, @Nullable OntologySchema schema) {
        if (schema == null) return r.status().equals(GraphCases.HOLDS) || r.status().equals(GraphCases.ENDED);
        return c.holdsOrEnded(r, schema);
    }

    private static final class Counter {
        int n;
        int right;
        int gold;

        void add(boolean isRight) {
            n++;
            if (isRight) right++;
        }

        ClassTally done() {
            return new ClassTally(n, right, n - right, gold, gold == 0 ? null : (double) right / gold);
        }
    }

    private static final class Tally {
        int gold;
        int written;
        int right;
        int noise;
        int failures;
        int ruleWritten;
        int ruleStatus;
        int gWrong;
        int trapGold;
        int trapViolations;
        int conflict;
        int vetoed;
        final Counter status = new Counter();
        final Counter time = new Counter();
        final Counter negation = new Counter();
        final Counter valence = new Counter();
        final List<WrongRecord> base = new ArrayList<>();
        final List<WrongRecord> qualified = new ArrayList<>();

        int count(String kind) {
            return (int) base.stream().filter(w -> w.kind().equals(kind)).count();
        }

        void addGold(Case c, @Nullable OntologySchema schema) {
            gold += GraphEvalScorer.gold(c, schema);
            for (var e : c.entities()) {
                if (!e.noise() && e.occurs() != null) time.gold++;
            }
            for (var r : c.relations()) {
                if (r.noise()) continue;
                if (r.denied()) negation.gold++;
                if (!r.status().equals(GraphCases.HOLDS)) trapGold++;
                if (!baseGold(c, r, schema)) continue;
                var from = c.entity(r.from());
                if (schema != null && from != null
                        && schema.effectiveStatuses(r.type(), from.type()).contains(ExtractionPipeline.ENDED)) {
                    status.gold++;
                }
                if (r.valid() != null) time.gold++;
                if (r.valence() != null) valence.gold++;
            }
        }
    }

    private static void scoreCase(Case c, CaseRun run, Set<String> symmetric, double t, Statements.Classes classes,
                                  Tally tally) {
        var outcome = Statements.at(run, t, classes);
        if (outcome.failed()) {
            tally.failures += (int) run.decisions().stream().filter(Decision::failed).count();
            return;
        }
        var schema = Objects.requireNonNull(run.schema());
        tally.conflict += outcome.conflict();
        tally.vetoed += (int) Records.vetoed(run).stream().filter(d -> d.writes(t)).count();
        var operatorSpans = new HashSet<String>();
        var types = new HashMap<String, String>();
        for (var d : run.stage(ExtractionPipeline.TERM)) {
            if (d.operator()) operatorSpans.add(d.subject());
            var choice = d.choice();
            if (choice != null && !d.declined()) types.putIfAbsent(d.subject(), choice);
        }

        // The written span -> the entity id it rightly wrote; a wrong term writes a span with no id.
        var writtenSpans = new HashSet<String>();
        var ids = new HashMap<String, String>();
        var seen = new HashSet<String>();
        for (var term : outcome.terms()) {
            if (!writtenSpans.add(term.span())) continue;
            if (operatorSpans.contains(term.span())) {
                tally.ruleWritten++;
                ids.put(term.span(), GraphCases.OPERATOR);
                seen.add(GraphCases.OPERATOR);
                continue;
            }
            tally.written++;
            var key = "term:" + term.span() + ":" + term.type();
            var entity = c.entityAt(term.span());
            if (entity == null) {
                base(c, tally, key, MATCH);
            } else if (!seen.add(entity.id())) {
                base(c, tally, key, DUPLICATE);
            } else if (!entity.type().equals(term.type())) {
                base(c, tally, key, TYPE);
            } else {
                ids.put(term.span(), entity.id());
                if (entity.noise()) {
                    tally.noise++;
                    continue;
                }
                tally.right++;
                var occurs = term.occurs();
                if (occurs != null && !qualifier(c, tally, tally.time, "occurs:" + term.span() + ":" + occurs,
                        TIME, same(entity.occurs(), occurs))) {
                    tally.gWrong++;
                }
            }
        }

        var violated = new HashSet<Relation>();
        var counted = new HashSet<List<String>>();
        var matched = new HashSet<Relation>();
        for (var claim : outcome.relations()) {
            var from = claim.from();
            var to = claim.to();
            var type = claim.type();
            if (!writtenSpans.contains(from) || !writtenSpans.contains(to)) continue;
            if (symmetric.contains(type) && counted.contains(List.of(to, type, from))) continue;
            if (!counted.add(List.of(from, type, to))) continue;
            var fromType = types.get(from);
            boolean decidedStatus = fromType != null
                    && schema.effectiveStatuses(type, fromType).contains(ExtractionPipeline.ENDED);
            if (!decidedStatus && claim.status() != null) tally.ruleStatus++;
            tally.written++;
            var triple = from + ":" + type + ":" + to;
            var fromId = ids.get(from);
            var toId = ids.get(to);
            var positive = fromId == null || toId == null ? null : c.positive(fromId, toId, symmetric);
            var denied = fromId == null || toId == null ? null : c.denied(fromId, toId, symmetric);
            if (positive != null && positive.type().equals(type) && matched.add(positive)) {
                var gold = c.scoredStatus(positive, schema);
                if (gold.equals(GraphCases.UNASSERTED)) {
                    base(c, tally, "rel:" + triple, UNASSERTED);
                    violated.add(positive);
                    continue;
                }
                if (positive.noise()) {
                    tally.noise++;
                    continue;
                }
                tally.right++;
                boolean wrong = false;
                var status = claim.status();
                if (decidedStatus && status != null) {
                    var value = lower(status);
                    wrong |= !qualifier(c, tally, tally.status, "status:" + triple + ":" + value, STATUS,
                            value.equals(gold));
                    if (gold.equals(GraphCases.ENDED) && status == OntologyRecord.Status.HOLDS) violated.add(positive);
                }
                var valid = claim.valid();
                if (valid != null) {
                    wrong |= !qualifier(c, tally, tally.time, "valid:" + triple + ":" + valid, TIME,
                            same(positive.valid(), valid));
                }
                var valence = claim.valence();
                if (valence != null) {
                    var value = lower(valence);
                    wrong |= !qualifier(c, tally, tally.valence, "valence:" + from + ":" + to + ":" + value, VALENCE,
                            value.equals(positive.valence()));
                }
                if (wrong) tally.gWrong++;
            } else if (denied != null && denied.type().equals(type)) {
                base(c, tally, "rel:" + triple, POLARITY);
                violated.add(denied);
            } else {
                base(c, tally, "rel:" + triple, RELATION);
            }
        }

        var countedDenials = new HashSet<List<String>>();
        for (var claim : outcome.denials()) {
            var from = claim.from();
            var to = claim.to();
            var type = claim.type();
            if (!writtenSpans.contains(from) || !writtenSpans.contains(to)) continue;
            if (symmetric.contains(type) && countedDenials.contains(List.of(to, type, from))) continue;
            if (!countedDenials.add(List.of(from, type, to))) continue;
            var fromId = ids.get(from);
            var toId = ids.get(to);
            var denied = fromId == null || toId == null ? null : c.denied(fromId, toId, symmetric);
            boolean right = denied != null && denied.type().equals(type);
            if (!qualifier(c, tally, tally.negation, "neg:" + from + ":" + type + ":" + to, NEGATIVE, right)) {
                tally.gWrong++;
            }
            var positive = fromId == null || toId == null ? null : c.positive(fromId, toId, symmetric);
            if (positive != null && positive.type().equals(type)
                    && c.scoredStatus(positive, schema).equals(GraphCases.UNASSERTED)) {
                violated.add(positive);
            }
        }
        for (var r : violated) {
            if (!r.noise() && !r.status().equals(GraphCases.HOLDS)) tally.trapViolations++;
        }
    }

    private static void base(Case c, Tally tally, String key, String kind) {
        tally.base.add(new WrongRecord(c.id(), key, kind));
        tally.gWrong++;
    }

    /** Counts a qualifier value written on a base-right parent; returns whether it was right. */
    private static boolean qualifier(Case c, Tally tally, Counter counter, String key, String kind, boolean right) {
        counter.add(right);
        if (!right) tally.qualified.add(new WrongRecord(c.id(), key, kind));
        return right;
    }

    private static boolean same(@Nullable String label, EdtfInterval written) {
        return label != null && EdtfInterval.parse(label).toString().equals(written.toString());
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
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
