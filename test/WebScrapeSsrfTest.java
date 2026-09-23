import models.Agent;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import tools.WebScrapeTool;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF containment across a multi-URL crawl (JCLAW-1085).
 *
 * <p>{@code WebFetchToolTest} covers the single-URL case. What a crawl adds is that
 * the next targets come from someone else's markup: the seed being safe says nothing
 * about the links on the page it returned. These cases drive links that a
 * prompt-injected or merely hostile page could plausibly contain.
 *
 * <p>The substituted client has no {@code SAFE_DNS}, which is deliberate — it means
 * a request reaching the interceptor proves the URL got past every check the tool
 * makes on its own, rather than being stopped by the resolver that production also
 * has. {@code routes.hits} is therefore the assertion that matters.
 */
class WebScrapeSsrfTest extends UnitTest {

    private static final Field CLIENT_FIELD;
    static {
        try {
            CLIENT_FIELD = WebScrapeTool.class.getDeclaredField("CLIENT");
            CLIENT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static final String CFG_ALLOWLIST = "web_fetch.allowlist";

    private RouteInterceptor routes;
    private OkHttpClient original;
    private String originalAllowlist;

    @BeforeEach
    void setup() throws Exception {
        routes = new RouteInterceptor();
        original = (OkHttpClient) CLIENT_FIELD.get(null);
        originalAllowlist = ConfigService.get(CFG_ALLOWLIST, "");
        CLIENT_FIELD.set(null, new OkHttpClient.Builder()
                .addInterceptor(routes)
                .callTimeout(5, TimeUnit.SECONDS)
                .build());
    }

    @AfterEach
    void teardown() throws Exception {
        CLIENT_FIELD.set(null, original);
        ConfigService.set(CFG_ALLOWLIST, originalAllowlist);
    }

    private String scrape(String json) {
        return new WebScrapeTool().execute(json, (Agent) null);
    }

    private static String pageLinking(String title, String... hrefs) {
        var links = new StringBuilder();
        for (var h : hrefs) {
            links.append("<a href=\"").append(h).append("\">x</a> ");
        }
        return "<html><head><title>" + title + "</title></head><body><article><p>"
                + "Ordinary body text that clears the readability floor. ".repeat(12)
                + "</p>" + links + "</article></body></html>";
    }

    @Test
    void linksToCloudMetadataAndPrivateRangesAreNeverFetched() {
        routes.put("https://site.test/", pageLinking("Home",
                "http://169.254.169.254/latest/meta-data/",   // AWS/GCP metadata
                "http://10.0.0.5/admin",                      // RFC-1918
                "http://192.168.1.1/",                        // RFC-1918
                "http://127.0.0.1:9000/api/status",           // loopback
                "/safe"));
        routes.put("https://site.test/safe", pageLinking("Safe"));

        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1,\"sameHostOnly\":false}");

        assertEquals(List.of("https://site.test/", "https://site.test/safe"), routes.hits,
                "only the seed and the safe link may reach the network: " + routes.pageHits());
        assertTrue(out.contains("# Safe"), "the crawl completes past the refusals");
        assertTrue(out.contains("Refused 4 links"), out.substring(0, 400));
        assertTrue(out.contains("169.254.169.254"), "the refusal names the host");
    }

    @Test
    void aRefusalDoesNotSpendAPageFromTheBudget() {
        routes.put("https://site.test/", pageLinking("Home",
                "http://10.0.0.5/a", "http://10.0.0.6/b", "/one", "/two"));
        routes.put("https://site.test/one", pageLinking("One"));
        routes.put("https://site.test/two", pageLinking("Two"));

        var out = scrape(
                "{\"url\":\"https://site.test/\",\"maxDepth\":1,\"maxPages\":3,\"sameHostOnly\":false}");

        // Budget 3 = seed + both safe pages. If refusals consumed slots, /two would be lost.
        assertTrue(out.contains("# One") && out.contains("# Two"),
                "refused links must not crowd out real pages");
    }

    @Test
    void nonHttpSchemesInMarkupAreNeverQueued() {
        routes.put("https://site.test/", pageLinking("Home",
                "file:///etc/passwd", "ftp://internal.test/x",
                "javascript:fetch('/admin')", "mailto:a@b.test", "/ok"));
        routes.put("https://site.test/ok", pageLinking("Ok"));

        scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1,\"sameHostOnly\":false}");
        assertEquals(List.of("https://site.test/", "https://site.test/ok"), routes.pageHits());
    }

    @Test
    void aRedirectOntoALoopbackAddressIsRefusedAtThatHop() {
        routes.put("https://site.test/", pageLinking("Home", "/bounce"));
        routes.redirect("https://site.test/bounce", "http://127.0.0.1:9000/api/status");

        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1}");

        assertFalse(routes.pageHits().contains("http://127.0.0.1:9000/api/status"),
                "the redirect target must never be requested: " + routes.pageHits());
        assertTrue(out.contains("Not retrieved") || out.contains("Refused"),
                "the refusal is surfaced, not swallowed");
    }

    @Test
    void aRedirectOntoAUrlBrowsersSendRawIsFollowedRatherThanFailing() {
        // A Location header is the commonest way a page-supplied URL reaches the fetch loop, and
        // it was resolved strictly — so an origin redirecting to its own font or tracking URL
        // failed the hop rather than following it (JCLAW-1287).
        routes.put("https://site.test/", pageLinking("Home", "/bounce"));
        routes.redirect("https://site.test/bounce", "https://site.test/x?family=Roboto|Open+Sans");
        routes.put("https://site.test/x?family=Roboto%7COpen+Sans", pageLinking("Landed"));

        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1}");

        assertTrue(routes.pageHits().contains("https://site.test/x?family=Roboto%7COpen+Sans"),
                "the redirect target must be requested, percent-encoded: " + routes.pageHits());
        assertTrue(out.contains("# Landed"), "and the page it landed on is what the crawl returns");
    }

    @Test
    void theAllowlistContainsEgressAcrossTheWholeCrawlNotJustTheSeed() {
        ConfigService.set(CFG_ALLOWLIST, "site.test");
        routes.put("https://site.test/", pageLinking("Home", "https://elsewhere.test/x", "/inside"));
        routes.put("https://site.test/inside", pageLinking("Inside"));
        routes.put("https://elsewhere.test/x", pageLinking("Outside"));

        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1,\"sameHostOnly\":false}");

        assertFalse(routes.pageHits().contains("https://elsewhere.test/x"),
                "an off-allowlist host must not be fetched even when linked: " + routes.pageHits());
        assertTrue(out.contains("# Inside"));
        assertFalse(out.contains("# Outside"));
    }

    @Test
    void anUnsafeSeedIsRejectedOutrightRatherThanCrawledEmpty() {
        var out = scrape("{\"url\":\"http://169.254.169.254/latest/meta-data/\"}");
        assertTrue(out.startsWith("Error: URL rejected by SSRF guard"), out);
        assertTrue(routes.pageHits().isEmpty(), "nothing may be requested: " + routes.pageHits());
    }

    static final class RouteInterceptor implements Interceptor {
        private final Map<String, String> bodies = new HashMap<>();
        private final Map<String, String> redirects = new HashMap<>();
        final List<String> hits = new ArrayList<>();

        /** Hits with robots.txt filtered out. RobotsCache is a process-global cache, so
         *  whether a given test observes that fetch depends on which test ran first —
         *  asserting on raw hits would make frontier tests order-dependent. */
        List<String> pageHits() {
            return hits.stream().filter(u -> !u.endsWith("/robots.txt")).toList();
        }

        void put(String url, String body) { bodies.put(url, body); }

        void redirect(String from, String to) { redirects.put(from, to); }

        @Override
        public Response intercept(Chain chain) throws IOException {
            var url = chain.request().url().toString();
            hits.add(url);
            var to = redirects.get(url);
            if (to != null) {
                return new Response.Builder()
                        .request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(302).message("Found").addHeader("Location", to)
                        .body(ResponseBody.create("", MediaType.parse("text/html")))
                        .build();
            }
            var body = bodies.get(url);
            if (body == null) {
                throw new IOException("no route for " + url);
            }
            return new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .body(ResponseBody.create(body, MediaType.parse("text/html")))
                    .build();
        }
    }
}
