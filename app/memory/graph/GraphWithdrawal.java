package memory.graph;

import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Constraint;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Term;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Withdraws sources from a record set: their Evidence and Mappings go, then the removal
 * cascades until every surviving record still has Evidence and resolves every reference.
 */
public final class GraphWithdrawal {

    /** @param removedIds every id the withdrawal removed */
    public record Result(List<OntologyRecord> survivors, Set<String> removedIds) {}

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

    /** The record with removed ids pruned, or null when it no longer stands. */
    private static @Nullable OntologyRecord cascade(OntologyRecord record, Set<String> removed) {
        return switch (record) {
            case Term t -> {
                var evidence = prune(t.evidenceIds(), removed);
                yield evidence.isEmpty() ? null
                        : new Term(t.meta(), t.type(), t.name(), prune(t.mappingIds(), removed), evidence);
            }
            case Mapping m -> {
                var evidence = prune(m.evidenceIds(), removed);
                yield evidence.isEmpty() || removed.contains(m.termId()) ? null
                        : new Mapping(m.meta(), m.termId(), m.source(), evidence);
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
