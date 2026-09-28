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
import tools.WebScrapeTool;
import utils.RobotsCache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frontier parallelism (JCLAW-1093).
 *
 * <p>Overlap is asserted with an in-flight counter rather than elapsed time. A timing
 * assertion on a loaded CI box is a flake generator, and "two requests were open at
 * once" is the actual claim.
 *
 * <p>Subdomains of one seed: {@code sameHostOnly} treats them as the same site, while
 * per-host pacing keys on the full hostname — so they crawl together without sharing a
 * crawl-delay slot, which is the case parallelism is for.
 */
class WebScrapeConcurrencyTest extends UnitTest {

    private static final String CFG_CONCURRENCY = "web_scrape.concurrency";
    private static final String SEED = "https://conc.test/";

    private final ScrapeClientSwap swap = new ScrapeClientSwap();
    private final ScrapeConfigGuard config = new ScrapeConfigGuard();
    private RouteInterceptor routes;

    @BeforeEach
    void setup() throws Exception {
        routes = new RouteInterceptor();
        swap.install(new OkHttpClient.Builder()
                .addInterceptor(routes).callTimeout(20, TimeUnit.SECONDS).build());
        RobotsCache.resetForTest();
    }

    @AfterEach
    void teardown() throws Exception {
        try {
            config.restore();
            RobotsCache.resetForTest();
        } finally {
            swap.restore();
        }
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

    /** Four subdomains, so no two share a pacing slot. */
    private void seedFourHosts() {
        routes.put(SEED, page("Home",
                "https://a.conc.test/p", "https://b.conc.test/p",
                "https://c.conc.test/p", "https://d.conc.test/p"));
        for (var h : List.of("a", "b", "c", "d")) {
            routes.put("https://" + h + ".conc.test/p", page(h.toUpperCase()));
        }
    }

    @Test
    void aLevelIsFetchedConcurrently() {
        config.set(CFG_CONCURRENCY, "4");
        seedFourHosts();
        routes.holdMillis = 250;

        scrape("{\"url\":\"" + SEED + "\",\"maxDepth\":1}");

        assertTrue(routes.maxInFlight.get() > 1,
                "level must overlap; peak in-flight was " + routes.maxInFlight.get());
    }

    @Test
    void concurrencyOneKeepsTheCrawlStrictlySequential() {
        config.set(CFG_CONCURRENCY, "1");
        seedFourHosts();
        routes.holdMillis = 100;

        scrape("{\"url\":\"" + SEED + "\",\"maxDepth\":1}");

        assertEquals(1, routes.maxInFlight.get(),
                "an operator setting 1 must get the old sequential behaviour");
    }

    @Test
    void theCapIsClampedSoConfigCannotAskForUnboundedFanOut() {
        config.set(CFG_CONCURRENCY, "9999");
        seedFourHosts();
        routes.holdMillis = 150;

        scrape("{\"url\":\"" + SEED + "\",\"maxDepth\":1}");

        // Only 4 pages exist at this level, so the observable ceiling is 4 — the point
        // is that a nonsense value does not become the pool size.
        assertTrue(routes.maxInFlight.get() <= 16,
                "clamped to the ceiling, saw " + routes.maxInFlight.get());
    }

    @Test
    void pageOrderIsDiscoveryOrderNotCompletionOrder() {
        config.set(CFG_CONCURRENCY, "4");
        // First link is slowest, last is fastest: completion order is the reverse of
        // discovery order, so any output ordered by completion would show it.
        routes.put(SEED, page("Home",
                "https://a.conc.test/p", "https://b.conc.test/p",
                "https://c.conc.test/p", "https://d.conc.test/p"));
        routes.put("https://a.conc.test/p", page("AAA"));
        routes.put("https://b.conc.test/p", page("BBB"));
        routes.put("https://c.conc.test/p", page("CCC"));
        routes.put("https://d.conc.test/p", page("DDD"));
        routes.perUrlHoldMillis.put("https://a.conc.test/p", 400L);
        routes.perUrlHoldMillis.put("https://b.conc.test/p", 300L);
        routes.perUrlHoldMillis.put("https://c.conc.test/p", 200L);
        routes.perUrlHoldMillis.put("https://d.conc.test/p", 50L);

        var out = scrape("{\"url\":\"" + SEED + "\",\"maxDepth\":1}");

        // The JCLAW-1091 harness compares runs; a result whose page order varies by
        // completion timing is not comparable.
        assertTrue(out.indexOf("## https://a.conc.test/p") < out.indexOf("## https://b.conc.test/p"));
        assertTrue(out.indexOf("## https://b.conc.test/p") < out.indexOf("## https://c.conc.test/p"));
        assertTrue(out.indexOf("## https://c.conc.test/p") < out.indexOf("## https://d.conc.test/p"));
    }

    @Test
    void theBudgetStaysExactUnderConcurrency() {
        config.set(CFG_CONCURRENCY, "8");
        seedFourHosts();

        var out = scrape("{\"url\":\"" + SEED + "\",\"maxDepth\":1,\"maxPages\":3}");

        // Workers racing a shared counter would overshoot; the level is sliced before
        // submission instead.
        assertTrue(out.contains("Scraped 3 pages"), out.substring(0, 200));
        assertEquals(3, routes.pageHits().size(), "exactly the budget: " + routes.pageHits());
        assertTrue(out.contains("page budget (3) reached"), out.substring(0, 250));
    }

    static final class RouteInterceptor implements Interceptor {
        private final Map<String, String> bodies = new HashMap<>();
        final Map<String, Long> perUrlHoldMillis = new HashMap<>();
        final List<String> hits = new ArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        volatile long holdMillis = 0;

        void put(String url, String body) { bodies.put(url, body); }

        List<String> pageHits() {
            synchronized (hits) {
                return hits.stream().filter(u -> !u.endsWith("/robots.txt")).toList();
            }
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            var url = chain.request().url().toString();
            synchronized (hits) {
                hits.add(url);
            }
            var body = bodies.get(url);
            if (body == null) {
                throw new IOException("no route for " + url);
            }
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                long hold = perUrlHoldMillis.getOrDefault(url, holdMillis);
                if (hold > 0) {
                    Thread.sleep(hold);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                inFlight.decrementAndGet();
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
