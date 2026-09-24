package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.AgentAccess;
import controllers.AgentAccessGate;
import controllers.RequestPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import models.Agent;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jspecify.annotations.Nullable;
import play.Play;
import play.mvc.ActionInvoker;
import play.mvc.Router;
import services.InternalApiTokenService;
import utils.HttpFactories;
import utils.HttpKeys;
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
 * token from {@link InternalApiTokenService}, whose owner is {@code "system"}, plus
 * {@link RequestPrincipal#AGENT_ID_HEADER} naming the calling agent -- one token serves every
 * agent, so the credential alone cannot say which one is calling (JCLAW-1270).
 *
 * <p><b>Default-deny.</b> A route is callable only where its action declares
 * {@link controllers.AgentAccess} {@code OPEN} or {@code OWN_ONLY}; an action that declares
 * nothing is refused, so a newly-added endpoint is unreachable until someone opts it in. This
 * check is not the security boundary -- {@link controllers.AgentAccessGate} enforces the same
 * annotation at the request layer, so an agent that reached the route by some other means is
 * refused there too.
 *
 * <p>The gate runs on {@link HttpUrl#encodedPath()} rather than on the model's string, so a
 * path cannot resolve past it on its way out -- {@code /api/skills/x/files/../../../logs} left
 * as {@code /api/logs} when checked raw, and normalising also keeps a path from escaping
 * {@code /api/} into the routes file's {@code {controller}/{action}} catch-all (JCLAW-1227).
 * Catalog text comes from the Swagger {@code @Operation} summary and {@code @RequestBody}
 * schema, synthesized from the action name and request DTO when those are absent.
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
        var unnormalized = unnormalizedReason(path);
        if (unnormalized != null) {
            return "Error: path contains %s and cannot be invoked through jclaw_api -- pass the resolved path. Got: %s"
                    .formatted(unnormalized, path);
        }

        var url = buildUrl(path, args);
        if (url == null) {
            return "Error: could not construct URL for path: " + path;
        }
        // Gate the path that will actually be sent, not the one the model typed: HttpUrl
        // resolves dot-segments while parsing (JCLAW-1227).
        var gatedPath = url.encodedPath();
        // Default-deny, the same set `discover` advertises; AgentAccessGate re-checks it.
        if (!isCallable(method, gatedPath)) {
            return "Error: %s %s is not callable through jclaw_api (no such endpoint, or it is deny-listed). "
                    .formatted(method, gatedPath)
                    + "Use action=\"discover\" to list the callable endpoints.";
        }

        var requestBuilder = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + InternalApiTokenService.token())
                .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON);

        // JCLAW-1270: stamped from the Agent the registry hands us, never from the model's
        // arguments. A null agent is a test driving the tool; the unstamped request reads as an
        // unidentified agent, which agent-scoped routes refuse.
        if (agent != null && agent.id != null) {
            requestBuilder.header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agent.id));
        }

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

    /**
     * Why {@code path} must be refused before it is parsed, or {@code null} when it is clean.
     * Each form makes the string the model wrote disagree with the URL that leaves: a
     * dot-segment or a backslash resolves elsewhere (measured on OkHttp 5.5.0 --
     * {@code /api/foo\bar} parses as {@code /api/foo/bar}), a {@code #} truncates the rest
     * including the query, and an empty segment is a path the router and the gate can read
     * differently. Refusing is safe because none of them can be part of a real JClaw route.
     */
    private static @Nullable String unnormalizedReason(String path) {
        if (path.indexOf('#') >= 0) return "a '#'";
        int q = path.indexOf('?');
        var pathPart = q >= 0 ? path.substring(0, q) : path;
        if (pathPart.indexOf('\\') >= 0) return "a backslash";
        if (pathPart.contains("//")) return "an empty path segment ('//')";
        for (var segment : pathPart.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) return "a dot-segment";
        }
        return null;
    }

    private static @Nullable HttpUrl buildUrl(String path, JsonObject args) {
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
    private static @Nullable RequestBody requestBodyFor(String method, JsonObject args) {
        var bodyJson = args.has(KEY_BODY) && args.get(KEY_BODY).isJsonObject()
                ? args.getAsJsonObject(KEY_BODY) : null;
        if ("GET".equals(method)) return null;
        var serialized = bodyJson == null ? "{}" : bodyJson.toString();
        return RequestBody.create(serialized, JSON);
    }

    private static @Nullable String stringField(JsonObject obj, String key) {
        // Guard before delegating: optNonBlankString calls getAsString, which throws on
        // an object/array value (a JsonNull is also non-primitive).
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        return JsonArgs.optNonBlankString(obj, key);
    }

    // ---------------------------------------------------------------- discover

    /**
     * List the callable JClaw API endpoints. Scans the live route table and keeps
     * every {@code /api/} route whose action declares {@link controllers.AgentAccess}
     * {@code OPEN} or {@code OWN_ONLY}. The route table is the source of truth for
     * verb + path, so a newly-added endpoint shows up here at runtime as soon as it
     * declares a level -- and stays hidden until it does. Catalog text comes from the Swagger
     * {@code @Operation} summary and {@code @RequestBody} schema, synthesized from
     * the action name and request DTO when those are absent.
     */
    private String discover(@Nullable String filter) {
        var needle = (filter == null || filter.isBlank()) ? null : filter.toLowerCase(Locale.ROOT);
        var entries = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var route : Router.routes) {
            Method m = resolveMethod(route.action);
            if (m == null) continue;                       // not a controller action
            var path = route.path;
            if (path == null || !path.startsWith(API_PREFIX)) continue;
            if (!agentMayCall(m)) continue;                // declared OPERATOR_ONLY, or undeclared
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
     * Default-deny gate for {@code action="call"}: a concrete {@code (method, path)} is callable
     * only where its action declares {@link controllers.AgentAccess} {@code OPEN} or
     * {@code OWN_ONLY} -- mirroring exactly what {@link #discover} advertises. An endpoint that
     * exists but declares nothing is refused, which is also what keeps a nonexistent path
     * (matched only by the 404 catch-all) from granting.
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
        for (var route : Router.routes) {
            Method m = resolveMethod(route.action);
            if (m == null) continue;                       // not a controller action
            if (!agentMayCall(m)) continue;                // declared OPERATOR_ONLY, or undeclared
            try {
                if (route.matches(method, matchPath) != null) return true;
            } catch (RuntimeException _) {
                // Defensive: a static/404 route whose matches() throws -- treat as a
                // non-match and keep scanning.
            }
        }
        return false;
    }

    /** An action the agent principal may invoke: anything the request layer would not refuse
     *  outright. {@code OWN_ONLY} counts — the row check happens inside the action. */
    private static boolean agentMayCall(Method m) {
        return AgentAccessGate.levelFor(m) != AgentAccess.Level.OPERATOR_ONLY;
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
    private static @Nullable Method resolveMethod(String action) {
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
