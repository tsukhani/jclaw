package mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Streamable HTTP's mirrored tool parameters under 2026-07-28 (JCLAW-1444): a top-level
 * {@code inputSchema} property annotated {@code "x-mcp-header": "Name"} travels in an
 * {@code Mcp-Param-Name} header as well as in the arguments, so a gateway can route on it.
 */
public final class McpParamHeaders {

    public static final String ANNOTATION = "x-mcp-header";
    public static final String HEADER_PREFIX = "Mcp-Param-";
    /** RFC 9110 {@code token}: what a header field name may hold. */
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> PRIMITIVE_TYPES = Set.of("string", "number", "integer", "boolean");

    private McpParamHeaders() {}

    /** Why the tool's annotations break the spec's constraints, or null when they are all valid. */
    public static @Nullable String invalidReason(JsonObject inputSchema) {
        var seen = new HashSet<String>();
        for (var entry : properties(inputSchema).entrySet()) {
            if (!(entry.getValue() instanceof JsonObject prop)) continue;
            var param = entry.getKey();
            for (var keyword : prop.entrySet()) {
                if (containsAnnotation(keyword.getValue())) {
                    return ANNOTATION + " sits below the top-level property '" + param + "'";
                }
            }
            if (!prop.has(ANNOTATION)) continue;
            var annotation = prop.get(ANNOTATION);
            if (!annotation.isJsonPrimitive() || !annotation.getAsJsonPrimitive().isString()) {
                return "the " + ANNOTATION + " on '" + param + "' is not a string";
            }
            var name = annotation.getAsString();
            if (!TOKEN.matcher(name).matches()) {
                return "the " + ANNOTATION + " on '" + param + "' is not a valid header name";
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                return "the " + ANNOTATION + " '" + name + "' appears twice, ignoring case";
            }
            if (!isPrimitive(prop.get("type"))) {
                return "'" + param + "' carries " + ANNOTATION + " but is not a string, number, integer or boolean";
            }
        }
        return null;
    }

    /**
     * The headers a {@code tools/call} with {@code arguments} mirrors, values encoded as
     * {@link McpClient#headerValue}; an absent or null argument sends no header.
     *
     * @throws McpException an annotated argument is an object or array
     */
    public static Map<String, String> headersFor(JsonObject inputSchema, JsonObject arguments) {
        var out = new LinkedHashMap<String, String>();
        for (var entry : properties(inputSchema).entrySet()) {
            if (!(entry.getValue() instanceof JsonObject prop) || !prop.has(ANNOTATION)) continue;
            var value = arguments.get(entry.getKey());
            if (value == null || value.isJsonNull()) continue;
            if (!value.isJsonPrimitive()) {
                throw new McpException("argument '" + entry.getKey()
                        + "' must be a string, number or boolean: the server reads it from a request header");
            }
            // A JSON number keeps the text it was written with; a boolean is "true" or "false".
            out.put(HEADER_PREFIX + prop.get(ANNOTATION).getAsString(),
                    McpClient.headerValue(value.getAsString()));
        }
        return out;
    }

    private static JsonObject properties(JsonObject schema) {
        return schema.get("properties") instanceof JsonObject props ? props : new JsonObject();
    }

    private static boolean isPrimitive(@Nullable JsonElement type) {
        if (type == null) return false;
        if (type.isJsonPrimitive()) return PRIMITIVE_TYPES.contains(type.getAsString());
        if (!type.isJsonArray() || type.getAsJsonArray().isEmpty()) return false;
        var primitives = 0;
        for (var t : type.getAsJsonArray()) {
            if (!t.isJsonPrimitive()) return false;
            if (PRIMITIVE_TYPES.contains(t.getAsString())) primitives++;
            else if (!"null".equals(t.getAsString())) return false;
        }
        return primitives > 0;
    }

    /** Whether {@code x-mcp-header} appears anywhere inside {@code el}; below the top level no header can carry it. */
    private static boolean containsAnnotation(JsonElement el) {
        if (el instanceof JsonObject obj) {
            if (obj.has(ANNOTATION)) return true;
            for (var entry : obj.entrySet()) {
                if (containsAnnotation(entry.getValue())) return true;
            }
        } else if (el.isJsonArray()) {
            for (var item : el.getAsJsonArray()) {
                if (containsAnnotation(item)) return true;
            }
        }
        return false;
    }
}
