package utils;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * The pieces every per-surface template table shares (JCLAW-60).
 *
 * <p>Separate from {@link ErrorTemplates} so the dependency runs one way — a surface table
 * reaches here, the registry reaches the surface tables — and no surface file has to reason
 * about being initialized from the class that is initializing it.
 */
final class ErrorTemplateSupport {

    private ErrorTemplateSupport() {}

    static final String CHECK_LOGS = "Open Logs and find the matching entry — it carries "
            + "the technical detail this message deliberately omits.";

    /** Terser construction for a table row, which is otherwise a near-identical pair of calls. */
    static Map.Entry<String, ErrorTemplate> e(String code, String broke, String check,
                                              @Nullable String retry) {
        return Map.entry(code, new ErrorTemplate(code, broke, check, retry));
    }
}
