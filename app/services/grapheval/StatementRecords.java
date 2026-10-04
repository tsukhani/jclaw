package services.grapheval;

import memory.graph.GraphStore;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Status;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologyRecord.Valence;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * Turns one memory's statements into graph records (JCLAW-1367): one Term per term id and one Relation per relation
 * id, each carrying one Evidence per (record, memory) with the memory's system time, anchor and author. Pure: the
 * records exist only in the returned list.
 */
public final class StatementRecords {

    private StatementRecords() {}

    /** The memory a batch of facts comes from: its id, system time, anchor day and author. */
    public record Source(long memoryId, Instant recordedAt, LocalDate anchor, MemoryAuthorType authorType) {}

    /** A term; the first fact for a {@code termId} fixes its name and type. */
    public record TermFact(String termId, String name, String type, double confidence,
                           @Nullable EdtfInterval occurs) {}

    /** A relation claim between two term ids; a null status claims nothing about whether it holds. */
    public record ClaimFact(String fromId, String type, String toId, @Nullable Status status,
                            @Nullable EdtfInterval valid, @Nullable Valence valence, double confidence) {}

    public record Facts(List<TermFact> terms, List<ClaimFact> claims) {
        public Facts {
            terms = List.copyOf(terms);
            claims = List.copyOf(claims);
        }
    }

    /** {@code rel:<type>:<from>:<to>}, the two ends in lexicographic order for a symmetric type. */
    public static String relationId(OntologySchema schema, String type, String fromId, String toId) {
        if (schema.symmetric(type) && fromId.compareTo(toId) > 0) return "rel:" + type + ":" + toId + ":" + fromId;
        return "rel:" + type + ":" + fromId + ":" + toId;
    }

    /**
     * {@code records} with {@code terms} and {@code claims} from {@code source} added. A second fact on a record from
     * the same source adds nothing, so a mention and its alias, or a symmetric pair stated both ways, claim once.
     *
     * @throws IllegalArgumentException when a claim names a term id neither {@code records} nor {@code terms} holds
     */
    public static List<OntologyRecord> add(OntologySchema schema, List<OntologyRecord> records, Source source,
                                           List<TermFact> terms, List<ClaimFact> claims) {
        var byId = new LinkedHashMap<String, OntologyRecord>();
        records.forEach(r -> byId.put(r.id(), r));
        var memory = GraphStore.memorySource(source.memoryId());
        for (var t : terms) {
            var evidenceId = Evidence.claimId(t.termId(), memory);
            if (byId.containsKey(evidenceId)) continue;
            var term = byId.get(t.termId()) instanceof Term existing ? existing
                    : new Term(Meta.fresh(t.termId(), 0L, Tier.FIRM), t.type(), t.name(), List.of(), List.of());
            byId.put(t.termId(), new Term(term.meta(), term.type(), term.name(), term.mappingIds(),
                    append(term.evidenceIds(), evidenceId), term.aliases(), term.mergedInto()));
            byId.put(evidenceId, evidence(evidenceId, memory, t.termId(), source, t.confidence(), null, null,
                    t.occurs(), null));
        }
        for (var c : claims) {
            for (var end : List.of(c.fromId(), c.toId())) {
                if (!(byId.get(end) instanceof Term)) {
                    throw new IllegalArgumentException("claim " + c.fromId() + " -" + c.type() + "-> " + c.toId()
                            + " names no term '" + end + "'");
                }
            }
            var id = relationId(schema, c.type(), c.fromId(), c.toId());
            var evidenceId = Evidence.claimId(id, memory);
            if (byId.containsKey(evidenceId)) continue;
            var symmetric = schema.symmetric(c.type()) && c.fromId().compareTo(c.toId()) > 0;
            var relation = byId.get(id) instanceof Relation existing ? existing
                    : new Relation(Meta.fresh(id, 0L, Tier.FIRM), c.type(), symmetric ? c.toId() : c.fromId(),
                            symmetric ? c.fromId() : c.toId(), List.of());
            byId.put(id, new Relation(relation.meta(), relation.type(), relation.from(), relation.to(),
                    relation.weight(), append(relation.evidenceIds(), evidenceId)));
            byId.put(evidenceId, evidence(evidenceId, memory, id, source, c.confidence(), c.status(), c.valid(), null,
                    c.valence()));
        }
        return List.copyOf(byId.values());
    }

    /**
     * The facts a run's statements make: its terms, then its positive relations and its denials (status DENIED, with
     * the denial's own valid). {@code termId} maps a span to the term id it is recorded under.
     */
    public static Facts fromStatements(Statements.Outcome terms, List<Statements.Claim> relations,
                                       List<Statements.Claim> denials, Function<String, String> termId) {
        var termFacts = new ArrayList<TermFact>();
        for (var t : terms.terms()) {
            termFacts.add(new TermFact(termId.apply(t.span()), t.span(), t.type(), t.confidence(), t.occurs()));
        }
        var claims = new ArrayList<ClaimFact>();
        for (var c : relations) {
            claims.add(new ClaimFact(termId.apply(c.from()), c.type(), termId.apply(c.to()), c.status(), c.valid(),
                    c.valence(), c.confidence()));
        }
        for (var c : denials) {
            claims.add(new ClaimFact(termId.apply(c.from()), c.type(), termId.apply(c.to()), Status.DENIED, c.valid(),
                    null, c.confidence()));
        }
        return new Facts(termFacts, claims);
    }

    private static Evidence evidence(String id, String memory, String subjectId, Source source, double confidence,
                                     @Nullable Status status, @Nullable EdtfInterval valid,
                                     @Nullable EdtfInterval occurs, @Nullable Valence valence) {
        return new Evidence(Meta.fresh(id, 0L, Tier.FIRM), memory, subjectId, source.authorType(), confidence, null,
                source.recordedAt(), null, null, null, null, source.anchor(), status, valid, occurs, valence);
    }

    private static List<String> append(List<String> ids, String id) {
        var out = new ArrayList<>(ids);
        out.add(id);
        return out;
    }
}
