package memory.graph;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Constraint;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Instant;
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
 * {@link Meta} keys first and then the family's own keys in component order, so the same
 * record set always encodes to the same bytes.
 */
public final class GraphCodec {

    private static final List<String> META_KEYS =
            List.of("id", "agentId", "tier", "usefulRecallCount", "lastUsefulRecall", "graphVersion");

    /** One document per family; the declaration order is the order files are written in. */
    public enum Family {
        TERM("term.jsonl", List.of("type", "name", "mappingIds", "evidenceIds")),
        MAPPING("mapping.jsonl", List.of("termId", "source", "evidenceIds")),
        RELATION("relation.jsonl", List.of("type", "from", "to", "weight", "evidenceIds")),
        CONSTRAINT("constraint.jsonl", List.of("termId", "rule", "evidenceIds")),
        EVIDENCE("evidence.jsonl", List.of("source", "subjectId"));

        private final String fileName;
        private final List<String> keys;

        Family(String fileName, List<String> ownKeys) {
            this.fileName = fileName;
            var all = new ArrayList<>(META_KEYS);
            all.addAll(ownKeys);
            this.keys = List.copyOf(all);
        }

        public String fileName() {
            return fileName;
        }

        /** Every key a line of this family carries, in written order. */
        public List<String> keys() {
            return keys;
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
                }
                case Mapping m -> {
                    w.name("termId").value(m.termId());
                    w.name("source").value(m.source());
                    strings(w.name("evidenceIds"), m.evidenceIds());
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
     *                                  line, a missing key or an unknown one
     */
    public static OntologyRecord decode(Family family, String line, String file, int lineNo) {
        try {
            var values = readObject(line);
            for (var key : values.keySet()) {
                if (!family.keys().contains(key)) throw new IllegalArgumentException("unknown key '" + key + "'");
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
                        fields.strings("mappingIds"), fields.strings("evidenceIds"));
                case MAPPING -> new Mapping(meta, fields.string("termId"), fields.string("source"),
                        fields.strings("evidenceIds"));
                case RELATION -> new Relation(meta, fields.string("type"), fields.string("from"), fields.string("to"),
                        fields.doubleValue("weight"), fields.strings("evidenceIds"));
                case CONSTRAINT -> new Constraint(meta, fields.string("termId"), fields.string("rule"),
                        fields.strings("evidenceIds"));
                case EVIDENCE -> new Evidence(meta, fields.string("source"), fields.nullableString("subjectId"));
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
    }
}
