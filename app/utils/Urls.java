package utils;

import java.net.URI;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Parses a URL the way a browser writes one: what {@link URI} rejects, but Chromium and OkHttp send
 * raw after the authority, is percent-encoded first — {@code | ^ { } `} in a query, {@code [ ]} in a
 * path, a space, {@code <} or {@code >}, and a {@code %} that starts no escape (JCLAW-1285).
 *
 * <p>Shared because the scrape path parses URLs the <em>page</em> supplies — a redirect's
 * {@code Location}, a harvested link, a sitemap entry, the browser's own final URL — where strict
 * parsing costs the item, or the whole crawl, over a URL every browser loads (JCLAW-1287). A URL an
 * operator or the model typed stays on {@link URI#create}, so a typo still fails at the edge.
 *
 * <p>The authority is never rewritten, so the host a guard reads is the host the page wrote. A
 * reference with no authority — {@code /p?q=|} — has no host to protect and is encoded whole.
 */
public final class Urls {

    private Urls() {}

    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*");

    /**
     * An absolute URL, a scheme-relative reference ({@code //host/p}) or a relative one
     * ({@code /p?q=|}), parsed leniently.
     *
     * @throws IllegalArgumentException when the URL still does not parse, as {@link URI#create} does
     */
    public static URI parse(String url) {
        return URI.create(encodeTail(url));
    }

    /**
     * {@code reference} resolved against {@code base} — a {@code Location} header or an href, either
     * of which the origin may write relative.
     *
     * @throws IllegalArgumentException when the reference does not parse
     */
    public static URI resolve(URI base, String reference) {
        return base.resolve(parse(reference));
    }

    private static String encodeTail(String url) {
        int tail = tailStart(url);
        StringBuilder out = null;
        for (int i = tail; i < url.length(); i++) {
            char c = url.charAt(i);
            String escape = switch (c) {
                case '|' -> "%7C";
                case '^' -> "%5E";
                case '{' -> "%7B";
                case '}' -> "%7D";
                case '`' -> "%60";
                case '[' -> "%5B";
                case ']' -> "%5D";
                case ' ' -> "%20";
                case '<' -> "%3C";
                case '>' -> "%3E";
                // The one arm that changes what the origin receives: ?off=100% is sent as ?off=100%25.
                case '%' -> startsEscape(url, i) ? null : "%25";
                default -> null;
            };
            if (escape != null && out == null) out = new StringBuilder(url.length() + 16).append(url, 0, i);
            if (out != null) {
                if (escape != null) out.append(escape);
                else out.append(c);
            }
        }
        return out == null ? url : out.toString();
    }

    private static boolean startsEscape(String url, int percent) {
        // ASCII only: URI refuses the Unicode digits Character.digit would accept.
        return percent + 2 < url.length()
                && HexFormat.isHexDigit(url.charAt(percent + 1))
                && HexFormat.isHexDigit(url.charAt(percent + 2));
    }

    /**
     * Where the re-encodable tail begins — the first {@code /}, {@code ?} or {@code #} after the
     * authority, the length when the URL is nothing but an authority, or 0 when there is no
     * authority to protect.
     *
     * <p>Package-private for {@code SsrfGuard.pinnedUrl}, which rebuilds a URL around a pinned host
     * and needs the caller's raw tail rather than {@link #parse}'s re-encoded copy of it.
     */
    static int tailStart(String url) {
        int authority = authorityStart(url);
        if (authority < 0) return 0;
        for (int i = authority; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') return i;
        }
        return url.length();
    }

    /** Just past {@code scheme://} or a scheme-relative {@code //}, or -1 when the reference has no authority. */
    private static int authorityStart(String url) {
        int separator = url.indexOf("://");
        if (separator > 0 && SCHEME.matcher(url).region(0, separator).matches()) return separator + 3;
        return url.startsWith("//") ? 2 : -1;
    }
}
