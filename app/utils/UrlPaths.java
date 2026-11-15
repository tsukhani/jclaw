package utils;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A request path as a server routes it (JF-374), so a rule naming one path cannot be slipped past with a spelling of
 * it: unreserved {@code %XX} escapes decoded and the rest written in capitals, repeated slashes collapsed, dot
 * segments resolved, a trailing slash dropped, and the query string ignored. {@link #normalize} keeps the case and
 * the {@code ;} parameters, as the operator wrote the path; a match compares as the most lenient server routes,
 * ignoring both, reads an escaped slash, and a doubly escaped slash or dot, every way a server may, and matches a last
 * segment with a format suffix such as {@code .json} without it too, as a server that renders formats may route one:
 * the request's suffix dropped against the rule as written, or the rule's against the request as written, never both,
 * so {@code /v1/users/john.doe} is no {@code /v1/users/john.smith}.
 */
public final class UrlPaths {

    private static final String HEX = "0123456789ABCDEF";
    /** An escaped slash or backslash: one server reads it as a separator, another keeps it in the segment. */
    private static final Pattern ESCAPED_SEPARATOR = Pattern.compile("(?i)%2F|%5C");
    /** A slash, backslash or dot escaped twice: a server that decodes twice reads it as the character. */
    private static final Pattern DOUBLY_ESCAPED = Pattern.compile("(?i)%25(?:2F|5C|2E)");
    private static final Pattern ESCAPED_PER_CENT = Pattern.compile("(?i)%25(?=[0-9a-f]{2})");
    /** A last segment ending in a format, as in {@code current.json}: the segment before it, and the suffix. */
    private static final Pattern FORMAT_SUFFIX = Pattern.compile("(.*/[^/]+)\\.[a-z0-9]{1,5}");

    /**
     * The forms a server may route a request path as, each as {@link #forMatching} writes one. {@code ambiguous} when
     * the path holds an escaped slash or backslash, or a doubly escaped slash, backslash or dot: the forms then read
     * it as a slash before the dot segments are resolved, as a slash after, and kept, and decoded twice too, and which
     * one the service uses cannot be known.
     */
    public record Readings(Set<String> forms, boolean ambiguous) {}

    private UrlPaths() {}

    /**
     * {@code path} normalized, or null when it cannot be: it does not start with {@code /}, holds a malformed escape or
     * a {@code #}, or an encoded slash or a backslash, which one server reads as a separator and another does not.
     */
    public static @Nullable String normalize(String path) {
        var raw = beforeQuery(path);
        if (!raw.startsWith("/")) return null;
        var decoded = decode(raw, true);
        return decoded == null ? null : join(resolve(decoded, false));
    }

    /** A normalized path in the form a match compares: each segment's {@code ;} parameters dropped, in lower case. */
    public static String forMatching(String path) {
        return join(resolve(path, true)).toLowerCase(Locale.ROOT);
    }

    /**
     * How a server may read {@code path}, the encoded path of a request: none when it does not start with {@code /}.
     * A malformed escape, a {@code #} or a backslash reaches the server as the bytes it is, and is read so.
     */
    public static Readings readings(String path) {
        var raw = beforeQuery(path);
        if (!raw.startsWith("/")) return new Readings(Set.of(), false);
        var decoded = decode(raw, false);
        if (decoded == null) return new Readings(Set.of(), false);
        var forms = new LinkedHashSet<String>();
        boolean ambiguous = addReadings(forms, decoded);
        if (DOUBLY_ESCAPED.matcher(decoded).find()) {
            var twice = decode(ESCAPED_PER_CENT.matcher(decoded).replaceAll("%"), false);
            if (twice != null) addReadings(forms, twice);
            ambiguous = true;
        }
        return new Readings(Set.copyOf(forms), ambiguous);
    }

    /** Add the forms {@code decoded} is read as; true when it holds an escaped slash, which it is read every way. */
    private static boolean addReadings(Set<String> forms, String decoded) {
        var kept = forMatching(decoded);
        forms.add(kept);
        if (!ESCAPED_SEPARATOR.matcher(decoded).find()) return false;
        forms.add(forMatching(asSlashes(decoded)));
        forms.add(forMatching(asSlashes(kept)));
        return true;
    }

    /**
     * Whether a request read as {@code readings} reaches {@code rulePath}, a path {@link #normalize} wrote: one of its
     * forms is the rule's, or is it once the request's format suffix or the rule's is dropped, so a rule saved as
     * {@code /v1/sessions.json} matches the request {@code /v1/sessions} a server routes the same.
     */
    public static boolean matches(Readings readings, String rulePath) {
        var rule = forMatching(rulePath);
        var bareRule = withoutSuffix(rule);
        for (var form : readings.forms()) {
            if (form.equals(rule) || rule.equals(withoutSuffix(form)) || form.equals(bareRule)) return true;
        }
        return false;
    }

    private static @Nullable String withoutSuffix(String form) {
        var suffixed = FORMAT_SUFFIX.matcher(form);
        return suffixed.matches() ? suffixed.group(1) : null;
    }

    private static String beforeQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private static String asSlashes(String path) {
        return ESCAPED_SEPARATOR.matcher(path).replaceAll("/");
    }

    private static Deque<String> resolve(String path, boolean dropParameters) {
        Deque<String> segments = new ArrayDeque<>();
        for (var segment : path.split("/", -1)) {
            // Parameters go first, as Tomcat strips them, so "..;x" climbs a segment as ".." does.
            int parameters = dropParameters ? segment.indexOf(';') : -1;
            var name = parameters < 0 ? segment : segment.substring(0, parameters);
            if (name.isEmpty() || name.equals(".")) continue;
            if (name.equals("..")) {
                segments.pollLast();
            } else {
                segments.addLast(name);
            }
        }
        return segments;
    }

    private static String join(Deque<String> segments) {
        return "/" + String.join("/", segments);
    }

    /**
     * {@code raw} with each unreserved escape decoded and every other byte that is not plain path text escaped in
     * capitals. {@code strict}, null for what has no single reading: a malformed escape, a {@code #}, an escaped slash
     * or backslash, or a backslash; otherwise each of those is kept as the bytes it is.
     */
    private static @Nullable String decode(String raw, boolean strict) {
        var out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int escaped = escapedByte(raw, i);
            if (escaped >= 0) {
                if (strict && (escaped == '/' || escaped == '\\')) return null;
                appendByte(out, escaped);
                i += 2;
            } else if (c == '%' || c == '\\' || c == '#') {
                if (strict) return null;
                escape(out, c);
            } else if (c <= 0x20 || c >= 0x7f) {
                int cp = raw.codePointAt(i);
                for (byte b : Character.toString(cp).getBytes(StandardCharsets.UTF_8)) escape(out, b & 0xff);
                i += Character.charCount(cp) - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The byte a {@code %XX} escape at {@code i} encodes, or -1 when no escape starts there. */
    private static int escapedByte(String raw, int i) {
        if (raw.charAt(i) != '%' || i + 2 >= raw.length()) return -1;
        int hi = Character.digit(raw.charAt(i + 1), 16);
        int lo = Character.digit(raw.charAt(i + 2), 16);
        return hi < 0 || lo < 0 ? -1 : hi << 4 | lo;
    }

    /** A decoded byte as path text: itself when unreserved, else escaped in capitals. */
    private static void appendByte(StringBuilder out, int b) {
        if (unreserved(b)) out.append((char) b);
        else escape(out, b);
    }

    private static boolean unreserved(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_'
                || c == '~';
    }

    private static void escape(StringBuilder out, int b) {
        out.append('%').append(HEX.charAt(b >> 4)).append(HEX.charAt(b & 0xf));
    }
}
