package mcp;

import agents.ToolRegistry;
import models.Agent;
import models.AgentToolConfig;
import models.McpServer;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;

/**
 * How a per-agent grant row addresses the tool it grants (JCLAW-983).
 *
 * <p>An MCP tool's name is built from its server's name, so keying the grant row by that
 * name made the join between the two a string prefix that nothing maintained: a rename
 * stranded every grant and a delete left them behind. JCLAW-982 patched both with explicit
 * handlers; this keys the row by the server's id instead, so a rename writes no row at all
 * and a delete cascades in the database.
 *
 * <p>Native tools are unaffected and stay keyed by name — they are the fallback here, taken
 * whenever a tool names no MCP server.
 */
public final class McpGrants {

    private McpGrants() {}

    /** The existing grant row for {@code toolName}, or {@code null}. */
    public static AgentToolConfig find(Agent agent, String toolName) {
        var server = serverFor(toolName);
        if (server == null) return AgentToolConfig.findByAgentAndTool(agent, toolName);
        return AgentToolConfig.find("agent = ?1 AND mcpServer = ?2 AND mcpAction = ?3",
                agent, server, actionOf(toolName, server.name)).first();
    }

    /**
     * An unsaved grant row addressing {@code toolName} — by {@code (server, action)} when the
     * name resolves to a live MCP tool, by name otherwise. The caller sets {@code enabled}
     * and saves.
     */
    public static AgentToolConfig newRow(Agent agent, String toolName) {
        var row = new AgentToolConfig();
        row.agent = agent;
        assign(row, toolName, serverFor(toolName));
        return row;
    }

    /**
     * Grant {@code agent} every tool in {@code tools}, resolving each MCP server once rather
     * than once per action — a server advertising 280 actions is the ordinary case, and this
     * runs on the subagent-spawn path.
     */
    public static void grantAll(Agent agent, List<ToolRegistry.Tool> tools) {
        var servers = new HashMap<String, McpServer>();
        for (var tool : tools) {
            var row = new AgentToolConfig();
            row.agent = agent;
            assign(row, tool.name(),
                    tool.group() == null ? null : servers.computeIfAbsent(tool.group(), McpServer::findByName));
            row.enabled = true;
            row.save();
        }
    }

    /** Populate exactly one of the two addressing forms and clear the other. */
    private static void assign(AgentToolConfig row, String toolName, @Nullable McpServer server) {
        row.toolName = server == null ? toolName : null;
        row.mcpServer = server;
        row.mcpAction = server == null ? null : actionOf(toolName, server.name);
    }

    /**
     * The MCP server {@code toolName} belongs to, or {@code null} when it names none. Resolved
     * through the live registry rather than by parsing the name, so a server whose name is a
     * prefix of another's cannot claim its tools.
     */
    private static @Nullable McpServer serverFor(String toolName) {
        var tool = ToolRegistry.lookupTool(toolName);
        if (tool == null || tool.group() == null) return null;
        return McpServer.findByName(tool.group());
    }

    /** The action within {@code serverName}; empty for the server-level handle. */
    static String actionOf(String toolName, String serverName) {
        var handle = McpServer.toolName(serverName, "");
        return toolName.equals(handle) ? "" : toolName.substring(handle.length() + 1);
    }
}
