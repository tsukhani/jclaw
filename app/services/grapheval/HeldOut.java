package services.grapheval;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import memory.AnchorResolver;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import play.Play;
import play.db.jpa.JPA;
import services.Tx;
import services.grapheval.GraphCases.Case;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

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

    /** The held-out author type for a memory with none. */
    public static final String UNATTRIBUTED = "unattributed";

    private static final String NOT_REPRESENTABLE = "notRepresentable";
    private static final String NUMBERS = "numbers";
    private static final String BACK_REFERENCE = "backReference";
    private static final Set<String> CASE_KEYS = heldKeys();
    private static final Set<String> AUTHOR_TYPES = Set.of("human_turn", "guest_turn", "agent_synthesized",
            "consolidation_derived", UNATTRIBUTED);

    /**
     * A labelled held-out case; {@code memoryId} stays inside the harness and never reaches a report. A null
     * {@code authorType} is an unattributed memory.
     */
    public record HeldCase(long memoryId, Case labels, @Nullable MemoryAuthorType authorType,
                           @Nullable Coverage coverage) {
        public HeldCase(long memoryId, Case labels, @Nullable MemoryAuthorType authorType) {
            this(memoryId, labels, authorType, null);
        }

        public HeldCase(long memoryId, Case labels) {
            this(memoryId, labels, MemoryAuthorType.HUMAN_TURN);
        }
    }

    /**
     * A case's coverage labels (JCLAW-1374): the {@link CompetencyQuestions#NOT_REPRESENTABLE} kinds it expresses, the
     * {@link CompetencyQuestions#NUMBER_KINDS} it holds, and whether a pronoun refers back within it.
     */
    public record Coverage(List<String> notRepresentable, List<String> numbers, boolean backReference) {
        public Coverage {
            notRepresentable = List.copyOf(notRepresentable);
            numbers = List.copyOf(numbers);
        }
    }

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
        List<Object[]> rows = Tx.run(() -> JPA.em().createQuery("SELECT m.id, m.text, m.authorType FROM Memory m "
                        + "WHERE m.agent.id = :agent AND m.supersededAt IS NULL ORDER BY m.id", Object[].class)
                .setParameter("agent", agent).getResultList());
        var shuffled = new ArrayList<>(rows);
        Collections.shuffle(shuffled, new Random(seed));
        var cases = new JsonArray();
        var anchors = new AnchorResolver.JpaLookup();
        for (var row : shuffled.subList(0, Math.min(count, shuffled.size()))) {
            long id = (Long) row[0];
            var text = (String) row[1];
            var author = (MemoryAuthorType) row[2];
            var anchor = anchors.node(id).map(AnchorResolver.Node::anchor)
                    .orElseThrow(() -> new IllegalStateException("a sampled memory vanished before its anchor was read"));
            var o = new JsonObject();
            o.addProperty("memoryId", id);
            o.addProperty("text", text);
            o.addProperty("capturedAt", anchor.toString());
            o.addProperty("authorType", author == null ? UNATTRIBUTED : author.name().toLowerCase(Locale.ROOT));
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
     * The name of the agent whose memories {@code loaded} holds, so its USER.md can name the owner; null when none of
     * them is still stored.
     *
     * @throws IllegalArgumentException when they belong to more than one agent
     */
    public static @Nullable String agentName(Loaded loaded) {
        var ids = loaded.cases().stream().map(HeldCase::memoryId).toList();
        if (ids.isEmpty()) return null;
        List<String> names = Tx.run(() -> JPA.em().createQuery(
                        "SELECT DISTINCT m.agent.name FROM Memory m WHERE m.id IN :ids", String.class)
                .setParameter("ids", ids).getResultList());
        if (names.size() > 1) throw new IllegalArgumentException("the held-out memories belong to more than one agent");
        return names.isEmpty() ? null : names.getFirst();
    }

    /**
     * The labelled cases in {@code file}, validated under the v3 rules with tags optional; an unlabelled one is
     * skipped and counted. Refusals name a case by its position, never its memory.
     */
    public static Loaded load(Path file, OntologySchema schema) throws IOException {
        var root = GraphCases.root(Files.readString(file));
        GraphCases.onlyKeys(root, Set.of("cases"), "graph cases");
        var symmetric = schema.symmetricSet();
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
            var author = o.get("authorType");
            if (!o.has("capturedAt") || author == null || !author.isJsonPrimitive()
                    || !AUTHOR_TYPES.contains(author.getAsString())) {
                throw new IllegalArgumentException("case " + id
                        + ": sampled before v3 (no capturedAt or authorType); move the file aside and resample");
            }
            GraphCases.Case labels;
            try {
                labels = GraphCases.parseCase(o, id, false, CASE_KEYS, null, schema, types, symmetric);
            } catch (IllegalArgumentException _) {
                // parseCase quotes spans, and a held-out span is real memory text.
                throw new IllegalArgumentException("case " + id + ": labels break the v3 rules in evals/graph/README.md");
            }
            var coverage = coverage(o, "case " + id);
            var raw = author.getAsString();
            cases.add(new HeldCase(memoryId, labels,
                    raw.equals(UNATTRIBUTED) ? null : MemoryAuthorType.valueOf(raw.toUpperCase(Locale.ROOT)),
                    coverage));
        }
        return new Loaded(cases, unlabelled);
    }

    /** The case's coverage labels, null unless it carries all three; a present one is checked even so. */
    private static @Nullable Coverage coverage(JsonObject o, String where) {
        var notRepresentable = o.has(NOT_REPRESENTABLE)
                ? kinds(o, NOT_REPRESENTABLE, CompetencyQuestions.NOT_REPRESENTABLE, where) : null;
        var numbers = o.has(NUMBERS) ? kinds(o, NUMBERS, CompetencyQuestions.NUMBER_KINDS, where) : null;
        boolean backReference = GraphCases.flag(o, BACK_REFERENCE, where);
        if (notRepresentable == null || numbers == null || !o.has(BACK_REFERENCE)) return null;
        return new Coverage(notRepresentable, numbers, backReference);
    }

    /** Refusals never quote the value: an unknown kind could be the memory's own words. */
    private static List<String> kinds(JsonObject o, String key, List<String> allowed, String where) {
        var value = o.get(key);
        if (!value.isJsonArray()) throw new IllegalArgumentException(where + ": '" + key + "' must be an array");
        var out = new ArrayList<String>();
        for (var e : value.getAsJsonArray()) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(where + ": '" + key + "' must hold strings");
            }
            var kind = e.getAsString();
            if (!allowed.contains(kind)) throw new IllegalArgumentException(where + ": '" + key + "' holds an unknown kind");
            if (out.contains(kind)) throw new IllegalArgumentException(where + ": '" + key + "' repeats a kind");
            out.add(kind);
        }
        return out;
    }

    private static Set<String> heldKeys() {
        var keys = new HashSet<>(GraphCases.CASE_KEYS);
        keys.remove("id");
        keys.addAll(List.of("memoryId", "labelled", "candidates", "authorType", NOT_REPRESENTABLE, NUMBERS,
                BACK_REFERENCE));
        return Set.copyOf(keys);
    }
}
