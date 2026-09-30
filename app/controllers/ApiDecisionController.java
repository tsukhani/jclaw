package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.decision.OllamaDecision;

import java.util.List;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/** The Ollama decision provider's server and its installed decision models, for Settings → Decision Providers. */
@With(AuthCheck.class)
public class ApiDecisionController extends Controller {

    /** {@code customized} is true when the operator stored an address rather than inheriting Ollama Local's. */
    public record OllamaDecisionStatus(String baseUrl, boolean customized, boolean reachable,
                                       @Nullable String error, List<String> models) {}

    @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = OllamaDecisionStatus.class)))
    @Operation(summary = "Report whether the Ollama decision server answers, and which decision models it has")
    @AgentAccess(OPERATOR_ONLY)
    public static void ollama() {
        var status = OllamaDecision.status(OllamaDecision.baseUrl());
        renderJSON(GSON.toJson(new OllamaDecisionStatus(status.baseUrl(), OllamaDecision.customized(),
                status.reachable(), status.error(), status.models())));
    }
}
