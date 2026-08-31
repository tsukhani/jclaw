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

    // JCLAW-1138: the rest of the wire contract, promoted from string literals scattered
    // across 15 controllers. These are values the SPA branches on, so a typo is a silently
    // broken client path no test would catch — and javac inlines a constant reference into
    // the caller's constant pool, so bytecode analysis cannot tell a literal from a constant
    // after the fact. One declaration each is the only enforceable guarantee.

    // Authentication and session.
    public static final String AUTHENTICATION_REQUIRED = "authentication_required";
    public static final String INVALID_CREDENTIALS = "invalid_credentials";
    public static final String CREDENTIALS_CHANGED = "credentials_changed";
    public static final String INVALID_TOKEN = "invalid_token";
    public static final String PASSWORD_UNSET = "password_unset";
    public static final String ALREADY_SET = "already_set";
    public static final String TOO_MANY_ATTEMPTS = "too_many_attempts";
    public static final String PASSWORD_TOO_SHORT = "password_too_short";
    public static final String PASSWORD_TOO_LONG = "password_too_long";
    public static final String PASSWORD_BREACHED = "password_breached";

    // Authorization.
    public static final String FORBIDDEN = "forbidden";
    /** 403 — an operator-only write that an agent-originated principal may not perform. */
    public static final String OPERATOR_ONLY = "operator_only";
    /** 403 — a hosted app reaching outside the agent it was installed for. */
    public static final String APP_SCOPE = "app_scope";

    // Agents and apps.
    public static final String NO_AGENT = "no_agent";
    public static final String UNKNOWN_AGENT = "unknown_agent";
    public static final String BAD_AGENT = "bad_agent";
    public static final String NO_SUCH_APP = "no_such_app";

    // Channel bindings.
    public static final String BOT_TOKEN_CONFLICT = "bot_token_conflict";
    public static final String PHONE_NUMBER_CONFLICT = "phone_number_conflict";
    public static final String CLOUD_API_VERIFICATION_FAILED = "cloud_api_verification_failed";

    // Request shape and limits.
    public static final String NO_INPUT = "no_input";
    public static final String PAYLOAD_TOO_LARGE = "payload_too_large";
    public static final String RESERVED_KEY = "reserved_key";

    // Upstream, capacity and I/O.
    public static final String RATE_LIMITED = "rate_limited";
    public static final String UPSTREAM_ERROR = "upstream_error";
    public static final String UNAVAILABLE = "unavailable";
    public static final String TTS_UNAVAILABLE = "tts_unavailable";
    public static final String POOL_UNAVAILABLE = "pool_unavailable";
    public static final String IO_ERROR = "io_error";
    public static final String SEARCH_FAILED = "search_failed";

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

    /**
     * The error to throw after a call that always throws but is typed to return —
     * {@link #error}, Play's {@code notFound()} / {@code badRequest()}. Returns rather
     * than throws so a bare call cannot compile into a silent no-op.
     *
     * @return the error for the caller to {@code throw}
     */
    public static AssertionError unreachable() {
        return new AssertionError("unreachable: the preceding call throws");
    }

    private static Map<String, Object> errorBody(String code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "error");
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
