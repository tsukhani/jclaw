import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import tools.scrape.DataImpulsePlans;
import tools.scrape.ScrapeProxyCheck;
import tools.scrape.WebScrapeSettings;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * {@code POST /api/scrape/proxy/test} (JCLAW-1323): the saved proxy answers with the egress IP or
 * with its own refusal, nothing is dialed when there is no proxy, and an agent is refused.
 *
 * <p>The {@code web_scrape.proxy.*} keys are process-wide and read by other scraping classes, so
 * each test commits them only around its one request, under {@link ScrapeConfigGuard}.
 */
class ApiScrapeProxyControllerTest extends FunctionalTest {

    private static final String ROUTE = "/api/scrape/proxy/test";

    /** A public literal, so the target check passes without a DNS lookup; the stub proxy answers for it. */
    private static final String ECHO = "http://93.184.215.14/";

    private final ScrapeConfigGuard config = ScrapeConfigGuard.committing();
    private MockWebServer proxy;

    @BeforeEach
    void setup() throws Exception {
        AuthFixture.seedAdminPassword("changeme");
        proxy = new MockWebServer();
        proxy.start();
        ScrapeProxyCheck.setEchoUrlForTest(ECHO);
    }

    @AfterEach
    void teardown() {
        try {
            config.restore();
        } finally {
            ScrapeProxyCheck.setEchoUrlForTest(null);
            proxy.close();
            clearCookies();
        }
    }

    private void login() {
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    private String proxyUrl() {
        return "http://127.0.0.1:" + proxy.getPort();
    }

    /** Runs {@code call} with the proxy saved, restoring the keys as soon as it returns. */
    private <T> T withSavedProxy(String url, String enabled, String username, String password, Supplier<T> call) {
        try {
            config.set(WebScrapeSettings.PROXY_URL, url);
            config.set(WebScrapeSettings.PROXY_ENABLED, enabled);
            config.set(WebScrapeSettings.PROXY_USERNAME, username);
            config.set(WebScrapeSettings.PROXY_PASSWORD, password);
            return call.get();
        } finally {
            config.restore();
        }
    }

    private static JsonObject resultOf(Http.Response response) {
        assertIsOk(response);
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private JsonObject testConnection() {
        return resultOf(POST(ROUTE, "application/json", "{}"));
    }

    @Test
    void theRouteNeedsALogin() {
        clearCookies();
        assertEquals(401, POST(ROUTE, "application/json", "{}").status.intValue());
    }

    @Test
    void aWorkingProxyReportsTheEgressIp() throws Exception {
        proxy.enqueue(new MockResponse.Builder().code(200).body("203.0.113.7\n").build());
        login();

        var result = withSavedProxy(proxyUrl(), "true", "", "", this::testConnection);

        assertTrue(result.get("ok").getAsBoolean(), result.toString());
        assertEquals("203.0.113.7", result.get("ip").getAsString());
        assertTrue(result.get("ms").getAsLong() >= 0, result.toString());
        var host = result.getAsJsonObject("proxy");
        assertEquals("127.0.0.1", host.get("host").getAsString());
        assertEquals("127.0.0.1", host.get("address").getAsString());
        assertTrue(host.get("reverseName").isJsonNull(), result.toString());
        // An absolute request target is what a client sends only to a proxy.
        assertEquals(ECHO, proxy.takeRequest().getTarget());
    }

    @Test
    void theProvidersRefusalReachesThePanelWithItsReasonPhrase() throws Exception {
        proxy.enqueue(new MockResponse.Builder().code(407)
                .addHeader("Proxy-Authenticate", "Basic realm=\"proxy\"").build());
        proxy.enqueue(new MockResponse.Builder().status("HTTP/1.1 407 TRAFFIC_EXHAUSTED").build());
        login();

        var result = withSavedProxy(proxyUrl(), "true", "abc__cr.de", "s3cret", this::testConnection);

        assertFalse(result.get("ok").getAsBoolean(), result.toString());
        assertEquals(407, result.get("status").getAsInt(), result.toString());
        assertEquals("TRAFFIC_EXHAUSTED", result.get("reason").getAsString());
        assertFalse(result.toString().contains("s3cret"), result.toString());
        proxy.takeRequest();
        var expected = "Basic " + Base64.getEncoder()
                .encodeToString("abc__cr.de:s3cret".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(expected, proxy.takeRequest().getHeaders().get("Proxy-Authorization"));
    }

    @Test
    void aDataImpulseGatewayAuthenticatesWithTheActivePlan() throws Exception {
        proxy.enqueue(new MockResponse.Builder().code(407)
                .addHeader("Proxy-Authenticate", "Basic realm=\"proxy\"").build());
        proxy.enqueue(new MockResponse.Builder().code(200).body("203.0.113.7").build());
        login();
        JsonObject result;
        try {
            config.set(DataImpulsePlans.loginKey("residential"), "res");
            // After the first write, which takes the scrape config lock that other users of the override hold too.
            // The stub stands in for gw.dataimpulse.com, which the plans are keyed on.
            DataImpulsePlans.setGatewayHostForTest("127.0.0.1");
            config.set(DataImpulsePlans.passwordKey("residential"), "res-pass");
            config.set(DataImpulsePlans.loginKey("mobile"), "abc");
            config.set(DataImpulsePlans.passwordKey("mobile"), "mob-pass");
            config.set(WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING, "cr.de");
            config.set(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, "mobile");
            result = withSavedProxy(proxyUrl(), "true", "generic", "generic-pass", this::testConnection);
        } finally {
            DataImpulsePlans.setGatewayHostForTest(null);
        }

        assertTrue(result.get("ok").getAsBoolean(), result.toString());
        proxy.takeRequest();
        var expected = "Basic " + Base64.getEncoder()
                .encodeToString("abc__cr.de:mob-pass".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(expected, proxy.takeRequest().getHeaders().get("Proxy-Authorization"));
    }

    @Test
    void aProxyThatDoesNotAnswerIsReportedAsSuch() throws Exception {
        String closed;
        try (var gone = new MockWebServer()) {
            gone.start();
            closed = "http://127.0.0.1:" + gone.getPort();
        }
        login();

        var result = withSavedProxy(closed, "true", "", "", this::testConnection);

        assertFalse(result.get("ok").getAsBoolean(), result.toString());
        assertTrue(result.get("status").isJsonNull(), result.toString());
        assertTrue(result.get("error").getAsString().startsWith("Nothing answered through the proxy"), result.toString());
    }

    @Test
    void aNamedProxyThatDoesNotAnswerSaysWhereItsNameResolved() throws Exception {
        String closed;
        try (var gone = new MockWebServer()) {
            gone.start();
            closed = "http://localhost:" + gone.getPort();
        }
        login();

        var result = withSavedProxy(closed, "true", "", "", this::testConnection);

        assertFalse(result.get("ok").getAsBoolean(), result.toString());
        var host = result.getAsJsonObject("proxy");
        assertEquals("localhost", host.get("host").getAsString());
        assertTrue(InetAddress.ofLiteral(host.get("address").getAsString()).isLoopbackAddress(), result.toString());
        assertTrue(host.get("reverseName").getAsString().contains("localhost"), result.toString());
    }

    @Test
    void aProxyNameThatDoesNotResolveHasNoAddress() {
        login();

        var result = withSavedProxy("http://no-such-proxy.invalid:8080", "true", "", "", this::testConnection);

        assertFalse(result.get("ok").getAsBoolean(), result.toString());
        assertTrue(result.get("error").getAsString().startsWith("Nothing answered through the proxy"), result.toString());
        var host = result.getAsJsonObject("proxy");
        assertEquals("no-such-proxy.invalid", host.get("host").getAsString());
        assertTrue(host.get("address").isJsonNull(), result.toString());
        assertTrue(host.get("reverseName").isJsonNull(), result.toString());
    }

    @Test
    void anEchoThatIsNotAnAddressIsNotReportedAsTheEgressIp() {
        proxy.enqueue(new MockResponse.Builder().code(200).body("deadbeef").build());
        login();

        var result = withSavedProxy(proxyUrl(), "true", "", "", this::testConnection);

        assertFalse(result.get("ok").getAsBoolean(), result.toString());
        assertTrue(result.get("ip").isJsonNull(), result.toString());
        assertEquals(200, result.get("status").getAsInt());
        assertTrue(result.get("error").getAsString().contains("not an address"), result.toString());
    }

    @Test
    void aPrivateEchoTargetIsRefusedBeforeAnythingIsSent() {
        ScrapeProxyCheck.setEchoUrlForTest("http://10.0.0.1/");
        login();

        var result = withSavedProxy(proxyUrl(), "true", "", "", this::testConnection);

        assertFalse(result.get("ok").getAsBoolean(), result.toString());
        assertTrue(result.get("error").getAsString().startsWith("The check was refused before anything was sent"),
                result.toString());
        assertEquals(0, proxy.getRequestCount());
    }

    @Test
    void nothingIsDialedWithoutASavedProxy() {
        // Aimed at the stub itself, so a direct dial would show up in its request count.
        ScrapeProxyCheck.setEchoUrlForTest(proxyUrl() + "/");
        login();

        var result = withSavedProxy("", "true", "", "", this::testConnection);

        assertFalse(result.get("ok").getAsBoolean(), result.toString());
        assertTrue(result.get("error").getAsString().contains("No proxy configured"), result.toString());
        assertTrue(result.get("proxy").isJsonNull(), result.toString());
        assertEquals(0, proxy.getRequestCount());
    }

    @Test
    void nothingIsDialedWhileTheProxyIsSwitchedOff() {
        ScrapeProxyCheck.setEchoUrlForTest(proxyUrl() + "/");
        login();

        var result = withSavedProxy(proxyUrl(), "false", "", "", this::testConnection);

        assertTrue(result.get("error").getAsString().contains("No proxy configured"), result.toString());
        assertEquals(0, proxy.getRequestCount());
    }

    @Test
    void theAgentPrincipalIsRefused() {
        proxy.enqueue(new MockResponse.Builder().code(200).body("203.0.113.7").build());
        var request = newRequest();
        request.headers.put("authorization",
                new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));

        Http.Response response;
        try {
            response = withSavedProxy(proxyUrl(), "true", "", "",
                    () -> POST(request, ROUTE, "application/json", "{}"));
        } finally {
            // The bearer branch answers with the agent principal's session cookie, which play1's
            // JVM-global cookie jar would carry into a concurrent class's requests.
            clearCookies();
        }

        assertStatus(403, response);
        assertEquals(0, proxy.getRequestCount());
    }
}
