package mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Streamable HTTP's mirrored tool parameters under 2026-07-28 (JCLAW-1444, JCLAW-1446): an
 * {@code inputSchema} property annotated {@code "x-mcp-header": "Name"} and reached from the root
 * through {@code properties} keys alone travels in an {@code Mcp-Param-Name} header as well as in
 * the arguments, so a gateway can route on it.
 */
public final class McpParamHeaders {

    public static final String ANNOTATION = "x-mcp-header";
    public static final String HEADER_PREFIX = "Mcp-Param-";
    /** JavaScript's {@code Number.MAX_SAFE_INTEGER}: the spec bounds a mirrored integer by it, both signs. */
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final BigDecimal MAX_SAFE = BigDecimal.valueOf(MAX_SAFE_INTEGER);
    private static final String PROPERTIES = "properties";
    /** RFC 9110 {@code token}: what a header field name may hold. */
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> PRIMITIVE_TYPES = Set.of("string", "integer", "boolean");

    private McpParamHeaders() {}

    /** An annotated property and the chain of {@code properties} names leading to it from the root. */
    private record Annotated(List<String> path, JsonObject schema) {
        String param() {
            return String.join(".", path);
        }
    }

    /** Why the tool's annotations break the spec's constraints, or null when they are all valid. */
    public static @Nullable String invalidReason(JsonObject inputSchema) {
        if (inputSchema.has(ANNOTATION)) return ANNOTATION + " sits on the schema root, which is not a property";
        var annotated = new ArrayList<Annotated>();
        var misplaced = collect(inputSchema, List.of(), annotated);
        if (misplaced != null) return misplaced;
        var seen = new HashSet<String>();
        for (var a : annotated) {
            var param = a.param();
            var annotation = a.schema().get(ANNOTATION);
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
            var typeProblem = typeProblem(a.schema().get("type"));
            if (typeProblem != null) return "'" + param + "' carries " + ANNOTATION + " but " + typeProblem;
        }
        return null;
    }

    /**
     * The headers a {@code tools/call} with {@code arguments} mirrors, each value read at its
     * annotated property's path and encoded as {@link McpClient#headerValue}; no value there sends
     * no header.
     *
     * @throws McpException an annotated argument is an object, an array, a fraction or an integer
     *                      outside {@link #MAX_SAFE_INTEGER}
     */
    public static Map<String, String> headersFor(JsonObject inputSchema, JsonObject arguments) {
        var annotated = new ArrayList<Annotated>();
        collect(inputSchema, List.of(), annotated);
        var out = new LinkedHashMap<String, String>();
        for (var a : annotated) {
            var value = valueAt(arguments, a.path());
            if (value == null || value.isJsonNull()) continue;
            out.put(HEADER_PREFIX + a.schema().get(ANNOTATION).getAsString(),
                    McpClient.headerValue(headerText(a.param(), value)));
        }
        return out;
    }

    /** A string as-is, a boolean as {@code true} or {@code false}, an integer in decimal. */
    private static String headerText(String param, JsonElement value) {
        if (!value.isJsonPrimitive()) {
            throw new McpException("argument '" + param
                    + "' must be a string, integer or boolean: the server reads it from a request header");
        }
        var p = value.getAsJsonPrimitive();
        if (!p.isNumber()) return p.getAsString();
        @Nullable BigDecimal n;
        try {
            n = p.getAsBigDecimal();
        } catch (NumberFormatException _) {
            n = null;  // Gson refuses a scale of 10,000 or more either way, so no safe integer lands here
        }
        // Range first: toBigIntegerExact on 1e999 would build a thousand-digit number.
        if (n == null || n.abs().compareTo(MAX_SAFE) > 0) {
            throw new McpException("argument '" + param + "' is outside the integer range a request header carries"
                    + " (-" + MAX_SAFE_INTEGER + " to " + MAX_SAFE_INTEGER + "), so the call was not sent");
        }
        if (n.stripTrailingZeros().scale() > 0) {
            throw new McpException("argument '" + param
                    + "' must be an integer: the server reads it from a request header");
        }
        return n.toBigIntegerExact().toString();
    }

    private static @Nullable JsonElement valueAt(JsonObject arguments, List<String> path) {
        JsonElement at = arguments;
        for (var key : path) {
            if (!(at instanceof JsonObject obj)) return null;
            at = obj.get(key);
        }
        return at;
    }

    /**
     * Adds every annotated property reached from {@code schema} through {@code properties} keys
     * alone; returns why the schema is invalid if an annotation sits anywhere else below it.
     */
    private static @Nullable String collect(JsonObject schema, List<String> path, List<Annotated> out) {
        for (var keyword : schema.entrySet()) {
            var key = keyword.getKey();
            if (key.equals(ANNOTATION)) continue;
            if (key.equals(PROPERTIES) && keyword.getValue() instanceof JsonObject props) {
                for (var prop : props.entrySet()) {
                    if (!(prop.getValue() instanceof JsonObject child)) continue;
                    var childPath = new ArrayList<>(path);
                    childPath.add(prop.getKey());
                    if (child.has(ANNOTATION)) out.add(new Annotated(List.copyOf(childPath), child));
                    var misplaced = collect(child, childPath, out);
                    if (misplaced != null) return misplaced;
                }
            } else if (containsAnnotation(keyword.getValue())) {
                return ANNOTATION + " sits under '" + key + "'"
                        + (path.isEmpty() ? " at the schema root" : " of '" + String.join(".", path) + "'")
                        + ", which is not reached through properties alone";
            }
        }
        return null;
    }

    /** Null for a string, integer or boolean, alone or with null; otherwise what is wrong with it. */
    private static @Nullable String typeProblem(@Nullable JsonElement type) {
        var types = new ArrayList<String>();
        if (type != null && type.isJsonPrimitive()) {
            types.add(type.getAsString());
        } else if (type != null && type.isJsonArray()) {
            for (var t : type.getAsJsonArray()) {
                if (!t.isJsonPrimitive()) return "its type is not a string, integer or boolean";
                types.add(t.getAsString());
            }
        }
        if (types.contains("number")) return "has type number, which the spec does not permit";
        var primitives = types.stream().filter(PRIMITIVE_TYPES::contains).count();
        var others = types.stream().filter(t -> !PRIMITIVE_TYPES.contains(t) && !"null".equals(t)).count();
        return primitives > 0 && others == 0 ? null : "its type is not a string, integer or boolean";
    }

    /** Whether {@code x-mcp-header} appears anywhere inside {@code el}. */
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
