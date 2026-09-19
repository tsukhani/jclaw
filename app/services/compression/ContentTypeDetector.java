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
 *   <li><b>CODE</b> — {@link CodeCompressor}'s own language hints, so the
 *       detector and the compressor cannot disagree about what code is.</li>
 *   <li><b>LOG</b> — log-level tokens ({@code ERROR}, {@code WARN}, …).</li>
 *   <li><b>TEXT</b> — the catch-all when nothing else matches.</li>
 * </ol>
 */
public final class ContentTypeDetector {

    private ContentTypeDetector() {}

    /** A lone declaration line in a document is a mention, not a listing (JCLAW-1230). */
    private static final int MIN_CODE_SIGNAL_LINES = 2;

    /** Short enough that one declaration line is most of the content, so one signal is enough. */
    private static final int SNIPPET_LINES = 5;

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
        if (isCode(content)) return ContentType.CODE;
        if (LOG_LEVEL.matcher(content).find()) return ContentType.LOG;
        return ContentType.TEXT;
    }

    private static boolean isCode(String content) {
        if (CodeCompressor.detectLanguage(content) == CodeCompressor.Language.UNKNOWN) return false;
        return CodeCompressor.signalLineCount(content) >= MIN_CODE_SIGNAL_LINES
                || content.lines().filter(line -> !line.isBlank()).count() <= SNIPPET_LINES;
    }
}
