package tools;

import com.google.gson.JsonObject;

/**
 * JCLAW-659: one normalized event produced by a {@link HarnessAdapter} while
 * parsing a line of external coding-harness output. Adapters translate each
 * harness's own on-the-wire shape (NDJSON tokens, tool-call frames, plain log
 * lines, …) into this common vocabulary so the spawn path can consume any
 * harness uniformly.
 *
 * @param kind one of {@code token} (an incremental output chunk),
 *             {@code tool_call} (the harness invoked a tool),
 *             {@code step} (a coarse progress line, and the tolerant fallback
 *             for a non-JSON line), {@code error} (a harness-reported failure),
 *             or {@code result} (the final answer)
 * @param text the human-readable payload for {@code kind} — token text, tool
 *             name/summary, step description, error message, or final result;
 *             may be blank but never {@code null}
 * @param raw  the JSON object the line parsed to, or {@code null} when the line
 *             was not JSON (a tolerant {@code step} event carrying the raw line)
 */
public record HarnessEvent(String kind, String text, JsonObject raw) {

    /** An incremental output chunk (streaming token). */
    public static final String TOKEN = "token";
    /** The harness invoked a tool. */
    public static final String TOOL_CALL = "tool_call";
    /** A coarse progress line — also the tolerant fallback for a non-JSON line. */
    public static final String STEP = "step";
    /** A harness-reported failure. */
    public static final String ERROR = "error";
    /** The final answer. */
    public static final String RESULT = "result";
}
