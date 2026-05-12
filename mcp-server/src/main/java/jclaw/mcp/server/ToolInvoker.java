package jclaw.mcp.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Execute one MCP {@code tools/call} against the JClaw backend.
 *
 * <p>Three steps:
 * <ol>
 *   <li>Bind the agent-supplied arguments to the tool's parameter
 *       locations (PATH, QUERY, BODY) — produced upstream by
 *       {@link ToolGenerator}.</li>
 *   <li>Issue the HTTP request through {@link JClawHttp}, attaching
 *       the bearer header.</li>
 *   <li>Wrap the response body in an MCP {@code CallToolResult}
 *       envelope (a single {@code text}-typed content block carrying
 *       the response JSON pretty-printed). Non-2xx responses surface
 *       as {@code isError: true} so the agent sees the failure without
 *       the MCP host treating it as a tool-protocol bug.</li>
 * </ol>
 *
 * <p>Argument coercion is permissive: numbers, booleans, and strings
 * all stringify cleanly into a query parameter or a path segment. An
 * argument supplied as a JSON object/array for a non-BODY slot
 * stringifies via {@link JsonElement#toString} — that's almost always
 * a caller bug rather than a real intent, and the JSON shape ends up
 * URL-encoded so the backend's 4xx surfaces the misuse.
 */
public class ToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(ToolInvoker.class);
    private static final Gson PRETTY = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final JClawHttp http;

    public ToolInvoker(JClawHttp http) {
        this.http = http;
    }

    /** Run the tool. Returns the {@code result} JSON for the
     *  {@code tools/call} response. The MCP {@code isError} flag is
     *  embedded in the result rather than thrown so the JSON-RPC layer
     *  doesn't conflate "tool failed" with "protocol failed". */
    public JsonObject invoke(ToolDefinition tool, JsonObject args) {
        try {
            var url = buildUrl(tool, args);
            var body = buildBody(tool, args);
            var req = new Request.Builder()
                    .url(url)
                    .method(tool.httpMethod(), body)
                    .header("Authorization", "Bearer " + http.config().bearerToken())
                    .header("Accept", "application/json")
                    .build();
            try (var resp = http.client().newCall(req).execute()) {
                var responseBody = resp.body() == null ? "" : resp.body().string();
                return formatResult(resp.code(), responseBody, !resp.isSuccessful());
            }
        }
        catch (IOException e) {
            log.warn("Tool {} failed: {}", tool.name(), e.getMessage());
            return formatResult(0,
                    "Network error invoking JClaw: " + e.getMessage(),
                    true);
        }
        catch (IllegalArgumentException e) {
            // Missing required arg, etc. — surface to the agent rather
            // than crash the JSON-RPC dispatcher.
            return formatResult(0, "Invalid arguments: " + e.getMessage(), true);
        }
    }

    private HttpUrl buildUrl(ToolDefinition tool, JsonObject args) {
        var baseUrl = http.config().baseUrl().toString();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        // Substitute path placeholders one binding at a time so the
        // path-encoding step uses the same scheme as the URL parser
        // expects (legal-in-path-segment encoding, not query-encoding).
        var path = tool.pathTemplate();
        for (var p : tool.parameters()) {
            if (p.location() != ToolDefinition.Location.PATH) continue;
            var value = args.has(p.name()) && !args.get(p.name()).isJsonNull()
                    ? stringify(args.get(p.name())) : null;
            if (value == null) {
                throw new IllegalArgumentException(
                        "Missing required path parameter: " + p.name());
            }
            path = path.replace("{" + p.name() + "}",
                    URLEncoder.encode(value, StandardCharsets.UTF_8));
        }

        var builder = HttpUrl.get(baseUrl + path).newBuilder();
        for (var p : tool.parameters()) {
            if (p.location() != ToolDefinition.Location.QUERY) continue;
            if (!args.has(p.name()) || args.get(p.name()).isJsonNull()) {
                if (p.required()) {
                    throw new IllegalArgumentException(
                            "Missing required query parameter: " + p.name());
                }
                continue;
            }
            builder.addQueryParameter(p.name(), stringify(args.get(p.name())));
        }
        return builder.build();
    }

    private RequestBody buildBody(ToolDefinition tool, JsonObject args) {
        if (!tool.hasJsonBody()) return needsEmptyBody(tool.httpMethod())
                ? RequestBody.create(new byte[0], null) : null;
        var body = new JsonObject();
        for (var p : tool.parameters()) {
            if (p.location() != ToolDefinition.Location.BODY) continue;
            if (args.has(p.name())) {
                body.add(p.name(), args.get(p.name()));
            } else if (p.required()) {
                throw new IllegalArgumentException(
                        "Missing required body field: " + p.name());
            }
        }
        return RequestBody.create(body.toString(), JSON);
    }

    /** OkHttp requires a (possibly-empty) body on POST/PUT/PATCH/DELETE
     *  but rejects one on GET/HEAD. Methods whose Play handler reads
     *  no body still need an empty {@link RequestBody} when there's
     *  no JSON body, so the call goes through cleanly. */
    private static boolean needsEmptyBody(String method) {
        return switch (method) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    static String stringify(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    /** Build the MCP {@code tools/call} result. MCP requires
     *  {@code content: [{type: text, text: ...}]} for textual results;
     *  the response code is embedded in the {@code text} so an agent
     *  reading the output sees not just the payload but the wire
     *  status that produced it. */
    private static JsonObject formatResult(int statusCode, String body, boolean isError) {
        var result = new JsonObject();
        var contentArr = new com.google.gson.JsonArray();
        var block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", renderForAgent(statusCode, body));
        contentArr.add(block);
        result.add("content", contentArr);
        if (isError) result.addProperty("isError", true);
        return result;
    }

    private static String renderForAgent(int statusCode, String body) {
        // Pretty-print JSON bodies so an LLM reading the tool result
        // can pattern-match on field names. Non-JSON bodies (HTML
        // error pages, plain text) round-trip as-is.
        String pretty;
        try {
            var parsed = JsonParser.parseString(body);
            if (parsed.isJsonNull()) {
                pretty = "(empty response)";
            } else {
                pretty = PRETTY.toJson(parsed);
            }
        }
        catch (RuntimeException _) {
            pretty = body;
        }
        if (statusCode == 0) return pretty;
        return "HTTP %d\n\n%s".formatted(statusCode, pretty);
    }

    /** Unwrap the agent-supplied {@code arguments} object from a
     *  {@code tools/call} params payload. Returns an empty object if
     *  {@code params.arguments} is absent or null — callers that
     *  declare required parameters will themselves 4xx on missing
     *  fields. */
    static JsonObject extractArguments(JsonElement params) {
        if (params == null || params.isJsonNull() || !params.isJsonObject()) {
            return new JsonObject();
        }
        var paramsObj = params.getAsJsonObject();
        if (!paramsObj.has("arguments")) return new JsonObject();
        var args = paramsObj.get("arguments");
        if (args == null || args.isJsonNull() || !args.isJsonObject()) return new JsonObject();
        return args.getAsJsonObject();
    }

    // Suppressed-warning import: keep JsonNull referenced so the static
    // import survives the IDE optimize-imports pass.
    @SuppressWarnings("unused")
    private static final JsonNull NULL_SENTINEL = JsonNull.INSTANCE;
}
