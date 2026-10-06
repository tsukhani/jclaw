package services.grapheval;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;
import services.grapheval.Certifier.ClassWalk;
import services.grapheval.Configuration.ClassSetting;
import services.grapheval.SequenceScorer.Timeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One model's certificate (JCLAW-1368): what certified, at which thresholds, on which split, stamped with the schema,
 * extraction and guide fingerprints and the model's Ollama digest. Written as canonical JSON, one file per model, at
 * {@code <root>/certificates/<model>.json}; {@code id} is {@code cert@} over the canonical JSON without it.
 */
public record CertificateDocument(JsonObject json) {

    public static final String DIR = "certificates";
    private static final Set<String> ROOT_KEYS = Set.of("id", "model", "digest", "status", "schema", "extraction",
            "split", "cases", "sequences", "guide", "startingThreshold", "terms", "relations", "classes", "pooled",
            "timeline", "recall", "resolution");
    private static final String RESOLUTION = "resolution";
    private static final String SHORTLIST = "shortlist";
    private static final Set<String> RESOLUTION_KEYS = Set.of(SHORTLIST);
    private static final Set<String> SHORTLIST_KEYS = Set.of("threshold", "n", "k", "bound");
    private static final Set<String> GATE_KEYS = Set.of("state", "threshold", "n", "k", "bound", "recallLower");
    private static final Set<String> CLASS_KEYS = Set.of("state", "threshold", "n", "k", "bound");
    private static final Set<String> POOLED_KEYS = Set.of("n", "k", "bound");
    private static final Set<String> TIMELINE_KEYS = Set.of("probes", "definite", "wrong", "bound", "result");
    private static final Set<String> RECALL_KEYS = Set.of("floor");

    public CertificateDocument {
        json = json.deepCopy();
    }

    /**
     * The certificate of a certified sequencing; the sequences' lineage walk joins the qualifier classes, and
     * {@code guide} is the guide version its verdicts were judged under.
     */
    public static CertificateDocument of(String model, String digest, String status, CertificationSplit split,
                                         String guide, String schema, String extraction, Certifier.Sequencing sequencing,
                                         ClassWalk lineage, Timeline timeline, String timelineResult,
                                         double recallFloor) {
        return of(model, digest, status, split, guide, schema, extraction, sequencing, lineage, timeline,
                timelineResult, recallFloor, null);
    }

    /**
     * {@link #of(String, String, String, CertificationSplit, String, String, String, Certifier.Sequencing, ClassWalk,
     * Timeline, String, double)} with the optional {@code resolution} section, written only when the shortlist walk
     * reached a threshold, from its step there.
     */
    public static CertificateDocument of(String model, String digest, String status, CertificationSplit split,
                                         String guide, String schema, String extraction, Certifier.Sequencing sequencing,
                                         ClassWalk lineage, Timeline timeline, String timelineResult,
                                         double recallFloor, ResolutionCalibration.@Nullable Walk shortlist) {
        var o = new JsonObject();
        o.addProperty("model", model);
        o.addProperty("digest", digest);
        o.addProperty("status", status);
        o.addProperty("schema", schema);
        o.addProperty("extraction", extraction);
        o.addProperty("split", split.split());
        o.addProperty("cases", split.cases());
        o.addProperty("sequences", split.sequences());
        o.addProperty("guide", guide);
        o.addProperty("startingThreshold", split.startingThreshold());
        o.add("terms", gate(sequencing.terms()));
        var relations = new JsonObject();
        sequencing.relations().forEach(g -> relations.add(g.name(), gate(g)));
        o.add("relations", relations);
        var classes = new JsonObject();
        for (var c : sequencing.classes()) {
            classes.add(c.name(), klass(c.state(), c.threshold(), c.n(), c.k(), c.bound()));
        }
        classes.add(lineage.name(), klass(lineage.state(), lineage.threshold(), lineage.n(), lineage.k(),
                lineage.bound()));
        o.add("classes", classes);
        var pooled = new JsonObject();
        for (var p : new Certifier.Pooled[] {sequencing.written(), sequencing.trap()}) {
            if (p == null) continue;
            var po = new JsonObject();
            po.addProperty("n", p.n());
            po.addProperty("k", p.k());
            po.addProperty("bound", p.bound());
            pooled.add(p.name(), po);
        }
        o.add("pooled", pooled);
        var t = new JsonObject();
        t.addProperty("probes", timeline.probes());
        t.addProperty("definite", timeline.definiteGold());
        t.addProperty("wrong", timeline.definiteWrong());
        t.addProperty("bound", timeline.bound());
        t.addProperty("result", timelineResult);
        o.add("timeline", t);
        var recall = new JsonObject();
        recall.addProperty("floor", recallFloor);
        o.add("recall", recall);
        var threshold = shortlist == null ? null : shortlist.threshold();
        var step = shortlist == null || threshold == null ? null : shortlist.at(threshold);
        if (step != null) {
            var sl = new JsonObject();
            sl.addProperty("threshold", step.t());
            sl.addProperty("n", step.attachments());
            sl.addProperty("k", step.falseMerges());
            sl.addProperty("bound", step.bound());
            var resolution = new JsonObject();
            resolution.add(SHORTLIST, sl);
            o.add(RESOLUTION, resolution);
        }
        var canonical = Fingerprints.canonical(o).getAsJsonObject();
        var withId = new JsonObject();
        withId.addProperty("id", Fingerprints.hex12("cert", canonical));
        canonical.entrySet().forEach(e -> withId.add(e.getKey(), e.getValue()));
        return new CertificateDocument(Fingerprints.canonical(withId).getAsJsonObject());
    }

    private static JsonObject gate(Certifier.Gate g) {
        var o = new JsonObject();
        o.addProperty("state", g.state());
        o.add("threshold", g.threshold() == null ? JsonNull.INSTANCE : new JsonPrimitive(g.threshold()));
        o.addProperty("n", g.n());
        o.addProperty("k", g.k());
        o.addProperty("bound", g.bound());
        o.add("recallLower", g.recallLower() == null ? JsonNull.INSTANCE
                : new JsonPrimitive(g.recallLower()));
        return o;
    }

    private static JsonObject klass(String state, @Nullable Double threshold, int n, int k, double bound) {
        var o = new JsonObject();
        o.addProperty("state", state);
        if (state.equals(Certifier.DISABLED)) return o;
        o.addProperty("threshold", threshold);
        o.addProperty("n", n);
        o.addProperty("k", k);
        o.addProperty("bound", bound);
        return o;
    }

    public String id() {
        return json.get("id").getAsString();
    }

    public String model() {
        return json.get("model").getAsString();
    }

    public String digest() {
        return json.get("digest").getAsString();
    }

    public String status() {
        return json.get("status").getAsString();
    }

    public String schema() {
        return json.get("schema").getAsString();
    }

    public String extraction() {
        return json.get("extraction").getAsString();
    }

    /** The shortlist threshold of the optional {@code resolution} section; null without one. */
    public @Nullable Double shortlistThreshold() {
        var resolution = json.getAsJsonObject(RESOLUTION);
        var shortlist = resolution == null ? null : resolution.getAsJsonObject(SHORTLIST);
        var threshold = shortlist == null ? null : shortlist.get("threshold");
        return threshold == null || threshold.isJsonNull() ? null : threshold.getAsDouble();
    }

    /** The canonical JSON, compact, as written. */
    public String text() {
        return Fingerprints.canonical(json).toString();
    }

    /**
     * What the certificate enables: Terms at their threshold, each {@code on} relation at its own, each class at its
     * state and threshold (a disabled class null). Lineage is not configurable and stays out.
     */
    public Configuration toConfiguration() {
        var relations = new TreeMap<String, Double>();
        for (var e : json.getAsJsonObject("relations").entrySet()) {
            var g = e.getValue().getAsJsonObject();
            if (g.get("state").getAsString().equals(Certifier.ON)) relations.put(e.getKey(), g.get("threshold").getAsDouble());
        }
        var classes = new TreeMap<String, ClassSetting>();
        for (var e : json.getAsJsonObject("classes").entrySet()) {
            if (!Certifier.V2_CLASSES.contains(e.getKey())) continue;
            var c = e.getValue().getAsJsonObject();
            var state = c.get("state").getAsString();
            classes.put(e.getKey(), new ClassSetting(state,
                    state.equals(Certifier.DISABLED) ? null : c.get("threshold").getAsDouble()));
        }
        return new Configuration(json.getAsJsonObject("terms").get("threshold").getAsDouble(), relations, classes);
    }

    public static Path path(Path root, String model) {
        return root.resolve(DIR).resolve(StoredRun.fileName(model) + ".json");
    }

    public void write(Path root) throws IOException {
        var file = path(root, model());
        Files.createDirectories(file.getParent());
        Files.writeString(file, text(), StandardCharsets.UTF_8);
    }

    /** The certificate, or why it was refused; exactly one is non-null. */
    public record Read(@Nullable CertificateDocument certificate, @Nullable String reason) {}

    /**
     * {@code model}'s certificate under {@code root}, returned only when its schema and extraction stamps and its
     * digest match the running ones and it holds no unknown key; otherwise the reason names which differs.
     */
    public static Read read(Path root, String model, String schemaFingerprint, String extractionFingerprint,
                            String runningDigest) throws IOException {
        var file = path(root, model);
        if (!Files.exists(file)) return new Read(null, "no certificate for " + model);
        CertificateDocument doc;
        try {
            doc = parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return new Read(null, e.getMessage());
        }
        if (!doc.schema().equals(schemaFingerprint)) {
            return new Read(null, "schema stamp %s differs from running %s".formatted(doc.schema(), schemaFingerprint));
        }
        if (!doc.extraction().equals(extractionFingerprint)) {
            return new Read(null, "extraction stamp %s differs from running %s".formatted(doc.extraction(),
                    extractionFingerprint));
        }
        if (!doc.digest().equals(runningDigest)) {
            return new Read(null, "digest %s differs from running %s".formatted(doc.digest(), runningDigest));
        }
        return new Read(doc, null);
    }

    /**
     * A certificate from its JSON.
     *
     * @throws IllegalArgumentException naming an unknown key, a missing one, or an id that is not its content's
     */
    public static CertificateDocument parse(String text) {
        JsonObject o;
        try {
            var root = JsonParser.parseString(text);
            if (!root.isJsonObject()) throw new IllegalArgumentException("certificate: must be an object");
            o = root.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("certificate: not valid JSON: " + e.getMessage(), e);
        }
        GraphCases.onlyKeys(o, ROOT_KEYS, "certificate");
        for (var key : ROOT_KEYS) {
            if (!key.equals(RESOLUTION) && !o.has(key)) throw new IllegalArgumentException("certificate: '" + key + "' is required");
        }
        GraphCases.onlyKeys(object(o, "terms"), GATE_KEYS, "certificate terms");
        each(object(o, "relations"), GATE_KEYS, "certificate relation");
        each(object(o, "classes"), CLASS_KEYS, "certificate class");
        each(object(o, "pooled"), POOLED_KEYS, "certificate pooled");
        GraphCases.onlyKeys(object(o, "timeline"), TIMELINE_KEYS, "certificate timeline");
        GraphCases.onlyKeys(object(o, "recall"), RECALL_KEYS, "certificate recall");
        if (o.has(RESOLUTION)) {
            var resolution = object(o, RESOLUTION);
            GraphCases.onlyKeys(resolution, RESOLUTION_KEYS, "certificate resolution");
            if (resolution.has(SHORTLIST)) {
                GraphCases.onlyKeys(object(resolution, SHORTLIST), SHORTLIST_KEYS, "certificate resolution shortlist");
            }
        }
        var content = o.deepCopy();
        content.remove("id");
        var id = Fingerprints.hex12("cert", content);
        if (!id.equals(o.get("id").getAsString())) {
            throw new IllegalArgumentException("certificate: id %s is not its content's %s".formatted(o.get("id"), id));
        }
        return new CertificateDocument(o);
    }

    private static JsonObject object(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("certificate: '" + key + "' must be an object");
        return e.getAsJsonObject();
    }

    private static void each(JsonObject o, Set<String> keys, String where) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getValue().isJsonObject()) throw new IllegalArgumentException(where + " '" + e.getKey() + "' must be an object");
            GraphCases.onlyKeys(e.getValue().getAsJsonObject(), keys, where + " '" + e.getKey() + "'");
        }
    }
}
