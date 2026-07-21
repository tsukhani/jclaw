import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.LocalProviderProbeSupport;

import java.net.InetSocketAddress;

/**
 * JCLAW-808: {@link LocalProviderProbeSupport#probeModels} must stay fail-soft on
 * a 200 response whose body isn't the expected {@code {"data":[...]}} shape. The
 * probe runs synchronously from an {@code @OnApplicationStart} job, and the play1
 * fork rethrows an escaped exception as {@code UnexpectedException} — so an
 * unguarded {@code JsonParser.parseString(...).getAsJsonObject()} on a malformed
 * body would fail the whole boot pass. Each case here asserts the class's own
 * documented {@code Result} contract instead of a thrown exception.
 */
class LocalProviderProbeSupportTest extends UnitTest {

    private HttpServer server;
    private int port;

    @AfterEach
    void teardown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void startServer(String responseBody, int statusCode) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBody.getBytes().length);
            exchange.getResponseBody().write(responseBody.getBytes());
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    @Test
    void malformedBodyYieldsFailSoftResultWithoutThrowing() throws Exception {
        // Not JSON at all → JsonSyntaxException from parseString.
        startServer("this is not json {", 200);

        var r = LocalProviderProbeSupport.probeModels(baseUrl(), "vllm");

        assertFalse(r.available(), "malformed body must not report available");
        assertEquals(0, r.modelCount());
        assertFalse(r.connectionRefused(),
                "server answered on the port, so it is reachable — this is not a connection-refused case");
        assertNotNull(r.reason(), "fail-soft Result should carry a reason");
        assertTrue(r.reason().contains("unusable"),
                "reason should flag the unusable body, got: " + r.reason());
    }

    @Test
    void jsonArrayBodyYieldsFailSoftResult() throws Exception {
        // Valid JSON, but a top-level array → getAsJsonObject throws
        // IllegalStateException. Still fail-soft, not a boot failure.
        startServer("[1, 2, 3]", 200);

        var r = LocalProviderProbeSupport.probeModels(baseUrl(), "vllm");

        assertFalse(r.available());
        assertFalse(r.connectionRefused());
        assertNotNull(r.reason());
    }

    @Test
    void dataFieldWrongTypeYieldsFailSoftResult() throws Exception {
        // Object shape but "data" is a string, not an array → getAsJsonArray
        // throws IllegalStateException.
        startServer("{\"data\":\"oops\"}", 200);

        var r = LocalProviderProbeSupport.probeModels(baseUrl(), "vllm");

        assertFalse(r.available());
        assertFalse(r.connectionRefused());
        assertNotNull(r.reason());
    }

    @Test
    void wellFormedBodyStillCountsModels() throws Exception {
        // Regression guard: the happy path is untouched by the fail-soft catch.
        startServer("{\"object\":\"list\",\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}", 200);

        var r = LocalProviderProbeSupport.probeModels(baseUrl(), "vllm");

        assertTrue(r.available());
        assertEquals(2, r.modelCount());
        assertNull(r.reason());
        assertFalse(r.connectionRefused());
    }
}
