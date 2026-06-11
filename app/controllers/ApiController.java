package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static utils.GsonHolder.INSTANCE;

/**
 * API controller for the Nuxt 3 frontend.
 * All endpoints are prefixed with /api/ in the routes file.
 */
public class ApiController extends Controller {

    /**
     * @param status                   {@code "ok"} when the controller responds at all
     * @param application              the value of {@code application.name} from
     *                                 {@code conf/application.conf}
     * @param mode                     {@code "DEV"} or {@code "PROD"}
     * @param applicationVersion       JClaw's own version from
     *                                 {@code application.version}
     * @param frameworkVersion         Play framework version baked into the fork
     *                                 the JVM booted from (e.g. {@code "1.13.24"});
     *                                 surfaced so the sidebar can render it under
     *                                 the JClaw version line
     * @param expectedFrameworkVersion the version string in {@code .play-version}
     *                                 at the project root — the version the
     *                                 fork SHOULD be on. {@code null} when the
     *                                 file is absent (dist install) or unreadable.
     *                                 The sidebar compares this against
     *                                 {@code frameworkVersion} and colors a dot
     *                                 green (match) or amber (drift).
     */
    public record StatusResponse(String status, String application, String mode,
                                  String applicationVersion, String frameworkVersion,
                                  String expectedFrameworkVersion) {}

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    @Operation(summary = "Report service health, app name/version, run mode, and Play framework version vs. expected")
    public static void status() {
        var resp = new StatusResponse(
                "ok",
                play.Play.configuration.getProperty("application.name"),
                play.Play.mode.toString(),
                play.Play.configuration.getProperty("application.version", "0.0.0"),
                play.Play.version,
                readExpectedFrameworkVersion());
        renderJSON(INSTANCE.toJson(resp));
    }

    /**
     * Read {@code .play-version} from {@link play.Play#applicationPath} and
     * return its trimmed contents, or {@code null} when the file is absent
     * / unreadable (dist installs strip the dotfile; cleanly-installed
     * environments otherwise without it). Never throws — the status
     * endpoint is health-check infrastructure and must not 500 on a
     * missing optional file.
     */
    private static String readExpectedFrameworkVersion() {
        try {
            var path = Path.of(play.Play.applicationPath.getAbsolutePath(), ".play-version");
            if (!Files.isRegularFile(path)) return null;
            var raw = Files.readString(path).trim();
            return raw.isEmpty() ? null : raw;
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }
}
