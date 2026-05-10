import com.google.gson.JsonObject;
import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpStdioTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stdio transport contract: spawn a Node-based fixture MCP server, exchange
 * JSON-RPC over its stdin/stdout, and verify both directions plus clean
 * shutdown. Skipped when {@code node} is not on PATH so backend-only CI
 * environments don't fail.
 */
public class McpStdioTransportTest extends UnitTest {

    private static final String FIXTURE_SCRIPT = """
            // Minimal MCP-shaped echo server: reads line-delimited JSON-RPC
            // from stdin, replies with the matching JSON-RPC shape per method.
            const readline = require('readline');
            process.stderr.write('fixture starting\\n');
            const rl = readline.createInterface({ input: process.stdin, terminal: false });
            rl.on('line', (line) => {
              if (!line.trim()) return;
              let msg;
              try { msg = JSON.parse(line); }
              catch (e) { process.stderr.write('bad json: ' + line + '\\n'); return; }
              if (msg.method === 'initialize') {
                send({ jsonrpc: '2.0', id: msg.id,
                  result: { protocolVersion: '2025-06-18', capabilities: { tools: {} },
                            serverInfo: { name: 'fixture', version: '0.0.1' } } });
              } else if (msg.method === 'tools/list') {
                send({ jsonrpc: '2.0', id: msg.id,
                  result: { tools: [{ name: 'echo', description: 'Echo input',
                                       inputSchema: { type: 'object',
                                         properties: { text: { type: 'string' } } } }] } });
              } else if (msg.method === 'tools/call') {
                const text = (msg.params && msg.params.arguments && msg.params.arguments.text) || '';
                send({ jsonrpc: '2.0', id: msg.id,
                  result: { content: [{ type: 'text', text: 'echo:' + text }] } });
              } else if (msg.method === 'ping') {
                send({ jsonrpc: '2.0', id: msg.id, result: {} });
              } else if (msg.method && msg.id !== undefined) {
                send({ jsonrpc: '2.0', id: msg.id,
                  error: { code: -32601, message: 'Method not found: ' + msg.method } });
              }
              // notifications (no id): silent
            });
            function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n'); }
            """;

    private Path fixturePath;
    private McpStdioTransport transport;
    private final List<JsonRpc.Message> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    @BeforeEach
    public void setUp() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH; skipping stdio transport test");
        fixturePath = Files.createTempFile("mcp-fixture-", ".js");
        Files.writeString(fixturePath, FIXTURE_SCRIPT);
        transport = new McpStdioTransport("fixture",
                List.of("node", fixturePath.toString()), Map.of());
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (transport != null) transport.close();
        if (fixturePath != null) Files.deleteIfExists(fixturePath);
    }

    @Test
    public void initializeAndCallToolRoundTrip() throws Exception {
        var initLatch = new CountDownLatch(1);
        var callLatch = new CountDownLatch(1);
        var tools = new AtomicReference<JsonRpc.Response>();
        var call = new AtomicReference<JsonRpc.Response>();

        transport.start(msg -> {
            received.add(msg);
            if (msg instanceof JsonRpc.Response r) {
                if (r.id().equals(1L)) initLatch.countDown();
                else if (r.id().equals(2L)) tools.set(r);
                else if (r.id().equals(3L)) { call.set(r); callLatch.countDown(); }
            }
        }, error::set);

        transport.send(new JsonRpc.Request(1L, "initialize", initParams()));
        assertTrue(initLatch.await(5, TimeUnit.SECONDS), "initialize response");

        transport.send(new JsonRpc.Notification("notifications/initialized", null));
        transport.send(new JsonRpc.Request(2L, "tools/list", new JsonObject()));

        var deadline = System.currentTimeMillis() + 3000;
        while (tools.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertNotNull(tools.get(), "tools/list response");
        var toolArr = tools.get().result().getAsJsonObject().getAsJsonArray("tools");
        assertEquals(1, toolArr.size());
        assertEquals("echo", toolArr.get(0).getAsJsonObject().get("name").getAsString());

        var callParams = new JsonObject();
        callParams.addProperty("name", "echo");
        var args = new JsonObject();
        args.addProperty("text", "hi");
        callParams.add("arguments", args);
        transport.send(new JsonRpc.Request(3L, "tools/call", callParams));
        assertTrue(callLatch.await(5, TimeUnit.SECONDS), "tools/call response");
        var content = call.get().result().getAsJsonObject().getAsJsonArray("content");
        assertEquals("echo:hi", content.get(0).getAsJsonObject().get("text").getAsString());
        assertNull(error.get(), "no transport error during round-trip");
    }

    @Test
    public void closeTerminatesProcessAndStopsReader() throws Exception {
        transport.start(received::add, error::set);
        transport.send(new JsonRpc.Request(1L, "ping", null));
        // Wait for the response to come back so we know the process is alive.
        var deadline = System.currentTimeMillis() + 3000;
        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(received.isEmpty(), "ping response should arrive");
        transport.close();
        // close() must not throw and must not surface a spurious onError for the EOF.
        Thread.sleep(100);
        assertNull(error.get(), "EOF after explicit close must not surface as error");
    }

    private static JsonObject initParams() {
        var params = new JsonObject();
        params.addProperty("protocolVersion", "2025-06-18");
        params.add("capabilities", new JsonObject());
        var info = new JsonObject();
        info.addProperty("name", "test");
        info.addProperty("version", "0.0.1");
        params.add("clientInfo", info);
        return params;
    }

    private static boolean nodeAvailable() {
        try {
            var p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
