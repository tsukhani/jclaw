package mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** What an MCP server publishes as resources: its listing and the contents of one read. */
public final class McpResources {

    private McpResources() {}

    /** One entry of {@code resources/list}. */
    public record Resource(String uri, @Nullable String name, @Nullable String description,
                           @Nullable String mimeType) {

        /** Null for an entry without a {@code uri}, which nothing could read. */
        static @Nullable Resource fromJson(JsonObject obj) {
            var uri = string(obj, "uri");
            if (uri == null) return null;
            return new Resource(uri, string(obj, "name"), string(obj, "description"), string(obj, "mimeType"));
        }
    }

    /** One entry of {@code resources/templates/list}: an RFC 6570 URI template. */
    public record Template(String uriTemplate, @Nullable String name, @Nullable String description) {

        static @Nullable Template fromJson(JsonObject obj) {
            var uriTemplate = string(obj, "uriTemplate");
            if (uriTemplate == null) return null;
            return new Template(uriTemplate, string(obj, "name"), string(obj, "description"));
        }
    }

    /**
     * At most {@link McpClient#MAX_LISTED_RESOURCES} of a server's resources, and as many of its templates.
     *
     * @param more the server lists more resources or templates than these hold
     */
    public record Listing(List<Resource> resources, List<Template> templates, boolean more) {}

    /**
     * One content of {@code resources/read}. A blob keeps only its decoded size, so its base64 never
     * travels further than the client.
     *
     * @param text      the text, or null for a blob
     * @param blobBytes the blob's decoded size; 0 for text
     */
    public record Content(String uri, @Nullable String mimeType, @Nullable String text, long blobBytes) {

        static Content fromJson(JsonObject obj, String requestedUri) {
            var uri = string(obj, "uri");
            var mimeType = string(obj, "mimeType");
            if (obj.get("text") instanceof JsonElement text && text.isJsonPrimitive()) {
                return new Content(uri != null ? uri : requestedUri, mimeType, text.getAsString(), 0);
            }
            if (obj.get("blob") instanceof JsonElement blob && blob.isJsonPrimitive()) {
                return new Content(uri != null ? uri : requestedUri, mimeType, null, decodedSize(blob.getAsString()));
            }
            return new Content(uri != null ? uri : requestedUri, mimeType, "", 0);
        }
    }

    static List<Content> contents(@Nullable JsonElement contents, String requestedUri) {
        var out = new ArrayList<Content>();
        if (contents == null || !contents.isJsonArray()) return out;
        for (var el : contents.getAsJsonArray()) {
            if (el instanceof JsonObject obj) out.add(Content.fromJson(obj, requestedUri));
        }
        return out;
    }

    /** Counted rather than decoded: every four base64 characters carry three bytes, and padding carries none. */
    static long decodedSize(String base64) {
        long chars = 0;
        for (var i = 0; i < base64.length(); i++) {
            var c = base64.charAt(i);
            if (c != '=' && !Character.isWhitespace(c)) chars++;
        }
        return chars * 3 / 4;
    }

    private static @Nullable String string(JsonObject obj, String key) {
        var el = obj.get(key);
        return el != null && el.isJsonPrimitive() && !el.getAsString().isBlank() ? el.getAsString() : null;
    }
}
