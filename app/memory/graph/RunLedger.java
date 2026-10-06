package memory.graph;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * An agent's extraction runs (JCLAW-1371): one canonical JSON object per line in {@value #FILE_NAME} beside the
 * graph's family files, sorted by run id, so a run, its model, certificate and fingerprints, and every decision it
 * made can be found and its records retracted.
 */
public final class RunLedger {

    public static final String FILE_NAME = "ledger.jsonl";

    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_TEXT = "text";
    private static final String KEY_OUTCOME = "outcome";
    private static final String KEY_MODEL = "model";
    private static final String KEY_DIGEST = "digest";
    private static final String KEY_CERTIFICATE = "certificate";
    private static final String KEY_SCHEMA = "schema";
    private static final String KEY_EXTRACTION = "extraction";
    private static final String KEY_RETRACTED = "retracted";
    private static final String KEY_DECISIONS = "decisions";
    private static final String KEY_STAGE = "stage";
    private static final String KEY_SUBJECT = "subject";
    private static final String KEY_RELATION = "relation";
    private static final String KEY_PROBABILITIES = "probabilities";
    private static final String KEY_YES = "yes";
    private static final String KEY_FAILURE = "failure";
    private static final String KEY_OPERATOR = "operator";

    private static final Set<String> ENTRY_KEYS = Set.of(KEY_RUN_ID, KEY_AGENT_ID, KEY_SOURCE, KEY_TEXT, KEY_OUTCOME,
            KEY_MODEL, KEY_DIGEST, KEY_CERTIFICATE, KEY_SCHEMA, KEY_EXTRACTION, KEY_RETRACTED, KEY_DECISIONS);
    private static final Set<String> DECISION_KEYS = Set.of(KEY_STAGE, KEY_SUBJECT, KEY_RELATION, KEY_PROBABILITIES,
            KEY_YES, KEY_FAILURE, KEY_OPERATOR);
    private static final TypeAdapter<JsonElement> ELEMENT = new Gson().getAdapter(JsonElement.class);

    private RunLedger() {}

    /** What a run left in the graph: {@code written} records, or nothing because it found, abstained or failed. */
    public enum Outcome {
        WRITTEN, EMPTY, ABSTAINED, FAILED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Outcome ofWire(String value) {
            for (var o : values()) {
                if (o.wire().equals(value)) return o;
            }
            throw new IllegalArgumentException("outcome '" + value + "' is not a known outcome");
        }
    }

    /**
     * One decision as the ledger keeps it: exactly one of a choice's {@code probabilities}, a yes/no's {@code yes}, a
     * {@code failure}, or {@code operator} for the operator term nobody asked about.
     */
    public record DecisionEntry(String stage, String subject, @Nullable String relation,
                                Map<String, Double> probabilities, @Nullable Double yes, @Nullable String failure,
                                boolean operator) {
        public DecisionEntry {
            probabilities = Collections.unmodifiableSortedMap(new TreeMap<>(probabilities));
            int set = (probabilities.isEmpty() ? 0 : 1) + (yes == null ? 0 : 1) + (failure == null ? 0 : 1)
                    + (operator ? 1 : 0);
            if (set != 1) {
                throw new IllegalArgumentException("decision " + stage + " '" + subject
                        + "' needs exactly one of probabilities, yes, failure or operator");
            }
            for (var p : probabilities.values()) {
                if (p == null || !Double.isFinite(p)) throw new IllegalArgumentException("a probability is not finite");
            }
            if (yes != null && !Double.isFinite(yes)) throw new IllegalArgumentException("yes is not finite");
        }
    }

    /** One run; {@code text} is {@code sha256:<hex>} of the memory text, never the text itself. */
    public record Entry(String runId, long agentId, String source, String text, Outcome outcome, String model,
                        String digest, String certificate, String schema, String extraction, boolean retracted,
                        List<DecisionEntry> decisions) {
        public Entry {
            decisions = List.copyOf(decisions);
        }

        public Entry withRetracted(boolean value) {
            return new Entry(runId, agentId, source, text, outcome, model, digest, certificate, schema, extraction,
                    value, decisions);
        }
    }

    /** Which runs a cross-agent retraction takes. */
    public sealed interface Selector {
        boolean matches(Entry entry);

        /** Every digest of one model name. */
        record Model(String name) implements Selector {
            public boolean matches(Entry entry) {
                return entry.model().equals(name);
            }
        }

        record Digest(String digest) implements Selector {
            public boolean matches(Entry entry) {
                return entry.digest().equals(digest);
            }
        }

        record Certificate(String id) implements Selector {
            public boolean matches(Entry entry) {
                return entry.certificate().equals(id);
            }
        }
    }

    /** The whole ledger: one line per entry, sorted by run id, each ending in {@code \n}. */
    public static String document(Collection<Entry> entries) {
        var sb = new StringBuilder();
        entries.stream().sorted(Comparator.comparing(Entry::runId)).forEach(e -> sb.append(encode(e)).append('\n'));
        return sb.toString();
    }

    public static String encode(Entry e) {
        var out = new StringWriter();
        try (var w = new JsonWriter(out)) {
            w.beginObject();
            w.name(KEY_RUN_ID).value(e.runId());
            w.name(KEY_AGENT_ID).value(e.agentId());
            w.name(KEY_SOURCE).value(e.source());
            w.name(KEY_TEXT).value(e.text());
            w.name(KEY_OUTCOME).value(e.outcome().wire());
            w.name(KEY_MODEL).value(e.model());
            w.name(KEY_DIGEST).value(e.digest());
            w.name(KEY_CERTIFICATE).value(e.certificate());
            w.name(KEY_SCHEMA).value(e.schema());
            w.name(KEY_EXTRACTION).value(e.extraction());
            w.name(KEY_RETRACTED).value(e.retracted());
            w.name(KEY_DECISIONS).beginArray();
            for (var d : e.decisions()) {
                w.beginObject();
                w.name(KEY_STAGE).value(d.stage());
                w.name(KEY_SUBJECT).value(d.subject());
                if (d.relation() != null) w.name(KEY_RELATION).value(d.relation());
                var yes = d.yes();
                var failure = d.failure();
                if (!d.probabilities().isEmpty()) {
                    w.name(KEY_PROBABILITIES).beginObject();
                    for (var p : d.probabilities().entrySet()) w.name(p.getKey()).value(p.getValue().doubleValue());
                    w.endObject();
                } else if (yes != null) {
                    w.name(KEY_YES).value(yes.doubleValue());
                } else if (failure != null) {
                    w.name(KEY_FAILURE).value(failure);
                } else {
                    w.name(KEY_OPERATOR).value(true);
                }
                w.endObject();
            }
            w.endArray();
            w.endObject();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toString();
    }

    /**
     * Every entry of a ledger document; a blank document is empty.
     *
     * @throws IllegalArgumentException naming {@code origin} and the line on a malformed, incomplete or unknown key
     */
    public static List<Entry> parse(String content, String origin) {
        var out = new ArrayList<Entry>();
        var lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            try {
                out.add(decode(lines[i]));
            } catch (IllegalArgumentException | IllegalStateException | IOException | ClassCastException e) {
                throw new IllegalArgumentException(origin + ":" + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static Entry decode(String line) throws IOException {
        var o = object(read(line), "entry");
        keys(o, ENTRY_KEYS, ENTRY_KEYS);
        var decisions = new ArrayList<DecisionEntry>();
        var array = o.get(KEY_DECISIONS);
        if (!array.isJsonArray()) throw new IllegalArgumentException("'decisions' is not an array");
        for (var d : array.getAsJsonArray()) decisions.add(decision(object(d, "decision")));
        return new Entry(string(o, KEY_RUN_ID), Long.parseLong(number(o, KEY_AGENT_ID).getAsString()),
                string(o, KEY_SOURCE), string(o, KEY_TEXT), Outcome.ofWire(string(o, KEY_OUTCOME)),
                string(o, KEY_MODEL), string(o, KEY_DIGEST), string(o, KEY_CERTIFICATE), string(o, KEY_SCHEMA),
                string(o, KEY_EXTRACTION), bool(o, KEY_RETRACTED), decisions);
    }

    private static DecisionEntry decision(JsonObject o) {
        keys(o, Set.of(KEY_STAGE, KEY_SUBJECT), DECISION_KEYS);
        var probabilities = new TreeMap<String, Double>();
        if (o.has(KEY_PROBABILITIES)) {
            var p = object(o.get(KEY_PROBABILITIES), KEY_PROBABILITIES);
            if (p.isEmpty()) throw new IllegalArgumentException("'probabilities' is empty");
            for (var e : p.entrySet()) probabilities.put(e.getKey(), number(p, e.getKey()).getAsDouble());
        }
        if (o.has(KEY_OPERATOR) && !bool(o, KEY_OPERATOR)) {
            throw new IllegalArgumentException("'operator' is written only as true");
        }
        return new DecisionEntry(string(o, KEY_STAGE), string(o, KEY_SUBJECT),
                o.has(KEY_RELATION) ? string(o, KEY_RELATION) : null, probabilities,
                o.has(KEY_YES) ? number(o, KEY_YES).getAsDouble() : null,
                o.has(KEY_FAILURE) ? string(o, KEY_FAILURE) : null, o.has(KEY_OPERATOR));
    }

    private static JsonElement read(String line) throws IOException {
        try (var r = new JsonReader(new StringReader(line))) {
            r.setStrictness(Strictness.STRICT);
            var element = ELEMENT.read(r);
            if (r.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing content");
            return element;
        }
    }

    private static JsonObject object(JsonElement e, String what) {
        if (!e.isJsonObject()) throw new IllegalArgumentException(what + " is not an object");
        return e.getAsJsonObject();
    }

    private static void keys(JsonObject o, Set<String> required, Set<String> allowed) {
        for (var key : o.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("unknown key '" + key + "'");
        }
        for (var key : required) {
            if (!o.has(key)) throw new IllegalArgumentException("missing key '" + key + "'");
        }
    }

    private static JsonPrimitive primitive(JsonObject o, String key) {
        var e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) throw new IllegalArgumentException("'" + key + "' is not a value");
        return e.getAsJsonPrimitive();
    }

    private static String string(JsonObject o, String key) {
        var p = primitive(o, key);
        if (!p.isString()) throw new IllegalArgumentException("'" + key + "' is not a string");
        return p.getAsString();
    }

    private static JsonPrimitive number(JsonObject o, String key) {
        var p = primitive(o, key);
        if (!p.isNumber()) throw new IllegalArgumentException("'" + key + "' is not a number");
        return p;
    }

    private static boolean bool(JsonObject o, String key) {
        var p = primitive(o, key);
        if (!p.isBoolean()) throw new IllegalArgumentException("'" + key + "' is not a boolean");
        return p.getAsBoolean();
    }
}
