import com.google.errorprone.annotations.MustBeClosed;
import com.google.gson.JsonObject;
import mcp.McpClient;
import mcp.McpConnectionManager;
import mcp.McpException;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpStreamableHttpTransport;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EventLogger;
import utils.CircuitBreakers;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * Streamable HTTP transport contract: POSTs each JSON-RPC message and
 * dispatches the server's reply via {@code onMessage} regardless of
 * whether the server replies with {@code application/json} (immediate)
 * or {@code text/event-stream} (streaming) per the MCP 2025-06-18 spec.
 */
class McpStreamableHttpTransportTest extends UnitTest {

    private MockWebServer server;
    private final List<JsonRpc.Message> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    /** Annotated so the constructor call is legal here; it propagates to every caller. */
    @MustBeClosed
    private McpStreamableHttpTransport newTransport() {
        return new McpStreamableHttpTransport("test", URI.create(server.url("/mcp").toString()),
                Map.of("Authorization", "Bearer t"));
    }

    // ==================== JSON response path ====================

    @Test
    void jsonResponseDispatchedAsResponseMessage() throws Exception {
        try (var transport = newTransport()) {
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
    }

    @Test
    void postCarriesAcceptHeaderAndAuthHeader() throws Exception {
        try (var transport = newTransport()) {
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
    }

    // ==================== SSE response path ====================

    @Test
    void sseResponseEachEventDispatched() throws Exception {
        try (var transport = newTransport()) {
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
    }

    @Test
    void sseMultiLineDataConcatenatedWithNewline() throws Exception {
        try (var transport = newTransport()) {
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
    }

    @Test
    void anSseStreamOfKeepAlivesThatEndsUnansweredFailsOnlyItsRequest() throws Exception {
        try (var transport = newTransport()) {
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(": keepalive\n\n: keepalive\n\n")
                    .build());
            var failure = new CompletableFuture<Map.Entry<Object, McpException>>();
            transport.start(received::add, (id, e) -> failure.complete(Map.entry(id, e)), error::set);
            transport.send(new JsonRpc.Request(5L, "tools/call", null));

            var failed = failure.get(3, TimeUnit.SECONDS);
            assertEquals(5L, failed.getKey());
            assertTrue(failed.getValue().getMessage().contains("stream ended"), failed.getValue().getMessage());
            assertTrue(received.isEmpty(), "keep-alive comments dispatch nothing");
            assertNull(error.get(), "the connection stands");
        }
    }

    @Test
    void aBrokenListenStreamFailsTheSubscriptionNotTheConnection() throws Exception {
        for (var method : List.of("subscriptions/listen", "tools/call")) {
            error.set(null);
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" + ": ping\n\n".repeat(2000))
                    .onResponseBody(new SocketEffect.CloseSocket())
                    .build());
            try (var transport = newTransport()) {
                var failed = new CompletableFuture<Object>();
                var broke = new CompletableFuture<Throwable>();
                transport.start(received::add, (id, _) -> failed.complete(id), broke::complete);
                transport.send(new JsonRpc.Request(9L, method, null));
                if (method.equals("subscriptions/listen")) {
                    assertEquals(9L, failed.get(3, TimeUnit.SECONDS));
                    assertFalse(broke.isDone(), "a dropped listen stream never reaches onError");
                } else {
                    assertNotNull(broke.get(3, TimeUnit.SECONDS), "the same break on any other request is connection-level");
                }
            }
        }
    }

    // ==================== notifications (202 Accepted) ====================

    @Test
    void notification202AcceptedNoOnMessageDispatch() throws Exception {
        try (var transport = newTransport()) {
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
    }

    // ==================== error paths ====================

    @Test
    void httpErrorFailsTheRequestNotTheConnection() throws Exception {
        try (var transport = newTransport()) {
            server.enqueue(new MockResponse.Builder().code(500).body("server bad").build());

            var failedId = new AtomicReference<Object>();
            var failure = new AtomicReference<McpException>();
            var latch = new CountDownLatch(1);
            transport.start(received::add,
                    (id, e) -> { failedId.set(id); failure.set(e); latch.countDown(); }, error::set);
            transport.send(new JsonRpc.Request(7L, "ping", null));

            assertTrue(latch.await(3, TimeUnit.SECONDS), "the request should fail on HTTP 500");
            assertEquals(7L, failedId.get());
            assertTrue(failure.get().getMessage().contains("500"), "failure must mention status: " + failure.get());
            assertNull(failure.get().code(), "a non-JSON-RPC body carries no code");
            assertNull(error.get(), "a failed exchange that got a response is not a transport error");
        }
    }

    @Test
    void httpErrorDoesNotReflectUpstreamBody() throws Exception {
        try (var transport = newTransport()) {
            // JCLAW-778: the failure carries the status only — the attacker-influenced upstream body is not echoed.
            server.enqueue(new MockResponse.Builder().code(500).body("SUPER_SECRET_UPSTREAM_BODY").build());

            var failure = new AtomicReference<McpException>();
            var latch = new CountDownLatch(1);
            transport.start(received::add, (_, e) -> { failure.set(e); latch.countDown(); }, error::set);
            transport.send(new JsonRpc.Request(1L, "ping", null));

            assertTrue(latch.await(3, TimeUnit.SECONDS), "the request should fail on HTTP 500");
            assertTrue(failure.get().getMessage().contains("500"), "failure must mention status: " + failure.get());
            assertFalse(failure.get().getMessage().contains("SUPER_SECRET_UPSTREAM_BODY"),
                    "failure must NOT reflect the upstream body: " + failure.get());
        }
    }

    @Test
    void twoArgStartRoutesARequestFailureToOnError() throws Exception {
        try (var transport = newTransport()) {
            server.enqueue(new MockResponse.Builder().code(500).body("server bad").build());

            var latch = new CountDownLatch(1);
            transport.start(received::add, t -> { error.set(t); latch.countDown(); });
            transport.send(new JsonRpc.Request(1L, "ping", null));

            assertTrue(latch.await(3, TimeUnit.SECONDS), "onError should fire on HTTP 500");
            assertTrue(error.get().getMessage().contains("500"), "error must mention status: " + error.get());
        }
    }

    // ==================== per-request failure through McpClient (JCLAW-1442) ====================

    private static final String MARKER = "JCLAW1442_BODY_MARKER";

    /** A scripted MCP server: handshake, then each tools/call answered by {@code onCall(toolName, id)}. */
    private void serveMcp(@Nullable String sessionId, BiFunction<String, Object, MockResponse> onCall) {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                var msg = JsonRpc.decode(request.getBody().utf8());
                if (!(msg instanceof JsonRpc.Request r)) return new MockResponse.Builder().code(202).build();
                return switch (r.method()) {
                    case "initialize" -> {
                        var b = json(200, result(r.id(), "{}"));
                        if (sessionId != null) b.addHeader("Mcp-Session-Id", sessionId);
                        yield b.build();
                    }
                    case "tools/list" -> json(200, result(r.id(), "{\"tools\":[]}")).build();
                    default -> onCall.apply(
                            ((JsonObject) Objects.requireNonNull(r.params())).get("name").getAsString(), r.id());
                };
            }
        });
    }

    private static MockResponse.Builder json(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body);
    }

    private static String result(Object id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
    }

    private static String rpcError(Object id, int code, String data) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code
                + ",\"message\":\"" + MARKER + "\",\"data\":" + data + "}}";
    }

    @MustBeClosed
    @SuppressWarnings("MustBeClosed") // the client owns the transport and closes it
    private McpClient newClient() {
        return new McpClient("test", newTransport(), "0", Duration.ofSeconds(5));
    }

    @Test
    void aFailedExchangeLeavesTheOtherInFlightCallAndTheClient() throws Exception {
        var slowArrived = new CountDownLatch(1);
        var releaseSlow = new CountDownLatch(1);
        serveMcp(null, (tool, id) -> {
            if (tool.equals("bad")) return json(400, rpcError(id, -32602, "null")).build();
            slowArrived.countDown();
            try {
                releaseSlow.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return json(200, result(id, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}")).build();
        });
        try (var client = newClient()) {
            client.connect();
            var slow = CompletableFuture.supplyAsync(() -> {
                try {
                    return client.callTool("slow", new JsonObject());
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });
            assertTrue(slowArrived.await(3, TimeUnit.SECONDS), "the slow call must be in flight");

            var ex = assertThrows(McpException.class, () -> client.callTool("bad", new JsonObject()));
            assertEquals(-32602, ex.code());
            assertEquals(McpClient.State.READY, client.state());

            releaseSlow.countDown();
            var ok = slow.get(5, TimeUnit.SECONDS);
            assertFalse(ok.isError());
            assertEquals(McpClient.State.READY, client.state());
            assertNull(client.lastError());
            // A later call still goes through on the same connection.
            serveMcp(null, (_, id) -> json(200, result(id, "{\"content\":[]}")).build());
            assertFalse(client.callTool("again", new JsonObject()).isError());
        }
    }

    @Test
    void aTimedOutRequestLeavesNoCallInFlightAndTheConnectionStands() throws Exception {
        var release = new CountDownLatch(1);
        serveMcp(null, (tool, id) -> {
            if (tool.equals("hang")) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return json(200, result(id, "{\"content\":[]}")).build();
        });
        try (var transport = newTransport();
             var client = new McpClient("test", transport, "0", Duration.ofSeconds(5), Duration.ofMillis(300))) {
            client.connect();
            var ex = assertThrows(McpException.class, () -> client.callTool("hang", new JsonObject()));
            assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
            // The server is still holding the request, so only the cancel can have ended the call.
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (transport.inFlightCalls() > 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(0, transport.inFlightCalls());
            assertEquals(McpClient.State.READY, client.state(), "a cancelled call is not a lost connection");
            assertNull(client.lastError());
            assertFalse(client.callTool("fast", new JsonObject()).isError());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aJsonRpcErrorBodyGivesItsCodeAndKnownVersionsButNeverItsMessage() throws Exception {
        serveMcp(null, (_, id) -> json(400, rpcError(id, -32022,
                "{\"supported\":[\"2026-07-28\",\"" + MARKER + "\",\"2025-06-18\",7]}")).build());
        var breakerName = "mcp-test-" + UUID.randomUUID();
        var breaker = CircuitBreakers.get(breakerName, McpConnectionManager.BREAKER_CONFIG);
        try (var client = newClient()) {
            client.connect();
            var thrown = new AtomicReference<McpException>();
            var events = EventLogger.captureMatchingForTest(
                    c -> c.message().contains(MARKER) || (c.details() != null && c.details().contains(MARKER)),
                    _ -> thrown.set(assertThrows(McpException.class, () -> McpConnectionManager.guardedCall(
                            breaker, "test", () -> client.callTool("t", new JsonObject())))));

            var ex = thrown.get();
            assertEquals(-32022, ex.code());
            assertEquals(List.of("2026-07-28", "2025-06-18"), ex.supportedVersions());
            assertTrue(ex.getMessage().contains("400"), "failure must mention status: " + ex.getMessage());
            assertFalse(ex.getMessage().contains(MARKER), ex.getMessage());
            assertTrue(client.lastError() == null || !client.lastError().contains(MARKER), client.lastError());
            assertEquals(List.of(), events);
            // JCLAW-1168: the failed call still counts against the server.
            assertEquals(1, breaker.stats().failures());
            assertEquals(McpClient.State.READY, client.state());
        } finally {
            CircuitBreakers.remove(breakerName);
        }
    }

    @Test
    void aNotFoundOnASessionBearingRequestTearsTheConnectionDown() throws Exception {
        assertSessionBearingStatusTearsDown(404);
    }

    @Test
    void aBadRequestOnASessionBearingRequestTearsTheConnectionDown() throws Exception {
        assertSessionBearingStatusTearsDown(400);
    }

    private void assertSessionBearingStatusTearsDown(int status) throws Exception {
        serveMcp("sess-1", (_, id) -> json(status, rpcError(id, -32001, "null")).build());
        try (var client = newClient()) {
            client.connect();
            assertThrows(Exception.class, () -> client.callTool("t", new JsonObject()));
            // onTransportError fails the pending future before it flips the state.
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (client.state() != McpClient.State.DISCONNECTED && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(McpClient.State.DISCONNECTED, client.state());
            assertEquals("MCP HTTP " + status, client.lastError());
        }
    }

    @Test
    void aNotFoundWithoutASessionFailsOnlyItsRequest() throws Exception {
        serveMcp(null, (_, id) -> json(404, rpcError(id, -32601, "null")).build());
        try (var client = newClient()) {
            client.connect();
            var ex = assertThrows(McpException.class, () -> client.callTool("t", new JsonObject()));
            assertEquals(-32601, ex.code());
            assertEquals(McpClient.State.READY, client.state());
        }
    }

    @Test
    void aServerErrorOnASessionBearingRequestFailsOnlyItsRequest() throws Exception {
        serveMcp("sess-1", (tool, id) -> tool.equals("bad")
                ? json(500, rpcError(id, -32603, "null")).build()
                : json(200, result(id, "{\"content\":[]}")).build());
        try (var client = newClient()) {
            client.connect();
            var ex = assertThrows(McpException.class, () -> client.callTool("bad", new JsonObject()));
            assertEquals(-32603, ex.code());
            assertEquals(McpClient.State.READY, client.state());
            assertNull(client.lastError());
            assertFalse(client.callTool("again", new JsonObject()).isError());
            assertEquals(McpClient.State.READY, client.state());
        }
    }

    @Test
    void sendRejectsMetadataEndpointBeforeConnect() {
        // JCLAW-778: an agent-settable endpoint pointing at the cloud-metadata IP
        // is rejected synchronously by the SSRF guard before the POST is enqueued.
        try (var t = new McpStreamableHttpTransport("meta",
                URI.create("http://169.254.169.254/mcp"), Map.of())) {
            t.start(received::add, error::set);
            var ex = assertThrows(java.io.IOException.class,
                    () -> t.send(new JsonRpc.Request(1L, "ping", null)));
            assertTrue(ex.getMessage().contains("SSRF guard"),
                    "rejection must come from the guard: " + ex.getMessage());
            assertEquals(0, server.getRequestCount(), "no request may reach any server");
        }
    }

    @Test
    void closeAfterStartIsClean() {
        try (var transport = newTransport()) {
            transport.start(received::add, error::set);
            transport.close();
            // Sending after close should fail synchronously.
            var ex = assertThrows(java.io.IOException.class,
                    () -> transport.send(new JsonRpc.Request(1L, "ping", null)));
            assertTrue(ex.getMessage().contains("closed"));
        }
    }

    // ==================== body sanity ====================

    @Test
    void postBodyIsValidJsonRpc() throws Exception {
        try (var transport = newTransport()) {
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
}
