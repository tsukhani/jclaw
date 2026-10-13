package utils;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks credentials in text bound for a log. Agents pass keys on command lines and in tool
 * arguments ({@code curl -H "X-Api-Key: ..."}, {@code ?apikey=...}), and {@link services.EventLogger}
 * writes those to the log file and the event table. The name of the header, parameter or variable
 * is kept, so a masked line still says which credential was used.
 *
 * <p>Best effort by construction: it recognizes a credential by the name beside it or by a
 * well-known token format, so a bare secret with neither passes through. It errs toward masking:
 * a value is never judged by its own shape, and a quoted value is masked up to its closing quote.
 *
 * <p>The text is model-controlled, so every pattern is linear: a quantifier that something must
 * follow is bounded, and an unbounded one is possessive and ends its pattern.
 */
public final class SecretRedactor {

    private SecretRedactor() {}

    public static final String MASK = "[redacted]";

    // A logged tool argument is JSON inside JSON: a quote arrives behind a backslash and, from an
    // HTML-safe serializer, = & ' and the double quote arrive as u003d, u0026, u0027, u0022 escapes.
    private static final String BACKSLASHES = "\\\\{1,4}+";
    private static final String QUOTE = "(?:\\\\{0,4}+[\"']|" + BACKSLASHES + "u002[27])";
    private static final String EQUALS = "(?:=|" + BACKSLASHES + "u003[dD])";
    private static final String SEPARATOR = "(?:" + QUOTE + ")?\\s{0,8}+(?::|" + EQUALS + ")\\s{0,8}+(?:" + QUOTE + ")?";
    private static final String NAME_TAIL = "[\\w.-]{0,64}+";
    /** After an opening quote the value runs to the closing one, spaces and commas included; bare, to the end of the word. */
    private static final String VALUE = "(?<v>(?<=[\"']|u0022|u0027)[^\"'\\\\]++|(?<![\"']|u0022|u0027)[^\\s\"'\\\\;&|)]++)";
    /** A header whose value holds spaces: everything up to the closing quote or the end of the line. */
    private static final String REST_OF_VALUE = "(?<v>[^\"'\\\\\\n]++)";

    /** Each pattern names what to keep ({@code keep}) and the credential ({@code v}). */
    private static final List<Pattern> VALUE_AFTER_NAME = List.of(
            // ?apikey=... and &key=... in a URL
            Pattern.compile("(?i)(?<keep>(?:[?&]|" + BACKSLASHES + "u0026)"
                    + "(?:api[-_]?key|access[-_]?token|auth[-_]?token|token|secret|password|passwd|pass|pwd|auth|jwt|signature|sig|key)"
                    + EQUALS + ")(?<v>[^&\\s\"'\\\\#]++)"),
            // Authorization: Bearer ...
            Pattern.compile("(?i)(?<keep>\\bauthorization" + SEPARATOR + "(?:(?:bearer|basic|token|digest)\\s{1,4}+)?)" + REST_OF_VALUE),
            // Cookie: session=...; other=...
            Pattern.compile("(?i)(?<keep>\\bcookie" + SEPARATOR + ")" + REST_OF_VALUE),
            // X-Api-Key: ..., MEDIA_API_KEY=..., "client_secret": "...", "password": "..."
            Pattern.compile("(?i)(?<keep>(?:api[-_ ]?key|access[-_]?key|private[-_]?key|subscription[-_]?key|secret|password|passwd"
                    + "|passphrase)" + NAME_TAIL + SEPARATOR + ")" + VALUE),
            // X-Plex-Token: ..., bot_token=...; a name that goes on past "token" (tokens, tokenCount) is a count,
            // and a page token is a cursor
            Pattern.compile("(?i)(?<keep>(?<!page|page[-_])token" + SEPARATOR + ")" + VALUE),
            // X-Functions-Key: ...
            Pattern.compile("(?i)(?<keep>\\bx-[\\w-]{0,40}?key" + SEPARATOR + ")" + VALUE),
            // SERVICE_KEY=...
            Pattern.compile("(?<keep>\\b[A-Z][A-Z0-9]{0,32}+(?:_[A-Z0-9]{1,32}+){0,6}?_KEY=(?:" + QUOTE + ")?)" + VALUE),
            // --password ..., --token ...
            Pattern.compile("(?i)(?<keep>(?:^|\\s)--(?:password|passwd|passphrase|token|secret|api-?key)(?:\\s{1,4}+|=)(?:" + QUOTE + ")?)"
                    + VALUE),
            // sshpass -p ...
            Pattern.compile("(?i)(?<keep>\\bsshpass\\s{1,4}+-p\\s{0,4}+(?:" + QUOTE + ")?)" + VALUE),
            // a bearer or basic credential named in prose, with no header beside it
            Pattern.compile("(?i)(?<keep>\\b(?:bearer|basic)\\s{1,4}+)(?<v>[A-Za-z0-9._~+/=-]{16,}+)"),
            // https://user:password@host
            Pattern.compile("(?<keep>://[^/\\s:@\"'\\\\]{1,256}+:)(?<v>[^@\\s/\"'\\\\]{1,256}+)(?=@)"),
            // curl -u user:password
            Pattern.compile("(?<keep>(?:^|\\s)(?:-u|--user)\\s{1,4}+(?:" + QUOTE + ")?[^\\s:\"'\\\\]{1,128}+:)(?<v>[^\\s\"'\\\\]++)"));

    /** A config write: {"key": "provider.x.apiKey", "value": "..."}. Masked when the key names a credential. */
    private static final Pattern CONFIG_WRITE = Pattern.compile(
            "(?i)(?<keep>" + QUOTE + "key" + QUOTE + "\\s{0,4}+:\\s{0,4}+" + QUOTE + "(?<cfg>[^\"'\\\\]{1,128}+)" + QUOTE
                    + "\\s{0,4}+,\\s{0,4}+" + QUOTE + "value" + QUOTE + "\\s{0,4}+:\\s{0,4}+" + QUOTE + ")(?<v>[^\"'\\\\]++)");
    private static final Pattern CREDENTIAL_NAME = Pattern.compile("(?i)api[-_]?key|token(?!s)|secret|password|passwd|credential");

    /**
     * Formats recognizable with no name beside them: OpenAI-style, GitHub, Slack, AWS, JWT, Telegram bot,
     * and a PEM private key. The lookbehind keeps a start out of the run a previous start already scanned.
     */
    private static final Pattern TOKEN_SHAPE = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:sk-[A-Za-z0-9_-]{20,}+|gh[pousr]_[A-Za-z0-9]{20,}+|xox[abprs]-[A-Za-z0-9-]{10,}+|AKIA[0-9A-Z]{16}"
                    + "|eyJ[A-Za-z0-9_-]{8,}+\\.[A-Za-z0-9_-]{8,}+\\.[A-Za-z0-9_-]{8,}+|\\d{8,10}:[A-Za-z0-9_-]{35})"
                    + "|-----BEGIN [A-Z ]{0,40}PRIVATE KEY-----[^-]*+(?:-----END [A-Z ]{0,40}PRIVATE KEY-----)?");

    /** {@code text} with every recognized credential's value replaced by {@link #MASK}; unchanged when it holds none. */
    public static String redact(String text) {
        var out = text;
        for (var pattern : VALUE_AFTER_NAME) {
            out = pattern.matcher(out).replaceAll(SecretRedactor::masked);
        }
        out = CONFIG_WRITE.matcher(out).replaceAll(m ->
                CREDENTIAL_NAME.matcher(m.group("cfg")).find() ? masked(m) : Matcher.quoteReplacement(m.group()));
        return TOKEN_SHAPE.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
    }

    private static String masked(MatchResult m) {
        return Matcher.quoteReplacement(m.group("keep") + MASK);
    }
}
