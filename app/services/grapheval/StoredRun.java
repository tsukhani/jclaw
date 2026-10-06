package services.grapheval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import memory.TemporalExpressions.DateSpan;
import memory.TemporalExpressions.Kind;
import memory.TemporalExpressions.NegationCue;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import services.grapheval.CandidateGenerator.Candidate;
import services.grapheval.CandidateGenerator.PreferenceFrame;
import services.grapheval.Certifier.SpotCheck;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.ExtractionPipeline.Predecessor;
import services.grapheval.ExtractionPipeline.Request;
import services.grapheval.SequenceHarness.SequenceSpotCheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static utils.GsonHolder.GSON;

/**
 * A certification run's decisions as stored (JCLAW-1368), so a re-score reads exactly what the run scored and calls no
 * model: every run's case and chain decisions, the scored gold-fed stages, both spot-checks, the agreed sample's seed
 * and share, the model's digest and the fingerprints it ran under. At {@code <root>/runs/<split>/<model>.json}.
 */
public record StoredRun(String split, String model, String digest, String schema, String extraction,
                        long agreedSeed, double agreedShare, List<Pass> passes, @Nullable SpotCheck spotCheck,
                        @Nullable SequenceSpotCheck sequenceSpotCheck, int memoriesChanged) {

    public static final String DIR = "runs";

    public StoredRun {
        passes = List.copyOf(passes);
    }

    /** One run: each case's decisions in split order, each chain's in chain order, and its gold-fed stage scores. */
    public record Pass(List<CaseRun> cases, List<List<CaseRun>> chains, JsonElement stages) {
        public Pass {
            cases = List.copyOf(cases);
            chains = chains.stream().map(List::copyOf).toList();
        }
    }

    /** Every failed decision of every run: the end-to-end cases, the chains and the gold-fed stages. */
    public int failedDecisions() {
        int n = 0;
        for (var p : passes) {
            if (p.stages().isJsonObject() && p.stages().getAsJsonObject().has("failures")) {
                n += p.stages().getAsJsonObject().get("failures").getAsInt();
            }
            for (var r : p.cases()) n += failed(r);
            for (var chain : p.chains()) {
                for (var r : chain) n += failed(r);
            }
        }
        return n;
    }

    private static int failed(CaseRun r) {
        return (int) r.decisions().stream().filter(Decision::failed).count();
    }

    /** {@code model} with every character a file name cannot safely hold replaced by {@code _}. */
    public static String fileName(String model) {
        return model.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static Path path(Path root, String split, String model) {
        return root.resolve(DIR).resolve(split).resolve(fileName(model) + ".json");
    }

    public static void write(Path root, StoredRun run) throws IOException {
        var file = path(root, run.split(), run.model());
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(run.toJson()));
    }

    /**
     * The stored run of {@code model} on {@code split}, its case runs carrying {@code schema}.
     *
     * @throws IllegalArgumentException when there is none
     */
    public static StoredRun read(Path root, String split, String model, OntologySchema schema) throws IOException {
        var file = path(root, split, model);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("no stored run of %s on split '%s'".formatted(model, split));
        }
        return fromJson(JsonParser.parseString(Files.readString(file)).getAsJsonObject(), schema);
    }

    public JsonObject toJson() {
        var o = new JsonObject();
        o.addProperty("split", split);
        o.addProperty("model", model);
        o.addProperty("digest", digest);
        o.addProperty("schema", schema);
        o.addProperty("extraction", extraction);
        o.addProperty("agreedSeed", agreedSeed);
        o.addProperty("agreedShare", agreedShare);
        var ps = new JsonArray();
        for (var p : passes) {
            var po = new JsonObject();
            var cases = new JsonArray();
            p.cases().forEach(r -> cases.add(toJson(r)));
            po.add("cases", cases);
            var chains = new JsonArray();
            for (var chain : p.chains()) {
                var runs = new JsonArray();
                chain.forEach(r -> runs.add(toJson(r)));
                chains.add(runs);
            }
            po.add("chains", chains);
            po.add("stages", p.stages());
            ps.add(po);
        }
        o.add("passes", ps);
        o.add("spotCheck", spotCheck == null ? JsonNull.INSTANCE : GSON.toJsonTree(spotCheck));
        o.add("sequenceSpotCheck", sequenceSpotCheck == null ? JsonNull.INSTANCE : GSON.toJsonTree(sequenceSpotCheck));
        o.addProperty("memoriesChanged", memoriesChanged);
        return o;
    }

    public static StoredRun fromJson(JsonObject o, OntologySchema schema) {
        var passes = new ArrayList<Pass>();
        for (var e : o.getAsJsonArray("passes")) {
            var po = e.getAsJsonObject();
            var cases = new ArrayList<CaseRun>();
            po.getAsJsonArray("cases").forEach(r -> cases.add(caseRun(r.getAsJsonObject(), schema)));
            var chains = new ArrayList<List<CaseRun>>();
            for (var chain : po.getAsJsonArray("chains")) {
                var runs = new ArrayList<CaseRun>();
                chain.getAsJsonArray().forEach(r -> runs.add(caseRun(r.getAsJsonObject(), schema)));
                chains.add(runs);
            }
            passes.add(new Pass(cases, chains, po.get("stages")));
        }
        var spot = o.get("spotCheck");
        var seqSpot = o.get("sequenceSpotCheck");
        return new StoredRun(o.get("split").getAsString(), o.get("model").getAsString(), o.get("digest").getAsString(),
                o.get("schema").getAsString(), o.get("extraction").getAsString(), o.get("agreedSeed").getAsLong(),
                o.get("agreedShare").getAsDouble(), passes,
                spot == null || spot.isJsonNull() ? null : GSON.fromJson(spot, SpotCheck.class),
                seqSpot == null || seqSpot.isJsonNull() ? null : GSON.fromJson(seqSpot, SequenceSpotCheck.class),
                o.get("memoriesChanged").getAsInt());
    }

    /** Every field of {@code run} a scorer reads; the schema is re-attached on reading. */
    public static JsonObject toJson(CaseRun run) {
        var o = new JsonObject();
        o.addProperty("caseId", run.caseId());
        var candidates = new JsonArray();
        for (var c : run.candidates()) {
            var co = new JsonObject();
            co.addProperty("span", c.span());
            co.addProperty("operator", c.operator());
            co.addProperty("implicit", c.implicit());
            co.addProperty("start", c.start());
            co.addProperty("end", c.end());
            var frame = c.frame();
            if (frame != null) {
                var fo = new JsonObject();
                fo.addProperty("valence", frame.valence().name());
                fo.addProperty("subject", frame.subject());
                co.add("frame", fo);
            }
            candidates.add(co);
        }
        o.add("candidates", candidates);
        o.addProperty("prunedPairs", run.prunedPairs());
        var decisions = new JsonArray();
        for (var d : run.decisions()) {
            var dobj = new JsonObject();
            dobj.addProperty("stage", d.stage());
            dobj.addProperty("subject", d.subject());
            dobj.addProperty("from", d.from());
            dobj.addProperty("to", d.to());
            dobj.addProperty("choice", d.choice());
            dobj.addProperty("confidence", d.confidence());
            dobj.addProperty("operator", d.operator());
            dobj.addProperty("failure", d.failure());
            dobj.addProperty("floor", d.floor());
            decisions.add(dobj);
        }
        o.add("decisions", decisions);
        o.addProperty("text", run.text());
        o.addProperty("anchor", run.anchor().toString());
        var dates = new JsonArray();
        for (var d : run.dates()) {
            var dobj = new JsonObject();
            dobj.addProperty("start", d.start());
            dobj.addProperty("end", d.end());
            dobj.addProperty("span", d.span());
            dobj.addProperty("phrase", d.phrase());
            dobj.addProperty("kind", d.kind().name());
            dobj.addProperty("relative", d.relative());
            var readings = new JsonArray();
            d.readings().forEach(r -> readings.add(r.toString()));
            dobj.add("readings", readings);
            dobj.addProperty("duration", d.duration());
            dates.add(dobj);
        }
        o.add("dates", dates);
        var cues = new JsonArray();
        for (var c : run.cues()) {
            var co = new JsonObject();
            co.addProperty("start", c.start());
            co.addProperty("end", c.end());
            co.addProperty("perfectNever", c.perfectNever());
            cues.add(co);
        }
        o.add("cues", cues);
        var predecessors = new JsonArray();
        for (var p : run.predecessors()) {
            var po = new JsonObject();
            po.addProperty("id", p.id());
            po.addProperty("text", p.text());
            po.addProperty("anchor", p.anchor() == null ? null : p.anchor().toString());
            predecessors.add(po);
        }
        o.add("predecessors", predecessors);
        var questions = new JsonObject();
        run.questionsByStage().forEach(questions::addProperty);
        o.add("questionsByStage", questions);
        var sent = new JsonArray();
        run.sent().forEach(r -> sent.add(r.name()));
        o.add("sent", sent);
        o.addProperty("memoryId", run.memoryId());
        return o;
    }

    public static CaseRun caseRun(JsonObject o, OntologySchema schema) {
        var candidates = new ArrayList<Candidate>();
        for (var e : o.getAsJsonArray("candidates")) {
            var c = e.getAsJsonObject();
            PreferenceFrame frame = null;
            if (c.has("frame")) {
                var f = c.getAsJsonObject("frame");
                frame = new PreferenceFrame(OntologyRecord.Valence.valueOf(f.get("valence").getAsString()),
                        nullable(f, "subject"));
            }
            candidates.add(new Candidate(c.get("span").getAsString(), c.get("operator").getAsBoolean(),
                    c.get("implicit").getAsBoolean(), c.get("start").getAsInt(), c.get("end").getAsInt(), frame));
        }
        var decisions = new ArrayList<Decision>();
        for (var e : o.getAsJsonArray("decisions")) {
            var d = e.getAsJsonObject();
            decisions.add(new Decision(d.get("stage").getAsString(), d.get("subject").getAsString(), nullable(d, "from"),
                    nullable(d, "to"), nullable(d, "choice"), d.get("confidence").getAsDouble(),
                    d.get("operator").getAsBoolean(), nullable(d, "failure"), d.get("floor").getAsDouble()));
        }
        var dates = new ArrayList<DateSpan>();
        for (var e : o.getAsJsonArray("dates")) {
            var d = e.getAsJsonObject();
            var readings = new ArrayList<EdtfInterval>();
            d.getAsJsonArray("readings").forEach(r -> readings.add(EdtfInterval.parse(r.getAsString())));
            dates.add(new DateSpan(d.get("start").getAsInt(), d.get("end").getAsInt(), d.get("span").getAsString(),
                    d.get("phrase").getAsString(), Kind.valueOf(d.get("kind").getAsString()),
                    d.get("relative").getAsBoolean(), readings, nullable(d, "duration")));
        }
        var cues = new ArrayList<NegationCue>();
        for (var e : o.getAsJsonArray("cues")) {
            var c = e.getAsJsonObject();
            cues.add(new NegationCue(c.get("start").getAsInt(), c.get("end").getAsInt(),
                    c.get("perfectNever").getAsBoolean()));
        }
        var predecessors = new ArrayList<Predecessor>();
        for (var e : o.getAsJsonArray("predecessors")) {
            var p = e.getAsJsonObject();
            var anchor = nullable(p, "anchor");
            predecessors.add(new Predecessor(p.get("id").getAsString(), p.get("text").getAsString(),
                    anchor == null ? null : LocalDate.parse(anchor)));
        }
        var questions = new LinkedHashMap<String, Integer>();
        o.getAsJsonObject("questionsByStage").entrySet().forEach(e -> questions.put(e.getKey(), e.getValue().getAsInt()));
        Set<Request> sent = EnumSet.noneOf(Request.class);
        o.getAsJsonArray("sent").forEach(e -> sent.add(Request.valueOf(e.getAsString())));
        var memoryId = o.get("memoryId");
        return new CaseRun(o.get("caseId").getAsString(), candidates, o.get("prunedPairs").getAsInt(), decisions,
                o.get("text").getAsString(), LocalDate.parse(o.get("anchor").getAsString()), dates, cues, predecessors,
                questions, sent, schema, memoryId == null || memoryId.isJsonNull() ? null : memoryId.getAsLong());
    }

    private static @Nullable String nullable(JsonObject o, String key) {
        var e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
