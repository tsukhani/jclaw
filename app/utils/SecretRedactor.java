package utils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks credentials in text bound for a log. Agents pass keys on command lines and in tool
 * arguments ({@code curl -H "X-Api-Key: ..."}, {@code ?apikey=...}), and {@link services.EventLogger}
 * writes those to the log file and the event table. The name of the header, parameter or variable
 * is kept, so a masked line still says which credential was used.
 */
public final class SecretRedactor {

    private SecretRedactor() {}

    public static final String MASK = "[redacted]";

    // A logged tool argument is JSON, so the quotes around a value can arrive backslash-escaped.
    private static final String QUOTE = "\\\\*+[\"']?";
    private static final String SEPARATOR = QUOTE + "\\s*+[:=]\\s*+" + QUOTE;
    private static final String VALUE = "[^\\s\"'\\\\,;&}]+";
    // Possessive: a name runs to its separator, and backtracking into a long word run is quadratic.
    private static final String NAME_TAIL = "[\\w.-]*+";

    /** Each pattern captures what to keep in group 1 and the credential in group 2. */
    private static final List<Pattern> VALUE_AFTER_NAME = List.of(
            // ?apikey=... and &key=... in a URL
            Pattern.compile("(?i)([?&](?:api[-_]?key|access[-_]?token|auth[-_]?token|token|secret|password|signature|sig|key)=)"
                    + "([^&\\s\"'\\\\#]+)"),
            // Authorization: Bearer ...
            Pattern.compile("(?i)(\\bauthorization" + SEPARATOR + "(?:(?:bearer|basic|token)\\s+)?)(" + VALUE + ")"),
            // X-Api-Key: ..., MEDIA_API_KEY=..., "client_secret": "...", "password": "..."
            Pattern.compile("(?i)((?:api[-_]?key|access[-_]?key|private[-_]?key|secret|password|passwd)" + NAME_TAIL
                    + SEPARATOR + ")(" + VALUE + ")"),
            // a config write: {"key": "provider.x.apiKey", "value": "..."}
            Pattern.compile("(?i)(" + QUOTE + "key" + QUOTE + "\\s*+:\\s*+" + QUOTE
                    + "[^\"'\\\\]*(?:api[-_]?key|token|secret|password|passwd)[^\"'\\\\]*" + QUOTE
                    + "\\s*+,\\s*+" + QUOTE + "value" + QUOTE + "\\s*+:\\s*+" + QUOTE + ")([^\"'\\\\]+)"),
            // a bearer token named in prose, with no header beside it
            Pattern.compile("(?i)(\\bbearer\\s+)([A-Za-z0-9._~+/=-]{16,})"),
            // https://user:password@host
            Pattern.compile("(://[^/\\s:@\"'\\\\]+:)([^@\\s/\"'\\\\]+)(?=@)"),
            // curl -u user:password
            Pattern.compile("((?:^|\\s)(?:-u|--user)\\s+" + QUOTE + "[^\\s:\"'\\\\]+:)([^\\s\"'\\\\]+)"));

    /** X-Plex-Token: ..., bot_token=...; "tokens" is a count, and so is a short or all-digit value. */
    private static final Pattern TOKEN_AFTER_NAME = Pattern.compile(
            "(?i)(token(?!s)" + NAME_TAIL + SEPARATOR + ")(" + VALUE + ")");

    /** Token formats recognizable with no name beside them: OpenAI-style, GitHub, Slack, AWS, JWT, Telegram bot. */
    private static final Pattern TOKEN_SHAPE = Pattern.compile(
            "\\bsk-[A-Za-z0-9_-]{20,}|\\bgh[pousr]_[A-Za-z0-9]{20,}|\\bxox[abprs]-[A-Za-z0-9-]{10,}|\\bAKIA[0-9A-Z]{16}\\b"
                    + "|\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}|\\b\\d{8,10}:[A-Za-z0-9_-]{35}");

    /** {@code text} with every credential's value replaced by {@link #MASK}; unchanged when it holds none. */
    public static String redact(String text) {
        var out = text;
        for (var pattern : VALUE_AFTER_NAME) {
            out = pattern.matcher(out).replaceAll(m -> Matcher.quoteReplacement(
                    isAFlag(m.group(2)) ? m.group() : m.group(1) + MASK));
        }
        out = TOKEN_AFTER_NAME.matcher(out).replaceAll(m -> Matcher.quoteReplacement(
                isACount(m.group(2)) || isAFlag(m.group(2)) ? m.group() : m.group(1) + MASK));
        return TOKEN_SHAPE.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
    }

    /** {@code "hasAppSecret": true} states that a credential exists; it is not one. */
    private static boolean isAFlag(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("null");
    }

    private static boolean isACount(String value) {
        return value.length() < 8 || value.chars().allMatch(Character::isDigit);
    }
}
