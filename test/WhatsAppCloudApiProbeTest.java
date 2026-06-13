import channels.WhatsAppCloudApiProbe;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * HTTP-level coverage for {@link WhatsAppCloudApiProbe} (JCLAW-445). The probe
 * GETs {@code graph.facebook.com/v21.0/{phoneNumberId}} with a Bearer token; here
 * that traffic is redirected to a {@link MockWebServer} via the {@code apiBase}
 * overload so we assert the {@link WhatsAppCloudApiProbe.Verified} /
 * {@link WhatsAppCloudApiProbe.Failed} mapping without touching Meta.
 */
class WhatsAppCloudApiProbeTest extends UnitTest {

    private MockWebServer server;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // Trailing slash so the probe concatenates "{id}?fields=..." onto it.
        base = server.url("/v21.0/").toString();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void verifiedNumberReturnsVerifiedWithNameAndDisplayNumber() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"verified_name\":\"Acme Corp\","
                        + "\"code_verification_status\":\"VERIFIED\","
                        + "\"display_phone_number\":\"+1 555-0100\","
                        + "\"id\":\"123456\"}")
                .build());

        var result = WhatsAppCloudApiProbe.probe("123456", "tok-good", base);

        assertTrue(result instanceof WhatsAppCloudApiProbe.Verified,
                "200 with a verified_name → Verified, got: " + result);
        var v = (WhatsAppCloudApiProbe.Verified) result;
        assertEquals("Acme Corp", v.verifiedName());
        assertEquals("+1 555-0100", v.displayNumber());

        var recorded = server.takeRequest();
        assertEquals("Bearer tok-good", recorded.getHeaders().get("Authorization"),
                "probe must attach the Bearer token");
        assertTrue(recorded.getTarget().contains("123456"),
                "probe must GET the phone-number-id path: " + recorded.getTarget());
        assertTrue(recorded.getTarget().contains("verified_name"),
                "probe must request the verified_name field: " + recorded.getTarget());
    }

    @Test
    void unverifiedNumberWithoutVerifiedNameIsFailed() {
        // 200 but no verified_name → the number exists but isn't a verified WABA number.
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"display_phone_number\":\"+1 555-0100\",\"id\":\"123456\"}")
                .build());

        var result = WhatsAppCloudApiProbe.probe("123456", "tok", base);

        assertTrue(result instanceof WhatsAppCloudApiProbe.Failed,
                "200 without verified_name → Failed, got: " + result);
        assertTrue(((WhatsAppCloudApiProbe.Failed) result).reason().contains("verified"),
                "reason should mention the missing verification");
    }

    @Test
    void badTokenSurfacesGraphErrorMessage() {
        server.enqueue(new MockResponse.Builder()
                .code(401)
                .body("{\"error\":{\"message\":\"Invalid OAuth access token\","
                        + "\"type\":\"OAuthException\",\"code\":190}}")
                .build());

        var result = WhatsAppCloudApiProbe.probe("123456", "tok-bad", base);

        assertTrue(result instanceof WhatsAppCloudApiProbe.Failed,
                "4xx → Failed, got: " + result);
        assertEquals("Invalid OAuth access token",
                ((WhatsAppCloudApiProbe.Failed) result).reason(),
                "Failed must surface the Graph error message verbatim");
    }

    @Test
    void blankCredentialsFailWithoutHttp() {
        assertTrue(WhatsAppCloudApiProbe.probe(null, "tok", base)
                instanceof WhatsAppCloudApiProbe.Failed);
        assertTrue(WhatsAppCloudApiProbe.probe("123", "", base)
                instanceof WhatsAppCloudApiProbe.Failed);
        assertEquals(0, server.getRequestCount(),
                "blank credentials must short-circuit before any HTTP call");
    }
}
