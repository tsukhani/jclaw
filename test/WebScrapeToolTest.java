import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.ScrapeJob;
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
import services.AgentService;
import services.ConfigService;
import services.Tx;
import services.scrape.ScrapeJobService;
import tools.WebScrapeTool;
import tools.scrape.WebScrapeSettings;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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

    /** A markdown body long enough not to read as an empty page, carrying one link. */
    private static String markdownPage(String title, String href) {
        return "# " + title + "\n\n"
                + "This page is about widgets and how they combine. ".repeat(12)
                + "\n\n[next](" + href + ")\n";
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

    /** JCLAW-1287: the characters browsers send raw after the host reach a crawl from the page,
     *  not from the caller, so parsing them strictly cost the link rather than reporting anything. */
    @Test
    void aLinkCarryingACharacterBrowsersSendRawIsHarvestedRatherThanDropped() {
        routes.put("https://site.test/", page("Home", "/x?family=Roboto|Open+Sans"));
        routes.put("https://site.test/x?family=Roboto%7COpen+Sans", page("Found"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1}");

        assertTrue(out.contains("# Found"), "the link is followed, not silently dropped: " + out);
        assertEquals(2, routes.pageHits().size(), routes.pageHits().toString());
    }

    @Test
    void aMarkdownPagesLinkCarriesTheSameCharactersThroughTheSameParse() {
        // The markdown lane resolves each link against the page's own final URL rather than through
        // jsoup, so it is a second strict parse of the same page-supplied string.
        routes.put("https://site.test/", markdownPage("Home", "/x?a=b|c"), "text/markdown; charset=utf-8");
        routes.put("https://site.test/x?a=b%7Cc", page("Found"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1}");

        assertTrue(out.contains("# Found"), "the markdown link is followed: " + out);
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
        // Written from the crawl's fetch workers, several at once.
        final List<String> hits = new CopyOnWriteArrayList<>();
        private final Map<String, Hold> holds = new ConcurrentHashMap<>();

        /** A request held on arrival until the test releases it. */
        record Hold(CountDownLatch arrived, CountDownLatch release) {}

        Hold hold(String url) {
            var hold = new Hold(new CountDownLatch(1), new CountDownLatch(1));
            holds.put(url, hold);
            return hold;
        }

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
            var hold = holds.remove(url);
            if (hold != null) {
                hold.arrived().countDown();
                try {
                    hold.release().await(4, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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

    // Output formats, extraction and saving (JCLAW-1271)

    private static String errorCode(ToolRegistry.ToolResult result) {
        return JsonParser.parseString(Objects.requireNonNull(result.structuredJson()))
                .getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    @Test
    void jsonFormatIsOneObjectWithARecordPerPage() {
        routes.put("https://site.test/", page("Home", "/a"));
        routes.put("https://site.test/a", page("A"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":1,\"format\":\"json\"}");

        var root = JsonParser.parseString(out).getAsJsonObject();
        assertTrue(root.get("summary").getAsString().startsWith("Scraped 2 pages"), out);
        var pages = root.getAsJsonArray("pages");
        assertEquals(2, pages.size());
        assertEquals("https://site.test/", pages.get(0).getAsJsonObject().get("url").getAsString());
        assertTrue(pages.get(1).getAsJsonObject().get("content").getAsString().contains("# A"), out);
    }

    @Test
    void extractCollectsTheFieldsFromEveryPageWithoutTheirContent() {
        routes.put("https://site.test/", page("Home", "/a"));
        routes.put("https://site.test/a", page("A"));
        var out = scrape("""
                {"url": "https://site.test/", "maxDepth": 1, "extract": {"title": "title"}}""");

        var pages = JsonParser.parseString(out).getAsJsonObject().getAsJsonArray("pages");
        assertEquals("[\"Home\"]", pages.get(0).getAsJsonObject().getAsJsonObject("fields").get("title").toString());
        assertEquals("[\"A\"]", pages.get(1).getAsJsonObject().getAsJsonObject("fields").get("title").toString());
        assertFalse(out.contains("widgets and how they combine"), "extract replaces the page content: " + out);
    }

    @Test
    void textFormatReadsWithoutMarkup() {
        routes.put("https://site.test/", page("Home"));
        var out = scrape("{\"url\":\"https://site.test/\",\"maxDepth\":0,\"format\":\"text\"}");

        assertTrue(out.contains("=== https://site.test/ ==="), out);
        assertFalse(out.contains("# Home"), "no Markdown heading: " + out);
        assertTrue(out.contains("widgets and how they combine"), out);
    }

    @Test
    void aBadArgumentIsRefusedBeforeTheCrawlStarts() {
        routes.put("https://site.test/", page("Home"));
        var result = new WebScrapeTool().executeRich(
                "{\"url\":\"https://site.test/\",\"format\":\"html\"}", (Agent) null);

        assertEquals("web_bad_argument", errorCode(result));
        assertTrue(routes.pageHits().isEmpty(), "nothing may be fetched for a refused call");
    }

    @Test
    void saveWritesTheCrawlToTheWorkspaceAndReturnsOnlyTheSummary() throws Exception {
        routes.put("https://site.test/", page("Home", "/a"));
        routes.put("https://site.test/a", page("A"));
        var name = "scrapesave" + (System.nanoTime() % 1_000_000);
        var agent = AgentService.create(name, "openrouter", "gpt-4.1");
        try {
            var out = new WebScrapeTool().execute("""
                    {"url": "https://site.test/", "maxDepth": 1, "format": "json", "save": true}""", agent);

            var saved = Pattern.compile("workspace file '([^']+\\.jsonl)'").matcher(out);
            assertTrue(saved.find(), out);
            assertFalse(out.contains("widgets and how they combine"), "the content goes to the file: " + out);
            var lines = Files.readAllLines(AgentService.workspacePath(name).resolve(saved.group(1)));
            assertEquals(2, lines.size(), "one JSON record per page");
            assertEquals("https://site.test/a",
                    JsonParser.parseString(lines.get(1)).getAsJsonObject().get("url").getAsString());
        } finally {
            AgentService.delete(agent);
            deleteTree(AgentService.workspacePath(name));
        }
    }

    // Background jobs (JCLAW-1272)

    @Test
    void backgroundAnswersWithinTheCallAndTheCrawlRunsAfterIt() throws Exception {
        routes.put("https://site.test/", page("Home", "/a"));
        routes.put("https://site.test/a", page("A"));
        var name = "scrapebg" + (System.nanoTime() % 1_000_000);
        var agent = onFreshThread(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
        try {
            // Off the test's own transaction, as a tool call runs: the job starts once its row commits.
            var result = onFreshThread(() -> new WebScrapeTool().executeRich(
                    "{\"url\": \"https://site.test/\", \"maxDepth\": 1, \"background\": true}", agent));

            assertTrue(result.text().startsWith("Started background scrape job"), result.text());
            var id = JsonParser.parseString(Objects.requireNonNull(result.structuredJson())).getAsJsonObject()
                    .getAsJsonObject("scrapeJob").get("id").getAsLong();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            ScrapeJob.State state;
            do {
                Thread.sleep(25);
                state = onFreshThread(() -> ((ScrapeJob) ScrapeJob.findById(id)).state);
            } while (!state.terminal() && System.nanoTime() < deadline);

            assertEquals(ScrapeJob.State.SUCCEEDED, state);
            assertEquals(2, (int) onFreshThread(() -> ((ScrapeJob) ScrapeJob.findById(id)).pagesFetched));
            var folder = AgentService.workspacePath(name).resolve("scrapes/" + id);
            assertTrue(Files.readString(folder.resolve("0002.md")).contains("# A"));
            assertTrue(Files.readString(folder.resolve("combined.md")).contains("## https://site.test/a"));
        } finally {
            onFreshThread(() -> {
                AgentService.delete(Agent.findById(agent.id));
                return null;
            });
            deleteTree(AgentService.workspacePath(name));
        }
    }

    @Test
    void backgroundRefusesABadArgumentAsAnInlineCallDoesAndStartsNothing() {
        var name = "scrapebgbad" + (System.nanoTime() % 1_000_000);
        var agent = onFreshThread(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
        try {
            var badSelector = onFreshThread(() -> new WebScrapeTool().executeRich(
                    "{\"url\": \"https://site.test/\", \"background\": true, \"extract\": {\"x\": \"div[[\"}}", agent));
            var badMinutes = onFreshThread(() -> new WebScrapeTool().executeRich(
                    "{\"url\": \"https://site.test/\", \"background\": true, \"maxMinutes\": \"soon\"}", agent));

            assertEquals("web_bad_argument", errorCode(badSelector));
            assertEquals("web_bad_argument", errorCode(badMinutes));
            assertTrue(badMinutes.text().contains("maxMinutes"), badMinutes.text());
            assertEquals(0L, (long) onFreshThread(() -> ScrapeJob.count("agent.id = ?1", agent.id)));
            assertTrue(routes.pageHits().isEmpty(), "nothing may be fetched for a refused call");
        } finally {
            onFreshThread(() -> {
                AgentService.delete(Agent.findById(agent.id));
                return null;
            });
        }
    }

    @Test
    void aPausedBackgroundCrawlResumesWithoutReadingAPageTwice() throws Exception {
        var held = twoLevelSite();
        var name = "scrapebgpause" + (System.nanoTime() % 1_000_000);
        var agent = onFreshThread(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
        try {
            var id = startBackground(agent);
            assertTrue(held.arrived().await(10, TimeUnit.SECONDS), "the crawl reaches the held page");
            assertEquals(ScrapeJobService.PauseResult.PAUSING, onFreshThread(() -> ScrapeJobService.pause(id)));
            held.release().countDown();

            assertEquals(ScrapeJob.State.PAUSED, awaitJob(id, ScrapeJob.State.PAUSED));
            int beforeResume = onFreshThread(() -> ((ScrapeJob) ScrapeJob.findById(id)).pagesRead);
            assertTrue(beforeResume < 13, "the pause stopped it partway, after " + beforeResume + " pages");

            assertEquals(ScrapeJobService.ResumeResult.RESUMED, onFreshThread(() -> ScrapeJobService.resume(id)));
            assertEquals(ScrapeJob.State.SUCCEEDED, awaitJob(id, ScrapeJob.State.SUCCEEDED));
            assertEveryPageReadOnce(id);
        } finally {
            onFreshThread(() -> {
                AgentService.delete(Agent.findById(agent.id));
                return null;
            });
            deleteTree(AgentService.workspacePath(name));
        }
    }

    @Test
    void aBackgroundCrawlLeftRunningByAStoppedAppContinuesWithoutReadingAPageTwice() throws Exception {
        var held = twoLevelSite();
        var name = "scrapebgrestart" + (System.nanoTime() % 1_000_000);
        var agent = onFreshThread(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
        try {
            var id = startBackground(agent);
            assertTrue(held.arrived().await(10, TimeUnit.SECONDS), "the crawl reaches the held page");
            onFreshThread(() -> ScrapeJobService.pause(id));
            held.release().countDown();
            awaitJob(id, ScrapeJob.State.PAUSED);
            // What a stopped app leaves behind: the row says RUNNING and no thread is running it.
            onFreshThread(() -> {
                ScrapeJob job = ScrapeJob.findById(id);
                job.state = ScrapeJob.State.RUNNING;
                job.save();
                return null;
            });

            onFreshThread(() -> {
                ScrapeJobService.tickOnce();
                return null;
            });
            assertEquals(ScrapeJob.State.SUCCEEDED, awaitJob(id, ScrapeJob.State.SUCCEEDED));
            assertEveryPageReadOnce(id);
        } finally {
            onFreshThread(() -> {
                AgentService.delete(Agent.findById(agent.id));
                return null;
            });
            deleteTree(AgentService.workspacePath(name));
        }
    }

    /** A seed linking six pages, each linking one more: thirteen pages over two levels, one of them held. */
    private RouteInterceptor.Hold twoLevelSite() {
        var hrefs = new String[6];
        for (int i = 1; i <= 6; i++) {
            hrefs[i - 1] = "/p" + i;
            routes.put("https://site.test/p" + i, page("P" + i, "/q" + i));
            routes.put("https://site.test/q" + i, page("Q" + i));
        }
        routes.put("https://site.test/", page("Home", hrefs));
        return routes.hold("https://site.test/p3");
    }

    private static Long startBackground(Agent agent) {
        var result = onFreshThread(() -> new WebScrapeTool().executeRich(
                "{\"url\": \"https://site.test/\", \"maxDepth\": 2, \"background\": true}", agent));
        return JsonParser.parseString(Objects.requireNonNull(result.structuredJson(), result.text())).getAsJsonObject()
                .getAsJsonObject("scrapeJob").get("id").getAsLong();
    }

    private static ScrapeJob.State awaitJob(Long id, ScrapeJob.State wanted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        ScrapeJob.State state;
        do {
            Thread.sleep(25);
            state = onFreshThread(() -> ((ScrapeJob) ScrapeJob.findById(id)).state);
        } while (state != wanted && !state.terminal() && System.nanoTime() < deadline);
        return state;
    }

    /** Every page of the site recorded once and requested once, across both runs. */
    private void assertEveryPageReadOnce(Long id) {
        List<String> requested = onFreshThread(() -> {
            List<models.ScrapeJobPage> rows = models.ScrapeJobPage.find("job.id = ?1", id).fetch();
            return rows.stream().map(p -> p.requestedUrl).toList();
        });
        assertEquals(13, requested.size(), requested.toString());
        assertEquals(13, requested.stream().distinct().count(), "no page is recorded twice: " + requested);
        for (var url : requested) {
            assertEquals(1, routes.pageHits().stream().filter(url::equals).count(), url + " was fetched once");
        }
    }

    private static <T> T onFreshThread(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
