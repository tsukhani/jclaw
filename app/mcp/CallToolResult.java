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
 */
public record CallToolResult(String content, boolean isError) {

    public static CallToolResult fromResultObject(JsonObject obj) {
        boolean isError = obj.has("isError") && obj.get("isError").getAsBoolean();
        var sb = new StringBuilder();
        if (obj.has("content") && obj.get("content").isJsonArray()) {
            JsonArray parts = obj.getAsJsonArray("content");
            for (var part : parts) {
                if (!part.isJsonObject()) continue;
                var p = part.getAsJsonObject();
                var type = p.has("type") ? p.get("type").getAsString() : "";
                switch (type) {
                    case "text" -> sb.append(p.has("text") ? p.get("text").getAsString() : "");
                    case "image" -> sb.append("[image: ")
                            .append(p.has("mimeType") ? p.get("mimeType").getAsString() : "unknown")
                            .append("]");
                    case "resource" -> {
                        var res = p.has("resource") && p.get("resource").isJsonObject()
                                ? p.getAsJsonObject("resource") : null;
                        sb.append("[resource: ")
                                .append(res != null && res.has("uri") ? res.get("uri").getAsString() : "?")
                                .append("]");
                    }
                    default -> sb.append(p.toString());
                }
            }
        }
        return new CallToolResult(sb.toString(), isError);
    }
}
