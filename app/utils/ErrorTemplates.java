package utils;

import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static utils.ErrorTemplateSupport.CHECK_LOGS;

/**
 * The error code to {@link ErrorTemplate} registry (JCLAW-1130).
 *
 * <p>The rows live one file per surface — {@link ApiErrorTemplates}, {@link ChannelErrorTemplates},
 * {@link LlmErrorTemplates}, {@link StartupErrorTemplates}, {@link ToolErrorTemplates} — and this
 * class only merges them (JCLAW-60). One table made every story that registers a code edit the
 * same lines of the same file, so the lanes conflicted by construction. A surface added later
 * needs a file of its own and a line in the merge below.
 *
 * <p><b>An unregistered code is not an error.</b> {@link #forCode} falls back to a generic
 * template rather than returning null or throwing: this table is consulted on the failure path,
 * and a lookup that fails there turns a handled error into an unhandled one. The fallback is
 * deliberately vague — that is the signal to add a row, not a reason to break the request.
 */
public final class ErrorTemplates {

    private ErrorTemplates() {}

    /** Two surfaces claiming one code fails at class-init rather than one silently winning. */
    private static final Map<String, ErrorTemplate> BY_CODE = Stream.of(
                    ApiErrorTemplates.templates(),
                    ChannelErrorTemplates.templates(),
                    LlmErrorTemplates.templates(),
                    StartupErrorTemplates.templates(),
                    ToolErrorTemplates.templates())
            .flatMap(surface -> surface.entrySet().stream())
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    /**
     * The template for {@code code}, or a generic one when the code has no row yet.
     *
     * <p>Never null and never throws — see the class note on why the failure path must not have
     * its own failure mode.
     */
    public static ErrorTemplate forCode(@NonNull String code) {
        var known = BY_CODE.get(code);
        return known != null ? known : fallback(code);
    }

    /** Whether {@code code} has a specific template, as opposed to falling back. */
    public static boolean isRegistered(@NonNull String code) {
        return BY_CODE.containsKey(code);
    }

    private static ErrorTemplate fallback(String code) {
        return new ErrorTemplate(code, "The operation did not complete.", CHECK_LOGS,
                "Retry the operation.");
    }
}
