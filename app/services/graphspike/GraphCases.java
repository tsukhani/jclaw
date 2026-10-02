package services.graphspike;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import memory.ontology.OntologySchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** The labelled synthetic memories in {@code evals/graph/cases.json}, validated against an ontology. */
public final class GraphCases {

    public static final String DEFAULT_PATH = "evals/graph/cases.json";

    private GraphCases() {}

    public record Entity(String mention, String type) {}

    public record Relation(String from, String type, String to) {}

    public record Case(String id, String text, List<Entity> entities, List<Relation> relations) {
        public Case {
            entities = List.copyOf(entities);
            relations = List.copyOf(relations);
        }

        /** The labelled type of {@code mention}, empty when it is no labelled entity. */
        public Optional<String> typeOf(String mention) {
            return entities.stream().filter(e -> e.mention().equals(mention)).map(Entity::type).findFirst();
        }
    }

    public static List<Case> load(Path path, OntologySchema schema) throws IOException {
        return parse(Files.readString(path), schema);
    }

    /**
     * The cases in {@code json}, in file order.
     *
     * @throws IllegalArgumentException naming the case, on a malformed entry, a duplicate id, a mention that is not
     *                                  verbatim, an undeclared type, an endpoint that is no labelled entity, or a
     *                                  relation the schema disallows for its endpoints' types
     */
    public static List<Case> parse(String json, OntologySchema schema) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("graph cases are not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("cases") || !root.getAsJsonObject().get("cases").isJsonArray()) {
            throw new IllegalArgumentException("graph cases: the document must be an object with a 'cases' array");
        }
        var cases = new ArrayList<Case>();
        var ids = new HashSet<String>();
        int index = 0;
        for (var element : root.getAsJsonObject().getAsJsonArray("cases")) {
            var where = "case #" + index++;
            if (!element.isJsonObject()) throw new IllegalArgumentException(where + ": must be an object");
            var c = parseCase(element.getAsJsonObject(), where, schema);
            if (!ids.add(c.id())) throw new IllegalArgumentException("case " + c.id() + ": duplicate id");
            cases.add(c);
        }
        return List.copyOf(cases);
    }

    private static Case parseCase(JsonObject object, String where, OntologySchema schema) {
        var id = text(object, "id", where);
        where = "case " + id;
        var text = text(object, "text", where);

        var types = new HashMap<String, String>();
        var entities = new ArrayList<Entity>();
        for (var e : array(object, "entities", where)) {
            if (!e.isJsonObject()) throw new IllegalArgumentException(where + ": an entity must be an object");
            var mention = text(e.getAsJsonObject(), "mention", where);
            var type = text(e.getAsJsonObject(), "type", where);
            if (!text.contains(mention)) {
                throw new IllegalArgumentException(where + ": mention '" + mention + "' is not verbatim in the text");
            }
            if (!schema.termTypes().containsKey(type)) {
                throw new IllegalArgumentException(where + ": mention '" + mention + "' has undeclared type '" + type + "'");
            }
            if (types.put(mention, type) != null) {
                throw new IllegalArgumentException(where + ": mention '" + mention + "' is labelled twice");
            }
            entities.add(new Entity(mention, type));
        }

        var relations = new ArrayList<Relation>();
        for (var r : array(object, "relations", where)) {
            if (!r.isJsonObject()) throw new IllegalArgumentException(where + ": a relation must be an object");
            var from = text(r.getAsJsonObject(), "from", where);
            var type = text(r.getAsJsonObject(), "type", where);
            var to = text(r.getAsJsonObject(), "to", where);
            var fromType = types.get(from);
            var toType = types.get(to);
            if (fromType == null || toType == null) {
                throw new IllegalArgumentException(where + ": relation endpoint '" + (fromType == null ? from : to)
                        + "' is not a labelled entity");
            }
            if (!schema.allows(type, fromType, toType)) {
                throw new IllegalArgumentException(where + ": the schema does not allow " + fromType + " " + type
                        + " " + toType + " ('" + from + "' -> '" + to + "')");
            }
            relations.add(new Relation(from, type, to));
        }
        return new Case(id, text, entities, relations);
    }

    private static String text(JsonObject object, String key, String where) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(where + ": '" + key + "' must be a non-blank string");
        }
        return value.getAsString();
    }

    private static JsonArray array(JsonObject object, String key, String where) {
        var value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(where + ": '" + key + "' must be an array");
        }
        return value.getAsJsonArray();
    }
}
