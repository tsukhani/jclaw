package memory.graph;

import memory.ontology.EdtfDate;
import memory.ontology.EdtfInterval;
import memory.ontology.EdtfInterval.Point;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Lineage;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Status;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Valence;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Time-aware reads over one agent's record set (JCLAW-1364): combines the per-source claims a Relation's Evidence
 * carries into one answer at a valid date and a system instant. Pure — no I/O, no clock, no mutation; callers pass
 * {@code today} (in {@code TimezoneResolver.appZone()}) and {@code now}. Every returned list is sorted, so the
 * answers do not depend on record order.
 */
public final class GraphView {

    public enum Truth { YES, NO, UNKNOWN }

    public enum Contested { NONE, DEFINITE, GUEST, ASSUMED }

    public enum Reason { STATED, EXPIRED, CHANGED, DENIED, SCHEDULED }

    public enum Timing { UPCOMING, PAST, NEITHER }

    /**
     * One Relation read at a valid date and a system instant.
     *
     * @param assumed the answer rests on persistence past what a source stated
     * @param reason why a NO; null unless {@code truth} is NO
     * @param deciding the deciding claim's Evidence id; null when no claim decides
     * @param others the other visible claims' Evidence ids
     * @param valences the distinct valences of the visible claims
     */
    public record Answer(
            String recordId,
            Truth truth,
            boolean assumed,
            Contested contested,
            @Nullable Reason reason,
            @Nullable String deciding,
            List<String> others,
            @Nullable Valence valence,
            List<Valence> valences) {
        public Answer {
            others = List.copyOf(others);
            valences = List.copyOf(valences);
        }

        /** @throws IllegalStateException unless the truth is NO, which always carries a reason */
        public Reason resolvedReason() {
            if (reason == null) throw new IllegalStateException(recordId + " is " + truth + ", which has no reason");
            return reason;
        }

        /** @throws IllegalStateException when the truth is UNKNOWN, which no claim decides */
        public String resolvedDeciding() {
            if (deciding == null) throw new IllegalStateException(recordId + " is " + truth + ", which no claim decided");
            return deciding;
        }
    }

    public record Current(
            List<Answer> current, List<Answer> upcoming, List<Answer> ended, List<Answer> denied,
            List<Answer> undetermined) {
        public Current {
            current = List.copyOf(current);
            upcoming = List.copyOf(upcoming);
            ended = List.copyOf(ended);
            denied = List.copyOf(denied);
            undetermined = List.copyOf(undetermined);
        }
    }

    public record Occurrence(EdtfInterval value, List<String> relationIds, Timing timing) {
        public Occurrence {
            relationIds = List.copyOf(relationIds);
        }
    }

    public record Occurrences(List<Occurrence> values, List<String> series, List<Occurrence> previous) {
        public Occurrences {
            values = List.copyOf(values);
            series = List.copyOf(series);
            previous = List.copyOf(previous);
        }
    }

    /** What one claim says at D (step 2). */
    private record Verdict(Truth truth, boolean assumed, @Nullable Reason reason) {
        boolean definite() {
            return truth != Truth.UNKNOWN && !assumed;
        }
    }

    private static final Verdict UNKNOWN = new Verdict(Truth.UNKNOWN, false, null);

    private record Claims(List<Evidence> visible, List<Evidence> eligible, List<Evidence> ineligible,
            Map<String, Verdict> verdicts) {}

    private record Persisted(List<Evidence> definite, List<Evidence> surviving, boolean unexplainedDrop) {}

    /** A dated Term's stated values, each with the Relations joined to it, and which values each source stated. */
    private static final class Stated {
        final TreeMap<EdtfInterval, TreeSet<String>> values = new TreeMap<>(BY_LO);
        private final Map<String, List<EdtfInterval>> bySource = new HashMap<>();

        void add(String source, EdtfInterval value) {
            values.computeIfAbsent(value, k -> new TreeSet<>());
            bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(value);
        }

        /** Joins the Relation to every value {@code source} stated; false when it stated none. */
        boolean join(String source, String relationId) {
            var stated = bySource.get(source);
            if (stated == null) return false;
            stated.forEach(v -> Objects.requireNonNull(values.get(v)).add(relationId));
            return true;
        }
    }

    /** Step 4's order: anchor, then recordedAt, then confidence (null below any value), then Evidence id. */
    private static final Comparator<Evidence> RANK = Comparator
            .comparing(Evidence::anchor, Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(Evidence::recordedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
            .thenComparing(Evidence::confidence, Comparator.nullsFirst(Comparator.<Double>naturalOrder()))
            .thenComparing(Evidence::id);

    private static final Comparator<EdtfInterval> BY_LO =
            Comparator.comparing(GraphView::lo).thenComparing(EdtfInterval::toString);

    private final OntologySchema schema;
    private final Map<String, OntologyRecord> byId;
    private final TreeMap<String, Relation> relations = new TreeMap<>();
    private final @Nullable String ownerTermId;

    public GraphView(OntologySchema schema, List<OntologyRecord> records, @Nullable String ownerTermId) {
        this.schema = schema;
        this.ownerTermId = ownerTermId;
        var index = new HashMap<String, OntologyRecord>();
        for (var record : List.copyOf(records)) {
            index.put(record.id(), record);
            if (record instanceof Relation r) relations.put(r.id(), r);
        }
        this.byId = Map.copyOf(index);
    }

    // ---- calls ----

    /** @throws IllegalArgumentException when {@code recordId} names no Relation */
    public Answer at(String recordId, LocalDate d, Instant s) {
        var relation = relations.get(recordId);
        if (relation == null) throw new IllegalArgumentException("'" + recordId + "' is not a Relation in this graph");
        return answer(relation, d, s);
    }

    /** Relations sorted into buckets at D = today and s = now; retired ones and those from a dated Term in none. */
    public Current current(LocalDate today, Instant now) {
        var current = new ArrayList<Answer>();
        var upcoming = new ArrayList<Answer>();
        var ended = new ArrayList<Answer>();
        var denied = new ArrayList<Answer>();
        var undetermined = new ArrayList<Answer>();
        for (var relation : relations.values()) {
            if (fromDated(relation) || retired(relation, now)) continue;
            var a = answer(relation, today, now);
            switch (a.truth()) {
                case YES -> current.add(a);
                case UNKNOWN -> undetermined.add(a);
                case NO -> {
                    switch (a.resolvedReason()) {
                        case SCHEDULED -> upcoming.add(a);
                        case DENIED -> denied.add(a);
                        case STATED, EXPIRED, CHANGED -> ended.add(a);
                    }
                }
            }
        }
        return new Current(current, upcoming, ended, denied, undetermined);
    }

    /** Every Relation, retired ones included, read at {@code d} as the sources stood at {@code s}. */
    public List<Answer> asOf(LocalDate d, Instant s) {
        return relations.values().stream().map(r -> answer(r, d, s)).toList();
    }

    /** Every Evidence visible at {@code s}, sorted by id. */
    public List<Evidence> knownAt(Instant s) {
        return byId.values().stream()
                .filter(r -> r instanceof Evidence e && visible(e, s))
                .map(Evidence.class::cast)
                .sorted(Comparator.comparing(Evidence::id))
                .toList();
    }

    /** @throws IllegalArgumentException when {@code termId} names no Term of a dated type */
    public Occurrences occurrences(String termId, LocalDate today, Instant s) {
        if (!(byId.get(termId) instanceof Term term) || !dated(term)) {
            throw new IllegalArgumentException("'" + termId + "' is not a dated Term in this graph");
        }
        var current = new Stated();
        var previous = new Stated();
        for (var e : resolve(term.evidenceIds())) {
            var occurs = e.occurs();
            if (occurs == null || !visible(e, s)) continue;
            (capped(e, s) ? previous : current).add(e.source(), occurs);
        }
        var series = new TreeSet<String>();
        for (var relation : relations.values()) {
            if (!relation.from().equals(termId) && !relation.to().equals(termId)) continue;
            join(relation, s, current, previous, series);
        }
        return new Occurrences(occurrenceList(current.values, today), List.copyOf(series),
                occurrenceList(previous.values, today));
    }

    // ---- steps ----

    private Answer answer(Relation relation, LocalDate d, Instant s) {
        var claims = claims(relation, d, s);
        var verdicts = claims.verdicts();

        // Step 3: persistence stops at a later stated change.
        var persisted = persisted(claims.eligible(), verdicts, d);

        // Step 4: decide.
        Evidence decided = persisted.definite().stream().max(RANK).orElse(null);
        boolean assumed = false;
        if (decided == null) {
            decided = persisted.surviving().stream().max(RANK).orElse(null);
            assumed = decided != null;
        }
        var verdict = decided == null ? UNKNOWN : Objects.requireNonNull(verdicts.get(decided.id()));
        var truth = verdict.truth();

        // Step 5: flags.
        var contested = contested(claims, persisted, truth);

        var visible = claims.visible();
        var decidingId = decided == null ? null : decided.id();
        var others = visible.stream().map(Evidence::id).filter(id -> !id.equals(decidingId)).toList();
        var valences = visible.stream().map(Evidence::valence).filter(Objects::nonNull).distinct().sorted().toList();
        return new Answer(relation.id(), truth, assumed, contested, truth == Truth.NO ? verdict.reason() : null,
                decidingId, others, decided == null ? null : decided.valence(), valences);
    }

    private Claims claims(Relation relation, LocalDate d, Instant s) {
        boolean timeless = timeless(relation);
        boolean touchesOwner = ownerTermId != null
                && (relation.from().equals(ownerTermId) || relation.to().equals(ownerTermId));
        var visible = resolve(relation.evidenceIds()).stream().filter(e -> visible(e, s)).toList();
        var eligible = new ArrayList<Evidence>();
        var ineligible = new ArrayList<Evidence>();
        var verdicts = new HashMap<String, Verdict>();
        for (var e : visible) {
            verdicts.put(e.id(), verdict(e, d, s, timeless));
            (touchesOwner && guest(e) ? ineligible : eligible).add(e);
        }
        return new Claims(visible, eligible, ineligible, verdicts);
    }

    private static Persisted persisted(List<Evidence> eligible, Map<String, Verdict> verdicts, LocalDate d) {
        var definite = new ArrayList<Evidence>();
        var surviving = new ArrayList<Evidence>();
        boolean unexplainedDrop = false;
        for (var c : eligible) {
            var v = verdicts.get(c.id());
            if (v == null || v.truth() == Truth.UNKNOWN) continue;
            if (v.definite()) {
                definite.add(c);
                continue;
            }
            var stoppers = eligible.stream().filter(x -> later(x, c) && stops(x, v.truth(), d)).toList();
            if (stoppers.isEmpty()) {
                surviving.add(c);
            } else if (stoppers.stream().noneMatch(x -> explains(x, c))) {
                unexplainedDrop = true;
            }
        }
        return new Persisted(definite, surviving, unexplainedDrop);
    }

    private static Contested contested(Claims claims, Persisted persisted, Truth truth) {
        var verdicts = claims.verdicts();
        boolean contestedDefinite = persisted.definite().stream().map(e -> verdicts.get(e.id()))
                .filter(Objects::nonNull).map(Verdict::truth).distinct().count() > 1;
        boolean contestedGuest = (!claims.visible().isEmpty() && claims.eligible().isEmpty())
                || (truth != Truth.UNKNOWN && claims.ineligible().stream().anyMatch(e -> {
                    var v = verdicts.get(e.id());
                    return v != null && v.truth() != Truth.UNKNOWN && v.truth() != truth;
                }));
        if (contestedDefinite) return Contested.DEFINITE;
        if (contestedGuest) return Contested.GUEST;
        if (persisted.unexplainedDrop()) return Contested.ASSUMED;
        return Contested.NONE;
    }

    /** Step 2: what one claim says at {@code d}. */
    private static Verdict verdict(Evidence e, LocalDate d, Instant s, boolean timeless) {
        var status = e.status();
        if (status == null) return UNKNOWN;
        var cap = e.changedBy();
        if (cap != null && capped(e, s) && !d.isBefore(cap)) {
            return status == Status.HOLDS ? new Verdict(Truth.NO, d.isAfter(cap), Reason.CHANGED) : UNKNOWN;
        }
        if (timeless && status == Status.HOLDS) return new Verdict(Truth.YES, false, null);
        var a = e.anchor();
        if (a == null) return UNKNOWN;
        return switch (status) {
            case HOLDS -> holds(d, a, startDate(e), endDate(e), single(e));
            case ENDED -> ended(d, a, startDate(e), endDate(e));
            case DENIED -> denied(d, a, e.valid());
        };
    }

    private static Verdict holds(
            LocalDate d, LocalDate a, @Nullable EdtfDate start, @Nullable EdtfDate end, boolean single) {
        // The validator lets a single date end before its anchor, so its stated end binds at every D.
        if ((single || !d.isBefore(a)) && end != null) {
            if (!d.isBefore(end.hi())) return new Verdict(Truth.NO, true, Reason.EXPIRED);
            if (!d.isBefore(end.lo())) return UNKNOWN;
        }
        if (start != null && start.lo().isAfter(a)) return laterStart(d, a, start);
        if (d.equals(a)) return new Verdict(Truth.YES, false, null);
        if (d.isAfter(a)) return new Verdict(Truth.YES, true, null);
        if (start == null) return UNKNOWN;
        if (d.isBefore(start.lo())) return new Verdict(Truth.NO, true, Reason.SCHEDULED);
        if (d.isBefore(start.hi())) return UNKNOWN;
        return new Verdict(Truth.YES, false, null);
    }

    private static Verdict laterStart(LocalDate d, LocalDate a, EdtfDate start) {
        if (d.isBefore(start.lo())) return new Verdict(Truth.NO, d.isBefore(a), Reason.SCHEDULED);
        if (d.isBefore(start.hi())) return UNKNOWN;
        return new Verdict(Truth.YES, true, null);
    }

    private static Verdict ended(LocalDate d, LocalDate a, @Nullable EdtfDate start, @Nullable EdtfDate end) {
        if (!d.isBefore(a)) return new Verdict(Truth.NO, d.isAfter(a), Reason.STATED);
        if (end != null) {
            if (!d.isBefore(end.hi())) return new Verdict(Truth.NO, false, Reason.STATED);
            if (!d.isBefore(end.lo())) return UNKNOWN;
        }
        if (start != null) {
            if (d.isBefore(start.lo())) return new Verdict(Truth.NO, true, Reason.STATED);
            if (d.isBefore(start.hi())) return UNKNOWN;
            if (end != null && d.isBefore(end.lo())) return new Verdict(Truth.YES, false, null);
        }
        return UNKNOWN;
    }

    private static Verdict denied(LocalDate d, LocalDate a, @Nullable EdtfInterval valid) {
        if (!d.isBefore(a)) return new Verdict(Truth.NO, d.isAfter(a), Reason.DENIED);
        if (valid != null && valid.toString().equals("../" + a)) return new Verdict(Truth.NO, false, Reason.DENIED);
        return UNKNOWN;
    }

    /** Whether {@code x}, a later claim, says a persisted {@code stopped} answer had changed by {@code d}. */
    private static boolean stops(Evidence x, Truth stopped, LocalDate d) {
        var anchor = x.anchor();
        if (anchor == null) return false;
        var status = x.status();
        if (stopped == Truth.YES) {
            if (status == Status.DENIED) return !anchor.isAfter(d);
            if (status != Status.ENDED) return false;
            var end = endDate(x);
            if (end != null) return !end.lo().isAfter(d);
            return endUnknown(x) && !anchor.isAfter(d);
        }
        if (status != Status.HOLDS) return false;
        var start = startDate(x);
        return !anchor.isAfter(d) || (start != null && !start.hi().isAfter(d));
    }

    /** An ended after a holds, a rejoin, or a claim from the memory that retired the stopped one as a successor. */
    private static boolean explains(Evidence stopping, Evidence stopped) {
        if (stopping.status() == Status.ENDED && stopped.status() == Status.HOLDS) return true;
        var start = startDate(stopping);
        var end = endDate(stopped);
        if (stopping.status() == Status.HOLDS && stopped.status() == Status.ENDED && start != null && end != null
                && !start.lo().isBefore(end.lo())) {
            return true;
        }
        var lineage = stopped.lineage();
        return stopping.source().equals(stopped.retiredBy())
                && (lineage == Lineage.UPDATE || lineage == Lineage.RESTATEMENT);
    }

    /** A null anchor is never later; any anchor is later than a null one. */
    private static boolean later(Evidence x, Evidence c) {
        var xa = x.anchor();
        var ca = c.anchor();
        return xa != null && (ca == null || xa.isAfter(ca));
    }

    // ---- step 1 ----

    private static boolean visible(Evidence e, Instant s) {
        var recorded = e.recordedAt();
        if (recorded != null && recorded.isAfter(s)) return false;
        var retired = e.retiredAt();
        return retired == null || s.isBefore(retired)
                || e.lineage() == Lineage.UPDATE || e.lineage() == Lineage.RESTATEMENT;
    }

    /** An update past its retirement; one with no {@code changedBy} has no cap and is not capped. */
    private static boolean capped(Evidence e, Instant s) {
        var retired = e.retiredAt();
        return e.lineage() == Lineage.UPDATE && retired != null && !s.isBefore(retired) && e.changedBy() != null;
    }

    private boolean retired(Relation relation, Instant s) {
        var claims = resolve(relation.evidenceIds());
        return !claims.isEmpty() && claims.stream().noneMatch(e -> visible(e, s));
    }

    /** Only a guest turn is a guest's; a null or UNATTRIBUTED author reads as the owner's. */
    private static boolean guest(Evidence e) {
        return e.authorType() == MemoryAuthorType.GUEST_TURN;
    }

    // ---- schema and records ----

    private List<Evidence> resolve(List<String> ids) {
        var out = new ArrayList<Evidence>();
        for (var id : new TreeSet<>(ids)) {
            if (byId.get(id) instanceof Evidence e) out.add(e);
        }
        return out;
    }

    private @Nullable Term fromTerm(Relation relation) {
        return byId.get(relation.from()) instanceof Term t ? t : null;
    }

    private boolean dated(Term term) {
        var type = schema.termTypes().get(term.type());
        return type != null && type.dated();
    }

    private boolean fromDated(Relation relation) {
        var from = fromTerm(relation);
        return from != null && dated(from);
    }

    /** No valid time and a declared From type that is not dated; anything undeclared reads as not timeless. */
    private boolean timeless(Relation relation) {
        var from = fromTerm(relation);
        if (from == null || !schema.relations().containsKey(relation.type())
                || !schema.termTypes().containsKey(from.type()) || dated(from)) {
            return false;
        }
        return !schema.validAllowed(relation.type(), from.type());
    }

    private static @Nullable EdtfDate startDate(Evidence e) {
        var valid = e.valid();
        return valid != null && valid.start() instanceof Point(var date) ? date : null;
    }

    private static @Nullable EdtfDate endDate(Evidence e) {
        var valid = e.valid();
        return valid != null && valid.end() instanceof Point(var date) ? date : null;
    }

    private static boolean single(Evidence e) {
        var valid = e.valid();
        return valid != null && valid.single();
    }

    private static boolean endUnknown(Evidence e) {
        var valid = e.valid();
        return valid == null || valid.end() instanceof EdtfInterval.Unknown;
    }

    private static LocalDate lo(EdtfInterval v) {
        if (v.start() instanceof Point(var date)) return date.lo();
        if (v.end() instanceof Point(var date)) return date.lo();
        throw new IllegalStateException("an interval needs at least one date: " + v);
    }

    private static LocalDate hi(EdtfInterval v) {
        if (v.end() instanceof Point(var date)) return date.hi();
        if (v.start() instanceof Point(var date)) return date.hi();
        throw new IllegalStateException("an interval needs at least one date: " + v);
    }

    private void join(Relation relation, Instant s, Stated current, Stated previous, Set<String> series) {
        for (var claim : resolve(relation.evidenceIds())) {
            if (!visible(claim, s)) continue;
            boolean inCurrent = current.join(claim.source(), relation.id());
            boolean inPrevious = previous.join(claim.source(), relation.id());
            if (!inCurrent && !inPrevious) series.add(relation.id());
        }
    }

    private static List<Occurrence> occurrenceList(TreeMap<EdtfInterval, TreeSet<String>> values, LocalDate today) {
        var out = new ArrayList<Occurrence>();
        values.forEach((value, ids) -> {
            var timing = timing(value, today);
            out.add(new Occurrence(value, List.copyOf(ids), timing));
        });
        return out;
    }

    private static Timing timing(EdtfInterval value, LocalDate today) {
        if (lo(value).isAfter(today)) return Timing.UPCOMING;
        if (!hi(value).isAfter(today)) return Timing.PAST;
        return Timing.NEITHER;
    }
}
