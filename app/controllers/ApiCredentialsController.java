package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.UnreadableCredentials;
import services.UnreadableCredentials.Item;

import java.util.List;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/** The stored credentials as a whole (JCLAW-1455): which of them this installation's secret cannot decrypt. */
@With(AuthCheck.class)
public class ApiCredentialsController extends Controller {

    // Item by its simple name: CredentialReadGateConformanceTest matches record names inside this type by substring.
    public record UnreadableResponse(List<Item> unreadable) {}

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = UnreadableResponse.class)))
    @Operation(summary = "The stored credentials that do not decrypt under this installation's secret, and where each is entered again")
    @AgentAccess(value = OPERATOR_ONLY, reason = "names every credential an operator must re-enter")
    public static void unreadable() {
        renderJSON(GSON.toJson(new UnreadableResponse(UnreadableCredentials.list())));
    }
}
