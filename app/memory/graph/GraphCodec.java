package memory.graph;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Constraint;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Lineage;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Status;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologyRecord.Valence;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The canonical line format of the graph documents: one JSON object per line, the common
 * {@link Meta} keys first, then the family's required keys in component order, then whichever
 * optional keys are set in their fixed order, so the same record set always encodes to the same
 * bytes. An unset optional key is omitted, never written as null or an empty array.
 */
public final class GraphCodec {

    private static final List<String> META_KEYS =
            List.of("id", "agentId", "tier", "usefulRecallCount", "lastUsefulRecall", "graphVersion");

    /** One document per family; the declaration order is the order files are written in. */
    public enum Family {
        TERM("term.jsonl", List.of("type", "name", "mappingIds", "evidenceIds"), List.of("aliases", "mergedInto")),
        MAPPING("mapping.jsonl", List.of("termId", "source", "evidenceIds"), List.of("surfaces")),
        RELATION("relation.jsonl", List.of("type", "from", "to", "weight", "evidenceIds"), List.of()),
        CONSTRAINT("constraint.jsonl", List.of("termId", "rule", "evidenceIds"), List.of()),
        EVIDENCE("evidence.jsonl", List.of("source", "subjectId"), List.of("authorType", "confidence", "runId",
                "recordedAt", "retiredAt", "retiredBy", "lineage", "changedBy", "anchor", "status", "valid", "occurs",
                "valence"));

        private final String fileName;
        private final List<String> keys;
        private final List<String> optionalKeys;

        Family(String fileName, List<String> ownKeys, List<String> optionalKeys) {
            this.fileName = fileName;
            var all = new ArrayList<>(META_KEYS);
            all.addAll(ownKeys);
            this.keys = List.copyOf(all);
            this.optionalKeys = optionalKeys;
        }

        public String fileName() {
            return fileName;
        }

        /** Every key a line of this family carries, in written order. */
        public List<String> keys() {
            return keys;
        }

        /** The keys a line carries only when set, written after {@link #keys()} in this order. */
        public List<String> optionalKeys() {
            return optionalKeys;
        }

        public static Family of(OntologyRecord record) {
            return switch (record) {
                case Term _ -> TERM;
                case Mapping _ -> MAPPING;
                case Relation _ -> RELATION;
                case Constraint _ -> CONSTRAINT;
                case Evidence _ -> EVIDENCE;
            };
        }

        public static @Nullable Family ofFileName(String fileName) {
            for (var f : values()) {
                if (f.fileName.equals(fileName)) return f;
            }
            return null;
        }
    }

    private GraphCodec() {}

    /** The whole document for {@code family}: its records' lines sorted by id, each ending in {@code \n}. */
    public static String document(Family family, Collection<? extends OntologyRecord> records) {
        var sb = new StringBuilder();
        records.stream()
                .filter(r -> Family.of(r) == family)
                .distinct()
                .sorted(Comparator.comparing(OntologyRecord::id))
                .forEach(r -> sb.append(encode(r)).append('\n'));
        return sb.toString();
    }

    /** One record as its canonical line, without the trailing newline. */
    public static String encode(OntologyRecord record) {
        var out = new StringWriter();
        try (var w = new JsonWriter(out)) {
            w.setSerializeNulls(true);
            w.beginObject();
            var meta = record.meta();
            w.name("id").value(meta.id());
            w.name("agentId").value(meta.agentId());
            w.name("tier").value(meta.tier().name().toLowerCase(Locale.ROOT));
            w.name("usefulRecallCount").value(meta.usefulRecallCount());
            var last = meta.lastUsefulRecall();
            w.name("lastUsefulRecall").value(last == null ? null : last.toString());
            w.name("graphVersion").value(meta.graphVersion());
            switch (record) {
                case Term t -> {
                    w.name("type").value(t.type());
                    w.name("name").value(t.name());
                    strings(w.name("mappingIds"), t.mappingIds());
                    strings(w.name("evidenceIds"), t.evidenceIds());
                    optionalStrings(w, "aliases", t.aliases());
                    optional(w, "mergedInto", t.mergedInto());
                }
                case Mapping m -> {
                    w.name("termId").value(m.termId());
                    w.name("source").value(m.source());
                    strings(w.name("evidenceIds"), m.evidenceIds());
                    optionalStrings(w, "surfaces", m.surfaces());
                }
                case Relation r -> {
                    w.name("type").value(r.type());
                    w.name("from").value(r.from());
                    w.name("to").value(r.to());
                    if (!Double.isFinite(r.weight())) {
                        throw new IllegalArgumentException("relation " + r.id() + ": weight " + r.weight() + " is not finite");
                    }
                    w.name("weight").value(r.weight());
                    strings(w.name("evidenceIds"), r.evidenceIds());
                }
                case Constraint c -> {
                    w.name("termId").value(c.termId());
                    w.name("rule").value(c.rule());
                    strings(w.name("evidenceIds"), c.evidenceIds());
                }
                case Evidence e -> {
                    w.name("source").value(e.source());
                    w.name("subjectId").value(e.subjectId());
                    optional(w, "authorType", lower(e.authorType()));
                    var confidence = e.confidence();
                    if (confidence != null) {
                        if (!Double.isFinite(confidence)) {
                            throw new IllegalArgumentException(
                                    "evidence " + e.id() + ": confidence " + confidence + " is not finite");
                        }
                        w.name("confidence").value(confidence);
                    }
                    optional(w, "runId", e.runId());
                    optional(w, "recordedAt", text(e.recordedAt()));
                    optional(w, "retiredAt", text(e.retiredAt()));
                    optional(w, "retiredBy", e.retiredBy());
                    optional(w, "lineage", lower(e.lineage()));
                    optional(w, "changedBy", text(e.changedBy()));
                    optional(w, "anchor", text(e.anchor()));
                    optional(w, "status", lower(e.status()));
                    optional(w, "valid", text(e.valid()));
                    optional(w, "occurs", text(e.occurs()));
                    optional(w, "valence", lower(e.valence()));
                }
            }
            w.endObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static void strings(JsonWriter w, List<String> values) throws IOException {
        w.beginArray();
        for (var v : values) w.value(v);
        w.endArray();
    }

    private static void optional(JsonWriter w, String key, @Nullable String value) throws IOException {
        if (value != null) w.name(key).value(value);
    }

    private static void optionalStrings(JsonWriter w, String key, List<String> values) throws IOException {
        if (!values.isEmpty()) strings(w.name(key), values);
    }

    private static @Nullable String lower(@Nullable Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    /** Instants, dates and EDTF intervals all round-trip through their {@code toString}. */
    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    /** Every record in a document; a blank document is empty. */
    public static List<OntologyRecord> parseDocument(Family family, String content, String file) {
        var out = new ArrayList<OntologyRecord>();
        var lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            out.add(decode(family, lines[i], file, i + 1));
        }
        return out;
    }

    /**
     * One line back to its record.
     *
     * @throws IllegalArgumentException naming {@code file} and {@code lineNo} on a malformed
     *                                  line, a missing key, an unknown one, or an optional key written
     *                                  as null or an empty array
     */
    public static OntologyRecord decode(Family family, String line, String file, int lineNo) {
        try {
            var values = readObject(line);
            for (var entry : values.entrySet()) {
                var key = entry.getKey();
                if (family.keys().contains(key)) continue;
                if (!family.optionalKeys().contains(key)) {
                    throw new IllegalArgumentException("unknown key '" + key + "'");
                }
                if (entry.getValue() instanceof Value.Null) {
                    throw new IllegalArgumentException("'" + key + "' is optional and must be omitted rather than null");
                }
                if (entry.getValue() instanceof Value.Texts(var texts) && texts.isEmpty()) {
                    throw new IllegalArgumentException("'" + key + "' is optional and must be omitted rather than empty");
                }
            }
            for (var key : family.keys()) {
                if (!values.containsKey(key)) throw new IllegalArgumentException("missing key '" + key + "'");
            }
            var fields = new Fields(values);
            var meta = new Meta(fields.string("id"), fields.longValue("agentId"), tier(fields.string("tier")),
                    fields.intValue("usefulRecallCount"), instant(fields.nullableString("lastUsefulRecall")),
                    fields.intValue("graphVersion"));
            return switch (family) {
                case TERM -> new Term(meta, fields.string("type"), fields.string("name"),
                        fields.strings("mappingIds"), fields.strings("evidenceIds"), fields.optionalStrings("aliases"),
                        fields.optionalString("mergedInto"));
                case MAPPING -> new Mapping(meta, fields.string("termId"), fields.string("source"),
                        fields.strings("evidenceIds"), fields.optionalStrings("surfaces"));
                case RELATION -> new Relation(meta, fields.string("type"), fields.string("from"), fields.string("to"),
                        fields.doubleValue("weight"), fields.strings("evidenceIds"));
                case CONSTRAINT -> new Constraint(meta, fields.string("termId"), fields.string("rule"),
                        fields.strings("evidenceIds"));
                case EVIDENCE -> new Evidence(meta, fields.string("source"), fields.nullableString("subjectId"),
                        fields.optionalEnum("authorType", MemoryAuthorType.class),
                        fields.optionalDouble("confidence"),
                        fields.optionalString("runId"),
                        instant(fields.optionalString("recordedAt")),
                        instant(fields.optionalString("retiredAt")),
                        fields.optionalString("retiredBy"),
                        fields.optionalEnum("lineage", Lineage.class),
                        date(fields.optionalString("changedBy")),
                        date(fields.optionalString("anchor")),
                        fields.optionalEnum("status", Status.class),
                        interval(fields.optionalString("valid")),
                        interval(fields.optionalString("occurs")),
                        fields.optionalEnum("valence", Valence.class));
            };
        } catch (IllegalArgumentException | IllegalStateException | IOException | DateTimeParseException e) {
            throw new IllegalArgumentException(file + ":" + lineNo + ": " + e.getMessage(), e);
        }
    }

    private static Tier tier(String value) {
        return switch (value) {
            case "tentative" -> Tier.TENTATIVE;
            case "firm" -> Tier.FIRM;
            default -> throw new IllegalArgumentException("tier '" + value + "' is neither tentative nor firm");
        };
    }

    private static @Nullable Instant instant(@Nullable String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static @Nullable LocalDate date(@Nullable String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static @Nullable EdtfInterval interval(@Nullable String value) {
        return value == null ? null : EdtfInterval.parse(value);
    }

    /** A JSON value as read; a number keeps its literal so each field parses it at its own width. */
    private sealed interface Value {
        record Text(String text) implements Value {}

        record Number(String literal) implements Value {}

        record Null() implements Value {}

        record Texts(List<String> texts) implements Value {}
    }

    private static Map<String, Value> readObject(String line) throws IOException {
        var values = new HashMap<String, Value>();
        try (var r = new JsonReader(new StringReader(line))) {
            r.setStrictness(Strictness.STRICT);
            r.beginObject();
            while (r.hasNext()) {
                var name = r.nextName();
                var value = switch (r.peek()) {
                    case STRING -> new Value.Text(r.nextString());
                    case NUMBER -> new Value.Number(r.nextString());
                    case NULL -> {
                        r.nextNull();
                        yield new Value.Null();
                    }
                    case BEGIN_ARRAY -> {
                        var texts = new ArrayList<String>();
                        r.beginArray();
                        while (r.hasNext()) {
                            if (r.peek() != JsonToken.STRING) {
                                throw new IllegalArgumentException("'" + name + "' holds a non-string element");
                            }
                            texts.add(r.nextString());
                        }
                        r.endArray();
                        yield new Value.Texts(texts);
                    }
                    default -> throw new IllegalArgumentException("'" + name + "' holds an unexpected " + r.peek());
                };
                if (values.put(name, value) != null) throw new IllegalArgumentException("duplicate key '" + name + "'");
            }
            r.endObject();
            if (r.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing content");
        }
        return values;
    }

    private record Fields(Map<String, Value> values) {
        String string(String key) {
            if (values.get(key) instanceof Value.Text(var text)) return text;
            throw new IllegalArgumentException("'" + key + "' is not a string");
        }

        @Nullable String nullableString(String key) {
            return values.get(key) instanceof Value.Null ? null : string(key);
        }

        String number(String key) {
            if (values.get(key) instanceof Value.Number(var literal)) return literal;
            throw new IllegalArgumentException("'" + key + "' is not a number");
        }

        long longValue(String key) {
            return Long.parseLong(number(key));
        }

        int intValue(String key) {
            return Integer.parseInt(number(key));
        }

        double doubleValue(String key) {
            return Double.parseDouble(number(key));
        }

        List<String> strings(String key) {
            if (values.get(key) instanceof Value.Texts(var texts)) return texts;
            throw new IllegalArgumentException("'" + key + "' is not a list of strings");
        }

        @Nullable String optionalString(String key) {
            return values.containsKey(key) ? string(key) : null;
        }

        List<String> optionalStrings(String key) {
            return values.containsKey(key) ? strings(key) : List.of();
        }

        @Nullable Double optionalDouble(String key) {
            return values.containsKey(key) ? doubleValue(key) : null;
        }

        /** Only the constant's exact lower-case name decodes. */
        <E extends Enum<E>> @Nullable E optionalEnum(String key, Class<E> type) {
            if (!values.containsKey(key)) return null;
            var text = string(key);
            for (var constant : type.getEnumConstants()) {
                if (constant.name().toLowerCase(Locale.ROOT).equals(text)) return constant;
            }
            throw new IllegalArgumentException("'" + key + "' value '" + text + "' is not a known " + key);
        }
    }
}
