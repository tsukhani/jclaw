package services.compression;

import java.util.regex.Pattern;

/**
 * Heuristic classifier for message contents (JCLAW-460). Routes a raw string
 * to one {@link ContentType} so the compression pipeline can pick the right
 * compressor. No ML dependency — a Gson parse plus anchored regex, well under
 * a millisecond for typical tool outputs.
 *
 * <p>Check order matters and is deliberate:
 * <ol>
 *   <li><b>JSON</b> — decided by {@link JsonSpan}, which parses an object/array
 *       even after a short non-JSON prefix (e.g. a {@code "HTTP 200"} status
 *       line); malformed input fast-fails.</li>
 *   <li><b>CODE</b> — declaration-style keywords anchored to line starts, so a
 *       log line that merely mentions {@code import} doesn't read as code.</li>
 *   <li><b>LOG</b> — log-level tokens ({@code ERROR}, {@code WARN}, …).</li>
 *   <li><b>TEXT</b> — the catch-all when nothing else matches.</li>
 * </ol>
 */
public final class ContentTypeDetector {

    private ContentTypeDetector() {}

    // Declaration-style code signals, anchored to line starts (UNIX_LINES so
    // ^ also matches after \n). Covers Java/Kotlin (package/import/public/…),
    // Python (import / from X import / def), Go (func/package/import), Rust
    // (fn/use), JS/TS (import/export/const/let/var), C/C++ (#include/using).
    private static final Pattern CODE_SIGNAL = Pattern.compile(
            "(?m)^[ \\t]*(?:package |import |from \\w[\\w.]* import |func |def |fn |use |class |interface |"
                    + "public |private |protected |export |const |let |var |#include|using )",
            Pattern.UNIX_LINES);

    // A function / class / method signature anywhere — catches JS
    // `function foo(`, Java methods, and the like even without a leading
    // declaration keyword on its own line.
    private static final Pattern CODE_SIGNATURE = Pattern.compile(
            "(?:function\\s+\\w+\\s*\\(|class\\s+\\w+|def\\s+\\w+\\s*\\(|func\\s+\\w+\\s*\\()");

    private static final Pattern LOG_LEVEL = Pattern.compile(
            "(?m)\\b(?:ERROR|WARN|WARNING|INFO|DEBUG|FATAL|TRACE)\\b");

    /**
     * Classify {@code content}. Null, blank, and otherwise-unrecognized input
     * resolves to {@link ContentType#TEXT} — the safe default, since TEXT is
     * the most conservative (lossiest-resistant) compression path.
     */
    public static ContentType detect(String content) {
        if (content == null) return ContentType.TEXT;
        var trimmed = content.strip();
        if (trimmed.isEmpty()) return ContentType.TEXT;

        if (JsonSpan.find(content).isPresent()) return ContentType.JSON;
        if (CODE_SIGNAL.matcher(content).find() || CODE_SIGNATURE.matcher(content).find()) {
            return ContentType.CODE;
        }
        if (LOG_LEVEL.matcher(content).find()) return ContentType.LOG;
        return ContentType.TEXT;
    }
}
