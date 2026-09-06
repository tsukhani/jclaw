import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.HttpFactories;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Covers the {@code ScopedValue} transport override on {@link HttpFactories}
 * (JCLAW-1151): the seam that lets a test substitute the outbound stack for the
 * duration of a call instead of standing up a listener on a port.
 *
 * <p>The canned-response client here is an OkHttp <em>application</em>
 * interceptor, which short-circuits ahead of DNS and connect — so the request
 * URLs below point at {@code .invalid}, a host that can never resolve. A leak in
 * the seam surfaces as a failed call, never as a silent live request.
 */
class HttpFactoriesOverrideTest extends UnitTest {

    private static final String UNROUTABLE = "http://transport-override.invalid/ping";

    @Test
    void unboundAccessorsReturnTheSharedProductionClients() {
        assertSame(HttpFactories.general(), HttpFactories.general(),
                "unbound, the accessor must keep handing out one shared client");
        assertSame(HttpFactories.llmStreaming(), HttpFactories.llmStreaming());
        assertSame(HttpFactories.llmSingleShotGuarded(), HttpFactories.llmSingleShotGuarded());
        assertNotSame(HttpFactories.general(), HttpFactories.llmStreaming(),
                "the tiers are distinct clients — a same-instance result would make the "
                        + "assertions above pass for the wrong reason");
    }

    @Test
    void bindingRedirectsEveryAccessorIncludingTheGuardedVariants() {
        var fake = cannedClient(200, "ok");
        HttpFactories.runWith(fake, () -> {
            assertSame(fake, HttpFactories.general());
            assertSame(fake, HttpFactories.llmStreaming());
            assertSame(fake, HttpFactories.llmSingleShot());
            assertSame(fake, HttpFactories.generalGuarded());
            assertSame(fake, HttpFactories.llmStreamingGuarded());
            assertSame(fake, HttpFactories.llmSingleShotGuarded());
        });
    }

    @Test
    void bindingIsReleasedWhenTheBodyReturns() {
        var production = HttpFactories.general();
        var fake = cannedClient(200, "ok");

        HttpFactories.runWith(fake, () -> assertSame(fake, HttpFactories.general()));

        assertSame(production, HttpFactories.general(),
                "the override must not outlive its binding — that is the leak a static field had");
    }

    @Test
    void boundTransportServesCannedResponsesWithoutAListener() throws Exception {
        var body = HttpFactories.callWith(cannedClient(418, "brewed"), () -> {
            var request = new Request.Builder().url(UNROUTABLE).get().build();
            try (var response = HttpFactories.general().newCall(request).execute()) {
                assertEquals(418, response.code());
                return response.body().string();
            } catch (IOException e) {
                throw new AssertionError("the override must short-circuit before the network", e);
            }
        });
        assertEquals("brewed", body);
    }

    @Test
    void aForeignThreadStillSeesTheProductionClient() throws Exception {
        var production = HttpFactories.general();
        var seen = new AtomicReference<OkHttpClient>();

        HttpFactories.runWith(cannedClient(200, "ok"), () -> {
            var other = new Thread(() -> seen.set(HttpFactories.general()));
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        assertSame(production, seen.get(),
                "a ScopedValue binding does not follow an unrelated thread, which is why a "
                        + "FunctionalTest driving a controller on Play's request thread cannot use it");
    }

    /** A client whose application interceptor answers every request with the given code and body. */
    private static OkHttpClient cannedClient(int code, String body) {
        Interceptor canned = chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(ResponseBody.create(body, null))
                .build();
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }
}
