package controllers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import memory.ontology.OntologySchema;
import models.Agent;
import org.jspecify.annotations.Nullable;
import play.Play;
import play.db.jpa.NoTransaction;
import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Http;
import services.EventLogger;
import services.Tx;
import services.WorkspaceFiles;
import services.decision.JevApi;
import services.decision.OllamaDecision;
import services.grapheval.Adjudications;
import services.grapheval.Agreement;
import services.grapheval.CertificationSplit;
import services.grapheval.Certifier;
import services.grapheval.CompetencyQuestions;
import services.grapheval.Configuration;
import services.grapheval.CoverageReport;
import services.grapheval.EvalProgress;
import services.grapheval.ExtractionPipeline.Decider;
import services.grapheval.GraphCases;
import services.grapheval.GraphEvalHarness;
import services.grapheval.GraphEvalHarness.DecisionModel;
import services.grapheval.HeldOut;
import services.grapheval.SequenceHarness;
import services.grapheval.Sequences;
import utils.ApiResponses;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Certifies local Ollama decision models for graph extraction over {@code evals/graph/cases.json}, or measures them
 * over the operator's held-out set (JCLAW-1344, JCLAW-1356). An endpoint, like {@link ApiScrapeTestController},
 * because the decision provider it measures exists only inside a booted app.
 */
public class ApiGraphEvalController extends Controller {

    private static final int MAX_CONCURRENCY = 4;
    private static final int DEFAULT_CONCURRENCY = 1;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_RUNS = 3;
    private static final int MAX_SAMPLE = 1000;
    private static final long DEFAULT_SEED = 1356;
    private static final String BLIND_SHEET = "blind-sheet.json";
    private static final long HEARTBEAT_SECONDS = 30;
    private static final String INVALID_CASE_SET = "invalid case set: ";

    @Before
    static void requireLoadtestAuth() {
        LoadtestAuthCheck.checkLoadtestAuth();
    }

    /**
     * {@code POST /api/graph/eval} with {@code {agent, decisionModels?, set?: "cases"|"heldout"|"sequences", split?,
     * runs?, recallFloor?, concurrency?, timeoutSeconds?, pairFilter?, configuration?, agreedShare?, agreedSeed?}};
     * {@code sequences} reads no {@code agent} and takes an optional {@code configuration} (JCLAW-1367). A
     * {@code split} names a frozen certification split, whose set it fixes (JCLAW-1368); without one, a run leaves out
     * every frozen split's ids. Model calls run outside any transaction, so each DB step opens its own. A refused
     * request is a 400; an accepted one is {@linkplain #stream streamed}.
     */
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "measurement harness -- loopback plus X-Loadtest-Auth; spends model calls, and only the cases set writes then deletes an agent's memories")
    public static void run() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) throw invalid("A JSON body is required");
        if (body.has("proposers")) throw invalid("proposers are gone: candidates come from fixed rules (JCLAW-1356)");
        if (body.has("threshold")) throw invalid("threshold is gone: the certification walk chooses it");
        CertificationSplit split = null;
        if (body.has("split")) {
            if (body.has("set")) throw invalid("set follows from the split: send split without set");
            split = loadSplit(string(body, "split"));
        }
        var set = split != null ? split.set() : body.has("set") ? string(body, "set") : "cases";
        if (!set.equals("cases") && !set.equals("heldout") && !set.equals("sequences")) {
            throw invalid("set must be 'cases', 'heldout' or 'sequences'");
        }
        if (body.has("configuration") && !set.equals("sequences")) {
            throw invalid("configuration applies only to the sequences set");
        }
        if (set.equals("sequences") && body.has("recallFloor")) {
            throw invalid("recallFloor does not apply to the sequences set");
        }
        if (set.equals("sequences") && body.has("pairFilter")) {
            throw invalid("pairFilter does not apply to the sequences set");
        }
        if (split != null && body.has("pairFilter")) throw invalid("pairFilter does not apply to a certification run");
        if (set.equals("sequences") && (body.has("agreedShare") || body.has("agreedSeed"))) {
            throw invalid("agreedShare and agreedSeed do not apply to the sequences set");
        }
        int runs = readInt(body, "runs", GraphEvalHarness.DEFAULT_RUNS);
        if (runs < 1 || runs > MAX_RUNS) throw invalid("runs must be between 1 and " + MAX_RUNS);
        if (split != null && runs > 2) throw invalid("a certification run takes runs 1 or 2");
        double recallFloor = Certifier.DEFAULT_RECALL_FLOOR;
        if (body.has("recallFloor")) recallFloor = share(body, "recallFloor", true);
        double floor = recallFloor;
        double agreedShare = body.has("agreedShare") ? share(body, "agreedShare", false) : Adjudications.DEFAULT_SHARE;
        long agreedSeed = body.has("agreedSeed") ? readLong(body, "agreedSeed")
                : split != null ? split.seed() : GraphEvalHarness.DEFAULT_AGREED_SEED;
        boolean pairFilter = false;
        if (body.has("pairFilter")) {
            var raw = body.get("pairFilter");
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean()) {
                throw invalid("pairFilter must be a boolean");
            }
            pairFilter = raw.getAsBoolean();
        }
        boolean filter = pairFilter;
        int timeoutSeconds = Math.clamp(readInt(body, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS), 1, MAX_TIMEOUT_SECONDS);
        int concurrency = Math.clamp(readInt(body, "concurrency", DEFAULT_CONCURRENCY), 1, MAX_CONCURRENCY);
        var modelNames = body.has("decisionModels") ? strings(body, "decisionModels") : OllamaDecision.selectedModels();
        if (modelNames.isEmpty()) throw invalid("decisionModels must not be empty");
        if (modelNames.contains(JevApi.MODEL)) {
            throw invalid(JevApi.MODEL + " is a hosted model; the graph certifier measures local Ollama models only");
        }
        long timeoutMs = timeoutSeconds * 1000L;
        var baseUrl = OllamaDecision.baseUrl();
        var models = modelNames.stream().distinct()
                .map(m -> new DecisionModel(m, Decider.ollama(baseUrl, m, timeoutMs))).toList();
        var schema = schema();

        if (split != null) {
            var digests = new LinkedHashMap<String, String>();
            for (var m : models) {
                var digest = OllamaDecision.digest(baseUrl, m.name());
                if (digest == null) {
                    throw invalid("decision model '%s' is not installed on the Ollama server at %s"
                            .formatted(m.name(), baseUrl));
                }
                digests.put(m.name(), digest);
            }
            var inputs = setInputs(set, schema);
            var agentId = set.equals("cases") ? agentId(body) : null;
            var request = new GraphEvalHarness.CertifyRequest(root(), split, inputs.source(), schema, agentId,
                    inputs.ownerName(), inputs.held(), sequences(schema), models, digests, runs, agreedSeed,
                    agreedShare, Adjudications.DEFAULT_CHECK_SHARE, floor, concurrency, inputs.secondLabels(),
                    inputs.secondLabelsReason(), inputs.verdicts());
            try {
                GraphEvalHarness.admit(request);
            } catch (IllegalArgumentException e) {
                throw invalid(String.valueOf(e.getMessage()));
            }
            stream(progress -> GraphEvalHarness.certify(request, progress), set.equals("heldout"));
            return;
        }

        if (set.equals("heldout")) {
            var file = HeldOut.defaultPath();
            if (!Files.exists(file)) throw invalid("no held-out file; run grapheval heldout-sample first");
            HeldOut.Loaded loaded;
            String ownerName;
            try {
                loaded = HeldOut.load(file, schema);
                var agentName = HeldOut.agentName(loaded);
                ownerName = agentName == null ? null : WorkspaceFiles.ownerName(agentName);
            } catch (IOException | RuntimeException e) {
                throw invalid("invalid held-out set: " + e.getMessage());
            }
            var frozen = frozenIds("heldout");
            var kept = loaded.cases().stream().filter(h -> !frozen.contains(String.valueOf(h.memoryId()))).toList();
            var unsplit = new HeldOut.Loaded(kept, loaded.unlabelled());
            var options = new GraphEvalHarness.DevOptions(guide(), agreedSeed, agreedShare,
                    Adjudications.DEFAULT_CHECK_SHARE, loaded.cases().size() - kept.size());
            var verdicts = verdicts(root().resolve(Adjudications.HELDOUT_FILE));
            var questions = questions(schema);
            stream(progress -> GraphEvalHarness.runHeldOut(unsplit, ownerName, schema, models, runs, floor,
                    concurrency, progress, filter, verdicts, options, questions), true);
            return;
        }

        if (set.equals("sequences")) {
            var sequences = sequences(schema);
            boolean defaultConfiguration = !body.has("configuration");
            Configuration configuration;
            if (defaultConfiguration) {
                configuration = Configuration.defaultFor(schema);
            } else {
                var raw = body.get("configuration");
                if (!raw.isJsonObject()) throw invalid("configuration must be an object");
                try {
                    configuration = Configuration.parse(raw.getAsJsonObject(), schema);
                } catch (IllegalArgumentException e) {
                    throw invalid(String.valueOf(e.getMessage()));
                }
            }
            stream(progress -> SequenceHarness.run(sequences, schema, models, runs, concurrency, configuration,
                    defaultConfiguration, progress), false);
            return;
        }

        var agentId = agentId(body);
        var inputs = setInputs("cases", schema);
        CertificationSplit.Outside outside;
        try {
            outside = CertificationSplit.outside(root(), "cases", List.copyOf(inputs.source().cases().values()));
        } catch (IOException | IllegalArgumentException e) {
            throw invalid("could not read the frozen splits: " + e.getMessage());
        }
        var options = new GraphEvalHarness.DevOptions(inputs.source().guide(), agreedSeed, agreedShare,
                Adjudications.DEFAULT_CHECK_SHARE, outside.excluded());
        stream(progress -> GraphEvalHarness.run(agentId, outside.kept(), inputs.ownerName(), schema, models, runs,
                floor, concurrency, inputs.secondLabels(), inputs.verdicts(), progress, filter,
                inputs.secondLabelsReason(), options), false);
    }

    /** What a set brings to a certification run or a re-score: its split source, owner, second labels and verdicts. */
    private record SetInputs(CertificationSplit.Source source, @Nullable String ownerName,
                             Map<String, HeldOut.HeldCase> held, List<GraphCases.Case> secondLabels,
                             @Nullable String secondLabelsReason, List<Adjudications.Verdict> verdicts) {}

    private static SetInputs setInputs(String set, OntologySchema schema) {
        var sequencesFingerprint = sequences(schema).fingerprint();
        byte[] guide;
        try {
            guide = Files.readAllBytes(appPath(CertificationSplit.GUIDE_PATH));
        } catch (IOException e) {
            throw invalid("could not read the guide: " + e.getMessage());
        }
        if (set.equals("heldout")) {
            var file = HeldOut.defaultPath();
            if (!Files.exists(file)) throw invalid("no held-out file; run grapheval heldout-sample first");
            try {
                var loaded = HeldOut.load(file, schema);
                var agentName = HeldOut.agentName(loaded);
                var ownerName = agentName == null ? null : WorkspaceFiles.ownerName(agentName);
                var held = new HashMap<String, HeldOut.HeldCase>();
                loaded.cases().forEach(h -> held.put(String.valueOf(h.memoryId()), h));
                var source = CertificationSplit.Source.heldout(Files.readString(file), loaded, sequencesFingerprint,
                        guide, schema);
                return new SetInputs(source, ownerName, held, List.of(), null,
                        verdicts(root().resolve(Adjudications.HELDOUT_FILE)));
            } catch (IOException | RuntimeException e) {
                throw invalid("invalid held-out set: " + e.getMessage());
            }
        }
        String json;
        try {
            json = Files.readString(appPath(GraphCases.DEFAULT_PATH));
        } catch (IOException e) {
            throw invalid(INVALID_CASE_SET + e.getMessage());
        }
        String ownerName;
        CertificationSplit.Source source;
        try {
            ownerName = GraphCases.ownerName(json);
            source = CertificationSplit.Source.cases(json, GraphCases.parse(json, schema), sequencesFingerprint, guide,
                    schema);
        } catch (RuntimeException e) {
            throw invalid(INVALID_CASE_SET + e.getMessage());
        }
        GraphCases.SecondLabels second;
        try {
            second = GraphCases.loadSecondLabels(appPath(GraphCases.SECOND_LABELS_PATH), schema);
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid second labels: " + e.getMessage());
        }
        return new SetInputs(source, ownerName, Map.of(), second.cases(), second.reason(),
                verdicts(appPath(GraphCases.ADJUDICATIONS_PATH)));
    }

    private static List<Adjudications.Verdict> verdicts(Path file) {
        try {
            return Files.exists(file) ? Adjudications.parse(Files.readString(file)) : List.of();
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid adjudications: " + e.getMessage());
        }
    }

    private static Sequences sequences(OntologySchema schema) {
        try {
            return Sequences.load(appPath(Sequences.DEFAULT_PATH), schema);
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid sequence set: " + e.getMessage());
        }
    }

    private static String guide() {
        try {
            return CertificationSplit.guideFingerprint(Files.readAllBytes(appPath(CertificationSplit.GUIDE_PATH)));
        } catch (IOException e) {
            throw invalid("could not read the guide: " + e.getMessage());
        }
    }

    private static CertificationSplit loadSplit(String name) {
        try {
            return CertificationSplit.load(root(), name);
        } catch (IOException | IllegalArgumentException e) {
            throw invalid(String.valueOf(e.getMessage()));
        }
    }

    private static Set<String> frozenIds(String set) {
        try {
            return CertificationSplit.frozenIds(root(), set);
        } catch (IOException | IllegalArgumentException e) {
            throw invalid("could not read the frozen splits: " + e.getMessage());
        }
    }

    /**
     * {@code POST /api/graph/eval/split} with {@code {name, set: "cases"|"heldout", share? | ids?, seed?,
     * startingThreshold?, relationOrder?}}: freezes a certification split under {@code data/graph-eval/splits/}.
     */
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "measurement harness -- loopback plus X-Loadtest-Auth; writes a split manifest under data/graph-eval")
    public static void freezeSplit() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) throw invalid("A JSON body is required");
        var name = JsonBodyReader.requiredOr400(body, "name");
        var set = JsonBodyReader.requiredOr400(body, "set");
        if (!set.equals("cases") && !set.equals("heldout")) throw invalid("set must be 'cases' or 'heldout'");
        if (body.has("share") && body.has("ids")) throw invalid("give share or ids, not both");
        Double share = body.has("share") ? share(body, "share", false) : null;
        List<String> ids = body.has("ids") ? strings(body, "ids") : null;
        long seed = body.has("seed") ? readLong(body, "seed") : DEFAULT_SEED;
        double start = body.has("startingThreshold") ? share(body, "startingThreshold", true)
                : GraphEvalHarness.DEVELOPMENT_START;
        List<String> order = body.has("relationOrder") ? strings(body, "relationOrder") : null;
        var schema = schema();
        var inputs = setInputs(set, schema);
        CertificationSplit split;
        try {
            split = CertificationSplit.freeze(root(), name, inputs.source(), seed, share, ids, start, order, schema);
        } catch (IOException | IllegalArgumentException e) {
            throw invalid(String.valueOf(e.getMessage()));
        }
        var out = split.toJson();
        out.addProperty("path", HeldOut.DIR + "/" + CertificationSplit.DIR + "/" + name + ".json");
        renderJSON(GSON.toJson(out));
    }

    /**
     * {@code POST /api/graph/eval/rescore} with {@code {split, decisionModel, checkShare?}}: scores a stored
     * certification run again under the verdicts on file. Asks no model.
     */
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "measurement harness -- loopback plus X-Loadtest-Auth; rewrites a sheet and a certificate under data/graph-eval")
    public static void rescore() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) throw invalid("A JSON body is required");
        if (body.has("threshold")) throw invalid("threshold is gone: the certification walk chooses it");
        var split = loadSplit(JsonBodyReader.requiredOr400(body, "split"));
        var model = JsonBodyReader.requiredOr400(body, "decisionModel");
        double checkShare = body.has("checkShare") ? share(body, "checkShare", false)
                : Adjudications.DEFAULT_CHECK_SHARE;
        var schema = schema();
        var inputs = setInputs(split.set(), schema);
        GraphEvalHarness.CertificationReport report;
        try {
            report = GraphEvalHarness.rescore(root(), split, inputs.source(), schema, model, sequences(schema),
                    checkShare, inputs.secondLabels(), inputs.secondLabelsReason(),
                    inputs.verdicts());
        } catch (IllegalArgumentException e) {
            throw invalid(String.valueOf(e.getMessage()));
        }
        renderJSON(GSON.toJson(report));
    }

    private static Path root() {
        return HeldOut.root().resolve(HeldOut.DIR);
    }

    /**
     * Streams newline-delimited JSON (JCLAW-1359): progress events as they happen, then the report as the last line.
     * Once a line is out the status is fixed, so a failure arrives as an {@code error} event; a held-out one names
     * only the exception's class, since its message could carry a memory's text.
     */
    private static void stream(Function<EvalProgress, Object> measure, boolean heldOut) {
        var target = response;
        target.contentType = "application/x-ndjson";
        var progress = new EvalProgress(event -> writeLine(target, GSON.toJson(event)));
        Object report = null;
        String failure = null;
        // close() runs before the catch and waits out a heartbeat already writing, so none lands after the report.
        try (var timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("grapheval-heartbeat").factory())) {
            timer.scheduleAtFixedRate(progress::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            report = measure.apply(progress);
        } catch (RuntimeException e) {
            failure = heldOut ? e.getClass().getSimpleName() : withCause(e);
            EventLogger.warn("grapheval", "graph eval failed: " + failure);
        } finally {
            progress.close();
        }
        if (report != null) {
            writeLine(target, GSON.toJson(report));
            return;
        }
        var error = new JsonObject();
        error.addProperty("event", "error");
        error.addProperty("message", failure);
        writeLine(target, GSON.toJson(error));
    }

    private static String withCause(RuntimeException e) {
        var cause = e.getCause();
        if (cause == null) return String.valueOf(e.getMessage());
        return e.getMessage() + ": " + cause.getMessage();
    }

    /** Bytes, not a String: Play encodes a String chunk with {@code Response.current()}, unset on the timer thread. */
    private static void writeLine(Http.Response target, String json) {
        target.writeChunk((json + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** {@code POST /api/graph/eval/blind-sheet}: the blind subset's ids, text, anchors and owner for a second labeller. */
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "measurement harness -- loopback plus X-Loadtest-Auth; writes a file under data/graph-eval")
    public static void blindSheet() {
        var schema = schema();
        JsonObject sheet;
        try {
            var json = Files.readString(appPath(GraphCases.DEFAULT_PATH));
            sheet = Agreement.blindSheet(GraphCases.parse(json, schema), GraphCases.userMd(json),
                    GraphCases.capturedAt(json));
        } catch (IOException | RuntimeException e) {
            throw invalid(INVALID_CASE_SET + e.getMessage());
        }
        var file = root().resolve(BLIND_SHEET);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(sheet));
        } catch (IOException e) {
            throw invalid("could not write the blind sheet: " + e.getMessage());
        }
        var out = new JsonObject();
        out.addProperty("path", HeldOut.DIR + "/" + BLIND_SHEET);
        out.addProperty("cases", sheet.getAsJsonArray("cases").size());
        renderJSON(GSON.toJson(out));
    }

    /** {@code POST /api/graph/eval/heldout/sample} with {@code {agent, count, seed?}}: reads, never writes, memories. */
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "measurement harness -- loopback plus X-Loadtest-Auth; reads an agent's memories into data/graph-eval")
    public static void heldOutSample() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) throw invalid("A JSON body is required");
        if (!body.has("count")) throw invalid("count is required");
        int count = readInt(body, "count", 0);
        if (count < 1 || count > MAX_SAMPLE) throw invalid("count must be between 1 and " + MAX_SAMPLE);
        long seed;
        try {
            seed = body.has("seed") ? body.get("seed").getAsLong() : DEFAULT_SEED;
        } catch (RuntimeException _) {
            throw invalid("seed must be an integer");
        }
        var agentId = agentId(body);
        HeldOut.Sampled sampled;
        try {
            sampled = HeldOut.sample(agentId, count, seed);
        } catch (IOException | IllegalStateException e) {
            throw invalid("could not sample: " + e.getMessage());
        }
        var out = new JsonObject();
        out.addProperty("path", HeldOut.DIR + "/" + HeldOut.FILE);
        out.addProperty("sampled", sampled.sampled());
        out.addProperty("available", sampled.available());
        renderJSON(GSON.toJson(out));
    }

    /**
     * {@code POST /api/graph/eval/heldout/coverage}: the coverage report's label sections over the held-out file and
     * the competency questions (JCLAW-1374). Model-free: it asks no model, reads no decision setting and no memory, and
     * covers every labelled case, frozen splits included.
     */
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "measurement harness -- loopback plus X-Loadtest-Auth; reads data/graph-eval/heldout.json and returns counts only")
    public static void coverage() {
        var schema = schema();
        var file = HeldOut.defaultPath();
        if (!Files.exists(file)) throw invalid("no held-out file; run grapheval heldout-sample first");
        HeldOut.Loaded loaded;
        try {
            loaded = HeldOut.load(file, schema);
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid held-out set: " + e.getMessage());
        }
        var questions = questions(schema);
        renderJSON(GSON.toJson(CoverageReport.sections(loaded.cases(), questions, schema)));
    }

    /** The operator's competency questions, null when there is no question file. */
    private static @Nullable List<CompetencyQuestions.Question> questions(OntologySchema schema) {
        var file = CompetencyQuestions.defaultPath();
        if (!Files.exists(file)) return null;
        try {
            return CompetencyQuestions.load(file, schema);
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid competency questions: " + e.getMessage());
        }
    }

    private static String agentId(JsonObject body) {
        var agentName = JsonBodyReader.requiredOr400(body, "agent");
        var agent = Tx.run(() -> Agent.findByName(agentName));
        if (agent == null) throw invalid("No agent named '%s'".formatted(agentName));
        return String.valueOf(agent.id);
    }

    private static OntologySchema schema() {
        try {
            return OntologySchema.seed();
        } catch (RuntimeException e) {
            throw invalid("invalid schema: " + e.getMessage());
        }
    }

    private static Path appPath(String relative) {
        return Play.applicationPath.toPath().resolve(relative);
    }

    private static String string(JsonObject body, String key) {
        var value = body.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(key + " must be a string");
        }
        return value.getAsString().strip();
    }

    private static List<String> strings(JsonObject body, String key) {
        if (!body.has(key) || !body.get(key).isJsonArray()) throw invalid(key + " must be an array of strings");
        JsonArray array = body.getAsJsonArray(key);
        var out = new ArrayList<String>();
        for (var e : array) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank()) {
                throw invalid(key + " must be an array of non-blank strings");
            }
            out.add(e.getAsString().strip());
        }
        return out;
    }

    /** A number in [0, 1], or in (0, 1] unless {@code zeroAllowed}. */
    private static double share(JsonObject body, String key, boolean zeroAllowed) {
        double v;
        try {
            v = body.get(key).getAsDouble();
        } catch (RuntimeException _) {
            throw invalid(key + " must be a number");
        }
        if (!(v >= 0 && v <= 1) || !zeroAllowed && v == 0) {
            throw invalid(key + " must be in " + (zeroAllowed ? "[0, 1]" : "(0, 1]"));
        }
        return v;
    }

    private static long readLong(JsonObject body, String key) {
        try {
            return body.get(key).getAsLong();
        } catch (RuntimeException _) {
            throw invalid(key + " must be an integer");
        }
    }

    private static int readInt(JsonObject body, String key, int fallback) {
        if (!body.has(key)) return fallback;
        try {
            return body.get(key).getAsInt();
        } catch (RuntimeException _) {
            throw invalid(key + " must be an integer");
        }
    }

    private static AssertionError invalid(String message) {
        ApiResponses.error(400, ApiResponses.INVALID_REQUEST, message);
        return ApiResponses.unreachable();
    }
}
