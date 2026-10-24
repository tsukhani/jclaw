package mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

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
 *                bracketed placeholders), or the {@code structuredContent} JSON
 *                when those parts flatten to nothing but whitespace
 * @param isError true when the tool ran but reported an error condition
 *                (distinct from a JSON-RPC error that means the tool never
 *                ran)
 * @param raw     the result object as the server sent it
 */
public record CallToolResult(String content, boolean isError, JsonObject raw) {

    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_RESOURCE = "resource";
    private static final String FIELD_STRUCTURED = "structuredContent";
    private static final String FIELD_META = "_meta";
    private static final String FIELD_IS_ERROR = "isError";
    /** The most of a view tool's {@code structuredContent} the model reads: about 4K tokens of typical JSON, under the default first-view bound. */
    public static final int VIEW_DATA_MAX_CHARS = 16_000;
    /** Longer text is taken to carry the data itself, so the view's data is not added beside it. */
    public static final int VIEW_SHORT_TEXT_MAX_CHARS = 500;

    public CallToolResult(String content, boolean isError) {
        this(content, isError, new JsonObject());
    }

    /**
     * The model's text for a tool that links a view: its text, then the {@code structuredContent} its view draws,
     * since such a tool may send only a placeholder as text (SerpAPI sends {@code [Rendered Prefab UI]}). Added only
     * beside short text that is not already that JSON, which the MCP tools spec asks a tool to send as text too.
     */
    public String contentWithViewData() {
        if (!(raw.get(FIELD_STRUCTURED) instanceof JsonElement structured) || structured.isJsonNull()) return content;
        if (content.length() > VIEW_SHORT_TEXT_MAX_CHARS || isJson(content, structured)) return content;
        var json = structured.toString();
        var label = "\n\nStructured content (the data the tool's view draws):\n";
        if (json.length() <= VIEW_DATA_MAX_CHARS) return content + label + json;
        var cut = Character.isHighSurrogate(json.charAt(VIEW_DATA_MAX_CHARS - 1)) ? VIEW_DATA_MAX_CHARS - 1 : VIEW_DATA_MAX_CHARS;
        return content + label + json.substring(0, cut) + String.format(Locale.ROOT,
                "\n[structuredContent truncated: showed the first %,d of %,d characters]", cut, json.length());
    }

    private static boolean isJson(String text, JsonElement expected) {
        try {
            return expected.equals(JsonParser.parseString(text));
        } catch (JsonParseException _) {
            return false;
        }
    }

    public static CallToolResult fromResultObject(JsonObject obj) {
        boolean isError = obj.has(FIELD_IS_ERROR) && obj.get(FIELD_IS_ERROR).getAsBoolean();
        var sb = new StringBuilder();
        if (obj.has(FIELD_CONTENT) && obj.get(FIELD_CONTENT).isJsonArray()) {
            JsonArray parts = obj.getAsJsonArray(FIELD_CONTENT);
            for (var part : parts) {
                if (!part.isJsonObject()) continue;
                appendPart(sb, part.getAsJsonObject());
            }
        }
        // A view-first tool may leave content empty; the declaration that invites one reaches every channel.
        var structured = obj.get(FIELD_STRUCTURED);
        var text = sb.toString().isBlank() && structured != null && !structured.isJsonNull()
                ? structured.toString() : sb.toString();
        return new CallToolResult(text, isError, obj);
    }

    /**
     * The result an MCP Apps view receives: {@code content}, {@code structuredContent}, {@code _meta} and
     * {@code isError}. In {@code content}, image and audio data and an embedded resource's blob become a
     * {@code [type, size]} placeholder, since the payload is stored with the message.
     */
    public JsonObject forView() {
        var out = new JsonObject();
        var content = new JsonArray();
        if (raw.get(FIELD_CONTENT) instanceof JsonArray parts) {
            for (var part : parts) content.add(part instanceof JsonObject p ? withoutBinary(p) : part.deepCopy());
        }
        out.add(FIELD_CONTENT, content);
        copyIfPresent(FIELD_STRUCTURED, out);
        copyIfPresent(FIELD_META, out);
        out.addProperty(FIELD_IS_ERROR, isError);
        return out;
    }

    private static JsonObject withoutBinary(JsonObject part) {
        var copy = part.deepCopy();
        var type = copy.get("type") instanceof JsonPrimitive t ? t.getAsString() : "";
        if (("image".equals(type) || "audio".equals(type)) && copy.get("data") instanceof JsonPrimitive data) {
            copy.addProperty("data", placeholder(copy, type, data));
        }
        if (FIELD_RESOURCE.equals(type) && copy.get(FIELD_RESOURCE) instanceof JsonObject res
                && res.get("blob") instanceof JsonPrimitive blob) {
            res.addProperty("blob", placeholder(res, "binary", blob));
        }
        return copy;
    }

    /** {@code [image/png, 8 bytes]}: the part's type and its decoded size. */
    private static String placeholder(JsonObject holder, String fallbackType, JsonPrimitive base64) {
        var mimeType = holder.get("mimeType") instanceof JsonPrimitive m ? m.getAsString() : fallbackType;
        return "[" + mimeType + ", " + McpResources.decodedSize(base64.getAsString()) + " bytes]";
    }

    private void copyIfPresent(String key, JsonObject out) {
        if (raw.get(key) instanceof JsonElement el && !el.isJsonNull()) out.add(key, el.deepCopy());
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
