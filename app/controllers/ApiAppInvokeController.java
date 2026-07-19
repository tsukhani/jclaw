package controllers;

import agents.AgentRunner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import play.Play;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.ConversationService;
import services.Tx;
import utils.ApiResponses;
import utils.GsonHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.regex.Pattern;

/**
 * JCLAW-764 / AD-2: the single-purpose App → Agent invoke endpoint. A hosted app
 * POSTs to {@code POST /api/apps/<slug>/invoke} and its one operator-designated
 * agent — resolved fail-closed from {@code app.json.agent} (AD-3) — runs on the
 * text input, returning the agent's response. There is <em>no</em> target-agent /
 * endpoint / verb parameter: the agent is resolved solely from {@code <slug>},
 * never from the request body. {@link AppOriginGate} (in {@link AuthCheck})
 * guarantees an app-originated caller can reach only this route for its own slug.
 *
 * <p>Each invoke runs in a fresh app-owned {@code Conversation}
 * ({@code channelType="app"}, {@code peerId=slug}), so it is viewable and
 * filterable in the operator's Conversations page under the {@code app} channel
 * (AD-4). Being a non-{@code web} origin, an app turn is untrusted, so a dangerous
 * tool such as {@code exec} fails closed for it (see {@code utils.ChannelOriginTrust}
 * / JCLAW-777) — apps may feed the agent untrusted content (e.g. an uploaded RFP).
 */
@With(AuthCheck.class)
public class ApiAppInvokeController extends Controller {

    /** Same slug shape as {@code ApiAppsController} — a single lowercase path segment. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final String APP_JSON = "app.json";

    /** Channel type stamped on every app-owned conversation, so they group under one
     *  "app" channel in the operator's Conversations page. */
    private static final String APP_CHANNEL = "app";

    public static void invoke(String slug) {
        var agent = resolveDesignatedAgent(slug); // AD-3 — fail-closed 4xx on any miss
        var input = readInput();                   // AD-2 — text from the body; agent comes from the slug, never the body
        // AD-4: a fresh app-owned conversation. create() (not findOrCreate) so each
        // invoke is its own row; channelType/peerId make it viewable under the app channel.
        var conversation = ConversationService.create(agent, APP_CHANNEL, slug);
        // AD-6: reuse the existing agent-run pipeline — no parallel execution stack.
        var result = AgentRunner.run(agent, conversation, input);
        var resp = new HashMap<String, Object>();
        resp.put("conversationId", conversation.id);
        resp.put("response", result.response());
        renderJSON(GsonHolder.INSTANCE.toJson(resp));
    }

    /**
     * AD-2: the app's text input, read from the request body's {@code message}
     * field. No target-agent / endpoint / verb parameter is honored — any
     * {@code agent} / {@code verb} field in the body is ignored, since the agent is
     * resolved solely from {@code <slug>} (AD-3).
     */
    private static String readInput() {
        var body = JsonBodyReader.readJsonBody();
        var msg = (body != null && body.has("message") && !body.get("message").isJsonNull())
                ? body.get("message").getAsString() : null;
        if (msg == null || msg.isBlank()) {
            ApiResponses.error(400, "no_input", "invoke requires a non-empty 'message'");
        }
        return msg;
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
