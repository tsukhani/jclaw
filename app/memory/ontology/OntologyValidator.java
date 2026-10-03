package memory.ontology;

import memory.ontology.EdtfInterval.Open;
import memory.ontology.EdtfInterval.Point;
import memory.ontology.EdtfInterval.Unknown;
import memory.ontology.OntologyRecord.Constraint;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Lineage;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Status;
import memory.ontology.OntologyRecord.Term;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Checks a record set against an {@link OntologySchema} with no model, network or database
 * call. The output is sorted by (recordId, kind, message), so input order and duplicated
 * input records never change it.
 */
public final class OntologyValidator {

    /** Mirrors the schema document's {@code references}; {@code OntologySchemaTest} pins the two equal. */
    public static final List<String> STRUCTURAL_REFERENCES = List.of(
            "Term -> Mapping",
            "Mapping -> Term",
            "Relation -> Term",
            "Constraint -> Term",
            "Term, Mapping, Relation, Constraint -> Evidence",
            "Evidence -> Any");

    /** Mirrors the schema document's {@code claims.status} keys; {@code OntologySchemaTest} pins the two equal. */
    public static final List<String> STATUSES = List.of("holds", "ended", "denied");

    /** Mirrors the schema document's {@code system_time.lineage} keys; {@code OntologySchemaTest} pins the two equal. */
    public static final List<String> LINEAGES = List.of("update", "restatement", "correction");

    public enum Kind {
        UNDECLARED_TYPE,
        DISALLOWED_ENDPOINT,
        UNRESOLVED_REFERENCE,
        MISSING_EVIDENCE,
        MISSING_SOURCE,
        DUPLICATE_ID,
        AXIOM,
        CLAIM_LINK,
        CLAIM_NOT_ALLOWED,
        INVALID_INTERVAL,
        SYSTEM_TIME,
        PROVENANCE_MISSING,
        DUPLICATE_CLAIM
    }

    private static final Pattern MEMORY_SOURCE = Pattern.compile("memory:[0-9]+");

    public record Violation(String recordId, Kind kind, String message) {}

    private static final Comparator<Violation> ORDER = Comparator.comparing(Violation::recordId)
            .thenComparing(Violation::kind)
            .thenComparing(Violation::message);

    private OntologyValidator() {}

    public static List<Violation> validate(OntologySchema schema, Collection<? extends OntologyRecord> records) {
        var byId = new TreeMap<String, List<OntologyRecord>>();
        for (var record : new LinkedHashSet<OntologyRecord>(records)) {
            byId.computeIfAbsent(record.id(), id -> new ArrayList<>()).add(record);
        }
        var run = new Run(schema, byId);
        byId.forEach((id, shared) -> {
            if (shared.size() > 1) {
                run.add(id, Kind.DUPLICATE_ID, "id '" + id + "' is shared by " + shared.size() + " records");
            }
            shared.forEach(run::check);
        });
        return run.violations.stream().sorted(ORDER).distinct().toList();
    }

    private static final class Run {
        private final OntologySchema schema;
        private final TreeMap<String, List<OntologyRecord>> byId;
        private final List<Violation> violations = new ArrayList<>();
        /** Relation ids keyed by (type, from, to), for the symmetric check's reverse lookup. */
        private final Map<List<String>, TreeSet<String>> relationsByEnds = new HashMap<>();
        /** The ids of the records listing each evidence id in their {@code evidenceIds}. */
        private final Map<String, TreeSet<String>> listers = new HashMap<>();
        /** Claim-bearing Evidence ids keyed by (source, subjectId). */
        private final Map<List<String>, TreeSet<String>> claimsBySourceAndSubject = new HashMap<>();

        Run(OntologySchema schema, TreeMap<String, List<OntologyRecord>> byId) {
            this.schema = schema;
            this.byId = byId;
            byId.values().forEach(records -> records.forEach(record -> {
                if (record instanceof Relation r) {
                    relationsByEnds.computeIfAbsent(List.of(r.type(), r.from(), r.to()), k -> new TreeSet<>()).add(r.id());
                }
                var subjectId = record instanceof Evidence e && claimBearing(e) ? e.subjectId() : null;
                if (record instanceof Evidence e && subjectId != null) {
                    claimsBySourceAndSubject.computeIfAbsent(List.of(e.source(), subjectId), k -> new TreeSet<>())
                            .add(e.id());
                }
                for (var evidenceId : evidenceIdsOf(record)) {
                    listers.computeIfAbsent(evidenceId, k -> new TreeSet<>()).add(record.id());
                }
            }));
        }

        void add(String recordId, Kind kind, String message) {
            violations.add(new Violation(recordId, kind, message));
        }

        void check(OntologyRecord record) {
            switch (record) {
                case Term term -> {
                    if (!schema.termTypes().containsKey(term.type())) undeclared(term, term.type());
                    for (var mappingId : term.mappingIds()) {
                        var mappings = resolve(term, "mappingIds", mappingId, Mapping.class);
                        if (!mappings.isEmpty()
                                && mappings.stream().noneMatch(m -> m.termId().equals(term.id()))) {
                            var grounded = new TreeSet<String>();
                            mappings.forEach(m -> grounded.add(m.termId()));
                            add(term.id(), Kind.UNRESOLVED_REFERENCE,
                                    label(term) + ": mappingIds '" + mappingId + "' grounds '"
                                            + String.join("', '", grounded) + "', not this term");
                        }
                    }
                    evidence(term, term.evidenceIds());
                }
                case Mapping mapping -> {
                    resolve(mapping, "termId", mapping.termId(), Term.class);
                    source(mapping, mapping.source());
                    evidence(mapping, mapping.evidenceIds());
                }
                case Relation relation -> checkRelation(relation);
                case Constraint constraint -> {
                    resolve(constraint, "termId", constraint.termId(), Term.class);
                    evidence(constraint, constraint.evidenceIds());
                }
                case Evidence evidence -> {
                    source(evidence, evidence.source());
                    systemTime(evidence);
                    if (claimBearing(evidence)) {
                        checkClaim(evidence);
                        return;
                    }
                    var subjectId = evidence.subjectId();
                    if (subjectId == null) return;
                    if (subjectId.equals(evidence.id())) {
                        add(evidence.id(), Kind.UNRESOLVED_REFERENCE,
                                label(evidence) + ": subjectId '" + subjectId + "' names the evidence itself");
                    } else {
                        resolve(evidence, "subjectId", subjectId, OntologyRecord.class);
                    }
                }
            }
        }

        private void systemTime(Evidence e) {
            var recordedAt = e.recordedAt();
            var retiredAt = e.retiredAt();
            var retiredBy = e.retiredBy();
            var changedBy = e.changedBy();
            var anchor = e.anchor();
            if (recordedAt != null && retiredAt != null && retiredAt.isBefore(recordedAt)) {
                add(e.id(), Kind.SYSTEM_TIME,
                        label(e) + ": retiredAt " + retiredAt + " is before recordedAt " + recordedAt);
            }
            if (retiredBy != null && retiredAt == null) {
                add(e.id(), Kind.SYSTEM_TIME, label(e) + ": retiredBy is set without retiredAt");
            }
            if (retiredBy != null && !MEMORY_SOURCE.matcher(retiredBy).matches()) {
                add(e.id(), Kind.SYSTEM_TIME, label(e) + ": retiredBy '" + retiredBy + "' is not memory:<id>");
            }
            if (e.lineage() != null && retiredBy == null) {
                add(e.id(), Kind.SYSTEM_TIME, label(e) + ": lineage is set without retiredBy");
            }
            if (changedBy != null && e.lineage() != Lineage.UPDATE) {
                add(e.id(), Kind.SYSTEM_TIME, label(e) + ": changedBy is set but lineage is not update");
            }
            if (changedBy != null && anchor != null && changedBy.isBefore(anchor)) {
                add(e.id(), Kind.SYSTEM_TIME, label(e) + ": changedBy " + changedBy + " is before anchor " + anchor);
            }
        }

        private void checkClaim(Evidence e) {
            var subjects = claimSubjects(e);
            if (!subjects.isEmpty()) {
                claimTargets(e, subjects);
                claimAllowed(e, subjects);
            }
            var subjectId = e.subjectId();
            var group = subjectId == null ? null : claimsBySourceAndSubject.get(List.of(e.source(), subjectId));
            if (group != null && group.size() > 1) {
                var others = new TreeSet<>(group);
                others.remove(e.id());
                add(e.id(), Kind.DUPLICATE_CLAIM, label(e) + ": " + e.source() + " already claims about '"
                        + subjectId + "' through " + String.join(", ", others));
            }
            intervals(e);
            var source = e.source();
            if (source.startsWith("memory:") || source.startsWith("message:")) {
                if (e.recordedAt() == null) provenance(e, "recordedAt");
                if (e.anchor() == null) provenance(e, "anchor");
                if (e.authorType() == null) provenance(e, "authorType");
            }
        }

        private void provenance(Evidence e, String field) {
            add(e.id(), Kind.PROVENANCE_MISSING, label(e) + ": claim from " + e.source() + " has no " + field);
        }

        /** The records under a claim's subjectId, empty after reporting a null, self or unresolved subject. */
        private List<OntologyRecord> claimSubjects(Evidence e) {
            var subjectId = e.subjectId();
            if (subjectId == null) {
                add(e.id(), Kind.CLAIM_LINK, label(e) + ": a claim needs a subjectId");
                return List.of();
            }
            if (subjectId.equals(e.id())) {
                add(e.id(), Kind.CLAIM_LINK, label(e) + ": subjectId '" + subjectId + "' names the evidence itself");
                return List.of();
            }
            var found = byId.get(subjectId);
            if (found == null) {
                add(e.id(), Kind.CLAIM_LINK, label(e) + ": subjectId '" + subjectId + "' is unresolved");
                return List.of();
            }
            return found;
        }

        private void claimTargets(Evidence e, List<OntologyRecord> subjects) {
            var subjectId = e.subjectId();
            if (subjects.stream().noneMatch(s -> evidenceIdsOf(s).contains(e.id()))) {
                add(e.id(), Kind.CLAIM_LINK, label(e) + ": subject '" + subjectId + "' does not list it in evidenceIds");
            }
            var others = new TreeSet<>(listers.getOrDefault(e.id(), new TreeSet<>()));
            others.remove(subjectId);
            if (!others.isEmpty()) {
                add(e.id(), Kind.CLAIM_LINK, label(e) + ": a claim about '" + subjectId + "' is also listed by "
                        + String.join(", ", others));
            }
            if ((e.status() != null || e.valid() != null) && subjects.stream().noneMatch(Relation.class::isInstance)) {
                add(e.id(), Kind.CLAIM_LINK, label(e) + ": status and valid need a relation subject");
            }
            if (e.occurs() != null) {
                boolean dated = false;
                boolean undeclared = false;
                for (var s : subjects) {
                    if (s instanceof Term t) {
                        var type = schema.termTypes().get(t.type());
                        if (type == null) undeclared = true;
                        else if (type.dated()) dated = true;
                    }
                }
                if (!dated && !undeclared) {
                    add(e.id(), Kind.CLAIM_LINK, label(e) + ": occurs needs a term of a dated type");
                }
            }
            if (e.valence() != null) {
                boolean valenced = false;
                boolean undeclared = false;
                for (var s : subjects) {
                    if (s instanceof Relation r) {
                        var type = schema.relations().get(r.type());
                        if (type == null) undeclared = true;
                        else if (type.valence()) valenced = true;
                    }
                }
                if (!valenced && !undeclared) {
                    add(e.id(), Kind.CLAIM_LINK, label(e) + ": valence needs a relation of a type that takes one");
                }
            }
        }

        /** Judged only against a declared relation whose From is a Term of declared type; any From type may allow it. */
        private void claimAllowed(Evidence e, List<OntologyRecord> subjects) {
            var pairs = new ArrayList<List<String>>();
            for (var s : subjects) {
                if (!(s instanceof Relation r) || !schema.relations().containsKey(r.type())) continue;
                for (var from : byId.getOrDefault(r.from(), List.of())) {
                    if (from instanceof Term t && schema.termTypes().containsKey(t.type())) {
                        pairs.add(List.of(r.type(), t.type()));
                    }
                }
            }
            if (pairs.isEmpty()) return;
            var status = e.status();
            var valid = e.valid();
            if (status != null) {
                var name = status.name().toLowerCase(Locale.ROOT);
                if (pairs.stream().noneMatch(p -> schema.effectiveStatuses(p.get(0), p.get(1)).contains(name))) {
                    add(e.id(), Kind.CLAIM_NOT_ALLOWED, label(e) + ": status " + name + " is not allowed on "
                            + pairs.getFirst().get(0) + " from " + pairs.getFirst().get(1));
                }
            }
            if (valid != null && pairs.stream().noneMatch(p -> schema.validAllowed(p.get(0), p.get(1)))) {
                add(e.id(), Kind.CLAIM_NOT_ALLOWED, label(e) + ": valid is not allowed on "
                        + pairs.getFirst().get(0) + " from " + pairs.getFirst().get(1));
            }
            if (status == Status.DENIED && valid != null) {
                var anchor = e.anchor();
                if (!(valid.start() instanceof Open) || valid.single()) {
                    add(e.id(), Kind.CLAIM_NOT_ALLOWED, label(e) + ": a denial's valid " + valid
                            + " is not an open start ../YYYY-MM-DD");
                } else if (anchor != null && !valid.toString().equals("../" + anchor)) {
                    add(e.id(), Kind.CLAIM_NOT_ALLOWED, label(e) + ": a denial's valid " + valid
                            + " does not end on its anchor " + anchor);
                }
            }
        }

        /** A denial's valid is {@link #claimAllowed}'s; an interval that cannot be constructed is the codec's. */
        private void intervals(Evidence e) {
            var status = e.status();
            var valid = e.valid();
            var anchor = e.anchor();
            if (valid != null && status != Status.DENIED) {
                if (valid.start() instanceof Open) {
                    add(e.id(), Kind.INVALID_INTERVAL, label(e) + ": valid " + valid + " has an open start");
                }
                var end = valid.end();
                if (status == Status.HOLDS) {
                    if (!valid.single() && end instanceof Unknown) {
                        add(e.id(), Kind.INVALID_INTERVAL,
                                label(e) + ": valid " + valid + " holds but its end is unknown");
                    } else if (!valid.single() && anchor != null && end instanceof Point(var d)
                            && !d.hi().isAfter(anchor)) {
                        add(e.id(), Kind.INVALID_INTERVAL,
                                label(e) + ": valid " + valid + " holds but ends before its anchor " + anchor);
                    }
                }
                if (status == Status.ENDED) {
                    if (end instanceof Open) {
                        add(e.id(), Kind.INVALID_INTERVAL, label(e) + ": valid " + valid + " ended but has an open end");
                    }
                    if (anchor != null && end instanceof Point(var d) && d.lo().isAfter(anchor)) {
                        add(e.id(), Kind.INVALID_INTERVAL,
                                label(e) + ": valid " + valid + " ended after its anchor " + anchor);
                    }
                    if (anchor != null && !valid.single() && valid.start() instanceof Point(var d)
                            && d.lo().isAfter(anchor)) {
                        add(e.id(), Kind.INVALID_INTERVAL,
                                label(e) + ": valid " + valid + " starts after its anchor " + anchor);
                    }
                }
            }
            var occurs = e.occurs();
            if (occurs != null && !occurs.single()
                    && !(occurs.start() instanceof Point && occurs.end() instanceof Point)) {
                add(e.id(), Kind.INVALID_INTERVAL,
                        label(e) + ": occurs " + occurs + " is neither a date nor a closed interval");
            }
        }

        private void checkRelation(Relation relation) {
            var declared = schema.relations().containsKey(relation.type());
            if (!declared) undeclared(relation, relation.type());
            var fromTypes = declaredTypes(resolve(relation, "from", relation.from(), Term.class));
            var toTypes = declaredTypes(resolve(relation, "to", relation.to(), Term.class));
            evidence(relation, relation.evidenceIds());
            if (declared) axioms(relation);
            if (!declared || fromTypes.isEmpty() || toTypes.isEmpty()) return;
            for (var fromType : fromTypes) {
                for (var toType : toTypes) {
                    if (schema.allows(relation.type(), fromType, toType)) return;
                }
            }
            add(relation.id(), Kind.DISALLOWED_ENDPOINT,
                    label(relation) + ": " + relation.type() + " does not allow " + fromTypes.first() + " -> "
                            + toTypes.first());
        }

        /** A self-loop is judged only as irreflexive, so {@code A family_of A} is reported once. */
        private void axioms(Relation relation) {
            var type = relation.type();
            if (relation.from().equals(relation.to())) {
                if (schema.irreflexive(type)) {
                    add(relation.id(), Kind.AXIOM, label(relation) + ": " + type
                            + " is irreflexive, but from and to are both '" + relation.from() + "'");
                }
                return;
            }
            if (!schema.symmetric(type)) return;
            var reverse = relationsByEnds.get(List.of(type, relation.to(), relation.from()));
            if (reverse == null) return;
            add(relation.id(), Kind.AXIOM, label(relation) + ": " + type + " is symmetric, and "
                    + String.join(", ", reverse) + " states it the other way round");
        }

        /** Declared term types among the resolved Terms; an undeclared one is reported on its own Term. */
        private TreeSet<String> declaredTypes(List<Term> terms) {
            var types = new TreeSet<String>();
            for (var term : terms) {
                if (schema.termTypes().containsKey(term.type())) types.add(term.type());
            }
            return types;
        }

        private static boolean claimBearing(Evidence e) {
            return e.status() != null || e.valid() != null || e.occurs() != null || e.valence() != null;
        }

        private static List<String> evidenceIdsOf(OntologyRecord record) {
            return switch (record) {
                case Term t -> t.evidenceIds();
                case Mapping m -> m.evidenceIds();
                case Relation r -> r.evidenceIds();
                case Constraint c -> c.evidenceIds();
                case Evidence _ -> List.of();
            };
        }

        private void undeclared(OntologyRecord record, String type) {
            add(record.id(), Kind.UNDECLARED_TYPE, label(record) + ": type '" + type + "' is not declared");
        }

        private void source(OntologyRecord record, String source) {
            if (source.isBlank()) add(record.id(), Kind.MISSING_SOURCE, label(record) + ": source is blank");
        }

        private void evidence(OntologyRecord record, List<String> evidenceIds) {
            if (evidenceIds.isEmpty()) {
                add(record.id(), Kind.MISSING_EVIDENCE, label(record) + ": evidenceIds is empty");
            }
            for (var evidenceId : evidenceIds) resolve(record, "evidenceIds", evidenceId, Evidence.class);
        }

        /**
         * The records under {@code id} of the expected family. With a duplicated id any one of
         * the right family satisfies the reference, so the result does not depend on input order.
         */
        private <T extends OntologyRecord> List<T> resolve(
                OntologyRecord owner, String field, String id, Class<T> family) {
            var found = byId.get(id);
            if (found == null) {
                add(owner.id(), Kind.UNRESOLVED_REFERENCE,
                        label(owner) + ": " + field + " '" + id + "' is unresolved");
                return List.of();
            }
            var matches = found.stream().filter(family::isInstance).map(family::cast).toList();
            if (matches.isEmpty()) {
                var families = new TreeSet<String>();
                found.forEach(r -> families.add(r.getClass().getSimpleName()));
                add(owner.id(), Kind.UNRESOLVED_REFERENCE,
                        label(owner) + ": " + field + " '" + id + "' is " + article(String.join(" and ", families))
                                + ", not " + article(family.getSimpleName()));
            }
            return matches;
        }

        private static String article(String noun) {
            return ("AEIOU".indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun;
        }

        private static String label(OntologyRecord record) {
            return record.getClass().getSimpleName().toLowerCase(Locale.ROOT) + " " + record.id();
        }
    }
}
