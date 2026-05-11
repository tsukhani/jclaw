package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import mcp.McpConnectionManager;
import models.McpServer;
import play.mvc.Controller;
import play.mvc.With;
import services.McpServerService;

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
 * Api* controller. The {@code mcp_server} table is operator-level and
 * has no per-user concept.
 */
@With(AuthCheck.class)
public class ApiMcpServersController extends Controller {

    private static final Gson gson = INSTANCE;

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = McpServerService.View.class))))
    public static void list() {
        renderJSON(gson.toJson(McpServerService.listAll()));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = McpServerService.View.class)))
    public static void get(Long id) {
        var row = requireServer(id);
        renderJSON(gson.toJson(McpServerService.View.of(row)));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = McpServerService.View.class)))
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
        row.enabled = body.has("enabled") && !body.get("enabled").isJsonNull()
                ? body.get("enabled").getAsBoolean() : true;
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

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = McpServerService.View.class)))
    public static void update(Long id) {
        var row = requireServer(id);
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        // Renaming is allowed; if it happens we tear down the prior connection
        // (under the OLD name) before re-syncing under the new one. Otherwise
        // McpConnectionManager would carry a stale entry forever.
        var priorName = row.name;
        if (body.has("name") && !body.get("name").isJsonNull()) {
            var newName = body.get("name").getAsString();
            if (!newName.equals(row.name)) {
                var existing = McpServer.findByName(newName);
                if (existing != null && !existing.id.equals(row.id)) {
                    error(409, "An MCP server named '%s' already exists".formatted(newName));
                }
                row.name = newName;
            }
        }
        if (body.has("enabled") && !body.get("enabled").isJsonNull()) {
            row.enabled = body.get("enabled").getAsBoolean();
        }
        if (body.has("transport") && !body.get("transport").isJsonNull()) {
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

    public static void test(Long id) {
        var row = requireServer(id);
        var result = McpServerService.testConnection(row);
        renderJSON(gson.toJson(result));
    }

    // ==================== helpers ====================

    private static McpServer requireServer(Long id) {
        var row = (McpServer) McpServer.findById(id);
        if (row == null) notFound();
        return row;
    }

    private static String readRequiredString(com.google.gson.JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) {
            error(400, "Field '%s' is required".formatted(key));
        }
        var s = body.get(key).getAsString();
        if (s.isBlank()) error(400, "Field '%s' must not be blank".formatted(key));
        return s;
    }

    private static McpServer.Transport readTransport(com.google.gson.JsonObject body) {
        var raw = readRequiredString(body, "transport");
        try {
            return McpServer.Transport.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            error(400, "Unknown transport '%s' (expected STDIO or HTTP)".formatted(raw));
            return null;  // unreachable; error() throws
        }
    }

    private static boolean touchesTransportConfig(com.google.gson.JsonObject body) {
        return body.has("transport") || body.has("command") || body.has("args")
                || body.has("env") || body.has("url") || body.has("headers");
    }
}
