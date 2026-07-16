package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.Play;
import play.mvc.Controller;
import play.mvc.With;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * Apps registry (SPEC-apps): enumerates operator-hosted mini-apps under
 * {@code public/apps/<slug>/}. Each app self-describes via {@code app.json}
 * (name/version/creator/icon/price) and is launched statically at
 * {@code /apps/<slug>/}. The filesystem IS the registry — no DB entity — so
 * adding or removing an app is adding or removing its directory.
 */
@With(AuthCheck.class)
public class ApiAppsController extends Controller {

    private static final Gson gson = INSTANCE;

    /** One hosted app: the parsed manifest plus derived launch fields. {@code id}
     *  is the directory name under {@code public/apps/}; {@code url} = {@code
     *  /apps/<id>/}; {@code icon} is resolved to an app-root-relative URL (null
     *  when the manifest omits it — the card supplies a default). {@code agent}
     *  is the designated agent id the app may invoke (JCLAW-763; null when the
     *  manifest omits it — the app is non-invoking). */
    public record AppEntry(String id, String url, String name, String version,
                           String creator, String icon, String price, String description,
                           String agent) {}

    public record AppsResponse(List<AppEntry> apps) {}

    /** GET /api/apps — every {@code public/apps/<slug>/} carrying both {@code
     *  app.json} and {@code index.html}. A missing or malformed manifest is
     *  skipped, never a 500. */
    @Operation(summary = "List operator-hosted mini-apps discovered under public/apps/")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AppsResponse.class)))
    public static void list() {
        var appsDir = Play.getFile("public/apps").toPath();
        var apps = new ArrayList<AppEntry>();
        if (Files.isDirectory(appsDir)) {
            try (var dirs = Files.list(appsDir)) {
                dirs.filter(Files::isDirectory).sorted().forEach(dir -> {
                    var entry = readApp(dir);
                    if (entry != null) apps.add(entry);
                });
            } catch (IOException _) {
                // public/apps unreadable — return whatever we have (likely empty)
            }
        }
        renderJSON(gson.toJson(new AppsResponse(apps)));
    }

    /** Parse one app directory into an entry, or null when it isn't a valid,
     *  launchable app (missing app.json/index.html, or unparseable manifest). */
    private static AppEntry readApp(Path dir) {
        if (!Files.isRegularFile(dir.resolve("app.json"))
                || !Files.isRegularFile(dir.resolve("index.html"))) {
            return null;
        }
        try {
            var m = JsonParser.parseString(Files.readString(dir.resolve("app.json"))).getAsJsonObject();
            var id = dir.getFileName().toString();
            var name = str(m, "name");
            var version = str(m, "version");
            var icon = str(m, "icon");
            return new AppEntry(
                    id,
                    "/apps/" + id + "/",
                    name != null ? name : id,
                    version != null ? version : "0.0.0",
                    str(m, "creator"),
                    icon != null ? "/apps/" + id + "/" + icon : null,
                    str(m, "price"),
                    str(m, "description"),
                    str(m, "agent"));
        } catch (RuntimeException | IOException _) {  // malformed manifest — skip, don't fail the list
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
