package controllers;

import agents.SkillLoader;
import agents.ToolRegistry;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import static utils.GsonHolder.INSTANCE;
import models.Agent;
import models.AgentToolConfig;
import play.mvc.Controller;
import play.mvc.With;

import java.util.HashMap;
import java.util.List;

@With(AuthCheck.class)
public class ApiToolsController extends Controller {

    private static final Gson gson = INSTANCE;

    public record ToolListEntry(String name, String description, boolean system) {}

    public record ToolMetaEntry(String name, String category, String icon, String shortDescription,
                                boolean system, String requiresConfig, String group, List<agents.ToolAction> actions) {}

    public record AgentToolEntry(String name, String description, boolean system, String group, boolean enabled) {}

    public record ToolToggleRequest(boolean enabled) {}

    public record ToolToggleResponse(String name, boolean enabled, String status) {}

    public record ToolGroupToggleResponse(String group, boolean enabled, int count, String status) {}

    /**
     * GET /api/tools — List all registered tools (global catalog).
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ToolListEntry.class))))
    public static void list() {
        var result = ToolRegistry.listTools().stream()
                .map(t -> new ToolListEntry(t.name(), t.description(), t.isSystem()))
                .toList();
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
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ToolMetaEntry.class))))
    public static void meta() {
        var result = ToolRegistry.listTools().stream()
                .map(t -> new ToolMetaEntry(
                        t.name(),
                        t.category(),
                        t.icon(),
                        t.shortDescription(),
                        t.isSystem(),
                        t.requiresConfig(),
                        t.group(),
                        t.actions()))
                .toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * GET /api/agents/{id}/tools — List tools for an agent with enabled status.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AgentToolEntry.class))))
    public static void listForAgent(Long id) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var allTools = ToolRegistry.listTools();
        var configs = AgentToolConfig.findByAgent(agent);
        var configMap = new HashMap<String, Boolean>();
        for (var c : configs) {
            configMap.put(c.toolName, c.enabled);
        }

        // Default policy: native tools are enabled-by-default; grouped tools
        // (MCP) are enabled-by-default for the main agent, disabled-by-default
        // for custom agents. Mirrors ToolRegistry.loadDisabledTools so the UI
        // and the agent loop agree on the resolved state.
        boolean isMain = agent.isMain();

        var result = allTools.stream().map(t -> {
            boolean defaultEnabled = t.group() == null || isMain;
            boolean enabled = t.isSystem() || configMap.getOrDefault(t.name(), defaultEnabled);
            return new AgentToolEntry(t.name(), t.description(), t.isSystem(), t.group(), enabled);
        }).toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * PUT /api/agents/{id}/tools/{name} — Enable or disable a tool for an agent.
     */
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ToolToggleRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ToolToggleResponse.class)))
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

        renderJSON(gson.toJson(new ToolToggleResponse(name, enabled, "ok")));
    }

    /**
     * PUT /api/agents/{id}/tool-groups/{group} — Bulk enable/disable every
     * tool in a group for one agent. Used by the agent detail page so
     * toggling an MCP server (which contributes N tools) is one HTTP call,
     * not N. Body: {@code {"enabled": boolean}}.
     */
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ToolToggleRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ToolGroupToggleResponse.class)))
    public static void updateGroupForAgent(Long id, String group) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("enabled")) badRequest();
        var enabled = body.get("enabled").getAsBoolean();

        var members = ToolRegistry.listTools().stream()
                .filter(t -> group.equals(t.group()))
                .toList();
        if (members.isEmpty()) notFound();

        for (var tool : members) {
            // System tools cannot be disabled; ignore them inside a group rather
            // than failing the bulk call (lets a future "system" group still toggle
            // its non-system members cleanly).
            if (tool.isSystem() && !enabled) continue;
            var config = AgentToolConfig.findByAgentAndTool(agent, tool.name());
            if (config == null) {
                config = new AgentToolConfig();
                config.agent = agent;
                config.toolName = tool.name();
            }
            config.enabled = enabled;
            config.save();
        }

        SkillLoader.clearCache();
        ToolRegistry.invalidateDisabledToolsCache(agent);

        renderJSON(gson.toJson(new ToolGroupToggleResponse(group, enabled, members.size(), "ok")));
    }

}
