package controllers;

import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import mcp.CallToolResult;
import mcp.McpAppPage;
import mcp.McpConnectionManager;
import mcp.McpElicitations;
import mcp.McpToolAdapter;
import mcp.McpViewTools;
import models.Agent;
import models.Conversation;
import models.McpServer;
import org.jspecify.annotations.Nullable;
import play.db.jpa.NoTransaction;
import play.mvc.Controller;
import play.mvc.With;
import services.EventLogger;
import services.McpServerService;
import services.Tx;
import utils.ApiResponses;
import utils.JsonArgs;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static controllers.AgentAccess.Level.OPEN;
import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Admin CRUD over the {@code mcp_server} table (JCLAW-33).
 *
 * <p>Ten endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/mcp-servers} — list with merged DB + runtime state</li>
 *   <li>{@code POST /api/mcp-servers} — create + connect-if-enabled</li>
 *   <li>{@code GET /api/mcp-servers/{id}} — single row</li>
 *   <li>{@code PUT /api/mcp-servers/{id}} — partial update; reconnect if needed</li>
 *   <li>{@code DELETE /api/mcp-servers/{id}} — disconnect + delete row
 *       (allowlist + tool registry teardown is handled by
 *       {@link McpConnectionManager#stop} which already audits
 *       {@code MCP_TOOL_UNREGISTER})</li>
 *   <li>{@code POST /api/mcp-servers/{id}/test} — synchronous test:
 *       throwaway client tries initialize + tools/list, returns
 *       success+tool count or error message</li>
 *   <li>{@code GET /api/mcp-servers/view} — an MCP Apps view page, for the web chat's frame</li>
 *   <li>{@code POST /api/mcp-servers/view/call} — a tool call from that view, on its own server</li>
 *   <li>{@code GET /api/mcp-servers/elicitations} — the servers' forms waiting in a web-chat conversation</li>
 *   <li>{@code POST /api/mcp-servers/elicitations/{id}} — the operator's answer to one of them</li>
 * </ul>
 *
 * <p>The {@code mcp_server} table is operator-wide — JClaw is
 * single-operator, so there is no per-user scoping.
 */
@With(AuthCheck.class)
public class ApiMcpServersController extends Controller {

    private static final Gson gson = GSON;

    public record McpServerRequest(String name, Boolean enabled, Boolean requiresApproval,
                                   Boolean offerViewToolsEverywhere,
                                   String transport, String command, List<String> args,
                                   Map<String, String> env, String url,
                                   Map<String, String> headers, String protocol) {}

    public record ViewCallRequest(Long conversationId, String server, String tool, Map<String, Object> arguments) {}

    public record ElicitationAnswer(String action, Map<String, Object> content) {}

    // A view's tool call is refused with JClaw's own words; a server's error text never reaches the view.
    private static final String VIEW_NOT_CONNECTED = "The server is not connected.";
    private static final String VIEW_NEEDS_APPROVAL = "This server needs approval for each call, which a view cannot give.";
    private static final String VIEW_NOT_FOR_APP = "This tool cannot be called from its view.";
    private static final String VIEW_NOT_ALLOWED = "The conversation's agent is not allowed to call this tool.";
    private static final String VIEW_CALL_FAILED = "The tool call failed.";
    private static final String VIEW_NOT_WEB_CHAT = "Views act only in web chat.";

    // JSON body keys reused across create/update parsers.
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TRANSPORT = "transport";
    private static final String KEY_REQUIRES_APPROVAL = "requiresApproval";
    private static final String KEY_VIEW_TOOLS_EVERYWHERE = "offerViewToolsEverywhere";
    private static final String KEY_PROTOCOL = "protocol";
    /** The extra key on a 400 that names the form field it is about. */
    private static final String KEY_FIELD = "field";

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = McpServerService.View.class))))
    @Operation(summary = "List MCP servers with status and tool count")
    @AgentAccess(OPEN)
    public static void list() {
        renderJSON(gson.toJson(McpServerService.listAll()));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = McpServerService.View.class)))
    @Operation(summary = "Get a single MCP server by id")
    @AgentAccess(OPEN)
    public static void get(Long id) {
        var row = requireServer(id);
        renderJSON(gson.toJson(McpServerService.View.of(row)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = McpServerService.View.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = McpServerRequest.class)))
    @Operation(summary = "Add an MCP server (STDIO or HTTP)")
    @AgentAccess(value = OPEN,
            reason = "HTTP transports are agent-reachable and DangerousActionGate bounds the verb; "
                    + "a STDIO command is main-only (JCLAW-1270); offerViewToolsEverywhere is operator-only")
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }

        var name = JsonBodyReader.requiredOr400(body, "name");
        if (McpServer.findByName(name) != null) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "An MCP server named '%s' already exists".formatted(name));
        }
        var transport = readTransport(body);
        requireMainForStdio(transport);
        var row = new McpServer();
        row.name = name;
        // Default true when the key is absent or explicitly null; otherwise
        // honor the user-supplied boolean.
        row.enabled = !body.has(KEY_ENABLED) || body.get(KEY_ENABLED).isJsonNull()
                || body.get(KEY_ENABLED).getAsBoolean();
        // JCLAW-388: per-server approval gate. Defaults false (opt-in) when the
        // key is absent or null; honors the supplied boolean otherwise.
        row.requiresApproval = body.has(KEY_REQUIRES_APPROVAL)
                && !body.get(KEY_REQUIRES_APPROVAL).isJsonNull()
                && body.get(KEY_REQUIRES_APPROVAL).getAsBoolean();
        var viewToolsEverywhere = readViewToolsEverywhere(body);
        requireOperatorForViewToolsChange(false, viewToolsEverywhere);
        row.offerViewToolsEverywhere = Boolean.TRUE.equals(viewToolsEverywhere);
        row.transport = transport;
        var protocol = readProtocol(body);
        if (protocol != null) row.protocol = protocol;
        restoreMaskedSecrets(body, McpServerService.TransportConfig.empty());
        row.configJson = McpServerService.composeConfigJson(transport, body);
        try {
            McpServerService.validate(row);
        } catch (IllegalArgumentException e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, ApiResponses.messageOf(e));
        }
        row.save();

        McpServerService.syncRuntime(row);
        renderJSON(gson.toJson(McpServerService.View.of(row)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = McpServerService.View.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = McpServerRequest.class)))
    @Operation(summary = "Update an MCP server by id; it reconnects automatically")
    @AgentAccess(value = OPEN,
            reason = "same seam as create; a STDIO command is main-only (JCLAW-1270); offerViewToolsEverywhere is operator-only")
    public static void update(Long id) {
        var row = requireServer(id);
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }

        // Renaming is allowed; if it happens we tear down the prior connection
        // (under the OLD name) before re-syncing under the new one. Otherwise
        // McpConnectionManager would carry a stale entry forever.
        var priorName = row.name;
        var priorConfig = McpServerService.explodeConfigJson(row.transport, row.configJson);
        applyRenameIfPresent(row, body);
        if (body.has(KEY_ENABLED) && !body.get(KEY_ENABLED).isJsonNull()) {
            row.enabled = body.get(KEY_ENABLED).getAsBoolean();
        }
        // JCLAW-388: only mutate the approval flag when the key is present —
        // a partial update that omits it leaves the persisted value intact.
        if (body.has(KEY_REQUIRES_APPROVAL) && !body.get(KEY_REQUIRES_APPROVAL).isJsonNull()) {
            row.requiresApproval = body.get(KEY_REQUIRES_APPROVAL).getAsBoolean();
        }
        var viewToolsEverywhere = readViewToolsEverywhere(body);
        requireOperatorForViewToolsChange(row.offerViewToolsEverywhere, viewToolsEverywhere);
        if (viewToolsEverywhere != null) row.offerViewToolsEverywhere = viewToolsEverywhere;
        if (body.has(KEY_TRANSPORT) && !body.get(KEY_TRANSPORT).isJsonNull()) {
            row.transport = readTransport(body);
        }
        // JCLAW-1443: the operator setting the protocol ends the note that JClaw switched it, and
        // any edit to how the server is reached earns its probe another chance.
        var protocol = readProtocol(body);
        if (protocol != null && protocol != row.protocolSetting()) {
            row.protocol = protocol;
            row.protocolSwitchedAt = null;
            row.protocolSwitchReason = null;
            row.probeTimedOutAt = null;
        }
        // configJson rebuild: any of (transport, command, args, env, url, headers)
        // appearing in the body triggers a fresh compose. Cheaper than diffing.
        if (touchesTransportConfig(body)) {
            // Guarded on the row's transport rather than the body's: a body that omits
            // `transport` but carries a new `command` rewrites what an existing STDIO server
            // executes, which is the same authority as creating one.
            requireMainForStdio(row.transport);
            restoreMaskedSecrets(body, priorConfig);
            row.configJson = McpServerService.composeConfigJson(row.transport, body);
            row.probeTimedOutAt = null;
        }
        try {
            McpServerService.validate(row);
        } catch (IllegalArgumentException e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, ApiResponses.messageOf(e));
        }
        row.save();

        if (!priorName.equals(row.name)) {
            McpConnectionManager.stop(priorName);
            // No grant fix-up: JCLAW-983 keys them by this row's id, so the rename cannot
            // strand any. Restoring one here would reintroduce the coupling it removed.
        }
        McpServerService.syncRuntime(row);
        renderJSON(gson.toJson(McpServerService.View.of(row)));
    }

    @SuppressWarnings("java:S2259")
    private static void applyRenameIfPresent(McpServer row, JsonObject body) {
        var newName = JsonArgs.optString(body, "name");
        if (newName == null || newName.equals(row.name)) return;
        var existing = McpServer.findByName(newName);
        if (existing != null && !existing.id.equals(row.id)) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "An MCP server named '%s' already exists".formatted(newName));
        }
        row.name = newName;
    }

    @Operation(summary = "Disconnect and delete an MCP server by id")
    @AgentAccess(value = OPEN,
            reason = "removes a server an agent can equally add; DangerousActionGate bounds it")
    public static void delete(Long id) {
        var row = requireServer(id);
        // stop() handles every teardown concern: closes the McpClient,
        // unpublishes the tools from ToolRegistry, deletes allowlist rows,
        // and emits MCP_TOOL_UNREGISTER. Doing it here means even a failed
        // row.delete() leaves the runtime clean.
        McpConnectionManager.stop(row.name);
        row.delete();
        // The FK cascade takes this server's grant rows out from under Hibernate, which
        // cannot know its per-agent disabled-tool sets are now stale. stop() clears them
        // too, but only when the server was connected — a disabled one never reaches that.
        ToolRegistry.clearDisabledToolsCache();
        ApiResponses.ok("deleted", true);
    }

    @SuppressWarnings("java:S2259")
    @Operation(summary = "Test an MCP server connection by id (probe); returns success, toolCount, toolNames")
    @AgentAccess(value = OPEN, reason = "probes an already-configured server and adds no reach")
    public static void test(Long id) {
        var row = requireServer(id);
        var result = McpServerService.testConnection(row);
        renderJSON(gson.toJson(result));
    }

    /**
     * GET /api/mcp-servers/view — an MCP Apps view page, read from its server for the web chat's frame.
     * A page that cannot be served gets a short plain body under a sandbox, which the frame shows as
     * a view that never initializes.
     *
     * @param server the MCP server's name, as a tool call's {@code mcpApp} payload names it
     * @param uri    the page's {@code ui://} URI
     */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/html",
            schema = @Schema(implementation = String.class)))
    @Operation(summary = "Serve an MCP Apps view page (a ui:// resource) for the web chat, under its own sandboxing CSP")
    @AgentAccess(value = OPERATOR_ONLY, reason = "renders server-supplied HTML for the operator's browser")
    public static void view(@Nullable String server, @Nullable String uri) {
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        // Opened as a tab, the page would sit under JClaw's own address.
        var dest = request.headers.get("sec-fetch-dest");
        if (dest == null || !"iframe".equalsIgnoreCase(dest.value().strip())) {
            renderUnavailable(403, "A view page opens only inside the chat.");
        }
        McpAppPage.Page page;
        try {
            page = McpAppPage.load(server == null ? "" : server, uri == null ? "" : uri,
                    new McpAppPage.Host(request.domain));
        } catch (McpAppPage.Unavailable e) {
            renderUnavailable(e.status(), Objects.requireNonNullElse(e.getMessage(), "The view could not be loaded."));
            throw ApiResponses.unreachable();
        }
        response.setHeader("Content-Security-Policy", page.contentSecurityPolicy());
        response.contentType = "text/html; charset=utf-8";
        renderText(page.html());
    }

    /**
     * POST /api/mcp-servers/view/call — a tool call from an MCP Apps view in web chat, run on the view's own
     * server under the grants of the conversation's agent and audited as that agent's call. Answers
     * {@code {"result": <the CallToolResult for the view>}} or {@code {"error": <message>}}; the agent is not told.
     */
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ViewCallRequest.class)))
    @Operation(summary = "Run a tool for an MCP Apps view in web chat, on the view's own server, under the conversation agent's grants")
    @AgentAccess(value = OPERATOR_ONLY, reason = "a view acts on the operator's click in their own web chat")
    @NoTransaction
    public static void viewCall() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var conversationId = JsonArgs.optLong(body, "conversationId");
        if (conversationId == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Field 'conversationId' is required");
            throw ApiResponses.unreachable();
        }
        var server = JsonBodyReader.requiredOr400(body, "server");
        var tool = JsonBodyReader.requiredOr400(body, "tool");
        var arguments = body.get("arguments");
        if (arguments != null && !arguments.isJsonNull() && !arguments.isJsonObject()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Field 'arguments' must be an object");
        }
        // No request transaction: the grant check and its audit row commit before the call goes out, as for a model's call.
        var agent = Tx.run(() -> Conversation.<Conversation>findById(conversationId) instanceof Conversation c ? c.agent : null);
        if (agent == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        if (!McpViewTools.webChatTurn(conversationId)) {
            renderJSON(gson.toJson(viewError(VIEW_NOT_WEB_CHAT)));
        }
        renderJSON(gson.toJson(callForView(agent, server, tool,
                arguments instanceof JsonObject args ? args : new JsonObject())));
    }

    /**
     * GET /api/mcp-servers/elicitations — the forms MCP servers are waiting on the operator to answer in a web-chat
     * conversation, oldest first.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = McpElicitations.Question.class))))
    @Operation(summary = "List the MCP servers' forms waiting for the operator in a web-chat conversation")
    @AgentAccess(value = OPERATOR_ONLY, reason = "a server's question is the operator's to answer")
    public static void elicitations(@Nullable Long conversationId) {
        if (conversationId == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Field 'conversationId' is required", KEY_FIELD, "conversationId");
            throw ApiResponses.unreachable();
        }
        renderJSON(gson.toJson(McpElicitations.pendingFor(conversationId)));
    }

    /**
     * POST /api/mcp-servers/elicitations/{id} — the operator's answer to a server's form: {@code accept} with its
     * values, {@code decline} or {@code cancel}. An accept that fails the form's schema is a 400 naming the field,
     * and the form stays open; a form no longer waiting is a 404.
     */
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ElicitationAnswer.class)))
    @Operation(summary = "Answer an MCP server's form: accept with its values, decline or cancel")
    @AgentAccess(value = OPERATOR_ONLY, reason = "only the operator answers a server's question")
    public static void answerElicitation(String id) {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var action = body.get("action") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
        switch (McpElicitations.resolve(id, action, body.get("content"))) {
            case McpElicitations.Outcome.Answered(var taken) -> ApiResponses.ok("action", taken);
            case McpElicitations.Outcome.NotPending _ ->
                    ApiResponses.error(404, ApiResponses.NOT_FOUND, "This question is no longer waiting for an answer.");
            case McpElicitations.Outcome.Refused(var field, var message) ->
                    ApiResponses.error(400, ApiResponses.INVALID_REQUEST, message, KEY_FIELD, field);
        }
    }

    private static JsonObject callForView(Agent agent, String server, String tool, JsonObject arguments) {
        if (McpConnectionManager.status(server) != McpServer.Status.CONNECTED) return viewError(VIEW_NOT_CONNECTED);
        if (McpConnectionManager.requiresApproval(server)) return viewError(VIEW_NEEDS_APPROVAL);
        var def = McpConnectionManager.tools(server).stream()
                .filter(d -> d.name().equals(tool) && d.visibleToApp()).findFirst().orElse(null);
        if (def == null) return viewError(VIEW_NOT_FOR_APP);
        CallToolResult result;
        try {
            result = new McpToolAdapter(server, def, McpConnectionManager::callTool).callFromView(arguments, agent);
        } catch (IOException | RuntimeException e) {
            EventLogger.warn("MCP_APPS", "MCP server '%s' tool '%s' failed for its view: %s"
                    .formatted(server, tool, ApiResponses.messageOf(e)));
            return viewError(VIEW_CALL_FAILED);
        }
        if (result == null) return viewError(VIEW_NOT_ALLOWED);
        var out = new JsonObject();
        out.add("result", result.forView());
        return out;
    }

    private static JsonObject viewError(String message) {
        var out = new JsonObject();
        out.addProperty("error", message);
        return out;
    }

    /** One plain line under a sandbox, which a frame shows as a view that never initializes. */
    private static void renderUnavailable(int status, String message) {
        response.status = status;
        response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
        response.contentType = "text/plain; charset=utf-8";
        renderText(message);
    }

    // ==================== helpers ====================

    @SuppressWarnings("java:S2259")
    private static McpServer requireServer(Long id) {
        var row = McpServerService.findById(id);
        if (row != null) return row;
        notFound();
        throw new AssertionError("notFound() did not throw");
    }

    /**
     * A STDIO server is a command this JVM spawns, unconfined — {@code McpStdioTransport} builds
     * a bare {@code ProcessBuilder}, outside {@code HarnessSandbox} and outside the shell
     * allowlist. That is the operator's authority to delegate, and {@code main} holds it;
     * a custom agent gets HTTP transports only (JCLAW-1270).
     */
    private static void restoreMaskedSecrets(JsonObject body, McpServerService.TransportConfig stored) {
        var refusal = McpServerService.restoreMaskedSecrets(body, stored);
        if (refusal != null) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, refusal);
    }

    private static void requireMainForStdio(McpServer.Transport transport) {
        if (transport == McpServer.Transport.STDIO && !RequestPrincipal.isOperatorOrMainAgent()) {
            ApiResponses.error(403, ApiResponses.AGENT_SCOPE,
                    "A STDIO MCP server runs a local command; only the operator or the main agent "
                            + "may configure one. Use an HTTP transport, or ask the operator.");
        }
    }

    @SuppressWarnings("java:S2259")
    private static McpServer.Transport readTransport(JsonObject body) {
        var raw = JsonBodyReader.requiredOr400(body, KEY_TRANSPORT);
        try {
            return McpServer.Transport.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException _) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Unknown transport '%s' (expected STDIO or HTTP)".formatted(raw));
            throw ApiResponses.unreachable();
        }
    }

    /** Which channels see a server's view tools is the operator's to change; an unchanged echo is no change. */
    private static void requireOperatorForViewToolsChange(boolean current, @Nullable Boolean requested) {
        if (requested == null || requested == current) return;
        if (RequestPrincipal.isAgentOriginated()) {
            ApiResponses.error(403, ApiResponses.OPERATOR_ONLY,
                    "offerViewToolsEverywhere is operator-only. A server's view tools are already offered in the operator's "
                            + "web chat; this setting only extends them to other channels, tasks and subagents.");
        }
    }

    /** The body's view-tools setting, or null when it carries none; anything but a JSON boolean is a 400. */
    private static @Nullable Boolean readViewToolsEverywhere(JsonObject body) {
        var el = body.get(KEY_VIEW_TOOLS_EVERYWHERE);
        if (el == null || el.isJsonNull()) return null;
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, KEY_VIEW_TOOLS_EVERYWHERE + " must be true or false");
        }
        return el.getAsBoolean();
    }

    /** The body's protocol setting, or null when it carries none. */
    @SuppressWarnings("java:S2259")
    private static McpServer.@Nullable Protocol readProtocol(JsonObject body) {
        var raw = JsonArgs.optString(body, KEY_PROTOCOL);
        if (raw == null) return null;
        try {
            return McpServer.Protocol.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException _) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Unknown protocol '%s' (expected AUTOMATIC or HANDSHAKE)".formatted(raw));
            throw ApiResponses.unreachable();
        }
    }

    private static boolean touchesTransportConfig(JsonObject body) {
        return body.has(KEY_TRANSPORT) || body.has("command") || body.has("args")
                || body.has("env") || body.has("url") || body.has("headers");
    }
}
