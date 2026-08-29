package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.Play;
import play.mvc.Before;
import play.mvc.Controller;
import services.AgentService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static utils.GsonHolder.GSON;

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
     * @param spaBuildId               the deployed SPA's Nuxt build id, or
     *                                 {@code null} when the SPA isn't built.
     *                                 Answers "is this browser on a stale
     *                                 build?" with one curl instead of an SSH
     *                                 session — the triage step every report of
     *                                 the GitHub-issue-9 class needs first.
     *                                 Only a meaningful signal because
     *                                 JCLAW-887 gated the SPA rebuild on
     *                                 staleness: Nuxt derives this id from
     *                                 {@code randomUUID()} per build, so before
     *                                 that gate every restart minted a new id
     *                                 and the field would have reported churn
     *                                 rather than change.
     */
    public record StatusResponse(String status, String application, String mode,
                                  String applicationVersion, String frameworkVersion,
                                  String expectedFrameworkVersion, String spaBuildId) {}

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    @Operation(summary = "Report service health, app name/version, run mode, and Play framework version vs. expected")
    public static void status() {
        var resp = new StatusResponse(
                "ok",
                Play.configuration.getProperty("application.name"),
                Play.mode.toString(),
                Play.configuration.getProperty("application.version", "0.0.0"),
                Play.version,
                readExpectedFrameworkVersion(),
                readSpaBuildId());
        renderJSON(GSON.toJson(resp));
    }

    /**
     * Read {@code .play-version} from {@link play.Play#applicationPath} and
     * return its trimmed contents, or {@code null} when the file is absent
     * / unreadable (dist installs strip the dotfile; cleanly-installed
     * environments otherwise without it). Never throws — the status
     * endpoint is health check infrastructure and must not 500 on a
     * missing optional file.
     */
    private static String readExpectedFrameworkVersion() {
        try {
            var path = Path.of(Play.applicationPath.getAbsolutePath(), ".play-version");
            if (!Files.isRegularFile(path)) return null;
            var raw = Files.readString(path).trim();
            return raw.isEmpty() ? null : raw;
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /**
     * The fields of Nuxt's {@code _nuxt/builds/latest.json} we care about.
     * It also carries a {@code timestamp}; Gson ignores what isn't declared.
     */
    private record NuxtBuildManifest(String id) {}

    /**
     * Read the deployed SPA's build id from
     * {@code public/spa/_nuxt/builds/latest.json}, or {@code null} when the
     * SPA isn't built or the manifest is unreadable/malformed. Never throws,
     * for the same reason as {@link #readExpectedFrameworkVersion()} — a
     * health check must not 500 over an optional file.
     */
    private static String readSpaBuildId() {
        try {
            var path = Play.getFile("public/spa/_nuxt/builds/latest.json").toPath();
            if (!Files.isRegularFile(path)) return null;
            var manifest = GSON.fromJson(Files.readString(path), NuxtBuildManifest.class);
            return manifest == null ? null : manifest.id();
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /**
     * @param bytes total on-disk size of the workspace root, or {@code -1}
     *              when the walk failed (permissions, concurrent deletion)
     */
    public record WorkspaceStatsResponse(long bytes) {}

    /** Session-gated, unlike {@link #status()} — the health check stays public. */
    @Before(only = {"workspaceStats"})
    static void requireAdminSession() {
        AuthCheck.checkAuthentication();
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WorkspaceStatsResponse.class)))
    @Operation(summary = "Total on-disk size of the agent workspace root, for the dashboard's runaway-growth line")
    public static void workspaceStats() {
        renderJSON(GSON.toJson(
                new WorkspaceStatsResponse(directorySizeBytes(AgentService.workspaceRoot()))));
    }

    /**
     * Recursive on-disk size of {@code root} in bytes: 0 when the directory
     * doesn't exist, -1 when the walk fails midway (unreadable subtree,
     * files vanishing during iteration). Never throws — a dashboard stat
     * must not 500 the page. Public because the test tree compiles into the
     * default package.
     */
    public static long directorySizeBytes(Path root) {
        if (!Files.isDirectory(root)) return 0;
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException _) {
                    return 0; // vanished mid-walk — count what remains
                }
            }).sum();
        } catch (IOException | RuntimeException _) {
            return -1;
        }
    }
}
