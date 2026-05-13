import com.google.gson.JsonObject;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpStreamableHttpTransport;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streamable HTTP transport contract: POSTs each JSON-RPC message and
 * dispatches the server's reply via {@code onMessage} regardless of
 * whether the server replies with {@code application/json} (immediate)
 * or {@code text/event-stream} (streaming) per the MCP 2025-06-18 spec.
 */
public class McpStreamableHttpTransportTest extends UnitTest {

    private MockWebServer server;
    private McpStreamableHttpTransport transport;
    private final List<JsonRpc.Message> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    @BeforeEach
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        var endpoint = URI.create(server.url("/mcp").toString());
        transport = new McpStreamableHttpTransport("test", endpoint, Map.of("Authorization", "Bearer t"));
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (transport != null) transport.close();
        server.close();
    }

    // ==================== JSON response path ====================

    @Test
    public void jsonResponseDispatchedAsResponseMessage() throws Exception {
        var resultJson = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}";
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(resultJson)
                .build());

        var latch = new CountDownLatch(1);
        transport.start(msg -> { received.add(msg); latch.countDown(); }, error::set);
        transport.send(new JsonRpc.Request(1L, "ping", null));

        assertTrue(latch.await(3, TimeUnit.SECONDS), "response should arrive within 3s");
        assertNull(error.get(), "no error on a clean JSON response");
        assertEquals(1, received.size());
        assertTrue(received.get(0) instanceof JsonRpc.Response);
        assertEquals(1L, ((JsonRpc.Response) received.get(0)).id());
    }

    @Test
    public void postCarriesAcceptHeaderAndAuthHeader() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")
                .build());

        var latch = new CountDownLatch(1);
        transport.start(msg -> latch.countDown(), error::set);
        transport.send(new JsonRpc.Request(1L, "ping", null));
        assertTrue(latch.await(3, TimeUnit.SECONDS));

        var req = server.takeRequest();
        var accept = req.getHeaders().get("Accept");
        assertNotNull(accept);
        assertTrue(accept.contains("application/json"), "Accept header must include JSON: " + accept);
        assertTrue(accept.contains("text/event-stream"), "Accept header must include SSE: " + accept);
        assertEquals("Bearer t", req.getHeaders().get("Authorization"));
    }

    // ==================== SSE response path ====================

    @Test
    public void sseResponseEachEventDispatched() throws Exception {
        var sse = """
                data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":0.5}}

                data: {"jsonrpc":"2.0","id":1,"result":{"final":true}}

                """;
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sse)
                .build());

        var latch = new CountDownLatch(2);
        transport.start(msg -> { received.add(msg); latch.countDown(); }, error::set);
        transport.send(new JsonRpc.Request(1L, "tools/call", null));

        assertTrue(latch.await(3, TimeUnit.SECONDS), "both SSE events should arrive within 3s");
        assertNull(error.get());
        assertEquals(2, received.size());
        assertTrue(received.get(0) instanceof JsonRpc.Notification);
        assertTrue(received.get(1) instanceof JsonRpc.Response);
        assertEquals(1L, ((JsonRpc.Response) received.get(1)).id());
    }

    @Test
    public void sseMultiLineDataConcatenatedWithNewline() throws Exception {
        // Spec: when 'data:' appears multiple times in one event, values are joined with \n.
        var sse = """
                data: {"jsonrpc":"2.0","id":1,
                data: "result":{"ok":true}}

                """;
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sse)
                .build());

        var latch = new CountDownLatch(1);
        transport.start(msg -> { received.add(msg); latch.countDown(); }, error::set);
        transport.send(new JsonRpc.Request(1L, "ping", null));
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertTrue(received.get(0) instanceof JsonRpc.Response);
    }

    // ==================== notifications (202 Accepted) ====================

    @Test
    public void notification202AcceptedNoOnMessageDispatch() throws Exception {
        server.enqueue(new MockResponse.Builder().code(202).build());

        transport.start(received::add, error::set);
        transport.send(new JsonRpc.Notification("notifications/initialized", null));

        // Wait long enough for the VT to have completed the POST.
        var deadline = System.currentTimeMillis() + 1500;
        while (server.getRequestCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(100);  // settle: ensure no late onMessage delivery
        assertEquals(1, server.getRequestCount(), "POST must have happened");
        assertEquals(0, received.size(), "202 must NOT trigger onMessage");
        assertNull(error.get());
    }

    // ==================== error paths ====================

    @Test
    public void httpErrorTriggersOnError() throws Exception {
        server.enqueue(new MockResponse.Builder().code(500).body("server bad").build());

        var latch = new CountDownLatch(1);
        transport.start(received::add, t -> { error.set(t); latch.countDown(); });
        transport.send(new JsonRpc.Request(1L, "ping", null));

        assertTrue(latch.await(3, TimeUnit.SECONDS), "onError should fire on HTTP 500");
        assertTrue(error.get().getMessage().contains("500"), "error must mention status: " + error.get());
    }

    @Test
    public void closeAfterStartIsClean() {
        transport.start(received::add, error::set);
        transport.close();
        // Sending after close should fail synchronously.
        var ex = assertThrows(java.io.IOException.class,
                () -> transport.send(new JsonRpc.Request(1L, "ping", null)));
        assertTrue(ex.getMessage().contains("closed"));
    }

    // ==================== body sanity ====================

    @Test
    public void postBodyIsValidJsonRpc() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")
                .build());

        var latch = new CountDownLatch(1);
        transport.start(msg -> latch.countDown(), error::set);
        var args = new JsonObject();
        args.addProperty("k", "v");
        transport.send(new JsonRpc.Request(1L, "tools/call", args));
        assertTrue(latch.await(3, TimeUnit.SECONDS));

        var bodyStr = server.takeRequest().getBody().utf8();
        var decoded = JsonRpc.decode(bodyStr);
        assertTrue(decoded instanceof JsonRpc.Request);
        var req = (JsonRpc.Request) decoded;
        assertEquals(1L, req.id());
        assertEquals("tools/call", req.method());
    }
}
