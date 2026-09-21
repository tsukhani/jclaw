import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.scrape.ScrapeProxy;
import utils.SsrfGuard;
import utils.WebExtraction;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * The operator's scrape proxy (JCLAW-1271): what it accepts, that the plain fetch really goes
 * through it, and that the SSRF check still runs on the target when the proxy does the lookup.
 *
 * <p>Built through {@link ScrapeProxy#parse} and {@link ScrapeProxy#apply} rather than the
 * {@code web_scrape.proxy.*} keys: those are process-wide, and play1 runs other scraping test
 * classes at the same time.
 */
class ScrapeProxyTest extends UnitTest {

    /** A public literal, so the target check passes without a DNS lookup. */
    private static final String TARGET = "http://93.184.215.14/page";

    private MockWebServer proxy;

    @BeforeEach
    void startProxy() throws Exception {
        proxy = new MockWebServer();
        proxy.start();
    }

    @AfterEach
    void stopProxy() {
        proxy.close();
    }

    private ScrapeProxy httpProxy(String username, String password) {
        return ScrapeProxy.parse("http://127.0.0.1:" + proxy.getPort(), "true", username, password).orElseThrow();
    }

    private static MockResponse html(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "text/html").body(body).build();
    }

    @Test
    void thePlainFetchGoesThroughTheProxy() throws Exception {
        proxy.enqueue(html(200, "<html><body><p>through the proxy</p></body></html>"));
        var client = httpProxy("", "").apply(SsrfGuard.buildGuardedClient(5, 10));

        var fetched = WebExtraction.fetch(TARGET, client, Map.of());

        assertTrue(new String(fetched.body(), StandardCharsets.UTF_8).contains("through the proxy"));
        // An absolute request target is what a client sends only to a proxy.
        assertEquals(TARGET, proxy.takeRequest().getTarget());
    }

    @Test
    void theProxyIsAnsweredWithTheCredentialsWhenItAsks() throws Exception {
        proxy.enqueue(new MockResponse.Builder().code(407)
                .addHeader("Proxy-Authenticate", "Basic realm=\"scrape\"").build());
        proxy.enqueue(html(200, "<html><body><p>authenticated</p></body></html>"));
        var client = httpProxy("scraper", "s3cret").apply(SsrfGuard.buildGuardedClient(5, 10));

        WebExtraction.fetch(TARGET, client, Map.of());

        proxy.takeRequest();
        var expected = "Basic " + Base64.getEncoder()
                .encodeToString("scraper:s3cret".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(expected, proxy.takeRequest().getHeaders().get("Proxy-Authorization"));
    }

    @Test
    void aPrivateTargetIsRefusedBeforeItReachesTheProxy() {
        var client = httpProxy("", "").apply(SsrfGuard.buildGuardedClient(5, 10));

        assertThrows(SecurityException.class, () -> WebExtraction.fetch("http://10.0.0.1/", client, Map.of()));
        // The proxy would have fetched it for us: the check has to happen before the request leaves.
        assertEquals(0, proxy.getRequestCount());
    }

    @Test
    void aTargetThatDoesNotResolveIsRefusedNotHandedToTheProxy() {
        var client = httpProxy("", "").apply(SsrfGuard.buildGuardedClient(5, 10));

        assertThrows(SecurityException.class,
                () -> WebExtraction.fetch("http://no-such-host.invalid/", client, Map.of()));
        assertEquals(0, proxy.getRequestCount());
    }

    @Test
    void noProxyIsConfiguredWhenTheUrlIsEmptyOrTheProxyIsOff() {
        assertTrue(ScrapeProxy.parse("", "true", "", "").isEmpty());
        assertTrue(ScrapeProxy.parse("http://proxy.example:8080", "false", "", "").isEmpty());
    }

    @Test
    void aStoredUrlTheApiWouldHaveRefusedFailsInsteadOfScrapingDirect() {
        assertThrows(IllegalStateException.class, () -> ScrapeProxy.parse("ftp://proxy.example:21", "true", "", ""));
    }

    @Test
    void theProxyUrlTakesAHostAndPortAndNothingElse() {
        for (var ok : new String[]{"", "http://proxy.example:8080", "socks5://127.0.0.1:1080",
                "http://10.0.0.2:3128", "http://[::1]:8080"}) {
            assertNull(ScrapeProxy.urlRejection(ok, false), ok);
        }
        for (var bad : new String[]{"ftp://proxy.example:21", "https://proxy.example:443",
                "http://proxy.example", "http://user:pw@proxy.example:8080", "http://proxy.example:8080/path",
                "http://169.254.169.254:80", "http://0.0.0.0:8080", "http://224.0.0.1:8080",
                "http://[fe80::1]:8080", "not a url"}) {
            assertNotNull(ScrapeProxy.urlRejection(bad, false), bad);
        }
    }

    @Test
    void credentialsAreRefusedForSocksFromEitherSide() {
        assertNotNull(ScrapeProxy.urlRejection("socks5://proxy.example:1080", true));
        assertNotNull(ScrapeProxy.credentialRejection("scraper", "socks5://proxy.example:1080"));
        assertNull(ScrapeProxy.credentialRejection("scraper", "http://proxy.example:8080"));
        assertNotNull(ScrapeProxy.credentialRejection("line\nbreak", "http://proxy.example:8080"));
    }

    @Test
    void thePasswordNeverAppearsInTheProxysStringForm() {
        var configured = ScrapeProxy.parse("http://proxy.example:8080", "true", "scraper", "s3cret").orElseThrow();

        assertFalse(configured.toString().contains("s3cret"), configured.toString());
        assertEquals("s3cret", configured.toJson().get("password").getAsString());
    }
}
