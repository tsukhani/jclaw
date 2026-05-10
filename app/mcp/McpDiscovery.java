package mcp;

import com.google.gson.JsonParser;
import models.Conversation;
import models.Message;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives the set of MCP servers the model has explicitly discovered in
 * a conversation by scanning prior assistant {@code list_mcp_tools} calls.
 *
 * <p>Used by {@link agents.ToolRegistry#getToolDefsForAgent(models.Agent, Conversation)}
 * to gate which MCP tool schemas ship in each turn's tools array — the
 * lazy half of the system-prompt-bloat fix. Phase 1 (catalog markdown
 * collapse) keeps the operator-readable index lean; this gates the
 * machine-readable schemas that travel separately to the LLM provider.
 *
 * <p>Stateless by design. No new schema column, no in-memory map keyed
 * by conversation id — the conversation's own message history is the
 * single source of truth, so the discovered set survives restarts and
 * matches whatever's actually in the LLM's context.
 */
public final class McpDiscovery {

    /** Tool name of the discovery entrypoint, registered as a native
     *  tool by {@link jobs.ToolRegistrationJob}. Calls to this tool
     *  with a {@code server} argument unlock that server's full schemas
     *  for the rest of the conversation. */
    public static final String DISCOVERY_TOOL_NAME = "list_mcp_tools";

    private McpDiscovery() {}

    /**
     * Walk the conversation's message history looking for prior assistant
     * tool calls to {@code list_mcp_tools}. Returns the set of server
     * names extracted from each call's {@code arguments.server} field.
     * Malformed JSON is ignored — best-effort parsing.
     */
    public static Set<String> discoveredServers(Conversation conv) {
        if (conv == null || conv.id == null) return Set.of();
        return discoveredServers(Message.findRecent(conv, Integer.MAX_VALUE));
    }

    /** Pure variant: derive the discovered set from a pre-loaded message
     *  list. Useful when the caller already has messages in scope and
     *  doesn't want a redundant DB read. */
    public static Set<String> discoveredServers(List<Message> messages) {
        var discovered = new HashSet<String>();
        if (messages == null) return discovered;
        for (var m : messages) {
            if (!"assistant".equals(m.role)) continue;
            if (m.toolCalls == null || m.toolCalls.isBlank()) continue;
            try {
                var arr = JsonParser.parseString(m.toolCalls).getAsJsonArray();
                for (var el : arr) {
                    if (!el.isJsonObject()) continue;
                    var fn = el.getAsJsonObject().getAsJsonObject("function");
                    if (fn == null || !fn.has("name") || !fn.has("arguments")) continue;
                    if (!DISCOVERY_TOOL_NAME.equals(fn.get("name").getAsString())) continue;
                    // arguments is itself a JSON-encoded STRING (per OpenAI tool-call shape).
                    var argsStr = fn.get("arguments").getAsString();
                    var args = JsonParser.parseString(argsStr).getAsJsonObject();
                    if (args.has("server") && !args.get("server").isJsonNull()) {
                        discovered.add(args.get("server").getAsString());
                    }
                }
            } catch (RuntimeException ignored) { /* skip malformed call rows */ }
        }
        return discovered;
    }
}
