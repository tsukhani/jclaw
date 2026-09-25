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
import utils.RobotsCache;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * robots.txt compliance and per-host pacing for web_scrape (JCLAW-1084).
 *
 * <p>Uses a host of its own rather than the one the other scrape suites use, because
 * {@link RobotsCache} is a process-global cache and play1 runs test classes
 * concurrently — sharing a host would make these assertions depend on which class
 * populated the cache first.
 */
class RobotsCacheTest extends UnitTest {

    private static final Field CLIENT_FIELD;
    static {
        try {
            CLIENT_FIELD = WebScrapeTool.class.getDeclaredField("CLIENT");
            CLIENT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static final String HOST = "https://robots.test";
    private static final String CFG_RESPECT = "web_scrape.respect-robots";
    private static final RobotsCache.Identity ID =
            new RobotsCache.Identity("Mozilla/5.0 (compatible; JClaw/1.0)", "jclaw");

    private RouteInterceptor routes;
    private OkHttpClient client;
    private OkHttpClient original;
    private String originalRespect;

    @BeforeEach
    void setup() throws Exception {
        RobotsCache.resetForTest();
        routes = new RouteInterceptor();
        client = new OkHttpClient.Builder()
                .addInterceptor(routes).callTimeout(5, TimeUnit.SECONDS).build();
        original = (OkHttpClient) CLIENT_FIELD.get(null);
        originalRespect = ConfigService.get(CFG_RESPECT, "true");
        CLIENT_FIELD.set(null, client);
    }

    @AfterEach
    void teardown() throws Exception {
        CLIENT_FIELD.set(null, original);
        ConfigService.set(CFG_RESPECT, originalRespect);
        RobotsCache.resetForTest();
    }

    private static String page(String title, String... hrefs) {
        var links = new StringBuilder();
        for (var h : hrefs) {
            links.append("<a href=\"").append(h).append("\">x</a> ");
        }
        return "<html><head><title>" + title + "</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p>" + links + "</article></body></html>";
    }

    private String scrape(String json) {
        return new WebScrapeTool().execute(json, (Agent) null);
    }

    @Test
    void aDisallowedPathIsNotFetchedAndIsReported() {
        routes.put(HOST + "/robots.txt", "User-agent: *\nDisallow: /private\n", "text/plain");
        routes.put(HOST + "/", page("Home", "/private/secret", "/public/ok"));
        routes.put(HOST + "/public/ok", page("Public"));

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");

        assertFalse(routes.pageHits().contains(HOST + "/private/secret"),
                "a disallowed path must not be requested: " + routes.pageHits());
        assertTrue(out.contains("# Public"), "allowed siblings are still crawled");
        assertTrue(out.contains("disallowed by robots.txt"), out.substring(0, 400));
    }

    @Test
    void aDirectiveAimedAtOurTokenIsHonored() {
        // A rule naming jclaw specifically must bind even when * is permissive.
        routes.put(HOST + "/robots.txt",
                "User-agent: *\nAllow: /\n\nUser-agent: jclaw\nDisallow: /nope\n", "text/plain");
        assertFalse(RobotsCache.isAllowed(URI.create(HOST + "/nope/x"), client, ID));
        assertTrue(RobotsCache.isAllowed(URI.create(HOST + "/yes/x"), client, ID));
    }

    @Test
    void aMissingRobotsTxtFailsOpen() {
        // Politeness, not security: a broken file on someone else's server must not
        // break the caller's crawl. SsrfGuard and the allowlist are the controls that
        // fail closed.
        routes.put(HOST + "/", page("Home"));
        assertTrue(RobotsCache.isAllowed(URI.create(HOST + "/anything"), client, ID));
    }

    @Test
    void robotsTxtIsFetchedOncePerHostNotOncePerUrl() {
        routes.put(HOST + "/robots.txt", "User-agent: *\nAllow: /\n", "text/plain");
        routes.put(HOST + "/", page("Home", "/a", "/b"));
        routes.put(HOST + "/a", page("A"));
        routes.put(HOST + "/b", page("B"));

        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");

        long robotsFetches = routes.hits.stream().filter(u -> u.endsWith("/robots.txt")).count();
        assertEquals(1, robotsFetches, "cached per host: " + routes.hits);
    }

    @Test
    void aDeclaredCrawlDelayIsHonoredWithinItsBand() {
        routes.put(HOST + "/robots.txt", "User-agent: *\nCrawl-delay: 2\n", "text/plain");
        assertEquals(2_000, RobotsCache.delayMillis(URI.create(HOST + "/"), client, ID));
    }

    @Test
    void aLargeCrawlDelayIsClampedRatherThanParkingTheCrawl() {
        // Sites do declare tens of seconds. Honouring that literally would park the
        // thread for the whole budget; the cap lets the wall-clock budget end the crawl
        // instead, so the outcome is fewer pages rather than a stalled tool call.
        routes.put(HOST + "/robots.txt", "User-agent: *\nCrawl-delay: 30\n", "text/plain");
        assertEquals(5_000, RobotsCache.delayMillis(URI.create(HOST + "/"), client, ID));
    }

    @Test
    void aCrawlDelayBeyondTheParsersOwnMaximumIsDiscardedNotClamped() {
        // Two thresholds stack, and only the second is ours. SimpleRobotRulesParser
        // discards any Crawl-delay over DEFAULT_MAX_CRAWL_DELAY (300s) rather than
        // capping it, so the value never reaches our clamp and the default applies.
        // Pinned because it is the opposite of what the clamp above would suggest.
        routes.put(HOST + "/robots.txt", "User-agent: *\nCrawl-delay: 600\n", "text/plain");
        assertEquals(RobotsCache.DEFAULT_DELAY_MS,
                RobotsCache.delayMillis(URI.create(HOST + "/"), client, ID));
    }

    @Test
    void noDeclaredDelayStillPaces() {
        routes.put(HOST + "/robots.txt", "User-agent: *\nAllow: /\n", "text/plain");
        assertEquals(RobotsCache.DEFAULT_DELAY_MS,
                RobotsCache.delayMillis(URI.create(HOST + "/"), client, ID));
    }

    @Test
    void consecutiveRequestsToOneHostAreSpacedByTheDelay() throws Exception {
        var uri = URI.create(HOST + "/x");
        long t0 = System.nanoTime();
        RobotsCache.awaitSlot(uri, 200);
        RobotsCache.awaitSlot(uri, 200);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(elapsedMs >= 190, "second request must wait its slot, waited " + elapsedMs + "ms");
    }

    @Test
    void pacingIsPerHostSoOneSlowSiteDoesNotStallAnother() throws Exception {
        long t0 = System.nanoTime();
        RobotsCache.awaitSlot(URI.create("https://a.test/x"), 400);
        RobotsCache.awaitSlot(URI.create("https://b.test/x"), 400);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(elapsedMs < 300, "different hosts share no slot, took " + elapsedMs + "ms");
    }

    @Test
    void aPerCallOverrideIgnoresRobotsWithoutTouchingConfig() {
        // The operator asking in chat is the case this exists for: config stays on, one
        // request opts out.
        ConfigService.set(CFG_RESPECT, "true");
        routes.put(HOST + "/robots.txt", "User-agent: *\nDisallow: /\n", "text/plain");
        routes.put(HOST + "/", page("Home", "/private/secret"));
        routes.put(HOST + "/private/secret", page("Secret"));

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1,\"respectRobots\":false}");
        assertTrue(out.contains("# Secret"), "the override reaches a disallowed path");
        assertFalse(out.contains("disallowed by robots.txt"));
    }

    @Test
    void omittingTheArgumentKeepsRobotsHonored() {
        // Opt-out per call, never by omission.
        ConfigService.set(CFG_RESPECT, "true");
        routes.put(HOST + "/robots.txt", "User-agent: *\nDisallow: /private\n", "text/plain");
        routes.put(HOST + "/", page("Home", "/private/secret"));
        routes.put(HOST + "/private/secret", page("Secret"));

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");
        assertFalse(out.contains("# Secret"));
        assertTrue(out.contains("disallowed by robots.txt"));
    }

    @Test
    void theArgumentCanAlsoRestoreRobotsWhenConfigTurnedThemOff() {
        ConfigService.set(CFG_RESPECT, "false");
        routes.put(HOST + "/robots.txt", "User-agent: *\nDisallow: /private\n", "text/plain");
        routes.put(HOST + "/", page("Home", "/private/secret"));
        routes.put(HOST + "/private/secret", page("Secret"));

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1,\"respectRobots\":true}");
        assertFalse(out.contains("# Secret"), "the argument overrides config in both directions");
    }

    @Test
    void turningOffRespectRobotsIgnoresTheRulesButStillPaces() {
        ConfigService.set(CFG_RESPECT, "false");
        routes.put(HOST + "/robots.txt", "User-agent: *\nDisallow: /\n", "text/plain");
        routes.put(HOST + "/", page("Home", "/private/secret"));
        routes.put(HOST + "/private/secret", page("Secret"));

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");

        assertTrue(out.contains("# Secret"), "the override reaches a disallowed path");
        // The override is about the site's opinion, not about request rate: with robots
        // ignored, robots.txt is not even fetched.
        assertFalse(routes.hits.stream().anyMatch(u -> u.endsWith("/robots.txt")),
                "robots.txt need not be fetched when its rules are ignored: " + routes.hits);
    }

    static final class RouteInterceptor implements Interceptor {
        private final Map<String, String> bodies = new HashMap<>();
        private final Map<String, String> types = new HashMap<>();
        final List<String> hits = new ArrayList<>();

        void put(String url, String body) { put(url, body, "text/html; charset=utf-8"); }

        void put(String url, String body, String contentType) {
            bodies.put(url, body);
            types.put(url, contentType);
        }

        List<String> pageHits() {
            return hits.stream().filter(u -> !u.endsWith("/robots.txt")).toList();
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            var url = chain.request().url().toString();
            hits.add(url);
            var body = bodies.get(url);
            if (body == null) {
                throw new IOException("no route for " + url);
            }
            var type = types.get(url);
            return new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .addHeader("Content-Type", type)
                    .body(ResponseBody.create(body, MediaType.parse(type)))
                    .build();
        }
    }
}
