package utils;

import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import net.dankito.readability4j.Readability4J;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.apache.tika.Tika;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.jsoup.Jsoup;
import services.ConfigService;
import services.EventLogger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * SSRF-guarded fetch plus readable-content extraction, shared by {@code web_fetch}
 * and {@code web_scrape} (JCLAW-1082).
 *
 * <p>Extracted from {@code WebFetchTool} rather than duplicated: a crawl runs this
 * chain once per page, and two copies would drift on exactly the details that took
 * bug reports to get right — the manual redirect loop, the byte-bounded read, the
 * Readability-then-Jsoup fallback.
 *
 * <p>The OkHttp client is a parameter, not a static here. {@code WebFetchTool} owns
 * its guarded client as the seam its tests substitute, and passing it in keeps that
 * seam working while making the dependency explicit for other callers.
 */
public final class WebExtraction {

    private WebExtraction() {}

    public static final int MAX_TEXT_LENGTH = 50_000;
    public static final int MAX_REDIRECTS = 5;

    /** Markdown preferred, HTML still acceptable. Sites that ignore this simply serve
     *  HTML and the existing pipeline runs unchanged. */
    private static final String DEFAULT_ACCEPT = "text/markdown, text/html;q=0.9, */*;q=0.8";

    /** Below this many extracted characters the Readability pass is treated as a
     *  miss and the Jsoup boilerplate-strip fallback runs instead — small pages
     *  and non-article fragments aren't article-shaped enough to score well. */
    private static final int MIN_READABILITY_CHARS = 200;

    /** Cap on the raw response bytes buffered into the heap per fetch. The body
     *  comes from an untrusted, LLM-supplied URL and the {@link SsrfGuard} client
     *  sets no read/body limit, so a large or slow response — multiplied across
     *  the parallel virtual-thread fetches — could OOM the shared JVM. 10 MiB by
     *  default: comfortably above a typical article PDF, yet small enough that
     *  many concurrent fetches can't exhaust the heap. */
    private static final long DEFAULT_MAX_BODY_BYTES = 10L * 1024 * 1024;
    private static final String CFG_MAX_BODY_BYTES = "web_fetch.max-body-bytes";

    /** Comma-separated outbound host allowlist; see {@link #assertHostAllowed(URI)}. */
    private static final String CFG_ALLOWLIST = "web_fetch.allowlist";

    private static final FlexmarkHtmlConverter HTML_TO_MARKDOWN =
            FlexmarkHtmlConverter.builder(new MutableDataSet()
                    // Suppress the {#id} inline-attribute annotations flexmark emits
                    // for element ids. Parsoid-rendered HTML (e.g. Wikipedia) tags
                    // nearly every node with an id, which is pure noise in LLM-facing
                    // markdown.
                    .set(FlexmarkHtmlConverter.OUTPUT_ATTRIBUTES_ID, false)).build();

    /** Shared and stateless, like {@link #HTML_TO_MARKDOWN} — flexmark parsers are
     *  documented as thread-safe once built. */
    private static final Parser MARKDOWN_PARSER =
            Parser.builder().build();

    /** Shared and configured once (never mutated per-call) so {@code parseToString}
     *  stays thread-safe under the parallel tool dispatch. Reuses TikaHolder's instance
     *  rather than constructing a second one, which would re-walk the parser registry.
     *  The cap is set here because this is the only parseToString caller — every other
     *  TikaHolder.TIKA consumer calls detect(), which ignores it. Sitting above
     *  MAX_TEXT_LENGTH keeps truncation this class's decision, with its explanatory
     *  marker, rather than a silent cut by Tika. */
    private static final Tika TIKA = TikaHolder.TIKA;
    static {
        TIKA.setMaxStringLength(MAX_TEXT_LENGTH + 10_000);
    }

    /** Raw fetch result: undecoded body bytes plus the response Content-Type and
     *  the final (post-redirect) URL. Bytes — not a decoded String — so binary
     *  documents (PDF, Office) reach Tika intact.
     *
     *  <p>A record component of array type gets reference equality from the compiler,
     *  which reads as value equality at the call site and is not. The three methods are
     *  written out so the type means what its shape advertises; {@code toString} reports
     *  the body's length rather than dumping several MiB of bytes into a log line. */
    public record FetchResult(byte[] body, String contentType, String finalUrl) {

        @Override
        public boolean equals(Object o) {
            return o instanceof FetchResult(byte[] b, String ct, String url)
                    && Arrays.equals(body, b)
                    && Objects.equals(contentType, ct)
                    && Objects.equals(finalUrl, url);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(body), contentType, finalUrl);
        }

        @Override
        public String toString() {
            return "FetchResult[body=%d bytes, contentType=%s, finalUrl=%s]"
                    .formatted(body == null ? 0 : body.length, contentType, finalUrl);
        }
    }

    /** Signals an outbound host the operator's allowlist doesn't cover. A distinct
     *  type from the {@link SecurityException} {@link SsrfGuard} throws, which
     *  reports a different refusal. */
    public static final class HostNotAllowedException extends RuntimeException {
        HostNotAllowedException(String host) {
            super(("Error: host '%s' is not on the operator's web_fetch allowlist (config %s). "
                    + "Ask the operator to add it if this fetch is intended.")
                    .formatted(host, CFG_ALLOWLIST));
        }
    }

    /**
     * One HTTP exchange with redirects <em>not</em> followed — the transport a fetch
     * lane plugs in. Rung 1 supplies OkHttp; rung 2 supplies the TLS-impersonation
     * sidecar (JCLAW-1087).
     *
     * <p>The redirect walk deliberately lives in {@link #fetch(String, Map, Transport)}
     * rather than in each transport, so both lanes share one SsrfGuard-per-hop loop.
     * A transport that followed redirects itself would hide hops from the guard.
     */
    @FunctionalInterface
    public interface Transport {
        Exchange exchange(URI uri, Map<String, String> headers) throws IOException;
    }

    /**
     * A single completed exchange. {@code location} is the raw {@code Location}
     * header and is non-null only on a 3xx; {@code body} is empty on one, because
     * the redirect walk never reads it.
     *
     * <p>Explicit {@code equals}/{@code hashCode}/{@code toString} for the same
     * reason {@link FetchResult} has them: a {@code byte[]} component would
     * otherwise get reference equality that reads as value equality.
     */
    public record Exchange(int status, byte[] body, String contentType, String location) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Exchange(int st, byte[] b, String ct, String loc)
                    && status == st
                    && Arrays.equals(body, b)
                    && Objects.equals(contentType, ct)
                    && Objects.equals(location, loc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, Arrays.hashCode(body), contentType, location);
        }

        @Override
        public String toString() {
            return "Exchange[status=%d, body=%d bytes, contentType=%s, location=%s]"
                    .formatted(status, body == null ? 0 : body.length, contentType, location);
        }
    }

    /** The shipped rung-1 transport: OkHttp, redirects left to the caller. */
    public static Transport okHttpTransport(OkHttpClient client) {
        return (uri, headers) -> {
            var builder = new Request.Builder().url(uri.toString()).get();
            headers.forEach(builder::header);
            try (var response = client.newCall(builder.build()).execute()) {
                int code = response.code();
                var contentType = response.header("Content-Type", "");
                // A redirect's body is never read: the walk only needs Location, and
                // reading it would buffer a page we are about to discard.
                var body = code >= 300 && code < 400
                        ? new byte[0]
                        : readBounded(response.body(), uri);
                return new Exchange(code, body, contentType, response.header("Location"));
            }
        };
    }

    public static FetchResult fetch(String url, OkHttpClient client, Map<String, String> headers)
            throws IOException {
        return fetch(url, headers, okHttpTransport(client));
    }

    /**
     * Fetch a URL through a {@link SsrfGuard}ed client. Redirects are followed
     * manually, up to {@link #MAX_REDIRECTS}, so each hop is re-validated through
     * {@link SsrfGuard#assertSafeScheme(URI)} and the outbound allowlist, and
     * re-resolved through the guarded DNS.
     *
     * <p>Only the final (non-redirect) response body is read, and it is read as raw
     * bytes — never a decoded String — so binary documents survive intact for Tika.
     * The read is size-bounded through {@link #readBounded}.
     *
     * @param headers request headers, so a caller can present a different client
     *                identity without forking this loop
     */
    public static FetchResult fetch(String url, Map<String, String> headers, Transport transport)
            throws IOException {
        // Ask for markdown first (JCLAW-1101). A growing number of documentation sites
        // publish an agent-oriented markdown rendering alongside the HTML, and taking
        // them up on it skips the whole Readability-plus-flexmark reconstruction: one
        // docs.openclaw.ai page is 66,184 bytes of markup or 5,673 bytes of markdown
        // carrying the same text. Advisory only — the response Content-Type decides what
        // actually happens, never the request.
        if (headers.keySet().stream().noneMatch(h -> h.equalsIgnoreCase("Accept"))) {
            var withAccept = new LinkedHashMap<>(headers);
            withAccept.put("Accept", DEFAULT_ACCEPT);
            headers = Map.copyOf(withAccept);
        }
        var current = URI.create(url);
        SsrfGuard.assertSafeScheme(current);
        assertHostAllowed(current);

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            var exchange = transport.exchange(current, headers);
            int code = exchange.status();

            // Follow 3xx here, never in the transport, so every hop re-enters SsrfGuard.
            if (code >= 300 && code < 400) {
                var location = exchange.location();
                if (location == null || location.isBlank()) {
                    throw new IOException(
                            "HTTP %d with no Location header for %s".formatted(code, current));
                }
                current = current.resolve(location);
                SsrfGuard.assertSafeScheme(current);
                assertHostAllowed(current);
                continue;
            }

            if (code >= 400) {
                throw new IOException("HTTP %d fetching %s".formatted(code, current));
            }

            return new FetchResult(exchange.body(), exchange.contentType(), current.toString());
        }
        throw new IOException("Too many redirects (>%d) fetching %s".formatted(MAX_REDIRECTS, url));
    }

    /**
     * Refuse an outbound host that is absent from the operator's allowlist
     * ({@link #CFG_ALLOWLIST}, comma-separated). Unset or blank means no
     * restriction — the shipped default, because deny-by-default would break
     * web_fetch on every existing install. An entry matches that host and any
     * subdomain of it. Enforced on every redirect hop, so a listed host can't
     * bounce the fetch onward to one the operator never listed.
     */
    private static void assertHostAllowed(URI uri) {
        var raw = ConfigService.get(CFG_ALLOWLIST, "").strip();
        if (raw.isEmpty()) {
            return;
        }
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        for (var entry : raw.split(",")) {
            var allowed = entry.strip().toLowerCase(Locale.ROOT);
            if (!allowed.isEmpty() && (host.equals(allowed) || host.endsWith("." + allowed))) {
                return;
            }
        }
        throw new HostNotAllowedException(host);
    }

    /**
     * Buffer at most {@link #maxBodyBytes()} of an untrusted response body into
     * the heap, so a large or slow LLM-supplied URL can't OOM the shared JVM.
     * Two layered guards:
     * <ol>
     *   <li>a declared {@code Content-Length} over the cap is rejected before a
     *       single body byte is read;</li>
     *   <li>the read itself is bounded — okio buffers only {@code cap + 1} bytes
     *       (rounded up to its segment size), so a server that omits or lies
     *       about {@code Content-Length} still can't push more than ~cap onto
     *       the heap.</li>
     * </ol>
     */
    public static byte[] readBounded(ResponseBody body, URI url) throws IOException {
        long cap = maxBodyBytes();
        long declared = body.contentLength();
        if (declared > cap) {
            throw new IOException("Response body too large (%d bytes, limit %d) fetching %s"
                    .formatted(declared, cap, url));
        }
        var source = body.source();
        // request(cap + 1) reads segment by segment only until the buffer holds
        // cap + 1 bytes (or the source is exhausted) — never the whole stream.
        if (source.request(cap + 1)) {
            return source.readByteArray(cap); // more than the cap available → keep the capped prefix
        }
        return source.readByteArray(); // whole body fit under the cap
    }

    /** Per-fetch heap cap for the raw response body ({@link #CFG_MAX_BODY_BYTES},
     *  default {@link #DEFAULT_MAX_BODY_BYTES}). Public so an out-of-process
     *  transport can be told the same cap and enforce it at its own end. */
    public static long maxBodyBytes() {
        return PlayConfig.longOr(CFG_MAX_BODY_BYTES, DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * Record a body an out-of-process transport cut at its own cap: extraction cannot tell
     * a clipped body from a complete one, so unreported it reads as a whole page that says
     * less. Presence, not equality — a sidecar reporting the cut as a byte count rather
     * than {@code true} still counts as truncated.
     */
    public static void noteUpstreamTruncated(String header, String url) {
        if (header == null || "false".equalsIgnoreCase(header)) return;
        EventLogger.info("scrape",
                "%s: the sidecar cut the body at its byte cap".formatted(url),
                "extraction sees a partial page (%s)".formatted(header));
    }

    /**
     * Render a fetched response as LLM-facing text, routed by content type:
     * HTML through the Readability pass to Markdown, already-readable formats
     * unchanged, and everything else through Tika.
     */
    public static String toText(FetchResult fetched) {
        var contentType = fetched.contentType();
        var body = fetched.body();

        if (isHtml(contentType, body)) {
            return extractText(new String(body, charsetFor(contentType)), fetched.finalUrl());
        }

        if (isTextual(contentType) || (contentType.isBlank() && !looksBinary(body))) {
            return truncate(new String(body, charsetFor(contentType)), "content");
        }

        return extractWithTika(body, contentType, fetched.finalUrl());
    }

    /**
     * Absolute http(s) links from an HTML response, in document order, deduplicated.
     *
     * <p>Resolved against the response's <em>final</em> URL rather than the requested
     * one, so relative hrefs on a page reached through a redirect resolve against
     * where the page actually came from.
     *
     * <p>Empty for any non-HTML response — a crawl has nothing to follow out of a PDF.
     */
    public static List<URI> links(FetchResult fetched) {
        if (isMarkdown(fetched.contentType())) {
            return markdownLinks(fetched);
        }
        if (!isHtml(fetched.contentType(), fetched.body())) {
            return List.of();
        }
        var html = new String(fetched.body(), charsetFor(fetched.contentType()));
        var out = new LinkedHashSet<URI>();
        for (var a : Jsoup.parse(html, fetched.finalUrl()).select("a[href]")) {
            var abs = a.attr("abs:href");
            if (abs.isBlank()) {
                continue;
            }
            try {
                var uri = URI.create(abs);
                var scheme = uri.getScheme();
                // mailto:, javascript:, tel: and friends are not crawlable, and
                // SsrfGuard would refuse them one layer down anyway.
                if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                        && uri.getHost() != null) {
                    out.add(uri);
                }
            } catch (IllegalArgumentException _) {
                // Malformed href on someone else's page — skip it, don't fail the crawl.
            }
        }
        return List.copyOf(out);
    }

    /**
     * Extract readable content from HTML and render it as Markdown.
     *
     * <p>A Readability main-content pass runs first; if it finds no substantial
     * article (or throws on malformed input) the original Jsoup boilerplate
     * strip runs as a fallback, so this never returns empty for a page that has
     * body content. The chosen content HTML is converted to Markdown, prefixed
     * with the page title as an H1 when present.
     */
    public static String extractText(String html, String url) {
        String contentHtml = null;
        String title = null;

        try {
            var article = new Readability4J(url, html).parse();
            var articleText = article.getTextContent();
            if (articleText != null && articleText.strip().length() >= MIN_READABILITY_CHARS) {
                contentHtml = article.getContent();
                title = article.getTitle();
            }
        } catch (Exception _) {
            // fall through to the Jsoup boilerplate-strip fallback
        }

        if (contentHtml == null || contentHtml.isBlank()) {
            var doc = Jsoup.parse(html, url);
            doc.select("script, style, noscript, iframe, svg, canvas, nav, footer, " +
                       "header, aside, form, button, input, select, textarea, " +
                       "[role=navigation], [role=banner], [role=complementary], " +
                       "[aria-hidden=true], .hidden, .sr-only, .visually-hidden").remove();
            title = doc.title();
            // jsoup always yields a <body> (creating an empty one if absent), so no null guard is needed.
            contentHtml = doc.body().html();
        }

        var markdown = HTML_TO_MARKDOWN.convert(contentHtml).strip();

        var result = new StringBuilder();
        if (title != null && !title.isBlank()) {
            result.append("# ").append(title.strip()).append("\n\n");
        }
        result.append(markdown);

        if (result.length() > MAX_TEXT_LENGTH) {
            return result.substring(0, MAX_TEXT_LENGTH)
                    + "\n\n[Truncated: extracted text exceeds %d characters]".formatted(MAX_TEXT_LENGTH);
        }
        return result.toString();
    }

    /** Extract text from a non-HTML document (PDF, Office, EPUB, …) with Tika. */
    private static String extractWithTika(byte[] body, String contentType, String url) {
        try {
            var metadata = new Metadata();
            if (!contentType.isBlank()) {
                metadata.set(HttpHeaders.CONTENT_TYPE, contentType);
            }
            // Resource-name hint lets Tika fall back to extension-based detection.
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, url);
            var text = TIKA.parseToString(new ByteArrayInputStream(body), metadata);
            return truncate(text.strip(), "content");
        } catch (Exception e) {
            return "Error: could not extract text from %s: %s".formatted(url, e.getMessage());
        }
    }

    /** True when the response is HTML: an explicit html content type, or — when
     *  the content type is absent — a body whose first non-whitespace char opens
     *  a tag that isn't an XML declaration. */
    private static boolean isHtml(String contentType, byte[] body) {
        if (contentType.toLowerCase().contains("html")) {
            return true;
        }
        if (contentType.isBlank()) {
            var head = new String(body, 0, Math.min(body.length, 256), StandardCharsets.UTF_8).stripLeading();
            return head.startsWith("<") && !head.regionMatches(true, 0, "<?xml", 0, 5);
        }
        return false;
    }

    /**
     * The page's declared translations, keyed by {@code hreflang} (JCLAW-1100).
     *
     * <p>Reads {@code <link rel="alternate" hreflang="..">}, which is the reliable
     * signal. A {@code /de} path prefix is a guess and {@code /design} is not German, so
     * path shape is never used to infer a language here.
     *
     * <p>Empty for markdown and for any page that declares none — most of the web.
     */
    public static Map<String, URI> alternates(FetchResult fetched) {
        if (!isHtml(fetched.contentType(), fetched.body())) {
            return Map.of();
        }
        var html = new String(fetched.body(), charsetFor(fetched.contentType()));
        var out = new LinkedHashMap<String, URI>();
        for (var link : Jsoup.parse(html, fetched.finalUrl())
                .select("link[rel~=(?i)alternate][hreflang]")) {
            var lang = link.attr("hreflang").strip().toLowerCase(Locale.ROOT);
            var abs = link.attr("abs:href");
            if (lang.isEmpty() || abs.isBlank()) continue;
            try {
                var uri = URI.create(abs);
                if (uri.getHost() != null) out.putIfAbsent(lang, uri);
            } catch (RuntimeException _) {
                // a malformed alternate must not lose the rest
            }
        }
        // Insertion order is preserved deliberately: document order is the only tie-break
        // a caller has when no variant matches the preferred language, and Map.copyOf
        // returns an UNORDERED map, which made that fallback pick a different variant
        // depending on hash iteration — green alone, red in a full suite.
        return Collections.unmodifiableMap(out);
    }

    /** True for a response the origin has labeled as markdown. Deliberately reads the
     *  RESPONSE type: content negotiation is advisory and plenty of servers return HTML
     *  whatever was asked for, so the request header decides nothing on its own. */
    public static boolean isMarkdown(String contentType) {
        var ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return ct.contains("markdown");
    }

    /**
     * Crawlable links from a markdown body (JCLAW-1101).
     *
     * <p>Without this, asking for markdown would silently disable link harvesting and a
     * crawl would return only its seed. Markdown pages carry far fewer links than their
     * HTML twins — the same docs page offers 3 against 118 — because the navigation
     * chrome is gone; what remains is the links the prose actually makes.
     */
    private static List<URI> markdownLinks(FetchResult fetched) {
        var text = new String(fetched.body(), charsetFor(fetched.contentType()));
        var base = URI.create(fetched.finalUrl());
        var out = new LinkedHashSet<URI>();
        var document = MARKDOWN_PARSER.parse(text);
        collectMarkdownLinks(document, base, out);
        return List.copyOf(out);
    }

    private static void collectMarkdownLinks(Node node,
                                             URI base, LinkedHashSet<URI> out) {
        for (var child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Link link) {
                try {
                    var resolved = base.resolve(link.getUrl().toString());
                    var scheme = resolved.getScheme();
                    if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                            && resolved.getHost() != null) {
                        out.add(resolved);
                    }
                } catch (RuntimeException _) {
                    // one unparseable link must not lose the rest of the page
                }
            }
            collectMarkdownLinks(child, base, out);
        }
    }

    /** True for content types that are already human-readable and must pass
     *  through untouched (JSON, XML, CSV, plain text, source). */
    private static boolean isTextual(String contentType) {
        if (contentType.isBlank()) {
            return false;
        }
        var ct = contentType.toLowerCase();
        return ct.startsWith("text/")
                || ct.contains("json")
                || ct.contains("xml")
                || ct.contains("csv")
                || ct.contains("javascript")
                || ct.contains("yaml")
                // isMarkdown matches on the word alone, so application/markdown routed to
                // the markdown link parser but fell through to Tika here — one response
                // with two different opinions about what it is.
                || ct.contains("markdown");
    }

    /** Heuristic used only when the content type is absent: magic numbers for
     *  common binary documents, or a NUL byte early in the stream. */
    private static boolean looksBinary(byte[] body) {
        if (body.length == 0) {
            return false;
        }
        if (startsWith(body, "%PDF")) {                                   // PDF
            return true;
        }
        if (body.length >= 4 && body[0] == 'P' && body[1] == 'K'
                && body[2] == 3 && body[3] == 4) {                        // ZIP (docx/xlsx/pptx/odf)
            return true;
        }
        if (body.length >= 2 && (body[0] & 0xFF) == 0xD0 && (body[1] & 0xFF) == 0xCF) {
            return true;                                                  // OLE2 (legacy .doc/.xls/.ppt)
        }
        int n = Math.min(body.length, 512);
        for (int i = 0; i < n; i++) {
            if (body[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] body, String ascii) {
        if (body.length < ascii.length()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if (body[i] != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Parse the charset from a Content-Type header, defaulting to UTF-8 — the
     *  same rule OkHttp's {@code ResponseBody.string()} applied before the
     *  switch to raw bytes. */
    public static Charset charsetFor(String contentType) {
        if (contentType.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        var mediaType = MediaType.parse(contentType);
        return mediaType != null ? mediaType.charset(StandardCharsets.UTF_8) : StandardCharsets.UTF_8;
    }

    private static String truncate(String text, String label) {
        if (text.length() > MAX_TEXT_LENGTH) {
            return text.substring(0, MAX_TEXT_LENGTH)
                    + "\n\n[Truncated: %s exceeds %d characters]".formatted(label, MAX_TEXT_LENGTH);
        }
        return text;
    }
}
