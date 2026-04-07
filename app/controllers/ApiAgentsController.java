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
        var modelProvider = body.get("modelProvider").getAsString();
        var modelId = body.get("modelId").getAsString();
        var isDefault = body.has("isDefault") && body.get("isDefault").getAsBoolean();

        var agent = AgentService.create(name, modelProvider, modelId, isDefault);
        renderJSON(gson.toJson(agentToMap(agent)));
    }

    public static void update(Long id) {
        var agent = AgentService.findById(id);
        if (agent == null) notFound();

        var body = readJsonBody();
        if (body == null) badRequest();

        var name = body.has("name") ? body.get("name").getAsString() : agent.name;
        var modelProvider = body.has("modelProvider") ? body.get("modelProvider").getAsString() : agent.modelProvider;
        var modelId = body.has("modelId") ? body.get("modelId").getAsString() : agent.modelId;
        var enabled = body.has("enabled") ? body.get("enabled").getAsBoolean() : agent.enabled;
        var isDefault = body.has("isDefault") ? body.get("isDefault").getAsBoolean() : agent.isDefault;

        agent = AgentService.update(agent, name, modelProvider, modelId, enabled, isDefault);
        renderJSON(gson.toJson(agentToMap(agent)));
    }

    public static void delete(Long id) {
        var agent = AgentService.findById(id);
        if (agent == null) notFound();
        AgentService.delete(agent);
        renderJSON(gson.toJson(new HashMap<>(java.util.Map.of("status", "ok"))));
    }

    // --- Workspace file endpoints ---

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
        map.put("isDefault", a.isDefault);
        map.put("createdAt", a.createdAt.toString());
        map.put("updatedAt", a.updatedAt.toString());

        var provider = ProviderRegistry.get(a.modelProvider);
        var providerConfigured = provider != null
                && provider.models().stream().anyMatch(m -> m.id().equals(a.modelId));
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
