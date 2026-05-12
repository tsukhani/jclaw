package jclaw.mcp.server;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip the three JSON-RPC methods over an in-memory transport
 * (no stdin, no network) to verify the server's wire-protocol contract.
 *
 * <p>Why a fake transport: {@link StdioTransport} couples to
 * {@code System.in}/{@code System.out}, which is awkward to plumb in
 * unit tests and would force every assertion through line buffering.
 * The {@link Loopback} transport here is plain in-memory queues — the
 * JSON-RPC layer is exactly the same code, just shorter wires.
 */
class McpServerTest {

    @Test
    void initializeAdvertisesToolsCapability() throws Exception {
        var transport = new Loopback();
        var server = new McpServer(transport, List.of(), stubInvoker());
        Thread.ofVirtual().start(() -> {
            try { server.run(); } catch (Exception ignored) { /* loop ends with stop() */ }
        });

        transport.deliverFromClient(makeRequest(1, "initialize", new JsonObject()));
        var reply = transport.awaitNextToClient();
        var result = parseResponse(reply, 1);

        assertEquals(McpServer.PROTOCOL_VERSION, result.get("protocolVersion").getAsString(),
                "protocolVersion must match the pinned MCP revision in McpServer");
        assertTrue(result.getAsJsonObject("capabilities").has("tools"),
                "JClaw advertises tools only — no resources/prompts/sampling");
        assertEquals(McpServer.SERVER_NAME,
                result.getAsJsonObject("serverInfo").get("name").getAsString());

        server.stop();
    }

    @Test
    void toolsListReturnsCatalog() throws Exception {
        var tool = new ToolDefinition("jclaw_listAgents", "List agents",
                new JsonObject(), "GET", "/api/agents", List.of(), false);
        var transport = new Loopback();
        var server = new McpServer(transport, List.of(tool), stubInvoker());
        Thread.ofVirtual().start(() -> {
            try { server.run(); } catch (Exception ignored) { /* loop ends with stop() */ }
        });

        transport.deliverFromClient(makeRequest(2, "tools/list", new JsonObject()));
        var reply = transport.awaitNextToClient();
        var result = parseResponse(reply, 2);

        var tools = result.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        var entry = tools.get(0).getAsJsonObject();
        assertEquals("jclaw_listAgents", entry.get("name").getAsString());
        // The inputSchema round-trips so the host can validate caller
        // arguments before issuing tools/call.
        assertTrue(entry.has("inputSchema"));

        server.stop();
    }

    @Test
    void toolsCallDelegatesToInvoker() throws Exception {
        var tool = new ToolDefinition("jclaw_listAgents", "List agents",
                new JsonObject(), "GET", "/api/agents", List.of(), false);
        var capturedArgs = new java.util.concurrent.atomic.AtomicReference<JsonObject>();
        var invoker = new StubInvoker((t, args) -> {
            capturedArgs.set(args);
            var result = new JsonObject();
            var arr = new com.google.gson.JsonArray();
            var block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", "ok");
            arr.add(block);
            result.add("content", arr);
            return result;
        });
        var transport = new Loopback();
        var server = new McpServer(transport, List.of(tool), invoker);
        Thread.ofVirtual().start(() -> {
            try { server.run(); } catch (Exception ignored) { /* loop ends with stop() */ }
        });

        var params = new JsonObject();
        params.addProperty("name", "jclaw_listAgents");
        var args = new JsonObject();
        args.addProperty("filter", "active");
        params.add("arguments", args);

        transport.deliverFromClient(makeRequest(3, "tools/call", params));
        var reply = transport.awaitNextToClient();
        var result = parseResponse(reply, 3);

        // Args reach the invoker unchanged — the dispatcher's job is
        // routing, not transformation. Transformation belongs in
        // ToolInvoker so a single test can capture it.
        assertEquals("active", capturedArgs.get().get("filter").getAsString());
        assertEquals("ok", result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString());

        server.stop();
    }

    @Test
    void unknownToolReturnsMethodNotFound() throws Exception {
        var transport = new Loopback();
        var server = new McpServer(transport, List.of(), stubInvoker());
        Thread.ofVirtual().start(() -> {
            try { server.run(); } catch (Exception ignored) { /* loop ends with stop() */ }
        });

        var params = new JsonObject();
        params.addProperty("name", "jclaw_nonexistent");
        params.add("arguments", new JsonObject());
        transport.deliverFromClient(makeRequest(4, "tools/call", params));

        var reply = transport.awaitNextToClient();
        var obj = com.google.gson.JsonParser.parseString(JsonRpc.encode(reply)).getAsJsonObject();
        // Unknown tool surfaces as JSON-RPC method_not_found rather than
        // an MCP isError result — the host's job is to never advertise
        // a tool the server didn't list.
        assertEquals(JsonRpc.ERROR_METHOD_NOT_FOUND,
                obj.getAsJsonObject("error").get("code").getAsInt());

        server.stop();
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        var transport = new Loopback();
        var server = new McpServer(transport, List.of(), stubInvoker());
        Thread.ofVirtual().start(() -> {
            try { server.run(); } catch (Exception ignored) { /* loop ends with stop() */ }
        });

        transport.deliverFromClient(makeRequest(5, "resources/list", new JsonObject()));
        var reply = transport.awaitNextToClient();
        var obj = com.google.gson.JsonParser.parseString(JsonRpc.encode(reply)).getAsJsonObject();
        // We advertise tools only; resources/list should 404 cleanly so
        // a host can keep its session going.
        assertEquals(JsonRpc.ERROR_METHOD_NOT_FOUND,
                obj.getAsJsonObject("error").get("code").getAsInt());

        server.stop();
    }

    // ==================== plumbing ====================

    private static JsonRpc.Request makeRequest(long id, String method, JsonObject params) {
        return new JsonRpc.Request(id, method, params);
    }

    private static JsonObject parseResponse(JsonRpc.Message msg, long expectedId) {
        assertTrue(msg instanceof JsonRpc.Response, "expected Response, got: " + msg);
        var resp = (JsonRpc.Response) msg;
        assertNull(resp.error(), "expected success response; got error: " + resp.error());
        // IDs are decoded as Long on the inbound path; compare numerically
        // so test-id ergonomics don't drift if we ever mint string IDs.
        assertEquals(expectedId, ((Number) resp.id()).longValue());
        return resp.result().getAsJsonObject();
    }

    private static ToolInvoker stubInvoker() {
        return new StubInvoker((t, args) -> {
            var r = new JsonObject();
            r.add("content", new com.google.gson.JsonArray());
            return r;
        });
    }

    /** Bypasses {@link ToolInvoker}'s HTTP dependency. */
    private static final class StubInvoker extends ToolInvoker {
        private final java.util.function.BiFunction<ToolDefinition, JsonObject, JsonObject> handler;

        StubInvoker(java.util.function.BiFunction<ToolDefinition, JsonObject, JsonObject> handler) {
            // Pass a never-used JClawHttp; super requires a non-null one
            // and we override invoke() so the underlying client is dead
            // code in tests.
            super(new JClawHttp(new Config(java.net.URI.create("http://example.invalid"),
                    "jcl_x", Config.Scope.READ_ONLY, List.of()),
                    new okhttp3.OkHttpClient()));
            this.handler = handler;
        }

        @Override
        public JsonObject invoke(ToolDefinition tool, JsonObject args) {
            return handler.apply(tool, args);
        }
    }

    /** In-memory transport: client and server queues for messages
     *  flowing in each direction. */
    private static final class Loopback implements Transport {
        private final LinkedBlockingQueue<JsonRpc.Message> fromClient = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<JsonRpc.Message> toClient = new LinkedBlockingQueue<>();
        private Thread readerThread;
        private volatile boolean closed;

        @Override
        public void start(Consumer<JsonRpc.Message> onMessage, Consumer<Throwable> onError) {
            readerThread = Thread.ofVirtual().name("loopback-reader").start(() -> {
                while (!closed) {
                    try {
                        var msg = fromClient.poll(50, TimeUnit.MILLISECONDS);
                        if (msg != null) onMessage.accept(msg);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        @Override
        public void send(JsonRpc.Message msg) {
            toClient.offer(msg);
        }

        @Override
        public void close() {
            closed = true;
            if (readerThread != null) readerThread.interrupt();
        }

        void deliverFromClient(JsonRpc.Message msg) {
            fromClient.offer(msg);
        }

        JsonRpc.Message awaitNextToClient() throws InterruptedException {
            var msg = toClient.poll(2, TimeUnit.SECONDS);
            assertNotNull(msg, "expected a response within 2s — server may have stalled");
            return msg;
        }
    }

}
