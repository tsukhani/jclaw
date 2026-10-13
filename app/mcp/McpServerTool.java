package mcp;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.McpServer;
import org.jspecify.annotations.Nullable;
import utils.GsonHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * with no {@code tool} argument, this returns the server's action list: with
 * each action's input schema while the list is small, otherwise as names and
 * one-line summaries, the schemas of chosen actions coming from a
 * {@code describe} call. The model builds its populated calls from those
 * schemas. When the model invokes with
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
    private static final String ARG_TOOL = "tool";
    private static final String ARG_DESCRIBE = "describe";

    /** google-workspace lists 119 actions in 200 KB, 81% of it schemas: past this size the list is an index. */
    static final int FULL_CATALOG_MAX_CHARS = 8_000;
    private static final int SUMMARY_MAX_CHARS = 160;

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
                + "Call with no arguments (or `{}`) to list the server's actions. "
                + "A large server lists names and summaries only: call with "
                + "`{\"describe\": [\"<action>\", ...]}` for the input schemas of the actions you will use. "
                + "Call with `{\"tool\": \"<action>\", \"args\": {...}}` to "
                + "execute one action against the server.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        ARG_TOOL, Map.of(
                                "type", "string",
                                FIELD_DESCRIPTION, "The action to execute. Omit to list the server's actions."),
                        "args", Map.of(
                                "type", "object",
                                FIELD_DESCRIPTION, "Arguments for the action, matching its input schema.",
                                "additionalProperties", true),
                        ARG_DESCRIBE, Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                FIELD_DESCRIPTION, "Action names whose input schemas to return, instead of executing one.")
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

    /** The action list and the described schemas are what the model builds its calls from. */
    @Override
    public boolean verbatimResult(String argsJson) {
        try {
            var parsed = JsonParser.parseString(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            if (!parsed.isJsonObject()) return false;
            var args = parsed.getAsJsonObject();
            return !describedNames(args).isEmpty() || !namesAnAction(args);
        } catch (RuntimeException _) {
            return false;
        }
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

        var described = describedNames(args);
        if (!described.isEmpty()) return describeActions(described);

        // No `tool` → the action list; the model builds its populated calls from the schemas.
        if (!namesAnAction(args)) {
            // A whole envelope wrapped once more also has no outer `tool`, and the catalog
            // does not correct it: the model re-sends the same shape.
            var nested = nestedEnvelopeHint(args);
            return nested.isEmpty()
                    ? enumerateActions()
                    : ToolRegistry.ToolResult.text(
                            "MCP server '" + serverName + "' was called with no `tool`. " + nested.strip());
        }

        var actionName = args.get(ARG_TOOL).getAsString();
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
                            + ". Call mcp_" + serverName + " with {\"describe\": [\"<action>\"]} "
                            + "for an action's input schema if needed.");
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
        var nested = nestedEnvelopeHint(args);
        if (!nested.isEmpty()) return nested;
        if (actionName.equals(serverName) || actionName.equals(name())) {
            return "That is this server's own name, not an action — `tool` takes the action name. ";
        }
        return "";
    }

    /** The corrective sentence when {@code args.tool} names a registered action, else empty. */
    private String nestedEnvelopeHint(JsonObject args) {
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
        return "";
    }

    private static boolean namesAnAction(JsonObject args) {
        return args.has(ARG_TOOL) && !args.get(ARG_TOOL).isJsonNull() && !args.get(ARG_TOOL).getAsString().isBlank();
    }

    private static List<String> describedNames(JsonObject args) {
        var el = args.get(ARG_DESCRIBE);
        if (el == null || el.isJsonNull()) return List.of();
        // Models send one name as a bare string as often as a one-element list.
        Iterable<JsonElement> items = el.isJsonArray() ? el.getAsJsonArray() : List.of(el);
        var names = new ArrayList<String>();
        for (var item : items) {
            if (item.isJsonPrimitive() && !item.getAsString().isBlank()) names.add(item.getAsString());
        }
        return names;
    }

    private ToolRegistry.ToolResult enumerateActions() {
        var defs = McpConnectionManager.toolsForTurn(serverName);
        return defs == null || defs.isEmpty() ? notConnected() : catalog(serverName, defs);
    }

    private ToolRegistry.ToolResult describeActions(List<String> names) {
        var defs = McpConnectionManager.toolsForTurn(serverName);
        return defs == null || defs.isEmpty() ? notConnected() : describe(serverName, defs, names);
    }

    private ToolRegistry.ToolResult notConnected() {
        return ToolRegistry.ToolResult.text(
                "MCP server '" + serverName + "' is not currently connected or advertises no actions.");
    }

    /** The action list: every input schema while that stays small, otherwise names and one-line summaries. */
    public static ToolRegistry.ToolResult catalog(String serverName, List<McpToolDef> defs) {
        var whole = GSON.toJson(payload(serverName, null, entries(defs, true)));
        if (whole.length() <= FULL_CATALOG_MAX_CHARS) return new ToolRegistry.ToolResult(whole, whole);
        var index = GSON.toJson(payload(serverName,
                "Not listed. Call mcp_" + serverName + " with {\"describe\": [\"<action>\", ...]} for the input "
                        + "schemas of the actions you will use, before calling them.",
                entries(defs, false)));
        return new ToolRegistry.ToolResult(index, index);
    }

    /** The whole description and input schema of each named action; a name the server lacks is reported as unknown. */
    public static ToolRegistry.ToolResult describe(String serverName, List<McpToolDef> defs, List<String> names) {
        var known = new ArrayList<McpToolDef>();
        var unknown = new ArrayList<String>();
        for (var name : names) {
            var def = defs.stream().filter(d -> d.name().equals(name)).findFirst().orElse(null);
            if (def == null) unknown.add(name);
            else known.add(def);
        }
        var payload = payload(serverName, null, entries(known, true));
        if (!unknown.isEmpty()) payload.put("unknown", unknown);
        var json = GSON.toJson(payload);
        return new ToolRegistry.ToolResult(json, json);
    }

    private static Map<String, Object> payload(String serverName, @Nullable String inputSchemas,
                                               List<Map<String, Object>> actions) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("server", serverName);
        if (inputSchemas != null) payload.put("inputSchemas", inputSchemas);
        payload.put("actions", actions);
        return payload;
    }

    private static List<Map<String, Object>> entries(List<McpToolDef> defs, boolean withSchema) {
        var out = new ArrayList<Map<String, Object>>(defs.size());
        for (var def : defs) {
            var description = def.description() == null ? "" : def.description();
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", def.name());
            entry.put(FIELD_DESCRIPTION, withSchema ? description : summary(description));
            if (withSchema) entry.put("inputSchema", def.parametersAsMap());
            out.add(entry);
        }
        return out;
    }

    /** A description's first line, cut at a sentence end where one falls inside the cap. */
    private static String summary(String description) {
        var line = description.strip();
        int newline = line.indexOf('\n');
        if (newline >= 0) line = line.substring(0, newline).strip();
        if (line.length() <= SUMMARY_MAX_CHARS) return line;
        int sentenceEnd = line.lastIndexOf(". ", SUMMARY_MAX_CHARS);
        return sentenceEnd > 0 ? line.substring(0, sentenceEnd + 1) : line.substring(0, SUMMARY_MAX_CHARS - 1) + "…";
    }

    /**
     * Compact name-only list for the unknown-action error path. The full
     * {@link #enumerateActions} dump includes each action's
     * {@code inputSchema} — for a typo-correction signal that's overkill
     * and on schema-heavy servers (google-workspace observed at ~175 KB)
     * inflates the next LLM turn's prompt by tens of thousands of tokens.
     * Names alone let the model spot the right action; if it actually needs
     * a schema it can ask for that one with {@code describe}.
     */
    private String enumerateActionNames() {
        var defs = McpConnectionManager.toolsForTurn(serverName);
        if (defs == null || defs.isEmpty()) {
            return "(none — server not connected or advertises no actions)";
        }
        var names = new ArrayList<String>(defs.size());
        for (var def : defs) names.add(def.name());
        return String.join(", ", names);
    }
}
