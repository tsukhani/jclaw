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
 * Contract test for {@link McpServerService#syncRuntime}: enabling a server
 * (the {@code POST/PUT /api/mcp-servers} path) kicks the connect off on its
 * own virtual thread and returns immediately — it must <em>not</em> block the
 * caller on the handshake, because the caller's request transaction holds a
 * write lock on the {@code mcp_server} row until it commits, and pinning that
 * lock across the handshake caused {@code LockTimeoutException} storms when
 * toggling servers on. The connect outcome (CONNECTED / ERROR) is observed
 * asynchronously by polling, exactly as the admin UI does via
 * {@code pollUntilStable}. (Was JCLAW-288's synchronous-await contract;
 * reverted to fire-and-forget.)
 *
 * <p>The disable path stays synchronous teardown.
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
        // Shrink the manager's first-attempt handshake timeout so the
        // failure-path test resolves in seconds, not the production 120 s.
        McpConnectionManager.setFirstAttemptRequestTimeout(Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() throws Exception {
        McpConnectionManager.shutdown();
        EventLogger.clear();
        McpConnectionManager.setBackoff(1_000, 30_000);
        McpConnectionManager.setFirstAttemptRequestTimeout(Duration.ofSeconds(120));
        if (fixturePath != null) Files.deleteIfExists(fixturePath);
        ToolRegistrationJob.registerAll();
    }

    @Test
    void syncRuntimeConnectsAsynchronouslyOnSuccessfulRegistration() throws Exception {
        var row = seedStdio("svc-ok", OK_FIXTURE);
        var before = System.nanoTime();
        McpServerService.syncRuntime(row);
        var elapsedMs = (System.nanoTime() - before) / 1_000_000;

        // Fire-and-forget: syncRuntime kicks the connect off on its own VT and
        // returns at once — it must NOT block on the handshake (that pinned the
        // mcp_server row lock and caused the LockTimeoutException storm).
        assertTrue(elapsedMs < 1_000,
                "syncRuntime must return promptly without awaiting the handshake; elapsed=" + elapsedMs);
        // The connect resolves on its VT; poll the manager until CONNECTED —
        // the same way the admin UI observes the outcome (pollUntilStable).
        awaitUntil(() -> McpConnectionManager.status("svc-ok") == McpServer.Status.CONNECTED,
                "manager never reached CONNECTED");
        assertNull(McpConnectionManager.lastError("svc-ok"),
                "successful connect leaves no lingering lastError");
        // View.of(row) is what the controller renders to the API client —
        // confirm it picks up the live state without us mutating the row.
        var view = McpServerService.View.of(row);
        assertEquals(McpServer.Status.CONNECTED.name(), view.status());
        assertNull(view.lastError());
    }

    @Test
    void syncRuntimeConnectsAsynchronouslyToErrorOnFailedRegistration() throws Exception {
        var row = seedStdio("svc-eof", EOF_FIXTURE);
        McpServerService.syncRuntime(row);

        // Poll until the failed handshake surfaces as ERROR with a reason.
        // (status flips to ERROR a hair before lastError is set, and the
        // fast-backoff loop re-attempts, so gate on both being present.)
        awaitUntil(() -> McpConnectionManager.status("svc-eof") == McpServer.Status.ERROR
                        && McpConnectionManager.lastError("svc-eof") != null,
                "manager never reported ERROR with a reason");
        var view = McpServerService.View.of(row);
        assertEquals(McpServer.Status.ERROR.name(), view.status());
        assertNotNull(view.lastError(),
                "View must surface the live error so a polled response carries it");
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

    /** Poll {@code cond} every 25 ms until it holds, up to ~10 s (generous
     *  ceiling for the Node fixture handshake plus the fast-backoff reconnect),
     *  then return; fail with {@code msg} otherwise. Mirrors how the admin UI
     *  observes the fire-and-forget connect outcome (pollUntilStable). */
    private static void awaitUntil(java.util.function.BooleanSupplier cond, String msg) {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(25); }
            catch (InterruptedException _) { Thread.currentThread().interrupt(); return; }
        }
        org.junit.jupiter.api.Assertions.fail(msg);
    }

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
