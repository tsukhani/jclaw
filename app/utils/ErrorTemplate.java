package utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One actionable error, in the three parts a person needs to act on it (JCLAW-1130):
 * what broke, what to check, and how to retry.
 *
 * <p>Deliberately free of Play, JPA and HTTP types. The same template has to render from a
 * controller, from a tool with no request in scope, from a channel sender, and from boot code
 * that runs before the container is up — {@code ErrorTemplates} is the only supported way to
 * obtain one, and {@link ErrorRendering} the only way to turn one into text.
 *
 * <p>{@code howToRetry} is nullable because some failures genuinely have no retry: a password
 * that appears in a breach corpus is not retryable, it is replaceable. Rendering omits the
 * section rather than emitting an empty one, so a caller never has to special-case it.
 *
 * <p><b>Localization keys are laid out, not resolved.</b> The three {@code *Key()} accessors
 * derive stable keys from the error code, so a future message bundle can key off them without
 * a schema change here. English text ships inline until then (i18n proper is v1.x, see the
 * epic). Deriving beats storing: a stored key can disagree with its code, a derived one cannot.
 */
public record ErrorTemplate(@NonNull String code, @NonNull String whatBroke,
                            @NonNull String whatToCheck, @Nullable String howToRetry) {

    public ErrorTemplate {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("error code must not be blank");
        }
        if (whatBroke == null || whatBroke.isBlank()) {
            throw new IllegalArgumentException("whatBroke must not be blank for code " + code);
        }
        if (whatToCheck == null || whatToCheck.isBlank()) {
            throw new IllegalArgumentException("whatToCheck must not be blank for code " + code);
        }
        if (howToRetry != null && howToRetry.isBlank()) {
            throw new IllegalArgumentException(
                    "howToRetry must be null rather than blank for code " + code);
        }
    }

    /** Whether this failure has a retry path at all. */
    public boolean hasRetry() {
        return howToRetry != null;
    }

    public String brokeKey() {
        return "error." + code + ".broke";
    }

    public String checkKey() {
        return "error." + code + ".check";
    }

    public String retryKey() {
        return "error." + code + ".retry";
    }
}
