package mcp;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.McpServer;
import utils.GsonHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-281: server-level handle that exposes an entire MCP server as ONE
 * parameterized {@link ToolRegistry.Tool} to the LLM, instead of N per-action
 * wrappers. The function-calling schema sent to the model now contains a
 * single entry per connected server (name {@code mcp_<server>}, parameters
 * {@code {tool, args}}); the per-action {@link McpToolAdapter} registrations
 * remain in the registry as the internal execution path but are hidden from
 * the function-calling defs via {@link ToolRegistry.Tool#isServerLevel()}.
 *
 * <p><b>Discovery is implicit.</b> When the model invokes {@code mcp_<server>}
 * with no {@code tool} argument, this returns a JSON catalog of the server's
 * available actions including each action's input schema; the model uses that
 * schema to construct subsequent populated calls. When the model invokes with
 * a {@code tool} field, this delegates to the corresponding {@code McpToolAdapter}
 * (preserving its allowlist gate, audit trail, and error handling) so server-
 * level invocations and any legacy direct-action invocations share the same
 * execution path.
 *
 * <p>Removes the need for a dedicated {@code list_mcp_tools} discovery tool —
 * discovery is now part of every server's own surface, consistent across all
 * models regardless of whether they introspect the function-calling schema or
 * follow the system-prompt catalog.
 */
public final class McpServerTool implements ToolRegistry.Tool {

    private static final Gson GSON = GsonHolder.GSON;

    private static final String FIELD_DESCRIPTION = "description";

    private final String serverName;

    public McpServerTool(String serverName) {
        this.serverName = serverName;
    }

    @Override
    public String name() {
        return McpServer.toolName(serverName, "");
    }

    @Override
    public String description() {
        return "MCP server `" + serverName + "`. "
                + "Call with no arguments (or `{}`) to enumerate the server's "
                + "available actions and their input schemas. "
                + "Call with `{\"tool\": \"<action>\", \"args\": {...}}` to "
                + "execute one action against the server.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "tool", Map.of(
                                "type", "string",
                                FIELD_DESCRIPTION, "The action to execute. Omit to enumerate available actions."),
                        "args", Map.of(
                                "type", "object",
                                FIELD_DESCRIPTION, "Arguments for the action, matching its input schema.",
                                "additionalProperties", true)
                ),
                "required", List.of());
    }

    /** Always {@code true} — this is the server-level handle that surfaces in
     *  the function-calling defs sent to the LLM. The per-action adapters
     *  registered alongside it return {@code false}. */
    @Override
    public boolean isServerLevel() { return true; }

    /** Share the per-server group so the admin UI's tool catalog continues
     *  to render one card per MCP server with its actions folded inside. */
    @Override
    public String group() { return serverName; }

    @Override
    public String category() { return "MCP"; }

    @Override
    public String icon() { return "plug"; }

    @Override
    public String summary() {
        return "MCP server `" + serverName + "`. Discovery and action dispatch live behind this single handle.";
    }

    @Override
    public String shortDescription() {
        return "Atomic handle for the `" + serverName + "` MCP server. "
                + "Empty-args invocations enumerate; populated invocations route to the action.";
    }

    /** Surface every discovered action as an admin-UI sub-entry so the
     *  /tools page card still shows what's available without the LLM seeing
     *  N separate function-calling defs. */
    @Override
    public List<ToolAction> actions() {
        var defs = McpConnectionManager.tools(serverName);
        if (defs == null || defs.isEmpty()) return List.of();
        var out = new ArrayList<ToolAction>(defs.size());
        for (var def : defs) out.add(new ToolAction(def.name(), def.description()));
        return out;
    }

    /** Network-bound and stateless from this Tool's perspective; concurrent
     *  invocations within a single round (each with a different {@code tool})
     *  are safe to race. Per-action serialization, if any, is the MCP
     *  server's own concern. */
    @Override
    public boolean parallelSafe() { return true; }

    /** JCLAW-388: surface the server's {@code requiresApproval} flag as this
     *  handle's {@link ToolRegistry.Tool#dangerous() dangerous} state. This is
     *  the live LLM-facing entry for the server (per JCLAW-281 the per-action
     *  adapters are hidden from the function-calling defs), so the wire-format
     *  name {@link agents.DangerousActionGate} sees is {@code mcp_<server>} — resolving
     *  the flag here is what makes an approval-flagged server's calls raise the
     *  same approve/deny prompt as {@code exec}. The lookup reads the in-memory
     *  connection entry (no DB hit on the dispatch path). */
    @Override
    public boolean dangerous() {
        return McpConnectionManager.requiresApproval(serverName);
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        return executeRich(argsJson, agent).text();
    }

    @Override
    public ToolRegistry.ToolResult executeRich(String argsJson, Agent agent) {
        JsonObject args;
        try {
            var parsed = JsonParser.parseString(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            args = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return ToolRegistry.ToolResult.text(
                    "Error parsing arguments for MCP server '" + serverName + "': " + e.getMessage());
        }

        // No `tool` → discovery catalog; the model builds its populated calls from the schemas.
        if (!args.has("tool") || args.get("tool").isJsonNull()
                || args.get("tool").getAsString().isBlank()) {
            return enumerateActions();
        }

        var actionName = args.get("tool").getAsString();
        var actionArgs = args.has("args") && args.get("args").isJsonObject()
                ? args.getAsJsonObject("args").toString()
                : "{}";

        // Delegate to the per-action adapter that already lives in the
        // registry. The adapter carries the allowlist gate + audit trail,
        // so server-level invocations get the same JCLAW-32 safety
        // guarantees as legacy direct-action invocations.
        var adapter = ToolRegistry.lookupTool(McpServer.toolName(serverName, actionName));
        if (adapter == null) {
            // Server down, action misspelled, or tool list changed since the model last
            // enumerated. Names only (no schemas) so the model can self-correct next turn
            // without re-ingesting the full catalog, which on a large server pushes the prompt
            // tens of thousands of tokens and time-to-first-token into the minute range.
            return ToolRegistry.ToolResult.text(
                    "MCP server '" + serverName + "' has no action named '" + actionName + "'. "
                            + envelopeHint(actionName, args)
                            + "Available actions: " + enumerateActionNames()
                            + ". Call mcp_" + serverName + " with empty arguments to "
                            + "retrieve the full input schemas if needed.");
        }
        return adapter.executeRich(actionArgs, agent);
    }

    /**
     * A bare action list does not correct a model that read {@code tool} as
     * "which server" and re-nested the real call under {@code args} — it
     * re-sends the same wrong shape until the round budget runs out.
     *
     * @return a corrective sentence ending in a space, empty for a plain
     *         unknown action (typo, stale tool list).
     */
    private String envelopeHint(String actionName, JsonObject args) {
        if (args.has("args") && args.get("args").isJsonObject()) {
            var inner = args.getAsJsonObject("args");
            if (inner.has("tool") && inner.get("tool").isJsonPrimitive()) {
                var nested = inner.get("tool").getAsString();
                if (ToolRegistry.lookupTool(McpServer.toolName(serverName, nested)) != null) {
                    return "The envelope is nested one level too deep — '" + nested
                            + "' is the action. Call {\"tool\": \"" + nested
                            + "\", \"args\": {...}} with the action's own arguments at the top of `args`. ";
                }
            }
        }
        if (actionName.equals(serverName) || actionName.equals(name())) {
            return "That is this server's own name, not an action — `tool` takes the action name. ";
        }
        return "";
    }

    private ToolRegistry.ToolResult enumerateActions() {
        var defs = McpConnectionManager.tools(serverName);
        if (defs == null || defs.isEmpty()) {
            return ToolRegistry.ToolResult.text(
                    "MCP server '" + serverName + "' is not currently connected or advertises no actions.");
        }
        var catalog = new ArrayList<Map<String, Object>>(defs.size());
        for (var def : defs) {
            catalog.add(Map.of(
                    "name", def.name(),
                    FIELD_DESCRIPTION, def.description() == null ? "" : def.description(),
                    "inputSchema", def.parametersAsMap()
            ));
        }
        var payload = Map.of(
                "server", serverName,
                "actions", catalog
        );
        return new ToolRegistry.ToolResult(GSON.toJson(payload), GSON.toJson(payload));
    }

    /**
     * Compact name-only list for the unknown-action error path. The full
     * {@link #enumerateActions} dump includes each action's
     * {@code inputSchema} — for a typo-correction signal that's overkill
     * and on schema-heavy servers (google-workspace observed at ~175 KB)
     * inflates the next LLM turn's prompt by tens of thousands of tokens.
     * Names alone let the model spot the right action; if it actually needs
     * a schema it can re-invoke {@code mcp_<server>} with no args.
     */
    private String enumerateActionNames() {
        var defs = McpConnectionManager.tools(serverName);
        if (defs == null || defs.isEmpty()) {
            return "(none — server not connected or advertises no actions)";
        }
        var names = new ArrayList<String>(defs.size());
        for (var def : defs) names.add(def.name());
        return String.join(", ", names);
    }
}
