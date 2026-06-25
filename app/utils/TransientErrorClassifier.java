package utils;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decide whether a {@link Throwable} represents a transient failure
 * that should be retried (rate limit, overload, network/timeout, 5xx)
 * versus a permanent failure that should bubble up. Used by
 * {@code JClawFailureHandler} to drive the db-scheduler retry path
 * for JCLAW-21 task fires.
 *
 * <p>Ported from OpenClaw's {@code failover-matches.ts} (TypeScript),
 * preserving the English-language and HTTP/POSIX error-code patterns.
 * Chinese-language provider messages — present in OpenClaw because
 * that codebase targets Chinese LLM providers — are dropped here:
 * JClaw's provider set (OpenAI, Anthropic, Ollama, OpenRouter) emits
 * English. Adding them back later is a one-line append per category
 * if a deployment proves to need them.
 *
 * <h2>Matching strategy</h2>
 * <ul>
 *   <li>Walks the {@link Throwable#getCause cause chain} up to
 *       {@link #MAX_CAUSE_DEPTH} levels — provider exceptions
 *       sometimes wrap the actual transient cause two or three
 *       deep (OkHttp → IOException → SocketTimeoutException is the
 *       canonical case).</li>
 *   <li>For each layer, checks the exception class's simple name
 *       against {@link #TRANSIENT_EXCEPTION_CLASSES} first — that's
 *       the cheapest signal and catches all the JDK network classes
 *       without scanning text.</li>
 *   <li>Falls through to a case-insensitive regex sweep of
 *       {@link Throwable#getMessage} — catches HTTP-status-coded
 *       messages, provider-specific error tags ({@code throttling},
 *       {@code rate_limit}, {@code overloaded_error},
 *       {@code resource_exhausted}, etc.), POSIX error codes
 *       (ECONNREFUSED / ECONNRESET / ETIMEDOUT / etc.), and the
 *       generic "service unavailable" / "bad gateway" / "5xx" wording
 *       that almost every reverse-proxy errors as.</li>
 *   <li>Identity-tracks visited throwables so a self-referential
 *       cause chain (malformed wrapper, getter that returns
 *       {@code this}) doesn't loop.</li>
 * </ul>
 *
 * <p>Conservative bias: false-positives ("permanent classified as
 * transient") cost one extra retry; false-negatives ("transient
 * classified as permanent") strand a task that would have succeeded
 * on retry. The patterns prefer false-positives.
 */
public final class TransientErrorClassifier {

    /**
     * Maximum cause-chain depth scanned. Same as OpenClaw's 25 —
     * an over-budget cap (real chains rarely exceed 4–5) that just
     * exists to bound a pathological self-referential loop.
     */
    private static final int MAX_CAUSE_DEPTH = 25;

    /**
     * Exception class simple-names whose mere presence in the cause
     * chain is enough to classify transient. All JDK network /
     * socket classes plus SSL handshake failures (which are
     * intermittent on cold connections and almost always recoverable
     * on retry).
     */
    private static final Set<String> TRANSIENT_EXCEPTION_CLASSES = Set.of(
            "SocketTimeoutException",
            "ConnectException",
            "UnknownHostException",
            "NoRouteToHostException",
            "HttpTimeoutException",
            "SSLException",
            "SSLHandshakeException",
            "SSLProtocolException",
            "InterruptedIOException",
            "ClosedChannelException",
            "AsynchronousCloseException"
    );

    /**
     * Case-insensitive regex sweep over the error message. Order
     * within the array doesn't matter — short-circuit is on first
     * match.
     */
    private static final Pattern[] TRANSIENT_PATTERNS = {
            // Rate limit / quota / throttling
            Pattern.compile("rate[_\\s]?limit|too many (?:concurrent )?requests|\\b429\\b|"
                    + "throttl(?:ing|ed)|quota.{0,40}exceeded|exceeded.{0,40}quota|"
                    + "resource[_ ]exhausted|model_cooldown|tokens per (?:minute|day)|\\btpm\\b",
                    Pattern.CASE_INSENSITIVE),

            // Overloaded / capacity
            Pattern.compile("overload(?:ed)?(?:_error)?|(?:selected\\s+)?model\\s+(?:is\\s+)?at capacity|"
                    + "high (?:demand|load)|service[_ ]unavailable.{0,40}(?:overload|capacity|high[_ ]demand)|"
                    + "(?:overload|capacity|high[_ ]demand).{0,40}service[_ ]unavailable",
                    Pattern.CASE_INSENSITIVE),

            // Timeout / network — message-level (core wording)
            Pattern.compile("timeout|timed out|deadline exceeded|context deadline exceeded|"
                    + "connection (?:error|reset|refused|aborted|closed)|network (?:error|request failed)|"
                    + "fetch failed|socket hang up|^terminated$",
                    Pattern.CASE_INSENSITIVE),

            // Streaming / provider stop-reason variants — split out so the
            // core network-message pattern stays under Sonar's regex
            // complexity ceiling without losing coverage.
            Pattern.compile("without sending (?:any )?chunks?|"
                    + "stop reason:\\s*(?:abort|error|malformed_response|network_error)|"
                    + "request failed after repeated internal retries",
                    Pattern.CASE_INSENSITIVE),

            // POSIX network error codes (Node-style ECONNREFUSED etc., also surface from
            // some JNI / native bridges and proxy responses).
            Pattern.compile("\\b(?:econn(?:refused|reset|aborted)|enetunreach|ehostunreach|"
                    + "ehostdown|enetreset|etimedout|esockettimedout|epipe|enotfound|eai_again|"
                    + "und_err_(?:socket|connect|headers?|body|req_content_length_mismatch|aborted|closed))\\b",
                    Pattern.CASE_INSENSITIVE),

            // 5xx and generic server-error wording
            Pattern.compile("\\bHTTP\\s*5\\d{2}\\b|\\b5\\d{2}\\s+(?:server|error|response)|"
                    + "internal[_ ]server[_ ]error|internal[_ ]error|server[_ ]error|"
                    + "service[_ ]temporarily[_ ]unavailable|service[_ ]unavailable|"
                    + "bad gateway|gateway timeout|upstream (?:error|connect error)",
                    Pattern.CASE_INSENSITIVE),
    };

    private TransientErrorClassifier() {}

    /**
     * Classify a {@code Throwable} as transient (true) or permanent
     * (false). Null is permanent (no signal to retry on).
     */
    public static boolean isTransient(Throwable t) {
        if (t == null) return false;
        var visited = Collections.newSetFromMap(
                new IdentityHashMap<Throwable, Boolean>());
        Throwable cursor = t;
        int depth = 0;
        while (cursor != null && depth < MAX_CAUSE_DEPTH && visited.add(cursor)) {
            if (matchesClassName(cursor) || matchesMessage(cursor.getMessage())) {
                return true;
            }
            cursor = cursor.getCause();
            depth++;
        }
        return false;
    }

    private static boolean matchesClassName(Throwable t) {
        return TRANSIENT_EXCEPTION_CLASSES.contains(t.getClass().getSimpleName());
    }

    private static boolean matchesMessage(String message) {
        if (message == null || message.isEmpty()) return false;
        for (var p : TRANSIENT_PATTERNS) {
            if (p.matcher(message).find()) return true;
        }
        return false;
    }
}
