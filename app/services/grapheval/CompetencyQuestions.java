package services.grapheval;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The operator's competency questions (JCLAW-1374): what the knowledge graph should answer, each tied to the term
 * types, relation and claim it needs, or to the kind of fact the schema cannot hold. The format is
 * {@code evals/graph/README.md}'s; any key or value outside it is refused.
 */
public final class CompetencyQuestions {

    public static final String DEFAULT_PATH = "evals/graph/competency-questions.json";
    /** The schema v3 claims a question may ask about. */
    public static final List<String> CLAIM_FACETS = List.of("status", "valid", "valence", "occurs");
    /** What a memory may express that the schema cannot store, in report order. */
    public static final List<String> NOT_REPRESENTABLE = List.of("quantity", "role", "kin-word", "plan",
            "belief-or-report", "relation", "type", "condition", "comparison", "cause", "goal", "health", "routine",
            "instruction", "other");
    /** The kinds of number a memory may hold, in report order. */
    public static final List<String> NUMBER_KINDS = List.of("date", "duration", "clock-time", "identifier",
            "quantity", "other");
    public static final int MIN = 20;
    public static final int MAX = 40;

    private static final Set<String> ROOT_KEYS = Set.of("questions");
    private static final Set<String> QUESTION_KEYS = Set.of("id", "text", "types", "relation", "facet");
    private static final Pattern ID = Pattern.compile("q\\d+");

    private CompetencyQuestions() {}

    /** {@code types} are From and To when {@code relation} is set, else the one type asked about. */
    public record Question(String id, String text, List<String> types, @Nullable String relation,
                           @Nullable String facet) {
        public Question {
            types = List.copyOf(types);
        }
    }

    public static List<Question> load(Path path, OntologySchema schema) throws IOException {
        return parse(Files.readString(path), schema);
    }

    /**
     * The questions in {@code json}, in file order.
     *
     * @throws IllegalArgumentException naming the question, or stating the count, on any break of the format
     */
    public static List<Question> parse(String json, OntologySchema schema) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("competency questions are not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("questions")
                || !root.getAsJsonObject().get("questions").isJsonArray()) {
            throw new IllegalArgumentException("competency questions: the document must be an object with a "
                    + "'questions' array");
        }
        GraphCases.onlyKeys(root.getAsJsonObject(), ROOT_KEYS, "competency questions");
        var array = root.getAsJsonObject().getAsJsonArray("questions");
        if (array.size() < MIN || array.size() > MAX) {
            throw new IllegalArgumentException("competency questions: the file holds " + array.size()
                    + " questions; " + MIN + " to " + MAX + " are required");
        }
        var out = new ArrayList<Question>();
        var ids = new HashSet<String>();
        int index = 0;
        for (var element : array) {
            var position = "question #" + index++;
            if (!element.isJsonObject()) throw new IllegalArgumentException(position + ": must be an object");
            var o = element.getAsJsonObject();
            var rawId = o.get("id");
            if (rawId == null || !rawId.isJsonPrimitive() || !rawId.getAsJsonPrimitive().isString()
                    || !ID.matcher(rawId.getAsString()).matches()) {
                throw new IllegalArgumentException(position + ": 'id' must be 'q' plus digits");
            }
            var id = rawId.getAsString();
            var where = "question " + id;
            if (!ids.add(id)) throw new IllegalArgumentException(where + ": duplicate id");
            out.add(parseQuestion(o, id, where, schema));
        }
        return List.copyOf(out);
    }

    private static Question parseQuestion(JsonObject o, String id, String where, OntologySchema schema) {
        GraphCases.onlyKeys(o, QUESTION_KEYS, where);
        var text = GraphCases.text(o, "text", where);
        var types = GraphCases.strings(o, "types", where);
        for (var type : types) {
            if (!schema.termTypes().containsKey(type)) {
                throw new IllegalArgumentException(where + ": undeclared type '" + type + "'");
            }
        }
        var relation = nullableString(o, "relation", where);
        var facet = nullableString(o, "facet", where);
        if (relation == null) {
            if (types.size() != 1) {
                throw new IllegalArgumentException(where + ": a question with no relation names exactly one type");
            }
        } else {
            if (!schema.relations().containsKey(relation)) {
                throw new IllegalArgumentException(where + ": undeclared relation '" + relation + "'");
            }
            if (types.size() != 2) {
                throw new IllegalArgumentException(where + ": a question with a relation names two types, From and To");
            }
            if (!schema.allows(relation, types.get(0), types.get(1))) {
                throw new IllegalArgumentException(where + ": the schema does not allow " + types.get(0) + " "
                        + relation + " " + types.get(1));
            }
        }
        if (facet != null) checkFacet(facet, types, relation, where, schema);
        return new Question(id, text, types, relation, facet);
    }

    private static void checkFacet(String facet, List<String> types, @Nullable String relation, String where,
                                   OntologySchema schema) {
        if (NOT_REPRESENTABLE.contains(facet)) return;
        if (!CLAIM_FACETS.contains(facet)) {
            throw new IllegalArgumentException(where + ": facet '" + facet
                    + "' is neither a schema claim nor a not-representable kind");
        }
        boolean admitted = switch (facet) {
            case "status" -> relation != null;
            case "valid" -> relation != null && schema.validAllowed(relation, types.getFirst());
            case "valence" -> relation != null && valence(schema, relation);
            default -> types.stream().anyMatch(t -> dated(schema, t));
        };
        if (!admitted) {
            throw new IllegalArgumentException(where + ": facet '" + facet + "' is not a claim "
                    + (relation == null ? "a question with no relation" : "relation '" + relation + "'") + " admits");
        }
    }

    private static boolean valence(OntologySchema schema, String relation) {
        var type = schema.relations().get(relation);
        return type != null && type.valence();
    }

    private static boolean dated(OntologySchema schema, String termType) {
        var type = schema.termTypes().get(termType);
        return type != null && type.dated();
    }

    private static @Nullable String nullableString(JsonObject o, String key, String where) {
        if (!o.has(key)) throw new IllegalArgumentException(where + ": '" + key + "' is required (it may be null)");
        if (o.get(key).isJsonNull()) return null;
        return GraphCases.text(o, key, where);
    }
}
