package utils;

import com.google.gson.Gson;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import play.mvc.Http;
import play.mvc.results.RenderJson;
import services.EventLogger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JCLAW-155: the single source of truth for the JSON envelope every
 * {@code Api*Controller} emits, so success acks and error bodies stop being
 * hand-rolled (and drifting) per controller.
 *
 * <p>Wire contract (locked):
 * <ul>
 *   <li>success — <code>{"status":"ok", &lt;extras&gt;}</code></li>
 *   <li>error   — <code>{"type":"error","code":"&lt;code&gt;","message":"&lt;message&gt;"}</code>
 *       rendered with the supplied HTTP status</li>
 * </ul>
 *
 * <p>Each method THROWS a Play {@link RenderJson} result — exactly like the
 * framework's own {@code renderJSON} / {@code notFound()} / {@code badRequest()}
 * (see {@code play.mvc.Controller}). A controller therefore calls it as a
 * terminal statement; control does not return. Because the JVM can't see that a
 * {@code void} method always throws, code after the call is still reachable to
 * the compiler — same ergonomics as {@code renderJSON}.
 *
 * <p>NOT for streaming: {@code ApiChatController}'s SSE event envelopes
 * (<code>{"type":"init|token|complete|error", ...}</code>) are a separate wire
 * protocol and deliberately do not go through here.
 */
public final class ApiResponses {

    /**
     * Canonical machine-readable error codes for {@link #error}/{@link #errorAndLog}.
     * Centralised here — the wire-contract source of truth — so controllers reference
     * one spelling instead of repeating the literal, which stops the codes from
     * drifting or being typo'd per controller (JCLAW: SonarQube java:S1192).
     */
    public static final String INVALID_REQUEST = "invalid_request";
    /** 500 — an unexpected server-side failure. */
    public static final String INTERNAL_ERROR = "internal_error";
    /** 409 — the request conflicts with existing state (duplicate name, already bound, …). */
    public static final String CONFLICT = "conflict";
    /** 404 — the addressed resource does not exist. */
    public static final String NOT_FOUND = "not_found";

    private static final Gson GSON = GsonHolder.GSON;
    private static final String LOG_CATEGORY = "api";

    private ApiResponses() {}

    /** Success ack with no payload: <code>{"status":"ok"}</code>. */
    public static void ok() {
        throw new RenderJson(GSON.toJson(Map.of("status", "ok")));
    }

    /**
     * Success ack with extra fields appended after {@code status:ok} — keys at
     * even indices, values at the following odd index. Example:
     * {@code ok("deleted", 3)} renders <code>{"status":"ok","deleted":3}</code>.
     *
     * @param kv alternating key/value pairs (even length required)
     */
    public static void ok(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("ApiResponses.ok(kv) requires an even number of arguments");
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("status", "ok");
        for (int i = 0; i < kv.length; i += 2) {
            body.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        throw new RenderJson(GSON.toJson(body));
    }

    /**
     * Render the canonical error body
     * <code>{"type":"error","code":...,"message":...}</code> with {@code httpStatus}.
     *
     * @param httpStatus HTTP status code to set on the response
     * @param code       stable, machine-readable error code (snake or kebab case)
     * @param message    human-readable error message
     */
    public static void error(int httpStatus, @NonNull String code, @NonNull String message) {
        Http.Response.current().status = httpStatus;
        throw new RenderJson(GSON.toJson(errorBody(code, message)));
    }

    /**
     * JCLAW-685: canonical error body plus extra machine-readable fields appended
     * after {@code message} — keys at even indices, values at the following odd
     * index (mirrors {@link #ok(Object...)}). Lets a controller surface a
     * structured hint (e.g. {@code error(409, "conflict", msg, "conflictingTaskId", id)})
     * that operators can target programmatically instead of regexing the message.
     *
     * @param kv alternating key/value pairs (even length required)
     */
    public static void error(int httpStatus, @NonNull String code, @NonNull String message, Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("ApiResponses.error(kv) requires an even number of extra arguments");
        }
        Http.Response.current().status = httpStatus;
        var body = errorBody(code, message);
        for (int i = 0; i < kv.length; i += 2) {
            body.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        throw new RenderJson(GSON.toJson(body));
    }

    /**
     * Log {@code t} through {@link EventLogger} then render the canonical error
     * body with {@code httpStatus} — collapses the repeated
     * {@code catch (Exception e) { Logger.error(...); renderJSON(...); }} blocks
     * the audit found across controllers into one call.
     *
     * @param t          the throwable to log (may be null)
     * @param httpStatus HTTP status code to set on the response
     * @param code       stable, machine-readable error code
     * @param message    human-readable error message
     */
    public static void errorAndLog(@Nullable Throwable t, int httpStatus, @NonNull String code, @NonNull String message) {
        EventLogger.error(LOG_CATEGORY, message, t);
        Http.Response.current().status = httpStatus;
        throw new RenderJson(GSON.toJson(errorBody(code, message)));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "error");
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
