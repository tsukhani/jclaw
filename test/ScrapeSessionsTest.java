import com.google.gson.JsonObject;
import models.Agent;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import tools.WebFetchTool;
import tools.WebScrapeTool;
import tools.scrape.CrawlListener;
import tools.scrape.ImpersonatedFetcher;
import tools.scrape.RenderedFetcher;
import tools.scrape.ScrapeJobRequest;
import tools.scrape.ScrapeLadder;
import tools.scrape.ScrapeOutput;
import tools.scrape.ScrapeProxy;
import tools.scrape.ScrapeSessions;
import tools.scrape.WebScrapeSettings;
import utils.WebExtraction;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * A crawl's stealth-browser sessions (JCLAW-1307): one per host, reused across the crawl's pages,
 * closed however the crawl ends, and never shared with another crawl or opened by a lone fetch.
 *
 * <p>The stealth sidecar is replaced through {@link ScrapeSessions.Sidecars}, the seam a crawl reaches
 * it by, so nothing here launches a browser; its own session bookkeeping is held by
 * {@code ScrapeSidecarContractTest}. URLs use public IP literals so {@code SsrfGuard} still runs for
 * real without a DNS lookup, and rung 1 is served by an interceptor, as in {@code WebScrapeToolTest}.
 */
class ScrapeSessionsTest extends UnitTest {

    private static final String HOST = "93.184.215.14";
    private static final String OTHER = "8.8.8.8";
    private static final String CHROME_146 = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";

    /** A Cloudflare gate as rung 1 sees it: thin, and carrying the challenge options. */
    private static final String GATE = "<html><head><title>Just a moment...</title></head><body><script>"
            + "window._cf_chl_opt={cType: 'managed'};</script></body></html>";

    private final ScrapeConfigGuard config = new ScrapeConfigGuard();

    @AfterEach
    void restoreConfig() {
        config.restore();
    }

    private static String page(String title, String... hrefs) {
        var links = new StringBuilder();
        for (var h : hrefs) links.append("<a href=\"").append(h).append("\">next</a> ");
        return "<html><head><title>" + title + "</title></head><body><article><p>"
                + "This page is about widgets and how they combine. ".repeat(12)
                + "</p>" + links + "</article></body></html>";
    }

    private static WebExtraction.FetchResult fetched(String url, String html) {
        return new WebExtraction.FetchResult(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8", url);
    }

    /** The two sidecars in memory. A session is an id; every render and close is recorded. */
    static final class FakeSidecars implements ScrapeSessions.Sidecars {
        final List<String> opened = new CopyOnWriteArrayList<>();
        final List<String> closed = new CopyOnWriteArrayList<>();
        /** "session url", or "own url" for a render with a browser of its own. */
        final List<String> renders = new CopyOnWriteArrayList<>();
        final List<Boolean> clearanceAsked = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> impersonated = new CopyOnWriteArrayList<>();
        final Set<String> gone = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger ids = new AtomicInteger();
        volatile int cap = Integer.MAX_VALUE;
        volatile boolean alwaysGone;
        volatile boolean failFirstClose;
        volatile boolean impersonateInstalled;
        volatile long renderMillis;
        volatile ScrapeSessions.@Nullable Clearance clearance;
        volatile OptionalInt profileMajor = OptionalInt.of(146);
        /** What a render of a URL returns; by default a page linking nowhere. */
        volatile java.util.function.Function<String, String> pages = url -> page("Rendered " + url);

        @Override public boolean renderAvailable() {
            return true;
        }

        @Override public boolean impersonateAvailable() {
            return impersonateInstalled;
        }

        @Override public RenderedFetcher.Render render(String url, String language) {
            renders.add("own " + url);
            return new RenderedFetcher.Render(fetched(url, pages.apply(url)), null);
        }

        @Override public @Nullable String open(String host, JsonObject pins, String language,
                                               @Nullable ScrapeProxy proxy) {
            if (opened.size() - closed.size() >= cap) return null;
            var id = "s" + ids.incrementAndGet();
            opened.add(host + " " + id);
            return id;
        }

        @Override public RenderedFetcher.Render render(String session, String url, boolean clearanceWanted)
                throws IOException {
            if (alwaysGone || gone.remove(session)) throw new RenderedFetcher.SessionGoneException("gone");
            var running = inFlight.computeIfAbsent(session, _ -> new AtomicInteger()).incrementAndGet();
            peak.accumulateAndGet(running, Math::max);
            try {
                if (renderMillis > 0) Thread.sleep(renderMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } finally {
                inFlight.get(session).decrementAndGet();
            }
            renders.add(session + " " + url);
            clearanceAsked.add(clearanceWanted);
            return new RenderedFetcher.Render(fetched(url, pages.apply(url)), "managed; cleared",
                    clearanceWanted ? clearance : null);
        }

        @Override public void close(String session) throws IOException {
            if (failFirstClose && closed.isEmpty()) {
                closed.add("failed " + session);
                throw new IOException("sidecar went away");
            }
            closed.add(session);
        }

        @Override public WebExtraction.FetchResult impersonate(String url, Map<String, String> headers, String host,
                                                               Map<String, String> hostHeaders) {
            impersonated.add(hostHeaders);
            return fetched(url, page("Impersonated " + url));
        }

        @Override public OptionalInt impersonationChromeMajor() {
            return profileMajor;
        }

        /** The sessions that served each render, in order, ignoring renders with a browser of their own. */
        List<String> sessionsUsed() {
            return renders.stream().filter(r -> !r.startsWith("own ")).map(r -> r.split(" ")[0]).toList();
        }
    }

    // ==================== The handle ====================

    @Test
    void aHostsPagesShareOneSessionAndEachHostHasItsOwn() throws Exception {
        var sidecars = new FakeSidecars();
        try (var sessions = new ScrapeSessions(sidecars, false)) {
            sessions.render("https://" + HOST + "/a", "en");
            sessions.render("https://" + HOST + "/b", "en");
            sessions.render("https://" + OTHER + "/c", "en");
        }
        assertEquals(List.of(HOST + " s1", OTHER + " s2"), sidecars.opened);
        assertEquals(List.of("s1", "s1", "s2"), sidecars.sessionsUsed());
        assertEquals(List.of("s1", "s2"), sidecars.closed.stream().sorted().toList(),
                "every session the crawl opened is closed, once");
    }

    @Test
    void aClosedHandleOpensNothingMore() throws Exception {
        var sidecars = new FakeSidecars();
        ScrapeSessions ended;
        try (var sessions = new ScrapeSessions(sidecars, false)) {
            sessions.render("https://" + HOST + "/a", "en");
            ended = sessions;
        }
        ended.render("https://" + HOST + "/late", "en");
        assertEquals(1, sidecars.opened.size());
        assertEquals("own https://" + HOST + "/late", sidecars.renders.getLast(),
                "a render after the crawl ended uses a browser of its own rather than reopening");
    }

    @Test
    void pastTheSidecarsCapAPageRendersWithABrowserOfItsOwn() throws Exception {
        var sidecars = new FakeSidecars();
        sidecars.cap = 0;
        try (var sessions = new ScrapeSessions(sidecars, false)) {
            sessions.render("https://" + HOST + "/a", "en");
            sidecars.cap = 1;
            sessions.render("https://" + HOST + "/b", "en");
        }
        assertEquals(List.of("own https://" + HOST + "/a", "s1 https://" + HOST + "/b"), sidecars.renders,
                "a refused open falls back for that page, and the next page asks again");
        assertEquals(List.of("s1"), sidecars.closed);
    }

    @Test
    void aSessionTheSidecarLostIsReopenedOnceThenGivenUpForThatPage() throws Exception {
        var sidecars = new FakeSidecars();
        try (var sessions = new ScrapeSessions(sidecars, false)) {
            sessions.render("https://" + HOST + "/a", "en");
            sidecars.gone.add("s1");
            sessions.render("https://" + HOST + "/b", "en");
            sidecars.alwaysGone = true;
            sessions.render("https://" + HOST + "/c", "en");
        }
        assertEquals(List.of("s1 https://" + HOST + "/a", "s2 https://" + HOST + "/b", "own https://" + HOST + "/c"),
                sidecars.renders);
        assertEquals(3, sidecars.opened.size(), "one reopen for /b, and one for /c before giving up on it");
        assertTrue(sidecars.closed.isEmpty(), "a session the sidecar lost is not closed again: " + sidecars.closed);
    }

    @Test
    void anUnsafeUrlIsRefusedBeforeTheSidecarIsAsked() {
        var sidecars = new FakeSidecars();
        try (var sessions = new ScrapeSessions(sidecars, false)) {
            for (var url : List.of("http://127.0.0.1:9000/api/status", "http://169.254.169.254/latest/meta-data/",
                    "http://[::1]:9/")) {
                assertThrows(SecurityException.class, () -> sessions.render(url, "en"), url);
            }
        }
        assertTrue(sidecars.opened.isEmpty(), "no session is opened for a URL the guard refused");
        assertTrue(sidecars.renders.isEmpty());
    }

    @Test
    void concurrentPagesOnOneHostQueueForItsOneSession() throws Exception {
        var sidecars = new FakeSidecars();
        sidecars.renderMillis = 30;
        try (var sessions = new ScrapeSessions(sidecars, false);
             var pool = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<java.util.concurrent.Future<RenderedFetcher.Render>>();
            for (int i = 0; i < 6; i++) {
                var url = "https://" + HOST + "/p" + i;
                futures.add(pool.submit(() -> sessions.render(url, "en")));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, sidecars.opened.size(), "six concurrent pages, one session: " + sidecars.opened);
        assertEquals(6, sidecars.sessionsUsed().size());
        assertEquals(1, sidecars.peak.get(), "a session renders one page at a time, queued here rather than inside its "
                + "call timeout");
    }

    @Test
    void closingSurvivesASessionThatWillNotClose() throws Exception {
        var sidecars = new FakeSidecars();
        sidecars.failFirstClose = true;
        try (var sessions = new ScrapeSessions(sidecars, false)) {
            sessions.render("https://" + HOST + "/a", "en");
            sessions.render("https://" + OTHER + "/b", "en");
        }
        assertEquals(2, sidecars.closed.size(), "one failed close must not leave the other open: " + sidecars.closed);
    }

    // ==================== The clearance handoff ====================

    @Test
    void withTheHandoffOffTheClearanceNeverLeavesTheSidecar() throws Exception {
        var sidecars = new FakeSidecars();
        sidecars.clearance = new ScrapeSessions.Clearance(CHROME_146, "tok");
        try (var sessions = new ScrapeSessions(sidecars, false)) {
            sessions.render("https://" + HOST + "/a", "en");
            assertEquals(ScrapeSessions.RungTwo.AS_USUAL, sessions.rungTwo("https://" + HOST + "/b"));
        }
        assertEquals(List.of(false), sidecars.clearanceAsked);
    }

    @Test
    void aCoherentClearanceGoesToRungTwoWithTheBrowsersExactUserAgent() throws Exception {
        var sidecars = new FakeSidecars();
        sidecars.clearance = new ScrapeSessions.Clearance(CHROME_146, "tok");
        try (var sessions = new ScrapeSessions(sidecars, true)) {
            assertEquals(ScrapeSessions.RungTwo.AS_USUAL, sessions.rungTwo("https://" + HOST + "/a"),
                    "no session yet, nothing to hand down");
            sessions.render("https://" + HOST + "/a", "en");
            var rungTwo = sessions.rungTwo("https://" + HOST + "/b");
            assertFalse(rungTwo.skip());
            assertEquals(Map.of("User-Agent", CHROME_146, "Cookie", "cf_clearance=tok"), rungTwo.hostHeaders());
            assertEquals(ScrapeSessions.RungTwo.AS_USUAL, sessions.rungTwo("https://" + OTHER + "/c"),
                    "one host's clearance is never another's");
            assertFalse(rungTwo.toString().contains("tok"), "a logged decision must not carry the cookie");
        }
        assertEquals(List.of(true), sidecars.clearanceAsked);
    }

    @Test
    void aClearanceFromAnotherChromeKeepsTheHostOnTheBrowserRung() throws Exception {
        var sidecars = new FakeSidecars();
        sidecars.clearance = new ScrapeSessions.Clearance(CHROME_146.replace("146", "153"), "tok");
        try (var sessions = new ScrapeSessions(sidecars, true)) {
            sessions.render("https://" + HOST + "/a", "en");
            assertEquals(ScrapeSessions.RungTwo.SKIPPED, sessions.rungTwo("https://" + HOST + "/b"),
                    "a Chrome 146 ClientHello must not carry a Chrome 153 agent string (JCLAW-1087)");
            sidecars.clearance = new ScrapeSessions.Clearance(CHROME_146, "tok2");
            sessions.render("https://" + HOST + "/c", "en");
            assertEquals(ScrapeSessions.RungTwo.SKIPPED, sessions.rungTwo("https://" + HOST + "/d"),
                    "the host stays on rung 3 for the rest of the crawl");
        }
    }

    @Test
    void aProfileOfUnknownVersionIsNotCoherentWithAnyBrowser() throws Exception {
        var sidecars = new FakeSidecars();
        sidecars.clearance = new ScrapeSessions.Clearance(CHROME_146, "tok");
        sidecars.profileMajor = OptionalInt.empty();
        try (var sessions = new ScrapeSessions(sidecars, true)) {
            sessions.render("https://" + HOST + "/a", "en");
            assertEquals(ScrapeSessions.RungTwo.SKIPPED, sessions.rungTwo("https://" + HOST + "/b"));
        }
    }

    @Test
    void aClearanceEarnedOnAnotherEgressIsNotHandedDown() throws Exception {
        var sidecars = new FakeSidecars();
        sidecars.clearance = new ScrapeSessions.Clearance(CHROME_146, "tok");
        config.delete(WebScrapeSettings.PROXY_URL);
        try (var sessions = new ScrapeSessions(sidecars, true)) {
            sessions.render("https://" + HOST + "/a", "en");
            config.set(WebScrapeSettings.PROXY_URL, "http://93.184.215.20:3128");
            assertEquals(ScrapeSessions.RungTwo.SKIPPED, sessions.rungTwo("https://" + HOST + "/b"),
                    "a clearance is bound to the address it was earned from");
        }
    }

    @Test
    void theClimbHandsACoherentClearanceToRungTwoAndSkipsItOtherwise() throws Exception {
        var url = "https://" + HOST + "/b";
        var blocked = new ScrapeLadder.Attempt(ScrapeRung.PLAIN, fetched(url, GATE), "", ScrapeReason.TRUST_BLOCK,
                null, 403);

        var coherent = new FakeSidecars();
        coherent.impersonateInstalled = true;
        coherent.clearance = new ScrapeSessions.Clearance(CHROME_146, "tok");
        try (var sessions = new ScrapeSessions(coherent, true)) {
            sessions.render("https://" + HOST + "/a", "en");
            var best = ScrapeLadder.climb(url, blocked, "en", sessions);
            assertEquals(ScrapeRung.IMPERSONATE, best.servedBy(), best.reason() + ": " + best.detail());
        }
        assertEquals(List.of(Map.of("User-Agent", CHROME_146, "Cookie", "cf_clearance=tok")), coherent.impersonated);
        assertEquals(1, coherent.renders.size(), "rung 2 read the page, so it was not rendered");

        var mismatched = new FakeSidecars();
        mismatched.impersonateInstalled = true;
        mismatched.profileMajor = OptionalInt.of(145);
        mismatched.clearance = new ScrapeSessions.Clearance(CHROME_146, "tok");
        try (var sessions = new ScrapeSessions(mismatched, true)) {
            sessions.render("https://" + HOST + "/a", "en");
            var best = ScrapeLadder.climb(url, blocked, "en", sessions);
            assertEquals(ScrapeRung.BROWSER, best.servedBy());
        }
        assertTrue(mismatched.impersonated.isEmpty(), "an incoherent host skips rung 2 entirely");
        assertEquals(List.of("s1", "s1"), mismatched.sessionsUsed(), "and renders in its session");
    }

    @Test
    void theClearanceGoesToItsHostOverHttpsAndNowhereElse() throws Exception {
        var sent = new ArrayList<String>();
        WebExtraction.Transport base = (uri, headers) -> {
            sent.add(uri + " " + headers.getOrDefault("Cookie", "-"));
            return uri.getPath().equals("/start")
                    ? new WebExtraction.Exchange(302, new byte[0], "", "https://" + OTHER + "/elsewhere", Map.of())
                    : new WebExtraction.Exchange(200, page("Landed").getBytes(StandardCharsets.UTF_8),
                            "text/html", "", Map.of());
        };
        var scoped = ImpersonatedFetcher.scoped(base, HOST, Map.of("Cookie", "cf_clearance=tok"));

        WebExtraction.fetch("https://" + HOST + "/start", Map.of("Accept", "text/html"), scoped);
        scoped.exchange(URI.create("http://" + HOST + "/plain"), Map.of());

        assertEquals(List.of("https://" + HOST + "/start cf_clearance=tok", "https://" + OTHER + "/elsewhere -",
                "http://" + HOST + "/plain -"), sent,
                "a redirect to another host, or the same host in the clear, carries no cookie");
    }

    // ==================== Crawls ====================

    private static final Field CLIENT_FIELD;
    static {
        try {
            CLIENT_FIELD = WebScrapeTool.class.getDeclaredField("CLIENT");
            CLIENT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private GatedSite site;
    private @Nullable OkHttpClient original;

    @BeforeEach
    void serveRungOne() throws Exception {
        site = new GatedSite();
        original = (OkHttpClient) CLIENT_FIELD.get(null);
        CLIENT_FIELD.set(null, new OkHttpClient.Builder().addInterceptor(site).callTimeout(5, TimeUnit.SECONDS).build());
    }

    @AfterEach
    void restoreRungOne() throws Exception {
        CLIENT_FIELD.set(null, original);
    }

    /** Every page is a Cloudflare gate to rung 1; robots.txt and sitemaps are absent. */
    static final class GatedSite implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            var path = chain.request().url().encodedPath();
            if (path.endsWith("robots.txt") || path.contains("sitemap")) throw new IOException("no route");
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .body(ResponseBody.create(GATE, MediaType.parse("text/html"))).build();
        }
    }

    /** A rendered page links to two more on its host, so a depth-1 crawl renders three. */
    private static FakeSidecars linkedSite() {
        var sidecars = new FakeSidecars();
        sidecars.pages = url -> url.endsWith("/") ? page("Home", "one", "two") : page("Leaf " + url);
        return sidecars;
    }

    private static ScrapeJobRequest job(String seed) {
        return new ScrapeJobRequest(URI.create(seed), 10, 1, 5, true, true, false, "en", ScrapeOutput.Request.MARKDOWN);
    }

    /** Records pages; stops the crawl or throws once {@code after} pages are in. */
    private static CrawlListener listener(int after, boolean stop, boolean fail) {
        var pages = new AtomicInteger();
        return new CrawlListener() {
            @Override public void page(Page page) {
                if (pages.incrementAndGet() >= after && fail) throw new IllegalStateException("the job's store failed");
            }

            @Override public void discovered(int urls) {}

            @Override public boolean stopRequested() {
                return stop && pages.get() >= after;
            }
        };
    }

    @Test
    void aCrawlRendersAHostInOneSessionAndClosesItWhenItEnds() {
        var sidecars = linkedSite();
        var out = new WebScrapeTool(sidecars).execute(
                "{\"url\": \"https://" + HOST + "/\", \"maxDepth\": 1, \"maxPages\": 5}", (Agent) null);

        assertTrue(out.startsWith("Scraped 3 pages"), out.substring(0, Math.min(200, out.length())));
        assertTrue(out.contains("_(via BROWSER)_"), out);
        assertEquals(List.of(HOST + " s1"), sidecars.opened);
        assertEquals(List.of("s1", "s1", "s1"), sidecars.sessionsUsed(), "every page of the host reused the session");
        assertEquals(List.of("s1"), sidecars.closed);
    }

    @Test
    void aBackgroundCrawlThatFailsClosesItsSession() {
        var sidecars = linkedSite();
        var request = job("https://" + HOST + "/");
        assertThrows(IllegalStateException.class, () -> new WebScrapeTool(sidecars).crawlForJob(request,
                listener(1, false, true), CrawlListener.Resume.fresh(Duration.ofMinutes(5))));
        assertEquals(List.of("s1"), sidecars.closed, "the failure left no session open");
    }

    @Test
    void aCancelledBackgroundCrawlClosesItsSession() {
        var sidecars = linkedSite();
        var request = job("https://" + HOST + "/");
        var crawl = new WebScrapeTool(sidecars).crawlForJob(request, listener(1, true, false),
                CrawlListener.Resume.fresh(Duration.ofMinutes(5)));

        assertEquals("stopped on request", crawl.stoppedBecause());
        assertEquals(1, sidecars.sessionsUsed().size(), "the crawl stopped after its first page");
        assertEquals(List.of("s1"), sidecars.closed);
    }

    @Test
    void twoCrawlsOfOneHostNeverShareASession() throws Exception {
        var sidecars = linkedSite();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new WebScrapeTool(sidecars).execute(
                    "{\"url\": \"https://" + HOST + "/\", \"maxDepth\": 1}", (Agent) null));
            var second = pool.submit(() -> new WebScrapeTool(sidecars).execute(
                    "{\"url\": \"https://" + HOST + "/other/\", \"maxDepth\": 1}", (Agent) null));
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        }
        var byCrawl = sidecars.renders.stream().collect(Collectors.groupingBy(
                r -> r.contains("/other/") ? "second" : "first",
                Collectors.mapping(r -> r.split(" ")[0], Collectors.toSet())));
        assertEquals(2, sidecars.opened.size(), sidecars.opened.toString());
        assertEquals(1, byCrawl.get("first").size(), byCrawl.toString());
        assertEquals(1, byCrawl.get("second").size(), byCrawl.toString());
        assertNotEquals(byCrawl.get("first"), byCrawl.get("second"), "each crawl renders only in its own session");
        assertEquals(Set.of("s1", "s2"), Set.copyOf(sidecars.closed));
    }

    @Test
    void aLoneWebFetchNeverOpensASession() {
        // web_fetch climbs with no handle, so each render launches and closes its own browser.
        noClasses().that().haveFullyQualifiedName(WebFetchTool.class.getName())
                .should().dependOnClassesThat().haveFullyQualifiedName(ScrapeSessions.class.getName())
                .check(ArchitectureTest.APP_CLASSES);
        noClasses().that().doNotHaveFullyQualifiedName(WebScrapeTool.class.getName())
                .and().doNotHaveFullyQualifiedName(ScrapeSessions.class.getName())
                .should().callConstructorWhere(ArchitectureTest.constructorCall(ScrapeSessions.class.getName(), false))
                .because("a session outlives a page only within the crawl that holds it")
                .check(ArchitectureTest.APP_CLASSES);
    }
}
