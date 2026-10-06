package services.grapheval;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import services.grapheval.GraphCases.Case;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static utils.GsonHolder.GSON;

/**
 * A frozen certification split (JCLAW-1368): the ids a certification run is judged on, each with its content hash,
 * and the fingerprints of the set, the sequences, the guide and the schema at freezing. A split is used once per model
 * version. The manifest lives at {@code <root>/splits/<name>.json}.
 */
public record CertificationSplit(String split, String set, long seed, List<String> ids, Map<String, String> hashes,
                                 String cases, String sequences, String guide, String schema,
                                 double startingThreshold, List<String> relationOrder) {

    public static final String DIR = "splits";
    public static final String CASES = "cases";
    public static final String HELDOUT = "heldout";
    public static final String GUIDE_PATH = "evals/graph/GUIDE.md";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Set<String> KEYS = Set.of("split", "set", "seed", "ids", "hashes", "cases", "sequences",
            "guide", "schema", "startingThreshold", "relationOrder");
    /** The held-out keys a memory's hash covers: its text and its labels, never its candidates or flags. */
    private static final List<String> HELD_KEYS = List.of("text", "capturedAt", "authorType", "tags", "entities",
            "relations", "negatives", "dates");

    public CertificationSplit {
        ids = List.copyOf(ids);
        hashes = Collections.unmodifiableMap(new LinkedHashMap<>(hashes));
        relationOrder = List.copyOf(relationOrder);
    }

    /**
     * What a split is drawn from and verified against: each item's raw JSON by id (a case id, or a held-out memory id),
     * the parsed cases under the same ids, and the running fingerprints.
     */
    public record Source(String set, Map<String, JsonObject> items, Map<String, Case> cases, String sequences,
                         String guide, String schema) {
        public Source {
            items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
            cases = Collections.unmodifiableMap(new LinkedHashMap<>(cases));
        }

        /** The committed set: {@code json} is {@code cases.json}, {@code parsed} its cases. */
        public static Source cases(String json, List<Case> parsed, String sequences, byte[] guide,
                                   OntologySchema schema) {
            var items = new LinkedHashMap<String, JsonObject>();
            for (var e : GraphCases.root(json).getAsJsonArray("cases")) {
                var o = e.getAsJsonObject();
                items.put(o.get("id").getAsString(), o);
            }
            var cases = new LinkedHashMap<String, Case>();
            parsed.forEach(c -> cases.put(c.id(), c));
            return new Source(CASES, items, cases, sequences, guideFingerprint(guide), schema.fingerprint());
        }

        /**
         * The held-out set: {@code json} is {@code heldout.json} and {@code loaded} its labelled cases, both keyed by
         * memory id; each case is re-identified by its memory id, which stays under {@code data/graph-eval/}.
         */
        public static Source heldout(String json, HeldOut.Loaded loaded, String sequences, byte[] guide,
                                     OntologySchema schema) {
            var items = new LinkedHashMap<String, JsonObject>();
            for (var e : GraphCases.root(json).getAsJsonArray("cases")) {
                var o = e.getAsJsonObject();
                if (!o.has("labelled") || !o.get("labelled").getAsBoolean()) continue;
                var labels = new JsonObject();
                for (var key : HELD_KEYS) {
                    if (o.has(key)) labels.add(key, o.get(key));
                }
                items.put(String.valueOf(o.get("memoryId").getAsLong()), labels);
            }
            var cases = new LinkedHashMap<String, Case>();
            for (var h : loaded.cases()) {
                var c = h.labels();
                var id = String.valueOf(h.memoryId());
                cases.put(id, new Case(id, c.tags(), c.text(), c.entities(), c.relations(), c.negatives(),
                        c.capturedAt(), c.dates()));
            }
            return new Source(HELDOUT, items, cases, sequences, guideFingerprint(guide), schema.fingerprint());
        }
    }

    /** {@code guide@<12 hex>} over the bytes of {@code GUIDE.md}. */
    public static String guideFingerprint(byte[] guide) {
        return Fingerprints.hex12("guide", guide);
    }

    public static Path path(Path root, String name) {
        return root.resolve(DIR).resolve(name + ".json");
    }

    /**
     * Freezes split {@code name} over {@code source}: {@code ids} as given, or drawn by a seeded shuffle at
     * {@code share} (default 1 for the held-out set). {@code relationOrder} defaults to each relation's gold count in
     * the set's cases outside the split, most frequent first, ties in schema order.
     *
     * @throws IllegalArgumentException when the name is taken or malformed, or an id, share, threshold or relation is
     *                                  not in the set
     */
    public static CertificationSplit freeze(Path root, String name, Source source, long seed, @Nullable Double share,
                                            @Nullable List<String> ids, double startingThreshold,
                                            @Nullable List<String> relationOrder, OntologySchema schema)
            throws IOException {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("split name must be letters, digits, '.', '_' or '-', up to 64");
        }
        var file = path(root, name);
        if (Files.exists(file)) {
            throw new IllegalArgumentException("split '%s' already exists: %s/%s.json".formatted(name, DIR, name));
        }
        if (GraphEvalScorer.THRESHOLDS.stream().noneMatch(t -> Math.abs(t - startingThreshold) < 1e-9)) {
            throw new IllegalArgumentException("startingThreshold must be a grid threshold, 0.95 to 0.50 by 0.05");
        }
        var chosen = chosen(source, seed, share, ids);
        var order = relationOrder == null ? defaultOrder(source, chosen, schema) : checkedOrder(relationOrder, schema);
        var hashes = new LinkedHashMap<String, String>();
        var selected = new JsonArray();
        for (var id : chosen) {
            var item = Objects.requireNonNull(source.items().get(id), "a chosen id is in the set");
            hashes.put(id, Fingerprints.hex12(item));
            selected.add(item);
        }
        var split = new CertificationSplit(name, source.set(), seed, chosen, hashes, Fingerprints.hex12(CASES, selected),
                source.sequences(), source.guide(), source.schema(), startingThreshold, order);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(split.toJson()), StandardOpenOption.CREATE_NEW);
        return split;
    }

    private static List<String> chosen(Source source, long seed, @Nullable Double share, @Nullable List<String> ids) {
        if (ids != null) {
            if (share != null) throw new IllegalArgumentException("give ids or a share, not both");
            if (ids.isEmpty()) throw new IllegalArgumentException("ids must not be empty");
            var unique = new LinkedHashSet<String>();
            for (var id : ids) {
                if (!source.items().containsKey(id)) {
                    throw new IllegalArgumentException("id '" + id + "' is not in the " + source.set() + " set");
                }
                if (!unique.add(id)) throw new IllegalArgumentException("id '" + id + "' is listed twice");
            }
            return inFileOrder(source, unique);
        }
        double s = share != null ? share : source.set().equals(HELDOUT) ? 1.0 : Double.NaN;
        if (!(s > 0 && s <= 1)) throw new IllegalArgumentException("share must be in (0, 1]");
        var all = new ArrayList<>(source.items().keySet());
        var shuffled = new ArrayList<>(all);
        Collections.shuffle(shuffled, new Random(seed));
        int count = Math.max(1, (int) Math.ceil(all.size() * s - 1e-9));
        return inFileOrder(source, new LinkedHashSet<>(shuffled.subList(0, Math.min(count, shuffled.size()))));
    }

    private static List<String> inFileOrder(Source source, Set<String> ids) {
        return source.items().keySet().stream().filter(ids::contains).toList();
    }

    private static List<String> checkedOrder(List<String> order, OntologySchema schema) {
        var seen = new LinkedHashSet<String>();
        for (var r : order) {
            if (!schema.relations().containsKey(r)) throw new IllegalArgumentException("unknown relation '" + r + "'");
            if (!seen.add(r)) throw new IllegalArgumentException("relation '" + r + "' is listed twice");
        }
        return List.copyOf(seen);
    }

    /** The default order over the cases outside the split. */
    static List<String> defaultOrder(Source source, List<String> split, OntologySchema schema) {
        var inSplit = Set.copyOf(split);
        var outside = new ArrayList<Case>();
        source.cases().forEach((id, c) -> {
            if (!inSplit.contains(id)) outside.add(c);
        });
        return relationOrder(outside, schema);
    }

    /** Every schema relation by its gold count in {@code cases}, most first; ties keep schema order. */
    public static List<String> relationOrder(Collection<Case> cases, OntologySchema schema) {
        var counts = new HashMap<String, Integer>();
        for (var c : cases) {
            for (var r : c.relations()) {
                if (!r.noise() && c.holdsOrEnded(r, schema)) counts.merge(r.type(), 1, Integer::sum);
            }
        }
        var order = new ArrayList<>(schema.relations().keySet());
        order.sort((a, b) -> Integer.compare(counts.getOrDefault(b, 0), counts.getOrDefault(a, 0)));
        return List.copyOf(order);
    }

    /**
     * Null when every split id still hashes as frozen and the sequences fingerprint is unchanged; else the refusal
     * naming the first id that differs, or {@code sequences}.
     */
    public @Nullable String verify(Source source) {
        if (!source.set().equals(set)) return "split '%s' is over the %s set, not %s".formatted(split, set, source.set());
        var noun = set.equals(HELDOUT) ? "memory" : "case";
        for (var id : ids) {
            var item = source.items().get(id);
            if (item == null) return "%s %s of split '%s' is no longer in the set".formatted(noun, id, split);
            if (!Fingerprints.hex12(item).equals(hashes.get(id))) {
                return "%s %s changed since split '%s' was frozen".formatted(noun, id, split);
            }
        }
        if (!source.sequences().equals(sequences)) {
            return "sequences changed since split '%s' was frozen (%s, now %s)".formatted(split, sequences,
                    source.sequences());
        }
        return null;
    }

    /** The split's cases from {@code source}, in split order. */
    public List<Case> casesFrom(Source source) {
        return ids.stream().map(id -> {
            var c = source.cases().get(id);
            if (c == null) throw new IllegalArgumentException("split id " + id + " has no labelled case");
            return c;
        }).toList();
    }

    public JsonObject toJson() {
        var o = new JsonObject();
        o.addProperty("split", split);
        o.addProperty("set", set);
        o.addProperty("seed", seed);
        o.add("ids", GSON.toJsonTree(ids));
        var h = new JsonObject();
        hashes.forEach(h::addProperty);
        o.add("hashes", h);
        o.addProperty("cases", cases);
        o.addProperty("sequences", sequences);
        o.addProperty("guide", guide);
        o.addProperty("schema", schema);
        o.addProperty("startingThreshold", startingThreshold);
        o.add("relationOrder", GSON.toJsonTree(relationOrder));
        return o;
    }

    /**
     * The manifest of split {@code name} under {@code root}.
     *
     * @throws IllegalArgumentException when there is none or it does not parse
     */
    public static CertificationSplit load(Path root, String name) throws IOException {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("no split named '" + name + "'");
        var file = path(root, name);
        if (!Files.exists(file)) throw new IllegalArgumentException("no split named '" + name + "'");
        return parse(Files.readString(file), name);
    }

    static CertificationSplit parse(String json, String name) {
        var where = "split " + name;
        JsonObject o;
        try {
            var root = JsonParser.parseString(json);
            if (!root.isJsonObject()) throw new IllegalArgumentException(where + ": the manifest must be an object");
            o = root.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(where + ": not valid JSON: " + e.getMessage(), e);
        }
        GraphCases.onlyKeys(o, KEYS, where);
        var hashes = new LinkedHashMap<String, String>();
        var h = o.get("hashes");
        if (h == null || !h.isJsonObject()) throw new IllegalArgumentException(where + ": 'hashes' must be an object");
        h.getAsJsonObject().keySet().forEach(k -> hashes.put(k, GraphCases.text(h.getAsJsonObject(), k, where)));
        return new CertificationSplit(GraphCases.text(o, "split", where), GraphCases.text(o, "set", where),
                number(o, "seed", where).longValue(), GraphCases.strings(o, "ids", where), hashes,
                GraphCases.text(o, "cases", where), GraphCases.text(o, "sequences", where),
                GraphCases.text(o, "guide", where), GraphCases.text(o, "schema", where),
                number(o, "startingThreshold", where).doubleValue(), GraphCases.strings(o, "relationOrder", where));
    }

    private static Number number(JsonObject o, String key, String where) {
        var e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(where + ": '" + key + "' must be a number");
        }
        return e.getAsNumber();
    }

    /** A development run's cases: those no frozen split holds, and how many were left out. */
    public record Outside(List<Case> kept, int excluded) {
        public Outside {
            kept = List.copyOf(kept);
        }
    }

    /** {@code cases} without the ids any split frozen over {@code set} under {@code root} holds. */
    public static Outside outside(Path root, String set, List<Case> cases) throws IOException {
        var frozen = frozenIds(root, set);
        var kept = cases.stream().filter(c -> !frozen.contains(c.id())).toList();
        return new Outside(kept, cases.size() - kept.size());
    }

    /** The ids of every split frozen over {@code set} under {@code root}. */
    public static Set<String> frozenIds(Path root, String set) throws IOException {
        var dir = root.resolve(DIR);
        var out = new TreeSet<String>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (var file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                var name = file.getFileName().toString();
                var split = parse(Files.readString(file), name.substring(0, name.length() - ".json".length()));
                if (split.set().equals(set)) out.addAll(split.ids());
            }
        }
        return out;
    }
}
