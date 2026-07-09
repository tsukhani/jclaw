package controllers;

import agents.SkillLoader;
import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.AgentToolConfig;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import utils.ApiResponses;

import java.util.HashMap;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

@With(AuthCheck.class)
public class ApiToolsController extends Controller {

    private static final Gson gson = INSTANCE;

    private static final String KEY_ENABLED = "enabled";

    // JCLAW-281: the `system` boolean is gone — there are no system tools
    // any more (list_mcp_tools deleted, loadtest_sleep gated by conditional
    // registration). The field is removed from every entry record.
    public record ToolListEntry(String name, String description) {}

    public record ToolMetaEntry(String name, String category, String icon, String shortDescription,
                                String requiresConfig, String group, List<ToolAction> actions) {}

    public record AgentToolEntry(String name, String description, String group, boolean enabled) {}

    public record ToolToggleRequest(boolean enabled) {}

    public record ToolToggleResponse(String name, boolean enabled, String status) {}

    public record ToolGroupToggleResponse(String group, boolean enabled, int count, String status) {}

    /**
     * GET /api/tools — List all registered tools (global catalog).
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ToolListEntry.class))))
    @Operation(summary = "List all globally-registered tools (name, category, description)")
    public static void list() {
        var result = ToolRegistry.listTools().stream()
                .map(t -> new ToolListEntry(t.name(), t.description()))
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
    @Operation(summary = "List tool metadata (category, icon, actions) for the admin UI")
    public static void meta() {
        var result = ToolRegistry.listTools().stream()
                .map(t -> new ToolMetaEntry(
                        t.name(),
                        t.category(),
                        t.icon(),
                        t.shortDescription(),
                        t.requiresConfig(),
                        t.group(),
                        t.actions()))
                .toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * GET /api/agents/{id}/tools — List tools for an agent with enabled status.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AgentToolEntry.class))))
    @Operation(summary = "List an agent's tools and their enabled state")
    public static void listForAgent(Long id) {
        Agent agent = AgentService.findById(id);
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
            boolean enabled = configMap.getOrDefault(t.name(), defaultEnabled);
            return new AgentToolEntry(t.name(), t.description(), t.group(), enabled);
        }).toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * PUT /api/agents/{id}/tools/{name} — Enable or disable a tool for an agent.
     *
     * <p>Per-action MCP tool names (e.g. {@code mcp_jira_create_issue}) are
     * rejected with 400. MCP tools toggle at the server level only: the
     * caller should use {@code PUT /api/agents/{id}/tool-groups/{group}}
     * instead. This is the post-Phase-6 contract — the per-action toggle
     * is no longer surfaced in the UI and the consolidated server-level
     * row is the single source of truth for MCP enablement.
     */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ToolToggleRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ToolToggleResponse.class)))
    @Operation(summary = "Enable or disable a tool for an agent")
    public static void updateForAgent(Long id, String name) {
        Agent agent = AgentService.findById(id);
        if (agent == null) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has(KEY_ENABLED)) {
            badRequest();
            throw new AssertionError("unreachable: badRequest() throws");
        }
        var enabled = body.get(KEY_ENABLED).getAsBoolean();

        // Reject per-action MCP toggles. A non-null group on a non-server-
        // level tool is the signature of an MCP per-action adapter. The
        // operator's per-server toggle goes through updateGroupForAgent;
        // direct per-action enable/disable is no longer a supported path.
        var tool = ToolRegistry.lookupTool(name);
        if (tool != null && tool.group() != null && !tool.isServerLevel()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Per-action MCP tools are no longer toggleable individually. "
                    + "Use PUT /api/agents/" + id + "/tool-groups/" + tool.group()
                    + " to enable or disable the entire '" + tool.group() + "' server.");
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
     * PUT /api/agents/{id}/tool-groups/{group} — Enable or disable an MCP
     * server (and therefore every action it advertises) for one agent.
     * Body: {@code {"enabled": boolean}}.
     *
     * <p>Writes a single {@link AgentToolConfig} row keyed by the
     * server-level handle name ({@code mcp_<group>}). The LLM's
     * function-calling schema only exposes the server-level handle
     * — actions are addressed via {@code mcp_<group>}'s {@code tool}
     * parameter at execution time — so a single row is sufficient to
     * gate the entire MCP server. Operators who want per-action
     * granularity must enforce it at the McpAllowlist layer (the
     * confused-deputy gate at execution time), not via the function-
     * calling schema.
     *
     * <p>Stale per-action rows (legacy: pre-server-level installs that
     * wrote one row per action) are cleaned up here too — toggling an
     * MCP server consolidates any per-action rows for that server's
     * group into the single server-level row.
     */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ToolToggleRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ToolGroupToggleResponse.class)))
    @Operation(summary = "Enable or disable a tool group (e.g. an MCP server) for an agent")
    public static void updateGroupForAgent(Long id, String group) {
        Agent agent = AgentService.findById(id);
        if (agent == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has(KEY_ENABLED)) badRequest();
        var enabled = body.get(KEY_ENABLED).getAsBoolean();

        // Find the server-level handle for this group. Every MCP server
        // registers exactly one isServerLevel()=true tool with group()
        // equal to the server name.
        var serverLevel = ToolRegistry.listTools().stream()
                .filter(t -> group.equals(t.group()) && t.isServerLevel())
                .findFirst()
                .orElse(null);
        if (serverLevel == null) notFound();

        // Write the single server-level row.
        var config = AgentToolConfig.findByAgentAndTool(agent, serverLevel.name());
        if (config == null) {
            config = new AgentToolConfig();
            config.agent = agent;
            config.toolName = serverLevel.name();
        }
        config.enabled = enabled;
        config.save();

        // Clean up any legacy per-action rows for this group — they no
        // longer have any read effect now that loadDisabledTools is
        // server-level-only, but leaving them around would (a) confuse
        // an operator inspecting the DB, and (b) leave noise for a
        // future refactor. One bulk DELETE per group toggle (rather than
        // one per action) keeps the table tidy as the operator naturally
        // cycles through their servers.
        var perActionNames = ToolRegistry.listTools().stream()
                .filter(t -> group.equals(t.group()) && !t.isServerLevel())
                .map(ToolRegistry.Tool::name)
                .toList();
        if (!perActionNames.isEmpty()) {
            AgentToolConfig.delete("agent = ?1 AND toolName IN (?2)", agent, perActionNames);
        }

        SkillLoader.clearCache();
        ToolRegistry.invalidateDisabledToolsCache(agent);

        renderJSON(gson.toJson(new ToolGroupToggleResponse(group, enabled, 1, "ok")));
    }

}
