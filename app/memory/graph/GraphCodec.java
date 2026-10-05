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

    private static final String KEY_ID = "id";
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_TIER = "tier";
    private static final String KEY_USEFUL_RECALL_COUNT = "usefulRecallCount";
    private static final String KEY_LAST_USEFUL_RECALL = "lastUsefulRecall";
    private static final String KEY_GRAPH_VERSION = "graphVersion";
    private static final String KEY_TYPE = "type";
    private static final String KEY_NAME = "name";
    private static final String KEY_MAPPING_IDS = "mappingIds";
    private static final String KEY_EVIDENCE_IDS = "evidenceIds";
    private static final String KEY_ALIASES = "aliases";
    private static final String KEY_MERGED_INTO = "mergedInto";
    private static final String KEY_TERM_ID = "termId";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_SURFACES = "surfaces";
    private static final String KEY_FROM = "from";
    private static final String KEY_TO = "to";
    private static final String KEY_WEIGHT = "weight";
    private static final String KEY_RULE = "rule";
    private static final String KEY_SUBJECT_ID = "subjectId";
    private static final String KEY_AUTHOR_TYPE = "authorType";
    private static final String KEY_CONFIDENCE = "confidence";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_RECORDED_AT = "recordedAt";
    private static final String KEY_RETIRED_AT = "retiredAt";
    private static final String KEY_RETIRED_BY = "retiredBy";
    private static final String KEY_LINEAGE = "lineage";
    private static final String KEY_CHANGED_BY = "changedBy";
    private static final String KEY_ANCHOR = "anchor";
    private static final String KEY_STATUS = "status";
    private static final String KEY_VALID = "valid";
    private static final String KEY_OCCURS = "occurs";
    private static final String KEY_VALENCE = "valence";

    private static final List<String> META_KEYS =
            List.of(KEY_ID, KEY_AGENT_ID, KEY_TIER, KEY_USEFUL_RECALL_COUNT, KEY_LAST_USEFUL_RECALL, KEY_GRAPH_VERSION);

    /** One document per family; the declaration order is the order files are written in. */
    public enum Family {
        TERM("term.jsonl", List.of(KEY_TYPE, KEY_NAME, KEY_MAPPING_IDS, KEY_EVIDENCE_IDS), List.of(KEY_ALIASES, KEY_MERGED_INTO)),
        MAPPING("mapping.jsonl", List.of(KEY_TERM_ID, KEY_SOURCE, KEY_EVIDENCE_IDS), List.of(KEY_SURFACES)),
        RELATION("relation.jsonl", List.of(KEY_TYPE, KEY_FROM, KEY_TO, KEY_WEIGHT, KEY_EVIDENCE_IDS), List.of()),
        CONSTRAINT("constraint.jsonl", List.of(KEY_TERM_ID, KEY_RULE, KEY_EVIDENCE_IDS), List.of()),
        EVIDENCE("evidence.jsonl", List.of(KEY_SOURCE, KEY_SUBJECT_ID), List.of(KEY_AUTHOR_TYPE, KEY_CONFIDENCE, KEY_RUN_ID,
                KEY_RECORDED_AT, KEY_RETIRED_AT, KEY_RETIRED_BY, KEY_LINEAGE, KEY_CHANGED_BY, KEY_ANCHOR, KEY_STATUS, KEY_VALID, KEY_OCCURS,
                KEY_VALENCE));

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
            w.name(KEY_ID).value(meta.id());
            w.name(KEY_AGENT_ID).value(meta.agentId());
            w.name(KEY_TIER).value(meta.tier().name().toLowerCase(Locale.ROOT));
            w.name(KEY_USEFUL_RECALL_COUNT).value(meta.usefulRecallCount());
            var last = meta.lastUsefulRecall();
            w.name(KEY_LAST_USEFUL_RECALL).value(last == null ? null : last.toString());
            w.name(KEY_GRAPH_VERSION).value(meta.graphVersion());
            switch (record) {
                case Term t -> {
                    w.name(KEY_TYPE).value(t.type());
                    w.name(KEY_NAME).value(t.name());
                    strings(w.name(KEY_MAPPING_IDS), t.mappingIds());
                    strings(w.name(KEY_EVIDENCE_IDS), t.evidenceIds());
                    optionalStrings(w, KEY_ALIASES, t.aliases());
                    optional(w, KEY_MERGED_INTO, t.mergedInto());
                }
                case Mapping m -> {
                    w.name(KEY_TERM_ID).value(m.termId());
                    w.name(KEY_SOURCE).value(m.source());
                    strings(w.name(KEY_EVIDENCE_IDS), m.evidenceIds());
                    optionalStrings(w, KEY_SURFACES, m.surfaces());
                }
                case Relation r -> {
                    w.name(KEY_TYPE).value(r.type());
                    w.name(KEY_FROM).value(r.from());
                    w.name(KEY_TO).value(r.to());
                    if (!Double.isFinite(r.weight())) {
                        throw new IllegalArgumentException("relation " + r.id() + ": weight " + r.weight() + " is not finite");
                    }
                    w.name(KEY_WEIGHT).value(r.weight());
                    strings(w.name(KEY_EVIDENCE_IDS), r.evidenceIds());
                }
                case Constraint c -> {
                    w.name(KEY_TERM_ID).value(c.termId());
                    w.name(KEY_RULE).value(c.rule());
                    strings(w.name(KEY_EVIDENCE_IDS), c.evidenceIds());
                }
                case Evidence e -> {
                    w.name(KEY_SOURCE).value(e.source());
                    w.name(KEY_SUBJECT_ID).value(e.subjectId());
                    optional(w, KEY_AUTHOR_TYPE, lower(e.authorType()));
                    var confidence = e.confidence();
                    if (confidence != null) {
                        if (!Double.isFinite(confidence)) {
                            throw new IllegalArgumentException(
                                    "evidence " + e.id() + ": confidence " + confidence + " is not finite");
                        }
                        w.name(KEY_CONFIDENCE).value(confidence);
                    }
                    optional(w, KEY_RUN_ID, e.runId());
                    optional(w, KEY_RECORDED_AT, text(e.recordedAt()));
                    optional(w, KEY_RETIRED_AT, text(e.retiredAt()));
                    optional(w, KEY_RETIRED_BY, e.retiredBy());
                    optional(w, KEY_LINEAGE, lower(e.lineage()));
                    optional(w, KEY_CHANGED_BY, text(e.changedBy()));
                    optional(w, KEY_ANCHOR, text(e.anchor()));
                    optional(w, KEY_STATUS, lower(e.status()));
                    optional(w, KEY_VALID, text(e.valid()));
                    optional(w, KEY_OCCURS, text(e.occurs()));
                    optional(w, KEY_VALENCE, lower(e.valence()));
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
            var meta = new Meta(fields.string(KEY_ID), fields.longValue(KEY_AGENT_ID), tier(fields.string(KEY_TIER)),
                    fields.intValue(KEY_USEFUL_RECALL_COUNT), instant(fields.nullableString(KEY_LAST_USEFUL_RECALL)),
                    fields.intValue(KEY_GRAPH_VERSION));
            return switch (family) {
                case TERM -> new Term(meta, fields.string(KEY_TYPE), fields.string(KEY_NAME),
                        fields.strings(KEY_MAPPING_IDS), fields.strings(KEY_EVIDENCE_IDS), fields.optionalStrings(KEY_ALIASES),
                        fields.optionalString(KEY_MERGED_INTO));
                case MAPPING -> new Mapping(meta, fields.string(KEY_TERM_ID), fields.string(KEY_SOURCE),
                        fields.strings(KEY_EVIDENCE_IDS), fields.optionalStrings(KEY_SURFACES));
                case RELATION -> new Relation(meta, fields.string(KEY_TYPE), fields.string(KEY_FROM), fields.string(KEY_TO),
                        fields.doubleValue(KEY_WEIGHT), fields.strings(KEY_EVIDENCE_IDS));
                case CONSTRAINT -> new Constraint(meta, fields.string(KEY_TERM_ID), fields.string(KEY_RULE),
                        fields.strings(KEY_EVIDENCE_IDS));
                case EVIDENCE -> new Evidence(meta, fields.string(KEY_SOURCE), fields.nullableString(KEY_SUBJECT_ID),
                        fields.optionalEnum(KEY_AUTHOR_TYPE, MemoryAuthorType.class),
                        fields.optionalDouble(KEY_CONFIDENCE),
                        fields.optionalString(KEY_RUN_ID),
                        instant(fields.optionalString(KEY_RECORDED_AT)),
                        instant(fields.optionalString(KEY_RETIRED_AT)),
                        fields.optionalString(KEY_RETIRED_BY),
                        fields.optionalEnum(KEY_LINEAGE, Lineage.class),
                        date(fields.optionalString(KEY_CHANGED_BY)),
                        date(fields.optionalString(KEY_ANCHOR)),
                        fields.optionalEnum(KEY_STATUS, Status.class),
                        interval(fields.optionalString(KEY_VALID)),
                        interval(fields.optionalString(KEY_OCCURS)),
                        fields.optionalEnum(KEY_VALENCE, Valence.class));
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
