package utils;

import org.jspecify.annotations.Nullable;

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
 * <p>A pattern finds the name; {@link #valueEnd} then decides where the value ends by following the
 * quoting, since a regex cannot: a quoted value runs to its closing quote at the same escape depth,
 * a value inside a quoted string (a header in {@code -H "..."}) runs to that string's end, and a
 * bare one is a shell word. Where the two readings differ it takes the longer, so it over-masks
 * rather than leave the tail of a value.
 *
 * <p>Best effort by construction: it recognizes a credential by the name beside it or by a
 * well-known format, so a bare secret with neither passes through. A value is never judged by its
 * own shape; a count or a cursor is told apart by its name ({@code tokens}, {@code page_token}).
 *
 * <p>The text is model-controlled, so the work is linear: a pattern's quantifier is bounded or
 * possessive, the scan after a name moves forward only, and the look back for an enclosing quote is
 * capped at {@link #LOOK_BACK} characters.
 */
public final class SecretRedactor {

    private SecretRedactor() {}

    public static final String MASK = "[redacted]";

    private static final int MAX_DEPTH = 32;
    private static final int LOOK_BACK = 512;

    // A logged tool argument is JSON inside JSON: a quote arrives behind backslashes and, from an
    // HTML-safe serializer, = & ' and the double quote arrive as u003d, u0026, u0027, u0022 escapes.
    private static final String BACKSLASHES = "\\\\{1," + MAX_DEPTH + "}+";
    private static final String QUOTE = "(?:\\\\{0," + MAX_DEPTH + "}+[\"']|" + BACKSLASHES + "u002[27])";
    private static final String EQUALS = "(?:=|" + BACKSLASHES + "u003[dD])";
    private static final String GAP = "\\s{0,64}+";
    /** After a name: the closing quote of a JSON key, then : or =. The value starts where it ends. */
    private static final String SEPARATOR = "(?<kq>" + QUOTE + ")?" + GAP + "(?::|" + EQUALS + ")" + GAP;
    private static final String NAME_TAIL = "[\\w.-]{0,128}+";

    /** How far a value with no quote of its own runs when it is not inside a quoted string either. */
    private enum Bare { WORD, LINE }

    /** {@code keyed}: the pattern has a {@code kq} group, set when the name was a quoted JSON key. */
    private record Named(Pattern name, Bare bare, boolean keyed) {}

    /** A match ends where the value begins. */
    private static final List<Named> NAMED = List.of(
            // Authorization: Bearer ..., Cookie: a=b; c=d
            new Named(Pattern.compile("(?i)\\b(?:authorization|cookie)" + SEPARATOR + "(?:(?:bearer|basic|token|digest)\\s{1,4}+)?"), Bare.LINE, true),
            // X-Api-Key: ..., MEDIA_API_KEY=..., "client_secret": "...", "password": "..."
            new Named(Pattern.compile("(?i)(?:api[-_ ]?key|access[-_]?key|private[-_]?key|subscription[-_]?key|secret|password|passwd"
                    + "|passphrase)" + NAME_TAIL + SEPARATOR), Bare.WORD, true),
            // X-Functions-Key: ...
            new Named(Pattern.compile("(?i)\\bx-[\\w-]{0,40}?key" + SEPARATOR), Bare.WORD, true),
            // SERVICE_KEY=...
            new Named(Pattern.compile("\\b[A-Z][A-Z0-9]{0,32}+(?:_[A-Z0-9]{1,32}+){0,6}?_KEY" + EQUALS), Bare.WORD, false),
            // credentials=... and "credentials": "..."; "credentials: a, b" in prose lists names, not values
            new Named(Pattern.compile("(?i)credentials?(?:(?<kq>" + QUOTE + ")" + GAP + ":|" + GAP + EQUALS + ")" + GAP), Bare.WORD, true),
            // --password ..., --token ...
            new Named(Pattern.compile("(?i)(?:^|\\s)--(?:password|passwd|passphrase|token|secret|api-?key)(?:\\s{1,4}+|=)"), Bare.WORD, false),
            // sshpass -p ...
            new Named(Pattern.compile("(?i)\\bsshpass\\s{1,4}+-p\\s{0,4}+"), Bare.WORD, false),
            // curl -u user:password
            new Named(Pattern.compile("(?:^|\\s)(?:-u|--user)\\s{1,4}+(?:" + QUOTE + ")?[^\\s:\"'\\\\]{1,128}+:"), Bare.WORD, false));

    /** ?apikey=... in a URL and &password=... in a form body. {@link #endOfParameter} cuts the value at the next field. */
    private static final Named PARAMETER = new Named(Pattern.compile("(?i)(?:[?&]|" + BACKSLASHES + "u0026)"
            + "(?:api[-_]?key|access[-_]?token|auth[-_]?token|token|secret|password|passwd|pass|pwd|auth|jwt|signature|sig|key)"
            + EQUALS), Bare.WORD, false);

    /** X-Plex-Token: ..., bot_token=..., GITHUB_TOKEN_2=...; {@link #namesACredential} drops counts and cursors. */
    private static final Named TOKEN = new Named(Pattern.compile("(?i)token(?<tail>" + NAME_TAIL + ")" + SEPARATOR), Bare.WORD, true);
    private static final Pattern COUNT_TAIL = Pattern.compile(
            "(?i)^[-_.]?(?:count|budget|limit|usage|used|type|len|length|size|total|estimate|estimated|window|ratio|rate)s?$");
    private static final Pattern PAGE = Pattern.compile("(?i)(?:^|[^a-z0-9])(?:next[-_]?)?page[-_]?$");

    /** Patterns whose value needs no scan: {@code keep} stays, {@code v} is the credential. */
    private static final List<Pattern> SELF_DELIMITED = List.of(
            // a bearer or basic credential named in prose, with no header beside it
            Pattern.compile("(?i)(?<keep>\\b(?:bearer|basic)\\s{1,4}+)(?<v>[A-Za-z0-9._~+/=-]{16,}+)"),
            // https://user:password@host
            Pattern.compile("(?<keep>://[^/\\s:@\"'\\\\]{1,256}+:)(?<v>[^@\\s/\"'\\\\]{1,1024}+)(?=@)"));

    /** A config write: {"key": "provider.x.apiKey", "value": "..."}. Masked when the key names a credential. */
    private static final Named CONFIG_WRITE = new Named(Pattern.compile(
            "(?i)" + QUOTE + "key" + QUOTE + "\\s{0,4}+:\\s{0,4}+" + QUOTE + "(?<cfg>[^\"'\\\\]{1,128}+)" + QUOTE
                    + "\\s{0,4}+,\\s{0,4}+" + QUOTE + "value" + QUOTE + "\\s{0,4}+:\\s{0,4}+"), Bare.WORD, false);
    private static final Pattern CREDENTIAL_NAME = Pattern.compile("(?i)api[-_]?key|token(?!s)|secret|password|passwd|credential");

    /**
     * Formats recognizable with no name beside them: OpenAI-style, GitHub, Slack, AWS, JWT, Telegram bot,
     * and a PEM private key. A JWT's first segment is bounded because a start can recur inside the run
     * it scans; the later segments cannot hold a start, so they need no bound.
     */
    private static final Pattern TOKEN_SHAPE = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:sk-[A-Za-z0-9_-]{20,}+|gh[pousr]_[A-Za-z0-9]{20,}+|xox[abprs]-[A-Za-z0-9-]{10,}+|AKIA[0-9A-Z]{16}"
                    + "|eyJ[A-Za-z0-9_-]{8,512}+\\.[A-Za-z0-9_-]{8,}+\\.[A-Za-z0-9_-]{8,}+|\\d{8,10}:[A-Za-z0-9_-]{35})"
                    + "|-----BEGIN [A-Z ]{0,40}PRIVATE KEY-----[^-]*+(?:-----END [A-Z ]{0,40}PRIVATE KEY-----)?");

    /** {@code text} with every recognized credential's value replaced by {@link #MASK}; unchanged when it holds none. */
    public static String redact(String text) {
        var out = text;
        for (var pattern : SELF_DELIMITED) {
            out = pattern.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group("keep") + MASK));
        }
        out = maskAfter(out, PARAMETER);
        for (var named : NAMED) {
            out = maskAfter(out, named);
        }
        out = maskAfter(out, TOKEN);
        out = maskAfter(out, CONFIG_WRITE);
        return TOKEN_SHAPE.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
    }

    private static String maskAfter(String text, Named rule) {
        var m = rule.name().matcher(text).useTransparentBounds(true).useAnchoringBounds(false);
        StringBuilder out = null;
        int copied = 0;
        int at = 0;
        while (at <= text.length() && m.region(at, text.length()).find()) {
            at = m.end() > m.start() ? m.end() : m.end() + 1;
            if (rule == TOKEN && !namesACredential(text, m)) continue;
            if (rule == CONFIG_WRITE && !CREDENTIAL_NAME.matcher(m.group("cfg")).find()) continue;
            int from = contentStart(text, m.end());
            int to = text.startsWith(MASK, from) ? from + MASK.length()
                    : rule == PARAMETER ? endOfParameter(text, m.start(), m.end(), from)
                    : valueEnd(text, m.end(), from, rule.bare(), rule.keyed() && m.group("kq") != null);
            if (to <= from) continue;
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, copied, from).append(MASK);
            copied = to;
            at = to;
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    /**
     * {@code tokens} and {@code tokenizer} are other words, {@code tokenCount} and {@code token_budget}
     * are counts, and a page token is a cursor.
     */
    private static boolean namesACredential(String text, MatchResult m) {
        var tail = m.group("tail");
        if (!tail.isEmpty()) {
            char last = text.charAt(m.start() + 4);   // the n of "token" as written
            char next = tail.charAt(0);
            boolean sameWord = Character.isLetter(next) && Character.isLowerCase(next) == Character.isLowerCase(last);
            return !sameWord && !COUNT_TAIL.matcher(tail).find();
        }
        return !PAGE.matcher(text.substring(Math.max(0, m.start() - 16), m.start())).find();
    }

    // ---------------------------------------------------------------- where a value ends

    /** A quote as it is written at one escape depth: the character (or its unicode escape) behind {@code depth} backslashes. */
    private record Quote(String token, int depth) {}

    /** The index just past the quote that opens the value at {@code at}, or {@code at} when it opens with none. */
    private static int contentStart(String s, int at) {
        var quote = quoteAt(s, at);
        return quote == null ? at : at + quote.depth() + quote.token().length();
    }

    /** The quote written at {@code at}: its backslashes first, then the character or escape. Null when there is none. */
    private static @Nullable Quote quoteAt(String s, int at) {
        int depth = 0;
        while (at + depth < s.length() && s.charAt(at + depth) == '\\' && depth <= MAX_DEPTH) depth++;
        if (depth > MAX_DEPTH) return null;
        int i = at + depth;
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        if (c == '"' || c == '\'') return new Quote(String.valueOf(c), depth);
        if (depth > 0 && (s.startsWith("u0022", i) || s.startsWith("u0027", i))) return new Quote(s.substring(i, i + 5), depth);
        return null;
    }

    /**
     * Where the value ends. {@code at} is where it starts as written and {@code from} is past its own
     * opening quote, when it has one. {@code jsonKey}: the name was a quoted key, so a bare value is JSON.
     */
    private static int valueEnd(String s, int at, int from, Bare bare, boolean jsonKey) {
        var quote = quoteAt(s, at);
        if (quote == null) quote = enclosing(s, at);
        if (quote != null) {
            int close = closing(s, from, quote);
            return jsonKey ? close : pastShellQuoteIdiom(s, close, quote);
        }
        if (jsonKey) return endOfJsonValue(s, from);
        return bare == Bare.LINE ? endOfLine(s, from) : endOfWord(s, from);
    }

    /**
     * A shell writes a quote inside single quotes as {@code 'it'\''s'}: the word goes on past the quote that
     * closed. Only that idiom continues; a double quote closing a JSON string does not.
     */
    private static int pastShellQuoteIdiom(String s, int close, Quote quote) {
        boolean single = quote.token().equals("'") || quote.token().equals("u0027");
        int after = close + quote.depth() + quote.token().length();
        if (!single || close >= s.length() || s.charAt(close) == '\n' || after > s.length()) return close;
        int i = after;
        while (i < s.length() && s.charAt(i) == '\\' && i - after <= MAX_DEPTH) i++;
        boolean anotherQuote = i < s.length() && (s.charAt(i) == '\'' || s.startsWith("u0027", i));
        return anotherQuote ? endOfWord(s, after) : close;
    }

    /**
     * A field ends at the next {@code &}, written plainly or as an escape, in a URL also at {@code #}, and
     * otherwise where its quoting ends. One forward scan: stopping early must not cost a scan to the far quote.
     */
    private static int endOfParameter(String s, int nameStart, int at, int from) {
        boolean url = s.charAt(nameStart) == '?' || inAQuery(s, nameStart);
        var quote = quoteAt(s, at);
        if (quote == null) quote = enclosing(s, at);
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' || c == '\n' || (url && c == '#')) return i;
            if (quote != null ? closesAt(s, i, quote) : Character.isWhitespace(c) || c == '"' || c == '\'') return i;
            if (c == '\\') {
                int run = i;
                while (run < s.length() && s.charAt(run) == '\\' && run - i <= MAX_DEPTH) run++;
                if (s.startsWith("u0026", run)) return i;
                if (quote == null) i = Math.min(s.length(), run + 1) - 1;   // an escaped character belongs to a bare word
            }
        }
        return s.length();
    }

    /** Whether the text behind {@code at} reaches a {@code ?} before a space or a quote: a URL's query, not a form body. */
    private static boolean inAQuery(String s, int at) {
        for (int i = at - 1; i >= Math.max(0, at - LOOK_BACK); i--) {
            char c = s.charAt(i);
            if (c == '?') return true;
            if (Character.isWhitespace(c) || c == '"' || c == '\'') return false;
        }
        return false;
    }

    /** A number, true, false or null after a JSON key. */
    private static int endOfJsonValue(String s, int from) {
        int i = from;
        while (i < s.length() && ",}]\"'\\".indexOf(s.charAt(i)) < 0 && !Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** The index of the quote that closes one opened as {@code quote}: same token, same depth. Stops at a line end. */
    private static int closing(String s, int from, Quote quote) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\n' || closesAt(s, i, quote)) return i;
        }
        return s.length();
    }

    /** Whether {@code quote} is written starting at {@code i}: exactly its backslashes, not the tail of a longer run. */
    private static boolean closesAt(String s, int i, Quote quote) {
        char c = s.charAt(i);
        if (c != '\\' && c != quote.token().charAt(0)) return false;
        if (i > 0 && s.charAt(i - 1) == '\\') return false;   // inside a longer run: a deeper, embedded quote
        int run = 0;
        while (i + run < s.length() && s.charAt(i + run) == '\\' && run <= quote.depth()) run++;
        return run == quote.depth() && s.startsWith(quote.token(), i + run);
    }

    /**
     * The quoted string {@code at} sits inside, if any: the nearest quote before it on the line is an
     * opening one when an odd number of its kind precede {@code at}. A JSON key's own two quotes cancel out.
     */
    private static @Nullable Quote enclosing(String s, int at) {
        int start = Math.max(0, at - LOOK_BACK);
        for (int i = at - 1; i >= start; i--) {
            if (s.charAt(i) == '\n') {
                start = i + 1;
                break;
            }
        }
        Quote nearest = null;
        for (int i = at - 1; i >= start && nearest == null; i--) nearest = quoteEndingAt(s, i, start, at);
        if (nearest == null) return null;
        int ofItsKind = 0;
        for (int i = start; i < at; i++) {
            if (nearest.equals(quoteEndingAt(s, i, start, at))) ofItsKind++;
        }
        return ofItsKind % 2 == 1 ? nearest : null;
    }

    /** The quote whose character (or the u of whose escape) is at {@code i}, with the backslashes before it. */
    private static @Nullable Quote quoteEndingAt(String s, int i, int start, int limit) {
        char c = s.charAt(i);
        boolean plain = c == '"' || c == '\'';
        boolean escape = c == 'u' && i + 5 <= limit && (s.startsWith("u0022", i) || s.startsWith("u0027", i));
        if (!plain && !escape) return null;
        int depth = 0;
        while (i - depth - 1 >= start && s.charAt(i - depth - 1) == '\\' && depth <= MAX_DEPTH) depth++;
        if (escape && depth == 0) return null;
        return new Quote(plain ? String.valueOf(c) : s.substring(i, i + 5), depth);
    }

    /** A shell word: to the next unquoted whitespace. An escaped character and a quoted stretch belong to it. */
    private static int endOfWord(String s, int from) {
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) break;
            if (c == '\\') {
                i = Math.min(s.length(), i + 2);
            } else if (c == '"' || c == '\'') {
                int close = closing(s, i + 1, new Quote(String.valueOf(c), 0));
                i = Math.min(s.length(), close + 1);
            } else {
                i++;
            }
        }
        return i;
    }

    private static int endOfLine(String s, int from) {
        int end = s.indexOf('\n', from);
        return end < 0 ? s.length() : end;
    }
}
