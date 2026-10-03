package memory.ontology;

import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A knowledge-graph record. The five families are the permits; a new family is a new permit
 * plus a switch arm in {@link OntologyValidator}. Term and relation types are strings checked
 * against the loaded {@link OntologySchema}.
 */
public sealed interface OntologyRecord
        permits OntologyRecord.Term,
                OntologyRecord.Mapping,
                OntologyRecord.Relation,
                OntologyRecord.Constraint,
                OntologyRecord.Evidence {

    /** Constant until JCLAW-1291 introduces graph versions. */
    int SEED_GRAPH_VERSION = 1;

    double NEUTRAL_WEIGHT = 1.0;

    enum Tier {
        TENTATIVE,
        FIRM
    }

    /** A claim's status; the lower-case names are the schema's {@code claims.status} keys. */
    enum Status {
        HOLDS,
        ENDED,
        DENIED
    }

    /** How a successor relates to the row it retires; the schema's {@code system_time.lineage} keys. */
    enum Lineage {
        UPDATE,
        RESTATEMENT,
        CORRECTION
    }

    /** The schema's {@code claims.valence} keys. */
    enum Valence {
        FAVORABLE,
        UNFAVORABLE
    }

    /** Fields every record carries; the recall counters are maintained by the evolution loop. */
    record Meta(
            String id,
            long agentId,
            Tier tier,
            int usefulRecallCount,
            @Nullable Instant lastUsefulRecall,
            int graphVersion) {

        public static Meta fresh(String id, long agentId, Tier tier) {
            return new Meta(id, agentId, tier, 0, null, SEED_GRAPH_VERSION);
        }
    }

    Meta meta();

    default String id() {
        return meta().id();
    }

    /**
     * An entity or concept, grounded by its Mappings. {@code aliases} lists every accepted surface;
     * {@code mergedInto} names the Term a merge copied this one's Mappings and Evidence into.
     */
    record Term(
            Meta meta,
            String type,
            String name,
            List<String> mappingIds,
            List<String> evidenceIds,
            List<String> aliases,
            @Nullable String mergedInto)
            implements OntologyRecord {
        public Term {
            mappingIds = List.copyOf(mappingIds);
            evidenceIds = List.copyOf(evidenceIds);
            aliases = List.copyOf(aliases);
        }

        public Term(Meta meta, String type, String name, List<String> mappingIds, List<String> evidenceIds) {
            this(meta, type, name, mappingIds, evidenceIds, List.of(), null);
        }
    }

    /**
     * Grounds a Term in a physical source: a memory id, message id or file path. {@code surfaces}
     * holds the mention's verbatim strings, never offsets, and is empty for a Mapping written by rule.
     */
    record Mapping(Meta meta, String termId, String source, List<String> evidenceIds, List<String> surfaces)
            implements OntologyRecord {
        public Mapping {
            evidenceIds = List.copyOf(evidenceIds);
            surfaces = List.copyOf(surfaces);
        }

        public Mapping(Meta meta, String termId, String source, List<String> evidenceIds) {
            this(meta, termId, source, evidenceIds, List.of());
        }
    }

    /** A typed edge between two Terms. */
    record Relation(Meta meta, String type, String from, String to, double weight, List<String> evidenceIds)
            implements OntologyRecord {
        public Relation {
            evidenceIds = List.copyOf(evidenceIds);
        }

        public Relation(Meta meta, String type, String from, String to, List<String> evidenceIds) {
            this(meta, type, from, to, NEUTRAL_WEIGHT, evidenceIds);
        }
    }

    /** A valid-use rule attached to a Term. */
    record Constraint(Meta meta, String termId, String rule, List<String> evidenceIds) implements OntologyRecord {
        public Constraint {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    /**
     * Provenance for a record; {@code subjectId}, when present, names any other record. Evidence
     * carrying any of {@code status}, {@code valid}, {@code occurs} or {@code valence} is
     * claim-bearing: what {@code source} states about its subject.
     */
    record Evidence(
            Meta meta,
            String source,
            @Nullable String subjectId,
            @Nullable MemoryAuthorType authorType,
            @Nullable Double confidence,
            @Nullable String runId,
            @Nullable Instant recordedAt,
            @Nullable Instant retiredAt,
            @Nullable String retiredBy,
            @Nullable Lineage lineage,
            @Nullable LocalDate changedBy,
            @Nullable LocalDate anchor,
            @Nullable Status status,
            @Nullable EdtfInterval valid,
            @Nullable EdtfInterval occurs,
            @Nullable Valence valence)
            implements OntologyRecord {
        public Evidence(Meta meta, String source, @Nullable String subjectId) {
            this(meta, source, subjectId, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        /**
         * The id of the one claim-bearing Evidence per (record, source). The length prefix keeps
         * ({@code a:b}, {@code c}) and ({@code a}, {@code b:c}) apart.
         */
        public static String claimId(String recordId, String source) {
            return "ev:" + recordId.length() + ":" + recordId + ":" + source;
        }
    }
}
