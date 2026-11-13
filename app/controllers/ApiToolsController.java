package controllers;

import agents.McpServerCatalog;
import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import mcp.McpGrants;
import models.Agent;
import models.AgentToolConfig;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.ConfigService;
import services.Tx;
import services.secrets.SecretGrants;
import utils.ApiResponses;

import java.util.List;
import java.util.Set;

import static controllers.AgentAccess.Level.OPEN;
import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static controllers.AgentAccess.Level.OWN_ONLY;
import static utils.GsonHolder.GSON;

@With(AuthCheck.class)
public class ApiToolsController extends Controller {

    private static final Gson gson = GSON;

    private static final String KEY_ENABLED = "enabled";


    // JCLAW-281: no `system` flag — there are no system tools (list_mcp_tools deleted, loadtest_sleep gated by conditional registration).
    // JF-142: a catalog row carries no description or actions. At 680 registered tools those made each catalog
    // read several times jclaw_api's 50,000-character cap; GET /api/tools/{name} returns them for one tool.
    public record ToolListEntry(String name, @Nullable String group, boolean enabled, String shortDescription) {}

    public record ToolMetaEntry(String name, @Nullable String group, boolean enabled, String shortDescription,
                                String category, String icon, @Nullable String requiresConfig, int actionCount) {}

    public record AgentToolEntry(String name, @Nullable String group, boolean enabled, String shortDescription) {}

    public record ToolDetail(String name, @Nullable String group, boolean enabled, String shortDescription,
                             String category, String icon, @Nullable String requiresConfig,
                             String description, List<ToolAction> actions) {}

    public record ToolToggleRequest(boolean enabled) {}

    public record ToolToggleResponse(String name, boolean enabled, String status) {}

    public record ToolGroupToggleResponse(String group, boolean enabled, int count, String status) {}

    /**
     * GET /api/tools — the global catalog.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ToolListEntry.class))))
    @Operation(summary = "List the tools (name, group, enabled, one-line summary); a tool's description and actions "
            + "come from GET /api/tools/{name}, and an agent's own grants from GET /api/agents/{id}/tools")
    @AgentAccess(OPEN)
    public static void list() {
        var result = catalog().stream()
                .map(t -> new ToolListEntry(t.name(), t.group(), usable(t), t.shortDescription()))
                .toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * GET /api/tools/meta — the catalog with what a collapsed {@code /tools} card shows.
     *
     * <p>The backend is the single source of truth for category, icon and short description
     * (JCLAW-72 migrated these off a hardcoded frontend dictionary). No agent's grants filter the rows, except
     * that an agent other than main is shown only the MCP servers it may call ({@link #mcpServersShownTo}).
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ToolMetaEntry.class))))
    @Operation(summary = "List tool metadata (category, icon, action count) for the admin UI; "
            + "a tool's actions come from GET /api/tools/{name}")
    @AgentAccess(OPEN)
    public static void meta() {
        var result = catalog().stream()
                .map(t -> new ToolMetaEntry(
                        t.name(),
                        t.group(),
                        usable(t),
                        t.shortDescription(),
                        t.category(),
                        t.icon(),
                        t.requiresConfig(),
                        t.actions().size()))
                .toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * GET /api/tools/{name} — one tool, with its full description and actions.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ToolDetail.class)))
    @Operation(summary = "Get one tool by name, with its full description and actions")
    @AgentAccess(OPEN)
    public static void get(String name) {
        var t = ToolRegistry.lookupTool(name);
        // A server the caller is not shown answers as a name nobody registered, so the answer does not confirm it.
        if (t == null || !shown(t, callerMcpServers())) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No tool named '" + name + "' is registered.");
            throw ApiResponses.unreachable();
        }
        renderJSON(gson.toJson(new ToolDetail(t.name(), t.group(), usable(t), t.shortDescription(), t.category(),
                t.icon(), t.requiresConfig(), t.description(), t.actions())));
    }

    /**
     * The tools a catalog lists for the caller. An MCP server's per-action adapters fold into its server-level
     * handle, whose action count and detail stand for them, as they do in the model's own tool list:
     * the 600-odd adapters alone would put a catalog over jclaw_api's cap.
     */
    private static List<ToolRegistry.Tool> catalog() {
        var servers = callerMcpServers();
        return ToolRegistry.listTools().stream()
                .filter(t -> t.group() == null || t.isServerLevel())
                .filter(t -> shown(t, servers))
                .toList();
    }

    /** Whether a caller shown {@code servers} is shown {@code t}: a built-in tool always, an MCP tool only on one of them. */
    private static boolean shown(ToolRegistry.Tool t, @Nullable Set<String> servers) {
        var group = t.group();
        return group == null || servers == null || servers.contains(group);
    }

    private static @Nullable Set<String> callerMcpServers() {
        return mcpServersShownTo(RequestPrincipal.isAgentOriginated(), RequestPrincipal.callingAgent());
    }

    /**
     * The MCP servers whose handles and actions a caller is shown, or null for every one (JF-293). The operator
     * and main are shown every server. Any other agent is shown the ones it may call: a handle names a service
     * the operator connected, and its actions say what that service does. An agent-originated request that names
     * no agent is shown none.
     */
    // Public and free of the request, as RequestPrincipal.isOperatorOrMain is: a test must not seed main.
    public static @Nullable Set<String> mcpServersShownTo(boolean agentOriginated, @Nullable Agent caller) {
        if (RequestPrincipal.isOperatorOrMain(agentOriginated, caller)) return null;
        return caller == null ? Set.of() : Set.copyOf(McpServerCatalog.serversForAgent(caller));
    }

    /** Whether the tool can run on this instance at all: its {@code requiresConfig} key, when it names one, is true. */
    private static boolean usable(ToolRegistry.Tool t) {
        var key = t.requiresConfig();
        return key == null || ConfigService.getBoolean(key, false);
    }

    /**
     * GET /api/agents/{id}/tools — List tools for an agent with enabled status.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AgentToolEntry.class))))
    @Operation(summary = "List an agent's tools (name, group, enabled for this agent, one-line summary); "
            + "a tool's description and actions come from GET /api/tools/{name}")
    @AgentAccess(value = OWN_ONLY, reason = "another agent's settings are refused with agent_scope; main reaches every agent's (JF-285)")
    public static void listForAgent(Long id) {
        Agent agent = AgentService.findById(id);
        if (agent == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        if (!RequestPrincipal.mayReachAgentScopedRow(agent)) {
            ApiResponses.error(403, ApiResponses.AGENT_SCOPE,
                    "Agent " + id + " is not the calling agent; only main reaches another agent's settings.");
        }

        // Ask the agent loop what it will actually do rather than re-deriving the policy here:
        // a local copy ("native tools on, MCP on for main") silently drifted once tools began
        // opting out by default — generate_image (JCLAW-228), generate_video (JCLAW-235),
        // generate_audio (JCLAW-876), printer (JCLAW-911) — so the agent editor showed a ticked
        // box for a tool the agent could not call (found during JCLAW-911 UAT). One source of
        // truth also covers tools added to the default-off list later.
        var disabled = ToolRegistry.loadDisabledTools(agent);
        var result = catalog().stream()
                .map(t -> new AgentToolEntry(t.name(), t.group(), !disabled.contains(t.name()),
                        t.shortDescription()))
                .toList();
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
    @AgentAccess(value = OPERATOR_ONLY, reason = "writes a per-agent tool grant -- privilege escalation")
    public static void updateForAgent(Long id, String name) {

        Agent agent = AgentService.findById(id);
        if (agent == null) {
            notFound();
            throw ApiResponses.unreachable();
        }

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has(KEY_ENABLED)) {
            badRequest();
            throw ApiResponses.unreachable();
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

        var config = McpGrants.find(agent, name);
        if (config == null) config = McpGrants.newRow(agent, name);
        config.enabled = enabled;
        config.save();

        // Drop the per-agent disabled-tools cache so the next streaming turn sees the new
        // configuration. Deferred to the commit (JCLAW-1042): the save above is in this
        // request's transaction, so evicting now lets a concurrent tool call recompute the set
        // from the pre-commit rows and re-cache it — leaving a freshly-disabled tool callable.
        var toggled = agent;
        Tx.afterCommit(() -> ToolRegistry.invalidateDisabledToolsCache(toggled));
        // A skill whose tools just became unmet is no longer offered and gives no secret, to a running command either.
        SecretGrants.reconcileAfterCommit(agent);

        renderJSON(gson.toJson(new ToolToggleResponse(name, enabled, "ok")));
    }

    /**
     * PUT /api/agents/{id}/tool-groups/{group} — Enable or disable an MCP
     * server (and therefore every action it advertises) for one agent.
     * Body: {@code {"enabled": boolean}}.
     *
     * <p>Writes a single {@link AgentToolConfig} row addressing the server
     * with an empty action (JCLAW-983). The LLM's
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
    @AgentAccess(value = OPERATOR_ONLY, reason = "writes a per-agent MCP server grant -- privilege escalation")
    public static void updateGroupForAgent(Long id, String group) {

        Agent agent = AgentService.findById(id);
        if (agent == null) {
            notFound();
            throw ApiResponses.unreachable();
        }

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has(KEY_ENABLED)) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var enabled = body.get(KEY_ENABLED).getAsBoolean();

        // Find the server-level handle for this group. Every MCP server
        // registers exactly one isServerLevel()=true tool with group()
        // equal to the server name.
        var serverLevel = ToolRegistry.listTools().stream()
                .filter(t -> group.equals(t.group()) && t.isServerLevel())
                .findFirst()
                .orElse(null);
        if (serverLevel == null) {
            notFound();
            throw ApiResponses.unreachable();
        }

        var config = McpGrants.find(agent, serverLevel.name());
        if (config == null) config = McpGrants.newRow(agent, serverLevel.name());
        config.enabled = enabled;
        config.save();

        // Clean up any legacy per-action rows for this group — they no
        // longer have any read effect now that loadDisabledTools is
        // server-level-only, but leaving them around would (a) confuse
        // an operator inspecting the DB, and (b) leave noise for a
        // future refactor. One bulk DELETE per group toggle (rather than
        // one per action) keeps the table tidy as the operator naturally
        // cycles through their servers.
        if (config.mcpServer != null) {
            AgentToolConfig.delete("agent = ?1 AND mcpServer = ?2 AND mcpAction <> ''",
                    agent, config.mcpServer);
        }

        // Deferred for the same reason as the per-tool toggle above (JCLAW-1042) — and this
        // path also runs a bulk DELETE, which bypasses the entity lifecycle entirely.
        var toggled = agent;
        Tx.afterCommit(() -> ToolRegistry.invalidateDisabledToolsCache(toggled));
        SecretGrants.reconcileAfterCommit(agent);

        renderJSON(gson.toJson(new ToolGroupToggleResponse(group, enabled, 1, "ok")));
    }

}
