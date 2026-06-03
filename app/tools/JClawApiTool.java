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
import controllers.ChatSafe;
import play.Play;
import play.mvc.ActionInvoker;
import play.mvc.Router;
import services.InternalApiTokenService;
import utils.HttpFactories;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
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
 * <p><b>Default-deny allowlist + blocklist deny-floor.</b> Invocation is
 * gated by two independent layers. The primary gate is an <em>allowlist</em>:
 * {@code call} only invokes endpoints whose route is annotated
 * {@link controllers.ChatSafe} — the same set {@link #discover} advertises —
 * so a path that exists but carries no marker is refused even though nothing
 * blocklists it (see {@link #isChatSafeCall}). On top of that, the
 * {@link #PATH_BLOCKLIST} is an unconditional <em>deny-floor</em> applied
 * first: belt-and-suspenders against prompt-injection or a mis-annotation,
 * refusing chat-send endpoints (recursion risk), auth endpoints (privilege
 * escalation), token CRUD (lockout risk), webhooks (verified by callers, not
 * by us), and SSE endpoints (this tool buffers full responses) regardless of
 * any marker. The SKILL.md steers the model toward useful endpoints in prose,
 * but the security boundary is these two code-enforced layers, not the prose.
 */
public class JClawApiTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "jclaw_api";

    private static final String KEY_ACTION = "action";
    private static final String KEY_FILTER = "filter";
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
    @Override public String category() { return "System"; }
    @Override public String icon() { return "cog"; }

    @Override
    public String shortDescription() {
        return "Call JClaw's own HTTP API (loopback only, bearer-auth handled internally).";
    }

    @Override
    public String description() {
        return """
                Invoke JClaw's own API by HTTP method + path, or discover what is callable. \
                Set action="discover" to list the chat-safe endpoints (verb, path, summary, body hint); \
                pass an optional `filter` substring to narrow the list. \
                To invoke one, omit action (or action="call") and pass `method` and `path` like "/api/agents" — never a full URL; \
                for mutating verbs (POST/PUT/PATCH/DELETE), pass `body` as a JSON object. \
                Prefer discover over guessing paths; never call an endpoint that discover does not list.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        KEY_ACTION, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of("call", "discover"),
                                SchemaKeys.DESCRIPTION, "\"discover\" lists the chat-safe endpoints; \"call\" (default) invokes one via method+path"),
                        KEY_FILTER, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Optional substring for action=discover; narrows results by path or summary"),
                        KEY_METHOD, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, ALLOWED_METHODS,
                                SchemaKeys.DESCRIPTION, "HTTP method (required when action=call)"),
                        KEY_PATH, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "JClaw API path starting with /api/ (required when action=call), e.g. /api/agents"),
                        KEY_BODY, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                                SchemaKeys.DESCRIPTION, "JSON request body for POST/PUT/PATCH; omit for GET/DELETE",
                                SchemaKeys.ADDITIONAL_PROPERTIES, true),
                        KEY_QUERY, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                                SchemaKeys.DESCRIPTION, "Query parameters as key→string-value pairs",
                                SchemaKeys.ADDITIONAL_PROPERTIES, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING))
                )
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

        var action = stringField(args, KEY_ACTION);
        if (action != null && "discover".equalsIgnoreCase(action)) {
            return discover(stringField(args, KEY_FILTER));
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
        // Allowlist enforcement (default-deny): only endpoints whose route is
        // annotated @ChatSafe may be invoked — the same set `discover` lists.
        // The PATH_BLOCKLIST above is an independent deny-floor that runs first,
        // so a path that were ever both annotated and blocklisted stays blocked.
        if (!isChatSafeCall(method, path)) {
            return "Error: %s %s is not a chat-safe endpoint and cannot be invoked through jclaw_api. "
                    .formatted(method, path)
                    + "Use action=\"discover\" to list the callable endpoints.";
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

    // ---------------------------------------------------------------- discover

    /**
     * JCLAW-329: list the chat-safe JClaw API endpoints. Scans the live route
     * table, keeps only actions annotated {@link controllers.ChatSafe}, and
     * excludes anything matching {@link #PATH_BLOCKLIST} (the unconditional
     * deny-floor — the annotation is an allowlist, not an override). The route
     * table is the source of truth for verb + path, so a newly-added annotated
     * endpoint shows up here at runtime with no skill edit.
     */
    private String discover(String filter) {
        var needle = (filter == null || filter.isBlank()) ? null : filter.toLowerCase(Locale.ROOT);
        var entries = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var route : Router.routes) {
            ChatSafe ann = annotationFor(route.action);
            if (ann == null) continue;
            var path = route.path;
            if (path == null || !path.startsWith("/api/")) continue;
            boolean blocked = false;
            for (var b : PATH_BLOCKLIST) {
                if (path.startsWith(b)) { blocked = true; break; }
            }
            if (blocked) continue;   // deny-floor wins over the marker
            var verb = (route.method == null || route.method.isBlank() || "*".equals(route.method))
                    ? "ANY" : route.method.toUpperCase(Locale.ROOT);
            if (!seen.add(verb + " " + path)) continue;
            if (needle != null
                    && !path.toLowerCase(Locale.ROOT).contains(needle)
                    && !ann.summary().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            var line = "- " + verb + " " + path + " — " + ann.summary();
            if (!ann.body().isBlank()) {
                line += "  [body: " + ann.body() + "]";
            }
            entries.add(line);
        }
        entries.sort(null);
        if (entries.isEmpty()) {
            return "No chat-safe JClaw API endpoints found"
                    + (needle != null ? " matching \"" + filter + "\"." : ".");
        }
        return "Chat-safe JClaw API endpoints (" + entries.size() + ")"
                + (needle != null ? " matching \"" + filter + "\"" : "")
                + ". Invoke one by calling this tool with method + path (and body for mutating verbs); "
                + "never call an endpoint that is not listed here.\n\n"
                + String.join("\n", entries);
    }

    /**
     * Allowlist gate for {@code action="call"}: returns {@code true} only when a
     * route matching {@code method} + the concrete {@code path} resolves to a
     * {@link ChatSafe}-annotated action — i.e. the very set {@link #discover}
     * advertises. This makes invocation default-deny: an endpoint that exists but
     * carries no marker cannot be called even though it isn't blocklisted.
     *
     * <p>{@code discover} compares against route <em>patterns</em>; {@code call}
     * receives a <em>concrete</em> path, so we delegate the pattern match to the
     * router's own compiled regex via {@link Router.Route#matches(String, String)}.
     * The annotation is checked first so {@code matches()} is only ever evaluated
     * on {@code @ChatSafe} routes — never the static-dir / 404 routes whose
     * {@code matches()} can throw {@code NotFound}/{@code RenderStatic}.
     */
    public static boolean isChatSafeCall(String method, String path) {
        var matchPath = path;
        int q = matchPath.indexOf('?');
        if (q >= 0) matchPath = matchPath.substring(0, q);
        for (var route : Router.routes) {
            if (annotationFor(route.action) == null) continue;
            try {
                if (route.matches(method, matchPath) != null) return true;
            } catch (RuntimeException ignored) {
                // Defensive: a @ChatSafe action should never be a static/404 route,
                // but if matches() throws we treat it as a non-match and keep scanning.
            }
        }
        return false;
    }

    /**
     * Resolve a route's action string to its {@link Method} and return its
     * {@link ChatSafe} annotation, or {@code null} if it can't be resolved or
     * isn't annotated. Defensive: many routes (static dirs, 404, non-controller
     * actions) don't map to a controller Method — those are simply skipped.
     */
    private static ChatSafe annotationFor(String action) {
        if (action == null || action.isBlank()) return null;
        // Preferred: Play's own action resolver.
        try {
            Object[] resolved = ActionInvoker.getActionMethod(action);
            if (resolved != null && resolved.length >= 2 && resolved[1] instanceof Method m) {
                return m.getAnnotation(ChatSafe.class);
            }
        } catch (Exception ignored) {
            // fall through to manual resolution
        }
        // Fallback: resolve "Controller.method" by name off Play's classloader.
        // Context-free, so it works outside an HTTP request (e.g. unit tests).
        try {
            int dot = action.lastIndexOf('.');
            if (dot <= 0) return null;
            var className = action.substring(0, dot);
            var methodName = action.substring(dot + 1);
            if (!className.contains(".")) className = "controllers." + className;
            Class<?> cls = Play.classloader.loadClass(className);
            for (var candidate : cls.getDeclaredMethods()) {
                if (candidate.getName().equals(methodName) && candidate.isAnnotationPresent(ChatSafe.class)) {
                    return candidate.getAnnotation(ChatSafe.class);
                }
            }
        } catch (Exception ignored) {
            // not a resolvable controller action — skip
        }
        return null;
    }
}
