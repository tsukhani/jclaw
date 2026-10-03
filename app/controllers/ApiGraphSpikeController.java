package controllers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import memory.ontology.OntologySchema;
import models.Agent;
import play.Play;
import play.db.jpa.NoTransaction;
import play.mvc.Before;
import play.mvc.Controller;
import services.Tx;
import services.decision.JevApi;
import services.decision.OllamaDecision;
import services.graphspike.Agreement;
import services.graphspike.Certifier;
import services.graphspike.ExtractionPipeline.Decider;
import services.graphspike.GraphCases;
import services.graphspike.GraphSpikeHarness;
import services.graphspike.GraphSpikeHarness.DecisionModel;
import services.graphspike.HeldOut;
import utils.ApiResponses;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Certifies local Ollama decision models for graph extraction over {@code evals/graph/cases.json}, or measures them
 * over the operator's held-out set (JCLAW-1344, JCLAW-1356). An endpoint, like {@link ApiScrapeTestController},
 * because the decision provider it measures exists only inside a booted app.
 */
public class ApiGraphSpikeController extends Controller {

    private static final int MAX_CONCURRENCY = 4;
    private static final int DEFAULT_CONCURRENCY = 2;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_RUNS = 3;
    private static final int MAX_SAMPLE = 1000;
    private static final long DEFAULT_SEED = 1356;
    private static final String BLIND_SHEET = "blind-sheet.json";

    @Before
    static void requireLoadtestAuth() {
        LoadtestAuthCheck.checkLoadtestAuth();
    }

    /**
     * {@code POST /api/graph/spike} with {@code {agent, decisionModels?, set?: "cases"|"heldout", runs?,
     * recallFloor?, concurrency?, timeoutSeconds?}}. Model calls run outside any transaction, so each DB step opens
     * its own.
     */
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "measurement harness -- loopback plus X-Loadtest-Auth; spends model calls and writes then deletes an agent's memories")
    public static void spike() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) throw invalid("A JSON body is required");
        if (body.has("proposers")) throw invalid("proposers are gone: candidates come from fixed rules (JCLAW-1356)");
        if (body.has("threshold")) throw invalid("threshold is gone: the certification walk chooses it");
        var set = body.has("set") ? string(body, "set") : "cases";
        if (!set.equals("cases") && !set.equals("heldout")) throw invalid("set must be 'cases' or 'heldout'");
        int runs = readInt(body, "runs", GraphSpikeHarness.DEFAULT_RUNS);
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
        int timeoutSeconds = Math.clamp(readInt(body, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS), 1, MAX_TIMEOUT_SECONDS);
        int concurrency = Math.clamp(readInt(body, "concurrency", DEFAULT_CONCURRENCY), 1, MAX_CONCURRENCY);
        var modelNames = body.has("decisionModels") ? strings(body, "decisionModels") : OllamaDecision.selectedModels();
        if (modelNames.isEmpty()) throw invalid("decisionModels must not be empty");
        if (modelNames.contains(JevApi.MODEL)) {
            throw invalid(JevApi.MODEL + " is a hosted model; the graph certifier measures local Ollama models only");
        }
        var agentId = agentId(body);

        long timeoutMs = timeoutSeconds * 1000L;
        var baseUrl = OllamaDecision.baseUrl();
        var models = modelNames.stream().distinct()
                .map(m -> new DecisionModel(m, Decider.ollama(baseUrl, m, timeoutMs))).toList();
        var schema = schema();

        if (set.equals("heldout")) {
            var file = HeldOut.defaultPath();
            if (!Files.exists(file)) throw invalid("no held-out file; run graphspike heldout-sample first");
            HeldOut.Loaded loaded;
            try {
                loaded = HeldOut.load(file, schema);
            } catch (IOException | RuntimeException e) {
                throw invalid("invalid held-out set: " + e.getMessage());
            }
            renderJSON(GSON.toJson(GraphSpikeHarness.runHeldOut(loaded, schema, models, runs, recallFloor,
                    concurrency)));
            return;
        }

        var cases = cases(schema);
        List<GraphCases.Case> secondLabels;
        List<Certifier.Adjudication> adjudications;
        try {
            var second = appPath(GraphCases.SECOND_LABELS_PATH);
            secondLabels = Files.exists(second) ? GraphCases.load(second, schema) : List.of();
            var verdicts = appPath(GraphCases.ADJUDICATIONS_PATH);
            adjudications = Files.exists(verdicts) ? Certifier.parseAdjudications(Files.readString(verdicts)) : List.of();
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid second labels or adjudications: " + e.getMessage());
        }
        renderJSON(GSON.toJson(GraphSpikeHarness.run(agentId, cases, schema, models, runs, recallFloor, concurrency,
                secondLabels, adjudications)));
    }

    /** {@code POST /api/graph/spike/blind-sheet}: writes the blind subset's ids and text for a second labeller. */
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "measurement harness -- loopback plus X-Loadtest-Auth; writes a file under data/graph-eval")
    public static void blindSheet() {
        var cases = cases(schema());
        var sheet = Agreement.blindSheet(cases);
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

    /** {@code POST /api/graph/spike/heldout/sample} with {@code {agent, count, seed?}}: reads, never writes, memories. */
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
