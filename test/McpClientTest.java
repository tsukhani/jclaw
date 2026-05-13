import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mcp.CallToolResult;
import mcp.McpClient;
import mcp.McpException;
import mcp.McpToolDef;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class McpClientTest extends UnitTest {

    private FakeTransport transport;
    private McpClient client;

    @AfterEach
    public void teardown() {
        if (client != null) client.close();
    }

    // ==================== handshake ====================

    @Test
    public void connectPerformsInitializeHandshake() throws Exception {
        transport = new FakeTransport();
        client = new McpClient("test", transport, "0.0.1");

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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        client.connect();
        driver.join(5000);
        assertEquals(McpClient.State.READY, client.state());
        assertEquals(1, client.tools().size());
        assertEquals("echo", client.tools().get(0).name());
    }

    @Test
    public void connectRejectsServerErrorOnInitialize() throws Exception {
        transport = new FakeTransport();
        client = new McpClient("test", transport, "0.0.1");

        Thread.ofVirtual().start(() -> {
            try {
                var req = transport.takeSent(JsonRpc.Request.class);
                transport.deliver(new JsonRpc.Response(req.id(), null,
                        new JsonRpc.Error(-32602, "Unsupported version")));
            } catch (InterruptedException ignored) {}
        });

        var ex = assertThrows(McpException.class, () -> client.connect());
        assertTrue(ex.getMessage().contains("Unsupported version"),
                "exception should propagate server error message: " + ex.getMessage());
        assertEquals(McpClient.State.DISCONNECTED, client.state());
    }

    @Test
    public void connectRefusedFromNonDisconnectedState() throws Exception {
        transport = new FakeTransport();
        client = new McpClient("test", transport, "0.0.1");
        completeHandshake();
        assertThrows(McpException.class, () -> client.connect());
    }

    // ==================== callTool ====================

    @Test
    public void callToolReturnsTextContent() throws Exception {
        transport = new FakeTransport();
        client = new McpClient("test", transport, "0.0.1");
        completeHandshake();

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
            } catch (InterruptedException ignored) {}
        });

        var args = new JsonObject();
        args.addProperty("text", "hello");
        var result = client.callTool("echo", args);
        assertEquals("echoed", result.content());
        assertFalse(result.isError());
    }

    @Test
    public void callToolPropagatesServerErrorAsMcpException() throws Exception {
        transport = new FakeTransport();
        client = new McpClient("test", transport, "0.0.1");
        completeHandshake();

        Thread.ofVirtual().start(() -> {
            try {
                var call = transport.takeSent(JsonRpc.Request.class);
                transport.deliver(new JsonRpc.Response(call.id(), null,
                        new JsonRpc.Error(-32000, "tool crashed")));
            } catch (InterruptedException ignored) {}
        });

        var args = new JsonObject();
        var ex = assertThrows(McpException.class,
                () -> client.callTool("echo", args));
        assertTrue(ex.getMessage().contains("tool crashed"));
    }

    @Test
    public void callToolRejectedBeforeReady() {
        transport = new FakeTransport();
        client = new McpClient("test", transport, "0.0.1");
        // No connect() — state remains DISCONNECTED.
        var args = new JsonObject();
        assertThrows(McpException.class, () -> client.callTool("echo", args));
    }

    // ==================== notifications ====================

    @Test
    public void toolsListChangedTriggersRefreshAndCallback() throws Exception {
        transport = new FakeTransport();
        client = new McpClient("test", transport, "0.0.1");
        var fired = new AtomicReference<List<McpToolDef>>();
        client.onToolsChanged(fired::set);

        completeHandshake();

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

    // ==================== server-initiated requests ====================

    @Test
    public void serverInitiatedRequestRepliedWithMethodNotFound() throws Exception {
        transport = new FakeTransport();
        client = new McpClient("test", transport, "0.0.1");
        completeHandshake();

        // Server requests something we don't support (e.g., sampling/createMessage).
        transport.deliver(new JsonRpc.Request(99L, "sampling/createMessage", null));

        var reply = transport.takeSent(JsonRpc.Response.class);
        assertEquals(99L, reply.id());
        assertTrue(reply.isError());
        assertEquals(-32601, reply.error().code());
    }

    // ==================== transport errors ====================

    @Test
    public void transportErrorMovesToDisconnectedAndCancelsPending() throws Exception {
        transport = new FakeTransport();
        client = new McpClient("test", transport, "0.0.1");
        completeHandshake();

        // Issue a callTool but never respond — instead, trip a transport error.
        var pendingCall = Thread.ofVirtual().start(() -> {
            try {
                client.callTool("echo", new JsonObject());
                fail("callTool should have failed");
            } catch (Exception expected) {
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

    // ==================== close ====================

    @Test
    public void closeIsIdempotent() throws Exception {
        transport = new FakeTransport();
        client = new McpClient("test", transport, "0.0.1");
        client.close();
        client.close();  // must not throw
        assertEquals(McpClient.State.DISCONNECTED, client.state());
    }

    // ==================== helpers ====================

    private void completeHandshake() throws Exception {
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
            } catch (InterruptedException ignored) {}
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
