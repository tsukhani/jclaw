package mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One MCP tool advertised by a connected server (JCLAW-31).
 *
 * <p>{@code name} and {@code description} are display-facing. {@code inputSchema}
 * is a JSON Schema object the agent's LLM uses to construct {@code tools/call}
 * arguments — passed through to {@link mcp.McpToolAdapter#parameters()} unchanged
 * so the existing tool-call machinery in {@code AgentRunner} sees a normal
 * tool with a schema. The MCP spec guarantees {@code inputSchema} is always
 * an object with {@code type: "object"} (or empty for parameter-less tools).
 */
public record McpToolDef(String name, String description, JsonObject inputSchema) {

    public static McpToolDef fromJson(JsonObject obj) {
        var name = obj.get("name").getAsString();
        var description = obj.has("description") && !obj.get("description").isJsonNull()
                ? obj.get("description").getAsString() : "";
        JsonObject schema = obj.has("inputSchema") && obj.get("inputSchema").isJsonObject()
                ? obj.getAsJsonObject("inputSchema") : new JsonObject();
        return new McpToolDef(name, description, schema);
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

    private static Object jsonToJava(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonObject()) return jsonToMap(el.getAsJsonObject());
        if (el.isJsonArray()) {
            var list = new java.util.ArrayList<Object>();
            for (var e : el.getAsJsonArray()) list.add(jsonToJava(e));
            return list;
        }
        var p = el.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsNumber();
        return p.getAsString();
    }
}
