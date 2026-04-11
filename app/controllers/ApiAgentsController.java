package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import llm.ProviderRegistry;
import models.Agent;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;
import services.AgentService;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

@With(AuthCheck.class)
public class ApiAgentsController extends Controller {

    private static final Gson gson = new Gson();

    public static void list() {
        var agents = AgentService.listAll();
        var result = agents.stream().map(a -> agentToMap(a)).toList();
        renderJSON(gson.toJson(result));
    }

    public static void get(Long id) {
        var agent = AgentService.findById(id);
        if (agent == null) notFound();
        renderJSON(gson.toJson(agentToMap(agent)));
    }

    public static void create() {
        var body = readJsonBody();
        if (body == null) badRequest();

        var name = body.get("name").getAsString();
        if (Agent.MAIN_AGENT_NAME.equalsIgnoreCase(name)) {
            error(409, "The agent name 'main' is reserved for the built-in agent");
        }
        var modelProvider = body.get("modelProvider").getAsString();
        var modelId = body.get("modelId").getAsString();
        var thinkingMode = body.has("thinkingMode") && !body.get("thinkingMode").isJsonNull()
                ? body.get("thinkingMode").getAsString() : null;

        var agent = AgentService.create(name, modelProvider, modelId, thinkingMode);
        renderJSON(gson.toJson(agentToMap(agent)));
    }

    public static void update(Long id) {
        var agent = AgentService.findById(id);
        if (agent == null) notFound();

        var body = readJsonBody();
        if (body == null) badRequest();

        var name = body.has("name") ? body.get("name").getAsString() : agent.name;
        // The reserved name "main" is a singleton: no other agent may take the name,
        // and the main agent may not be renamed away from it.
        if (!agent.isMain() && Agent.MAIN_AGENT_NAME.equalsIgnoreCase(name)) {
            error(409, "The agent name 'main' is reserved for the built-in agent");
        }
        if (agent.isMain() && !Agent.MAIN_AGENT_NAME.equalsIgnoreCase(name)) {
            error(409, "The main agent cannot be renamed");
        }
        var modelProvider = body.has("modelProvider") ? body.get("modelProvider").getAsString() : agent.modelProvider;
        var modelId = body.has("modelId") ? body.get("modelId").getAsString() : agent.modelId;
        var enabled = body.has("enabled") ? body.get("enabled").getAsBoolean() : agent.enabled;
        // The main agent cannot be disabled. Service-layer enforcement would also
        // catch this, but we reject at the API boundary so the operator sees an
        // explicit error instead of a silently-ignored toggle.
        if (agent.isMain() && !enabled) {
            error(409, "The main agent cannot be disabled");
        }
        var thinkingMode = body.has("thinkingMode")
                ? (body.get("thinkingMode").isJsonNull() ? null : body.get("thinkingMode").getAsString())
                : agent.thinkingMode;

        agent = AgentService.update(agent, name, modelProvider, modelId, enabled, thinkingMode);
        renderJSON(gson.toJson(agentToMap(agent)));
    }

    public static void delete(Long id) {
        var agent = AgentService.findById(id);
        if (agent == null) notFound();
        if (agent.isMain()) {
            error(409, "The built-in 'main' agent cannot be deleted");
        }
        AgentService.delete(agent);
        renderJSON(gson.toJson(new HashMap<>(java.util.Map.of("status", "ok"))));
    }

    // --- Workspace file endpoints ---

    /**
     * GET /api/agents/{id}/files/{filePath} — Serve a workspace file with proper content type.
     * Supports images, PDFs, and other binary files for inline rendering or download.
     */
    public static void serveWorkspaceFile(Long id, String filePath) {
        var agent = AgentService.findById(id);
        if (agent == null) notFound();

        // Two-layer (lexical + canonical) path validation with double-resolve.
        // The previous substring check (`filePath.contains("..")`) didn't
        // normalize the path before checking, and never compared against the
        // workspace root. acquireWorkspacePath does both, plus realpath
        // resolution so a symlink inside the workspace pointing outside is
        // also rejected.
        java.nio.file.Path path;
        try {
            path = AgentService.acquireWorkspacePath(agent.name, filePath);
        } catch (SecurityException e) {
            forbidden();
            return;
        }
        var file = path.toFile();
        if (!file.exists() || !file.isFile()) notFound();

        // Content type: Play's MimeTypes resolver covers the bundled database plus
        // any custom mimetype.* entries declared in application.conf.
        var contentType = play.libs.MimeTypes.getContentType(filePath);

        response.setHeader("Cache-Control", "private, max-age=300");

        var inline = contentType.startsWith("image/") || contentType.startsWith("application/pdf");
        try {
            renderBinary(new java.io.FileInputStream(file), file.getName(), file.length(), contentType, inline);
        } catch (java.io.FileNotFoundException e) {
            notFound();
        }
    }

    public static void getWorkspaceFile(Long id, String filename) {
        var agent = AgentService.findById(id);
        if (agent == null) notFound();
        var content = AgentService.readWorkspaceFile(agent.name, filename);
        if (content == null) notFound();
        renderJSON(gson.toJson(java.util.Map.of("filename", filename, "content", content)));
    }

    public static void saveWorkspaceFile(Long id, String filename) {
        var agent = AgentService.findById(id);
        if (agent == null) notFound();
        var body = readJsonBody();
        if (body == null || !body.has("content")) badRequest();
        AgentService.writeWorkspaceFile(agent.name, filename, body.get("content").getAsString());
        renderJSON(gson.toJson(java.util.Map.of("status", "ok", "filename", filename)));
    }

    // --- Helpers ---

    private static HashMap<String, Object> agentToMap(Agent a) {
        var map = new HashMap<String, Object>();
        map.put("id", a.id);
        map.put("name", a.name);
        map.put("modelProvider", a.modelProvider);
        map.put("modelId", a.modelId);
        map.put("enabled", a.enabled);
        map.put("isMain", a.isMain());
        map.put("thinkingMode", a.thinkingMode);
        map.put("createdAt", a.createdAt.toString());
        map.put("updatedAt", a.updatedAt.toString());

        var provider = ProviderRegistry.get(a.modelProvider);
        var providerConfigured = provider != null
                && provider.config().models().stream().anyMatch(m -> m.id().equals(a.modelId));
        map.put("providerConfigured", providerConfigured);

        return map;
    }

    private static com.google.gson.JsonObject readJsonBody() {
        try {
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception _) {
            return null;
        }
    }
}
