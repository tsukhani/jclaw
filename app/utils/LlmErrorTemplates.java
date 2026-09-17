package utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

/**
 * Remedies for a failed LLM call (JCLAW-1134) — the partition {@code LlmException}'s subclasses
 * deliberately do not make.
 *
 * <p>Those subclasses name the <em>fault location</em> — ours, the provider's, the breaker's —
 * because that is what a circuit breaker needs. An operator needs the <em>remedy</em>, and the two
 * cuts cross: an invalid key, an exhausted balance, a model the provider does not serve and a
 * prompt that overflows the context window are one undifferentiated {@code ClientError} between
 * them, and send the operator to four different screens. {@link Remedy} is that second cut, and
 * nothing here changes the fault-location classes.
 *
 * <p>Separate from {@link ErrorTemplates} because none of this is keyed off an
 * {@code ApiResponses} code: an LLM failure names the provider and model of the call that
 * produced it, so its template is built per failure rather than looked up from a table.
 *
 * <p><b>No provider text reaches a template.</b> A provider error body is
 * attacker-influenceable (JCLAW-730), so only values this codebase owns are interpolated: the
 * configured provider name, the model id sent on the wire, the already-clamped retry-after, and
 * the context window from our own model catalog. The body stays on the sanitized path, in the
 * exception message and the log entry.
 */
public final class LlmErrorTemplates {

    private LlmErrorTemplates() {}

    /** Not a {@link Remedy}: nothing was sent, so there is no call for the classifier to read. */
    public static final String BASE_URL_REFUSED = "llm_base_url_refused";

    private static final String CHECK_LOGS = "Open Logs and find the matching entry — it carries "
            + "the status and the provider's own message, which this one deliberately omits.";

    /**
     * What an operator has to do about a failed call. Each constant owns an error code, so a
     * future message bundle keys off {@link ErrorTemplate}'s derived keys without a schema
     * change here.
     */
    public enum Remedy {
        INVALID_KEY("llm_invalid_key"),
        QUOTA_EXHAUSTED("llm_quota_exhausted"),
        MODEL_NOT_FOUND("llm_model_not_found"),
        CONTEXT_WINDOW_EXCEEDED("llm_context_window_exceeded"),
        RATE_LIMITED("llm_rate_limited"),
        /** A failure with no remedy of its own — the generic template, naming the call. */
        UNCLASSIFIED("llm_call_failed");

        private final String code;

        Remedy(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * One failed call: its remedy, and the identity of the call itself.
     *
     * <p>{@code provider} and {@code model} are read off the call that failed rather than
     * reconstructed from configuration. They differ: an unpinned task inherits its agent's
     * current model, a failover retries on a second provider entirely, and a tools-unsupported
     * retry re-sends under the same model — so the agent's configured default is not reliably
     * the model that was rejected.
     *
     * @param retryAfterSeconds the wait the provider asked for, already clamped, or null
     * @param contextWindow     this model's window from the provider's catalog, or null when
     *                          the model is not listed
     */
    public record Failure(@NonNull Remedy remedy, @NonNull String provider, @Nullable String model,
                          @Nullable Long retryAfterSeconds, @Nullable Integer contextWindow)
            implements Serializable {}

    /**
     * The remedy for {@code failure}, or a generic model-call template when the failure carries
     * none. Never null and never throws: this is consulted on the failure path, where a lookup
     * with its own failure mode turns a handled error into an unhandled one.
     */
    public static ErrorTemplate forFailure(@Nullable Failure failure) {
        if (failure == null) {
            return new ErrorTemplate(Remedy.UNCLASSIFIED.code(), "The model call failed.",
                    CHECK_LOGS, "Retry the turn.");
        }
        var provider = failure.provider();
        var model = failure.model();
        return switch (failure.remedy()) {
            case INVALID_KEY -> new ErrorTemplate(Remedy.INVALID_KEY.code(),
                    "%s rejected the API key%s.".formatted(provider, forModel(model)),
                    ("Settings → Providers → %s: the key may be missing, mistyped, or revoked at "
                            + "the provider — one rotated there still reads as configured here. "
                            + "The balance is not the problem; a spent account fails differently.")
                            .formatted(provider),
                    "Paste a current %s key, save, and send again.".formatted(provider));
            case QUOTA_EXHAUSTED -> new ErrorTemplate(Remedy.QUOTA_EXHAUSTED.code(),
                    "%s accepted the key, but the account behind it is out of credit or quota%s."
                            .formatted(provider, forModel(model)),
                    ("%s's own billing console, not Settings — the key is valid and the "
                            + "configuration is right, so nothing here will change it. Check the "
                            + "balance, the spend cap and the plan's quota.").formatted(provider),
                    ("Top up or raise the cap at %s, or point the agent at a provider that still "
                            + "has credit.").formatted(provider));
            case MODEL_NOT_FOUND -> new ErrorTemplate(Remedy.MODEL_NOT_FOUND.code(),
                    model != null ? "%s does not serve a model called %s.".formatted(provider, model)
                            : "%s rejected the model this call requested.".formatted(provider),
                    ("That is the model this call actually sent, which is not always the agent's "
                            + "default — a task can pin its own. Check it against the model list "
                            + "for %s in Settings → Providers.").formatted(provider),
                    ("Set a model %s serves, on the agent or on the task that pinned one, then "
                            + "retry.").formatted(provider));
            case CONTEXT_WINDOW_EXCEEDED -> new ErrorTemplate(Remedy.CONTEXT_WINDOW_EXCEEDED.code(),
                    contextExceeded(provider, model, failure.contextWindow()),
                    "Everything counts toward that limit, not just your last message: the system "
                            + "prompt, standing orders, recalled memories, tool results and the "
                            + "whole conversation so far.",
                    "Compact or start a fresh conversation, or switch to a model with a larger "
                            + "context window.");
            case RATE_LIMITED -> new ErrorTemplate(Remedy.RATE_LIMITED.code(),
                    "%s is rate-limiting calls%s.".formatted(provider, forModel(model)),
                    rateLimitDetail(failure.retryAfterSeconds()),
                    ("Wait, then send again. If it keeps happening, check the rate limits on the "
                            + "plan behind the %s key.").formatted(provider));
            case UNCLASSIFIED -> new ErrorTemplate(Remedy.UNCLASSIFIED.code(),
                    "The call to %s%s failed.".formatted(provider, forModel(model)),
                    CHECK_LOGS,
                    "Retry. If it repeats the same way, check %s's status page.".formatted(provider));
        };
    }

    /**
     * A provider call refused before it was sent, on the configured base URL. No retry: the same
     * URL is refused the same way until the operator changes it.
     */
    public static ErrorTemplate baseUrlRefused(@NonNull String provider) {
        return new ErrorTemplate(BASE_URL_REFUSED,
                "%s was not called: its base URL is missing or points somewhere this instance never fetches."
                        .formatted(provider),
                ("Settings → Providers → %s: the base URL must be an http or https URL with a host. "
                        + "Loopback and LAN addresses are allowed for local inference; link-local "
                        + "addresses such as the 169.254.169.254 metadata endpoint are not.").formatted(provider),
                null);
    }

    private static String contextExceeded(String provider, @Nullable String model,
                                          @Nullable Integer contextWindow) {
        if (model == null) {
            return "The request was longer than the context window %s allows.".formatted(provider);
        }
        if (contextWindow == null) {
            return "The request was longer than the context window of %s on %s."
                    .formatted(model, provider);
        }
        return String.format(Locale.ROOT,
                "The request was longer than the context window of %s on %s (%,d tokens).",
                model, provider, contextWindow);
    }

    private static String rateLimitDetail(@Nullable Long retryAfterSeconds) {
        var spent = " Every retry allowed for this call has already been spent.";
        return retryAfterSeconds == null
                ? "Not a fault in the request — the provider is throttling us." + spent
                : "Not a fault in the request — the provider asked for a %d second wait."
                        .formatted(retryAfterSeconds) + spent;
    }

    /** {@code " for model x"}, or nothing when the call carried no model id. */
    private static String forModel(@Nullable String model) {
        return model == null || model.isBlank() ? "" : " for model " + model;
    }

    /**
     * Nothing static to register. A provider failure's template is parameterised by the call
     * that produced it — the provider actually used, the model actually requested, the
     * retry-after the provider returned — so the rows above are built per failure rather than
     * held as table entries, and {@link ErrorTemplates#forCode} falls back for these codes by
     * design. The method exists so this file still satisfies the registry's one-file-per-surface
     * contract (JCLAW-60) and stays visible to its merge.
     */
    static Map<String, ErrorTemplate> templates() {
        return Map.of();
    }
}
