package controllers;

import agents.SkillLoader;
import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import models.Agent;
import models.AgentToolConfig;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;
import services.AgentService;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

@With(AuthCheck.class)
public class ApiToolsController extends Controller {

    private static final Gson gson = new Gson();

    /**
     * GET /api/tools — List all registered tools (global catalog).
     */
    public static void list() {
        var tools = ToolRegistry.listTools();
        var result = tools.stream().map(t -> {
            var map = new HashMap<String, Object>();
            map.put("name", t.name());
            map.put("description", t.description());
            return map;
        }).toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * GET /api/agents/{id}/tools — List tools for an agent with enabled status.
     */
    public static void listForAgent(Long id) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var allTools = ToolRegistry.listTools();
        var configs = AgentToolConfig.findByAgent(agent);
        var configMap = new HashMap<String, Boolean>();
        for (var c : configs) {
            configMap.put(c.toolName, c.enabled);
        }

        var result = allTools.stream().map(t -> {
            var map = new HashMap<String, Object>();
            map.put("name", t.name());
            map.put("description", t.description());
            // Default to enabled if no config exists
            map.put("enabled", configMap.getOrDefault(t.name(), true));
            return map;
        }).toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * PUT /api/agents/{id}/tools/{name} — Enable or disable a tool for an agent.
     */
    public static void updateForAgent(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var body = readJsonBody();
        if (body == null || !body.has("enabled")) badRequest();
        var enabled = body.get("enabled").getAsBoolean();

        var config = AgentToolConfig.findByAgentAndTool(agent, name);
        if (config == null) {
            config = new AgentToolConfig();
            config.agent = agent;
            config.toolName = name;
        }
        config.enabled = enabled;
        config.save();

        // Invalidate the SkillLoader cache so any skill whose tool requirements just became
        // unmet is excluded from the next request's <available_skills>, and any skill whose
        // requirements just became met is re-included. Prevents a window where the agent can
        // still see/invoke a skill that requires a freshly-disabled tool.
        SkillLoader.clearCache();

        var map = new HashMap<String, Object>();
        map.put("name", name);
        map.put("enabled", enabled);
        map.put("status", "ok");
        renderJSON(gson.toJson(map));
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
