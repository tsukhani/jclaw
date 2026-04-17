package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import org.jsoup.Jsoup;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Fetch the content of a URL. Supports two modes:
 * - "text" (default): Extract readable text from HTML using Jsoup. Best for reading/summarizing.
 * - "html": Return raw HTML. Best for saving the actual page to a file.
 */
public class WebFetchTool implements ToolRegistry.Tool {

    private static final int MAX_TEXT_LENGTH = 50_000;
    private static final int MAX_HTML_LENGTH = 100_000;
    private static final int TIMEOUT_SECONDS = 30;

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

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var url = args.get("url").getAsString();
        var mode = args.has("mode") ? args.get("mode").getAsString() : "text";

        try {
            var body = fetchUrl(url);
            return processResponse(body, mode, url, agent);
        } catch (java.net.http.HttpTimeoutException _) {
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

    private String fetchUrl(String url) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (compatible; JClaw/1.0)")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build();

        var response = utils.HttpClients.GENERAL.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP %d fetching %s".formatted(response.statusCode(), url));
        }

        return response.body();
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
