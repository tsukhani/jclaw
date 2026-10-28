package tools;

import agents.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmTypes.ToolDef;
import models.Agent;
import org.jspecify.annotations.Nullable;
import utils.GsonHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JF-153: the one handle through which an agent with many tools reaches the native tools whose schemas
 * {@link ToolRegistry#deferCold} leaves out of the request. Shaped as an MCP server's handle
 * ({@link mcp.McpServerTool}) is: no arguments lists them, {@code describe} returns their schemas, and
 * {@code tool} with {@code args} runs one. {@link agents.ParallelToolExecutor} turns a run into the named tool's own
 * call before any check, so it is offered, approved, logged and run exactly as a direct call is.
 */
public final class ToolInvokeTool implements ToolRegistry.Tool {

    public static final String NAME = "tool_invoke";

    private static final String ARG_TOOL = "tool";
    private static final String ARG_ARGS = "args";
    private static final String ARG_DESCRIBE = "describe";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_PROPERTIES = "properties";
    private static final String FIELD_TOOLS = "tools";
    private static final String TYPE = "type";
    private static final String ENUM = "enum";

    private static final String DESCRIPTION = "Runs the tools whose input schemas this request leaves out; `tool` "
            + "lists their names. Call with no arguments (or `{}`) to list them with one line each. Call with "
            + "`{\"describe\": [\"<tool>\", ...]}` for the input schemas of the ones you will use, then with "
            + "`{\"tool\": \"<tool>\", \"args\": {...}}` to run one with its own arguments.";

    /** The tool a {@code tool_invoke} call names and the arguments it passes on. */
    public record Invocation(String name, String args) {}

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    /** The schema without a turn's names; {@link ToolRegistry#deferCold} sends {@link #parameters(List)}. */
    @Override
    public Map<String, Object> parameters() {
        return parameters(List.of());
    }

    /** A run is turned into the named tool's own call before scheduling, so only a list or a describe stays here. */
    @Override
    public boolean parallelSafe() {
        return true;
    }

    /** A list or a describe is what the model builds its calls from, as an MCP server's list is. */
    @Override
    public boolean verbatimResult(String argsJson) {
        var invocation = invokedThrough(NAME, argsJson);
        if (invocation == null) return true;
        var inner = ToolRegistry.lookupTool(invocation.name());
        return inner != null && !(inner instanceof ToolInvokeTool) && inner.verbatimResult(invocation.args());
    }

    @Override
    public boolean keepsResultInContext(String argsJson) {
        var invocation = invokedThrough(NAME, argsJson);
        if (invocation == null) return false;
        var inner = ToolRegistry.lookupTool(invocation.name());
        return inner != null && !(inner instanceof ToolInvokeTool) && inner.keepsResultInContext(invocation.args());
    }

    /** What stays here lists or describes JClaw's own tools, or refuses. */
    @Override
    public boolean readsOutsideContent(String argsJson, Agent agent) {
        return false;
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        return executeRich(argsJson, agent).text();
    }

    @Override
    public ToolRegistry.ToolResult executeRich(String argsJson, Agent agent) {
        return invoke(argsJson, agent, null);
    }

    /**
     * The handle's answer on a turn that offered {@code offered}: the list, the schemas asked for, or a refusal.
     * {@code null} reaches every native tool {@code agent} has enabled. It never runs a tool: a run reaching here
     * skipped the dispatcher's unwrap, and with it the offered check, the approval gate and the event log.
     */
    public ToolRegistry.ToolResult invoke(String argsJson, Agent agent, @Nullable Set<String> offered) {
        var invocation = ToolRegistry.unwrapInvocation(NAME, argsJson, offered);
        if (invocation != null) {
            return ToolRegistry.ToolResult.refused(("Error: %s runs no tool itself; the turn's tool loop dispatches '%s' "
                    + "as its own call.").formatted(NAME, invocation.name()), ToolRegistry.ToolResult.Outcome.NOT_ENABLED);
        }
        var args = argsObject(argsJson);
        var reached = ToolRegistry.deferredTools(agent, offered);
        var named = namedTool(args);
        if (named != null) return refusal(named, reached);
        var described = describedNames(args);
        return described.isEmpty() ? list(reached) : describe(reached, described);
    }

    /**
     * The tool and arguments a {@code tool_invoke} call names, read off the call alone; null for any other call and for
     * one naming no tool. Arguments that are not an object pass on as {@code {}}, as an MCP handle passes them.
     */
    public static @Nullable Invocation invokedThrough(@Nullable String toolName, @Nullable String argsJson) {
        if (!NAME.equals(toolName)) return null;
        var args = argsObject(argsJson);
        var named = namedTool(args);
        if (named == null) return null;
        var inner = args.get(ARG_ARGS);
        return new Invocation(named, inner != null && inner.isJsonObject() ? inner.toString() : "{}");
    }

    /** The input schema sent for a turn whose deferred tools are {@code names}, which {@code tool} lists. */
    public static Map<String, Object> parameters(List<String> names) {
        var tool = new LinkedHashMap<String, Object>();
        tool.put(TYPE, "string");
        if (!names.isEmpty()) tool.put(ENUM, List.copyOf(names));
        tool.put(FIELD_DESCRIPTION, "The tool to run. Omit to list the tools.");
        var args = new LinkedHashMap<String, Object>();
        args.put(TYPE, "object");
        args.put(FIELD_DESCRIPTION, "Arguments for the tool, matching its input schema.");
        args.put("additionalProperties", true);
        var describe = new LinkedHashMap<String, Object>();
        describe.put(TYPE, "array");
        describe.put("items", Map.of(TYPE, "string"));
        describe.put(FIELD_DESCRIPTION, "Tool names whose input schemas to return, instead of running one.");
        var properties = new LinkedHashMap<String, Object>();
        properties.put(ARG_TOOL, tool);
        properties.put(ARG_ARGS, args);
        properties.put(ARG_DESCRIBE, describe);
        var schema = new LinkedHashMap<String, Object>();
        schema.put(TYPE, "object");
        schema.put(FIELD_PROPERTIES, properties);
        schema.put("required", List.of());
        return schema;
    }

    /** The names a handle definition built by {@link #parameters(List)} offers. */
    public static List<String> namesIn(ToolDef def) {
        if (!(def.function().parameters().get(FIELD_PROPERTIES) instanceof Map<?, ?> properties)
                || !(properties.get(ARG_TOOL) instanceof Map<?, ?> tool)
                || !(tool.get(ENUM) instanceof List<?> names)) {
            return List.of();
        }
        var out = new ArrayList<String>(names.size());
        for (var name : names) out.add(String.valueOf(name));
        return out;
    }

    private static JsonObject argsObject(@Nullable String argsJson) {
        try {
            var parsed = JsonParser.parseString(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException _) {
            return new JsonObject();
        }
    }

    private static @Nullable String namedTool(JsonObject args) {
        var el = args.get(ARG_TOOL);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) return null;
        var name = el.getAsString().strip();
        return name.isEmpty() ? null : name;
    }

    private static List<String> describedNames(JsonObject args) {
        var el = args.get(ARG_DESCRIBE);
        if (el == null || el.isJsonNull()) return List.of();
        // Models send one name as a bare string as often as a one-element list.
        Iterable<JsonElement> items = el.isJsonArray() ? el.getAsJsonArray() : List.of(el);
        var names = new ArrayList<String>();
        for (var item : items) {
            if (item.isJsonPrimitive() && !item.getAsString().isBlank()) names.add(item.getAsString().strip());
        }
        return names;
    }

    /** A tool the request already carries is called by its own name, so every call to it reads under that name. */
    private static ToolRegistry.ToolResult refusal(String named, List<ToolRegistry.Tool> reached) {
        var tool = ToolRegistry.lookupTool(named);
        if (tool != null && !(tool instanceof ToolInvokeTool)) {
            return ToolRegistry.ToolResult.refused("Error: '%s' is in your tool list: call it directly, not through %s."
                    .formatted(named, NAME), ToolRegistry.ToolResult.Outcome.NOT_ENABLED);
        }
        var names = reached.stream().map(ToolRegistry.Tool::name).toList();
        return ToolRegistry.ToolResult.refused(("Error: %s reaches no tool named '%s'. It reaches: %s. Call %s with no "
                + "arguments to list them with one line each.").formatted(NAME, named,
                names.isEmpty() ? "(none)" : String.join(", ", names), NAME), ToolRegistry.ToolResult.Outcome.UNKNOWN_TOOL);
    }

    private static ToolRegistry.ToolResult list(List<ToolRegistry.Tool> reached) {
        var entries = new ArrayList<Map<String, Object>>(reached.size());
        for (var tool : reached) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", tool.name());
            entry.put(FIELD_DESCRIPTION, firstLine(tool.summary()));
            entries.add(entry);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("inputSchemas", "Not listed. Call " + NAME + " with {\"describe\": [\"<tool>\", ...]} for the input "
                + "schemas of the tools you will use, before calling them.");
        payload.put(FIELD_TOOLS, entries);
        return json(payload);
    }

    /** Each named tool's whole description and input schema, as a direct definition would carry them. */
    private static ToolRegistry.ToolResult describe(List<ToolRegistry.Tool> reached, List<String> names) {
        var known = new ArrayList<Map<String, Object>>();
        var unknown = new ArrayList<String>();
        for (var name : names) {
            var tool = reached.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
            if (tool == null) {
                unknown.add(name);
                continue;
            }
            var def = ToolDef.of(tool.name(), tool.description(), tool.parameters()).function();
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", def.name());
            entry.put(FIELD_DESCRIPTION, def.description());
            entry.put("inputSchema", def.parameters());
            known.add(entry);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_TOOLS, known);
        if (!unknown.isEmpty()) payload.put("unknown", unknown);
        return json(payload);
    }

    private static String firstLine(String text) {
        var line = text.strip();
        int newline = line.indexOf('\n');
        return newline >= 0 ? line.substring(0, newline).strip() : line;
    }

    private static ToolRegistry.ToolResult json(Map<String, Object> payload) {
        var json = GsonHolder.GSON.toJson(payload);
        return new ToolRegistry.ToolResult(json, json);
    }
}
