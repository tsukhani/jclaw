package utils;

/**
 * Filename inspection helpers. Centralizes the edge-case logic (leading
 * dot = hidden file, trailing dot = no extension, dot in a parent dir vs.
 * in the leaf itself) so callers don't have to rediscover it each time.
 */
public final class Filenames {

    private Filenames() {}

    /**
     * Return the first non-empty extension found across {@code candidates},
     * preserving the leading dot (e.g. {@code ".wav"}, {@code ".jpg"}).
     * Returns an empty string when no candidate has a well-formed extension.
     *
     * <p>Extension rules:
     * <ul>
     *   <li>A dot must be present and not at position 0 (leading dot = hidden
     *       file with no extension, e.g. {@code .gitignore}).</li>
     *   <li>The dot must not be at the end of the string (trailing dot = no
     *       extension, e.g. {@code foo.}).</li>
     *   <li>If the candidate contains path separators ({@code /} or
     *       {@code \}), the dot must come after the last separator — a dot in
     *       a parent directory name doesn't count (e.g.
     *       {@code "foo.d/no_ext"} → empty).</li>
     * </ul>
     *
     * <p>Use cases: pick between user-supplied filename and fallback path
     * source, salvage an extension for locally-staged downloads, produce a
     * reasonable leaf name when the source has multiple possible labels.
     */
    public static String extensionOf(String... candidates) {
        if (candidates == null) return "";
        for (var c : candidates) {
            var ext = candidateExtension(c);
            if (ext != null) return ext;
        }
        return "";
    }

    private static String candidateExtension(String s) {
        if (s == null) return null;
        int dot = s.lastIndexOf('.');
        if (dot <= 0) return null;
        if (dot == s.length() - 1) return null;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (dot < slash) return null;
        return s.substring(dot);
    }
}
