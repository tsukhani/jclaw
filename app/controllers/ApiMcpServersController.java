package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import mcp.McpConnectionManager;
import models.McpServer;
import play.mvc.Controller;
import play.mvc.With;
import services.McpServerService;

import java.util.List;
import java.util.Map;

import static utils.GsonHolder.INSTANCE;

/**
 * Admin CRUD over the {@code mcp_server} table (JCLAW-33).
 *
 * <p>Six endpoints:
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
 * </ul>
 *
 * <p>Auth: class-level {@code @With(AuthCheck.class)} mirrors every other
 * Api* controller. The {@code mcp_server} table is operator-wide — single-operator
 * Personal Edition, so there is no per-user scoping.
 */
@With(AuthCheck.class)
public class ApiMcpServersController extends Controller {

    private static final Gson gson = INSTANCE;

    public record McpServerRequest(String name, Boolean enabled, Boolean requiresApproval,
                                   String transport, String command, List<String> args,
                                   Map<String, String> env, String url,
                                   Map<String, String> headers) {}

    // JSON body keys reused across create/update parsers.
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TRANSPORT = "transport";
    private static final String KEY_REQUIRES_APPROVAL = "requiresApproval";

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = McpServerService.View.class))))
    @Operation(summary = "List MCP servers with status and tool count")
    public static void list() {
        renderJSON(gson.toJson(McpServerService.listAll()));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = McpServerService.View.class)))
    @Operation(summary = "Get a single MCP server by id")
    public static void get(Long id) {
        var row = requireServer(id);
        renderJSON(gson.toJson(McpServerService.View.of(row)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = McpServerService.View.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = McpServerRequest.class)))
    @Operation(summary = "Add an MCP server (STDIO or HTTP)")
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var name = readRequiredString(body, "name");
        if (McpServer.findByName(name) != null) {
            error(409, "An MCP server named '%s' already exists".formatted(name));
        }
        var transport = readTransport(body);
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
        row.transport = transport;
        row.configJson = McpServerService.composeConfigJson(transport, body);
        try {
            McpServerService.validate(row);
        } catch (IllegalArgumentException e) {
            error(400, e.getMessage());
        }
        row.save();

        McpServerService.syncRuntime(row);
        renderJSON(gson.toJson(McpServerService.View.of(row)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = McpServerService.View.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = McpServerRequest.class)))
    @Operation(summary = "Update an MCP server by id; it reconnects automatically")
    public static void update(Long id) {
        var row = requireServer(id);
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        // Renaming is allowed; if it happens we tear down the prior connection
        // (under the OLD name) before re-syncing under the new one. Otherwise
        // McpConnectionManager would carry a stale entry forever.
        var priorName = row.name;
        applyRenameIfPresent(row, body);
        if (body.has(KEY_ENABLED) && !body.get(KEY_ENABLED).isJsonNull()) {
            row.enabled = body.get(KEY_ENABLED).getAsBoolean();
        }
        // JCLAW-388: only mutate the approval flag when the key is present —
        // a partial update that omits it leaves the persisted value intact.
        if (body.has(KEY_REQUIRES_APPROVAL) && !body.get(KEY_REQUIRES_APPROVAL).isJsonNull()) {
            row.requiresApproval = body.get(KEY_REQUIRES_APPROVAL).getAsBoolean();
        }
        if (body.has(KEY_TRANSPORT) && !body.get(KEY_TRANSPORT).isJsonNull()) {
            row.transport = readTransport(body);
        }
        // configJson rebuild: any of (transport, command, args, env, url, headers)
        // appearing in the body triggers a fresh compose. Cheaper than diffing.
        if (touchesTransportConfig(body)) {
            row.configJson = McpServerService.composeConfigJson(row.transport, body);
        }
        try {
            McpServerService.validate(row);
        } catch (IllegalArgumentException e) {
            error(400, e.getMessage());
        }
        row.save();

        if (!priorName.equals(row.name)) {
            // Renamed. Disconnect under the old name FIRST so the registry
            // doesn't carry both. syncRuntime then connects under the new name.
            McpConnectionManager.stop(priorName);
        }
        McpServerService.syncRuntime(row);
        renderJSON(gson.toJson(McpServerService.View.of(row)));
    }

    @SuppressWarnings("java:S2259")
    private static void applyRenameIfPresent(McpServer row, JsonObject body) {
        if (!body.has("name") || body.get("name").isJsonNull()) return;
        var newName = body.get("name").getAsString();
        if (newName.equals(row.name)) return;
        var existing = McpServer.findByName(newName);
        if (existing != null && !existing.id.equals(row.id)) {
            error(409, "An MCP server named '%s' already exists".formatted(newName));
        }
        row.name = newName;
    }

    @Operation(summary = "Disconnect and delete an MCP server by id")
    public static void delete(Long id) {
        var row = requireServer(id);
        // stop() handles every teardown concern: closes the McpClient,
        // unpublishes the tools from ToolRegistry, deletes allowlist rows,
        // and emits MCP_TOOL_UNREGISTER. Doing it here means even a failed
        // row.delete() leaves the runtime clean.
        McpConnectionManager.stop(row.name);
        row.delete();
        renderJSON("{\"deleted\":true}");
    }

    @SuppressWarnings("java:S2259")
    @Operation(summary = "Test an MCP server connection by id (probe); returns success, toolCount, toolNames")
    public static void test(Long id) {
        var row = requireServer(id);
        var result = McpServerService.testConnection(row);
        renderJSON(gson.toJson(result));
    }

    // ==================== helpers ====================

    @SuppressWarnings("java:S2259")
    private static McpServer requireServer(Long id) {
        var row = (McpServer) McpServer.findById(id);
        if (row != null) return row;
        notFound();
        throw new AssertionError("notFound() did not throw");
    }

    @SuppressWarnings("java:S2259")
    private static String readRequiredString(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) {
            error(400, "Field '%s' is required".formatted(key));
        }
        var s = body.get(key).getAsString();
        if (s.isBlank()) error(400, "Field '%s' must not be blank".formatted(key));
        return s;
    }

    @SuppressWarnings("java:S2259")
    private static McpServer.Transport readTransport(JsonObject body) {
        var raw = readRequiredString(body, KEY_TRANSPORT);
        try {
            return McpServer.Transport.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException _) {
            error(400, "Unknown transport '%s' (expected STDIO or HTTP)".formatted(raw));
            return null;  // unreachable; error() throws
        }
    }

    private static boolean touchesTransportConfig(JsonObject body) {
        return body.has(KEY_TRANSPORT) || body.has("command") || body.has("args")
                || body.has("env") || body.has("url") || body.has("headers");
    }
}
