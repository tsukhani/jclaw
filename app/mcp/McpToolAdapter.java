package mcp;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.EventLog;
import services.Tx;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wraps one MCP tool as a {@link ToolRegistry.Tool} so the agent loop sees
 * it as a normal tool (JCLAW-31).
 *
 * <p>Naming convention: {@code mcp_<server>_<tool>}. The flat-namespace
 * underscore form keeps the result a valid identifier across LLM tool-call
 * schemas (some OpenAI-style schemas reject {@code .}); the {@code mcp_}
 * prefix makes MCP origins visible in logs and the admin UI without a
 * separate column.
 *
 * <p>{@link #parallelSafe()} returns {@code false} — we don't know what
 * an arbitrary MCP server's tools do under concurrent invocation, and the
 * AgentRunner default already serializes calls to one tool within a single
 * round per the JCLAW-80 scheduler. Conservative is correct.
 */
public final class McpToolAdapter implements ToolRegistry.Tool {

    private final String serverName;
    private final McpToolDef def;
    private final ToolInvoker invoker;

    /** Functional indirection so the adapter doesn't need a back-pointer to
     *  the connection manager — the manager supplies a Lambda at registration. */
    @FunctionalInterface
    public interface ToolInvoker {
        CallToolResult invoke(String serverName, String toolName, JsonObject arguments) throws Exception;
    }

    public McpToolAdapter(String serverName, McpToolDef def, ToolInvoker invoker) {
        this.serverName = serverName;
        this.def = def;
        this.invoker = invoker;
    }

    @Override
    public String name() {
        return "mcp_" + serverName + "_" + def.name();
    }

    @Override
    public String description() {
        return def.description();
    }

    @Override
    public Map<String, Object> parameters() {
        return def.parametersAsMap();
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        JsonObject args;
        try {
            var parsed = JsonParser.parseString(argsJson);
            args = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return "Error parsing arguments for MCP tool '%s': %s".formatted(name(), e.getMessage());
        }
        // JCLAW-32: gate + audit in one atomic Tx. The check (isAllowed) and
        // the audit log (MCP_TOOL_INVOKE) MUST commit together so an attacker
        // who somehow bypasses the in-memory ToolRegistry can't fabricate a
        // grant the audit trail wouldn't show. The actual MCP server call
        // happens AFTER commit — we don't hold a DB tx across the network.
        boolean allowed;
        try {
            allowed = Tx.run(() -> {
                var managed = (Agent) (agent == null || agent.id == null ? null
                        : Agent.findById(agent.id));
                boolean grant = managed != null
                        && McpAllowlist.isAllowed(managed, serverName, def.name());
                writeInvokeAudit(agent, argsJson, grant);
                return grant;
            });
        } catch (RuntimeException e) {
            return "Error checking MCP allowlist for '%s': %s".formatted(name(), e.getMessage());
        }
        if (!allowed) {
            return "MCP tool '%s' is not on the allowlist for agent '%s'. "
                   .formatted(name(), agent != null ? agent.name : "<unknown>")
                   + "Connect the server or grant the agent first.";
        }
        try {
            var result = invoker.invoke(serverName, def.name(), args);
            if (result.isError()) {
                return "MCP tool '%s' reported error: %s".formatted(name(), result.content());
            }
            return result.content();
        } catch (Exception e) {
            return "Error invoking MCP tool '%s': %s".formatted(name(), e.getMessage());
        }
    }

    /** Inline audit-log row save (bypasses {@link services.EventLogger}'s
     *  async queue) so the row commits in the same Tx as the allowlist check.
     *  The AC requires both to land atomically. */
    private void writeInvokeAudit(Agent agent, String argsJson, boolean allowed) {
        var ev = new EventLog();
        ev.timestamp = Instant.now();
        ev.level = allowed ? "INFO" : "WARN";
        ev.category = "MCP_TOOL_INVOKE";
        var agentName = agent != null && agent.name != null ? agent.name : "<unknown>";
        ev.agentId = agent != null && agent.id != null ? String.valueOf(agent.id) : null;
        ev.message = "%s/%s by '%s' [%s]".formatted(
                serverName, def.name(), agentName, allowed ? "allowed" : "denied");
        ev.details = argsJson;
        ev.save();
    }

    @Override
    public String summary() {
        return def.description();
    }

    @Override
    public String category() {
        return "MCP";
    }

    @Override
    public String icon() {
        return "plug";
    }

    @Override
    public String shortDescription() {
        return def.description();
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction(def.name(), def.description()));
    }

    /** Group all tools from one MCP server under a single /tools-page card
     *  (JCLAW-33 follow-up). Card title becomes the server name; each
     *  MCP-discovered tool's inner name + description folds into the
     *  Functions disclosure. The LLM-facing tool catalog is unaffected:
     *  every {@link McpToolAdapter} remains its own callable entry with
     *  its own schema. */
    @Override
    public String group() { return serverName; }
}
