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
import tools.scrape.WebScrapeSettings;

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
 * Verifies {@link WebScrapeTool}'s frontier: depth and page budgets, host scoping,
 * dedup, and that one unreachable page does not end the crawl (JCLAW-1083).
 *
 * <p>Swaps {@code WebScrapeTool.CLIENT} for a client whose {@link Interceptor} serves
 * canned pages keyed by URL without opening a socket — the same approach
 * {@code WebFetchToolTest} uses, and for the same reason: SsrfGuard's DNS blocks the
 * loopback a local server would bind, and mockwebserver 4.x is off this classpath.
 *
 * <p>URLs use a routable-looking host so {@code assertSafeScheme} and the literal-IP
 * checks still run for real; only the socket is stubbed.
 */
class WebScrapeToolTest extends UnitTest {

    private static final Field CLIENT_FIELD;
    static {
        try {
            CLIENT_FIELD = WebScrapeTool.class.getDeclaredField("CLIENT");
            CLIENT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private RouteInterceptor routes;
    private OkHttpClient original;

    @BeforeEach
    void setup() throws Exception {
        routes = new RouteInterceptor();
        original = (OkHttpClient) CLIENT_FIELD.get(null);
        CLIENT_FIELD.set(null, new OkHttpClient.Builder()
                .addInterceptor(routes)
                .callTimeout(5, TimeUnit.SECONDS)
                .build());
    }

    @AfterEach
    void teardown() throws Exception {
        CLIENT_FIELD.set(null, original);
    }

    private String scrape(String json) {
        return new WebScrapeTool().execute(json, (Agent) null);
    }

    /** Enough body text that the Readability pass clears MIN_READABILITY_CHARS and the
     *  page is not mistaken for an empty one. */
    private static String page(String title, String... hrefs) {
        var links = new StringBuilder();
        for (var h : hrefs) {
            links.append("<a href=\"").append(h).append("\">next</a> ");
        }
        return "<html><head><title>" + title + "</title></head><body><article><p>"
                + "This page is about widgets and how they combine. ".repeat(12)
                + "</p>" + links + "</article></body></html>";
    }

    @Test
    void depthZeroReadsOnlyTheSeed() {
        routes.put("https://site.test/", page("Home", "/a", "/b"));
        routes.put("https://site.test/a", page("A"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":0}");

        assertTrue(out.contains("Scraped 1 page from"), out.substring(0, 120));
        assertTrue(out.contains("# Home"));
        assertFalse(out.contains("# A"), "depth 0 must not follow links");
        assertEquals(1, routes.pageHits().size());
    }

    @Test
    void followsLinksToTheGivenDepth() {
        routes.put("https://site.test/", page("Home", "/a"));
        routes.put("https://site.test/a", page("A", "/b"));
        routes.put("https://site.test/b", page("B"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1}");

        assertTrue(out.contains("# Home"));
        assertTrue(out.contains("# A"));
        assertFalse(out.contains("# B"), "depth 1 must not reach a grandchild");
    }

    @Test
    void sameHostOnlyIsTheDefaultAndExcludesOffHostLinks() {
        routes.put("https://site.test/", page("Home", "https://other.test/x"));
        routes.put("https://other.test/x", page("Offsite"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1}");

        assertFalse(out.contains("# Offsite"));
        assertTrue(out.contains("same host only"));
    }

    @Test
    void subdomainsCountAsTheSameSite() {
        routes.put("https://site.test/", page("Home", "https://docs.site.test/guide"));
        routes.put("https://docs.site.test/guide", page("Guide"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1}");
        assertTrue(out.contains("# Guide"), "docs.site.test is part of a site.test crawl");
    }

    @Test
    void offHostIsFollowedWhenSameHostOnlyIsFalse() {
        routes.put("https://site.test/", page("Home", "https://other.test/x"));
        routes.put("https://other.test/x", page("Offsite"));
        var out = scrape(
                "{\"url\":\"https://site.test/\",\"maxDepth\":1,\"sameHostOnly\":false}");
        assertTrue(out.contains("# Offsite"));
    }

    @Test
    void pageBudgetStopsTheCrawlAndSaysWhatWasLeft() {
        routes.put("https://site.test/", page("Home", "/a", "/b", "/c"));
        for (var p : List.of("a", "b", "c")) {
            routes.put("https://site.test/" + p, page(p.toUpperCase()));
        }
        var out = scrape("{\"url\":\"https://site.test/\",\"maxPages\":2,\"maxDepth\":1}");

        assertTrue(out.contains("Scraped 2 pages"), out.substring(0, 160));
        // Silent truncation would read as a complete crawl.
        assertTrue(out.contains("page budget (2) reached"), out.substring(0, 200));
        assertTrue(out.contains("not read"), out.substring(0, 200));
        assertEquals(2, routes.pageHits().size());
    }

    @Test
    void theDepthCeilingIsReadFromRuntimeConfig() {
        routes.put("https://site.test/", page("Home", "/a"));
        routes.put("https://site.test/a", page("A", "/b"));
        routes.put("https://site.test/b", page("B", "/c"));
        routes.put("https://site.test/c", page("C"));
        var request = "{\"url\":\"https://site.test/\",\"maxDepth\":3}";
        var originalDepth = ConfigService.get(WebScrapeSettings.MAX_DEPTH);
        try {
            assertFalse(scrape(request).contains("# C"), "the default ceiling of 2 caps a request for 3");

            // Raised, never lowered: the scrape classes running concurrently pass depths of
            // 2 or less, which a higher ceiling leaves untouched.
            ConfigService.set(WebScrapeSettings.MAX_DEPTH, "3");
            assertTrue(scrape(request).contains("# C"), "a Settings ceiling of 3 must admit depth 3");
        } finally {
            if (originalDepth == null) {
                ConfigService.delete(WebScrapeSettings.MAX_DEPTH);
            } else {
                ConfigService.set(WebScrapeSettings.MAX_DEPTH, originalDepth);
            }
        }
    }

    @Test
    void aUrlIsFetchedOnceEvenWhenLinkedRepeatedlyOrWithAFragment() {
        routes.put("https://site.test/", page("Home", "/a", "/a#section", "/a"));
        routes.put("https://site.test/a", page("A", "/"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":2}");

        assertEquals(2, routes.pageHits().size(), "seed + /a only: " + routes.pageHits());
        assertTrue(out.contains("Scraped 2 pages"));
    }

    @Test
    void queryStringsAreDistinctPagesNotDuplicates() {
        routes.put("https://site.test/", page("Home", "/p?id=1", "/p?id=2"));
        routes.put("https://site.test/p?id=1", page("One"));
        routes.put("https://site.test/p?id=2", page("Two"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1}");

        assertTrue(out.contains("# One") && out.contains("# Two"),
                "a query parameter can select a different page");
    }

    @Test
    void oneUnreachablePageDoesNotEndTheCrawl() {
        routes.put("https://site.test/", page("Home", "/broken", "/good"));
        routes.fail("https://site.test/broken", new IOException("connection reset"));
        routes.put("https://site.test/good", page("Good"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1}");

        assertTrue(out.contains("Not retrieved"), "the failure is reported");
        assertTrue(out.contains("connection reset"));
        assertTrue(out.contains("# Good"), "the crawl continues past a broken link");
    }

    @Test
    void eachPageIsAttributedToItsSourceUrl() {
        routes.put("https://site.test/", page("Home", "/a"));
        routes.put("https://site.test/a", page("A"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1}");

        assertTrue(out.contains("## https://site.test/"));
        assertTrue(out.contains("## https://site.test/a"));
    }

    @Test
    void nonHtmlPagesYieldNoLinksToFollow() {
        routes.put("https://site.test/", "{\"a\":1}", "application/json");
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":2}");

        assertEquals(1, routes.pageHits().size());
        assertTrue(out.contains("{\"a\":1}"), "JSON passes through unchanged");
    }

    /** Serves canned responses keyed by URL; records every URL actually requested. */
    static final class RouteInterceptor implements Interceptor {
        private final Map<String, String> bodies = new HashMap<>();
        private final Map<String, String> types = new HashMap<>();
        private final Map<String, IOException> failures = new HashMap<>();
        final List<String> hits = new ArrayList<>();

        /** Hits with robots.txt filtered out. RobotsCache is a process-global cache, so
         *  whether a given test observes that fetch depends on which test ran first —
         *  asserting on raw hits would make frontier tests order-dependent. */
        List<String> pageHits() {
            return hits.stream().filter(u -> !u.endsWith("/robots.txt")).toList();
        }

        void put(String url, String body) { put(url, body, "text/html; charset=utf-8"); }

        void put(String url, String body, String contentType) {
            bodies.put(url, body);
            types.put(url, contentType);
        }

        void fail(String url, IOException e) { failures.put(url, e); }

        @Override
        public Response intercept(Chain chain) throws IOException {
            var url = chain.request().url().toString();
            hits.add(url);
            var failure = failures.get(url);
            if (failure != null) {
                throw failure;
            }
            var body = bodies.get(url);
            if (body == null) {
                throw new IOException("no route for " + url);
            }
            var type = types.get(url);
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", type)
                    .body(ResponseBody.create(body, MediaType.parse(type)))
                    .build();
        }
    }
}
