package agents;

import mcp.McpConnectionManager;
import mcp.McpServerTool;
import mcp.McpViewTools;
import models.Agent;
import models.McpServer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * JCLAW-281: builds the {@code ## MCP Servers} system-prompt manifest — the
 * operator-readable index of MCP servers attached to an agent, parallel to
 * the {@link ToolCatalog} for native tools. One row per connected server
 * with name, action-count hint, and the invocation convention. Rendered
 * whenever {@code ≥1} server is connected for this agent, independent of
 * whether the agent has skills attached.
 *
 * <p>The function-calling schema delivers each server as a parameterized
 * {@link McpServerTool} entry; this catalog tells the model what those
 * entries are <em>for</em> in human-readable form, so a model that follows
 * the prompt rather than introspecting the schema still finds the servers.
 */
public final class McpServerCatalog {

    private McpServerCatalog() {}

    /**
     * Build the catalog for {@code agent}, honoring its disabled-tools set
     * so an operator who's turned off a particular MCP server for this
     * agent doesn't see it advertised. Returns the empty string when no
     * server-level handles are reachable, so callers can omit the whole
     * section without emitting a stray header.
     */
    public static String formatCatalogForPrompt(Agent agent) {
        return formatCatalogForPrompt(ToolRegistry.loadDisabledTools(agent));
    }

    /**
     * Pure variant taking a pre-loaded disabled-tools set; the hot
     * streaming path passes one set through both this catalog and the
     * function-calling defs to avoid a redundant DB query per turn.
     */
    public static String formatCatalogForPrompt(Set<String> disabledForAgent) {
        return formatCatalogForPrompt(disabledForAgent, () -> false);
    }

    /**
     * {@link #formatCatalogForPrompt(Set)} for a turn that is ({@code webChatTurn}) or is not a web-chat
     * turn of a top-level conversation, where a server's tools that link a view are counted. The turn is
     * asked once, and only when a server has view tools.
     */
    public static String formatCatalogForPrompt(Set<String> disabledForAgent, BooleanSupplier webChatTurn) {
        var once = McpViewTools.once(webChatTurn);
        // Walk the registry directly. Each connected MCP server publishes
        // exactly one server-level handle (isServerLevel() == true) via
        // McpConnectionManager.republishTools, so iterating the registry
        // for those handles is the authoritative live view. Group key
        // gives us a stable per-server identifier even when a server
        // hasn't yet been queried for its action list.
        var serverHandles = new LinkedHashMap<String, ToolRegistry.Tool>();
        for (var tool : ToolRegistry.listTools()) {
            if (tool.isServerLevel() && tool.group() != null && !disabledForAgent.contains(tool.name())) {
                serverHandles.put(tool.group(), tool);
            }
        }
        if (serverHandles.isEmpty()) return "";

        // Load all McpServer rows once instead of one findByName per row while
        // building the manifest — a single DB roundtrip keyed by name.
        var serverRows = loadServerRows();

        var sb = new StringBuilder();
        sb.append("| Server | Actions | Description |\n");
        sb.append("|---|---|---|\n");
        for (var entry : serverHandles.entrySet()) {
            var serverName = entry.getKey();
            var handle = entry.getValue();
            var actionCount = countActions(serverName, once);
            sb.append("| `").append(handle.name()).append("` | ")
              .append(actionCountLabel(actionCount)).append(" | ")
              .append(describeServer(serverRows.get(serverName), handle))
              .append(" |\n");
        }
        return sb.toString();
    }

    /** Snapshot every {@link McpServer} row into a name→row map in one query. */
    private static Map<String, McpServer> loadServerRows() {
        var rows = new HashMap<String, McpServer>();
        for (McpServer s : McpServer.<McpServer>findAll()) {
            rows.put(s.name, s);
        }
        return rows;
    }

    /** {@code <0} means "not yet enumerated"; the server is connected but
     *  the registry doesn't have a tools-list response cached yet. */
    private static int countActions(String serverName, BooleanSupplier webChatTurn) {
        var defs = McpConnectionManager.toolsForTurn(serverName);
        return defs == null ? -1 : McpViewTools.offered(serverName, defs, webChatTurn).size();
    }

    private static String actionCountLabel(int count) {
        if (count < 0) return "lazy, call to enumerate";
        if (count == 1) return "1 action";
        return count + " actions";
    }

    /**
     * Per-server description for the catalog row. Falls back to a generic
     * line for servers without an admin-supplied description. The {@code server}
     * row is looked up once by the caller from a batch-loaded map.
     */
    private static String describeServer(@Nullable McpServer server, ToolRegistry.Tool handle) {
        if (server != null && server.transport != null) {
            // No description column on McpServer today; render transport
            // shape as a hint so the operator can still tell HTTP vs stdio
            // sources apart in the rendered prompt.
            return "MCP server via " + server.transport.name().toLowerCase() + ". "
                    + invocationHint(handle);
        }
        return handle.summary() == null ? invocationHint(handle) : handle.summary();
    }

    private static String invocationHint(ToolRegistry.Tool handle) {
        var hint = "Call `" + handle.name()
                + "` with no arguments to enumerate available actions; "
                + "call with `{\"tool\": \"<action>\", \"args\": {...}}` to execute one.";
        var server = handle.group();
        if (server == null || !McpConnectionManager.offersResources(server)) return hint;
        return hint + " It also lists resources: call with `{\"read\": \"<uri>\"}` to read one.";
    }

    /** Whether any server in the catalog for this disabled set declared the {@code resources} capability. */
    public static boolean anyOffersResources(Set<String> disabledForAgent) {
        for (var server : serversForAgent(disabledForAgent)) {
            if (McpConnectionManager.offersResources(server)) return true;
        }
        return false;
    }

    /**
     * Convenience: returns the list of connected server names that would
     * appear in the catalog for {@code agent}, in the same order as the
     * markdown rows. Used by the PromptBreakdown so the byte footprint of
     * the manifest can be reported as its own line item.
     */
    public static List<String> serversForAgent(Agent agent) {
        return serversForAgent(ToolRegistry.loadDisabledTools(agent));
    }

    public static List<String> serversForAgent(Set<String> disabledForAgent) {
        var out = new ArrayList<String>();
        for (var tool : ToolRegistry.listTools()) {
            if (tool.isServerLevel() && tool.group() != null
                    && !disabledForAgent.contains(tool.name())
                    && !out.contains(tool.group())) {
                out.add(tool.group());
            }
        }
        return out;
    }
}
