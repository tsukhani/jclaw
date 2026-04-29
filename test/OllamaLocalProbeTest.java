import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import play.test.UnitTest;
import services.OllamaLocalProbe;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Tests for {@link OllamaLocalProbe} — the boot-time health check that
 * {@link jobs.OllamaLocalProbeJob} drives. Pins three behaviors that matter
 * for JCLAW-178 AC #6:
 *
 * <ol>
 *   <li>A reachable Ollama-shaped /models endpoint counts entries from the
 *       OpenAI-compatible {@code data} array.</li>
 *   <li>Connection-refused is flagged distinctly from other failures so the
 *       boot job can demote it to DEBUG.</li>
 *   <li>{@link OllamaLocalProbe#setForTest(OllamaLocalProbe.ProbeResult)}
 *       overrides the cached result without touching the network.</li>
 * </ol>
 */
public class OllamaLocalProbeTest extends UnitTest {

    private HttpServer server;
    private int port;

    @AfterEach
    void teardown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        OllamaLocalProbe.setForTest(null);
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
    public void probeReturnsAvailableWithModelCount() throws Exception {
        startServer("""
                {"object":"list","data":[
                  {"id":"llama3.1:8b","object":"model"},
                  {"id":"qwen2.5","object":"model"},
                  {"id":"mistral","object":"model"}
                ]}""", 200);

        var r = OllamaLocalProbe.probe(baseUrl());

        assertTrue(r.available());
        assertEquals(3, r.modelCount());
        assertNull(r.reason());
        assertFalse(r.connectionRefused());
    }

    @Test
    public void probeReturnsZeroCountWhenDataArrayMissing() throws Exception {
        startServer("{\"object\":\"list\"}", 200);

        var r = OllamaLocalProbe.probe(baseUrl());

        assertTrue(r.available());
        assertEquals(0, r.modelCount());
    }

    @Test
    public void probeFailsOnNon200WithReasonAndNotConnectionRefused() throws Exception {
        startServer("internal server error", 500);

        var r = OllamaLocalProbe.probe(baseUrl());

        assertFalse(r.available());
        assertFalse(r.connectionRefused(),
                "non-200 from a reachable server is a real WARN-worthy failure, not the silent case");
        assertTrue(r.reason().contains("HTTP 500"),
                "reason should mention HTTP status, got: " + r.reason());
    }

    @Test
    public void probeReportsConnectionRefusedForUnreachableHost() throws Exception {
        // Bind a socket then close it to get a port that nothing listens on.
        // The kernel returns ECONNREFUSED on a connect attempt — exactly the
        // "Ollama not installed" failure mode AC #6 cares about.
        int closedPort;
        try (var s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        var r = OllamaLocalProbe.probe("http://127.0.0.1:" + closedPort);

        assertFalse(r.available());
        assertTrue(r.connectionRefused(),
                "connection-refused should be flagged so the boot job can log at DEBUG, "
                        + "not WARN. Got reason: " + r.reason());
    }

    @Test
    public void lastResultReflectsMostRecentProbe() throws Exception {
        startServer("{\"data\":[{\"id\":\"only-model\"}]}", 200);

        OllamaLocalProbe.probe(baseUrl());

        assertTrue(OllamaLocalProbe.lastResult().available());
        assertEquals(1, OllamaLocalProbe.lastResult().modelCount());
    }

    @Test
    public void setForTestOverridesCachedResultWithoutNetwork() {
        OllamaLocalProbe.setForTest(new OllamaLocalProbe.ProbeResult(
                true, 7, null, false));

        var r = OllamaLocalProbe.lastResult();

        assertTrue(r.available());
        assertEquals(7, r.modelCount());
    }
}
