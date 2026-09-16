package llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import utils.LlmErrorTemplates.Failure;
import utils.LlmErrorTemplates.Remedy;

import java.util.List;
import java.util.Locale;

/**
 * Which remedy a provider's rejection needs (JCLAW-1134).
 *
 * <p>The {@code LlmException} subclasses partition by fault location, which is what the circuit
 * breaker reads; this partitions the same failures by what an operator has to do about them. The
 * two are orthogonal on purpose — an invalid key, an exhausted balance, an unserved model and an
 * overlong prompt are all one {@code ClientError}, and nothing here changes that.
 *
 * <p>Detection reads the <em>raw</em> body, never the sanitized one: redaction and the 500-char
 * cap can land mid-code. Nothing read here is ever interpolated into a message — only the
 * {@link Remedy} escapes — so the sanitized copy remains the only body text an operator sees.
 */
public final class LlmFailureClassifier {

    private LlmFailureClassifier() {}

    /**
     * Error codes on a 429 that mean the balance is gone rather than the rate being too high
     * (JCLAW-929). Waiting never clears these, so retrying only burns the remaining attempts and
     * hammers the provider — one exhausted-balance backfill of 616 rows spent four attempts
     * each, about 11 seconds per row, embedding none.
     *
     * <p>Matched against the error {@code code}/{@code type} field, never free text: OpenAI
     * separates {@code rate_limit_exceeded} from {@code insufficient_quota} by code alone, and
     * its rate-limit copy has itself used the word "quota". Misreading a transient limit as
     * permanent turns a recoverable call into a hard failure, so this list stays narrow and
     * additions need the same evidence.
     */
    private static final List<String> QUOTA_CODES =
            List.of("insufficient_quota", "credit_balance_exhausted");

    private static final List<String> AUTH_CODES = List.of("invalid_api_key", "authentication_error",
            "invalid_authentication", "invalid_request_authentication", "unauthorized");

    private static final List<String> MODEL_CODES =
            List.of("model_not_found", "model_not_available", "invalid_model", "unknown_model");

    private static final List<String> CONTEXT_CODES =
            List.of("context_length_exceeded", "context_window_exceeded", "prompt_too_long");

    /**
     * Free-text fallbacks, consulted only for a 4xx and only after the code position has had its
     * say. Plenty of OpenAI-compatible servers send no {@code code} at all — Ollama answers a
     * missing model with a bare {@code {"error":"model 'x' not found, try pulling it first"}} —
     * so a code-only rule would leave the two commonest local failures unclassified.
     *
     * <p>Narrower than they look: inside a rejection body "context window" and "model not found"
     * say what they mean. The ambiguous word is "quota", which is why quota detection above stays
     * on the code position and has no phrase list at all.
     */
    private static final List<String> CONTEXT_PHRASES = List.of("maximum context length",
            "context length exceeded", "context window", "prompt is too long", "too many tokens",
            "reduce the length of the messages");

    private static final List<String> MODEL_PHRASES = List.of("model not found", "no such model",
            "unknown model", "invalid model", "model does not exist", "is not a valid model",
            "try pulling it first");

    /**
     * The remedy for a non-200 answer.
     *
     * <p>A 429 is settled before anything else and yields only {@link Remedy#QUOTA_EXHAUSTED} or
     * {@link Remedy#RATE_LIMITED}: the retry loop keys off exactly that distinction, so widening
     * it here would change which calls get retried.
     *
     * @param body the provider's raw response body; may be null, empty, or not JSON
     */
    public static Remedy classify(int status, @Nullable String body) {
        var signals = Signals.from(body);
        if (status == 429) {
            return signals.hasCode(QUOTA_CODES) ? Remedy.QUOTA_EXHAUSTED : Remedy.RATE_LIMITED;
        }
        if (signals.hasCode(QUOTA_CODES) || status == 402) return Remedy.QUOTA_EXHAUSTED;
        if (signals.hasCode(AUTH_CODES) || status == 401) return Remedy.INVALID_KEY;
        if (signals.hasCode(CONTEXT_CODES)) return Remedy.CONTEXT_WINDOW_EXCEEDED;
        if (signals.hasCode(MODEL_CODES)) return Remedy.MODEL_NOT_FOUND;
        if (status >= 400 && status < 500) {
            if (signals.mentions(CONTEXT_PHRASES)) return Remedy.CONTEXT_WINDOW_EXCEEDED;
            if (signals.mentions(MODEL_PHRASES)) return Remedy.MODEL_NOT_FOUND;
        }
        return Remedy.UNCLASSIFIED;
    }

    /**
     * The provider and model of one in-flight call, so its failure can name what actually failed.
     * Built from the request being sent rather than from the agent's configuration — an unpinned
     * task inherits its agent's current model, and a failover moves the call to a different
     * provider altogether.
     *
     * @param contextWindow the model's window from this provider's catalog, or null when the
     *                      model is not listed there
     */
    public record CallSite(String provider, @Nullable String model,
                           @Nullable Integer contextWindow) {

        public Failure failure(Remedy remedy, @Nullable Long retryAfterSeconds) {
            return new Failure(remedy, provider, model, retryAfterSeconds, contextWindow);
        }

        /** The failure for a non-200 answer, classified from its raw body. */
        public Failure classifying(int status, @Nullable String body) {
            return failure(classify(status, body), null);
        }
    }

    /** The three positions a provider states an error in, lowercased once. */
    private record Signals(@Nullable String code, @Nullable String type, @Nullable String message) {

        static Signals from(@Nullable String body) {
            if (body == null || body.isBlank()) return new Signals(null, null, null);
            var error = errorObject(body);
            if (error == null) return new Signals(null, null, lower(body));
            return new Signals(lower(string(error, "code")), lower(string(error, "type")),
                    lower(string(error, "message")));
        }

        /** {@code List.of(...).contains(null)} throws, and an absent field is the common case. */
        boolean hasCode(List<String> codes) {
            return (code != null && codes.contains(code)) || (type != null && codes.contains(type));
        }

        boolean mentions(List<String> phrases) {
            if (message == null) return false;
            return phrases.stream().anyMatch(message::contains);
        }

        /**
         * The {@code error} object, or null when the body is not JSON or states its error some
         * other way — a bare string, a top-level message. Both of those still reach the phrase
         * list, through the whole-body fallback in {@link #from}.
         */
        private static @Nullable JsonObject errorObject(String body) {
            try {
                var root = JsonParser.parseString(body);
                if (!root.isJsonObject()) return null;
                var error = root.getAsJsonObject().get("error");
                return error != null && error.isJsonObject() ? error.getAsJsonObject() : null;
            } catch (Exception _) {
                return null;
            }
        }

        private static @Nullable String string(JsonObject obj, String field) {
            var el = obj.get(field);
            return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
        }

        private static @Nullable String lower(@Nullable String s) {
            return s == null ? null : s.toLowerCase(Locale.ROOT);
        }
    }
}
