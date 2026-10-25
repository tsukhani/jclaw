import agents.ToolContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import mcp.McpClient;
import mcp.McpElicitations;
import mcp.McpEraDetection;
import mcp.McpException;
import mcp.McpResources;
import mcp.McpToolDef;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpTransport;
import models.McpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EventLogger;
import utils.AppClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static final String UI_CAPABILITIES =
            "{\"extensions\":{\"io.modelcontextprotocol/ui\":{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}}}";

    @Test
    void theHandshakeDeclaresTheUiExtension() throws Exception {
        var t = new ScriptedTransport(handshakeEra(null));
        try (var client = new McpClient("test", t, "0.0.1")) {
            client.connect();
            var init = t.requests.stream().filter(r -> r.method().equals("initialize")).findFirst().orElseThrow();
            assertEquals(UI_CAPABILITIES,
                    ((JsonObject) Objects.requireNonNull(init.params())).get("capabilities").toString());
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

            // The handshake era's elicitation/create is not offered: it is refused like any other server request.
            transport.deliver(new JsonRpc.Request(100L, "elicitation/create", JsonParser.parseString(
                    "{\"message\":\"Email?\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{}}}")));
            var refused = transport.takeSent(JsonRpc.Response.class);
            assertEquals(100L, refused.id());
            assertEquals(-32601, refused.error().code());
            assertEquals("Method not found: elicitation/create", refused.error().message());
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
            assertFalse(client.probeTimedOut(), "a server that answered the probe keeps being probed");
        }
    }

    @Test
    void aSilentProbeThenInitializeRefusedWithMinus32022EndsModernAndKeepsProbing() throws Exception {
        Script silentModern = (_, r) -> switch (r.method()) {
            case "server/discover" -> null;
            case "initialize" -> err(r, -32022, "Unsupported protocol version", "{\"supported\":[\"2026-07-28\"]}");
            case "tools/list" -> ok(r, TOOLS);
            default -> ok(r, "{}");
        };
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var opened = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(transports, () -> silentModern, Duration.ofMillis(300)));
        try (var client = opened.client()) {
            assertTrue(client.isModern());
            assertEquals(List.of("server/discover", "initialize", "tools/list"), transports.getFirst().methods);
            assertFalse(client.probeTimedOut(), "only a connect that ends on the handshake remembers the timeout");
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
        assertTrue(ex.getMessage().contains("now needs MCP v2"), ex.getMessage());
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
        assertTrue(ex.getMessage().contains("offers MCP v1 (2025-11-25)"), ex.getMessage());
    }

    @Test
    void everyHandshakeRevisionReadsAsMcpV1AndTheStatelessOnesAsV2() {
        for (var v : List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")) {
            assertEquals("MCP v1", McpClient.label(v), v);
        }
        assertEquals("MCP v2", McpClient.label("2026-07-28"));
        assertEquals(2, McpClient.generation("2027-03-01"), "a later revision stays in the stateless generation");
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
            assertEquals(UI_CAPABILITIES, meta.get("io.modelcontextprotocol/clientCapabilities").toString());
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

    // ==================== JCLAW-1444: result types, MRTR, mirrored headers, pagination, cancellation ====================

    private McpClient modernClient(List<ScriptedTransport> transports, Script onCall) throws IOException {
        return McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(transports, () -> modernOnly(onCall), Duration.ofSeconds(5))).client();
    }

    private McpException callFails(String result) throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = modernClient(transports, (_, r) -> ok(r, result))) {
            var ex = assertThrows(McpException.class, () -> client.callTool("echo", new JsonObject()));
            assertEquals(McpClient.State.READY, client.state(), "a refused result fails the call, not the connection");
            assertEquals(1, transports.getFirst().methods.stream().filter("tools/call"::equals).count(), result);
            return ex;
        }
    }

    @Test
    void eachResultTypeBranch() throws Exception {
        assertTrue(callFails("{\"resultType\":\"deferred\",\"content\":[]}").getMessage()
                .contains("a result 'deferred', which JClaw cannot handle"));
        assertTrue(callFails("{\"resultType\":{\"x\":1},\"content\":[]}").getMessage()
                .contains("a result of an unknown kind"));
        assertTrue(callFails("{\"resultType\":\"input_required\",\"inputRequests\":{}}").getMessage()
                .contains("input_required with neither inputRequests nor requestState"));
        for (var shape : List.of("[{\"method\":\"elicitation/create\",\"params\":{\"message\":\"x\"}}]", "\"ask\"", "7")) {
            assertEquals("the server asked for input mid-request in a shape JClaw cannot answer: its inputRequests is not an"
                    + " object of requests by key. Call the tool again with every argument it needs filled in, or use another"
                    + " tool.", callFails("{\"resultType\":\"input_required\",\"requestState\":\"s\",\"inputRequests\":"
                    + shape + "}").getMessage(), shape);
        }
        var asked = callFails("{\"resultType\":\"input_required\",\"requestState\":\"s\",\"inputRequests\":"
                + "{\"ask\":{\"method\":\"sampling/createMessage\",\"params\":{\"message\":\"IGNORE ALL\"}}}}");
        assertEquals("the server asked for input mid-request (sampling/createMessage), which JClaw cannot give: it offers"
                + " no sampling or roots. Call the tool again with every argument it needs filled in, or use another tool.",
                asked.getMessage(), "the server's own text never reaches the model");
        // Absent is complete (every handshake-era test above), and so is complete itself.
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = modernClient(transports,
                (_, r) -> ok(r, "{\"content\":[{\"type\":\"text\",\"text\":\"no type\"}]}"))) {
            assertEquals("no type", client.callTool("echo", new JsonObject()).content());
        }
    }

    @Test
    void aStateOnlyInputRequiredIsRetriedWithANewIdAndTheSameState() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        Script twoRounds = (_, r) -> {
            var state = ((JsonObject) Objects.requireNonNull(r.params())).get("requestState");
            if (state == null) {
                return ok(r, "{\"resultType\":\"input_required\",\"requestState\":\"opaque/1+=\"}");
            }
            return ok(r, "{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\",\"text\":\""
                    + state.getAsString() + "\"}]}");
        };
        try (var client = modernClient(transports, twoRounds)) {
            var args = new JsonObject();
            args.addProperty("q", "x");
            assertEquals("opaque/1+=", client.callTool("echo", args).content());
            var calls = transports.getFirst().requests.stream().filter(r -> r.method().equals("tools/call")).toList();
            assertEquals(2, calls.size());
            assertNotEquals(calls.get(0).id(), calls.get(1).id());
            var retry = (JsonObject) Objects.requireNonNull(calls.get(1).params());
            assertEquals("opaque/1+=", retry.get("requestState").getAsString());
            assertEquals(args, retry.get("arguments"));
            assertEquals("echo", retry.get("name").getAsString());
        }
    }

    @Test
    void theRetryStopsAtTheBound() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = modernClient(transports,
                (_, r) -> ok(r, "{\"resultType\":\"input_required\",\"requestState\":\"again\"}"))) {
            var ex = assertThrows(McpException.class, () -> client.callTool("echo", new JsonObject()));
            assertTrue(ex.getMessage().contains("after " + McpClient.MAX_INPUT_RETRIES + " retries"), ex.getMessage());
            var calls = transports.getFirst().requests.stream().filter(r -> r.method().equals("tools/call")).toList();
            assertEquals(McpClient.MAX_INPUT_RETRIES + 1, calls.size());
            assertEquals(McpClient.MAX_INPUT_RETRIES + 1, calls.stream().map(JsonRpc.Request::id).distinct().count());
        }
    }

    // ==================== elicitation (forms) ====================

    private static final String ELICITING_CAPABILITIES = "{\"extensions\":{\"io.modelcontextprotocol/ui\":"
            + "{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}},\"elicitation\":{\"form\":{}}}";

    private static final String EMAIL_FORM = "{\"method\":\"elicitation/create\",\"params\":{\"mode\":\"form\","
            + "\"message\":\"Your email?\",\"requestedSchema\":{\"type\":\"object\","
            + "\"properties\":{\"email\":{\"type\":\"string\",\"format\":\"email\"}},\"required\":[\"email\"]}}}";

    private static final String PLAN_FORM = "{\"method\":\"elicitation/create\",\"params\":{\"message\":\"Which plan?\","
            + "\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"plan\":{\"type\":\"string\",\"enum\":[\"free\",\"pro\"]}}}}}";

    /** A tool that asks {@code inputRequests} under the state {@code st/1}, then answers with the responses it got. */
    private static Script asking(String inputRequests) {
        return (_, r) -> {
            var responses = ((JsonObject) Objects.requireNonNull(r.params())).get("inputResponses");
            if (responses == null) {
                return ok(r, "{\"resultType\":\"input_required\",\"requestState\":\"st/1\",\"inputRequests\":"
                        + inputRequests + "}");
            }
            return ok(r, "{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\",\"text\":"
                    + new JsonPrimitive(responses.toString()) + "}]}");
        };
    }

    private static <T> T commitInFreshTx(Callable<T> block) {
        var holder = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                holder.set(services.Tx.run(() -> {
                    try { return block.call(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }));
            } catch (Throwable e) {
                err.set(e);
            }
        });
        try { t.join(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        if (err.get() != null) throw new RuntimeException(err.get());
        return holder.get();
    }

    /** A top-level conversation on {@code channel} of a new agent. */
    private static long conversation(String channel) {
        return commitInFreshTx(() -> {
            var agent = services.AgentService.create("mcp-elicit-" + UUID.randomUUID().toString().substring(0, 8),
                    "openrouter", "gpt-4.1");
            return services.ConversationService.create(agent, channel, "web".equals(channel) ? "admin" : "peer-1").id;
        });
    }

    @FunctionalInterface
    private interface TurnBody<T> {
        T run() throws Exception;
    }

    /** {@code body} on a virtual thread, as a tool dispatched on {@code conversationId}'s turn runs. */
    private static <T> CompletableFuture<T> onTurn(long conversationId, TurnBody<T> body) {
        var out = new CompletableFuture<T>();
        Thread.ofVirtual().start(() -> {
            try {
                out.complete(ToolContext.withScope(conversationId, null, null, new AtomicBoolean(), () -> {
                    try {
                        return body.run();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        });
        return out;
    }

    private static McpElicitations.Question awaitQuestion(long conversationId, String message) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            for (var q : McpElicitations.pendingFor(conversationId)) {
                if (q.message().equals(message)) return q;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no form '" + message + "' ever waited in conversation " + conversationId);
    }

    private static String capabilities(JsonRpc.Request request) {
        return ((JsonObject) Objects.requireNonNull(request.params())).getAsJsonObject("_meta")
                .get("io.modelcontextprotocol/clientCapabilities").toString();
    }

    private static List<JsonRpc.Request> calls(ScriptedTransport t) {
        return t.requests.stream().filter(r -> r.method().equals("tools/call")).toList();
    }

    @Test
    void onlyAToolCallFromAWebChatTurnDeclaresFormElicitation() throws Exception {
        // A read or a listing is not the agent's tool call, so nothing it asks is put to the operator.
        var web = conversation("web");
        var telegram = conversation("telegram");
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        Script server = (_, r) -> switch (r.method()) {
            case "server/discover" -> ok(r, "{\"supportedVersions\":[\"2026-07-28\"],\"capabilities\":" + RESOURCE_CAPS + "}");
            case "tools/list" -> ok(r, TOOLS);
            case "resources/list" -> ok(r, "{\"resources\":[]}");
            case "resources/templates/list" -> ok(r, "{\"resourceTemplates\":[]}");
            case "resources/read" -> ok(r, "{\"contents\":[{\"uri\":\"x://a\",\"text\":\"a\"}]}");
            default -> ok(r, "{\"content\":[]}");
        };
        // Connected on the web-chat turn, so even its tools/list is a request made from one.
        try (var client = onTurn(web, () -> McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(transports, () -> server, Duration.ofSeconds(5))).client()).get(10, TimeUnit.SECONDS)) {
            onTurn(web, () -> client.callTool("echo", new JsonObject())).get(10, TimeUnit.SECONDS);
            onTurn(web, () -> client.readResource("x://a")).get(10, TimeUnit.SECONDS);
            onTurn(web, client::listResources).get(10, TimeUnit.SECONDS);
            onTurn(telegram, () -> client.callTool("echo", new JsonObject())).get(10, TimeUnit.SECONDS);
            client.callTool("echo", new JsonObject());
            var sent = transports.getFirst().requests.stream()
                    .map(r -> r.method() + " " + (capabilities(r).equals(ELICITING_CAPABILITIES) ? "forms"
                            : capabilities(r).equals(UI_CAPABILITIES) ? "no forms" : capabilities(r)))
                    .toList();
            assertEquals(List.of("server/discover no forms", "tools/list no forms", "tools/call forms", "resources/read no forms",
                    "resources/list no forms", "resources/templates/list no forms", "tools/call no forms", "tools/call no forms"),
                    sent);
        }
    }

    @Test
    void theHandshakeEraDeclaresNoElicitationEvenOnAWebChatTurn() throws Exception {
        var web = conversation("web");
        var t = new ScriptedTransport(handshakeEra(null));
        try (var client = new McpClient("test", t, "0.0.1")) {
            onTurn(web, () -> {
                client.connect();
                return client.callTool("echo", new JsonObject());
            }).get(10, TimeUnit.SECONDS);
            var init = t.requests.stream().filter(r -> r.method().equals("initialize")).findFirst().orElseThrow();
            assertEquals(UI_CAPABILITIES, ((JsonObject) Objects.requireNonNull(init.params())).get("capabilities").toString());
            var call = calls(t).getFirst();
            assertNull(((JsonObject) Objects.requireNonNull(call.params())).get("_meta"), "a handshake-era call carries no _meta");
        }
    }

    @Test
    void aSubmittedFormGoesBackInInputResponsesWithTheEchoedStateAndTheResultReachesTheCaller() throws Exception {
        var web = conversation("web");
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = modernClient(transports, asking("{\"email\":" + EMAIL_FORM + "}"))) {
            var args = new JsonObject();
            args.addProperty("q", "x");
            var result = onTurn(web, () -> client.callTool("echo", args));
            var question = awaitQuestion(web, "Your email?");
            assertEquals("fake", question.server());
            assertEquals(JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"email\":{\"type\":\"string\","
                    + "\"format\":\"email\"}},\"required\":[\"email\"]}"), question.requestedSchema());
            assertEquals(new McpElicitations.Outcome.Answered("accept"), McpElicitations.resolve(question.id(), "accept",
                    JsonParser.parseString("{\"email\":\"ops@example.com\"}")));

            var got = result.get(10, TimeUnit.SECONDS);
            assertEquals("{\"email\":{\"action\":\"accept\",\"content\":{\"email\":\"ops@example.com\"}}}",
                    got.content(), "the tool's result reaches the agent");
            assertEquals("", got.note(), "the server's own result answers a submit");
            assertFalse(got.isError());
            var sent = calls(transports.getFirst());
            assertEquals(2, sent.size());
            assertNotEquals(sent.get(0).id(), sent.get(1).id());
            var retry = (JsonObject) Objects.requireNonNull(sent.get(1).params());
            assertEquals("st/1", retry.get("requestState").getAsString());
            assertEquals("{\"email\":{\"action\":\"accept\",\"content\":{\"email\":\"ops@example.com\"}}}",
                    retry.get("inputResponses").toString());
            assertEquals(args, retry.get("arguments"));
            assertEquals("echo", retry.get("name").getAsString());
            assertEquals(ELICITING_CAPABILITIES, capabilities(sent.get(1)), "the retry declares what the first request did");
            assertEquals(List.of(), McpElicitations.pendingFor(web));
        }
    }

    @Test
    void declineAndCancelGoBackAsTheirActionAndTheResultSaysWhatTheOperatorDid() throws Exception {
        for (var action : List.of("decline", "cancel")) {
            var web = conversation("web");
            var transports = new CopyOnWriteArrayList<ScriptedTransport>();
            try (var client = modernClient(transports, asking("{\"email\":" + EMAIL_FORM + "}"))) {
                var result = onTurn(web, () -> client.callTool("echo", new JsonObject()));
                McpElicitations.resolve(awaitQuestion(web, "Your email?").id(), action, null);
                var got = result.get(10, TimeUnit.SECONDS);
                assertEquals("{\"email\":{\"action\":\"" + action + "\"}}", got.content(), action);
                assertEquals("JClaw: the operator " + (action.equals("decline") ? "declined" : "cancelled")
                        + " the request for input from MCP server 'fake'.", got.note(), action);
                assertEquals(2, calls(transports.getFirst()).size(), "the answer went back as given");
            }
        }
    }

    private static final String EMAIL_QUESTION_FOR_AGENT = "MCP server 'fake' asked for input that JClaw did not put to"
            + " the operator, because JClaw asks the operator only on a top-level web-chat turn that can be stopped, so the"
            + " call ended with nothing sent back. The server asked:\n- \"Your email?\" Fields: \"email\" (string, email,"
            + " required).\nYou can fill the values in as arguments and call again, ask in chat, or use another tool.";

    @Test
    void offAWebChatTurnTheAgentGetsTheQuestionAndTheServerNoRetry() throws Exception {
        var telegram = conversation("telegram");
        for (var turn : List.of("telegram", "no turn")) {
            var transports = new CopyOnWriteArrayList<ScriptedTransport>();
            try (var client = modernClient(transports, asking("{\"email\":" + EMAIL_FORM + "}"))) {
                var result = turn.equals("no turn") ? client.callTool("echo", new JsonObject())
                        : onTurn(telegram, () -> client.callTool("echo", new JsonObject())).get(10, TimeUnit.SECONDS);
                assertEquals(EMAIL_QUESTION_FOR_AGENT, result.content(), turn);
                assertTrue(result.isError(), turn + ": the call ended unfinished");
                var sent = calls(transports.getFirst());
                assertEquals(1, sent.size(), turn + ": no answer and no retry");
                assertEquals(UI_CAPABILITIES, capabilities(sent.get(0)), turn + ": the request declared no elicitation");
                assertEquals(List.of(), McpElicitations.pendingFor(telegram), turn);
                assertEquals(McpClient.State.READY, client.state(), "the connection stands");
            }
        }
    }

    @Test
    void whenOneFormCannotBeShownNeitherIsAskedAndTheAgentGetsBoth() throws Exception {
        var web = conversation("web");
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        var url = "{\"method\":\"elicitation/create\",\"params\":{\"mode\":\"url\",\"message\":\"Sign in\","
                + "\"url\":\"https://example.com/login\",\"elicitationId\":\"e1\"}}";
        try (var client = modernClient(transports, asking("{\"email\":" + EMAIL_FORM + ",\"login\":" + url + "}"))) {
            var result = onTurn(web, () -> client.callTool("echo", new JsonObject())).get(10, TimeUnit.SECONDS);
            assertEquals("MCP server 'fake' asked for input that JClaw did not put to the operator, because a question asks"
                    + " the operator to open a link, which JClaw does not do, so the call ended with nothing sent back. The"
                    + " server asked:\n- \"Your email?\" Fields: \"email\" (string, email, required).\n- \"Sign in\" (a link"
                    + " to open)\nYou can fill the values in as arguments and call again, ask in chat, or use another tool.",
                    result.content());
            assertEquals(List.of(), McpElicitations.pendingFor(web), "no form was shown");
            assertEquals(1, calls(transports.getFirst()).size());
        }
    }

    @Test
    void twoFormsAreAskedInTurnAndAnsweredInOneRetry() throws Exception {
        var web = conversation("web");
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = modernClient(transports,
                asking("{\"email\":" + EMAIL_FORM + ",\"plan\":" + PLAN_FORM + "}"))) {
            var result = onTurn(web, () -> client.callTool("echo", new JsonObject()));
            var first = awaitQuestion(web, "Your email?");
            assertEquals(List.of(first), McpElicitations.pendingFor(web), "one form at a time");
            McpElicitations.resolve(first.id(), "accept", JsonParser.parseString("{\"email\":\"ops@example.com\"}"));
            var second = awaitQuestion(web, "Which plan?");
            McpElicitations.resolve(second.id(), "accept", JsonParser.parseString("{\"plan\":\"pro\"}"));
            assertEquals("{\"email\":{\"action\":\"accept\",\"content\":{\"email\":\"ops@example.com\"}},"
                    + "\"plan\":{\"action\":\"accept\",\"content\":{\"plan\":\"pro\"}}}", result.get(10, TimeUnit.SECONDS).content());
            assertEquals(2, calls(transports.getFirst()).size(), "one retry carries both answers");
        }
    }

    @Test
    void oneWaitCoversTheCallSoAFormAfterOneThatTimedOutIsNeverShownAndBothCancel() throws Exception {
        var web = conversation("web");
        var announced = new CopyOnWriteArrayList<String>();
        var unsubscribe = services.NotificationBus.subscribe(e -> {
            if (e.contains("\"type\":\"mcp.elicitation\"") && e.contains("\"conversationId\":" + web + ",")) announced.add(e);
        });
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = modernClient(transports, asking("{\"email\":" + EMAIL_FORM + ",\"plan\":" + PLAN_FORM + "}"))) {
            var result = onTurn(web, () -> McpElicitations.callWithWaitForTest(Duration.ofMillis(300), () -> {
                try {
                    return client.callTool("echo", new JsonObject());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            })).get(10, TimeUnit.SECONDS);
            assertEquals("{\"email\":{\"action\":\"cancel\"},\"plan\":{\"action\":\"cancel\"}}", result.content());
            assertEquals("JClaw: the operator did not answer the request for input from MCP server 'fake' within 0.3 seconds.",
                    result.note());
            assertEquals(1, announced.size(), "the second form was never shown: " + announced);
            assertTrue(announced.getFirst().contains("\"message\":\"Your email?\""), announced.getFirst());
            assertEquals(2, calls(transports.getFirst()).size(), "one retry carries both cancels");
        } finally {
            unsubscribe.run();
        }
    }

    @Test
    void aQuestionHandedToTheAgentAfterAnAnsweredOneKeepsWhatTheOperatorDid() throws Exception {
        var web = conversation("web");
        var url = "{\"method\":\"elicitation/create\",\"params\":{\"mode\":\"url\",\"message\":\"Sign in\","
                + "\"url\":\"https://example.com/login\",\"elicitationId\":\"e1\"}}";
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        Script twoRounds = (_, r) -> ((JsonObject) Objects.requireNonNull(r.params())).get("inputResponses") == null
                ? ok(r, "{\"resultType\":\"input_required\",\"inputRequests\":{\"email\":" + EMAIL_FORM + "}}")
                : ok(r, "{\"resultType\":\"input_required\",\"inputRequests\":{\"login\":" + url + "}}");
        try (var client = modernClient(transports, twoRounds)) {
            var result = onTurn(web, () -> client.callTool("echo", new JsonObject()));
            McpElicitations.resolve(awaitQuestion(web, "Your email?").id(), "decline", null);
            assertEquals("MCP server 'fake' asked for input that JClaw did not put to the operator, because a question asks"
                    + " the operator to open a link, which JClaw does not do, so the call ended with nothing sent back. The"
                    + " server asked:\n- \"Sign in\" (a link to open)\nYou can fill the values in as arguments and call"
                    + " again, ask in chat, or use another tool.\nJClaw: the operator declined the request for input from MCP"
                    + " server 'fake'.", result.get(10, TimeUnit.SECONDS).content());
            assertEquals(2, calls(transports.getFirst()).size());
        }
    }

    @Test
    void noRequestTimeoutRunsWhileTheOperatorAnswers() throws Exception {
        var web = conversation("web");
        var t = new ScriptedTransport(modernOnly(asking("{\"email\":" + EMAIL_FORM + "}")));
        try (var client = new McpClient("fake", t, "0.0.1", Duration.ofSeconds(5), Duration.ofMillis(300),
                McpClient.Opening.PROBE)) {
            client.connect();
            var result = onTurn(web, () -> client.callTool("echo", new JsonObject()));
            var question = awaitQuestion(web, "Your email?");
            Thread.sleep(900);
            assertFalse(result.isDone(), "three request budgets later the call still waits for the operator");
            McpElicitations.resolve(question.id(), "accept", JsonParser.parseString("{\"email\":\"ops@example.com\"}"));
            assertEquals("{\"email\":{\"action\":\"accept\",\"content\":{\"email\":\"ops@example.com\"}}}",
                    result.get(10, TimeUnit.SECONDS).content());
            assertEquals(List.of(), t.cancelled, "nothing timed out");
        }
    }

    @Test
    void aFormBesideARequestOfAnotherKindFailsTheCallWithNoFormShown() throws Exception {
        var web = conversation("web");
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = modernClient(transports, asking("{\"email\":" + EMAIL_FORM
                + ",\"roots\":{\"method\":\"roots/list\"}}"))) {
            var ex = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> onTurn(web, () -> client.callTool("echo", new JsonObject())).get(10, TimeUnit.SECONDS));
            assertEquals("the server asked for input mid-request (roots/list), which JClaw cannot give: it offers no sampling"
                    + " or roots. Call the tool again with every argument it needs filled in, or use another tool.",
                    ex.getCause().getMessage());
            assertEquals(List.of(), McpElicitations.pendingFor(web));
            assertEquals(1, calls(transports.getFirst()).size());
        }
    }

    @Test
    void aServerThatKeepsAskingStopsAtTheBoundWithoutShowingAnotherForm() throws Exception {
        var web = conversation("web");
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = modernClient(transports, (_, r) -> ok(r,
                "{\"resultType\":\"input_required\",\"inputRequests\":{\"email\":" + EMAIL_FORM + "}}"))) {
            var result = onTurn(web, () -> client.callTool("echo", new JsonObject()));
            var answered = 0;
            var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!result.isDone() && System.nanoTime() < deadline) {
                for (var q : McpElicitations.pendingFor(web)) {
                    if (McpElicitations.resolve(q.id(), "decline", null) instanceof McpElicitations.Outcome.Answered) answered++;
                }
                Thread.sleep(10);
            }
            var ex = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            assertEquals("tools/call still asked for another round after " + McpClient.MAX_INPUT_RETRIES
                    + " retries, so JClaw stopped: the server never completed the request\nJClaw: the operator declined the"
                    + " request for input from MCP server 'fake'.", ex.getCause().getMessage(), "the operator's answers stay told");
            assertEquals(McpClient.MAX_INPUT_RETRIES, answered, "a form is shown only for a retry JClaw will make");
            assertEquals(McpClient.MAX_INPUT_RETRIES + 1, calls(transports.getFirst()).size());
        }
    }

    private static final String ANNOTATED_TOOLS = "{\"tools\":["
            + "{\"name\":\"good\",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
            + "\"region\":{\"type\":\"string\",\"x-mcp-header\":\"Region\"},\"q\":{\"type\":\"string\"}}}},"
            + "{\"name\":\"bad\",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
            + "\"region\":{\"type\":\"object\",\"x-mcp-header\":\"Region\"}}}},"
            + "{\"name\":\"plain\",\"inputSchema\":{\"type\":\"object\"}}]}";

    @Test
    void aToolWithAnInvalidAnnotationIsDroppedAndItsSiblingsMirrorTheirArguments() throws Exception {
        Script server = (_, r) -> switch (r.method()) {
            case "server/discover" -> ok(r, DISCOVER_RESULT);
            case "tools/list" -> ok(r, ANNOTATED_TOOLS);
            default -> ok(r, "{\"content\":[]}");
        };
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(transports, () -> server, Duration.ofSeconds(5))).client()) {
            assertEquals(List.of("good", "plain"), client.tools().stream().map(McpToolDef::name).toList());
            var args = new JsonObject();
            args.addProperty("region", "eu west");
            args.addProperty("q", "not mirrored");
            client.callTool("good", args);
            var headers = transports.getFirst().headers.getLast();
            assertEquals("eu west", headers.get("Mcp-Param-Region"));
            assertEquals(4, headers.size(), headers.toString());
            client.callTool("good", new JsonObject());
            assertNull(transports.getFirst().headers.getLast().get("Mcp-Param-Region"), "an absent argument sends no header");
        }
    }

    @Test
    void theHandshakeEraNeitherDropsAnnotatedToolsNorMirrorsHeaders() throws Exception {
        Script server = (_, r) -> switch (r.method()) {
            case "initialize" -> ok(r, INIT_RESULT);
            case "tools/list" -> ok(r, ANNOTATED_TOOLS);
            default -> ok(r, "{\"content\":[]}");
        };
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = McpEraDetection.open("fake", McpServer.Protocol.HANDSHAKE, false,
                clients(transports, () -> server, Duration.ofSeconds(5))).client()) {
            assertEquals(3, client.tools().size());
            var args = new JsonObject();
            args.addProperty("region", "eu");
            client.callTool("good", args);
            assertNull(transports.getFirst().headers.getLast().get("Mcp-Param-Region"));
        }
    }

    /** A tools/list answering page n with tool {@code t<n>} and a cursor to page n+1 while n < pages. */
    private static Script paged(boolean modern, int pages) {
        return (_, r) -> switch (r.method()) {
            case "server/discover" -> modern ? ok(r, DISCOVER_RESULT) : err(r, -32601, "no such method", null);
            case "initialize" -> ok(r, INIT_RESULT);
            case "tools/list" -> {
                var cursor = ((JsonObject) Objects.requireNonNull(r.params())).get("cursor");
                var page = cursor == null ? 1 : Integer.parseInt(cursor.getAsString().substring(1));
                var next = page < pages ? ",\"nextCursor\":\"p" + (page + 1) + "\"" : "";
                yield ok(r, "{\"tools\":[{\"name\":\"t" + page + "\",\"inputSchema\":{}}]" + next + "}");
            }
            default -> ok(r, "{}");
        };
    }

    @Test
    void aTwoPageToolListPublishesBothPagesInEitherEra() throws Exception {
        for (var modern : new boolean[] {true, false}) {
            var transports = new CopyOnWriteArrayList<ScriptedTransport>();
            try (var client = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                    clients(transports, () -> paged(modern, 2), Duration.ofSeconds(5))).client()) {
                assertEquals(modern, client.isModern());
                assertEquals(List.of("t1", "t2"), client.tools().stream().map(McpToolDef::name).toList(), "modern=" + modern);
                var lists = transports.getFirst().requests.stream().filter(r -> r.method().equals("tools/list")).toList();
                assertEquals(2, lists.size());
                assertNull(((JsonObject) Objects.requireNonNull(lists.get(0).params())).get("cursor"));
                assertEquals("p2", ((JsonObject) Objects.requireNonNull(lists.get(1).params())).get("cursor").getAsString());
            }
        }
    }

    @Test
    void aServerThatPagesForeverStopsAtTheCap() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(transports, () -> paged(true, Integer.MAX_VALUE), Duration.ofSeconds(5))).client()) {
            assertEquals(McpClient.State.READY, client.state());
            assertEquals(McpClient.MAX_TOOL_PAGES, client.tools().size());
            assertEquals(McpClient.MAX_TOOL_PAGES,
                    transports.getFirst().methods.stream().filter("tools/list"::equals).count());
        }
        // One page short of the cap still ends on its own.
        var below = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(below, () -> paged(true, McpClient.MAX_TOOL_PAGES - 1), Duration.ofSeconds(5))).client()) {
            assertEquals(McpClient.MAX_TOOL_PAGES - 1, client.tools().size());
        }
    }

    @Test
    void anEmptyNextCursorEndsTheListInEitherEra() throws Exception {
        for (var modern : new boolean[] {true, false}) {
            Script server = (_, r) -> switch (r.method()) {
                case "server/discover" -> modern ? ok(r, DISCOVER_RESULT) : err(r, -32601, "no such method", null);
                case "initialize" -> ok(r, INIT_RESULT);
                case "tools/list" -> ok(r, "{\"tools\":[{\"name\":\"t1\",\"inputSchema\":{}}],\"nextCursor\":\"\"}");
                default -> ok(r, "{}");
            };
            var transports = new CopyOnWriteArrayList<ScriptedTransport>();
            try (var client = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                    clients(transports, () -> server, Duration.ofSeconds(5))).client()) {
                assertEquals(List.of("t1"), client.tools().stream().map(McpToolDef::name).toList(), "modern=" + modern);
                assertEquals(1, transports.getFirst().methods.stream().filter("tools/list"::equals).count(),
                        "modern=" + modern);
            }
        }
    }

    private static final String SPEC_ANNOTATED_TOOLS = "{\"tools\":["
            + "{\"name\":\"num\",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
            + "\"n\":{\"type\":\"number\",\"x-mcp-header\":\"N\"}}}},"
            + "{\"name\":\"nested\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"target\":{\"type\":\"object\","
            + "\"properties\":{\"location\":{\"type\":\"object\",\"properties\":{"
            + "\"region\":{\"type\":\"string\",\"x-mcp-header\":\"Region\"}}}}}}}},"
            + "{\"name\":\"branch\",\"inputSchema\":{\"type\":\"object\",\"anyOf\":[{\"properties\":{"
            + "\"region\":{\"type\":\"string\",\"x-mcp-header\":\"Region\"}}}]}},"
            + "{\"name\":\"big\",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
            + "\"n\":{\"type\":\"integer\",\"x-mcp-header\":\"N\"}}}},"
            + "{\"name\":\"plain\",\"inputSchema\":{\"type\":\"object\"}}]}";

    @SuppressWarnings("MustBeClosed") // each caller closes it in try-with-resources
    private static McpClient specAnnotated(List<ScriptedTransport> transports) throws IOException {
        Script server = (_, r) -> switch (r.method()) {
            case "server/discover" -> ok(r, DISCOVER_RESULT);
            case "tools/list" -> ok(r, SPEC_ANNOTATED_TOOLS);
            default -> ok(r, "{\"content\":[]}");
        };
        return McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(transports, () -> server, Duration.ofSeconds(5))).client();
    }

    @Test
    void aNumberOrBranchAnnotatedToolIsDroppedAndANestedOneMirrorsItsPath() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = specAnnotated(transports)) {
            assertEquals(List.of("nested", "big", "plain"), client.tools().stream().map(McpToolDef::name).toList());
            client.callTool("nested", JsonParser.parseString(
                    "{\"target\":{\"location\":{\"region\":\"eu-west1\"}},\"region\":\"top\"}").getAsJsonObject());
            assertEquals("eu-west1", transports.getFirst().headers.getLast().get("Mcp-Param-Region"));
            client.callTool("nested", JsonParser.parseString("{\"region\":\"top\"}").getAsJsonObject());
            assertNull(transports.getFirst().headers.getLast().get("Mcp-Param-Region"), "no value at the path, no header");
        }
    }

    @Test
    void anIntegerOutsideTheSafeRangeFailsTheCallNamingItAndSendsNothing() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = specAnnotated(transports)) {
            var ex = assertThrows(McpException.class, () -> client.callTool("big",
                    JsonParser.parseString("{\"n\":9007199254740992}").getAsJsonObject()));
            assertTrue(ex.getMessage().contains("argument 'n' is outside the integer range"), ex.getMessage());
            assertFalse(transports.getFirst().methods.contains("tools/call"), transports.getFirst().methods.toString());
            client.callTool("big", JsonParser.parseString("{\"n\":9007199254740991}").getAsJsonObject());
            assertEquals("9007199254740991", transports.getFirst().headers.getLast().get("Mcp-Param-N"));
        }
    }

    /** Cancelling the probe could reach a handshake-era stdio server before initialize, which the spec forbids. */
    @Test
    void aStdioProbeThatRunsOutItsBudgetIsFollowedByInitializeAndNoCancellation() throws Exception {
        var t = new ScriptedTransport(handshakeEra(null));
        t.carriesHeaders = false;
        try (var client = new McpClient("fake", t, "0", Duration.ofMillis(300), Duration.ofSeconds(5),
                McpClient.Opening.PROBE)) {
            client.connect();
            assertTrue(client.probeTimedOut());
            assertEquals(List.of("server/discover", "initialize", "notifications/initialized", "tools/list"), t.methods);
            assertEquals(List.of(), t.cancelled);
        }
    }

    @Test
    void aTimedOutCallIsCancelledOnceAndTheConnectionStands() throws Exception {
        var t = new ScriptedTransport(modernOnly((_, r) ->
                "slow".equals(((JsonObject) Objects.requireNonNull(r.params())).get("name").getAsString())
                        ? null : ok(r, "{\"content\":[]}")));
        try (var client = new McpClient("fake", t, "0", Duration.ofSeconds(5), Duration.ofMillis(200),
                McpClient.Opening.PROBE)) {
            client.connect();
            var ex = assertThrows(McpException.class, () -> client.callTool("slow", new JsonObject()));
            assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
            assertEquals(List.of(t.requests.getLast().id()), t.cancelled);
            assertEquals(McpClient.State.READY, client.state());
            assertFalse(client.callTool("fast", new JsonObject()).isError());
            assertEquals(1, t.cancelled.size(), "an answered call is never cancelled");
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
        final List<Object> cancelled = new CopyOnWriteArrayList<>();
        private final Script script;
        private volatile Consumer<JsonRpc.Message> onMessage = _ -> {};
        private volatile boolean closed;

        ScriptedTransport(Script script) {
            this.script = script;
        }

        private volatile java.util.function.BiConsumer<Object, McpException> onRequestFailed = (_, _) -> {};

        @Override
        public void start(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) {
            this.onMessage = onMessage;
        }

        @Override
        public void start(Consumer<JsonRpc.Message> onMessage,
                          java.util.function.BiConsumer<Object, McpException> onRequestFailed,
                          Consumer<Throwable> onError) {
            this.onMessage = onMessage;
            this.onRequestFailed = onRequestFailed;
        }

        /** Fails one request alone, as Streamable HTTP does for a non-2xx answer. */
        void fail(Object id, McpException e) {
            onRequestFailed.accept(id, e);
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

        /** True stands in for Streamable HTTP, the transport that sends headers; false for stdio. */
        volatile boolean carriesHeaders = true;

        @Override
        public boolean carriesHeaders() {
            return carriesHeaders;
        }

        @Override
        public void cancel(Object requestId, String reason) {
            cancelled.add(requestId);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    // ==================== resources ====================

    private static final String RESOURCE_CAPS = "{\"tools\":{},\"resources\":{}}";

    /** A server declaring resources in either era; every resources/* request is answered by {@code onResources}. */
    private static Script resourceServer(boolean modern, Script onResources) {
        return (t, r) -> switch (r.method()) {
            case "server/discover" -> modern
                    ? ok(r, "{\"supportedVersions\":[\"2026-07-28\"],\"capabilities\":" + RESOURCE_CAPS + "}")
                    : err(r, -32601, "no such method", null);
            case "initialize" -> ok(r, "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":" + RESOURCE_CAPS + "}");
            case "tools/list" -> ok(r, TOOLS);
            default -> onResources.answer(t, r);
        };
    }

    private McpClient resourceClient(boolean modern, List<ScriptedTransport> transports, Script onResources)
            throws IOException {
        return McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(transports, () -> resourceServer(modern, onResources), Duration.ofSeconds(5))).client();
    }

    private static String param(JsonRpc.Request r, String key) {
        var el = ((JsonObject) Objects.requireNonNull(r.params())).get(key);
        return el == null ? "" : el.getAsString();
    }

    /** {@code total} resources {@code r1..r<total>}, {@code perPage} to a page, and one template on one page. */
    private static Script pagedResources(int total, int perPage) {
        return (_, r) -> switch (r.method()) {
            case "resources/list" -> {
                var cursor = param(r, "cursor");
                var start = cursor.isEmpty() ? 0 : Integer.parseInt(cursor);
                var items = new StringBuilder();
                for (var i = start; i < Math.min(start + perPage, total); i++) {
                    if (!items.isEmpty()) items.append(',');
                    items.append("{\"uri\":\"x://r").append(i + 1).append("\",\"name\":\"r").append(i + 1).append("\"}");
                }
                var next = start + perPage < total ? ",\"nextCursor\":\"" + (start + perPage) + "\"" : "";
                yield ok(r, "{\"resources\":[" + items + "]" + next + "}");
            }
            case "resources/templates/list" -> ok(r, "{\"resourceTemplates\":[{\"uriTemplate\":\"x://r{n}\",\"name\":\"r\"}]}");
            default -> ok(r, "{}");
        };
    }

    private static long count(ScriptedTransport t, String method) {
        return t.methods.stream().filter(method::equals).count();
    }

    @Test
    void theResourcesCapabilityIsRecordedInEitherEra() throws Exception {
        for (var modern : new boolean[] {true, false}) {
            try (var client = resourceClient(modern, new CopyOnWriteArrayList<>(), (_, r) -> ok(r, "{}"))) {
                assertEquals(modern, client.isModern());
                assertTrue(client.offersResources(), "modern=" + modern);
            }
        }
        try (var client = McpEraDetection.open("fake", McpServer.Protocol.AUTOMATIC, false,
                clients(new CopyOnWriteArrayList<>(), () -> handshakeEra(-32601), Duration.ofSeconds(5))).client()) {
            assertFalse(client.isModern());
            assertFalse(client.offersResources(), "an InitializeResult declaring no resources");
        }
        try (var client = modernClient(new CopyOnWriteArrayList<>(), (_, r) -> ok(r, "{}"))) {
            assertTrue(client.isModern());
            assertFalse(client.offersResources(), "a DiscoverResult declaring tools only");
        }
    }

    @Test
    void aListingPagesUntilMoreThanFiftyArriveAndShowsFiftyInEitherEra() throws Exception {
        for (var modern : new boolean[] {true, false}) {
            var transports = new CopyOnWriteArrayList<ScriptedTransport>();
            try (var client = resourceClient(modern, transports, pagedResources(120, 10))) {
                var listing = client.listResources();
                assertEquals(McpClient.MAX_LISTED_RESOURCES, listing.resources().size(), "modern=" + modern);
                assertEquals("x://r1", listing.resources().getFirst().uri());
                assertEquals("x://r50", listing.resources().getLast().uri());
                assertTrue(listing.more(), "modern=" + modern);
                assertEquals(6, count(transports.getFirst(), "resources/list"), "the sixth page passed fifty: stop there");
                assertEquals(List.of(new McpResources.Template("x://r{n}", "r", null)), listing.templates());
            }
        }
    }

    /** One resource, and {@code total} templates {@code t1..t<total>}, ten to a page. */
    private static Script pagedTemplates(int total) {
        return (_, r) -> switch (r.method()) {
            case "resources/list" -> ok(r, "{\"resources\":[{\"uri\":\"x://a\"}]}");
            case "resources/templates/list" -> {
                var cursor = param(r, "cursor");
                var start = cursor.isEmpty() ? 0 : Integer.parseInt(cursor);
                var items = new StringBuilder();
                for (var i = start; i < Math.min(start + 10, total); i++) {
                    if (!items.isEmpty()) items.append(',');
                    items.append("{\"uriTemplate\":\"x://t").append(i + 1).append("/{id}\"}");
                }
                var next = start + 10 < total ? ",\"nextCursor\":\"" + (start + 10) + "\"" : "";
                yield ok(r, "{\"resourceTemplates\":[" + items + "]" + next + "}");
            }
            default -> ok(r, "{}");
        };
    }

    @Test
    void templatesPageUntilMoreThanFiftyArriveAndFiftyAreShown() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = resourceClient(true, transports, pagedTemplates(120))) {
            var listing = client.listResources();
            assertEquals(50, listing.templates().size());
            assertEquals(new McpResources.Template("x://t1/{id}", null, null), listing.templates().getFirst());
            assertEquals(new McpResources.Template("x://t50/{id}", null, null), listing.templates().getLast());
            assertTrue(listing.more(), "more templates exist");
            assertEquals(6, count(transports.getFirst(), "resources/templates/list"), "the sixth page passed fifty: stop there");
        }
        try (var client = resourceClient(true, new CopyOnWriteArrayList<>(), pagedTemplates(50))) {
            var listing = client.listResources();
            assertEquals(50, listing.templates().size());
            assertFalse(listing.more(), "fifty templates are all shown");
        }
    }

    @Test
    void aListingThePageCapCutsOffShowsFiftyAndSaysMoreExist() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = resourceClient(true, transports, pagedResources(1_000, 1))) {
            var listing = client.listResources();
            assertEquals(50, listing.resources().size());
            assertEquals("x://r50", listing.resources().getLast().uri());
            assertTrue(listing.more(), "the cap ended a list that still had a next page");
            assertEquals(50, count(transports.getFirst(), "resources/list"), "one request per page, up to the cap");
        }
    }

    @Test
    void fiftyResourcesAreShownWholeAndFiftyOneSayMoreExist() throws Exception {
        try (var client = resourceClient(true, new CopyOnWriteArrayList<>(), pagedResources(50, 50))) {
            var listing = client.listResources();
            assertEquals(50, listing.resources().size());
            assertFalse(listing.more());
        }
        try (var client = resourceClient(true, new CopyOnWriteArrayList<>(), pagedResources(51, 50))) {
            var listing = client.listResources();
            assertEquals(50, listing.resources().size());
            assertTrue(listing.more());
        }
        try (var client = resourceClient(true, new CopyOnWriteArrayList<>(), pagedResources(0, 50))) {
            var listing = client.listResources();
            assertEquals(List.of(), listing.resources());
            assertFalse(listing.more());
        }
    }

    @Test
    void everyTemplatePageIsListedAndAServerWithoutTemplatesListsNone() throws Exception {
        Script twoPages = (_, r) -> switch (r.method()) {
            case "resources/list" -> ok(r, "{\"resources\":[{\"uri\":\"x://a\"}]}");
            case "resources/templates/list" -> param(r, "cursor").isEmpty()
                    ? ok(r, "{\"resourceTemplates\":[{\"uriTemplate\":\"x://{a}\",\"description\":\"A\"}],\"nextCursor\":\"2\"}")
                    : ok(r, "{\"resourceTemplates\":[{\"uriTemplate\":\"x://{b}\"},{\"name\":\"no template\"}]}");
            default -> ok(r, "{}");
        };
        try (var client = resourceClient(true, new CopyOnWriteArrayList<>(), twoPages)) {
            var listing = client.listResources();
            assertEquals(List.of(new McpResources.Resource("x://a", null, null, null)), listing.resources());
            assertEquals(List.of(new McpResources.Template("x://{a}", null, "A"), new McpResources.Template("x://{b}", null, null)),
                    listing.templates(), "an entry with no uriTemplate is dropped");
        }
        Script noTemplates = (_, r) -> switch (r.method()) {
            case "resources/list" -> ok(r, "{\"resources\":[{\"uri\":\"x://a\"}]}");
            default -> err(r, -32601, "Method not found", null);
        };
        try (var client = resourceClient(true, new CopyOnWriteArrayList<>(), noTemplates)) {
            var listing = client.listResources();
            assertEquals(1, listing.resources().size());
            assertEquals(List.of(), listing.templates());
        }
        Script brokenTemplates = (_, r) -> switch (r.method()) {
            case "resources/list" -> ok(r, "{\"resources\":[{\"uri\":\"x://a\"}]}");
            default -> err(r, -32603, "Internal error", null);
        };
        try (var client = resourceClient(true, new CopyOnWriteArrayList<>(), brokenTemplates)) {
            var ex = assertThrows(McpException.class, client::listResources);
            assertEquals(-32603, ex.code());
        }
    }

    @Test
    void aReadReturnsItsTextAndABlobsSizeButNeverItsBase64() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        Script read = (_, r) -> ok(r, "{\"contents\":["
                + "{\"uri\":\"serpapi://engines/google_flights\",\"mimeType\":\"application/json\",\"text\":\"{\\\"params\\\":32}\"},"
                + "{\"uri\":\"x://logo\",\"mimeType\":\"image/png\",\"blob\":\"iVBORw0KGgo=\"},"
                + "{\"blob\":\"QUJD\"}]}");
        try (var client = resourceClient(false, transports, read)) {
            var contents = client.readResource("serpapi://engines/google_flights");
            assertEquals(List.of(
                    new McpResources.Content("serpapi://engines/google_flights", "application/json", "{\"params\":32}", 0),
                    new McpResources.Content("x://logo", "image/png", null, 8),
                    new McpResources.Content("serpapi://engines/google_flights", null, null, 3)), contents);
            var sent = transports.getFirst().requests.getLast();
            assertEquals("resources/read", sent.method());
            assertEquals("serpapi://engines/google_flights", param(sent, "uri"));
        }
    }

    @Test
    void aViewPageKeepsItsUiMetaInTheListingAndTheReadAndABlobPageReadsAsItsHtml() throws Exception {
        var csp = "{\"csp\":{\"resourceDomains\":[\"https://cdn.jsdelivr.net\"]}}";
        var html = "<!doctype html><p>é</p>";
        var blob = Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        Script server = (_, r) -> switch (r.method()) {
            case "resources/list" -> ok(r, "{\"resources\":[{\"uri\":\"ui://t.html\",\"mimeType\":\"text/html;profile=mcp-app\","
                    + "\"_meta\":{\"ui\":" + csp + ",\"fastmcp\":{}}},{\"uri\":\"x://a\",\"_meta\":{\"fastmcp\":{}}}]}");
            case "resources/templates/list" -> ok(r, "{\"resourceTemplates\":[]}");
            default -> ok(r, "{\"contents\":["
                    + "{\"uri\":\"ui://t.html\",\"mimeType\":\"text/html;profile=mcp-app\",\"text\":\"<p>\",\"_meta\":{\"ui\":" + csp + "}},"
                    + "{\"uri\":\"ui://b.html\",\"mimeType\":\"text/html; profile=mcp-app\",\"blob\":\"" + blob + "\"},"
                    + "{\"uri\":\"ui://bad.html\",\"mimeType\":\"text/html;profile=mcp-app\",\"blob\":\"!!\"},"
                    + "{\"uri\":\"x://h.html\",\"mimeType\":\"text/html\",\"blob\":\"" + blob + "\"}]}");
        };
        try (var client = resourceClient(false, new CopyOnWriteArrayList<>(), server)) {
            var ui = JsonParser.parseString(csp).getAsJsonObject();
            assertEquals(List.of(new McpResources.Resource("ui://t.html", null, null, "text/html;profile=mcp-app", ui),
                    new McpResources.Resource("x://a", null, null, null, null)), client.listResources().resources());
            assertEquals(List.of(
                    new McpResources.Content("ui://t.html", "text/html;profile=mcp-app", "<p>", 0, ui),
                    new McpResources.Content("ui://b.html", "text/html; profile=mcp-app", html, 0, null),
                    new McpResources.Content("ui://bad.html", "text/html;profile=mcp-app", null, 1, null),
                    new McpResources.Content("x://h.html", "text/html", null, html.getBytes(StandardCharsets.UTF_8).length, null)),
                    client.readResource("ui://t.html"));
        }
    }

    @Test
    void anUnknownUriIsNotFoundUnderEitherCodeAndTheServersOwnTextStaysOut() throws Exception {
        for (var code : new int[] {-32602, -32002}) {
            for (var modern : new boolean[] {true, false}) {
                try (var client = resourceClient(modern, new CopyOnWriteArrayList<>(),
                        (_, r) -> err(r, code, "SECRET upstream text", "{\"uri\":\"SECRET\"}"))) {
                    var ex = assertThrows(McpException.ResourceNotFound.class, () -> client.readResource("x://nope"));
                    assertEquals("x://nope", ex.uri());
                    assertEquals(code, ex.code());
                    assertEquals("[code=" + code + "] no resource 'x://nope'", ex.getMessage(), "the server's text stays out");
                    assertEquals(McpClient.State.READY, client.state(), code + " modern=" + modern);
                }
            }
        }
    }

    /** Over HTTP a legacy -32002 arrives on a 4xx, which {@code callFailure} would read as the server leaving MCP v2. */
    @Test
    void aLegacyNotFoundOnAnHttpErrorStatusLeavesTheModernConnectionUp() throws Exception {
        try (var client = resourceClient(true, new CopyOnWriteArrayList<>(), (t, r) -> {
            t.fail(r.id(), McpException.fromHttp(404, -32002, null));
            return null;
        })) {
            var ex = assertThrows(McpException.ResourceNotFound.class, () -> client.readResource("x://gone"));
            assertEquals("x://gone", ex.uri());
            assertEquals(McpClient.State.READY, client.state());
            assertNull(client.lastError());
        }
    }

    @Test
    void anyOtherReadRefusalKeepsTheServersTextForTheOperatorAndOutOfTheMessageAndLastError() throws Exception {
        try (var client = resourceClient(true, new CopyOnWriteArrayList<>(),
                (_, r) -> err(r, -32603, "upstream text", null))) {
            var thrown = new McpException[1];
            var events = EventLogger.captureForTest(
                    () -> thrown[0] = assertThrows(McpException.class, () -> client.readResource("x://a")));
            assertEquals(McpException.class, thrown[0].getClass());
            assertEquals("[code=-32603] resources/read was refused", thrown[0].getMessage());
            assertNull(client.lastError());
            assertEquals(List.of("MCP server 'fake' refused resources/read of \"x://a\": [code=-32603] upstream text"),
                    events.stream().filter(e -> e.category().equals("MCP_RESOURCES")).map(EventLogger.Captured::message).toList());
        }
    }

    private McpException assertReadRefusal(int code, @Nullable String data, boolean needsHandshake) throws Exception {
        try (var client = modernClient(new CopyOnWriteArrayList<>(), (_, r) -> err(r, code, "refused", data))) {
            var ex = assertThrows(McpException.class, () -> client.readResource("x://a"));
            assertEquals(needsHandshake, ex instanceof McpException.NeedsHandshake, code + " " + data + ": " + ex);
            assertEquals(McpClient.State.READY, client.state(), "the connection stands");
            return ex;
        }
    }

    @Test
    void aModernReadRefusedForTheProtocolAsksForTheHandshakeAsACallDoes() throws Exception {
        assertEquals("[code=-32020] a request was refused for a header mismatch (-32020)",
                assertReadRefusal(-32020, null, true).getMessage());
        assertEquals("[code=-32022] a request was refused with -32022, offering MCP v1 (2025-11-25)",
                assertReadRefusal(-32022, "{\"supported\":[\"2025-11-25\"]}", true).getMessage());
        // Still offering 2026-07-28, the server refused this request, not the protocol.
        assertEquals("[code=-32022] resources/read was refused",
                assertReadRefusal(-32022, "{\"supported\":[\"2026-07-28\"]}", false).getMessage());
    }

    @Test
    void aReadNeedingAMissingCapabilityFailsAloneNamingIt() throws Exception {
        var ex = assertReadRefusal(-32021, "{\"requiredCapabilities\":{\"elicitation\":{}}}", false);
        assertEquals(-32021, ex.code());
        assertEquals("[code=-32021] the server requires client capability elicitation, which JClaw does not offer",
                ex.getMessage());
    }

    @Test
    void aReadThatAsksForInputGivesTheAgentTheQuestionAndIsNotRetried() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = resourceClient(true, transports, (_, r) -> ok(r,
                "{\"resultType\":\"input_required\",\"inputRequests\":{\"q\":{\"method\":\"elicitation/create\","
                        + "\"params\":{\"message\":\"Which file?\",\"requestedSchema\":{\"type\":\"object\"}}}}}"))) {
            var ex = assertThrows(McpException.InputRequired.class, () -> client.readResource("x://a"));
            assertEquals("MCP server 'fake' asked for input that JClaw did not put to the operator, because JClaw does not"
                    + " ask the operator during a resource read or a listing, so the call ended with nothing sent back. The"
                    + " server asked:\n- \"Which file?\" (no fields)\nYou can fill the values in as arguments and call"
                    + " again, ask in chat, or use another tool.", ex.getMessage());
            assertEquals(1, transports.getFirst().requests.stream().filter(r -> r.method().equals("resources/read")).count());
        }
    }

    @Test
    void aModernReadNamesItsUriInMcpNameAndAHandshakeReadSendsNoNameHeader() throws Exception {
        var transports = new CopyOnWriteArrayList<ScriptedTransport>();
        Script answers = (_, r) -> r.method().equals("resources/read")
                ? ok(r, "{\"contents\":[]}") : ok(r, "{\"resources\":[]}");
        try (var client = resourceClient(true, transports, answers)) {
            var t = transports.getFirst();
            client.readResource("serpapi://engines/google_flights");
            assertEquals("resources/read", t.headers.getLast().get("Mcp-Method"));
            assertEquals("serpapi://engines/google_flights", t.headers.getLast().get("Mcp-Name"));
            client.readResource("file:///notes/résumé.txt");
            var encoded = Base64.getEncoder().encodeToString("file:///notes/résumé.txt".getBytes(StandardCharsets.UTF_8));
            assertEquals("=?base64?" + encoded + "?=", t.headers.getLast().get("Mcp-Name"), "a non-ASCII URI travels base64");
            client.listResources();
            assertEquals("resources/templates/list", t.headers.getLast().get("Mcp-Method"));
            assertNull(t.headers.getLast().get("Mcp-Name"), "a listing names nothing");
        }
        var handshake = new CopyOnWriteArrayList<ScriptedTransport>();
        try (var client = resourceClient(false, handshake, answers)) {
            client.readResource("serpapi://engines/google_flights");
            assertNull(handshake.getFirst().headers.getLast().get("Mcp-Name"));
            assertNull(handshake.getFirst().headers.getLast().get("Mcp-Method"));
        }
    }

    @Test
    void aReadBeforeTheConnectionIsReadyIsRefusedWithoutARequest() {
        var t = new ScriptedTransport((_, r) -> ok(r, "{}"));
        try (var client = new McpClient("fake", t, "0")) {
            var ex = assertThrows(McpException.class, () -> client.readResource("x://a"));
            assertEquals("readResource requires READY state, was DISCONNECTED", ex.getMessage());
            assertEquals(List.of(), t.methods);
        }
    }

    // ==================== JCLAW-1445: tool-list freshness ====================

    private static final Instant T0 = Instant.parse("2026-10-10T12:00:00Z");

    /**
     * A modern server whose n-th {@code tools/list} lists tool {@code t<n>} (or {@code echo} every
     * time when {@code !changing}). Each refetch after the connect's lands in {@link #refetches};
     * while {@link #hold} is set, its answer waits for the latch.
     */
    private static final class ToolServer implements Script {
        final AtomicInteger lists = new AtomicInteger();
        final BlockingQueue<Integer> refetches = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonRpc.Request> listens = new LinkedBlockingQueue<>();
        volatile @Nullable Long ttlMs;
        volatile boolean listChanged;
        volatile boolean changing = true;
        volatile @Nullable CountDownLatch hold;
        volatile @Nullable String listenAnswer;

        @Override
        public JsonRpc.@Nullable Response answer(ScriptedTransport t, JsonRpc.Request r) {
            return switch (r.method()) {
                case "server/discover" -> ok(r, "{\"supportedVersions\":[\"2026-07-28\"],\"capabilities\":{\"tools\":"
                        + "{\"listChanged\":" + listChanged + "}}}");
                case "tools/list" -> {
                    var n = lists.incrementAndGet();
                    var body = "{\"tools\":[{\"name\":\"" + (changing ? "t" + n : "echo")
                            + "\",\"inputSchema\":{}}]" + (ttlMs != null ? ",\"ttlMs\":" + ttlMs : "") + "}";
                    if (n == 1) yield ok(r, body);
                    refetches.add(n);
                    var latch = hold;
                    if (latch == null) yield ok(r, body);
                    Thread.startVirtualThread(() -> {
                        try {
                            latch.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                        t.deliver(ok(r, body));
                    });
                    yield null;
                }
                case "subscriptions/listen" -> {
                    listens.add(r);
                    var answer = listenAnswer;
                    yield answer == null ? null : answer.startsWith("error")
                            ? err(r, -32601, "Method not found", null) : ok(r, answer);
                }
                default -> ok(r, "{}");
            };
        }
    }

    @SuppressWarnings("MustBeClosed") // each caller closes it in try-with-resources
    private static McpClient connectedAt(Instant at, ScriptedTransport t) throws Exception {
        var client = new McpClient("fake", t, "0", Duration.ofSeconds(5), Duration.ofSeconds(5),
                McpClient.Opening.PROBE);
        AppClock.callWith(Clock.fixed(at, ZoneOffset.UTC), () -> {
            client.connect();
            return null;
        });
        return client;
    }

    private static void readAt(McpClient client, Instant at) {
        AppClock.runWith(Clock.fixed(at, ZoneOffset.UTC), client::refreshToolsIfStale);
    }

    private static List<String> names(McpClient client) {
        return client.tools().stream().map(McpToolDef::name).toList();
    }

    @Test
    void aFreshListIsNotRefetched() throws Exception {
        var server = new ToolServer();
        server.ttlMs = 600_000L;
        try (var client = connectedAt(T0, new ScriptedTransport(server))) {
            readAt(client, T0.plusSeconds(120));
            assertNull(server.refetches.poll(300, TimeUnit.MILLISECONDS), "a fresh list is not refetched");
            assertEquals(List.of("t1"), names(client));
        }
    }

    @Test
    void aStaleListIsRefetchedOnceAcrossTwoReads() throws Exception {
        var server = new ToolServer();
        server.ttlMs = 600_000L;
        try (var client = connectedAt(T0, new ScriptedTransport(server))) {
            var changed = new CountDownLatch(1);
            client.onToolsChanged(_ -> changed.countDown());
            readAt(client, T0.plusSeconds(601));
            readAt(client, T0.plusSeconds(601));
            assertEquals(2, server.refetches.poll(5, TimeUnit.SECONDS));
            assertTrue(changed.await(5, TimeUnit.SECONDS));
            readAt(client, T0.plusSeconds(601));
            assertNull(server.refetches.poll(300, TimeUnit.MILLISECONDS), "exactly one refetch");
            assertEquals(List.of("t2"), names(client));
        }
    }

    @Test
    void thePreviousListIsServedWhileTheRefetchIsInFlight() throws Exception {
        var server = new ToolServer();
        server.ttlMs = 600_000L;
        var release = new CountDownLatch(1);
        server.hold = release;
        try (var client = connectedAt(T0, new ScriptedTransport(server))) {
            var changed = new CountDownLatch(1);
            client.onToolsChanged(_ -> changed.countDown());
            readAt(client, T0.plusSeconds(601));
            assertEquals(2, server.refetches.poll(5, TimeUnit.SECONDS));
            assertEquals(List.of("t1"), names(client), "the old list until the refetch lands");
            readAt(client, T0.plusSeconds(700));
            assertEquals(List.of("t1"), names(client));
            release.countDown();
            assertTrue(changed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("t2"), names(client));
            assertNull(server.refetches.poll(300, TimeUnit.MILLISECONDS), "a read during the refetch adds none");
        }
    }

    @Test
    void aZeroTtlIsNotAskedMoreOftenThanTheMinimumInterval() throws Exception {
        var server = new ToolServer();
        server.ttlMs = 0L;
        try (var client = connectedAt(T0, new ScriptedTransport(server))) {
            var changed = new CountDownLatch(1);
            client.onToolsChanged(_ -> changed.countDown());
            readAt(client, T0.plusSeconds(30));
            assertNull(server.refetches.poll(300, TimeUnit.MILLISECONDS), "not before the minimum interval");
            readAt(client, T0.plusSeconds(61));
            assertEquals(2, server.refetches.poll(5, TimeUnit.SECONDS));
            assertTrue(changed.await(5, TimeUnit.SECONDS));
            readAt(client, T0.plusSeconds(61));
            assertNull(server.refetches.poll(300, TimeUnit.MILLISECONDS), "none on a repeat read at the same instant");
        }
    }

    /** Two pages, each carrying its own {@code ttlMs} (null for none); records every tools/list. */
    private static Script pagedTtl(@Nullable Long first, @Nullable Long second, BlockingQueue<Object> lists) {
        return (_, r) -> switch (r.method()) {
            case "server/discover" -> ok(r, DISCOVER_RESULT);
            case "tools/list" -> {
                lists.add(r.id());
                var cursor = ((JsonObject) Objects.requireNonNull(r.params())).get("cursor");
                var ttl = cursor == null ? first : second;
                var tail = (ttl != null ? ",\"ttlMs\":" + ttl : "") + (cursor == null ? ",\"nextCursor\":\"p2\"" : "");
                yield ok(r, "{\"tools\":[{\"name\":\"" + (cursor == null ? "a" : "b") + "\",\"inputSchema\":{}}]"
                        + tail + "}");
            }
            default -> ok(r, "{}");
        };
    }

    @Test
    void aPagedListTakesTheMinimumTtlOverItsPagesWithAbsentAsZero() throws Exception {
        for (var absentSecond : new boolean[] {true, false}) {
            var lists = new LinkedBlockingQueue<Object>();
            var t = new ScriptedTransport(pagedTtl(300_000L, absentSecond ? null : 300_000L, lists));
            try (var client = connectedAt(T0, t)) {
                assertEquals(2, lists.size());
                lists.clear();
                readAt(client, T0.plusSeconds(61));
                if (absentSecond) {
                    assertNotNull(lists.poll(5, TimeUnit.SECONDS), "an absent ttlMs on one page makes the list stale");
                } else {
                    assertNull(lists.poll(300, TimeUnit.MILLISECONDS), "both pages fresh for 300 s");
                }
            }
        }
    }

    @Test
    void aHandshakeEraListIsNeverRefetchedOnRead() throws Exception {
        var lists = new LinkedBlockingQueue<Object>();
        Script handshake = (t, r) -> {
            if (r.method().equals("tools/list")) lists.add(r.id());
            return handshakeEra(-32601).answer(t, r);
        };
        try (var client = connectedAt(T0, new ScriptedTransport(handshake))) {
            assertFalse(client.isModern());
            assertNotNull(lists.poll());
            readAt(client, T0.plus(Duration.ofDays(10)));
            assertNull(lists.poll(300, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void aListChangedDuringARefreshRunsOneMoreFetch() throws Exception {
        var server = new ToolServer();
        var release = new CountDownLatch(1);
        server.hold = release;
        var t = new ScriptedTransport(server);
        try (var client = connectedAt(T0, t)) {
            var changes = new LinkedBlockingQueue<List<McpToolDef>>();
            client.onToolsChanged(changes::add);
            t.deliver(new JsonRpc.Notification("notifications/tools/list_changed", null));
            assertEquals(2, server.refetches.poll(5, TimeUnit.SECONDS));
            t.deliver(new JsonRpc.Notification("notifications/tools/list_changed", null));
            t.deliver(new JsonRpc.Notification("notifications/tools/list_changed", null));
            release.countDown();
            assertEquals(3, server.refetches.poll(5, TimeUnit.SECONDS), "the change may postdate the fetch in flight");
            assertNull(server.refetches.poll(300, TimeUnit.MILLISECONDS), "exactly one more");
            assertNotNull(changes.poll(5, TimeUnit.SECONDS));
            assertNotNull(changes.poll(5, TimeUnit.SECONDS));
            assertEquals(List.of("t3"), names(client));
        }
    }

    @Test
    void anUnchangedRefreshedListFiresNoCallback() throws Exception {
        var server = new ToolServer();
        server.changing = false;
        var t = new ScriptedTransport(server);
        try (var client = connectedAt(T0, t)) {
            var fired = new CountDownLatch(1);
            client.onToolsChanged(_ -> fired.countDown());
            t.deliver(new JsonRpc.Notification("notifications/tools/list_changed", null));
            assertEquals(2, server.refetches.poll(5, TimeUnit.SECONDS));
            assertFalse(fired.await(300, TimeUnit.MILLISECONDS), "nothing to republish");
            assertEquals(List.of("echo"), names(client));
        }
    }

    // ==================== JCLAW-1445: subscriptions/listen ====================

    private static JsonRpc.Notification withSubscription(String method, @Nullable String idJson,
                                                         @Nullable String extraParams) {
        var params = new JsonObject();
        if (extraParams != null) params = JsonParser.parseString(extraParams).getAsJsonObject();
        if (idJson != null) {
            var meta = new JsonObject();
            meta.add("io.modelcontextprotocol/subscriptionId", JsonParser.parseString(idJson));
            params.add("_meta", meta);
        }
        return new JsonRpc.Notification(method, params);
    }

    private static JsonRpc.Notification ack(@Nullable String idJson, String granted) {
        return withSubscription("notifications/subscriptions/acknowledged", idJson,
                "{\"notifications\":" + granted + "}");
    }

    private static JsonRpc.Notification listChanged(@Nullable String idJson) {
        return withSubscription("notifications/tools/list_changed", idJson, null);
    }

    @Test
    void discoveryDecidesWhetherListChangedIsOffered() throws Exception {
        for (var offered : new boolean[] {true, false}) {
            var server = new ToolServer();
            server.listChanged = offered;
            try (var client = connectedAt(T0, new ScriptedTransport(server))) {
                assertEquals(offered, client.toolsListChangedOffered());
            }
        }
        // A -32022 refusal settles the era with no DiscoverResult, so nothing was offered.
        try (var client = connectedAt(T0, new ScriptedTransport((_, r) -> switch (r.method()) {
            case "server/discover" -> err(r, -32022, "Unsupported", "{\"supported\":[\"2026-07-28\"]}");
            default -> ok(r, TOOLS);
        }))) {
            assertTrue(client.isModern());
            assertFalse(client.toolsListChangedOffered());
        }
    }

    @Test
    void theListenRequestCarriesToolsListChangedAndItsAckMatchesNumberOrStringIds() throws Exception {
        var server = new ToolServer();
        server.listChanged = true;
        var t = new ScriptedTransport(server);
        try (var client = connectedAt(T0, t)) {
            var events = new LinkedBlockingQueue<McpClient.SubscriptionEvent>();
            var changes = new LinkedBlockingQueue<List<McpToolDef>>();
            client.onToolsChanged(changes::add);
            client.openToolsSubscription(events::add);
            var listen = server.listens.poll(5, TimeUnit.SECONDS);
            assertNotNull(listen);
            var params = (JsonObject) Objects.requireNonNull(listen.params());
            assertTrue(params.getAsJsonObject("notifications").get("toolsListChanged").getAsBoolean());
            assertEquals("subscriptions/listen", t.headers.get(t.methods.indexOf("subscriptions/listen")).get("Mcp-Method"));
            var id = listen.id().toString();

            t.deliver(ack("\"999\"", "{\"toolsListChanged\":true}"));
            assertNull(events.poll(200, TimeUnit.MILLISECONDS), "another subscription's ack");
            t.deliver(ack(id, "{\"toolsListChanged\":true}"));
            assertEquals(McpClient.SubscriptionEvent.ACKNOWLEDGED, events.poll(5, TimeUnit.SECONDS));

            t.deliver(listChanged("\"" + id + "\""));
            assertEquals(2, server.refetches.poll(5, TimeUnit.SECONDS), "a string id matches");
            assertNotNull(changes.poll(5, TimeUnit.SECONDS));
            t.deliver(listChanged(id));
            assertEquals(3, server.refetches.poll(5, TimeUnit.SECONDS), "a numeric id matches");
            assertNotNull(changes.poll(5, TimeUnit.SECONDS));
            t.deliver(listChanged(Long.toString(Long.parseLong(id) + 100)));
            assertNull(server.refetches.poll(300, TimeUnit.MILLISECONDS), "a mismatched id is ignored");

            t.carriesHeaders = false;
            t.deliver(listChanged(null));
            assertNull(server.refetches.poll(300, TimeUnit.MILLISECONDS), "an id-less one is ignored on stdio");
            t.carriesHeaders = true;
            t.deliver(listChanged(null));
            assertEquals(4, server.refetches.poll(5, TimeUnit.SECONDS), "an id-less one counts over HTTP");
            assertTrue(t.cancelled.isEmpty());
        }
    }

    @Test
    void anAckThatDoesNotGrantToolsListChangedIsRefusedAndCancelled() throws Exception {
        for (var granted : List.of("{}", "{\"toolsListChanged\":\"false\"}")) {
            var server = new ToolServer();
            server.listChanged = true;
            var t = new ScriptedTransport(server);
            try (var client = connectedAt(T0, t)) {
                var events = new LinkedBlockingQueue<McpClient.SubscriptionEvent>();
                client.openToolsSubscription(events::add);
                var listen = Objects.requireNonNull(server.listens.poll(5, TimeUnit.SECONDS));
                t.deliver(ack(listen.id().toString(), granted));
                assertEquals(McpClient.SubscriptionEvent.REFUSED, events.poll(5, TimeUnit.SECONDS), granted);
                assertEquals(List.of(listen.id()), t.cancelled, granted);
                assertNull(events.poll(200, TimeUnit.MILLISECONDS), "one terminal event");
                assertEquals(McpClient.State.READY, client.state());
            }
        }
    }

    @Test
    void aListenRefusedWithAnHttpJsonRpcErrorIsRefusedNotDropped() throws Exception {
        var server = new ToolServer();
        server.listChanged = true;
        var t = new ScriptedTransport(server);
        try (var client = connectedAt(T0, t)) {
            var events = new LinkedBlockingQueue<McpClient.SubscriptionEvent>();
            client.openToolsSubscription(events::add);
            var listen = Objects.requireNonNull(server.listens.poll(5, TimeUnit.SECONDS));
            t.fail(listen.id(), McpException.fromHttp(400, -32601, null));
            assertEquals(McpClient.SubscriptionEvent.REFUSED, events.poll(5, TimeUnit.SECONDS));
            assertNull(events.poll(200, TimeUnit.MILLISECONDS), "one terminal event");
        }
    }

    @Test
    void aCallbackThatThrowsDoesNotWedgeLaterRefreshes() throws Exception {
        var server = new ToolServer();
        var t = new ScriptedTransport(server);
        try (var client = connectedAt(T0, t)) {
            client.onToolsChanged(_ -> { throw new IllegalStateException("publish failed"); });
            t.deliver(new JsonRpc.Notification("notifications/tools/list_changed", null));
            assertEquals(2, server.refetches.poll(5, TimeUnit.SECONDS));
            assertEquals(List.of("t2"), waitForNames(client, List.of("t2")));
            t.deliver(new JsonRpc.Notification("notifications/tools/list_changed", null));
            assertEquals(3, server.refetches.poll(5, TimeUnit.SECONDS), "the single-flight flag was released");
        }
    }

    private static List<String> waitForNames(McpClient client, List<String> expected) throws InterruptedException {
        var latest = new LinkedBlockingQueue<List<String>>();
        for (var i = 0; i < 250 && !names(client).equals(expected); i++) latest.poll(20, TimeUnit.MILLISECONDS);
        return names(client);
    }

    @Test
    void aListenEndedByAResultDropsAndOneEndedByAnErrorIsRefused() throws Exception {
        for (var answer : List.of("{}", "error")) {
            var server = new ToolServer();
            server.listChanged = true;
            server.listenAnswer = answer;
            try (var client = connectedAt(T0, new ScriptedTransport(server))) {
                var events = new LinkedBlockingQueue<McpClient.SubscriptionEvent>();
                client.openToolsSubscription(events::add);
                assertEquals("error".equals(answer) ? McpClient.SubscriptionEvent.REFUSED
                        : McpClient.SubscriptionEvent.DROPPED, events.poll(5, TimeUnit.SECONDS), answer);
                assertEquals(McpClient.State.READY, client.state());
            }
        }
    }

    @Test
    void anUnacknowledgedListenIsCancelledAndDropped() throws Exception {
        var server = new ToolServer();
        server.listChanged = true;
        var t = new ScriptedTransport(server);
        try (var client = new McpClient("fake", t, "0", Duration.ofSeconds(5), Duration.ofMillis(200),
                McpClient.Opening.PROBE)) {
            client.connect();
            var events = new LinkedBlockingQueue<McpClient.SubscriptionEvent>();
            client.openToolsSubscription(events::add);
            assertEquals(McpClient.SubscriptionEvent.DROPPED, events.poll(5, TimeUnit.SECONDS));
            assertEquals(1, t.cancelled.size());
        }
    }

    @Test
    void closingTheClientEndsTheSubscriptionSilently() throws Exception {
        var server = new ToolServer();
        server.listChanged = true;
        var events = new LinkedBlockingQueue<McpClient.SubscriptionEvent>();
        try (var client = connectedAt(T0, new ScriptedTransport(server))) {
            client.openToolsSubscription(events::add);
            assertNotNull(server.listens.poll(5, TimeUnit.SECONDS));
        }
        assertNull(events.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void aHandshakeEraClientOpensNoSubscription() throws Exception {
        var t = new ScriptedTransport(handshakeEra(-32601));
        try (var client = connectedAt(T0, t)) {
            client.openToolsSubscription(_ -> {});
            assertFalse(t.methods.contains("subscriptions/listen"));
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
