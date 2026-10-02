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
import services.decision.DecisionSettings;
import services.decision.JevApi;
import services.decision.OllamaDecision;
import services.graphspike.ExtractionPipeline.Decider;
import services.graphspike.GraphCases;
import services.graphspike.GraphSpikeHarness;
import services.graphspike.GraphSpikeHarness.DecisionModel;
import services.graphspike.GraphSpikeHarness.NamedProposer;
import services.graphspike.MentionProposer;
import utils.ApiResponses;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Measures graph-extraction reliability per decision model over {@code evals/graph/cases.json} (JCLAW-1344). An
 * endpoint, like {@link ApiScrapeTestController}, because the decision providers and the chat providers it measures
 * exist only inside a booted app.
 */
public class ApiGraphSpikeController extends Controller {

    private static final List<String> DEFAULT_MODELS = List.of(JevApi.MODEL, "tev1", "nimble");
    /** The router's {@code jev.minConfidence} default. */
    private static final double DEFAULT_THRESHOLD = 0.50;
    private static final int MAX_CONCURRENCY = 4;
    private static final int DEFAULT_CONCURRENCY = 2;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;

    @Before
    static void requireLoadtestAuth() {
        LoadtestAuthCheck.checkLoadtestAuth();
    }

    /**
     * {@code POST /api/graph/spike} with {@code {agent, proposers: ["provider/model"], decisionModels?, threshold?,
     * concurrency?, timeoutSeconds?}}. Model calls run outside any transaction, so each DB step opens its own.
     */
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "measurement harness -- loopback plus X-Loadtest-Auth; spends model calls and writes then deletes an agent's memories")
    public static void spike() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) throw invalid("A JSON body is required");
        var agentName = JsonBodyReader.requiredOr400(body, "agent");
        var agent = Tx.run(() -> Agent.findByName(agentName));
        if (agent == null) throw invalid("No agent named '%s'".formatted(agentName));
        var agentId = String.valueOf(agent.id);

        int timeoutSeconds = Math.clamp(readInt(body, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS), 1, MAX_TIMEOUT_SECONDS);
        int concurrency = Math.clamp(readInt(body, "concurrency", DEFAULT_CONCURRENCY), 1, MAX_CONCURRENCY);
        double threshold = DEFAULT_THRESHOLD;
        if (body.has("threshold")) {
            try {
                threshold = body.get("threshold").getAsDouble();
            } catch (RuntimeException _) {
                throw invalid("threshold must be a number");
            }
            if (!(threshold > 0 && threshold <= 1)) throw invalid("threshold must be in (0, 1]");
        }

        var proposers = new ArrayList<NamedProposer>();
        for (var name : strings(body, "proposers")) {
            int slash = name.indexOf('/');
            if (slash <= 0 || slash == name.length() - 1) throw invalid("proposer '%s' must read provider/model".formatted(name));
            proposers.add(new NamedProposer(name,
                    MentionProposer.llm(name.substring(0, slash), name.substring(slash + 1), timeoutSeconds)));
        }
        if (proposers.isEmpty()) throw invalid("proposers must name at least one provider/model");

        var modelNames = body.has("decisionModels") ? strings(body, "decisionModels") : DEFAULT_MODELS;
        if (modelNames.isEmpty()) throw invalid("decisionModels must not be empty");
        long timeoutMs = timeoutSeconds * 1000L;
        var models = new ArrayList<DecisionModel>();
        for (var model : modelNames.stream().distinct().toList()) {
            models.add(decisionModel(model, timeoutMs));
        }

        OntologySchema schema;
        List<GraphCases.Case> cases;
        try {
            schema = OntologySchema.seed();
            cases = GraphCases.load(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH), schema);
        } catch (IOException | RuntimeException e) {
            throw invalid("invalid case set: " + e.getMessage());
        }

        renderJSON(GSON.toJson(GraphSpikeHarness.run(agentId, cases, schema, proposers, models, threshold,
                concurrency)));
    }

    private static DecisionModel decisionModel(String model, long timeoutMs) {
        if (model.equals(JevApi.MODEL)) {
            var key = DecisionSettings.apiKey();
            return key == null ? DecisionModel.skipped(model, "no API key")
                    : DecisionModel.of(model, Decider.jev(key, timeoutMs));
        }
        return DecisionModel.of(model, Decider.ollama(OllamaDecision.baseUrl(), model, timeoutMs));
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
