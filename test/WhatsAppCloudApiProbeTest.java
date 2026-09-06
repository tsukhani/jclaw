import channels.WhatsAppCloudApiProbe;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.HttpFactories;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * HTTP-level coverage for {@link WhatsAppCloudApiProbe} (JCLAW-445). The probe
 * GETs {@code graph.facebook.com/v21.0/{phoneNumberId}} with a Bearer token;
 * here a canned-response transport installed through
 * {@link HttpFactories#callWith} answers it, so the
 * {@link WhatsAppCloudApiProbe.Verified} / {@link WhatsAppCloudApiProbe.Failed}
 * mapping is asserted without binding a port and without reaching Meta.
 *
 * <p>An OkHttp application interceptor short-circuits ahead of DNS, so
 * {@link #BASE} names a {@code .invalid} host: a hole in the seam shows up as a
 * probe failure rather than as a silent live request.
 *
 * <p>The three-argument overload is deliberate — the two-argument entry consults
 * {@code WhatsAppCloudApiProbe}'s static test override, which
 * {@code ApiWhatsAppBindingsControllerTest} installs while this class may be
 * running beside it.
 */
class WhatsAppCloudApiProbeTest extends UnitTest {

    private static final String BASE = "http://graph.invalid/v21.0/";

    private final AtomicReference<Request> recorded = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void verifiedNumberReturnsVerifiedWithNameAndDisplayNumber() {
        var result = withCannedResponse(200,
                "{\"verified_name\":\"Acme Corp\","
                        + "\"code_verification_status\":\"VERIFIED\","
                        + "\"display_phone_number\":\"+1 555-0100\","
                        + "\"id\":\"123456\"}",
                () -> WhatsAppCloudApiProbe.probe("123456", "tok-good", BASE));

        assertTrue(result instanceof WhatsAppCloudApiProbe.Verified,
                "200 with a verified_name → Verified, got: " + result);
        var v = (WhatsAppCloudApiProbe.Verified) result;
        assertEquals("Acme Corp", v.verifiedName());
        assertEquals("+1 555-0100", v.displayNumber());

        var request = recorded.get();
        assertEquals("Bearer tok-good", request.header("Authorization"),
                "probe must attach the Bearer token");
        assertTrue(request.url().toString().contains("123456"),
                "probe must GET the phone-number-id path: " + request.url());
        assertTrue(request.url().toString().contains("verified_name"),
                "probe must request the verified_name field: " + request.url());
    }

    @Test
    void unverifiedNumberWithoutVerifiedNameIsFailed() {
        // 200 but no verified_name → the number exists but isn't a verified WABA number.
        var result = withCannedResponse(200,
                "{\"display_phone_number\":\"+1 555-0100\",\"id\":\"123456\"}",
                () -> WhatsAppCloudApiProbe.probe("123456", "tok", BASE));

        assertTrue(result instanceof WhatsAppCloudApiProbe.Failed,
                "200 without verified_name → Failed, got: " + result);
        assertTrue(((WhatsAppCloudApiProbe.Failed) result).reason().contains("verified"),
                "reason should mention the missing verification");
    }

    @Test
    void badTokenSurfacesGraphErrorMessage() {
        var result = withCannedResponse(401,
                "{\"error\":{\"message\":\"Invalid OAuth access token\","
                        + "\"type\":\"OAuthException\",\"code\":190}}",
                () -> WhatsAppCloudApiProbe.probe("123456", "tok-bad", BASE));

        assertTrue(result instanceof WhatsAppCloudApiProbe.Failed,
                "4xx → Failed, got: " + result);
        assertEquals("Invalid OAuth access token",
                ((WhatsAppCloudApiProbe.Failed) result).reason(),
                "Failed must surface the Graph error message verbatim");
    }

    @Test
    void blankCredentialsFailWithoutHttp() {
        withCannedResponse(200, "{}", () -> {
            assertTrue(WhatsAppCloudApiProbe.probe(null, "tok", BASE)
                    instanceof WhatsAppCloudApiProbe.Failed);
            assertTrue(WhatsAppCloudApiProbe.probe("123", "", BASE)
                    instanceof WhatsAppCloudApiProbe.Failed);
            return null;
        });

        assertEquals(0, calls.get(),
                "blank credentials must short-circuit before any HTTP call");
    }

    /**
     * Run {@code probe} with every {@link HttpFactories} accessor bound to a
     * transport that records each request and answers it with {@code code} and
     * {@code responseBody}.
     */
    private <T> T withCannedResponse(int code, String responseBody, Supplier<T> probe) {
        Interceptor canned = chain -> {
            recorded.set(chain.request());
            calls.incrementAndGet();
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("canned")
                    .body(ResponseBody.create(responseBody, null))
                    .build();
        };
        return HttpFactories.callWith(
                new OkHttpClient.Builder().addInterceptor(canned).build(), probe);
    }
}
