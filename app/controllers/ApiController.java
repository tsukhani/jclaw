package controllers;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;

import static utils.GsonHolder.INSTANCE;

/**
 * API controller for the Nuxt 3 frontend.
 * All endpoints are prefixed with /api/ in the routes file.
 */
public class ApiController extends Controller {

    public record StatusResponse(String status, String application, String mode, String applicationVersion) {}

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    public static void status() {
        var resp = new StatusResponse(
                "ok",
                play.Play.configuration.getProperty("application.name"),
                play.Play.mode.toString(),
                play.Play.configuration.getProperty("application.version", "0.0.0"));
        renderJSON(INSTANCE.toJson(resp));
    }
}
