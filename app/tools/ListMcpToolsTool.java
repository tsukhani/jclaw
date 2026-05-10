package tools;

import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.McpConnectionManager;
import models.Agent;

import java.util.List;
import java.util.Map;

/**
 * Discovery entrypoint for MCP tools (Phase 2 of the system-prompt
 * bloat fix). Native tools always ship their schemas in the request's
 * tools array; MCP tools are gated on this tool having been called for
 * their server in the current conversation.
 *
 * <p>The conversation history is the source of truth — calling this
 * tool with {@code {"server": "<name>"}} and receiving its result is
 * what unlocks {@code <name>}'s tool schemas for the remainder of the
 * conversation. {@link mcp.McpDiscovery} reads the unlock state by
 * scanning the assistant's prior tool-call records, so there's no
 * persistent state to manage here.
 *
 * <p>Returned shape mirrors the MCP {@code tools/list} response so the
 * model recognizes it: a JSON array of {@code {name, description,
 * inputSchema}} objects. Names are pre-prefixed with
 * {@code mcp_<server>_} (the form the agent loop dispatches on), so
 * the model can call them directly on the next turn without further
 * mangling.
 */
public class ListMcpToolsTool implements ToolRegistry.Tool {

    @Override
    public String name() { return mcp.McpDiscovery.DISCOVERY_TOOL_NAME; }

    @Override
    public String description() {
        return "Discover the tools advertised by a connected MCP server. "
             + "Call this BEFORE invoking any mcp_<server>_<tool> to load the server's "
             + "tool schemas into the conversation. Returns a JSON array of "
             + "{name, description, inputSchema} for every tool the named server "
             + "currently advertises. Once called for a given server, that server's "
             + "tools become directly callable by name for the rest of this conversation.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "server", Map.of(
                                "type", "string",
                                "description", "MCP server name (matches the name column in /mcp-servers, e.g., 'jira-confluence')"
                        )
                ),
                "required", List.of("server"),
                "additionalProperties", false
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson).getAsJsonObject();
        }
        catch (RuntimeException e) {
            return "Error: arguments are not valid JSON: " + e.getMessage();
        }
        if (!args.has("server") || args.get("server").isJsonNull()) {
            return "Error: required 'server' field is missing.";
        }
        var server = args.get("server").getAsString();
        var defs = McpConnectionManager.tools(server);
        if (defs.isEmpty()) {
            var status = McpConnectionManager.status(server);
            return "Error: MCP server '" + server + "' is " + status
                 + " with 0 tools advertised. Enable the server in /mcp-servers, "
                 + "wait for it to reach CONNECTED, then retry.";
        }
        var arr = new JsonArray();
        for (var def : defs) {
            var obj = new JsonObject();
            obj.addProperty("name", "mcp_" + server + "_" + def.name());
            obj.addProperty("description", def.description());
            obj.add("inputSchema", def.inputSchema());
            arr.add(obj);
        }
        return arr.toString();
    }

    @Override public String summary() {
        return "Discover the tools advertised by a connected MCP server. Call this once per server before invoking any mcp_<server>_* tool.";
    }

    @Override public String shortDescription() { return summary(); }

    @Override public String category() { return "System"; }

    @Override public String icon() { return "search"; }

    /** Read-only metadata fetch — multiple parallel calls (e.g., for two
     *  different servers in one round) can race freely. */
    @Override public boolean parallelSafe() { return true; }

    /** Always-on, hidden from per-agent toggling. The lazy-discovery
     *  contract assumes this tool is callable whenever any MCP server
     *  is enabled — letting an operator disable it per-agent would
     *  break MCP entirely for that agent (server enabled → catalog
     *  row visible → model tries to discover → call fails). System
     *  tier removes the toggle and the foot-gun together. */
    @Override public boolean isSystem() { return true; }
}
