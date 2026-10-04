package memory.graph;

import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Constraint;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Lineage;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Term;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Withdraws sources from a record set: their Evidence and Mappings go, then the removal
 * cascades until every surviving record still has Evidence and resolves every reference.
 * Also retires a source: its Evidence is stamped with when and by what it was superseded,
 * and nothing is removed.
 */
public final class GraphWithdrawal {

    /** @param removedIds every id the withdrawal removed */
    public record Result(List<OntologyRecord> survivors, Set<String> removedIds) {}

    /** A memory row's supersession as Evidence fields; {@code by} is {@code memory:<supersededById>} or null. */
    public record Retirement(@Nullable Instant at, @Nullable String by) {
        public static final Retirement CLEARED = new Retirement(null, null);

        public static Retirement of(@Nullable Instant supersededAt, @Nullable Long supersededById) {
            if (supersededAt == null) return CLEARED;
            return new Retirement(supersededAt, supersededById == null ? null : GraphStore.memorySource(supersededById));
        }
    }

    /** How a successor relates to its predecessor; {@code changedBy} is written for UPDATE only. */
    public record LineageDecision(Lineage lineage, @Nullable LocalDate changedBy, Retirement retirement) {}

    /**
     * @param evidenceRetired Evidence whose (retiredAt, retiredBy) pair was set, changed or cleared
     * @param lineageCleared  Evidence whose lineage or changedBy was nulled because retiredBy changed
     */
    public record Stamped(List<OntologyRecord> records, int evidenceRetired, int lineageCleared) {}

    public record Lineaged(List<OntologyRecord> records, boolean changed) {}

    private GraphWithdrawal() {}

    public static Result withdraw(Collection<? extends OntologyRecord> records, Set<String> sources) {
        var removed = new TreeSet<String>();
        var current = new ArrayList<OntologyRecord>();
        for (var record : records) {
            var source = GraphStore.sourceOf(record);
            if (source != null && sources.contains(source)) {
                removed.add(record.id());
            } else {
                current.add(record);
            }
        }
        if (removed.isEmpty()) return new Result(List.copyOf(current), Set.of());

        boolean changed = true;
        while (changed) {
            changed = false;
            var next = new ArrayList<OntologyRecord>(current.size());
            for (var record : current) {
                var kept = cascade(record, removed);
                if (kept == null) {
                    removed.add(record.id());
                    changed = true;
                } else {
                    if (!kept.equals(record)) changed = true;
                    next.add(kept);
                }
            }
            current = next;
        }
        return new Result(List.copyOf(current), Set.copyOf(removed));
    }

    /**
     * Stamp each source's Evidence with its retirement, in record order. Changing or clearing
     * an Evidence's {@code retiredBy} also clears its {@code lineage} and {@code changedBy}:
     * they describe a successor it no longer names.
     */
    public static Stamped retire(Collection<? extends OntologyRecord> records, Map<String, Retirement> bySource) {
        var out = new ArrayList<OntologyRecord>(records.size());
        int retired = 0;
        int cleared = 0;
        for (var record : records) {
            if (!(record instanceof Evidence e)) {
                out.add(record);
                continue;
            }
            var r = bySource.get(e.source());
            if (r == null) {
                out.add(e);
                continue;
            }
            var byChanged = !Objects.equals(e.retiredBy(), r.by());
            if (!byChanged && Objects.equals(e.retiredAt(), r.at())) {
                out.add(e);
                continue;
            }
            retired++;
            if (byChanged && (e.lineage() != null || e.changedBy() != null)) cleared++;
            out.add(stamp(e, r.at(), r.by(), byChanged ? null : e.lineage(), byChanged ? null : e.changedBy()));
        }
        return new Stamped(List.copyOf(out), retired, cleared);
    }

    /**
     * Retire each predecessor source as its decision says, then stamp the lineage on every one
     * of its Evidence. An UPDATE dated before a claim's anchor did not change that claim, so the
     * claim takes no lineage.
     */
    public static Lineaged recordLineage(Collection<? extends OntologyRecord> records,
            Map<String, LineageDecision> bySource) {
        var retirements = new HashMap<String, Retirement>();
        bySource.forEach((source, d) -> retirements.put(source, d.retirement()));
        var retired = retire(records, retirements).records();
        var out = new ArrayList<OntologyRecord>(retired.size());
        for (var record : retired) {
            if (!(record instanceof Evidence e)) {
                out.add(record);
                continue;
            }
            var d = bySource.get(e.source());
            if (d == null) {
                out.add(e);
                continue;
            }
            var update = d.lineage() == Lineage.UPDATE;
            var changedBy = update ? d.changedBy() : null;
            var anchor = e.anchor();
            var beforeAnchor = changedBy != null && anchor != null && changedBy.isBefore(anchor);
            out.add(beforeAnchor
                    ? stamp(e, e.retiredAt(), e.retiredBy(), null, null)
                    : stamp(e, e.retiredAt(), e.retiredBy(), d.lineage(), changedBy));
        }
        var result = List.<OntologyRecord>copyOf(out);
        return new Lineaged(result, !result.equals(List.copyOf(records)));
    }

    private static Evidence stamp(Evidence e, @Nullable Instant retiredAt, @Nullable String retiredBy,
            @Nullable Lineage lineage, @Nullable LocalDate changedBy) {
        return new Evidence(e.meta(), e.source(), e.subjectId(), e.authorType(), e.confidence(), e.runId(),
                e.recordedAt(), retiredAt, retiredBy, lineage, changedBy, e.anchor(), e.status(), e.valid(),
                e.occurs(), e.valence());
    }

    /** The record with removed ids pruned, or null when it no longer stands. */
    private static @Nullable OntologyRecord cascade(OntologyRecord record, Set<String> removed) {
        return switch (record) {
            case Term t -> {
                var evidence = prune(t.evidenceIds(), removed);
                yield evidence.isEmpty() ? null
                        : new Term(t.meta(), t.type(), t.name(), prune(t.mappingIds(), removed), evidence,
                                t.aliases(), t.mergedInto());
            }
            case Mapping m -> {
                var evidence = prune(m.evidenceIds(), removed);
                yield evidence.isEmpty() || removed.contains(m.termId()) ? null
                        : new Mapping(m.meta(), m.termId(), m.source(), evidence, m.surfaces());
            }
            case Relation r -> {
                var evidence = prune(r.evidenceIds(), removed);
                yield evidence.isEmpty() || removed.contains(r.from()) || removed.contains(r.to()) ? null
                        : new Relation(r.meta(), r.type(), r.from(), r.to(), r.weight(), evidence);
            }
            case Constraint c -> {
                var evidence = prune(c.evidenceIds(), removed);
                yield evidence.isEmpty() || removed.contains(c.termId()) ? null
                        : new Constraint(c.meta(), c.termId(), c.rule(), evidence);
            }
            case Evidence e -> {
                var subject = e.subjectId();
                yield subject != null && removed.contains(subject) ? null : e;
            }
        };
    }

    private static List<String> prune(List<String> ids, Set<String> removed) {
        return ids.stream().filter(id -> !removed.contains(id)).toList();
    }
}
