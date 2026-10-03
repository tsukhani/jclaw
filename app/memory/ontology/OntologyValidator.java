package memory.ontology;

import memory.ontology.OntologyRecord.Constraint;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Relation;
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
        AXIOM
    }

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

        Run(OntologySchema schema, TreeMap<String, List<OntologyRecord>> byId) {
            this.schema = schema;
            this.byId = byId;
            byId.values().forEach(records -> records.forEach(record -> {
                if (record instanceof Relation r) {
                    relationsByEnds.computeIfAbsent(List.of(r.type(), r.from(), r.to()), k -> new TreeSet<>()).add(r.id());
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
