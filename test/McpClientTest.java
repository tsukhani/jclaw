import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.McpClient;
import mcp.McpEraDetection;
import mcp.McpException;
import mcp.McpToolDef;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpTransport;
import models.McpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

class McpClientTest extends UnitTest {

    private FakeTransport transport;

    // ==================== handshake ====================

    @Test
    void connectPerformsInitializeHandshake() throws Exception {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            // Drive the conversation in a background thread so connect() can block on responses.
            var driver = Thread.ofVirtual().start(() -> {
                try {
                    var initReq = transport.takeSent(JsonRpc.Request.class);
                    assertEquals("initialize", initReq.method());
                    assertNotNull(initReq.id());
                    var initResult = new JsonObject();
                    initResult.addProperty("protocolVersion", McpClient.PROTOCOL_VERSION);
                    initResult.add("capabilities", new JsonObject());
                    transport.deliver(new JsonRpc.Response(initReq.id(), initResult, null));

                    var initialized = transport.takeSent(JsonRpc.Notification.class);
                    assertEquals("notifications/initialized", initialized.method());

                    var listReq = transport.takeSent(JsonRpc.Request.class);
                    assertEquals("tools/list", listReq.method());
                    transport.deliver(new JsonRpc.Response(listReq.id(), toolsResult(), null));
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });

            client.connect();
            driver.join(5000);
            assertEquals(McpClient.State.READY, client.state());
            assertEquals(1, client.tools().size());
            assertEquals("echo", client.tools().get(0).name());
        }
    }

    @Test
    void connectRejectsServerErrorOnInitialize() {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            Thread.ofVirtual().start(() -> {
                try {
                    var req = transport.takeSent(JsonRpc.Request.class);
                    transport.deliver(new JsonRpc.Response(req.id(), null,
                            new JsonRpc.Error(-32602, "Unsupported version")));
                } catch (InterruptedException _) {}
            });

            var ex = assertThrows(McpException.class, client::connect);
            assertTrue(ex.getMessage().contains("Unsupported version"),
                    "exception should propagate server error message: " + ex.getMessage());
            assertEquals(McpClient.State.DISCONNECTED, client.state());
        }
    }

    @Test
    void connectRefusedFromNonDisconnectedState() throws Exception {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            completeHandshake(client);
            assertThrows(McpException.class, client::connect);
        }
    }

    // ==================== callTool ====================

    // JCLAW-1191: the generous first-connect budget belongs to the handshake alone. A client
    // that kept it for every request made a hung server cost two minutes per tool call.
    @Test
    void aToolCallUsesTheRequestBudgetNotTheHandshakeBudget() throws Exception {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1", Duration.ofSeconds(30), Duration.ofMillis(300))) {
            completeHandshake(client);

            // The server never answers the call.
            var t0 = System.nanoTime();
            var ex = assertThrows(McpException.class, () -> client.callTool("echo", new JsonObject()));
            var elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertTrue(ex.getMessage().contains("timed out after PT0.3S"), ex.getMessage());
            assertTrue(elapsedMs < 5_000, "the 30 s handshake budget must not apply to a tool call: " + elapsedMs + " ms");
        }
    }

    @Test
    void callToolReturnsTextContent() throws Exception {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            completeHandshake(client);

            Thread.ofVirtual().start(() -> {
                try {
                    var call = transport.takeSent(JsonRpc.Request.class);
                    assertEquals("tools/call", call.method());
                    var result = new JsonObject();
                    var content = new JsonArray();
                    var part = new JsonObject();
                    part.addProperty("type", "text");
                    part.addProperty("text", "echoed");
                    content.add(part);
                    result.add("content", content);
                    transport.deliver(new JsonRpc.Response(call.id(), result, null));
                } catch (InterruptedException _) {}
            });

            var args = new JsonObject();
            args.addProperty("text", "hello");
            var result = client.callTool("echo", args);
            assertEquals("echoed", result.content());
            assertFalse(result.isError());
        }
    }

    @Test
    void callToolPropagatesServerErrorAsMcpException() throws Exception {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            completeHandshake(client);

            Thread.ofVirtual().start(() -> {
                try {
                    var call = transport.takeSent(JsonRpc.Request.class);
                    transport.deliver(new JsonRpc.Response(call.id(), null,
                            new JsonRpc.Error(-32000, "tool crashed")));
                } catch (InterruptedException _) {}
            });

            var args = new JsonObject();
            var ex = assertThrows(McpException.class,
                    () -> client.callTool("echo", args));
            assertTrue(ex.getMessage().contains("tool crashed"));
        }
    }

    @Test
    void callToolRejectedBeforeReady() {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            // No connect() — state remains DISCONNECTED.
            var args = new JsonObject();
            assertThrows(McpException.class, () -> client.callTool("echo", args));
        }
    }

    // ==================== notifications ====================

    @Test
    void toolsListChangedTriggersRefreshAndCallback() throws Exception {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            var fired = new AtomicReference<List<McpToolDef>>();
            client.onToolsChanged(fired::set);

            completeHandshake(client);

            // Server pushes a list_changed notification.
            transport.deliver(new JsonRpc.Notification("notifications/tools/list_changed", null));

            // The handler spawns a VT that fetches tools/list. We respond with a different list.
            var refresh = transport.takeSent(JsonRpc.Request.class);
            assertEquals("tools/list", refresh.method());
            var newTools = new JsonObject();
            var arr = new JsonArray();
            var t = new JsonObject();
            t.addProperty("name", "echo");
            t.addProperty("description", "Echo");
            t.add("inputSchema", new JsonObject());
            arr.add(t);
            var t2 = new JsonObject();
            t2.addProperty("name", "noop");
            t2.addProperty("description", "Do nothing");
            t2.add("inputSchema", new JsonObject());
            arr.add(t2);
            newTools.add("tools", arr);
            transport.deliver(new JsonRpc.Response(refresh.id(), newTools, null));

            // Wait briefly for the VT to complete the refresh and fire the callback.
            var deadline = System.currentTimeMillis() + 2000;
            while (fired.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertNotNull(fired.get(), "onToolsChanged callback must fire after refresh");
            assertEquals(2, fired.get().size());
            assertEquals(2, client.tools().size());
        }
    }

    // ==================== server-initiated requests ====================

    @Test
    void serverInitiatedRequestRepliedWithMethodNotFound() throws Exception {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            completeHandshake(client);

            // Server requests something we don't support (e.g., sampling/createMessage).
            transport.deliver(new JsonRpc.Request(99L, "sampling/createMessage", null));

            var reply = transport.takeSent(JsonRpc.Response.class);
            assertEquals(99L, reply.id());
            assertTrue(reply.isError());
            assertEquals(-32601, reply.error().code());
        }
    }

    // ==================== transport errors ====================

    @Test
    void transportErrorMovesToDisconnectedAndCancelsPending() throws Exception {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            completeHandshake(client);

            // Issue a callTool but never respond — instead, trip a transport error.
            var pendingCall = Thread.ofVirtual().start(() -> {
                try {
                    client.callTool("echo", new JsonObject());
                    fail("callTool should have failed");
                } catch (Exception _) {
                    // expected: future is completed exceptionally
                }
            });

            // Wait for the request to land.
            transport.takeSent(JsonRpc.Request.class);
            transport.tripError(new IOException("connection reset"));

            pendingCall.join(2000);
            assertEquals(McpClient.State.DISCONNECTED, client.state());
            assertNotNull(client.lastError());
        }
    }

    /**
     * Regression: a transport-level error must symmetrically close the
     * transport. Without this, the underlying host process (e.g. a
     * `docker run` subprocess wrapping an MCP server image) stays alive
     * holding stdio open even after the inner server has died — the
     * connection manager observes state≠READY and reconnects, but the
     * orphaned host process persists. Repeated disconnect-reconnect
     * cycles leak one process (and one docker --rm container) each.
     */
    @Test
    void transportErrorTriggersTransportCloseSoUnderlyingProcessIsReleased() throws Exception {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            completeHandshake(client);

            assertFalse(transport.closed, "precondition: transport open after handshake");
            transport.tripError(new IOException("server died"));

            assertEquals(McpClient.State.DISCONNECTED, client.state());
            assertTrue(transport.closed,
                    "onTransportError must call transport.close() so the underlying "
                            + "host process (e.g. docker run subprocess) exits and --rm "
                            + "releases the container");
        }
    }

    // ==================== close ====================

    @Test
    void closeIsIdempotent() {
        transport = new FakeTransport();
        try (var client = new McpClient("test", transport, "0.0.1")) {
            client.close();
            client.close();  // must not throw
            assertEquals(McpClient.State.DISCONNECTED, client.state());
        }
    }

    // ==================== JCLAW-1443: protocol eras ====================

    private static final String DISCOVER_RESULT =
            "{\"supportedVersions\":[\"2026-07-28\"],\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}";
    private static final String TOOLS = "{\"tools\":[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]}";
    private static final String INIT_RESULT = "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}";

    /** A handshake-era server: the probe is refused with {@code probeCode}, or ignored when it is null. */
    private static Script handshakeEra(@Nullable Integer probeCode) {
        return (_, r) -> switch (r.method()) {
            case "server/discover" -> probeCode == null ? null : err(r, probeCode, "no such method", null);
            case "initialize" -> ok(r, INIT_RESULT);
            case "tools/list" -> ok(r, TOOLS);
            default -> ok(r, "{\"content\":[]}");
        };
    }

    /** A dual-era server whose era, like a real stdio process's, is fixed by the first request it answers. */
    private static Script dualEra() {
        var era = new AtomicReference<String>();
        return (_, r) -> switch (r.method()) {
            case "server/discover" -> {
                era.compareAndSet(null, "modern");
                yield ok(r, DISCOVER_RESULT);
            }
            case "initialize" -> {
                if (era.compareAndSet(null, "handshake") || "handshake".equals(era.get())) yield ok(r, INIT_RESULT);
                yield err(r, -32022, "Unsupported protocol version", "{\"supported\":[\"2026-07-28\"]}");
            }
            case "tools/list" -> ok(r, TOOLS);
            default -> ok(r, "{\"content\":[{\"type\":\"text\",\"text\":\"" + era.get() + "\"}]}");
        };
    }

    /** A server that speaks only 2026-07-28; a modern tools/call is answered by {@code onCall}. */
    private static Script modernOnly(Script onCall) {
        return (t, r) -> switch (r.method()) {
            case "server/discover" -> ok(r, DISCOVER_RESULT);
            case "initialize" -> err(r, -32022, "Unsupported protocol version", "{\"supported\":[\"2026-07-28\"]}");
            case "tools/list" -> ok(r, "{\"resultType\":\"complete\"," + TOOLS.substring(1));
            default -> onCall.answer(t, r);
        };
    }

    private record EraCase(String name, Supplier<Script> server, boolean modern, int connections,
                           List<String> firstConnection, boolean probeTimedOut) {}

    /** The spec's compatibility matrix, as each kind of server answers the probe. */
    @Test
    void theCompatibilityMatrixSettlesEachServersEra() throws Exception {
        var handshake = List.of("server/discover", "initialize", "notifications/initialized", "tools/list");
        var cases = List.of(
                new EraCase("modern server", () -> modernOnly((_, r) -> ok(r, "{}")), true, 1,
                        List.of("server/discover", "tools/list"), false),
                new EraCase("handshake-era, probe refused -32601", () -> handshakeEra(-32601), false, 1, handshake, false),
                new EraCase("handshake-era, probe refused -32602", () -> handshakeEra(-32602), false, 1, handshake, false),
                new EraCase("handshake-era, probe ignored", () -> handshakeEra(null), false, 1, handshake, true),
                new EraCase("modern server rejecting the version with -32022", () -> (_, r) -> switch (r.method()) {
                    case "server/discover" -> err(r, -32022, "Unsupported protocol version",
                            "{\"supported\":[\"2025-11-25\"]}");
                    case "initialize" -> ok(r, INIT_RESULT);
                    default -> ok(r, TOOLS);
                }, false, 2, List.of("server/discover"), false));
        for (var c : cases) {
            var transports = new CopyOnWriteArrayList<ScriptedTransport>();
            var opened = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                    clients(transports, c.server(), Duration.ofMillis(400)));
            try (var client = opened.client()) {
                assertEquals(McpClient.State.READY, client.state(), c.name());
                assertEquals(c.modern(), client.isModern(), c.name());
                assertEquals(c.modern() ? "2026-07-28" : "2025-06-18", client.protocolVersion(), c.name());
                assertEquals(c.connections(), transports.size(), c.name());
                assertEquals(c.firstConnection(), transports.getFirst().methods, c.name());
                assertEquals(c.probeTimedOut(), client.probeTimedOut(), c.name());
                assertEquals(1, client.tools().size(), c.name());
                assertNull(opened.switchReason(), c.name());
            }
        }
    }

    @Test
    void aDualEraServerThatAnsweredDiscoveryIsNeverSentInitialize() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var opened = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(transports, McpClientTest::dualEra, Duration.ofSeconds(5)));
        try (var client = opened.client()) {
            assertTrue(client.isModern());
            assertTrue(opened.offersModern());
            assertEquals(1, transports.size());
            assertFalse(transports.getFirst().methods.contains("initialize"), transports.getFirst().methods.toString());
            assertEquals("modern", client.callTool("echo", new JsonObject()).content());
        }
    }

    @Test
    void aProbeAnsweredAfterTheBudgetAndTheFallbackInitializeEndsModern() throws Exception {
        var discoverId = new AtomicReference<Object>();
        Script late = (t, r) -> switch (r.method()) {
            case "server/discover" -> {
                discoverId.set(r.id());
                yield null;
            }
            case "initialize" -> {
                // A stdio server writes in order: the late DiscoverResult, then its -32022 to initialize.
                t.deliver(ok(new JsonRpc.Request(discoverId.get(), "server/discover", null), DISCOVER_RESULT));
                yield err(r, -32022, "Unsupported protocol version", "{\"supported\":[\"2026-07-28\"]}");
            }
            case "tools/list" -> ok(r, TOOLS);
            default -> ok(r, "{}");
        };
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var opened = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(transports, () -> late, Duration.ofMillis(300)));
        try (var client = opened.client()) {
            assertEquals(McpClient.State.READY, client.state());
            assertTrue(client.isModern());
            assertEquals(1, transports.size());
            assertEquals(List.of("server/discover", "initialize", "tools/list"), transports.getFirst().methods);
            assertTrue(transports.getFirst().requests.getLast().params().toString()
                    .contains("io.modelcontextprotocol/protocolVersion"), "the tools/list after must be modern");
        }
    }

    @Test
    void aServerSetToHandshakeIsNeverSentTheProbe() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var opened = McpEraDetection.open("fake", McpServer.Protocol.HANDSHAKE, false,
                clients(transports, McpClientTest::dualEra, Duration.ofSeconds(5)));
        try (var client = opened.client()) {
            assertFalse(client.isModern());
            assertEquals(List.of("initialize", "notifications/initialized", "tools/list"), transports.getFirst().methods);
            assertEquals("handshake", client.callTool("echo", new JsonObject()).content());
        }
    }

    @Test
    void aHandshakeServerRefusedWithMinus32022NamesTheSettingToChange() {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var ex = assertThrows(McpException.class, () -> McpEraDetection.open("fake", McpServer.Protocol.HANDSHAKE,
                false, clients(transports, () -> modernOnly((_, r) -> ok(r, "{}")), Duration.ofSeconds(5))));
        assertEquals(-32022, ex.code());
        assertTrue(ex.getMessage().contains("now needs protocol 2026-07-28"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Set its Protocol to Automatic"), ex.getMessage());
        assertFalse(transports.getFirst().methods.contains("server/discover"));
    }

    @Test
    void aServerSharingNoVersionFailsNamingItsVersionsWhenTheHandshakeIsRefusedToo() {
        Script none = (_, r) -> err(r, -32022, "Unsupported protocol version", "{\"supported\":[\"2025-11-25\"]}");
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var ex = assertThrows(McpException.class, () -> McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC,
                false, clients(transports, () -> none, Duration.ofSeconds(5))));
        assertEquals(2, transports.size(), "the handshake must be tried on a fresh connection");
        assertTrue(ex.getMessage().contains("offers 2025-11-25"), ex.getMessage());
    }

    @Test
    void aModernRequestCarriesItsMetaAndTheThreeHeaders() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var opened = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(transports, () -> modernOnly((_, r) -> ok(r, "{\"content\":[]}")), Duration.ofSeconds(5)));
        try (var client = opened.client()) {
            client.callTool("échø", new JsonObject());
            var t = transports.getFirst();
            var call = t.requests.getLast();
            var meta = ((JsonObject) Objects.requireNonNull(call.params())).getAsJsonObject("_meta");
            assertEquals("2026-07-28", meta.get("io.modelcontextprotocol/protocolVersion").getAsString());
            assertEquals(new JsonObject(), meta.get("io.modelcontextprotocol/clientCapabilities"));
            assertEquals("jclaw", meta.getAsJsonObject("io.modelcontextprotocol/clientInfo").get("name").getAsString());
            var headers = t.headers.getLast();
            assertEquals("2026-07-28", headers.get("MCP-Protocol-Version"));
            assertEquals("tools/call", headers.get("Mcp-Method"));
            var encoded = Base64.getEncoder().encodeToString("échø".getBytes(StandardCharsets.UTF_8));
            assertEquals("=?base64?" + encoded + "?=", headers.get("Mcp-Name"), "a non-ASCII name travels base64");
        }
    }

    @Test
    void theHandshakeSendsTheVersionItsServerReturned() throws Exception {
        Script older = (_, r) -> switch (r.method()) {
            case "initialize" -> ok(r, "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{}}");
            default -> ok(r, TOOLS);
        };
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var opened = McpEraDetection.open("fake", McpServer.Protocol.HANDSHAKE, false,
                clients(transports, () -> older, Duration.ofSeconds(5)));
        try (var client = opened.client()) {
            var t = transports.getFirst();
            assertNull(t.headers.getFirst().get("MCP-Protocol-Version"), "initialize goes out before any version is agreed");
            assertEquals("2025-03-26", t.headers.getLast().get("MCP-Protocol-Version"));
            assertEquals("2025-03-26", client.protocolVersion());
            assertNull(t.headers.getLast().get("Mcp-Method"), "the handshake era has no Mcp-Method header");
        }
    }

    @Test
    void aResultThatIsNotCompleteFailsTheCall() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var opened = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false, clients(transports,
                () -> modernOnly((_, r) -> ok(r, "{\"resultType\":\"input_required\",\"inputRequests\":{}}")),
                Duration.ofSeconds(5)));
        try (var client = opened.client()) {
            var ex = assertThrows(McpException.class, () -> client.callTool("echo", new JsonObject()));
            assertTrue(ex.getMessage().contains("input_required"), ex.getMessage());
            assertEquals(McpClient.State.READY, client.state());
        }
    }

    @Test
    void aCompleteResultTypeIsAnOrdinaryResult() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var opened = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false, clients(transports,
                () -> modernOnly((_, r) -> ok(r, "{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}")),
                Duration.ofSeconds(5)));
        try (var client = opened.client()) {
            assertEquals("ok", client.callTool("echo", new JsonObject()).content());
        }
    }

    @Test
    void aModernCallRefusedForTheProtocolAsksForTheHandshake() throws Exception {
        assertCallRefusal(-32020, null, true);
        assertCallRefusal(-32022, "{\"supported\":[\"2025-11-25\"]}", true);
        // Still offering 2026-07-28, the server refused this request, not the protocol.
        assertCallRefusal(-32022, "{\"supported\":[\"2026-07-28\"]}", false);
        assertCallRefusal(-32602, null, false);
    }

    @Test
    void aMissingCapabilityFailsTheCallAloneNamingIt() throws Exception {
        var ex = assertCallRefusal(-32021, "{\"requiredCapabilities\":{\"elicitation\":{}}}", false);
        assertEquals(-32021, ex.code());
        assertTrue(ex.getMessage().contains("elicitation"), ex.getMessage());
    }

    private McpException assertCallRefusal(int code, @Nullable String data, boolean needsHandshake) throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var opened = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false, clients(transports,
                () -> modernOnly((_, r) -> err(r, code, "refused", data)), Duration.ofSeconds(5)));
        try (var client = opened.client()) {
            var ex = assertThrows(McpException.class, () -> client.callTool("echo", new JsonObject()));
            assertEquals(needsHandshake, ex instanceof McpException.NeedsHandshake, code + " " + data + ": " + ex);
            assertEquals(McpClient.State.READY, client.state(), "the connection stands");
            return ex;
        }
    }

    @SuppressWarnings("MustBeClosed") // McpEraDetection closes a client it discards; the test closes the one it returns
    private static McpEraDetection.ClientFactory clients(List<ScriptedTransport> made, Supplier<Script> server,
                                                         Duration handshakeTimeout) {
        return opening -> {
            var t = new ScriptedTransport(server.get());
            made.add(t);
            return new McpClient("fake", t, "0.0.1", handshakeTimeout, Duration.ofSeconds(5), opening);
        };
    }

    private static JsonRpc.Response ok(JsonRpc.Request r, String result) {
        return new JsonRpc.Response(r.id(), JsonParser.parseString(result), null);
    }

    private static JsonRpc.Response err(JsonRpc.Request r, int code, String message, @Nullable String data) {
        return new JsonRpc.Response(r.id(), null,
                new JsonRpc.Error(code, message, data == null ? null : JsonParser.parseString(data)));
    }

    /** How a scripted server answers one request; null stays silent. */
    @FunctionalInterface
    interface Script {
        JsonRpc.@Nullable Response answer(ScriptedTransport transport, JsonRpc.Request request);
    }

    /** A server in a transport: each request answered by its script on another thread, as a real one would. */
    static final class ScriptedTransport implements McpTransport {
        final List<String> methods = new CopyOnWriteArrayList<>();
        final List<JsonRpc.Request> requests = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
        private final Script script;
        private volatile Consumer<JsonRpc.Message> onMessage = _ -> {};
        private volatile boolean closed;

        ScriptedTransport(Script script) {
            this.script = script;
        }

        @Override
        public void start(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) {
            this.onMessage = onMessage;
        }

        @Override
        public void send(JsonRpc.Message msg) throws IOException {
            send(msg, Map.of());
        }

        @Override
        public synchronized void send(JsonRpc.Message msg, Map<String, String> requestHeaders) throws IOException {
            if (closed) throw new IOException("transport closed");
            switch (msg) {
                case JsonRpc.Request r -> {
                    methods.add(r.method());
                    requests.add(r);
                    headers.add(Map.copyOf(requestHeaders));
                    var reply = script.answer(this, r);
                    if (reply != null) Thread.startVirtualThread(() -> onMessage.accept(reply));
                }
                case JsonRpc.Notification n -> {
                    methods.add(n.method());
                    headers.add(Map.copyOf(requestHeaders));
                }
                case JsonRpc.Response _ -> { /* a reply to a server request */ }
            }
        }

        void deliver(JsonRpc.Message msg) {
            onMessage.accept(msg);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    // ==================== helpers ====================

    private void completeHandshake(McpClient client) throws Exception {
        var driver = Thread.ofVirtual().start(() -> {
            try {
                var init = transport.takeSent(JsonRpc.Request.class);
                var ok = new JsonObject();
                ok.addProperty("protocolVersion", McpClient.PROTOCOL_VERSION);
                ok.add("capabilities", new JsonObject());
                transport.deliver(new JsonRpc.Response(init.id(), ok, null));
                transport.takeSent(JsonRpc.Notification.class);  // initialized
                var list = transport.takeSent(JsonRpc.Request.class);
                transport.deliver(new JsonRpc.Response(list.id(), toolsResult(), null));
            } catch (InterruptedException _) {}
        });
        client.connect();
        driver.join(5000);
    }

    private static JsonObject toolsResult() {
        var result = new JsonObject();
        var arr = new JsonArray();
        var t = new JsonObject();
        t.addProperty("name", "echo");
        t.addProperty("description", "Echo");
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        t.add("inputSchema", schema);
        arr.add(t);
        result.add("tools", arr);
        return result;
    }

    /** In-memory transport that captures sent messages and lets the test deliver inbound ones. */
    static class FakeTransport implements McpTransport {
        private final LinkedBlockingQueue<JsonRpc.Message> sent = new LinkedBlockingQueue<>();
        private final List<JsonRpc.Message> sentLog = new ArrayList<>();
        private Consumer<JsonRpc.Message> onMessage;
        private Consumer<Throwable> onError;
        private volatile boolean closed;

        @Override
        public void start(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) {
            this.onMessage = onMessage;
            this.onError = onError;
        }

        @Override
        public synchronized void send(JsonRpc.Message msg) throws IOException {
            if (closed) throw new IOException("transport closed");
            sent.offer(msg);
            sentLog.add(msg);
        }

        @Override
        public void close() {
            closed = true;
        }

        @SuppressWarnings("unchecked")
        <T extends JsonRpc.Message> T takeSent(Class<T> expected) throws InterruptedException {
            var msg = sent.poll(5, TimeUnit.SECONDS);
            if (msg == null) throw new AssertionError("Timed out waiting for sent " + expected.getSimpleName());
            if (!expected.isInstance(msg)) {
                throw new AssertionError("Expected " + expected.getSimpleName() + " got " + msg);
            }
            return (T) msg;
        }

        void deliver(JsonRpc.Message msg) {
            if (onMessage != null) onMessage.accept(msg);
        }

        void tripError(Throwable t) {
            if (onError != null) onError.accept(t);
        }
    }
}
