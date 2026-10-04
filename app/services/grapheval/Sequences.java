package services.grapheval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import memory.graph.GraphView;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import services.WorkspaceFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The superseding-memory chains in {@code evals/graph/sequences.json} (JCLAW-1367): each memory follows the v3 case
 * rules, links only to earlier memories of its chain, and each probe asks whether a relation held at a valid date as the
 * sources said just after or before one memory. The format is {@code evals/graph/README.md}'s. {@code ownerName} is
 * the one {@code userMd} declares, or null.
 */
public record Sequences(@Nullable String ownerName, String userMd, List<Chain> chains, String fingerprint) {

    public static final String DEFAULT_PATH = "evals/graph/sequences.json";
    public static final String UPDATE = "update";
    public static final String RESTATEMENT = "restatement";
    public static final String CORRECTION = "correction";
    public static final Set<String> CHAIN_TAGS = Set.of(UPDATE, RESTATEMENT, CORRECTION, GraphCases.GUEST_ABOUT_OWNER);

    public Sequences {
        chains = List.copyOf(chains);
    }

    static final Set<String> ROOT_KEYS = Set.of("userMd", "chains");
    static final Set<String> CHAIN_KEYS = Set.of("id", "tags", "memories", "probes");
    static final Set<String> MEMORY_KEYS = Set.of("id", "capturedAt", "text", "entities", "relations", "dates",
            "supersedes", "derivedFrom", "message", "authorType");
    static final Set<String> PROBE_KEYS = Set.of("from", "type", "to", "d", "after", "before", "truth", "assumed");
    private static final int FINGERPRINT_HEX = 12;

    /** One memory: its v3 labels, the earlier memories it supersedes (with gold lineage) and derives from. */
    public record Memory(GraphCases.Case labels, LinkedHashMap<String, OntologyRecord.Lineage> supersedes,
                         List<String> derivedFrom, @Nullable String message, MemoryAuthorType authorType) {
        public Memory {
            supersedes = new LinkedHashMap<>(supersedes);
            derivedFrom = List.copyOf(derivedFrom);
        }

        public String id() {
            return labels.id();
        }
    }

    /** Whether {@code from -type-> to} held at {@code d}, read just after or just before one memory. */
    public record Probe(String from, String type, String to, LocalDate d, @Nullable String after,
                        @Nullable String before, GraphView.Truth truth, boolean assumed) {
        /** The memory the probe is read beside. */
        public String memoryId() {
            var id = after != null ? after : before;
            if (id == null) throw new IllegalStateException("a probe names after or before");
            return id;
        }
    }

    public record Chain(String id, List<String> tags, List<Memory> memories, List<Probe> probes) {
        public Chain {
            tags = List.copyOf(tags);
            memories = List.copyOf(memories);
            probes = List.copyOf(probes);
        }

        public int indexOf(String memoryId) {
            for (int i = 0; i < memories.size(); i++) {
                if (memories.get(i).id().equals(memoryId)) return i;
            }
            return -1;
        }

        /** Every gold supersedes link of the chain. */
        public int links() {
            return memories.stream().mapToInt(m -> m.supersedes().size()).sum();
        }
    }

    public static Sequences load(Path path, OntologySchema schema) throws IOException {
        return parse(Files.readString(path), schema);
    }

    /**
     * The set in {@code json}.
     *
     * @throws IllegalArgumentException naming the chain and the memory or probe, on any break of the rules
     */
    public static Sequences parse(String json, OntologySchema schema) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("sequences are not valid JSON: " + e.getMessage(), e);
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("sequences: the document must be an object");
        var root = parsed.getAsJsonObject();
        GraphCases.onlyKeys(root, ROOT_KEYS, "sequences");
        var userMd = GraphCases.text(root, "userMd", "sequences");
        var owner = WorkspaceFiles.ownerNameIn(userMd);
        var chains = new ArrayList<Chain>();
        var chainIds = new HashSet<String>();
        var memoryIds = new HashSet<String>();
        int index = 0;
        for (var element : GraphCases.array(root, "chains", "sequences")) {
            var where = "sequences: chain #" + index++;
            if (!element.isJsonObject()) throw new IllegalArgumentException(where + ": must be an object");
            var object = element.getAsJsonObject();
            var id = GraphCases.text(object, "id", where);
            if (!chainIds.add(id)) throw new IllegalArgumentException("sequences: chain " + id + ": duplicate id");
            try {
                chains.add(chain(object, id, owner, schema, memoryIds));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("sequences: chain " + id + ": " + e.getMessage(), e);
            }
        }
        return new Sequences(owner, userMd, chains, fingerprint(root));
    }

    private static Chain chain(JsonObject object, String id, @Nullable String owner, OntologySchema schema,
                               Set<String> memoryIds) {
        GraphCases.onlyKeys(object, CHAIN_KEYS, "chain");
        var tags = GraphCases.strings(object, "tags", "chain");
        for (var tag : tags) {
            if (!CHAIN_TAGS.contains(tag)) throw new IllegalArgumentException("unknown tag '" + tag + "'");
        }
        boolean aboutOwner = tags.contains(GraphCases.GUEST_ABOUT_OWNER);
        var types = new HashMap<String, String>();
        var symmetric = schema.symmetricSet();
        var memories = new ArrayList<Memory>();
        LocalDate previous = null;
        int index = 0;
        for (var element : GraphCases.array(object, "memories", "chain")) {
            var where = "memory #" + index++;
            if (!element.isJsonObject()) throw new IllegalArgumentException(where + ": must be an object");
            var m = element.getAsJsonObject();
            var memoryId = GraphCases.text(m, "id", where);
            var labels = GraphCases.parseCase(m, memoryId, false, MEMORY_KEYS, null, schema, types, symmetric);
            where = "memory " + memoryId;
            if (!memoryIds.add(memoryId)) throw new IllegalArgumentException(where + ": duplicate id");
            if (previous != null && labels.capturedAt().isBefore(previous)) {
                throw new IllegalArgumentException(where + ": capturedAt " + labels.capturedAt()
                        + " is before the previous memory's " + previous);
            }
            previous = labels.capturedAt();
            var operator = labels.entity(GraphCases.OPERATOR);
            var mention = operator == null ? null : operator.mention();
            if (owner != null && mention != null && !mention.equalsIgnoreCase(GraphCases.IMPLICIT_OPERATOR_SPAN)
                    && !mention.equals(owner)) {
                throw new IllegalArgumentException(where + ": the operator is mentioned as '" + mention
                        + "', neither \"The user\" nor the declared owner '" + owner + "'");
            }

            var supersedes = new LinkedHashMap<String, OntologyRecord.Lineage>();
            if (m.has("supersedes")) {
                var raw = m.get("supersedes");
                if (!raw.isJsonObject()) throw new IllegalArgumentException(where + ": 'supersedes' must be an object");
                for (var entry : raw.getAsJsonObject().entrySet()) {
                    earlier(memories, entry.getKey(), memoryId, "supersedes", where);
                    supersedes.put(entry.getKey(), lineage(entry.getValue(), entry.getKey(), where));
                }
            }
            var derivedFrom = new ArrayList<String>();
            if (m.has("derivedFrom")) {
                for (var from : GraphCases.strings(m, "derivedFrom", where)) {
                    earlier(memories, from, memoryId, "derivedFrom", where);
                    if (derivedFrom.contains(from)) {
                        throw new IllegalArgumentException(where + ": derivedFrom names '" + from + "' twice");
                    }
                    derivedFrom.add(from);
                }
            }
            var message = m.has("message") ? GraphCases.text(m, "message", where) : null;
            var authorType = MemoryAuthorType.HUMAN_TURN;
            if (m.has("authorType")) {
                var raw = GraphCases.text(m, "authorType", where);
                authorType = switch (raw) {
                    case "human_turn" -> MemoryAuthorType.HUMAN_TURN;
                    case "guest_turn" -> MemoryAuthorType.GUEST_TURN;
                    case "consolidation_derived" -> MemoryAuthorType.CONSOLIDATION_DERIVED;
                    default -> throw new IllegalArgumentException(where + ": authorType '" + raw
                            + "' is not human_turn, guest_turn or consolidation_derived");
                };
            }
            if (authorType == MemoryAuthorType.CONSOLIDATION_DERIVED && derivedFrom.isEmpty()) {
                throw new IllegalArgumentException(where + ": a consolidation_derived memory names its sources in"
                        + " derivedFrom");
            }
            if (aboutOwner) {
                if (authorType != MemoryAuthorType.GUEST_TURN) {
                    throw new IllegalArgumentException(where + ": a " + GraphCases.GUEST_ABOUT_OWNER
                            + " chain's memories are guest_turn");
                }
                for (var r : labels.relations()) {
                    if (r.reaches(GraphCases.OPERATOR) && !r.status().equals(GraphCases.UNASSERTED)) {
                        throw new IllegalArgumentException(where + ": a " + GraphCases.GUEST_ABOUT_OWNER
                                + " chain asserts nothing of the operator, so '" + r.from() + "' -> '" + r.to()
                                + "' must be " + GraphCases.UNASSERTED);
                    }
                }
            }
            memories.add(new Memory(labels, supersedes, derivedFrom, message, authorType));
        }

        var probes = new ArrayList<Probe>();
        int p = 0;
        for (var element : GraphCases.array(object, "probes", "chain")) {
            var where = "probe #" + p++;
            if (!element.isJsonObject()) throw new IllegalArgumentException(where + ": must be an object");
            probes.add(probe(element.getAsJsonObject(), where, memories, types, schema));
        }
        return new Chain(id, tags, memories, probes);
    }

    private static void earlier(List<Memory> earlier, String target, String memoryId, String key, String where) {
        if (target.equals(memoryId)) throw new IllegalArgumentException(where + ": " + key + " names itself");
        if (earlier.stream().noneMatch(e -> e.id().equals(target))) {
            throw new IllegalArgumentException(where + ": " + key + " '" + target
                    + "' is not an earlier memory of the chain");
        }
    }

    private static OntologyRecord.Lineage lineage(JsonElement value, String target, String where) {
        var raw = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
        if (raw == null || !(raw.equals(UPDATE) || raw.equals(RESTATEMENT) || raw.equals(CORRECTION))) {
            throw new IllegalArgumentException(where + ": supersedes '" + target
                    + "' must map to update, restatement or correction");
        }
        return OntologyRecord.Lineage.valueOf(raw.toUpperCase(Locale.ROOT));
    }

    private static Probe probe(JsonObject o, String where, List<Memory> memories, Map<String, String> types,
                               OntologySchema schema) {
        GraphCases.onlyKeys(o, PROBE_KEYS, where);
        var from = GraphCases.text(o, "from", where);
        var type = GraphCases.text(o, "type", where);
        var to = GraphCases.text(o, "to", where);
        var fromType = types.get(from);
        var toType = types.get(to);
        if (fromType == null || toType == null) {
            throw new IllegalArgumentException(where + ": '" + (fromType == null ? from : to)
                    + "' is not an entity id labelled in the chain");
        }
        if (!schema.allows(type, fromType, toType)) {
            throw new IllegalArgumentException(where + ": the schema does not allow " + fromType + " " + type + " "
                    + toType);
        }
        var d = GraphCases.date(o, "d", where);
        if (o.has("after") == o.has("before")) {
            throw new IllegalArgumentException(where + ": exactly one of 'after' and 'before' is required");
        }
        var after = o.has("after") ? GraphCases.text(o, "after", where) : null;
        var before = o.has("before") ? GraphCases.text(o, "before", where) : null;
        var memory = after != null ? after : before;
        if (memories.stream().noneMatch(m -> m.id().equals(memory))) {
            throw new IllegalArgumentException(where + ": '" + memory + "' is not a memory of the chain");
        }
        var raw = GraphCases.text(o, "truth", where);
        GraphView.Truth truth;
        try {
            truth = GraphView.Truth.valueOf(raw);
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException(where + ": truth '" + raw + "' is not YES, NO or UNKNOWN");
        }
        return new Probe(from, type, to, d, after, before, truth, GraphCases.flag(o, "assumed", where));
    }

    /** {@code sequences@<12 hex>}: a SHA-256 prefix over the document with its keys sorted at every level, written compact. */
    static String fingerprint(JsonElement root) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(root).toString().getBytes(StandardCharsets.UTF_8));
            return "sequences@" + HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_HEX);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static JsonElement canonical(JsonElement e) {
        if (e.isJsonObject()) {
            var sorted = new JsonObject();
            var object = e.getAsJsonObject();
            for (var key : new TreeSet<>(object.keySet())) sorted.add(key, canonical(object.get(key)));
            return sorted;
        }
        if (e.isJsonArray()) {
            var out = new JsonArray();
            e.getAsJsonArray().forEach(x -> out.add(canonical(x)));
            return out;
        }
        return e;
    }
}
