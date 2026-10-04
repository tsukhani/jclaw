package services.grapheval;

import memory.ontology.OntologyRecord;
import org.jspecify.annotations.Nullable;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.Statements.Classes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * How a run's memory relates to each predecessor it supersedes, at base threshold {@code t} (JCLAW-1365). A
 * predecessor whose lineage stays null reads as retracted. A run any of whose decisions failed derives nothing.
 */
public final class Lineage {

    private Lineage() {}

    /** One predecessor's lineage; {@code changedBy} is the successor's anchor when the lineage wrote. */
    public record Entry(String predecessorId, OntologyRecord.@Nullable Lineage lineage, @Nullable LocalDate changedBy,
                        double confidence) {}

    public record Outcome(boolean failed, List<Entry> entries, int conflict) {
        public Outcome {
            entries = List.copyOf(entries);
        }
    }

    public static Outcome at(CaseRun run, double t, Classes classes) {
        if (Statements.anyFailed(run)) return new Outcome(true, List.of(), 0);
        var threshold = Statements.at(t, classes.lineage());
        int conflict = 0;
        var entries = new ArrayList<Entry>();
        for (var p : run.predecessors()) {
            var d = run.stage(ExtractionPipeline.LINEAGE).stream().filter(x -> p.id().equals(x.to())).findFirst()
                    .orElse(null);
            if (d == null) continue;
            OntologyRecord.Lineage lineage = null;
            if (threshold != null && d.writes(threshold)) {
                lineage = OntologyRecord.Lineage.valueOf(
                        Objects.requireNonNull(d.choice()).toUpperCase(Locale.ROOT));
            }
            // An update cannot change a memory before that memory was written.
            if (lineage == OntologyRecord.Lineage.UPDATE && p.anchor() != null && run.anchor().isBefore(p.anchor())) {
                conflict++;
                lineage = null;
            }
            entries.add(new Entry(p.id(), lineage, lineage == null ? null : run.anchor(), d.confidence()));
        }
        return new Outcome(false, entries, conflict);
    }
}
