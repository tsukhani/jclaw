package services.compression;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.Optional;

/**
 * Locates a JSON object/array embedded in a tool-output string that may carry a
 * short leading non-JSON prefix — e.g. {@code jclaw_api}'s {@code "HTTP 200\n"}
 * status line, or a {@code "Result:\n"} preamble. Splits the content into the
 * prefix and the parsed JSON body so the {@link ContentTypeDetector} can route
 * it and the {@link JsonCompressor} can crush the JSON while preserving the
 * prefix.
 *
 * <p>Heuristic, in order: the first {@code &#123;} / {@code [} (within
 * {@link #MAX_PREFIX_CHARS}) begins the JSON, which must parse as an object or
 * array through the end of the string, and must be at least as long as the
 * prefix. That last guard is what stops source code ending in {@code {}} (a
 * valid empty object dwarfed by the code before it) from being misread as JSON,
 * while still accepting "status line + large JSON body".
 *
 * <p>Not handled: a trailing suffix after the JSON (e.g. a closing code fence),
 * or real JSON that appears only after a brace that belongs to the prefix.
 */
public record JsonSpan(String prefix, JsonElement json) {

    /** Cap on how far in the JSON may start — keeps the prefix to status-line territory. */
    private static final int MAX_PREFIX_CHARS = 512;

    public static Optional<JsonSpan> find(String content) {
        if (content == null) return Optional.empty();
        int start = firstJsonStart(content);
        if (start < 0 || start > MAX_PREFIX_CHARS) return Optional.empty();

        var candidate = content.substring(start);
        // candidate.length() is the JSON length; `start` is the prefix length.
        // Require JSON to be the dominant part so a code/text body that merely
        // ends in "{}" / "[]" isn't classified as JSON.
        if (candidate.length() < start) return Optional.empty();

        try {
            var el = JsonParser.parseString(candidate);
            if (el != null && (el.isJsonObject() || el.isJsonArray())) {
                return Optional.of(new JsonSpan(content.substring(0, start), el));
            }
        } catch (Exception _) {
            // Not parseable from the first brace — treat as non-JSON.
        }
        return Optional.empty();
    }

    private static int firstJsonStart(String s) {
        int brace = s.indexOf('{');
        int bracket = s.indexOf('[');
        if (brace < 0) return bracket;
        if (bracket < 0) return brace;
        return Math.min(brace, bracket);
    }
}
