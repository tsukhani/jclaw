package mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/** What an MCP server publishes as resources: its listing and the contents of one read. */
public final class McpResources {

    /** The content type of an MCP Apps page (SEP-1865). */
    public static final String APP_MIME_TYPE = "text/html;profile=mcp-app";
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    private McpResources() {}

    /** A {@code ui://} URI, however its scheme is cased: the one rule for an MCP Apps page's address. */
    public static boolean isUiUri(String uri) {
        return uri.regionMatches(true, 0, "ui://", 0, 5);
    }

    /** A view page, kept from the model: a {@code ui://} URI or an app page's type. */
    public static boolean isViewPage(String uri, @Nullable String mimeType) {
        return isUiUri(uri) || isAppPage(mimeType);
    }

    /** Whether {@code mimeType} is {@code text/html} with a {@code profile} of {@code mcp-app}, beside any other parameter. */
    public static boolean isAppPage(@Nullable String mimeType) {
        if (mimeType == null) return false;
        var parts = mimeType.split(";");
        if (!parts[0].strip().equalsIgnoreCase("text/html")) return false;
        for (var i = 1; i < parts.length; i++) {
            var eq = parts[i].indexOf('=');
            if (eq < 0 || !parts[i].substring(0, eq).strip().equalsIgnoreCase("profile")) continue;
            var value = parts[i].substring(eq + 1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.equalsIgnoreCase("mcp-app")) return true;
        }
        return false;
    }

    /**
     * One entry of {@code resources/list}.
     *
     * @param ui the entry's {@code _meta.ui}, or null
     */
    public record Resource(String uri, @Nullable String name, @Nullable String description,
                           @Nullable String mimeType, @Nullable JsonObject ui) {

        public Resource(String uri, @Nullable String name, @Nullable String description, @Nullable String mimeType) {
            this(uri, name, description, mimeType, null);
        }

        /** Null for an entry without a {@code uri}, which nothing could read. */
        static @Nullable Resource fromJson(JsonObject obj) {
            var uri = string(obj, "uri");
            if (uri == null) return null;
            return new Resource(uri, string(obj, "name"), string(obj, "description"), string(obj, "mimeType"), uiMeta(obj));
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
     * travels further than the client; an MCP Apps page is the exception, decoded to its HTML.
     *
     * @param text      the text, or null for a blob
     * @param blobBytes the blob's decoded size; 0 for text
     * @param ui        the content's {@code _meta.ui}, or null
     */
    public record Content(String uri, @Nullable String mimeType, @Nullable String text, long blobBytes,
                          @Nullable JsonObject ui) {

        public Content(String uri, @Nullable String mimeType, @Nullable String text, long blobBytes) {
            this(uri, mimeType, text, blobBytes, null);
        }

        static Content fromJson(JsonObject obj, String requestedUri) {
            var uri = string(obj, "uri") instanceof String u ? u : requestedUri;
            var mimeType = string(obj, "mimeType");
            if (obj.get("text") instanceof JsonElement text && text.isJsonPrimitive()) {
                return new Content(uri, mimeType, text.getAsString(), 0, uiMeta(obj));
            }
            if (obj.get("blob") instanceof JsonElement blob && blob.isJsonPrimitive()) {
                var page = isAppPage(mimeType) ? decodedText(blob.getAsString()) : null;
                return page != null ? new Content(uri, mimeType, page, 0, uiMeta(obj))
                        : new Content(uri, mimeType, null, decodedSize(blob.getAsString()), uiMeta(obj));
            }
            return new Content(uri, mimeType, "", 0, uiMeta(obj));
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

    private static @Nullable String decodedText(String base64) {
        try {
            return new String(Base64.getDecoder().decode(WHITESPACE.matcher(base64).replaceAll("")), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static @Nullable JsonObject uiMeta(JsonObject obj) {
        return obj.get("_meta") instanceof JsonObject meta && meta.get("ui") instanceof JsonObject ui ? ui : null;
    }

    private static @Nullable String string(JsonObject obj, String key) {
        var el = obj.get(key);
        return el != null && el.isJsonPrimitive() && !el.getAsString().isBlank() ? el.getAsString() : null;
    }
}
