import agents.ToolRegistry;
import com.google.gson.JsonObject;
import jobs.ToolRegistrationJob;
import mcp.McpAllowlist;
import mcp.McpClient;
import mcp.McpConnectionManager;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.EventLog;
import models.McpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.EventLogger;
import services.Tx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end test for {@link McpConnectionManager} with a real Node-based
 * MCP fixture server. Exercises the full chain: seed an mcp_server row →
 * manager spawns transport → initialize handshake completes → tools are
 * registered with {@link ToolRegistry} → admin tears down → entry is gone
 * and tools are unregistered. The "kill the process and verify backoff"
 * branch is covered by aggressively shrinking the backoff schedule via
 * {@link McpConnectionManager#setBackoff}, then asserting the disconnect
 * event is recorded.
 *
 * <p>Skipped when {@code node} is not on PATH (same gate as
 * {@link McpStdioTransportTest}).
 */
public class McpConnectionManagerTest extends UnitTest {

    private static final String FIXTURE_SCRIPT = """
            const readline = require('readline');
            const rl = readline.createInterface({ input: process.stdin, terminal: false });
            rl.on('line', (line) => {
              if (!line.trim()) return;
              let m;
              try { m = JSON.parse(line); } catch (e) { return; }
              if (m.method === 'initialize') {
                send({ jsonrpc: '2.0', id: m.id,
                  result: { protocolVersion: '2025-06-18', capabilities: { tools: {} },
                            serverInfo: { name: 'fixture', version: '0.0.1' } } });
              } else if (m.method === 'tools/list') {
                send({ jsonrpc: '2.0', id: m.id,
                  result: { tools: [{ name: 'echo', description: 'Echo input',
                                       inputSchema: { type: 'object',
                                         properties: { text: { type: 'string' } } } }] } });
              } else if (m.method === 'tools/call') {
                const text = (m.params && m.params.arguments && m.params.arguments.text) || '';
                send({ jsonrpc: '2.0', id: m.id,
                  result: { content: [{ type: 'text', text: 'echo:' + text }] } });
              }
            });
            function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n'); }
            """;

    private Path fixturePath;

    @BeforeEach
    public void setUp() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH; skipping");
        Fixtures.deleteDatabase();
        EventLogger.clear();
        // Clear out any tools other tests may have registered.
        ToolRegistry.publish(List.of());
        // Aggressively shrink the backoff window so this test stays under a few seconds.
        McpConnectionManager.setBackoff(50, 200);
        fixturePath = Files.createTempFile("mcp-cm-fixture-", ".js");
        Files.writeString(fixturePath, FIXTURE_SCRIPT);
    }

    @AfterEach
    public void tearDown() throws Exception {
        McpConnectionManager.shutdown();
        EventLogger.clear();
        // Restore the production backoff numbers so any later test isn't perturbed.
        McpConnectionManager.setBackoff(1_000, 30_000);
        if (fixturePath != null) Files.deleteIfExists(fixturePath);
        // Re-run native tool registration so the next test sees the canonical set.
        ToolRegistrationJob.registerAll();
    }

    // ==================== happy path ====================

    @Test
    public void connectsAndRegistersToolsWithRegistry() throws Exception {
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);

        awaitState("fixture", McpServer.Status.CONNECTED, 10);
        assertTrue(ToolRegistry.listTools().stream()
                .anyMatch(t -> t.name().equals("mcp_fixture_echo")),
                "MCP-discovered tool should appear in the registry as mcp_fixture_echo");
    }

    @Test
    public void callToolDispatchesThroughAdapter() throws Exception {
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);
        awaitState("fixture", McpServer.Status.CONNECTED, 10);

        var args = new JsonObject();
        args.addProperty("text", "hi");
        var result = McpConnectionManager.callTool("fixture", "echo", args);
        assertEquals("echo:hi", result.content());
        assertFalse(result.isError());
    }

    @Test
    public void connectEmitsMcpConnectEventLog() throws Exception {
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);
        awaitState("fixture", McpServer.Status.CONNECTED, 10);

        EventLogger.flush();
        var connectEvents = Tx.run(() -> EventLog.find(
                "category = ?1 ORDER BY timestamp DESC", "MCP_CONNECT").<EventLog>fetch());
        assertFalse(connectEvents.isEmpty(),
                "MCP_CONNECT event must be persisted on successful connection");
        assertTrue(connectEvents.get(0).message.contains("fixture"),
                "MCP_CONNECT event must mention server name: " + connectEvents.get(0).message);
    }

    // ==================== unhappy paths ====================

    @Test
    public void unreachableHttpServerSurfacesErrorAndRetriesWithBackoff() throws Exception {
        // Point at a port that is almost certainly not listening.
        var cfg = new JsonObject();
        cfg.addProperty("url", "http://127.0.0.1:1/mcp");
        var server = seedServer("dead-http", McpServer.Transport.HTTP, cfg.toString());
        McpConnectionManager.connect(server);

        // Wait until at least one connect attempt has failed and status is ERROR.
        awaitState("dead-http", McpServer.Status.ERROR, 5);
        assertNotNull(McpConnectionManager.lastError("dead-http"),
                "lastError must be set after connect failure");

        EventLogger.flush();
        var attemptLogs = Tx.run(() -> EventLog.find(
                "category = ?1 AND message LIKE ?2 ORDER BY timestamp DESC",
                "MCP_CONNECT", "%dead-http%").<EventLog>fetch());
        assertFalse(attemptLogs.isEmpty(),
                "Failed connect attempts must be logged under MCP_CONNECT");
    }

    @Test
    public void stopRemovesToolsAndCancelsRetry() throws Exception {
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);
        awaitState("fixture", McpServer.Status.CONNECTED, 10);
        assertTrue(toolRegistered("mcp_fixture_echo"));

        McpConnectionManager.stop("fixture");
        // After stop, the registry must no longer carry our adapter and the
        // manager must have no entry for this name.
        assertFalse(toolRegistered("mcp_fixture_echo"),
                "stop() must unregister the server's tools");
        assertEquals(McpServer.Status.DISCONNECTED, McpConnectionManager.status("fixture"));
    }

    // ==================== JCLAW-32: allowlist + MCP_TOOL_UNREGISTER ====================

    @Test
    public void connectWritesAllowlistRowsForExistingAgents() throws Exception {
        var agentId = commitInFreshTx(() -> seedAgent("alpha").id);
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);

        McpConnectionManager.connect(server);
        awaitState("fixture", McpServer.Status.CONNECTED, 10);

        Tx.run(() -> {
            var agent = (Agent) Agent.findById(agentId);
            assertTrue(McpAllowlist.isAllowed(agent, "fixture", "echo"),
                    "alpha must be granted the fixture's 'echo' tool after connect");
            var rows = AgentSkillAllowedTool.find(
                    "skillName = ?1", "mcp:fixture").<AgentSkillAllowedTool>fetch();
            assertEquals(1, rows.size(), "exactly one row: 1 agent x 1 tool");
        });
    }

    @Test
    public void stopClearsAllowlistAndEmitsMcpToolUnregisterEvent() throws Exception {
        commitInFreshTx(() -> { seedAgent("alpha"); return null; });
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);
        awaitState("fixture", McpServer.Status.CONNECTED, 10);

        // Sanity: rows exist before stop.
        Tx.run(() -> assertEquals(1L, AgentSkillAllowedTool.count(
                "skillName = ?1", "mcp:fixture")));

        McpConnectionManager.stop("fixture");

        Tx.run(() -> {
            assertEquals(0L, AgentSkillAllowedTool.count(
                    "skillName = ?1", "mcp:fixture"),
                    "allowlist rows must be cleared on stop");
            var unregEvents = EventLog.find(
                    "category = ?1 ORDER BY timestamp DESC", "MCP_TOOL_UNREGISTER")
                    .<EventLog>fetch();
            assertFalse(unregEvents.isEmpty(),
                    "MCP_TOOL_UNREGISTER event must be persisted on stop");
            assertTrue(unregEvents.get(0).message.contains("fixture"));
        });
    }

    // ==================== JCLAW-32: agent-creation backfill ====================

    @Test
    public void agentCreatedAfterConnectGetsBackfilledGrants() throws Exception {
        // 1. Connect with NO agents in the DB.
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);
        awaitState("fixture", McpServer.Status.CONNECTED, 10);

        Tx.run(() -> assertEquals(0L, AgentSkillAllowedTool.count(
                "skillName = ?1", "mcp:fixture"),
                "with zero agents at connect time, no rows are written"));

        // 2. Now create a new agent the canonical way (AgentService.create).
        var agentId = commitInFreshTx(() -> services.AgentService.create(
                "fresh", "openrouter", "gpt-4.1").id);

        // 3. Backfill should have written one row for this agent.
        Tx.run(() -> {
            var agent = (Agent) Agent.findById(agentId);
            assertTrue(McpAllowlist.isAllowed(agent, "fixture", "echo"),
                    "newly-created agent must be backfilled with MCP grants");
            assertEquals(1L, AgentSkillAllowedTool.count(
                    "agent.id = ?1 AND skillName = ?2", agentId, "mcp:fixture"));
        });
    }

    // ==================== JCLAW-32: gated invocation + MCP_TOOL_INVOKE ====================

    @Test
    public void invokeViaToolRegistrySucceedsWhenAgentIsAllowed() throws Exception {
        var agentId = commitInFreshTx(() -> seedAgent("alpha").id);
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);
        awaitState("fixture", McpServer.Status.CONNECTED, 10);

        var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
        var result = ToolRegistry.execute(
                "mcp_fixture_echo", "{\"text\":\"world\"}", agent);
        assertEquals("echo:world", result);

        Tx.run(() -> {
            var events = EventLog.find(
                    "category = ?1 AND level = ?2", "MCP_TOOL_INVOKE", "INFO")
                    .<EventLog>fetch();
            assertFalse(events.isEmpty(),
                    "successful invoke must log an MCP_TOOL_INVOKE row at INFO");
            var ev = events.get(events.size() - 1);
            assertTrue(ev.message.contains("allowed"), "audit message must say allowed");
            assertTrue(ev.message.contains("echo"));
            assertTrue(ev.details.contains("world"), "details must carry args JSON");
        });
    }

    @Test
    public void invokeViaToolRegistryDeniesWhenAllowlistRowMissing() throws Exception {
        // Seed agent BEFORE connect so connect's broadcast grants it,
        // then DELETE the row to simulate an admin-denied scenario.
        var agentId = commitInFreshTx(() -> seedAgent("alpha").id);
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);
        awaitState("fixture", McpServer.Status.CONNECTED, 10);

        // Strip the broadcast grant for THIS agent (mimics what JCLAW-33
        // admin UI would do for per-agent revocation).
        commitInFreshTx(() -> {
            AgentSkillAllowedTool.delete(
                    "agent.id = ?1 AND skillName = ?2", agentId, "mcp:fixture");
            return null;
        });

        var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
        var result = ToolRegistry.execute(
                "mcp_fixture_echo", "{\"text\":\"world\"}", agent);
        assertTrue(result.contains("not on the allowlist"),
                "denied invoke must return allowlist error: " + result);

        Tx.run(() -> {
            var denied = EventLog.find(
                    "category = ?1 AND level = ?2", "MCP_TOOL_INVOKE", "WARN")
                    .<EventLog>fetch();
            assertFalse(denied.isEmpty(),
                    "denied invoke must log MCP_TOOL_INVOKE at WARN");
            assertTrue(denied.get(denied.size() - 1).message.contains("denied"));
        });
    }

    @Test
    public void shutdownTearsDownAllConnections() throws Exception {
        var s1 = seedStdioServer("fix1", FIXTURE_SCRIPT);
        var s2 = seedStdioServer("fix2", FIXTURE_SCRIPT);
        McpConnectionManager.connect(s1);
        McpConnectionManager.connect(s2);
        awaitState("fix1", McpServer.Status.CONNECTED, 10);
        awaitState("fix2", McpServer.Status.CONNECTED, 10);
        assertEquals(2, McpConnectionManager.connectionCount());

        McpConnectionManager.shutdown();
        assertEquals(0, McpConnectionManager.connectionCount(),
                "shutdown() must remove every entry");
        assertFalse(toolRegistered("mcp_fix1_echo"));
        assertFalse(toolRegistered("mcp_fix2_echo"));
    }

    // ==================== fixtures ====================

    private McpServer seedStdioServer(String name, String script) throws IOException {
        var path = Files.createTempFile("mcp-srv-" + name + "-", ".js");
        Files.writeString(path, script);
        path.toFile().deleteOnExit();
        var cfg = new JsonObject();
        cfg.addProperty("command", "node");
        var args = new com.google.gson.JsonArray();
        args.add(path.toString());
        cfg.add("args", args);
        return seedServer(name, McpServer.Transport.STDIO, cfg.toString());
    }

    private McpServer seedServer(String name, McpServer.Transport transport, String cfgJson) {
        return Tx.run(() -> {
            var srv = new McpServer();
            srv.name = name;
            srv.enabled = true;
            srv.transport = transport;
            srv.configJson = cfgJson;
            srv.save();
            return srv;
        });
    }

    private static Agent seedAgent(String name) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.enabled = true;
        a.save();
        return a;
    }

    /** Run {@code block} in a fresh virtual thread + Tx so the commit lands
     *  before the calling thread proceeds — needed when seed data must be
     *  visible to a subsequent connector VT (mirrors WebhookControllerTest's
     *  pattern, since UnitTest's outer carrier tx otherwise holds writes
     *  uncommitted from peer threads' POV). */
    private static <T> T commitInFreshTx(java.util.concurrent.Callable<T> block) {
        var holder = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try { holder.set(Tx.run(() -> {
                try { return block.call(); }
                catch (Exception e) { throw new RuntimeException(e); }
            })); }
            catch (Throwable e) { err.set(e); }
        });
        try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (err.get() != null) throw new RuntimeException(err.get());
        return holder.get();
    }

    private void awaitState(String name, McpServer.Status target, long maxSeconds) throws InterruptedException {
        var deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(maxSeconds);
        while (System.currentTimeMillis() < deadline) {
            if (McpConnectionManager.status(name) == target) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + name + " to reach " + target
                + " (current=" + McpConnectionManager.status(name)
                + ", lastError=" + McpConnectionManager.lastError(name) + ")");
    }

    private static boolean toolRegistered(String name) {
        return ToolRegistry.listTools().stream().anyMatch(t -> t.name().equals(name));
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
