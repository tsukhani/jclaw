package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import okhttp3.OkHttpClient;
import services.AgentService;
import services.ConfigService;
import services.EventLogger;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeObservation;
import services.scrape.ScrapeRung;
import tools.scrape.ScrapeLadder;
import utils.SsrfGuard;
import utils.WebExtraction;

import javax.net.ssl.SSLException;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>The fetch and extraction chain lives in {@link WebExtraction}, shared with
 * {@code web_scrape} (JCLAW-1082). What stays here is what is specific to this
 * tool: the schema, the html-mode workspace save, and the mapping of failures
 * onto LLM-facing error strings.
 *
 * <p>Because this tool consumes URLs emitted by the LLM, every request goes
 * through {@link SsrfGuard}: the scheme is pinned to http/https and the DNS
 * resolver rejects loopback, link-local (cloud metadata), RFC-1918, and
 * multicast ranges before any socket is opened. Redirects are followed
 * manually so each hop can be re-validated — the built-in OkHttp redirect
 * path is disabled.
 *
 * <p>{@link SsrfGuard} only constrains which hosts are reachable, not what
 * leaves: a prompt-injected agent can still encode conversation content into a
 * URL on a host it is allowed to reach. An operator who wants that contained
 * sets {@code web_fetch.allowlist}.
 */
public class WebFetchTool implements ToolRegistry.Tool {

    private static final int MAX_HTML_LENGTH = 100_000;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int TIMEOUT_SECONDS = 30;

    /** Identifies this client honestly. A per-request value in
     *  {@link WebExtraction#fetch} so a caller can present differently without
     *  forking the redirect loop. */
    private static final Map<String, String> HEADERS =
            Map.of("User-Agent", "Mozilla/5.0 (compatible; JClaw/1.0)");

    /** Mode {@code html} promises the page's own source, so it asks for it: the shared
     *  default prefers markdown, and a site that honors that had its markdown written
     *  to the workspace as {@code <host>.html}. */
    private static Map<String, String> headersFor(String mode) {
        if (!"html".equals(mode)) return HEADERS;
        var withAccept = new HashMap<>(HEADERS);
        withAccept.put("Accept", "text/html,application/xhtml+xml");
        return Map.copyOf(withAccept);
    }

    /**
     * Package-private and non-final so {@code WebFetchToolTest} can substitute
     * a loopback-friendly client (SsrfGuard's {@code SAFE_DNS} blocks 127.0.0.1
     * where MockWebServer binds). Production code must not mutate this — the
     * guarded client is the only supported runtime path.
     */
    static OkHttpClient CLIENT = SsrfGuard.buildGuardedClient(
            CONNECT_TIMEOUT_SECONDS, TIMEOUT_SECONDS);

    private static final String EVENT_CATEGORY = "scrape";
    private static final String CFG_MAX_ESCALATIONS = "web_fetch.max-escalations-per-minute";

    /** A single-URL tool has no crawl to budget within, so the bound spans calls: a page
     *  extracting under 200 characters classifies THIN_CONTENT, so an agent walking a list
     *  of short pages paid for a browser render on every one. Five a minute leaves any
     *  hand-driven fetch escalating and caps the loop. */
    private static final int DEFAULT_MAX_ESCALATIONS_PER_MINUTE = 5;
    private static final long ESCALATION_WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();

    /** Agent name to the window it is currently spending, keyed per agent rather than per
     *  JVM: the loop this bounds runs inside one agent's turn, and a shared counter would
     *  let it refuse escalation to every other agent for the rest of the minute. */
    private static final ConcurrentHashMap<String, Window> ESCALATION_WINDOWS =
            new ConcurrentHashMap<>();

    /** Which window an agent is in, and how many escalations it has admitted there. */
    private static final class Window {
        long id;
        int count;
    }

    /**
     * Record an escalation for {@code agentKey} and report whether it fits inside
     * {@code limit} for the fixed window {@code nowMillis} falls in; a {@code limit} of
     * zero or less admits nothing. Counts only what it admits, so a refusal does not
     * deepen the shortfall. Pure in {@code nowMillis}, and the per-key {@code compute} is
     * atomic, so two parallel fetches by one agent cannot overdraw. Mirrors
     * {@link services.AppInvokeLimits#tryAcquire}.
     */
    public static boolean claimEscalation(String agentKey, int limit, long nowMillis) {
        long windowId = nowMillis / ESCALATION_WINDOW_MILLIS;
        boolean[] admitted = {false};
        ESCALATION_WINDOWS.compute(agentKey, (_, existing) -> {
            var window = existing != null && existing.id == windowId ? existing : new Window();
            window.id = windowId;
            if (window.count < limit) {
                window.count++;
                admitted[0] = true;
            }
            return window;
        });
        return admitted[0];
    }

    /** {@link #claimEscalation(String, int, long)} against the wall clock + live limit config. */
    private static boolean claimEscalation(Agent agent) {
        return claimEscalation(agent == null ? "" : agent.name,
                ConfigService.getInt(CFG_MAX_ESCALATIONS, DEFAULT_MAX_ESCALATIONS_PER_MINUTE),
                System.currentTimeMillis());
    }

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
            var fetched = WebExtraction.fetch(url, CLIENT, headersFor(mode));
            var text = WebExtraction.toText(fetched);
            var best = climb(url, fetched, text, null, agent);
            var body = best.fetched() == null ? fetched : best.fetched();
            var extracted = best.text() == null ? text : best.text();
            return "html".equals(mode) ? rawHtml(body, url, agent) : extracted;
        } catch (WebExtraction.HostNotAllowedException e) {
            return e.getMessage();
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
            // A refusal is the case escalation exists for — an HTTP 403 arrives here as
            // an IOException, and giving up on it is exactly what left the higher rungs
            // unreachable (JCLAW-1099). The SSRF, host-allowlist and TLS branches above
            // deliberately do NOT escalate: those are our own refusals, and retrying
            // them through a different transport would be a way around the guard.
            var escalated = climb(url, null, null, e.getMessage(), agent);
            if (escalated.usable()) {
                return "html".equals(mode) && escalated.fetched() != null
                        ? rawHtml(escalated.fetched(), url, agent) : escalated.text();
            }
            return "Error fetching URL: %s".formatted(e.getMessage());
        }
    }

    /** Hand one URL to the ladder, classifying the plain attempt the way the crawler and
     *  the harness both do so all three agree on what counts as a failure. */
    private static ScrapeLadder.Attempt climb(String url, WebExtraction.FetchResult fetched,
                                              String text, String error, Agent agent) {
        var detail = error == null ? "fetch failed" : error;
        var obs = fetched == null
                ? ScrapeObservation.failed(url, detail)
                : ScrapeObservation.of(fetched, text);
        var plain = new ScrapeLadder.Attempt(
                ScrapeRung.PLAIN, fetched, text, BlockClassifier.classify(obs), error);
        // Ask before claiming, as the crawler does: a reason no installed rung addresses
        // would spend the budget without a request ever being issued.
        if (plain.usable() || !ScrapeLadder.wouldAttempt(plain.reason())) return plain;
        if (!claimEscalation(agent)) {
            EventLogger.info(EVENT_CATEGORY,
                    "%s: not escalated, this agent's budget for the minute is spent".formatted(url),
                    "%s; raise %s to escalate more often".formatted(plain.reason(), CFG_MAX_ESCALATIONS));
            return plain;
        }
        return ScrapeLadder.climb(url, plain);
    }

    /**
     * Raw HTML mode. Large pages are written to the agent workspace instead of
     * returned, so hundreds of KB of markup can't flood the LLM context.
     */
    private String rawHtml(WebExtraction.FetchResult fetched, String url, Agent agent) {
        var html = new String(fetched.body(), WebExtraction.charsetFor(fetched.contentType()));
        if (html.length() > WebExtraction.MAX_TEXT_LENGTH && agent != null) {
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
}
