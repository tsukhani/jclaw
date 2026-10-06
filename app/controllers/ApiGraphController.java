package controllers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import memory.graph.GraphStore;
import memory.graph.RunLedger.Selector;
import play.db.jpa.NoTransaction;
import play.mvc.Controller;
import play.mvc.With;
import utils.ApiResponses;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/** The memory graph's operator actions: retracting what extraction runs wrote (JCLAW-1371). */
@With(AuthCheck.class)
public class ApiGraphController extends Controller {

    private static final String RUN_IDS = "runIds";
    private static final List<String> SELECTORS = List.of(RUN_IDS, "model", "digest", "certificate");

    public record RetractionCounts(int runs, int evidence, int records) {}

    /**
     * {@code POST /api/graph/retract} with exactly one of {@code runIds} (a non-empty array), {@code model},
     * {@code digest} or {@code certificate}: retracts the matching ledger runs in every agent's graph.
     */
    @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = RetractionCounts.class)))
    @Operation(summary = "Retract the graph records extraction runs wrote, by run id, model, digest or certificate")
    @NoTransaction
    @AgentAccess(value = OPERATOR_ONLY, reason = "quarantines graph records an extraction run wrote; operator decision")
    public static void retract() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) throw invalid("A JSON body is required");
        for (var key : body.keySet()) {
            if (!SELECTORS.contains(key)) throw invalid("unknown key '" + key + "'");
        }
        if (body.size() != 1) throw invalid("give exactly one of " + String.join(", ", SELECTORS));
        var key = body.keySet().iterator().next();
        var store = GraphStore.get();
        GraphStore.Retraction result;
        try {
            if (key.equals(RUN_IDS)) {
                var runIds = runIds(body.get(RUN_IDS));
                result = GraphStore.Retraction.NONE;
                for (var agentId : store.agentIds()) result = result.plus(store.retract(agentId, runIds));
            } else {
                var value = text(body, key);
                result = store.retract(switch (key) {
                    case "model" -> new Selector.Model(value);
                    case "digest" -> new Selector.Digest(value);
                    default -> new Selector.Certificate(value);
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        renderJSON(GSON.toJson(new RetractionCounts(result.runs(), result.evidence(), result.records())));
    }

    private static Set<String> runIds(JsonElement e) {
        if (!e.isJsonArray() || e.getAsJsonArray().isEmpty()) throw invalid("runIds must be a non-empty array");
        var out = new TreeSet<String>();
        for (var id : e.getAsJsonArray()) {
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || id.getAsString().isBlank()) {
                throw invalid("runIds holds a blank or non-string id");
            }
            out.add(id.getAsString());
        }
        return out;
    }

    private static String text(JsonObject body, String key) {
        var e = body.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank()) {
            throw invalid(key + " must be a non-blank string");
        }
        return e.getAsString();
    }

    private static AssertionError invalid(String message) {
        ApiResponses.error(400, ApiResponses.INVALID_REQUEST, message);
        return ApiResponses.unreachable();
    }
}
