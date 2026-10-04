package controllers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import memory.ontology.OntologySchema;
import models.Agent;
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
import services.grapheval.Agreement;
import services.grapheval.Certifier;
import services.grapheval.Configuration;
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
import java.util.List;
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

    @Before
    static void requireLoadtestAuth() {
        LoadtestAuthCheck.checkLoadtestAuth();
    }

    /**
     * {@code POST /api/graph/eval} with {@code {agent, decisionModels?, set?: "cases"|"heldout"|"sequences", runs?,
     * recallFloor?, concurrency?, timeoutSeconds?, pairFilter?, configuration?}}; {@code sequences} reads no
     * {@code agent} and takes an optional {@code configuration} (JCLAW-1367). Model calls run outside any transaction, so each DB step opens
     * its own. A refused request is a 400; an accepted one is {@linkplain #stream streamed}.
     */
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "measurement harness -- loopback plus X-Loadtest-Auth; spends model calls, and only the cases set writes then deletes an agent's memories")
    public static void run() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) throw invalid("A JSON body is required");
        if (body.has("proposers")) throw invalid("proposers are gone: candidates come from fixed rules (JCLAW-1356)");
        if (body.has("threshold")) throw invalid("threshold is gone: the certification walk chooses it");
        var set = body.has("set") ? string(body, "set") : "cases";
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
        int runs = readInt(body, "runs", GraphEvalHarness.DEFAULT_RUNS);
        if (runs < 1 || runs > MAX_RUNS) throw invalid("runs must be between 1 and " + MAX_RUNS);
        double recallFloor = Certifier.DEFAULT_RECALL_FLOOR;
        if (body.has("recallFloor")) {
            try {
                recallFloor = body.get("recallFloor").getAsDouble();
            } catch (RuntimeException _) {
                throw invalid("recallFloor must be a number");
            }
            if (!(recallFloor >= 0 && recallFloor <= 1)) throw invalid("recallFloor must be in [0, 1]");
        }
        double floor = recallFloor;
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
            stream(progress -> GraphEvalHarness.runHeldOut(loaded, ownerName, schema, models, runs, floor,
                    concurrency, progress, filter), true);
            return;
        }

        if (set.equals("sequences")) {
            Sequences sequences;
            try {
                sequences = Sequences.load(appPath(Sequences.DEFAULT_PATH), schema);
            } catch (IOException | RuntimeException e) {
                throw invalid("invalid sequence set: " + e.getMessage());
            }
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
        var cases = cases(schema);
        String ownerName;
        try {
            ownerName = GraphCases.ownerName(Files.readString(appPath(GraphCases.DEFAULT_PATH)));
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid case set: " + e.getMessage());
        }
        GraphCases.SecondLabels second;
        try {
            second = GraphCases.loadSecondLabels(appPath(GraphCases.SECOND_LABELS_PATH), schema);
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid second labels: " + e.getMessage());
        }
        List<Certifier.Adjudication> adjudications;
        try {
            var verdicts = appPath(GraphCases.ADJUDICATIONS_PATH);
            adjudications = Files.exists(verdicts) ? Certifier.parseAdjudications(Files.readString(verdicts)) : List.of();
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid adjudications: " + e.getMessage());
        }
        stream(progress -> GraphEvalHarness.run(agentId, cases, ownerName, schema, models, runs, floor, concurrency,
                second.cases(), adjudications, progress, filter, second.reason()), false);
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
        var timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("grapheval-heartbeat").factory());
        timer.scheduleAtFixedRate(progress::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        Object report = null;
        String failure = null;
        try {
            report = measure.apply(progress);
        } catch (RuntimeException e) {
            var cause = e.getCause();
            failure = heldOut ? e.getClass().getSimpleName()
                    : e.getMessage() + (cause == null ? "" : ": " + cause.getMessage());
            EventLogger.warn("grapheval", "graph eval failed: " + failure);
        } finally {
            timer.shutdown();
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
            throw invalid("invalid case set: " + e.getMessage());
        }
        var file = appPath(HeldOut.DIR).resolve(BLIND_SHEET);
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

    private static List<GraphCases.Case> cases(OntologySchema schema) {
        try {
            return GraphCases.load(appPath(GraphCases.DEFAULT_PATH), schema);
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid case set: " + e.getMessage());
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
