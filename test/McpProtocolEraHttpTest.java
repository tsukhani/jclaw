import com.google.errorprone.annotations.MustBeClosed;
import com.google.gson.JsonObject;
import mcp.McpClient;
import mcp.McpEraDetection;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpStreamableHttpTransport;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import models.McpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * JCLAW-1443 over Streamable HTTP: how each kind of server answers the {@code server/discover}
 * probe decides the era, and a modern connection carries the 2026-07-28 headers and no session.
 */
class McpProtocolEraHttpTest extends UnitTest {

    private static final String DISCOVER_RESULT = "{\"supportedVersions\":[\"2026-07-28\"],\"capabilities\":{}}";
    private static final String TOOLS = "{\"tools\":[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]}";

    private MockWebServer server;
    /** Every request the server saw, in order, with its headers. */
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    private record Seen(String method, @Nullable String version, @Nullable String mcpMethod,
                        @Nullable String mcpName, @Nullable String session, String body) {}

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private void serve(BiFunction<String, Object, MockResponse> answer) {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                var body = request.getBody().utf8();
                var msg = JsonRpc.decode(body);
                var h = request.getHeaders();
                var method = switch (msg) {
                    case JsonRpc.Request r -> r.method();
                    case JsonRpc.Notification n -> n.method();
                    case JsonRpc.Response _ -> "response";
                };
                seen.add(new Seen(method, h.get("MCP-Protocol-Version"), h.get("Mcp-Method"), h.get("Mcp-Name"),
                        h.get("Mcp-Session-Id"), body));
                if (!(msg instanceof JsonRpc.Request r)) return new MockResponse.Builder().code(202).build();
                return answer.apply(r.method(), r.id());
            }
        });
    }

    /** A handshake-era server that refuses the probe with {@code probe}. */
    private static BiFunction<String, Object, MockResponse> handshakeEra(BiFunction<String, Object, MockResponse> probe) {
        return (method, id) -> switch (method) {
            case "server/discover" -> probe.apply(method, id);
            case "initialize" -> json(200, result(id, "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}"))
                    .addHeader("Mcp-Session-Id", "sess-1").build();
            case "tools/list" -> json(200, result(id, TOOLS)).build();
            default -> json(200, result(id, "{\"content\":[]}")).build();
        };
    }

    private static BiFunction<String, Object, MockResponse> modern() {
        return (method, id) -> switch (method) {
            case "server/discover" -> json(200, result(id, DISCOVER_RESULT)).addHeader("Mcp-Session-Id", "nope").build();
            case "initialize" -> json(400, error(id, -32022, "{\"supported\":[\"2026-07-28\"]}")).build();
            case "tools/list" -> json(200, result(id, TOOLS)).addHeader("Mcp-Session-Id", "nope").build();
            default -> json(200, result(id, "{\"resultType\":\"complete\",\"content\":[]}")).build();
        };
    }

    private record EraCase(String name, BiFunction<String, Object, MockResponse> server, boolean modern,
                           List<String> methods) {}

    @Test
    void theCompatibilityMatrixOverHttp() throws Exception {
        var handshake = List.of("server/discover", "initialize", "tools/list");
        var cases = List.of(
                new EraCase("modern server", modern(), true, List.of("server/discover", "tools/list")),
                new EraCase("handshake-era, probe refused -32601",
                        handshakeEra((_, id) -> json(200, error(id, -32601, null)).build()), false, handshake),
                new EraCase("handshake-era, probe ignored", handshakeEra((_, id) -> json(200, result(id, "{}"))
                        .headersDelay(1500, TimeUnit.MILLISECONDS).build()), false, handshake),
                new EraCase("modern server rejecting the version with -32022", (method, id) -> switch (method) {
                    case "server/discover" -> json(400, error(id, -32022, "{\"supported\":[\"2025-11-25\"]}")).build();
                    case "initialize" -> json(200, result(id, "{\"protocolVersion\":\"2025-06-18\"}")).build();
                    case "tools/list" -> json(200, result(id, TOOLS)).build();
                    default -> json(202, "").build();
                }, false, handshake));
        for (var c : cases) {
            seen.clear();
            serve(c.server());
            try (var client = McpEraDetection.open("http", McpServer.Protocol.AUTOMATIC, false, clients()).client()) {
                assertEquals(c.modern(), client.isModern(), c.name());
                // notifications/initialized and tools/list are separate POSTs and may arrive in either order.
                assertEquals(c.methods(), seen.stream().map(Seen::method)
                        .filter(m -> !m.equals("notifications/initialized")).toList(), c.name());
                if (!c.modern()) awaitInitialized();
                assertEquals(!c.modern(), seen.stream().anyMatch(x -> x.method().equals("notifications/initialized")),
                        c.name());
                assertEquals(1, client.tools().size(), c.name());
            }
        }
    }

    /** The transport enqueues each POST, so the notification can still be in flight when open() returns. */
    private void awaitInitialized() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && seen.stream().noneMatch(x -> x.method().equals("notifications/initialized"))) {
            Thread.sleep(10);
        }
    }

    /** What nuxt.com/mcp answered a 2026-07-28 probe on 2026-10-11. */
    @Test
    void nuxtsBadRequestWithMinus32000FallsBackToInitialize() throws Exception {
        serve(handshakeEra((_, id) -> json(400, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32000,"
                + "\"message\":\"Unsupported protocol version\"}}").build()));
        try (var client = McpEraDetection.open("nuxt", McpServer.Protocol.AUTOMATIC, false, clients()).client()) {
            assertFalse(client.isModern());
            assertEquals("2025-06-18", client.protocolVersion());
            assertEquals(List.of("server/discover", "initialize", "tools/list"), seen.stream().map(Seen::method)
                    .filter(m -> !m.equals("notifications/initialized")).toList());
            // The handshake path now sends the version initialize returned, and the session it was given.
            var list = seen.stream().filter(x -> x.method().equals("tools/list")).findFirst().orElseThrow();
            assertEquals("2025-06-18", list.version());
            assertEquals("sess-1", list.session());
        }
    }

    @Test
    void aModernServerSeesNoInitializeAndTheThreeHeadersOnACall() throws Exception {
        serve(modern());
        try (var client = McpEraDetection.open("ctx", McpServer.Protocol.AUTOMATIC, false, clients()).client()) {
            client.callTool("resolve-library-id", new JsonObject());
            var methods = seen.stream().map(Seen::method).toList();
            assertFalse(methods.contains("initialize"), methods.toString());
            assertFalse(methods.contains("notifications/initialized"), methods.toString());
            var call = seen.getLast();
            assertEquals("tools/call", call.method());
            assertEquals("2026-07-28", call.version());
            assertTrue(call.body().contains("\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\""),
                    "the header must equal the version in the body: " + call.body());
            assertEquals("tools/call", call.mcpMethod());
            assertEquals("resolve-library-id", call.mcpName());
            for (var s : seen) {
                assertNull(s.session(), "a modern connection never sends a session id: " + s);
                assertEquals("2026-07-28", s.version(), s.toString());
                assertEquals(s.method(), s.mcpMethod(), s.toString());
            }
            assertNull(seen.getFirst().mcpName(), "only tools/call carries Mcp-Name");
        }
    }

    @MustBeClosed
    private McpStreamableHttpTransport transport() {
        return new McpStreamableHttpTransport("http", URI.create(server.url("/mcp").toString()), Map.of());
    }

    @SuppressWarnings("MustBeClosed") // McpEraDetection closes a client it discards; the test closes the one it returns
    private McpEraDetection.ClientFactory clients() {
        return opening -> new McpClient("http", transport(), "0.0.1", Duration.ofMillis(800), Duration.ofSeconds(5),
                opening);
    }

    private static MockResponse.Builder json(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body);
    }

    private static String result(Object id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
    }

    private static String error(Object id, int code, @Nullable String data) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code + ",\"message\":\"refused\""
                + (data != null ? ",\"data\":" + data : "") + "}}";
    }
}
