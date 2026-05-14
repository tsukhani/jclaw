import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jobs.ToolRegistrationJob;
import mcp.McpConnectionManager;
import models.McpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.EventLogger;
import services.McpServerService;
import services.Tx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JCLAW-288 contract test for {@link McpServerService#syncRuntime}: the
 * admin API ({@code POST/PUT /api/mcp-servers}) must block its caller
 * until the first connect attempt has resolved (CONNECTED or ERROR) so
 * the response carries the actual outcome of registration.
 *
 * <p>Disabled-toggle paths stay asynchronous — teardown does not need to
 * block the caller.
 *
 * <p>Skipped when {@code node} is not on PATH (same gate as the rest of
 * the MCP integration tests).
 */
class McpServerServiceTest extends UnitTest {

    private static final String OK_FIXTURE = """
            const readline = require('readline');
            const rl = readline.createInterface({ input: process.stdin, terminal: false });
            rl.on('line', (line) => {
              if (!line.trim()) return;
              let m;
              try { m = JSON.parse(line); } catch (e) { return; }
              if (m.method === 'initialize') {
                send({ jsonrpc: '2.0', id: m.id,
                  result: { protocolVersion: '2025-06-18', capabilities: { tools: {} },
                            serverInfo: { name: 'svc-ok', version: '0.0.1' } } });
              } else if (m.method === 'tools/list') {
                send({ jsonrpc: '2.0', id: m.id, result: { tools: [] } });
              }
            });
            function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n'); }
            """;

    private static final String EOF_FIXTURE = """
            process.exit(0);
            """;

    private Path fixturePath;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH; skipping");
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ToolRegistry.publish(List.of());
        // Same fast-backoff window the manager test uses, so failure-path
        // tests don't spend real seconds in the watchdog's reconnect loop.
        McpConnectionManager.setBackoff(50, 200);
        // Tight per-test cap on syncRuntime's await — the production 130 s
        // would mask test failures behind huge timeouts. Set the manager's
        // own first-attempt timeout below this so it always fires first.
        McpServerService.setSyncRuntimeAwait(Duration.ofSeconds(10));
        McpConnectionManager.setFirstAttemptRequestTimeout(Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() throws Exception {
        McpConnectionManager.shutdown();
        EventLogger.clear();
        McpConnectionManager.setBackoff(1_000, 30_000);
        McpConnectionManager.setFirstAttemptRequestTimeout(Duration.ofSeconds(120));
        McpServerService.setSyncRuntimeAwait(Duration.ofSeconds(130));
        if (fixturePath != null) Files.deleteIfExists(fixturePath);
        ToolRegistrationJob.registerAll();
    }

    @Test
    void syncRuntimeBlocksUntilConnectedOnSuccessfulRegistration() throws Exception {
        var row = seedStdio("svc-ok", OK_FIXTURE);
        var before = System.nanoTime();
        McpServerService.syncRuntime(row);
        var elapsedMs = (System.nanoTime() - before) / 1_000_000;

        // The contract is "block until first attempt resolves" — we don't
        // care about exact timing, only that by the time syncRuntime
        // returns the manager has reached CONNECTED. A few seconds is a
        // generous ceiling for the Node fixture handshake.
        assertTrue(elapsedMs < 10_000,
                "syncRuntime should resolve well under the 10 s cap; elapsed=" + elapsedMs);
        assertEquals(McpServer.Status.CONNECTED,
                McpConnectionManager.status("svc-ok"),
                "manager must report CONNECTED before syncRuntime returns");
        assertNull(McpConnectionManager.lastError("svc-ok"),
                "successful first attempt leaves no lingering lastError");
        // View.of(row) is what the controller renders to the API client —
        // confirm it picks up the live state without us mutating the row.
        var view = McpServerService.View.of(row);
        assertEquals(McpServer.Status.CONNECTED.name(), view.status());
        assertNull(view.lastError());
    }

    @Test
    void syncRuntimeBlocksUntilErrorOnFailedRegistration() throws Exception {
        var row = seedStdio("svc-eof", EOF_FIXTURE);
        McpServerService.syncRuntime(row);

        assertEquals(McpServer.Status.ERROR,
                McpConnectionManager.status("svc-eof"),
                "manager must report ERROR before syncRuntime returns");
        var liveError = McpConnectionManager.lastError("svc-eof");
        assertNotNull(liveError, "manager must carry the subprocess-exit reason");
        var view = McpServerService.View.of(row);
        assertEquals(McpServer.Status.ERROR.name(), view.status());
        assertNotNull(view.lastError(),
                "View must surface the live error so the API response carries it");
    }

    @Test
    void syncRuntimeReturnsImmediatelyWhenDisabled() throws Exception {
        // Seed disabled directly — the disabled branch is the contract
        // under test, so there's no need to flip the flag across two
        // transactions (which trips Hibernate's detached-entity check).
        var row = seedStdio("svc-disabled", OK_FIXTURE, false);
        var before = System.nanoTime();
        McpServerService.syncRuntime(row);
        var elapsedMs = (System.nanoTime() - before) / 1_000_000;

        // No connect attempt should fire — the disabled path is pure
        // teardown and must not block.
        assertTrue(elapsedMs < 1_000,
                "disabled syncRuntime must not block on a connect attempt; elapsed=" + elapsedMs);
        assertEquals(McpServer.Status.DISCONNECTED, row.status);
        assertNull(row.lastError);
    }

    // ==================== helpers ====================

    private McpServer seedStdio(String name, String script) throws IOException {
        return seedStdio(name, script, true);
    }

    private McpServer seedStdio(String name, String script, boolean enabled) throws IOException {
        fixturePath = Files.createTempFile("mcp-svc-" + name + "-", ".js");
        Files.writeString(fixturePath, script);
        fixturePath.toFile().deleteOnExit();
        var cfg = new JsonObject();
        cfg.addProperty("command", "node");
        var args = new JsonArray();
        args.add(fixturePath.toString());
        cfg.add("args", args);
        return Tx.run(() -> {
            var srv = new McpServer();
            srv.name = name;
            srv.enabled = enabled;
            srv.transport = McpServer.Transport.STDIO;
            srv.configJson = cfg.toString();
            srv.save();
            return srv;
        });
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
