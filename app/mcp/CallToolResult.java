package mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Result of a single MCP {@code tools/call} (JCLAW-31).
 *
 * <p>The MCP spec returns a {@code content} array of typed parts (text, image,
 * resource link). For now we flatten to a plain string — JClaw's tool loop
 * receives strings from native tools too. {@code isError} is the spec's
 * separate "tool ran but failed" signal, distinct from the JSON-RPC error
 * channel which means "tool didn't run at all".
 *
 * @param content flattened result body — concatenation of the spec's typed
 *                content parts (text inlined, image / resource rendered as
 *                bracketed placeholders)
 * @param isError true when the tool ran but reported an error condition
 *                (distinct from a JSON-RPC error that means the tool never
 *                ran)
 */
public record CallToolResult(String content, boolean isError) {

    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_RESOURCE = "resource";

    public static CallToolResult fromResultObject(JsonObject obj) {
        boolean isError = obj.has("isError") && obj.get("isError").getAsBoolean();
        var sb = new StringBuilder();
        if (obj.has(FIELD_CONTENT) && obj.get(FIELD_CONTENT).isJsonArray()) {
            JsonArray parts = obj.getAsJsonArray(FIELD_CONTENT);
            for (var part : parts) {
                if (!part.isJsonObject()) continue;
                appendPart(sb, part.getAsJsonObject());
            }
        }
        return new CallToolResult(sb.toString(), isError);
    }

    private static void appendPart(StringBuilder sb, JsonObject p) {
        var type = p.has("type") ? p.get("type").getAsString() : "";
        switch (type) {
            case "text" -> sb.append(p.has("text") ? p.get("text").getAsString() : "");
            case "image" -> sb.append("[image: ")
                    .append(p.has("mimeType") ? p.get("mimeType").getAsString() : "unknown")
                    .append("]");
            case FIELD_RESOURCE -> appendResource(sb, p);
            default -> sb.append(p.toString());
        }
    }

    private static void appendResource(StringBuilder sb, JsonObject p) {
        var res = p.has(FIELD_RESOURCE) && p.get(FIELD_RESOURCE).isJsonObject()
                ? p.getAsJsonObject(FIELD_RESOURCE) : null;
        sb.append("[resource: ")
                .append(res != null && res.has("uri") ? res.get("uri").getAsString() : "?")
                .append("]");
    }
}
