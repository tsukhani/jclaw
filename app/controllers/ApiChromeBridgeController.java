package controllers;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.ChromeBridgePairing;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Pairing for the Chrome extension bridge. Every action is operator-only: the credential it
 * mints lets a browser extension drive the operator's own browser tabs.
 */
@With(AuthCheck.class)
public class ApiChromeBridgeController extends Controller {

    public record StatusResponse(boolean paired) {}

    public record PairingResponse(String token) {}

    /** GET /api/chrome-bridge — whether an extension is paired. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    @AgentAccess(OPERATOR_ONLY)
    public static void status() {
        renderJSON(GSON.toJson(new StatusResponse(ChromeBridgePairing.isPaired())));
    }

    /** POST /api/chrome-bridge/pairing — mints the credential, shown once; revokes any earlier one. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "mints the credential that lets a browser extension drive the operator's tabs")
    public static void pair() {
        renderJSON(GSON.toJson(new PairingResponse(ChromeBridgePairing.mint())));
    }

    /** DELETE /api/chrome-bridge/pairing — revokes the pairing. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "cuts the browser extension off from this instance")
    public static void unpair() {
        ChromeBridgePairing.revoke();
        renderJSON(GSON.toJson(new StatusResponse(false)));
    }
}
