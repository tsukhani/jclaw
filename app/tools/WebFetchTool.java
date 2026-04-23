package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jsoup.Jsoup;
import utils.SsrfGuard;

import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

/**
 * Fetch the content of a URL. Supports two modes:
 * - "text" (default): Extract readable text from HTML using Jsoup. Best for reading/summarizing.
 * - "html": Return raw HTML. Best for saving the actual page to a file.
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

    /**
     * Package-private and non-final so {@code WebFetchToolTest} can substitute
     * a loopback-friendly client (SsrfGuard's {@code SAFE_DNS} blocks 127.0.0.1
     * where MockWebServer binds). Production code must not mutate this — the
     * guarded client is the only supported runtime path.
     */
    static OkHttpClient CLIENT = SsrfGuard.buildGuardedClient(
            CONNECT_TIMEOUT_SECONDS, TIMEOUT_SECONDS);

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
    public java.util.List<agents.ToolAction> actions() {
        return java.util.List.of(
                new agents.ToolAction("fetch (text)", "Retrieve a URL and extract clean, readable text content"),
                new agents.ToolAction("fetch (html)", "Retrieve a URL and return the raw HTML source")
        );
    }

    @Override
    public String description() {
        return """
                Fetch the content of a URL. \
                Use mode "text" (default) to extract readable text — best for reading, summarizing, saving content, or answering questions about a page. \
                Use mode "html" ONLY when the user explicitly asks for the raw HTML source code of a page.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "description", "The URL to fetch"),
                        "mode", Map.of("type", "string",
                                "enum", List.of("text", "html"),
                                "description", "Extraction mode: 'text' extracts readable content (default), 'html' returns raw HTML")
                ),
                "required", List.of("url")
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
            var body = fetchUrl(url);
            return processResponse(body, mode, url, agent);
        } catch (SecurityException e) {
            // SsrfGuard rejected a scheme or host — surface plainly so the LLM
            // understands why and doesn't keep retrying the same URL.
            return "Error: URL rejected by SSRF guard: %s".formatted(e.getMessage());
        } catch (UnknownHostException e) {
            return "Error: URL rejected: %s".formatted(e.getMessage());
        } catch (java.net.SocketTimeoutException _) {
            return "Error: Request timed out after %d seconds fetching %s".formatted(TIMEOUT_SECONDS, url);
        } catch (javax.net.ssl.SSLException e) {
            return "Error: SSL/TLS certificate verification failed for %s: %s. The site may have an expired, self-signed, or invalid certificate."
                    .formatted(url, e.getMessage());
        } catch (Exception e) {
            if (e.getCause() instanceof javax.net.ssl.SSLException sslEx) {
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
     */
    private String fetchUrl(String url) throws Exception {
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
                        throw new RuntimeException(
                                "HTTP %d with no Location header for %s".formatted(code, current));
                    }
                    current = current.resolve(location);
                    SsrfGuard.assertSafeScheme(current);
                    continue;
                }

                if (code >= 400) {
                    throw new RuntimeException("HTTP %d fetching %s".formatted(code, current));
                }

                var body = response.body();
                return body != null ? body.string() : "";
            }
        }
        throw new RuntimeException("Too many redirects (>%d) fetching %s"
                .formatted(MAX_REDIRECTS, url));
    }

    private String processResponse(String body, String mode, String url, Agent agent) {
        if ("html".equals(mode)) {
            // Raw HTML mode — for large pages, auto-save to workspace to avoid
            // flooding the LLM context with hundreds of KB of HTML
            if (body.length() > MAX_TEXT_LENGTH && agent != null) {
                var filename = URI.create(url).getHost().replaceAll("[^a-zA-Z0-9.-]", "_") + ".html";
                services.AgentService.writeWorkspaceFile(agent.name, filename, body);
                return "HTML saved to workspace as '%s' (%d characters from %s)"
                        .formatted(filename, body.length(), url);
            }
            if (body.length() > MAX_HTML_LENGTH) {
                return body.substring(0, MAX_HTML_LENGTH)
                        + "\n\n[Truncated: HTML exceeds %d characters]".formatted(MAX_HTML_LENGTH);
            }
            return body;
        }

        // Text mode — extract readable content
        var contentType = body.strip().startsWith("<") ? "html" : "text";
        if ("html".equals(contentType)) {
            return extractText(body, url);
        }

        // Already plain text (JSON, XML, etc.)
        if (body.length() > MAX_TEXT_LENGTH) {
            return body.substring(0, MAX_TEXT_LENGTH)
                    + "\n\n[Truncated: content exceeds %d characters]".formatted(MAX_TEXT_LENGTH);
        }
        return body;
    }

    /**
     * Extract readable text from HTML using Jsoup.
     * Strips scripts, styles, nav, and other non-content elements.
     * Preserves document structure with headers, paragraphs, and lists.
     */
    private String extractText(String html, String url) {
        var doc = Jsoup.parse(html, url);

        // Remove non-content elements
        doc.select("script, style, noscript, iframe, svg, canvas, nav, footer, " +
                   "header, aside, form, button, input, select, textarea, " +
                   "[role=navigation], [role=banner], [role=complementary], " +
                   "[aria-hidden=true], .hidden, .sr-only, .visually-hidden").remove();

        // Extract title
        var title = doc.title();

        // Get text with whitespace structure preserved
        doc.outputSettings().prettyPrint(false);
        var text = doc.body() != null ? doc.body().wholeText() : doc.text();

        // Clean up excessive whitespace while preserving paragraph breaks
        text = text.replaceAll("[ \\t]+", " ")           // Collapse horizontal whitespace
                   .replaceAll("\\n[ \\t]+", "\n")        // Trim leading whitespace on lines
                   .replaceAll("[ \\t]+\\n", "\n")        // Trim trailing whitespace on lines
                   .replaceAll("\\n{3,}", "\n\n")         // Max two consecutive newlines
                   .strip();

        var result = new StringBuilder();
        if (!title.isBlank()) {
            result.append("# ").append(title).append("\n\n");
        }
        result.append(text);

        if (result.length() > MAX_TEXT_LENGTH) {
            return result.substring(0, MAX_TEXT_LENGTH)
                    + "\n\n[Truncated: extracted text exceeds %d characters]".formatted(MAX_TEXT_LENGTH);
        }
        return result.toString();
    }
}
