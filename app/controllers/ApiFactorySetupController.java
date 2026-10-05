package controllers;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.EventLogger;
import services.factory.FactoryInstallJob;
import services.factory.FactorySetup;
import utils.ApiResponses;

import java.io.IOException;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Software Factory setup (JCLAW-1393): prerequisites and which credentials are set, write-only
 * credential entry, and {@code install-agent.sh} as a pollable background job. The terminal
 * equivalent is {@code .sandcastle/README.md}'s "Setup on a Mac".
 */
@With(AuthCheck.class)
public class ApiFactorySetupController extends Controller {

    private static final String CATEGORY = "factory";
    static final String CREDENTIALS_MESSAGE = "Credentials saved. Re-run the installer for a running harness to use them.";

    /** GET /api/factory/setup — the prerequisites and a boolean per credential, never a value. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = FactorySetup.SetupView.class)))
    @AgentAccess(OPERATOR_ONLY)
    public static void setup() {
        renderJSON(GSON.toJson(FactorySetup.view(null)));
    }

    /** POST /api/factory/setup/credentials — write any of the credential fields to the factory home. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = FactorySetup.SetupView.class)))
    @AgentAccess(value = OPERATOR_ONLY, reason = "writes the AFK factory's model, Jira and GitHub credentials")
    public static void credentials() {
        var action = "update factory credentials";
        var body = JsonBodyReader.readJsonBody();
        if (body == null) throw refuse(400, ApiResponses.INVALID_REQUEST, action, "the body is not a JSON object");
        String invalid;
        String failure = null;
        try {
            invalid = FactorySetup.applyCredentials(body);
        } catch (IOException e) {
            invalid = null;
            failure = e.getClass().getSimpleName();
        }
        if (invalid != null) throw refuse(400, ApiResponses.INVALID_REQUEST, action, stripPeriod(invalid));
        if (failure != null) {
            // The exception's message can carry a path, never a value; the class name is enough here.
            EventLogger.warn(CATEGORY, "Operator could not update factory credentials: " + failure);
            ApiResponses.error(500, ApiResponses.INTERNAL_ERROR, "Could not write the credential files: " + failure);
        }
        EventLogger.info(CATEGORY, "Operator updated factory credentials: " + String.join(", ", body.keySet()));
        renderJSON(GSON.toJson(FactorySetup.view(CREDENTIALS_MESSAGE)));
    }

    /** POST /api/factory/setup/install — start {@code install-agent.sh} in the background; 202 with the job. */
    @ApiResponse(responseCode = "202", content = @Content(schema = @Schema(implementation = FactoryInstallJob.JobView.class)))
    @AgentAccess(value = OPERATOR_ONLY, reason = "runs the AFK factory installer on the operator's machine")
    public static void install() {
        var job = FactoryInstallJob.start();
        if (job == null) {
            throw refuse(409, ApiResponses.CONFLICT, "run the factory installer",
                    "another install or harness command is still running");
        }
        EventLogger.info(CATEGORY, "Operator started the factory installer (job " + job.id() + ")");
        response.status = 202;
        renderJSON(GSON.toJson(job));
    }

    /** GET /api/factory/setup/install/{id} — the job's state, elapsed time and output so far. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = FactoryInstallJob.JobView.class)))
    @AgentAccess(OPERATOR_ONLY)
    public static void installStatus(String id) {
        var job = FactoryInstallJob.get(id);
        if (job == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No factory install job " + id + ".");
            throw ApiResponses.unreachable();
        }
        renderJSON(GSON.toJson(job));
    }

    private static String stripPeriod(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static AssertionError refuse(int status, String code, String action, String reason) {
        EventLogger.warn(CATEGORY, "Operator request to " + action + " refused: " + reason);
        ApiResponses.error(status, code, "Cannot " + action + ": " + reason + ".");
        return ApiResponses.unreachable();
    }
}
