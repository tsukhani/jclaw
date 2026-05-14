package tools;

import agents.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import play.Play;
import services.InternalApiTokenService;
import utils.HttpFactories;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Call JClaw's own HTTP API from an agent (JCLAW-282).
 *
 * <p>Pairs with the {@code jclaw-api} skill: the SKILL.md documents the
 * endpoint catalog in prose; the model picks paths, verbs, and bodies
 * from those instructions and calls this tool to actually perform the
 * request. One generic tool keeps the agent surface narrow while the
 * skill's text steers the model toward useful operations.
 *
 * <p><b>Path-only input.</b> The model supplies a path like
 * {@code /api/mcp-servers}; this tool prepends the loopback URL
 * {@code http://127.0.0.1:<http.port>} so the host portion can never
 * be redirected by the LLM. Skipping {@link utils.SsrfGuard} is safe
 * because the model never controls the host — the only network destination
 * is the same JVM's HTTP listener.
 *
 * <p><b>Bearer auth.</b> Every request carries the auto-managed internal
 * token from {@link InternalApiTokenService}. The token has FULL scope
 * (owner {@code "system"}) so mutating verbs reach their controllers,
 * but {@link controllers.AuthCheck} still 403s any bearer-authed call
 * to the token-CRUD or password-reset routes — the {@link #PATH_BLOCKLIST}
 * below catches the rest defensively before the request is even made.
 *
 * <p><b>Why a blocklist alongside a curated SKILL.md.</b> The SKILL.md
 * tells the model which endpoints to use; this blocklist refuses the
 * dangerous ones at the tool layer regardless of what the model tries.
 * Belt and suspenders against prompt-injection or model error: chat-send
 * endpoints (recursion risk), auth endpoints (privilege escalation),
 * token CRUD (lockout risk), webhooks (verified by callers, not by us),
 * and SSE endpoints (this tool buffers full responses).
 */
public class JClawApiTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "jclaw_api";

    private static final String KEY_METHOD = "method";
    private static final String KEY_PATH = "path";
    private static final String KEY_BODY = "body";
    private static final String KEY_QUERY = "query";

    /** Path prefixes refused at the tool layer. Each one corresponds
     *  to a category of operation the in-process tool must never
     *  invoke regardless of the model's intent or the SKILL.md's
     *  contents. Order doesn't matter; the loop short-circuits on the
     *  first match. */
    private static final List<String> PATH_BLOCKLIST = List.of(
            "/api/chat/",          // recursion via send/stream/upload
            "/api/auth/",          // login/setup/reset — admin-only via UI
            "/api/api-tokens",     // privilege-escalation surface
            "/api/webhooks/",      // verified by their own signature
            "/api/events"          // SSE; we buffer full bodies
    );

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final int MAX_RESPONSE_CHARS = 50_000;
    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    @Override public String name() { return TOOL_NAME; }
    @Override public String category() { return "JClaw"; }
    @Override public String icon() { return "cog"; }

    @Override
    public String shortDescription() {
        return "Call JClaw's own HTTP API (loopback only, bearer-auth handled internally).";
    }

    @Override
    public String description() {
        return """
                Invoke JClaw's own API by HTTP method + path. \
                Read the jclaw-api SKILL.md for the catalog of safe endpoints and example payloads. \
                Pass `path` like "/api/agents" — never a full URL. \
                For mutating verbs (POST/PUT/PATCH/DELETE), pass `body` as a JSON object matching the endpoint's schema.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        KEY_METHOD, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, ALLOWED_METHODS,
                                SchemaKeys.DESCRIPTION, "HTTP method"),
                        KEY_PATH, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "JClaw API path starting with /api/ (e.g. /api/agents)"),
                        KEY_BODY, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                                SchemaKeys.DESCRIPTION, "JSON request body for POST/PUT/PATCH; omit for GET/DELETE",
                                SchemaKeys.ADDITIONAL_PROPERTIES, true),
                        KEY_QUERY, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                                SchemaKeys.DESCRIPTION, "Query parameters as key→string-value pairs",
                                SchemaKeys.ADDITIONAL_PROPERTIES, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING))
                ),
                SchemaKeys.REQUIRED, List.of(KEY_METHOD, KEY_PATH)
        );
    }

    /** Stateless HTTP call over loopback — safe to invoke in parallel
     *  for the GET case. The mutating branches funnel through Play's
     *  per-request JPA tx, so backend-side concurrency is governed by
     *  the controllers, not this tool. */
    @Override public boolean parallelSafe() { return true; }

    @Override
    public String execute(String argsJson, Agent agent) {
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return "Error: arguments are not valid JSON: " + e.getMessage();
        }

        var methodRaw = stringField(args, KEY_METHOD);
        var path = stringField(args, KEY_PATH);
        if (methodRaw == null || path == null) {
            return "Error: both 'method' and 'path' are required";
        }
        var method = methodRaw.toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            return "Error: method must be one of " + ALLOWED_METHODS + " — got: " + methodRaw;
        }
        if (!path.startsWith("/api/")) {
            return "Error: path must start with /api/ — got: " + path;
        }
        for (var blocked : PATH_BLOCKLIST) {
            if (path.startsWith(blocked)) {
                return "Error: %s is reserved and cannot be invoked through jclaw_api. "
                        .formatted(blocked)
                        + "See the jclaw-api SKILL.md for the allowed endpoint catalog.";
            }
        }

        var url = buildUrl(path, args);
        if (url == null) {
            return "Error: could not construct URL for path: " + path;
        }

        var requestBuilder = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + InternalApiTokenService.token())
                .header("Accept", "application/json");

        RequestBody body = requestBodyFor(method, args);
        requestBuilder.method(method, body);

        try (var response = HttpFactories.general().newCall(requestBuilder.build()).execute()) {
            var status = response.code();
            var text = response.body().string();
            if (text.length() > MAX_RESPONSE_CHARS) {
                text = text.substring(0, MAX_RESPONSE_CHARS)
                        + "\n\n[Truncated: response exceeds %d characters]"
                                .formatted(MAX_RESPONSE_CHARS);
            }
            return "HTTP %d\n%s".formatted(status, text);
        } catch (java.io.IOException e) {
            return "Error: HTTP request failed: " + e.getMessage();
        }
    }

    private static HttpUrl buildUrl(String path, JsonObject args) {
        var port = Play.configuration.getProperty("http.port", "9000");
        var base = "http://127.0.0.1:" + port + path;
        var parsed = HttpUrl.parse(base);
        if (parsed == null) return null;
        var builder = parsed.newBuilder();
        if (args.has(KEY_QUERY) && args.get(KEY_QUERY).isJsonObject()) {
            for (var entry : args.getAsJsonObject(KEY_QUERY).entrySet()) {
                var v = entry.getValue();
                if (v == null || v.isJsonNull()) continue;
                builder.addQueryParameter(entry.getKey(),
                        v.isJsonPrimitive() ? v.getAsString() : v.toString());
            }
        }
        return builder.build();
    }

    /** OkHttp requires a (possibly-empty) body on POST/PUT/PATCH/DELETE
     *  but rejects one on GET. When the model declared a body for a
     *  body-less verb, we accept silently rather than fail — the
     *  alternative would force the model to coordinate verb + body
     *  shape perfectly, which it sometimes doesn't. */
    private static RequestBody requestBodyFor(String method, JsonObject args) {
        var bodyJson = args.has(KEY_BODY) && args.get(KEY_BODY).isJsonObject()
                ? args.getAsJsonObject(KEY_BODY) : null;
        if ("GET".equals(method)) return null;
        var serialized = bodyJson == null ? "{}" : bodyJson.toString();
        return RequestBody.create(serialized, JSON);
    }

    private static String stringField(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        if (!el.isJsonPrimitive()) return null;
        var s = el.getAsString();
        return s.isBlank() ? null : s;
    }
}
