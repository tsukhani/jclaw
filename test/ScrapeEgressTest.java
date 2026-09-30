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
import tools.scrape.DataImpulsePlans;
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
    /** The Proxy-Authorization each request carried; with it set, a request without one is challenged. */
    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean challenge;

    @BeforeEach
    void setup() throws Exception {
        proxy = new MockWebServer();
        proxy.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                var authorization = request.getHeaders().get("Proxy-Authorization");
                if (challenge && authorization == null) {
                    return new MockResponse.Builder().code(407)
                            .addHeader("Proxy-Authenticate", "Basic realm=\"proxy\"").build();
                }
                if (authorization != null) authorizations.add(authorization);
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
            DataImpulsePlans.setGatewayHostForTest(null);
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
        crawl(url, "", listener);
    }

    /** {@code extra} is spliced into the stored request, as a resumed job reads it back. */
    private static void crawl(String url, String extra, CrawlListener listener) {
        var request = ScrapeJobRequest.fromJson("""
                {"url":"%s","maxPages":2,"maxDepth":1,"respectRobots":false%s}""".formatted(url, extra));
        new WebScrapeTool().crawlForJob(request, listener, CrawlListener.Resume.fresh(Duration.ofMinutes(1)));
    }

    private static final String MOBILE = ",\"proxy\":{\"provider\":\"dataimpulse\",\"plan\":\"mobile\"}";

    /** The fixture stands in for a DataImpulse gateway, with every plan cleared but the ones a test saves. */
    private String asGateway() {
        var proxyUrl = "http://127.0.0.1:" + proxy.getPort();
        config.set(WebScrapeSettings.PROXY_URL, proxyUrl);
        // After the first write, which takes the lock every user of this process-wide override holds.
        DataImpulsePlans.setGatewayHostForTest("127.0.0.1");
        for (var plan : DataImpulsePlans.PLANS.keySet()) {
            config.delete(DataImpulsePlans.loginKey(plan));
            config.delete(DataImpulsePlans.passwordKey(plan));
        }
        config.delete(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN);
        config.set(WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING, "cr.de");
        challenge = true;
        return proxyUrl;
    }

    private static String basic(String username, String password) {
        return okhttp3.Credentials.basic(username, password);
    }

    @Test
    void aJobsPlanCarriesItThroughWithTheProxySwitchedOffAndLeavesTheSwitchAlone() {
        var proxyUrl = asGateway();
        config.set(DataImpulsePlans.loginKey("mobile"), "abc");
        config.set(DataImpulsePlans.passwordKey("mobile"), "mob-pass");
        config.set(DataImpulsePlans.loginKey("residential"), "res");
        config.set(DataImpulsePlans.passwordKey("residential"), "res-pass");
        config.set(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, "residential");
        config.set(WebScrapeSettings.PROXY_ENABLED, "false");
        var listener = new Recording(false, false);

        crawl("http://8.8.8.8/", MOBILE, listener);

        assertEquals(List.of(proxyUrl + "#mobile"), listener.egress);
        assertEquals(2, listener.pages.size(), listener.pages.toString());
        assertTrue(proxied.contains("http://8.8.8.8/a"), proxied.toString());
        assertFalse(authorizations.isEmpty());
        for (var sent : authorizations) assertEquals(basic("abc__cr.de", "mob-pass"), sent, "the job's plan, not the active one");
        assertEquals("false", ConfigService.get(WebScrapeSettings.PROXY_ENABLED, ""));
        assertEquals("residential", ConfigService.get(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, ""));
    }

    @Test
    void theActivePlanInSettingsIsRecordedByName() {
        var proxyUrl = asGateway();
        config.set(DataImpulsePlans.loginKey("residential"), "res");
        config.set(DataImpulsePlans.passwordKey("residential"), "res-pass");
        config.set(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, "residential");
        config.set(WebScrapeSettings.PROXY_ENABLED, "true");
        var listener = new Recording(false, false);

        crawl("http://8.8.8.8/", listener);

        assertEquals(List.of(proxyUrl + "#residential"), listener.egress);
        assertEquals(2, listener.pages.size(), listener.pages.toString());
        for (var sent : authorizations) assertEquals(basic("res__cr.de", "res-pass"), sent);
    }

    @Test
    void aManualProxyIsRecordedWithoutAPlan() {
        var proxyUrl = "http://127.0.0.1:" + proxy.getPort();
        config.set(WebScrapeSettings.PROXY_URL, proxyUrl);
        config.delete(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN);
        config.set(WebScrapeSettings.PROXY_ENABLED, "true");
        var listener = new Recording(false, true);

        crawl("http://8.8.8.8/", listener);

        assertEquals(List.of(proxyUrl), listener.egress);
    }

    @Test
    void aPlanWhoseCredentialsAreGoneFailsTheJobRatherThanGoingDirect() {
        asGateway();
        config.set(DataImpulsePlans.loginKey("mobile"), "abc");
        config.set(WebScrapeSettings.PROXY_ENABLED, "false");
        var listener = new Recording(false, false);

        var error = assertThrows(IllegalStateException.class, () -> crawl("http://8.8.8.8/", MOBILE, listener));

        assertTrue(error.getMessage().contains("DataImpulse Mobile plan"), error.getMessage());
        assertTrue(listener.egress.isEmpty(), listener.egress.toString());
        assertTrue(listener.pages.isEmpty(), listener.pages.toString());
    }

    @Test
    void withoutAGatewaySavedAPlanGoesToDataImpulsesOwn() {
        config.set(WebScrapeSettings.PROXY_URL, "http://proxy.example:3128");
        config.set(DataImpulsePlans.loginKey("mobile"), "abc");
        config.set(DataImpulsePlans.passwordKey("mobile"), "mob-pass");
        config.set(WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING, "");

        var plan = DataImpulsePlans.proxyFor("mobile");

        assertEquals("http://gw.dataimpulse.com:823#mobile", plan.route());
        assertEquals("http://gw.dataimpulse.com:823", plan.url(), "the url stays the address alone");
        assertEquals("abc", plan.username());
        assertEquals("mob-pass", plan.password());
        assertFalse(plan.toString().contains("mob-pass"), plan.toString());
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
