package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import tools.HarnessSandbox;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Whether this host can start the OS sandbox, for the Settings panels: what {@code shell.sandbox} and
 * {@code subagent.acp.sandbox} need, and what every custom agent's {@code exec} needs, since it always runs
 * confined and is refused where the sandbox cannot start.
 */
@With(AuthCheck.class)
public class ApiSandboxController extends Controller {

    /** GET /api/sandbox — probe whether the platform's sandbox can start (on Linux, a real bwrap launch). */
    @Operation(summary = "Report whether this host has an OS sandbox mechanism (sandbox-exec / bwrap)")
    @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = HarnessSandbox.Availability.class)))
    @AgentAccess(value = OPERATOR_ONLY, reason = "a host probe only the Settings panels read")
    public static void availability() {
        renderJSON(GSON.toJson(HarnessSandbox.availability()));
    }
}
