package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import models.Agent;
import net.dankito.readability4j.Readability4J;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.tika.Tika;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.jsoup.Jsoup;
import services.AgentService;
import utils.SsrfGuard;

import javax.net.ssl.SSLException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Fetch the content of a URL. Supports two modes:
 * <ul>
 *   <li>"text" (default): Extract readable content and return it as Markdown.
 *       HTML is run through a Readability main-content pass (falling back to a
 *       Jsoup boilerplate strip) and converted to Markdown; PDF / Office / other
 *       non-HTML documents are extracted to text with Apache Tika; JSON, XML and
 *       plain text pass through unchanged. Best for reading/summarizing.</li>
 *   <li>"html": Return raw HTML. Best for saving the actual page to a file.</li>
 * </ul>
 *
 * <p>Because this tool consumes URLs emitted by the LLM, every request goes
 * through {@link SsrfGuard}: the scheme is pinned to http/https and the DNS
 * resolver rejects loopback, link-local (cloud metadata), RFC-1918, and
 * multicast ranges before any socket is opened. Redirects are followed
 * manually so each hop can be re-validated — the built-in OkHttp redirect
 * path is disabled.
 */
public class WebFetchTool implements ToolRegistry.Tool {

    private static final int MAX_TEXT_LENGTH = 50_000;
    private static final int MAX_HTML_LENGTH = 100_000;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_REDIRECTS = 5;

    /** Below this many extracted characters the Readability pass is treated as a
     *  miss and the Jsoup boilerplate-strip fallback runs instead — small pages
     *  and non-article fragments aren't article-shaped enough to score well. */
    private static final int MIN_READABILITY_CHARS = 200;

    private static final FlexmarkHtmlConverter HTML_TO_MARKDOWN =
            FlexmarkHtmlConverter.builder(new MutableDataSet()
                    // Suppress the {#id} inline-attribute annotations flexmark emits
                    // for element ids. Parsoid-rendered HTML (e.g. Wikipedia) tags
                    // nearly every node with an id, which is pure noise in LLM-facing
                    // Markdown and carries no semantic value.
                    .set(FlexmarkHtmlConverter.OUTPUT_ATTRIBUTES_ID, false))
                    .build();

    /** Shared Tika facade for non-HTML document extraction. {@code maxStringLength}
     *  is set once here (never mutated per-call) so {@code parseToString} stays
     *  thread-safe under the parallel tool dispatch. */
    private static final Tika TIKA = new Tika();
    static {
        TIKA.setMaxStringLength(MAX_TEXT_LENGTH + 10_000);
    }

    /**
     * Package-private and non-final so {@code WebFetchToolTest} can substitute
     * a loopback-friendly client (SsrfGuard's {@code SAFE_DNS} blocks 127.0.0.1
     * where MockWebServer binds). Production code must not mutate this — the
     * guarded client is the only supported runtime path.
     */
    static OkHttpClient CLIENT = SsrfGuard.buildGuardedClient(
            CONNECT_TIMEOUT_SECONDS, TIMEOUT_SECONDS);

    /** Raw fetch result: undecoded body bytes plus the response Content-Type and
     *  the final (post-redirect) URL. Bytes — not a decoded String — so binary
     *  documents (PDF, Office) reach Tika intact. */
    private record FetchResult(byte[] body, String contentType, String finalUrl) {}

    @Override
    public String name() { return "web_fetch"; }

    @Override
    public String category() { return "Web"; }

    @Override
    public String icon() { return "globe"; }

    @Override
    public String shortDescription() {
        return "Fetch and extract readable text or raw HTML from any URL.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction("fetch (text)", "Retrieve a URL and extract clean, readable content as Markdown"),
                new ToolAction("fetch (html)", "Retrieve a URL and return the raw HTML source")
        );
    }

    @Override
    public String description() {
        return """
                Fetch the content of a URL. \
                Use mode "text" (default) to extract readable content as Markdown — best for reading, summarizing, saving content, or answering questions about a page. Handles HTML articles, PDFs and Office documents. \
                Use mode "html" ONLY when the user explicitly asks for the raw HTML source code of a page.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        "url", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING, SchemaKeys.DESCRIPTION, "The URL to fetch"),
                        "mode", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of("text", "html"),
                                SchemaKeys.DESCRIPTION, "Extraction mode: 'text' extracts readable content as Markdown (default), 'html' returns raw HTML")
                ),
                SchemaKeys.REQUIRED, List.of("url")
        );
    }

    /** Stateless HTTP GET — holds no handles between calls, writes nothing
     *  to disk. Safe to call many URLs in parallel. */
    @Override public boolean parallelSafe() { return true; }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var url = args.get("url").getAsString();
        var mode = args.has("mode") ? args.get("mode").getAsString() : "text";

        try {
            var fetched = fetchUrl(url);
            return processResponse(fetched, mode, url, agent);
        } catch (SecurityException e) {
            // SsrfGuard rejected a scheme or host — surface plainly so the LLM
            // understands why and doesn't keep retrying the same URL.
            return "Error: URL rejected by SSRF guard: %s".formatted(e.getMessage());
        } catch (UnknownHostException e) {
            return "Error: URL rejected: %s".formatted(e.getMessage());
        } catch (SocketTimeoutException _) {
            return "Error: Request timed out after %d seconds fetching %s".formatted(TIMEOUT_SECONDS, url);
        } catch (SSLException e) {
            return "Error: SSL/TLS certificate verification failed for %s: %s. The site may have an expired, self-signed, or invalid certificate."
                    .formatted(url, e.getMessage());
        } catch (Exception e) {
            if (e.getCause() instanceof SSLException sslEx) {
                return "Error: SSL/TLS certificate verification failed for %s: %s. The site may have an expired, self-signed, or invalid certificate."
                        .formatted(url, sslEx.getMessage());
            }
            return "Error fetching URL: %s".formatted(e.getMessage());
        }
    }

    /**
     * Fetch a URL through the {@link SsrfGuard}ed client. Redirects are
     * followed manually, up to {@link #MAX_REDIRECTS}, so each hop is
     * re-validated through {@link SsrfGuard#assertSafeScheme(URI)} and
     * re-resolved through the guarded DNS.
     *
     * <p>Only the final (non-redirect) response body is read, and it is read as
     * raw bytes — never a decoded String — so binary documents survive intact
     * for Tika. The SSRF scheme/redirect logic is unchanged.
     */
    private FetchResult fetchUrl(String url) throws IOException {
        var current = URI.create(url);
        SsrfGuard.assertSafeScheme(current);

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            var request = new Request.Builder()
                    .url(current.toString())
                    .header("User-Agent", "Mozilla/5.0 (compatible; JClaw/1.0)")
                    .get()
                    .build();

            try (var response = CLIENT.newCall(request).execute()) {
                int code = response.code();

                // Follow 3xx manually so every hop re-enters SsrfGuard.
                if (code >= 300 && code < 400) {
                    var location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException(
                                "HTTP %d with no Location header for %s".formatted(code, current));
                    }
                    current = current.resolve(location);
                    SsrfGuard.assertSafeScheme(current);
                    continue;
                }

                if (code >= 400) {
                    throw new IOException("HTTP %d fetching %s".formatted(code, current));
                }

                var bytes = response.body().bytes();
                var contentType = response.header("Content-Type", "");
                return new FetchResult(bytes, contentType, current.toString());
            }
        }
        throw new IOException("Too many redirects (>%d) fetching %s"
                .formatted(MAX_REDIRECTS, url));
    }

    private String processResponse(FetchResult fetched, String mode, String url, Agent agent) {
        var contentType = fetched.contentType();
        var body = fetched.body();

        if ("html".equals(mode)) {
            // Raw HTML mode — for large pages, auto-save to workspace to avoid
            // flooding the LLM context with hundreds of KB of HTML.
            var html = new String(body, charsetFor(contentType));
            if (html.length() > MAX_TEXT_LENGTH && agent != null) {
                var filename = URI.create(url).getHost().replaceAll("[^a-zA-Z0-9.-]", "_") + ".html";
                AgentService.writeWorkspaceFile(agent.name, filename, html);
                return "HTML saved to workspace as '%s' (%d characters from %s)"
                        .formatted(filename, html.length(), url);
            }
            if (html.length() > MAX_HTML_LENGTH) {
                return html.substring(0, MAX_HTML_LENGTH)
                        + "\n\n[Truncated: HTML exceeds %d characters]".formatted(MAX_HTML_LENGTH);
            }
            return html;
        }

        // Text mode — route by content type.
        // 1. HTML → Readability main-content pass → Markdown.
        if (isHtml(contentType, body)) {
            return extractText(new String(body, charsetFor(contentType)), fetched.finalUrl());
        }

        // 2. Textual (JSON / XML / CSV / plain text) → pass through unchanged.
        if (isTextual(contentType) || (contentType.isBlank() && !looksBinary(body))) {
            return truncate(new String(body, charsetFor(contentType)), "content");
        }

        // 3. Binary document (PDF / Office / …) → Tika text extraction.
        return extractWithTika(body, contentType, url);
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
    private String extractText(String html, String url) {
        String contentHtml = null;
        String title = null;

        // 1. Readability main-content pass.
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

        // 2. Fallback: strip non-content elements and keep the body HTML.
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

        // 3. HTML → Markdown.
        var markdown = HTML_TO_MARKDOWN.convert(contentHtml).strip();

        // 4. Assemble with an optional title heading.
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
    private String extractWithTika(byte[] body, String contentType, String url) {
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
                || ct.contains("yaml");
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
    private static Charset charsetFor(String contentType) {
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
