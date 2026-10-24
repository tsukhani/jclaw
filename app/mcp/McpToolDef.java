package mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One MCP tool advertised by a connected server (JCLAW-31).
 *
 * @param name        display-facing tool name from the MCP server
 * @param description display-facing prose describing what the tool does
 * @param inputSchema JSON Schema object the agent's LLM uses to construct
 *                    {@code tools/call} arguments — passed through to
 *                    {@link mcp.McpToolAdapter#parameters()} unchanged so
 *                    the existing tool-call machinery in {@code AgentRunner}
 *                    sees a normal tool with a schema. The MCP spec
 *                    guarantees this is always an object with
 *                    {@code type: "object"} (or empty for parameter-less
 *                    tools).
 * @param resourceUri the {@code ui://} page an MCP Apps host renders the result in, or null
 * @param visibility  who may call the tool, {@code "model"} and {@code "app"}; MCP Apps' default is both
 */
public record McpToolDef(String name, String description, JsonObject inputSchema,
                         @Nullable String resourceUri, List<String> visibility) {

    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_INPUT_SCHEMA = "inputSchema";
    public static final List<String> DEFAULT_VISIBILITY = List.of("model", "app");

    public McpToolDef {
        visibility = List.copyOf(visibility);
    }

    /** A tool with no view, visible to the model and the app alike. */
    public McpToolDef(String name, String description, JsonObject inputSchema) {
        this(name, description, inputSchema, null, DEFAULT_VISIBILITY);
    }

    public static McpToolDef fromJson(JsonObject obj) {
        var name = obj.get("name").getAsString();
        var description = obj.has(FIELD_DESCRIPTION) && !obj.get(FIELD_DESCRIPTION).isJsonNull()
                ? obj.get(FIELD_DESCRIPTION).getAsString() : "";
        JsonObject schema = obj.has(FIELD_INPUT_SCHEMA) && obj.get(FIELD_INPUT_SCHEMA).isJsonObject()
                ? obj.getAsJsonObject(FIELD_INPUT_SCHEMA) : new JsonObject();
        var meta = obj.get("_meta") instanceof JsonObject m ? m : new JsonObject();
        var ui = meta.get("ui") instanceof JsonObject u ? u : new JsonObject();
        var resourceUri = pageUri(ui.get("resourceUri"));
        if (resourceUri == null) resourceUri = pageUri(meta.get("ui/resourceUri"));
        return new McpToolDef(name, description, schema, resourceUri, visibility(ui.get("visibility")));
    }

    /** Whether the model may list, describe and call this tool. */
    public boolean visibleToModel() {
        return visibility.contains("model");
    }

    /** Whether the tool's view may call this tool through its host. */
    public boolean visibleToApp() {
        return visibility.contains("app");
    }

    /** A page URI must use {@code ui://}; any other value leaves the tool without a view. */
    private static @Nullable String pageUri(@Nullable JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) return null;
        var uri = el.getAsString();
        return McpResources.isUiUri(uri) ? uri : null;
    }

    /** The default applies only when the field is absent; an empty list hides the tool from both. */
    private static List<String> visibility(@Nullable JsonElement el) {
        if (el == null || !el.isJsonArray()) return DEFAULT_VISIBILITY;
        var out = new ArrayList<String>();
        for (var item : el.getAsJsonArray()) {
            if (item.isJsonPrimitive()) out.add(item.getAsString());
        }
        return out;
    }

    public Map<String, Object> parametersAsMap() {
        // The agent tool-loop's ToolDef expects a Map<String, Object>; convert
        // the JsonObject schema branch-by-branch. Preserves insertion order so
        // properties: keys retain the LLM-relevant declaration order.
        return jsonToMap(inputSchema);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonToMap(JsonObject obj) {
        var out = new LinkedHashMap<String, Object>();
        for (var entry : obj.entrySet()) {
            out.put(entry.getKey(), jsonToJava(entry.getValue()));
        }
        return out;
    }

    private static @Nullable Object jsonToJava(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonObject()) return jsonToMap(el.getAsJsonObject());
        if (el.isJsonArray()) {
            var list = new ArrayList<Object>();
            for (var e : el.getAsJsonArray()) list.add(jsonToJava(e));
            return list;
        }
        var p = el.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsNumber();
        return p.getAsString();
    }
}
