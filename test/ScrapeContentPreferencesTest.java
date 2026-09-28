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

/**
 * What the crawler asks for and what it keeps — markdown preference (JCLAW-1101) and
 * locale de-duplication (JCLAW-1100).
 *
 * <p>Own host, like the sibling scrape suites: {@link RobotsCache} is a process-global
 * cache and play1 runs test classes concurrently.
 */
class ScrapeContentPreferencesTest extends UnitTest {

    private static final String HOST = "https://prefs.test";
    private static final String CFG_LANGUAGE = "web_scrape.language";

    private final ScrapeClientSwap swap = new ScrapeClientSwap();
    private final ScrapeConfigGuard config = new ScrapeConfigGuard();
    private RouteInterceptor routes;

    @BeforeEach
    void setup() throws Exception {
        routes = new RouteInterceptor();
        swap.install(new OkHttpClient.Builder()
                .addInterceptor(routes).callTimeout(5, TimeUnit.SECONDS).build());
        RobotsCache.resetForTest();
        routes.put(HOST + "/robots.txt", "User-agent: *\nAllow: /\n", "text/plain");
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

    /** Bounded prefix for an assertion message: a crawl that returned nothing at all is
     *  exactly when the message is wanted, and substring(0, 400) throws there. */
    private static String head(String out) {
        return out.substring(0, Math.min(400, out.length()));
    }

    private static String body(String... paragraphs) {
        return String.join("\n\n", paragraphs) + "\n\n"
                + "Body text long enough to clear the readability floor. ".repeat(12);
    }

    private String scrape(String json) {
        return new WebScrapeTool().execute(json, (Agent) null);
    }

    // ==================== JCLAW-1101: markdown ====================

    @Test
    void markdownIsPreferredInTheRequest() {
        routes.put(HOST + "/", "<html><head><title>H</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p></article></body></html>");
        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}");

        var accept = routes.acceptFor(HOST + "/");
        assertNotNull(accept, "no Accept header was sent");
        assertTrue(accept.startsWith("text/markdown"),
                "markdown must be preferred over HTML, got: " + accept);
        assertTrue(accept.contains("text/html"),
                "HTML must stay acceptable — most sites serve nothing else: " + accept);
    }

    @Test
    void markdownIsUsedVerbatimRatherThanRunThroughReadability() {
        // The point of the whole story: when the origin hands us the target format, the
        // HTML reconstruction is skipped rather than applied to markdown.
        //
        // Asserted on the whole source, and with a raw tag in it. "**bold**" is text on
        // the HTML path too, so it cannot tell the branches apart; a literal <span>
        // survives only where nothing parsed the body as markup, and any Readability,
        // html2md or Tika pass would also re-space what surrounds it.
        var source = body("# Real Heading", "Some **bold** prose.",
                "<span data-keep=\"1\">raw markup kept</span>");
        routes.put(HOST + "/", source, "text/markdown");
        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}");
        assertTrue(out.contains(source),
                "markdown must reach the caller byte for byte: " + head(out));
    }

    @Test
    void markdownIsVerbatimUnderTheApplicationTypeToo() {
        // application/markdown routed to the markdown link parser but fell through to
        // Tika for the text — one response with two different opinions about what it is.
        var source = body("# Real Heading", "Some **bold** prose.");
        routes.put(HOST + "/", source, "application/markdown");
        assertTrue(scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}").contains(source),
                "the application/ spelling must take the same path as text/markdown");
    }

    @Test
    void linksAreHarvestedFromMarkdownSoCrawlingStillWorks() {
        // Without markdown link parsing, preferring markdown would silently reduce every
        // crawl to its seed page. The seed's own body contains the string "/guide", so
        // what is asserted is that the link was FOLLOWED: the request, and content only
        // the linked page carries.
        routes.put(HOST + "/", body("# Index", "See [the guide](/guide) for more."), "text/markdown");
        routes.put(HOST + "/guide", body("# Guide", "Deployment on Tuesdays."), "text/markdown");

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");
        assertTrue(routes.hits.contains(HOST + "/guide"),
                "a markdown link must be followed: " + routes.hits);
        assertTrue(out.contains("Deployment on Tuesdays."),
                "the linked page's own content must be in the result: " + head(out));
    }

    @Test
    void anOriginThatIgnoresTheHeaderStillWorks() {
        // Content negotiation is advisory; a server returning HTML anyway must take the
        // ordinary path unchanged.
        routes.put(HOST + "/", "<html><head><title>Plain</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p></article></body></html>");
        assertTrue(scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}").contains("# Plain"));
    }

    @Test
    void theRequestStatesALanguagePreferenceWithoutDemandingIt() {
        routes.put(HOST + "/", "<html><head><title>H</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p></article></body></html>");
        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0,\"language\":\"ja\"}");

        var lang = routes.languageFor(HOST + "/");
        assertNotNull(lang, "no Accept-Language header was sent");
        assertTrue(lang.startsWith("ja"), "the requested language must lead: " + lang);
        // A bare "Accept-Language: ja" invites a 406 or an empty body from a site with no
        // Japanese. A language preference must never cost us the page.
        assertTrue(lang.contains("*"), "a wildcard fallback must remain acceptable: " + lang);
    }

    @Test
    void theLanguagePreferenceDefaultsToEnglish() {
        // The key is DELETED first: while a value sits in global config this asserts
        // whatever wrote it, not the compiled-in default.
        config.delete(CFG_LANGUAGE);
        routes.put(HOST + "/", "<html><head><title>H</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p></article></body></html>");
        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}");
        assertTrue(routes.languageFor(HOST + "/").startsWith("en"),
                "English by default: " + routes.languageFor(HOST + "/"));
    }

    @Test
    void aBlankConfiguredLanguageFallsBackRatherThanAskingForNothing() {
        // An operator clearing the field leaves "", which reached the header as
        // "Accept-Language: , *;q=0.5".
        config.set(CFG_LANGUAGE, "   ");
        routes.put(HOST + "/", "<html><head><title>H</title></head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p></article></body></html>");
        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":0}");
        assertTrue(routes.languageFor(HOST + "/").startsWith("en"),
                "a blank preference must fall back: " + routes.languageFor(HOST + "/"));
    }

    // ==================== JCLAW-1100: locale variants ====================

    private static String withAlternates(String title, String... langToPath) {
        var links = new StringBuilder();
        for (int i = 0; i < langToPath.length; i += 2) {
            links.append("<link rel=\"alternate\" hreflang=\"").append(langToPath[i])
                 .append("\" href=\"").append(HOST).append(langToPath[i + 1]).append("\"/>");
        }
        return "<html><head><title>" + title + "</title>" + links
                + "</head><body><article><p>"
                + "Body text long enough to clear the readability floor. ".repeat(12)
                + "</p><a href=\"" + HOST + "/de\">de</a><a href=\"" + HOST + "/ja\">ja</a>"
                + "<a href=\"" + HOST + "/fr\">fr</a>"
                + "</article></body></html>";
    }

    @Test
    void translationsOfAPageDoNotSpendThePageBudget() {
        routes.put(HOST + "/", withAlternates("Home", "en", "/", "de", "/de", "ja", "/ja"));
        routes.put(HOST + "/de", withAlternates("Startseite", "en", "/", "de", "/de"));
        routes.put(HOST + "/ja", withAlternates("Home JA", "en", "/", "ja", "/ja"));

        var out = scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");
        assertFalse(routes.hits.contains(HOST + "/de"), "a declared translation must not be fetched");
        assertFalse(routes.hits.contains(HOST + "/ja"), "a declared translation must not be fetched");
        assertTrue(out.contains("Dropped 2 locale variants"),
                "the caller must be told what was dropped: " + out.substring(0, Math.min(400, out.length())));
    }

    @Test
    void theLanguageArgumentSelectsWhichTranslationIsKept() {
        routes.put(HOST + "/", withAlternates("Home", "en", "/", "de", "/de"));
        routes.put(HOST + "/de", withAlternates("Startseite", "en", "/", "de", "/de"));

        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1,\"language\":\"de\"}");
        assertTrue(routes.hits.contains(HOST + "/de"),
                "asking for German must keep the German variant: " + routes.hits);
    }

    @Test
    void aSiteWithNoPreferredVariantKeepsOneRatherThanNone() {
        // A language filter that empties the frontier is worse than no filter.
        routes.put(HOST + "/", withAlternates("Startseite", "de", "/de", "fr", "/fr"));
        routes.put(HOST + "/de", withAlternates("DE", "de", "/de"));
        routes.put(HOST + "/fr", withAlternates("FR", "fr", "/fr"));

        scrape("{\"url\":\"" + HOST + "/\",\"maxDepth\":1}");
        long kept = routes.hits.stream().filter(u -> u.endsWith("/de") || u.endsWith("/fr")).count();
        assertEquals(1, kept, "exactly one variant survives when none matches: " + routes.hits);
    }

    // ==================== Helper ====================

    static final class RouteInterceptor implements Interceptor {
        private final Map<String, String> bodies = new HashMap<>();
        private final Map<String, String> types = new HashMap<>();
        private final Map<String, String> accepts = new HashMap<>();
        private final Map<String, String> languages = new HashMap<>();
        final List<String> hits = new ArrayList<>();

        void put(String url, String b) { put(url, b, "text/html; charset=utf-8"); }

        void put(String url, String b, String contentType) {
            bodies.put(url, b);
            types.put(url, contentType);
        }

        String acceptFor(String url) { return accepts.get(url); }

        String languageFor(String url) { return languages.get(url); }

        @Override
        public Response intercept(Chain chain) throws IOException {
            var url = chain.request().url().toString();
            hits.add(url);
            accepts.put(url, chain.request().header("Accept"));
            languages.put(url, chain.request().header("Accept-Language"));
            var b = bodies.get(url);
            if (b == null) throw new IOException("no route for " + url);
            var type = types.get(url);
            return new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .addHeader("Content-Type", type)
                    .body(ResponseBody.create(b, MediaType.parse(type)))
                    .build();
        }
    }
}
