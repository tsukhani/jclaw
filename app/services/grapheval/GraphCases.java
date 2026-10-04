package services.grapheval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import services.WorkspaceFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The v3 labelled memories in {@code evals/graph/cases.json} (JCLAW-1356, JCLAW-1366), validated against an ontology.
 * The rules are {@code evals/graph/GUIDE.md}'s; the format is {@code evals/graph/README.md}'s. Labels are checked for
 * syntax and verbatim spans only, never against the code that extracts them.
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
    /** A guest's memory that speaks about the owner: it may name the operator, but asserts nothing of them. */
    public static final String GUEST_ABOUT_OWNER = "guest-about-owner";
    public static final String NEGATED = "negated";
    public static final String DATED = "dated";
    public static final Set<String> TAGS = Set.of("weekday-time", "role", "everyday-object", "descriptive-phrase",
            "reversed-direction", "employer-tool", PLAIN, GUEST, "ended", NEGATED, "unasserted", DATED,
            GUEST_ABOUT_OWNER);
    public static final String HOLDS = "holds";
    public static final String ENDED = "ended";
    public static final String DENIED = "denied";
    /** An eval-only label: the text mentions the relation without asserting it. */
    public static final String UNASSERTED = "unasserted";
    public static final Set<String> STATUSES = Set.of(HOLDS, ENDED, DENIED, UNASSERTED);
    public static final Set<String> VALENCES = Set.of("favorable", "unfavorable");

    static final Set<String> ROOT_KEYS = Set.of("userMd", "capturedAt", "cases");
    static final Set<String> CASE_KEYS = Set.of("id", "tags", "text", "entities", "relations", "negatives",
            "capturedAt", "dates");
    private static final Set<String> ENTITY_KEYS = Set.of("id", "mention", "type", "aliases", "implicit", "noise",
            "occurs");
    private static final Set<String> RELATION_KEYS = Set.of("from", "type", "to", "status", "valid", "valence", "noise");
    private static final Set<String> DATE_KEYS = Set.of("span", "value");

    private GraphCases() {}

    /** A labelled entity; {@code mention} is null only on an implicit operator. */
    public record Entity(String id, @Nullable String mention, String type, List<String> aliases, boolean implicit,
                         boolean noise, @Nullable String occurs) {
        public Entity {
            aliases = List.copyOf(aliases);
        }

        public Entity(String id, @Nullable String mention, String type, List<String> aliases, boolean implicit,
                      boolean noise) {
            this(id, mention, type, aliases, implicit, noise, null);
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

    /** {@code status} is one of {@link #STATUSES}; {@code valid} and {@code valence} are as labelled. */
    public record Relation(String from, String type, String to, String status, @Nullable String valid,
                           @Nullable String valence, boolean noise) {
        public Relation(String from, String type, String to, boolean noise) {
            this(from, type, to, HOLDS, null, null, noise);
        }

        public static Relation of(String from, String type, String to) {
            return new Relation(from, type, to, false);
        }

        public boolean denied() {
            return status.equals(DENIED);
        }

        public boolean reaches(String id) {
            return from.equals(id) || to.equals(id);
        }
    }

    /** A labelled date span; {@code value} is an EDTF string, or null for a span excluded from scoring. */
    public record DateLabel(String span, @Nullable String value) {}

    public record Case(String id, List<String> tags, String text, List<Entity> entities, List<Relation> relations,
                       List<String> negatives, LocalDate capturedAt, List<DateLabel> dates) {
        public Case {
            tags = List.copyOf(tags);
            entities = List.copyOf(entities);
            relations = List.copyOf(relations);
            negatives = List.copyOf(negatives);
            dates = List.copyOf(dates);
        }

        public Case(String id, List<String> tags, String text, List<Entity> entities, List<Relation> relations,
                    List<String> negatives) {
            this(id, tags, text, entities, relations, negatives, ExtractionPipeline.DEFAULT_ANCHOR, List.of());
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

        /** The positive (holds, ended or unasserted) label from one id to another, either way round when symmetric. */
        public @Nullable Relation positive(String from, String to, Set<String> symmetric) {
            return find(from, to, symmetric, false);
        }

        /** The denied label from one id to another, either way round for a symmetric type. */
        public @Nullable Relation denied(String from, String to, Set<String> symmetric) {
            return find(from, to, symmetric, true);
        }

        private @Nullable Relation find(String from, String to, Set<String> symmetric, boolean denied) {
            for (var r : relations) {
                if (r.denied() != denied) continue;
                if (r.from().equals(from) && r.to().equals(to)) return r;
                if (symmetric.contains(r.type()) && r.from().equals(to) && r.to().equals(from)) return r;
            }
            return null;
        }

        /**
         * The status {@code r} scores under: its label, except that an {@code ended} its relation cannot take (a
         * dated From, or a type without ended) scores as {@link #UNASSERTED}.
         */
        public String scoredStatus(Relation r, OntologySchema schema) {
            if (!r.status().equals(ENDED)) return r.status();
            var from = entity(r.from());
            return from != null && schema.effectiveStatuses(r.type(), from.type()).contains(ENDED) ? ENDED : UNASSERTED;
        }

        /** Whether {@code r} is base gold: a {@code holds}, or an {@code ended} its relation admits. */
        public boolean holdsOrEnded(Relation r, OntologySchema schema) {
            var status = scoredStatus(r, schema);
            return status.equals(HOLDS) || status.equals(ENDED);
        }
    }

    public static List<Case> load(Path path, OntologySchema schema) throws IOException {
        return parse(Files.readString(path), schema);
    }

    /**
     * The cases in {@code json}, in file order.
     *
     * @throws IllegalArgumentException naming the case, on any break of the v3 rules
     */
    public static List<Case> parse(String json, OntologySchema schema) {
        var root = root(json);
        onlyKeys(root, ROOT_KEYS, "graph cases");
        var anchor = capturedAt(root);
        var owner = ownerName(root);
        var symmetric = schema.symmetricSet();
        var cases = new ArrayList<Case>();
        var types = new HashMap<String, String>();
        var ids = new HashSet<String>();
        int index = 0;
        for (var element : root.getAsJsonArray("cases")) {
            var where = "case #" + index++;
            if (!element.isJsonObject()) throw new IllegalArgumentException(where + ": must be an object");
            var object = element.getAsJsonObject();
            var id = text(object, "id", where);
            var c = parseCase(object, id, true, CASE_KEYS, anchor, schema, types, symmetric);
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

    /**
     * The set's root {@code capturedAt}, the anchor every case inherits.
     *
     * @throws IllegalArgumentException when {@code json} is not a case set, or has no ISO-date {@code capturedAt}
     */
    public static LocalDate capturedAt(String json) {
        return capturedAt(root(json));
    }

    private static LocalDate capturedAt(JsonObject root) {
        if (!root.has("capturedAt")) throw new IllegalArgumentException("graph cases: the root needs 'capturedAt'");
        return date(root, "capturedAt", "graph cases");
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
     * One case under the v3 rules. {@code keys} are the keys the case may carry; {@code inherited} is the root's
     * {@code capturedAt}, or null when the case must carry its own. {@code types} carries each entity id's type across
     * the cases parsed so far; {@code tagged} cases must carry known tags, held-out ones may omit them.
     */
    static Case parseCase(JsonObject object, String id, boolean tagged, Set<String> keys, @Nullable LocalDate inherited,
                          OntologySchema schema, Map<String, String> types, Set<String> symmetric) {
        var where = "case " + id;
        onlyKeys(object, keys, where);
        var text = text(object, "text", where);
        LocalDate capturedAt;
        if (object.has("capturedAt")) capturedAt = date(object, "capturedAt", where);
        else if (inherited != null) capturedAt = inherited;
        else throw new IllegalArgumentException(where + ": 'capturedAt' is required");

        var tags = new ArrayList<String>();
        if (tagged || object.has("tags")) {
            for (var tag : strings(object, "tags", where)) {
                if (!TAGS.contains(tag)) throw new IllegalArgumentException(where + ": unknown tag '" + tag + "'");
                tags.add(tag);
            }
        }
        boolean aboutOwner = tags.contains(GUEST_ABOUT_OWNER);
        if (aboutOwner && !tags.contains(GUEST)) {
            throw new IllegalArgumentException(where + ": '" + GUEST_ABOUT_OWNER + "' is always tagged beside '" + GUEST + "'");
        }

        var dates = new ArrayList<DateLabel>();
        if (object.has("dates")) {
            for (var d : array(object, "dates", where)) {
                if (!d.isJsonObject()) throw new IllegalArgumentException(where + ": a date must be an object");
                dates.add(parseDate(d.getAsJsonObject(), text, where));
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
        var positivePairs = new HashSet<List<String>>();
        var deniedPairs = new HashSet<List<String>>();
        for (var r : array(object, "relations", where)) {
            if (!r.isJsonObject()) throw new IllegalArgumentException(where + ": a relation must be an object");
            var relation = parseRelation(r.getAsJsonObject(), where, entities, capturedAt, schema);
            var from = relation.from();
            var to = relation.to();
            var pairs = relation.denied() ? deniedPairs : positivePairs;
            boolean taken = pairs.contains(List.of(from, to))
                    || (symmetric.contains(relation.type()) && pairs.contains(List.of(to, from)));
            if (taken) {
                throw new IllegalArgumentException(where + ": two " + (relation.denied() ? "denied relations" : "relations")
                        + " on '" + from + "' -> '" + to + "'");
            }
            pairs.add(List.of(from, to));
            if (symmetric.contains(relation.type())) pairs.add(List.of(to, from));
            if (aboutOwner && relation.reaches(OPERATOR) && !relation.status().equals(UNASSERTED)) {
                throw new IllegalArgumentException(where + ": a " + GUEST_ABOUT_OWNER + " case asserts nothing of the "
                        + "operator, so '" + from + "' -> '" + to + "' must be " + UNASSERTED);
            }
            relations.add(relation);
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
        return new Case(id, tags, text, List.copyOf(entities.values()), relations, negatives, capturedAt, dates);
    }

    private static DateLabel parseDate(JsonObject o, String text, String where) {
        onlyKeys(o, DATE_KEYS, where);
        var span = text(o, "span", where);
        if (!text.contains(span)) {
            throw new IllegalArgumentException(where + ": date span '" + span + "' is not verbatim in the text");
        }
        var value = o.get("value");
        if (value == null) throw new IllegalArgumentException(where + ": date '" + span + "' needs a 'value' (or null)");
        if (value.isJsonNull()) return new DateLabel(span, null);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(where + ": date '" + span + "' value must be an EDTF string or null");
        }
        return new DateLabel(span, edtf(value.getAsString(), "date '" + span + "'", where).toString());
    }

    private static Relation parseRelation(JsonObject o, String where, Map<String, Entity> entities, LocalDate capturedAt,
                                          OntologySchema schema) {
        onlyKeys(o, RELATION_KEYS, where);
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
        var pair = "'" + from + "' -" + type + "-> '" + to + "'";
        if (!o.has("status")) throw new IllegalArgumentException(where + ": relation " + pair + " has no 'status'");
        var status = text(o, "status", where);
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException(where + ": relation " + pair + " has unknown status '" + status + "'");
        }
        boolean positive = status.equals(HOLDS) || status.equals(ENDED);

        String valid = null;
        if (o.has("valid")) {
            var raw = text(o, "valid", where);
            if (status.equals(UNASSERTED) || !schema.validAllowed(type, fromEntity.type())) {
                throw new IllegalArgumentException(where + ": relation " + pair + " takes no 'valid'");
            }
            if (status.equals(DENIED)) {
                var never = "../" + capturedAt;
                if (!raw.equals(never)) {
                    throw new IllegalArgumentException(where + ": a denial's 'valid' is exactly '" + never + "', not '"
                            + raw + "'");
                }
                valid = raw;
            } else {
                valid = edtf(raw, "relation " + pair + " 'valid'", where).toString();
            }
        }

        String valence = null;
        if (o.has("valence")) {
            valence = text(o, "valence", where);
            var relation = schema.relations().get(type);
            if (relation == null || !relation.valence()) {
                throw new IllegalArgumentException(where + ": relation " + pair + " takes no 'valence'");
            }
            if (!VALENCES.contains(valence)) {
                throw new IllegalArgumentException(where + ": valence '" + valence + "' is neither favorable nor unfavorable");
            }
        }

        boolean noise = flag(o, "noise", where);
        if (noise && !positive) {
            throw new IllegalArgumentException(where + ": 'noise' only marks a holds or ended relation, not " + status);
        }
        return new Relation(from, type, to, status, valid, valence, noise);
    }

    private static Entity parseEntity(JsonObject o, String text, String where, OntologySchema schema) {
        onlyKeys(o, ENTITY_KEYS, where);
        var id = text(o, "id", where);
        var type = text(o, "type", where);
        var termType = schema.termTypes().get(type);
        if (termType == null) {
            throw new IllegalArgumentException(where + ": entity '" + id + "' has undeclared type '" + type + "'");
        }
        boolean implicit = flag(o, "implicit", where);
        boolean noise = flag(o, "noise", where);
        String occurs = null;
        if (o.has("occurs")) {
            if (!termType.dated()) {
                throw new IllegalArgumentException(where + ": entity '" + id + "' is a " + type + ", and only an Event occurs");
            }
            var interval = edtf(text(o, "occurs", where), "entity '" + id + "' 'occurs'", where);
            if (!(interval.start() instanceof EdtfInterval.Point) || !(interval.end() instanceof EdtfInterval.Point)) {
                throw new IllegalArgumentException(where + ": entity '" + id + "' 'occurs' is a date or a closed interval");
            }
            occurs = interval.toString();
        }
        if (implicit) {
            if (!id.equals(OPERATOR) || o.has("mention") || o.has("aliases")) {
                throw new IllegalArgumentException(where + ": only the operator is implicit, and it has no span");
            }
            return new Entity(id, null, type, List.of(), true, noise, occurs);
        }
        var mention = text(o, "mention", where);
        var aliases = o.has("aliases") ? strings(o, "aliases", where) : List.<String>of();
        for (var span : concat(mention, aliases)) {
            if (!text.contains(span)) {
                throw new IllegalArgumentException(where + ": mention '" + span + "' is not verbatim in the text");
            }
        }
        return new Entity(id, mention, type, aliases, false, noise, occurs);
    }

    static void onlyKeys(JsonObject object, Set<String> keys, String where) {
        for (var key : object.keySet()) {
            if (!keys.contains(key)) throw new IllegalArgumentException(where + ": unknown key '" + key + "'");
        }
    }

    private static EdtfInterval edtf(String value, String what, String where) {
        try {
            return EdtfInterval.parse(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": " + what + " '" + value + "' is outside the EDTF subset", e);
        }
    }

    static LocalDate date(JsonObject object, String key, String where) {
        var raw = text(object, key, where);
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException _) {
            throw new IllegalArgumentException(where + ": '" + key + "' must be an ISO date (YYYY-MM-DD)");
        }
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
