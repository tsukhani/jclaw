package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.decision.LayaDecision;
import services.decision.OllamaDecision;

import java.util.List;
import java.util.Map;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/** The Ollama and Laya decision providers' servers and what each reports, for Settings → Decision Providers. */
@With(AuthCheck.class)
public class ApiDecisionController extends Controller {

    /**
     * {@code customized} is true when the operator stored an address rather than inheriting Ollama Local's;
     * {@code cloudModels} are the decision models the server hands to ollama.com, kept out of {@code models};
     * {@code contextTokens} names, by model, how many tokens one request may hold, where the server states it.
     */
    public record OllamaDecisionStatus(String baseUrl, boolean customized, boolean reachable,
                                       @Nullable String error, List<String> models, List<String> cloudModels,
                                       Map<String, Integer> contextTokens) {}

    @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = OllamaDecisionStatus.class)))
    @Operation(summary = "Report whether the Ollama decision server answers, and which decision models it has")
    @AgentAccess(OPERATOR_ONLY)
    public static void ollama() {
        var status = OllamaDecision.status(OllamaDecision.baseUrl());
        renderJSON(GSON.toJson(new OllamaDecisionStatus(status.baseUrl(), OllamaDecision.customized(),
                status.reachable(), status.error(), status.models(), status.cloudModels(), status.contextTokens())));
    }

    /**
     * {@code loaded} is the checkpoints in the server's memory and {@code device} where they compute; a server
     * that wants a key it was not sent reports neither.
     */
    public record LayaDecisionStatus(String baseUrl, boolean reachable, @Nullable String error, List<String> loaded,
                                     @Nullable String device) {}

    @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = LayaDecisionStatus.class)))
    @Operation(summary = "Report whether the Laya decision server answers, and which checkpoints it has loaded")
    @AgentAccess(OPERATOR_ONLY)
    public static void laya() {
        var status = LayaDecision.status(LayaDecision.baseUrl(), LayaDecision.apiKey());
        renderJSON(GSON.toJson(new LayaDecisionStatus(status.baseUrl(), status.reachable(), status.error(),
                status.loaded(), status.device())));
    }
}
