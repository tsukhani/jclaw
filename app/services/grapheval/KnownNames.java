package services.grapheval;

import memory.graph.GraphStore;
import memory.ontology.OntologyRecord;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;

/** An agent's known spans, the candidate generator's KNOWN input (JCLAW-1372). Read, never written. */
public final class KnownNames {

    private KnownNames() {}

    /** Every Term's name then its aliases, in record order, stripped, without blanks or exact duplicates. */
    public static List<String> of(GraphStore store, long agentId) throws IOException {
        var out = new LinkedHashSet<String>();
        for (var r : store.read(agentId)) {
            if (!(r instanceof OntologyRecord.Term t)) continue;
            add(out, t.name());
            t.aliases().forEach(a -> add(out, a));
        }
        return List.copyOf(out);
    }

    private static void add(LinkedHashSet<String> out, String name) {
        var stripped = name.strip();
        if (!stripped.isEmpty()) out.add(stripped);
    }
}
