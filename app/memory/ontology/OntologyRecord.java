package memory.ontology;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
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

    /** An entity or concept, grounded by its Mappings. */
    record Term(Meta meta, String type, String name, List<String> mappingIds, List<String> evidenceIds)
            implements OntologyRecord {
        public Term {
            mappingIds = List.copyOf(mappingIds);
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    /** Grounds a Term in a physical source: a memory id, message id or file path. */
    record Mapping(Meta meta, String termId, String source, List<String> evidenceIds) implements OntologyRecord {
        public Mapping {
            evidenceIds = List.copyOf(evidenceIds);
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

    /** Provenance for a claim; {@code subjectId}, when present, names any other record. */
    record Evidence(Meta meta, String source, @Nullable String subjectId) implements OntologyRecord {}
}
