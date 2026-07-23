import mcp.jsonrpc.JsonRpc;
import mcp.transport.McpStdioTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.SubprocessEnv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-779 regression: an MCP stdio child must be spawned with the
 * secret-filtered host environment, not the JVM's full env. Spawns a Node
 * fixture that dumps {@code process.env} back over JSON-RPC and asserts through
 * the real {@link McpStdioTransport#start} path that (1) no sensitive-named host
 * var reaches the child, (2) the non-sensitive host PATH is carried through, and
 * (3) the operator-supplied MCP config env is delivered. Skipped when {@code
 * node} is not on PATH so backend-only CI doesn't fail.
 */
class McpStdioEnvIsolationTest extends UnitTest {

    private static final String FIXTURE_SCRIPT = """
            // Dumps the child's own environment back as a JSON-RPC result so the
            // test can inspect exactly what the parent handed the subprocess.
            const readline = require('readline');
            const rl = readline.createInterface({ input: process.stdin, terminal: false });
            rl.on('line', (line) => {
              if (!line.trim()) return;
              let msg;
              try { msg = JSON.parse(line); } catch (e) { return; }
              if (msg.method === 'env/dump') {
                send({ jsonrpc: '2.0', id: msg.id, result: { env: process.env } });
              } else if (msg.method && msg.id !== undefined) {
                send({ jsonrpc: '2.0', id: msg.id, result: {} });
              }
            });
            function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n'); }
            """;

    private Path fixturePath;
    private McpStdioTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH; skipping stdio env-isolation test");
        fixturePath = Files.createTempFile("mcp-envdump-", ".js");
        Files.writeString(fixturePath, FIXTURE_SCRIPT);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (transport != null) transport.close();
        if (fixturePath != null) Files.deleteIfExists(fixturePath);
    }

    @Test
    void childGetsFilteredHostEnvPlusConfigNeverHostSecrets() throws Exception {
        // Operator-supplied MCP config env — must survive to the child.
        transport = new McpStdioTransport("envdump",
                List.of("node", fixturePath.toString()),
                Map.of("MCP_CONFIG_VAR", "cfg-value"));

        var dump = new AtomicReference<JsonRpc.Response>();
        transport.start(msg -> {
            if (msg instanceof JsonRpc.Response r && r.id().equals(1L)) dump.set(r);
        }, err -> { });

        transport.send(new JsonRpc.Request(1L, "env/dump", null));

        var deadline = System.currentTimeMillis() + 5000;
        while (dump.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertNotNull(dump.get(), "env/dump response should arrive");

        var childEnv = dump.get().result().getAsJsonObject().getAsJsonObject("env");

        // (1) No sensitive host var leaked into the child.
        for (var key : childEnv.keySet()) {
            assertFalse(SubprocessEnv.isSensitive(key),
                    "host secret leaked to MCP stdio child: " + key);
        }

        // (2) Non-sensitive host env still flows through (PATH).
        Assumptions.assumeTrue(System.getenv("PATH") != null, "no PATH in this environment");
        assertTrue(childEnv.has("PATH"), "non-sensitive host PATH must reach the child");

        // (3) Operator-supplied config env is delivered.
        assertEquals("cfg-value", childEnv.get("MCP_CONFIG_VAR").getAsString(),
                "operator MCP config env must reach the child");
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
