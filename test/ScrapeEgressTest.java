import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import models.ScrapeJob;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.scrape.ScrapeReason;
import tools.WebScrapeTool;
import tools.scrape.CrawlListener;
import tools.scrape.ScrapeJobRequest;
import tools.scrape.WebScrapeSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A crawl goes out the way it started, whatever Settings says by the time it finishes, and a job records
 * each run's way out (JCLAW-1333). The target is a public address literal, so the SSRF guard admits it
 * without a DNS lookup, and only the proxy fixture ever answers it.
 */
class ScrapeEgressTest extends UnitTest {

    private final ScrapeClientSwap swap = new ScrapeClientSwap();
    private final ScrapeConfigGuard config = new ScrapeConfigGuard();
    private MockWebServer proxy;
    private final List<String> proxied = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setup() throws Exception {
        proxy = new MockWebServer();
        proxy.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                proxied.add(request.getTarget());
                return switch (request.getTarget()) {
                    case "http://8.8.8.8/" -> html(page("Home", "/a"));
                    case "http://8.8.8.8/a" -> html(page("A"));
                    default -> new MockResponse.Builder().code(404).build();
                };
            }
        });
        proxy.start();
        swap.install(new OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build());
    }

    @AfterEach
    void teardown() throws Exception {
        try {
            config.restore();
        } finally {
            try {
                swap.restore();
            } finally {
                proxy.close();
            }
        }
    }

    private static MockResponse html(String body) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body(body).build();
    }

    private static String page(String title, String... hrefs) {
        var links = new StringBuilder();
        for (var h : hrefs) links.append("<a href=\"").append(h).append("\">next</a> ");
        return "<html><head><title>" + title + "</title></head><body><article><p>"
                + "This page is about widgets and how they combine. ".repeat(12)
                + "</p>" + links + "</article></body></html>";
    }

    /** Records what the crawl reports; on the first page it switches the proxy off in Settings. */
    private static final class Recording implements CrawlListener {
        final List<String> egress = new ArrayList<>();
        final List<Page> pages = Collections.synchronizedList(new ArrayList<>());
        final boolean switchOffAfterFirstPage;
        final boolean stopBeforeFetching;

        Recording(boolean switchOffAfterFirstPage, boolean stopBeforeFetching) {
            this.switchOffAfterFirstPage = switchOffAfterFirstPage;
            this.stopBeforeFetching = stopBeforeFetching;
        }

        @Override public void page(Page page) {
            pages.add(page);
            if (switchOffAfterFirstPage) ConfigService.set(WebScrapeSettings.PROXY_ENABLED, "false");
        }

        @Override public void discovered(int urls) {}

        @Override public boolean stopRequested() {
            return stopBeforeFetching;
        }

        @Override public void egress(@Nullable String proxyUrl) {
            egress.add(String.valueOf(proxyUrl));
        }
    }

    private static void crawl(String url, CrawlListener listener) {
        var request = ScrapeJobRequest.fromJson("""
                {"url":"%s","maxPages":2,"maxDepth":1,"respectRobots":false}""".formatted(url));
        new WebScrapeTool().crawlForJob(request, listener, CrawlListener.Resume.fresh(Duration.ofMinutes(1)));
    }

    @Test
    void aCrawlKeepsTheProxyItStartedWithWhenTheSettingChangesMidway() {
        var proxyUrl = "http://127.0.0.1:" + proxy.getPort();
        config.set(WebScrapeSettings.PROXY_URL, proxyUrl);
        config.set(WebScrapeSettings.PROXY_ENABLED, "true");
        var listener = new Recording(true, false);

        crawl("http://8.8.8.8/", listener);

        assertEquals(List.of(proxyUrl), listener.egress, "told once, before the first fetch");
        assertEquals(2, listener.pages.size(), listener.pages.toString());
        for (var page : listener.pages) assertEquals(ScrapeReason.OK, page.reason(), page.toString());
        assertTrue(proxied.contains("http://8.8.8.8/a"),
                "the page after the switch still went through the proxy: " + proxied);
    }

    @Test
    void aDirectCrawlSaysSo() {
        config.set(WebScrapeSettings.PROXY_ENABLED, "false");
        // Stopped before its first fetch, so nothing is dialled, and still told where it goes out.
        var listener = new Recording(false, true);

        crawl("http://8.8.8.8/", listener);

        assertEquals(List.of("null"), listener.egress);
        assertTrue(listener.pages.isEmpty(), listener.pages.toString());
        assertTrue(proxied.isEmpty(), proxied.toString());
    }

    @Test
    void aJobRecordsEachDifferentWayOutOnceInOrder() {
        var job = new ScrapeJob();
        assertNull(job.egressRoutes(), "a job from before it was recorded says nothing");

        job.recordEgress("http://74.81.81.81:823");
        job.recordEgress("http://74.81.81.81:823");
        assertEquals(List.of("http://74.81.81.81:823"), job.egressRoutes());

        job.recordEgress(null);
        assertEquals(List.of("http://74.81.81.81:823", ScrapeJob.DIRECT), job.egressRoutes());
    }
}
