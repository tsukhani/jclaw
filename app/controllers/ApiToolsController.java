package controllers;

import agents.SkillLoader;
import agents.ToolRegistry;
import com.google.gson.Gson;

import static utils.GsonHolder.INSTANCE;
import models.Agent;
import models.AgentToolConfig;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;

import java.util.HashMap;
import java.util.List;

@With(AuthCheck.class)
public class ApiToolsController extends Controller {

    private static final Gson gson = INSTANCE;

    /**
     * GET /api/tools — List all registered tools (global catalog).
     */
    public static void list() {
        var tools = ToolRegistry.listTools();
        var result = tools.stream().map(t -> {
            var map = new HashMap<String, Object>();
            map.put("name", t.name());
            map.put("description", t.description());
            map.put("system", t.isSystem());
            return map;
        }).toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * GET /api/tools/meta — Authoritative tool metadata for the admin UI.
     *
     * <p>Unlike {@link #list()} this returns the rich presentational shape the
     * frontend's {@code useToolMeta} composable consumes. The backend is now
     * the single source of truth for category, icon, short description, and the
     * enumerated action list (JCLAW-72 migrated these off the hardcoded frontend
     * dictionary). Purely static registry metadata — no per-agent filtering,
     * no DB transaction.
     */
    public static void meta() {
        var result = ToolRegistry.listTools().stream().map(t -> {
            var map = new HashMap<String, Object>();
            map.put("name", t.name());
            map.put("category", t.category());
            map.put("icon", t.icon());
            map.put("shortDescription", t.shortDescription());
            map.put("system", t.isSystem());
            if (t.requiresConfig() != null) {
                map.put("requiresConfig", t.requiresConfig());
            }
            if (t.group() != null) {
                map.put("group", t.group());
            }
            map.put("actions", t.actions());
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
            map.put("system", t.isSystem());
            // System tools are always enabled; user config is ignored for them
            map.put("enabled", t.isSystem() || configMap.getOrDefault(t.name(), true));
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

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("enabled")) badRequest();
        var enabled = body.get("enabled").getAsBoolean();

        // System tools cannot be disabled — they are always available to the agent.
        var tool = ToolRegistry.listTools().stream().filter(t -> t.name().equals(name)).findFirst();
        if (tool.isPresent() && tool.get().isSystem() && !enabled) {
            error(403, "System tool '%s' cannot be disabled.".formatted(name));
        }

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
        // Drop the per-agent disabled-tools cache so the next streaming turn sees the
        // new configuration immediately instead of on cache expiry.
        ToolRegistry.invalidateDisabledToolsCache(agent);

        var map = new HashMap<String, Object>();
        map.put("name", name);
        map.put("enabled", enabled);
        map.put("status", "ok");
        renderJSON(gson.toJson(map));
    }

}
