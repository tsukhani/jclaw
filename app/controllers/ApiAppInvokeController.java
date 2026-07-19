package controllers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import play.Play;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.Tx;
import utils.ApiResponses;
import utils.GsonHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JCLAW-764 / AD-2: the single-purpose App → Agent invoke endpoint. A hosted app
 * POSTs to {@code POST /api/apps/<slug>/invoke} and its one operator-designated
 * agent — resolved fail-closed from {@code app.json.agent} (AD-3) — runs. There is
 * <em>no</em> target-agent / endpoint / verb parameter: the agent is resolved solely
 * from {@code <slug>}, never from the request body. {@link AppOriginGate} (in
 * {@link AuthCheck}) guarantees an app-originated caller can reach only this route
 * for its own slug.
 *
 * <p><b>Slice A (this commit):</b> the provenance gate + AD-3 fail-closed resolution,
 * returning a stub. Execution — a fresh app-owned {@code Conversation}
 * ({@code channelType="app"}) delegating to the agent-run pipeline — is the next
 * commit.
 */
@With(AuthCheck.class)
public class ApiAppInvokeController extends Controller {

    /** Same slug shape as {@code ApiAppsController} — a single lowercase path segment. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final String APP_JSON = "app.json";

    public static void invoke(String slug) {
        var agent = resolveDesignatedAgent(slug); // AD-3 — throws 4xx (fail-closed) on any miss
        // Slice A: no agent run yet — this proves the gate + resolution end-to-end.
        renderJSON(GsonHolder.INSTANCE.toJson(
                Map.of("stub", true, "agentId", agent.id, "agentName", agent.name)));
    }

    /**
     * AD-3: resolve the app's designated agent from {@code app.json.agent} (the
     * agent's id, stored as a string — see {@code apps.vue}). A missing manifest,
     * missing/blank field, non-numeric value, or unknown/deleted agent fails closed
     * ({@code 4xx}) — never a default agent.
     */
    private static Agent resolveDesignatedAgent(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            ApiResponses.error(404, "no_such_app", "No such app");
        }
        var appsDir = Play.getFile("public/apps").toPath().toAbsolutePath().normalize();
        var manifest = appsDir.resolve(slug).resolve(APP_JSON).normalize();
        if (!manifest.startsWith(appsDir) || !Files.isRegularFile(manifest)) {
            ApiResponses.error(404, "no_such_app", "No such app: " + slug);
        }
        var agentIdStr = readAgentId(manifest, slug); // throws 4xx if manifest unreadable / no agent
        var agentId = parseAgentId(agentIdStr, slug);  // throws 400 if non-numeric
        var agent = Tx.run(() -> AgentService.findById(agentId));
        if (agent == null) {
            ApiResponses.error(400, "unknown_agent",
                    "App '" + slug + "' designates an agent that no longer exists");
        }
        return agent;
    }

    /** The raw {@code agent} field of the manifest, or fail-closed 4xx when absent. */
    private static String readAgentId(Path manifest, String slug) {
        JsonObject m;
        try {
            m = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        } catch (Exception e) {
            ApiResponses.error(404, "no_such_app", "App manifest unreadable: " + slug);
            return null; // unreachable — error() threw
        }
        var v = (m.has("agent") && !m.get("agent").isJsonNull()) ? m.get("agent").getAsString() : null;
        if (v == null || v.isBlank()) {
            ApiResponses.error(400, "no_agent", "App '" + slug + "' has no designated agent");
        }
        return v;
    }

    private static long parseAgentId(String agentIdStr, String slug) {
        try {
            return Long.parseLong(agentIdStr.trim());
        } catch (NumberFormatException e) {
            ApiResponses.error(400, "bad_agent", "App '" + slug + "' has an invalid agent designation");
            return -1; // unreachable — error() threw
        }
    }
}
