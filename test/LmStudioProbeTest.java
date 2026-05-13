import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import play.test.UnitTest;
import services.LmStudioProbe;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Tests for {@link LmStudioProbe} — the boot-time health check that
 * {@link jobs.LmStudioProbeJob} drives. Mirrors {@code OllamaLocalProbeTest},
 * since both probes hit the same OpenAI-compatible /models shape and use
 * the same connection-refused-vs-other-failures distinction to keep the
 * boot log quiet on fresh installs.
 */
class LmStudioProbeTest extends UnitTest {

    private HttpServer server;
    private int port;

    @AfterEach
    void teardown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        LmStudioProbe.setForTest(null);
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
    void probeReturnsAvailableWithModelCount() throws Exception {
        startServer("""
                {"object":"list","data":[
                  {"id":"qwen2.5-7b-instruct","object":"model"},
                  {"id":"llama-3.1-8b","object":"model"}
                ]}""", 200);

        var r = LmStudioProbe.probe(baseUrl());

        assertTrue(r.available());
        assertEquals(2, r.modelCount());
        assertNull(r.reason());
        assertFalse(r.connectionRefused());
    }

    @Test
    void probeFailsOnNon200WithReasonAndNotConnectionRefused() throws Exception {
        startServer("server error", 503);

        var r = LmStudioProbe.probe(baseUrl());

        assertFalse(r.available());
        assertFalse(r.connectionRefused());
        assertTrue(r.reason().contains("HTTP 503"),
                "reason should mention HTTP status, got: " + r.reason());
    }

    @Test
    void probeReportsConnectionRefusedForUnreachableHost() throws Exception {
        // Bind a socket then close it to get a port that nothing listens on —
        // the kernel returns ECONNREFUSED on a connect attempt, which is the
        // typical "LM Studio not started" failure mode (the desktop app may
        // be installed but the local server is paused).
        int closedPort;
        try (var s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        var r = LmStudioProbe.probe("http://127.0.0.1:" + closedPort);

        assertFalse(r.available());
        assertTrue(r.connectionRefused(),
                "connection-refused should be flagged so the boot job can log at DEBUG, "
                        + "not WARN. Got reason: " + r.reason());
    }

    @Test
    void setForTestOverridesCachedResultWithoutNetwork() {
        LmStudioProbe.setForTest(new LmStudioProbe.ProbeResult(
                true, 4, null, false));

        var r = LmStudioProbe.lastResult();

        assertTrue(r.available());
        assertEquals(4, r.modelCount());
    }
}
