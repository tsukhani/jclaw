package services.grapheval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import services.WorkspaceFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The v2 labelled memories in {@code evals/graph/cases.json} (JCLAW-1356), validated against an ontology. The rules
 * are {@code evals/graph/GUIDE.md}'s; the format is {@code evals/graph/README.md}'s.
 */
public final class GraphCases {

    public static final String DEFAULT_PATH = "evals/graph/cases.json";
    public static final String SECOND_LABELS_PATH = "evals/graph/second-labels.json";
    public static final String ADJUDICATIONS_PATH = "evals/graph/adjudications.json";
    public static final String OPERATOR = "operator";
    /** The surface an implicit operator is asked and reported under. */
    public static final String IMPLICIT_OPERATOR_SPAN = "the user";
    public static final List<String> HARD_NEGATIVE_TAGS = List.of("weekday-time", "role", "everyday-object",
            "descriptive-phrase", "reversed-direction", "employer-tool");
    public static final String PLAIN = "plain";
    /** A memory about a guest, not the owner: it carries no operator. */
    public static final String GUEST = "guest";
    public static final Set<String> TAGS = Set.of("weekday-time", "role", "everyday-object", "descriptive-phrase",
            "reversed-direction", "employer-tool", PLAIN, GUEST);

    private GraphCases() {}

    /** A labelled entity; {@code mention} is null only on an implicit operator. */
    public record Entity(String id, @Nullable String mention, String type, List<String> aliases, boolean implicit,
                         boolean noise) {
        public Entity {
            aliases = List.copyOf(aliases);
        }

        public static Entity of(String id, String mention, String type, String... aliases) {
            return new Entity(id, mention, type, List.of(aliases), false, false);
        }

        public static Entity implicitOperator() {
            return new Entity(OPERATOR, null, "Person", List.of(), true, false);
        }

        public boolean operator() {
            return id.equals(OPERATOR);
        }

        /** Whether the harness writes it without asking: the implicit operator, or one mentioned as "The user". */
        public boolean ruleWritten() {
            return operator() && (implicit || IMPLICIT_OPERATOR_SPAN.equalsIgnoreCase(mention));
        }

        /** The span a question names it by: the mention, or {@link #IMPLICIT_OPERATOR_SPAN}. */
        public String span() {
            return mention == null ? IMPLICIT_OPERATOR_SPAN : mention;
        }

        public boolean answersTo(String span) {
            return span.equals(mention) || aliases.contains(span);
        }
    }

    public record Relation(String from, String type, String to, boolean noise) {
        public static Relation of(String from, String type, String to) {
            return new Relation(from, type, to, false);
        }
    }

    public record Case(String id, List<String> tags, String text, List<Entity> entities, List<Relation> relations,
                       List<String> negatives) {
        public Case {
            tags = List.copyOf(tags);
            entities = List.copyOf(entities);
            relations = List.copyOf(relations);
            negatives = List.copyOf(negatives);
        }

        public @Nullable Entity entity(String id) {
            return entities.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
        }

        /** The entity a span names: by mention or alias, or the operator for "the user" in any case. */
        public @Nullable Entity entityAt(String span) {
            for (var e : entities) {
                if (e.answersTo(span)) return e;
            }
            return span.equalsIgnoreCase(IMPLICIT_OPERATOR_SPAN) ? entity(OPERATOR) : null;
        }

        /** The labelled relation from one id to another, either way round for a symmetric type. */
        public @Nullable Relation relation(String from, String to) {
            for (var r : relations) {
                if (r.from().equals(from) && r.to().equals(to)) return r;
                if (GraphEvalScorer.SYMMETRIC.contains(r.type()) && r.from().equals(to) && r.to().equals(from)) return r;
            }
            return null;
        }
    }

    public static List<Case> load(Path path, OntologySchema schema) throws IOException {
        return parse(Files.readString(path), schema);
    }

    /**
     * The cases in {@code json}, in file order.
     *
     * @throws IllegalArgumentException naming the case, on any break of the v2 rules
     */
    public static List<Case> parse(String json, OntologySchema schema) {
        var root = root(json);
        var owner = ownerName(root);
        var cases = new ArrayList<Case>();
        var types = new HashMap<String, String>();
        var ids = new HashSet<String>();
        int index = 0;
        for (var element : root.getAsJsonArray("cases")) {
            var where = "case #" + index++;
            if (!element.isJsonObject()) throw new IllegalArgumentException(where + ": must be an object");
            var object = element.getAsJsonObject();
            var id = text(object, "id", where);
            var c = parseCase(object, id, true, schema, types);
            var operator = c.entity(OPERATOR);
            var mention = operator == null ? null : operator.mention();
            if (owner != null && mention != null && !mention.equalsIgnoreCase(IMPLICIT_OPERATOR_SPAN)
                    && !mention.equals(owner)) {
                throw new IllegalArgumentException("case " + c.id() + ": the operator is mentioned as '" + mention
                        + "', neither \"The user\" nor the declared owner '" + owner + "'");
            }
            if (!ids.add(c.id())) throw new IllegalArgumentException("case " + c.id() + ": duplicate id");
            cases.add(c);
        }
        return List.copyOf(cases);
    }

    /**
     * The owner's name declared by the set's optional root {@code userMd}, a USER.md header, or null when it declares
     * none.
     *
     * @throws IllegalArgumentException when {@code json} is not a case set, or {@code userMd} is not a string
     */
    public static @Nullable String ownerName(String json) {
        return ownerName(root(json));
    }

    /**
     * The set's root {@code userMd} as written, or null when it declares none.
     *
     * @throws IllegalArgumentException when {@code json} is not a case set, or {@code userMd} is not a string
     */
    public static @Nullable String userMd(String json) {
        return userMd(root(json));
    }

    private static @Nullable String userMd(JsonObject root) {
        var userMd = root.get("userMd");
        if (userMd == null) return null;
        if (!userMd.isJsonPrimitive() || !userMd.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("graph cases: 'userMd' must be a string");
        }
        return userMd.getAsString();
    }

    private static @Nullable String ownerName(JsonObject root) {
        var userMd = userMd(root);
        return userMd == null ? null : WorkspaceFiles.ownerNameIn(userMd);
    }

    static JsonObject root(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("graph cases are not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("cases") || !root.getAsJsonObject().get("cases").isJsonArray()) {
            throw new IllegalArgumentException("graph cases: the document must be an object with a 'cases' array");
        }
        return root.getAsJsonObject();
    }

    /**
     * One case under the v2 rules. {@code types} carries each entity id's type across the cases parsed so far;
     * {@code tagged} cases must carry known tags, held-out ones may omit them.
     */
    static Case parseCase(JsonObject object, String id, boolean tagged, OntologySchema schema, Map<String, String> types) {
        var where = "case " + id;
        var text = text(object, "text", where);

        var tags = new ArrayList<String>();
        if (tagged || object.has("tags")) {
            for (var tag : strings(object, "tags", where)) {
                if (!TAGS.contains(tag)) throw new IllegalArgumentException(where + ": unknown tag '" + tag + "'");
                tags.add(tag);
            }
        }

        var entities = new LinkedHashMap<String, Entity>();
        var spans = new HashSet<String>();
        for (var e : array(object, "entities", where)) {
            if (!e.isJsonObject()) throw new IllegalArgumentException(where + ": an entity must be an object");
            var entity = parseEntity(e.getAsJsonObject(), text, where, schema);
            if (entities.put(entity.id(), entity) != null) {
                throw new IllegalArgumentException(where + ": entity id '" + entity.id() + "' is labelled twice");
            }
            for (var span : spansOf(entity)) {
                if (!spans.add(span)) throw new IllegalArgumentException(where + ": span '" + span + "' names two entities");
            }
            var known = types.putIfAbsent(entity.id(), entity.type());
            if (known != null && !known.equals(entity.type())) {
                throw new IllegalArgumentException(where + ": id '" + entity.id() + "' is typed " + entity.type()
                        + " here and " + known + " elsewhere");
            }
        }

        var relations = new ArrayList<Relation>();
        var pairs = new HashSet<List<String>>();
        for (var r : array(object, "relations", where)) {
            if (!r.isJsonObject()) throw new IllegalArgumentException(where + ": a relation must be an object");
            var o = r.getAsJsonObject();
            var from = text(o, "from", where);
            var type = text(o, "type", where);
            var to = text(o, "to", where);
            var fromEntity = entities.get(from);
            var toEntity = entities.get(to);
            if (fromEntity == null || toEntity == null) {
                throw new IllegalArgumentException(where + ": relation endpoint '" + (fromEntity == null ? from : to)
                        + "' is not a case entity id");
            }
            if (!schema.allows(type, fromEntity.type(), toEntity.type())) {
                throw new IllegalArgumentException(where + ": the schema does not allow " + fromEntity.type() + " "
                        + type + " " + toEntity.type() + " ('" + from + "' -> '" + to + "')");
            }
            boolean reversedSymmetric = GraphEvalScorer.SYMMETRIC.contains(type) && pairs.contains(List.of(to, from));
            if (!pairs.add(List.of(from, to)) || reversedSymmetric) {
                throw new IllegalArgumentException(where + ": two relations on '" + from + "' -> '" + to + "'");
            }
            relations.add(new Relation(from, type, to, flag(o, "noise", where)));
        }

        var negatives = new ArrayList<String>();
        if (object.has("negatives")) {
            for (var negative : strings(object, "negatives", where)) {
                if (spans.contains(negative)) {
                    throw new IllegalArgumentException(where + ": negative '" + negative + "' is a labelled mention or alias");
                }
                if (!text.contains(negative)) {
                    throw new IllegalArgumentException(where + ": negative '" + negative + "' is not verbatim in the text");
                }
                negatives.add(negative);
            }
        }
        return new Case(id, tags, text, List.copyOf(entities.values()), relations, negatives);
    }

    private static Entity parseEntity(JsonObject o, String text, String where, OntologySchema schema) {
        var id = text(o, "id", where);
        var type = text(o, "type", where);
        if (!schema.termTypes().containsKey(type)) {
            throw new IllegalArgumentException(where + ": entity '" + id + "' has undeclared type '" + type + "'");
        }
        boolean implicit = flag(o, "implicit", where);
        boolean noise = flag(o, "noise", where);
        if (implicit) {
            if (!id.equals(OPERATOR) || o.has("mention") || o.has("aliases")) {
                throw new IllegalArgumentException(where + ": only the operator is implicit, and it has no span");
            }
            return new Entity(id, null, type, List.of(), true, noise);
        }
        var mention = text(o, "mention", where);
        var aliases = o.has("aliases") ? strings(o, "aliases", where) : List.<String>of();
        for (var span : concat(mention, aliases)) {
            if (!text.contains(span)) {
                throw new IllegalArgumentException(where + ": mention '" + span + "' is not verbatim in the text");
            }
        }
        return new Entity(id, mention, type, aliases, false, noise);
    }

    private static List<String> spansOf(Entity e) {
        return e.mention() == null ? List.of() : concat(e.mention(), e.aliases());
    }

    private static List<String> concat(String first, List<String> rest) {
        var out = new ArrayList<String>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    static boolean flag(JsonObject object, String key, String where) {
        var value = object.get(key);
        if (value == null) return false;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(where + ": '" + key + "' must be a boolean");
        }
        return value.getAsBoolean();
    }

    static String text(JsonObject object, String key, String where) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(where + ": '" + key + "' must be a non-blank string");
        }
        return value.getAsString();
    }

    static List<String> strings(JsonObject object, String key, String where) {
        var out = new ArrayList<String>();
        for (var e : array(object, key, where)) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank()) {
                throw new IllegalArgumentException(where + ": '" + key + "' must hold non-blank strings");
            }
            out.add(e.getAsString());
        }
        return out;
    }

    static JsonArray array(JsonObject object, String key, String where) {
        var value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(where + ": '" + key + "' must be an array");
        }
        return value.getAsJsonArray();
    }

    /** Whether {@code text} opens with the operator: "The user", the owner's name, or a subjectless verb. */
    public static boolean operatorVoice(String text, @Nullable String ownerName) {
        return text.startsWith("The user") || (ownerName != null && text.startsWith(ownerName))
                || CandidateGenerator.subjectless(text);
    }
}
