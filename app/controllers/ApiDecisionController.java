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

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/** The Ollama and Laya decision providers' servers and what each reports, for Settings → Decision Providers. */
@With(AuthCheck.class)
public class ApiDecisionController extends Controller {

    /** {@code customized} is true when the operator stored an address rather than inheriting Ollama Local's. */
    public record OllamaDecisionStatus(String baseUrl, boolean customized, boolean reachable,
                                       @Nullable String error, List<OllamaDecision.DecisionModel> models) {}

    @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = OllamaDecisionStatus.class)))
    @Operation(summary = "Report whether the Ollama decision server answers, and which decision models it has")
    @AgentAccess(OPERATOR_ONLY)
    public static void ollama() {
        var status = OllamaDecision.status(OllamaDecision.baseUrl());
        renderJSON(GSON.toJson(new OllamaDecisionStatus(status.baseUrl(), OllamaDecision.customized(),
                status.reachable(), status.error(), status.models())));
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
