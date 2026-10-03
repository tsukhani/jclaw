package services.graphspike;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import memory.ontology.OntologySchema;
import play.Play;
import play.db.jpa.JPA;
import services.Tx;
import services.graphspike.GraphCases.Case;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static utils.GsonHolder.GSON;

/**
 * The held-out set (JCLAW-1356): real memories of one agent, sampled read-only into {@code data/graph-eval/} for an
 * operator to label there. Nothing here stores, edits or deletes a memory, and nothing it returns names one.
 */
public final class HeldOut {

    public static final String DIR = "data/graph-eval";
    public static final String FILE = "heldout.json";

    private HeldOut() {}

    /** How many memories were written to the file, of how many active ones the agent has. */
    public record Sampled(int sampled, int available) {}

    /** A labelled held-out case; {@code memoryId} stays inside the harness and never reaches a report. */
    public record HeldCase(long memoryId, Case labels) {}

    public record Loaded(List<HeldCase> cases, int unlabelled) {
        public Loaded {
            cases = List.copyOf(cases);
        }
    }

    public static Path defaultPath() {
        return Play.applicationPath.toPath().resolve(DIR).resolve(FILE);
    }

    public static Sampled sample(String agentId, int count, long seed) throws IOException {
        return sample(defaultPath(), agentId, count, seed);
    }

    /**
     * Writes {@code count} of the agent's active memories, chosen by {@code seed}, to {@code file} unlabelled.
     *
     * @throws IllegalStateException when {@code file} already exists, so a labelled file is never overwritten
     */
    public static Sampled sample(Path file, String agentId, int count, long seed) throws IOException {
        if (Files.exists(file)) throw new IllegalStateException("held-out file already exists; move it aside first");
        long agent = Long.parseLong(agentId);
        List<Object[]> rows = Tx.run(() -> JPA.em().createQuery("SELECT m.id, m.text FROM Memory m "
                        + "WHERE m.agent.id = :agent AND m.supersededAt IS NULL ORDER BY m.id", Object[].class)
                .setParameter("agent", agent).getResultList());
        var shuffled = new ArrayList<>(rows);
        Collections.shuffle(shuffled, new Random(seed));
        var cases = new JsonArray();
        for (var row : shuffled.subList(0, Math.min(count, shuffled.size()))) {
            var text = (String) row[1];
            var o = new JsonObject();
            o.addProperty("memoryId", (Long) row[0]);
            o.addProperty("text", text);
            o.addProperty("labelled", false);
            var candidates = new JsonArray();
            CandidateGenerator.generate(text).forEach(c -> candidates.add(c.span()));
            o.add("candidates", candidates);
            o.add("entities", new JsonArray());
            o.add("relations", new JsonArray());
            cases.add(o);
        }
        var root = new JsonObject();
        root.add("cases", cases);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, GSON.toJson(root));
        return new Sampled(cases.size(), rows.size());
    }

    /**
     * The labelled cases in {@code file}, validated under the v2 rules with tags optional; an unlabelled one is
     * skipped and counted. Refusals name a case by its position, never its memory.
     */
    public static Loaded load(Path file, OntologySchema schema) throws IOException {
        var root = GraphCases.root(Files.readString(file));
        var cases = new ArrayList<HeldCase>();
        var types = new HashMap<String, String>();
        int unlabelled = 0;
        int index = 0;
        for (var element : root.getAsJsonArray("cases")) {
            var id = "h" + index++;
            if (!element.isJsonObject()) throw new IllegalArgumentException("case " + id + ": must be an object");
            var o = element.getAsJsonObject();
            if (!o.has("labelled") || !GraphCases.flag(o, "labelled", "case " + id)) {
                unlabelled++;
                continue;
            }
            long memoryId;
            try {
                memoryId = o.get("memoryId").getAsLong();
            } catch (RuntimeException _) {
                throw new IllegalArgumentException("case " + id + ": 'memoryId' must be a number");
            }
            cases.add(new HeldCase(memoryId, GraphCases.parseCase(o, id, false, schema, types)));
        }
        return new Loaded(cases, unlabelled);
    }
}
