package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.ChatHidden;
import io.swagger.v3.oas.annotations.Operation;
import models.Agent;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import play.Play;
import play.mvc.ActionInvoker;
import play.mvc.Router;
import services.InternalApiTokenService;
import utils.HttpFactories;
import utils.JsonArgs;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Call JClaw's own HTTP API from an agent (JCLAW-282).
 *
 * <p>Pairs with the {@code jclaw-api} skill: the SKILL.md documents how to drive
 * the API in prose; the model picks paths, verbs, and bodies and calls this tool
 * to perform the request. One generic tool keeps the agent surface narrow while
 * the skill's text steers the model toward useful operations.
 *
 * <p><b>Path-only input.</b> The model supplies a path like
 * {@code /api/mcp-servers}; this tool prepends the loopback URL
 * {@code http://127.0.0.1:<http.port>} so the host portion can never
 * be redirected by the LLM. Skipping {@link utils.SsrfGuard} is safe
 * because the model never controls the host -- the only network destination
 * is the same JVM's HTTP listener.
 *
 * <p><b>Bearer auth.</b> Every request carries the auto-managed internal
 * token from {@link InternalApiTokenService}. The token has FULL scope
 * (owner {@code "system"}) so mutating verbs reach their controllers,
 * but {@link controllers.AuthCheck} still 403s any bearer-authed call
 * to the token-CRUD or password-reset routes -- the {@link #PATH_BLOCKLIST}
 * below catches the rest defensively before the request is even made.
 *
 * <p><b>Default-allow blacklist + deny-floor.</b> Invocation is gated by two
 * independent <em>deny</em> layers; everything else is callable. The
 * {@link #PATH_BLOCKLIST} is an unconditional <em>deny-floor</em> applied first
 * -- coarse, whole-subsystem categories that must never be reached: chat-send
 * (recursion), auth (privilege escalation), token CRUD (lockout), webhooks
 * (caller-verified), SSE (we buffer full responses), plus secret-bearing /
 * infra / resource-abuse subsystems (bindings, telegram bindings, tailscale,
 * logs, the load-test harness). On top of that, individual actions carry
 * {@link controllers.ChatHidden} as a precise per-action opt-out (see
 * {@link #isCallable}). Any {@code /api/} route that resolves to a controller
 * action and is caught by neither layer is callable -- so a newly-added endpoint
 * is reachable with no annotation. Catalog text comes from the Swagger
 * {@code @Operation} summary and {@code @RequestBody} schema, synthesized from
 * the action name and request DTO when those are absent. The security boundary
 * is the two code-enforced deny layers, not the prose.
 */
public class JClawApiTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "jclaw_api";

    private static final String KEY_ACTION = "action";
    private static final String ACTION_CALL = "call";
    private static final String ACTION_DISCOVER = "discover";

    private static final String KEY_FILTER = "filter";
    private static final String KEY_METHOD = "method";
    private static final String KEY_PATH = "path";
    private static final String KEY_BODY = "body";
    private static final String KEY_QUERY = "query";

    /** Every callable path must start with this prefix (the three gate checks share it). */
    private static final String API_PREFIX = "/api/";

    /** Path prefixes refused at the tool layer (the deny-floor). Each one is a
     *  whole-subsystem category the in-process tool must never invoke regardless
     *  of the model's intent or the SKILL.md's contents. Order doesn't matter;
     *  the loop short-circuits on the first match. Per-action exclusions inside
     *  an otherwise-callable controller use {@link controllers.ChatHidden}. */
    private static final List<String> PATH_BLOCKLIST = List.of(
            "/api/chat/",                 // recursion via send/stream/upload
            "/api/auth/",                 // login/setup/reset -- admin-only via UI
            "/api/api-tokens",            // privilege-escalation surface
            "/api/webhooks/",             // verified by their own signature
            "/api/events",                // SSE; we buffer full bodies
            "/api/bindings",              // channel routing -- comms redirection / secrets
            "/api/channels/telegram/",    // telegram bindings carry bot tokens
            "/api/channels/slack/",       // slack bindings carry bot tokens + signing secrets
            "/api/tailscale",             // network-funnel infra config
            "/api/logs",                  // raw app logs can leak secrets/PII
            "/api/metrics/loadtest"       // load-test harness -- resource/cost abuse
    );

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final int MAX_RESPONSE_CHARS = 50_000;
    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /** JCLAW-844: the mutating subset of {@link #ALLOWED_METHODS}. A jclaw_api call
     *  with one of these verbs can create/alter server-side state (create an MCP
     *  server = arbitrary local exec, set a provider baseUrl = SSRF, promote a
     *  skill), so it routes through {@link agents.DangerousActionGate}; GET and
     *  discovery are reads. */
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override public String name() { return TOOL_NAME; }
    @Override public String category() { return "System"; }
    @Override public String icon() { return "cog"; }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction(ACTION_CALL, "Invoke a JClaw API endpoint via method and path"),
                new ToolAction(ACTION_DISCOVER, "List the callable API endpoints, optionally filtered")
        );
    }

    @Override
    public String shortDescription() {
        return "Call JClaw's own HTTP API (loopback only, bearer-auth handled internally).";
    }

    @Override
    public String description() {
        return """
                Invoke JClaw's own API by HTTP method + path, or discover what is callable. \
                Set action="discover" to list the callable endpoints (verb, path, summary, body hint); \
                pass an optional `filter` substring to narrow the list. \
                To invoke one, omit action (or action="call") and pass `method` and `path` like "/api/agents" -- never a full URL; \
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
                                SchemaKeys.ENUM, List.of(ACTION_CALL, ACTION_DISCOVER),
                                SchemaKeys.DESCRIPTION, "\"discover\" lists the callable endpoints; \"call\" (default) invokes one via method+path"),
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
                                SchemaKeys.DESCRIPTION, "Query parameters as key->string-value pairs",
                                SchemaKeys.ADDITIONAL_PROPERTIES, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING))
                )
        );
    }

    /** Stateless HTTP call over loopback -- safe to invoke in parallel
     *  for the GET case. The mutating branches funnel through Play's
     *  per-request JPA tx, so backend-side concurrency is governed by
     *  the controllers, not this tool. */
    @Override public boolean parallelSafe() { return true; }

    /**
     * JCLAW-844: {@code jclaw_api} is a universal proxy to every JClaw controller,
     * so its danger depends on the specific call rather than on the tool. A mutating
     * verb (POST/PUT/PATCH/DELETE) can create an MCP server (arbitrary local command
     * execution via a STDIO transport), point a provider {@code baseUrl} at an
     * internal host (SSRF), or promote a skill outside its root -- all actions the
     * native {@code exec} tool gates -- so it must route through
     * {@link agents.DangerousActionGate} with the agent's real conversation origin.
     * Reads ({@code GET}) and {@code action="discover"} are not dangerous (read
     * secret-exposure is handled at the serialization seam, JCLAW-780). A malformed,
     * action-only, or method-less call is a no-op in {@link #execute}, so it is
     * classified non-dangerous rather than raising a spurious approval prompt.
     */
    @Override
    public boolean dangerous(String argsJson) {
        if (argsJson == null) return false;
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (RuntimeException _) {
            return false;   // execute() rejects malformed args before any request
        }
        var action = stringField(args, KEY_ACTION);
        if (action != null && ACTION_DISCOVER.equalsIgnoreCase(action)) {
            return false;   // discovery only lists endpoints
        }
        var method = stringField(args, KEY_METHOD);
        return method != null && MUTATING_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return "Error: arguments are not valid JSON: " + e.getMessage();
        }

        var action = stringField(args, KEY_ACTION);
        if (action != null && ACTION_DISCOVER.equalsIgnoreCase(action)) {
            return discover(stringField(args, KEY_FILTER));
        }

        var methodRaw = stringField(args, KEY_METHOD);
        var path = stringField(args, KEY_PATH);
        if (methodRaw == null || path == null) {
            return "Error: both 'method' and 'path' are required";
        }
        var method = methodRaw.toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            return "Error: method must be one of " + ALLOWED_METHODS + " -- got: " + methodRaw;
        }
        if (!path.startsWith(API_PREFIX)) {
            return "Error: path must start with /api/ -- got: " + path;
        }
        for (var blocked : PATH_BLOCKLIST) {
            if (path.startsWith(blocked)) {
                return "Error: %s is reserved and cannot be invoked through jclaw_api. "
                        .formatted(blocked)
                        + "See the jclaw-api SKILL.md for the boundary.";
            }
        }
        // Default-allow gate: any /api/ route that resolves to a controller action
        // is callable unless the deny-floor (above) or a @ChatHidden marker excludes
        // it -- the same set `discover` advertises. The deny-floor runs first, so a
        // path that were ever both deny-floored and otherwise-callable stays blocked.
        if (!isCallable(method, path)) {
            return "Error: %s %s is not callable through jclaw_api (no such endpoint, or it is deny-listed). "
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
        } catch (IOException e) {
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
     *  body-less verb, we accept silently rather than fail -- the
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
        // Keep the non-primitive guard before delegating: optNonBlankString would
        // throw on an object/array value (getAsString). Gating on isJsonPrimitive
        // first (a JsonNull is also non-primitive) leaves only the null/blank
        // collapse for the shared reader — same result as the inline dance.
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        return JsonArgs.optNonBlankString(obj, key);
    }

    // ---------------------------------------------------------------- discover

    /**
     * List the callable JClaw API endpoints. Scans the live route table and keeps
     * every {@code /api/} route that resolves to a controller action, minus the
     * {@link #PATH_BLOCKLIST} deny-floor and minus any action annotated
     * {@link controllers.ChatHidden}. The route table is the source of truth for
     * verb + path, so a newly-added endpoint shows up here at runtime with no
     * annotation and no skill edit. Catalog text comes from the Swagger
     * {@code @Operation} summary and {@code @RequestBody} schema, synthesized from
     * the action name and request DTO when those are absent.
     */
    private String discover(String filter) {
        var needle = (filter == null || filter.isBlank()) ? null : filter.toLowerCase(Locale.ROOT);
        var entries = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var route : Router.routes) {
            Method m = resolveMethod(route.action);
            if (m == null) continue;                       // not a controller action
            var path = route.path;
            if (path == null || !path.startsWith(API_PREFIX)) continue;
            if (isDenyFloored(path)) continue;             // unconditional deny-floor
            if (m.isAnnotationPresent(ChatHidden.class)) continue;   // per-action opt-out
            var verb = (route.method == null || route.method.isBlank() || "*".equals(route.method))
                    ? "ANY" : route.method.toUpperCase(Locale.ROOT);
            if (!seen.add(verb + " " + path)) continue;
            var summary = summaryFor(m);
            if (needle != null
                    && !path.toLowerCase(Locale.ROOT).contains(needle)
                    && !summary.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            var line = "- " + verb + " " + path + " - " + summary;
            var bodyHint = bodyHintFor(m);
            if (!bodyHint.isBlank()) {
                line += "  [body: " + bodyHint + "]";
            }
            entries.add(line);
        }
        entries.sort(null);
        if (entries.isEmpty()) {
            return "No callable JClaw API endpoints found"
                    + (needle != null ? " matching \"" + filter + "\"." : ".");
        }
        return "Callable JClaw API endpoints (" + entries.size() + ")"
                + (needle != null ? " matching \"" + filter + "\"" : "")
                + ". Invoke one by calling this tool with method + path (and body for mutating verbs); "
                + "endpoints not listed here are deny-listed and will be refused.\n\n"
                + String.join("\n", entries);
    }

    /**
     * Default-allow gate for {@code action="call"}: a concrete {@code (method, path)}
     * is callable unless the {@link #PATH_BLOCKLIST} deny-floor or a
     * {@link ChatHidden} marker excludes it -- mirroring exactly what {@link #discover}
     * advertises. An endpoint that exists but carries no annotation IS callable
     * (the blacklist inversion). The 404 catch-all and other never-callable actions
     * carry {@link ChatHidden} so they never grant, which keeps nonexistent paths
     * (matched only by the catch-all) correctly refused.
     *
     * <p>{@code discover} compares route <em>patterns</em>; {@code call} receives a
     * <em>concrete</em> path, so the pattern match is delegated to the router's own
     * compiled regex via {@link Router.Route#matches(String, String)}.
     */
    public static boolean isCallable(String method, String path) {
        var matchPath = path;
        int q = matchPath.indexOf('?');
        if (q >= 0) matchPath = matchPath.substring(0, q);
        if (!matchPath.startsWith(API_PREFIX)) return false;
        if (isDenyFloored(matchPath)) return false;
        for (var route : Router.routes) {
            Method m = resolveMethod(route.action);
            if (m == null) continue;                       // not a controller action
            if (m.isAnnotationPresent(ChatHidden.class)) continue;   // hidden never grants
            try {
                if (route.matches(method, matchPath) != null) return true;
            } catch (RuntimeException _) {
                // Defensive: a static/404 route whose matches() throws -- treat as a
                // non-match and keep scanning.
            }
        }
        return false;
    }

    private static boolean isDenyFloored(String path) {
        for (var b : PATH_BLOCKLIST) {
            if (path.startsWith(b)) return true;
        }
        return false;
    }

    /** Catalog summary for a discovered action: the Swagger {@code @Operation}
     *  summary if the action carries one (curated prose), else a humanized
     *  method name. */
    private static String summaryFor(Method m) {
        var op = m.getAnnotation(Operation.class);
        if (op != null && !op.summary().isBlank()) return op.summary();
        return humanize(m.getName());
    }

    /** Body-field hint for a discovered action: the request DTO's record-component
     *  names mined from the Swagger {@code @RequestBody} schema, else empty. */
    private static String bodyHintFor(Method m) {
        var rb = m.getAnnotation(io.swagger.v3.oas.annotations.parameters.RequestBody.class);
        if (rb == null) return "";
        for (var content : rb.content()) {
            Class<?> impl = content.schema().implementation();
            if (impl != null && impl != Void.class && impl.isRecord()) {
                var names = new ArrayList<String>();
                for (RecordComponent rc : impl.getRecordComponents()) {
                    names.add(rc.getName());
                }
                if (!names.isEmpty()) return String.join(", ", names);
            }
        }
        return "";
    }

    /** "deleteConversation" -> "delete conversation". Best-effort summary for
     *  actions that carry no {@code @Operation} summary. */
    private static String humanize(String methodName) {
        return methodName.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }

    /**
     * Resolve a route's action string to its {@link Method}, or {@code null} if it
     * can't be resolved to a controller method. Defensive: many routes (static
     * dirs, 404, non-controller actions) don't map to a controller Method -- those
     * are simply skipped by callers, which is what makes the default-allow gate
     * still refuse non-controller routes.
     */
    private static Method resolveMethod(String action) {
        if (action == null || action.isBlank()) return null;
        // Preferred: Play's own action resolver.
        try {
            Object[] resolved = ActionInvoker.getActionMethod(action);
            if (resolved != null && resolved.length >= 2 && resolved[1] instanceof Method m) {
                return m;
            }
        } catch (Exception _) {
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
                if (candidate.getName().equals(methodName)) {
                    return candidate;
                }
            }
        } catch (Exception _) {
            // not a resolvable controller action -- skip
        }
        return null;
    }
}
