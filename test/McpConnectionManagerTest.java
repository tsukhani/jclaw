import agents.McpServerCatalog;
import agents.SystemPromptAssembler;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import mcp.McpAllowlist;
import mcp.McpConnectionManager;
import mcp.McpServerTool;
import mcp.jsonrpc.JsonRpc;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.EventLog;
import models.McpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.EventLogger;
import services.Tx;
import utils.AppClock;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.ToolErrorTemplates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
class McpConnectionManagerTest extends UnitTest {

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
              } else if (m.id !== undefined) {
                send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'Method not found' } });
              }
            });
            function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n'); }
            """;

    /** Handshakes normally, then exits — a server that dies after connecting,
     *  which is what drives the watchdog's teardown-and-reconnect path. The
     *  300 ms is measured from spawn and the handshake takes tens of ms, so
     *  tools/list always lands first. */
    private static final String DIES_AFTER_HANDSHAKE_SCRIPT =
            FIXTURE_SCRIPT + "setTimeout(() => process.exit(0), 300);\n";

    /** Handshakes normally, then exits the instant a {@code die} tool call arrives.
     *  Lets a test time disconnect detection from a known instant rather than
     *  guessing when the subprocess died. */
    private static final String DIES_ON_TOOL_CALL_SCRIPT = FIXTURE_SCRIPT.replace(
            "if (m.method === 'tools/call') {",
            "if (m.method === 'tools/call' && m.params.name === 'die') { process.exit(0); }"
                    + " else if (m.method === 'tools/call') {");

    private Path fixturePath;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH; skipping");
        Fixtures.deleteDatabase();
        EventLogger.clear();
        // Clear out any tools other tests may have registered.
        ToolRegistrySync.publishForTest(List.of());  // JCLAW-894: under the registry lock
        // Aggressively shrink the backoff window so this test stays under a few seconds.
        McpConnectionManager.setBackoff(50, 200);
        fixturePath = Files.createTempFile("mcp-cm-fixture-", ".js");
        Files.writeString(fixturePath, FIXTURE_SCRIPT);
    }

    @AfterEach
    void tearDown() throws Exception {
        McpConnectionManager.shutdown();
        EventLogger.clear();
        // Restore the production backoff + first-attempt-timeout numbers so
        // any later test isn't perturbed (JCLAW-288 added the timeout knob).
        McpConnectionManager.setBackoff(1_000, 30_000);
        McpConnectionManager.setFirstAttemptRequestTimeout(Duration.ofSeconds(120));
        if (fixturePath != null) Files.deleteIfExists(fixturePath);
        // Re-run native tool registration so the next test sees the canonical set.
        ToolRegistrySync.release();
    }

    // ==================== happy path ====================

    @Test
    void connectsAndRegistersToolsWithRegistry() throws Exception {
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);

        awaitState("fixture", McpServer.Status.CONNECTED, 10);
        assertTrue(ToolRegistry.listTools().stream()
                .anyMatch(t -> t.name().equals("mcp_fixture_echo")),
                "MCP-discovered tool should appear in the registry as mcp_fixture_echo");
    }

    @Test
    void callToolDispatchesThroughAdapter() throws Exception {
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
    void connectEmitsMcpConnectEventLog() throws Exception {
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);

        // Polled, not read once: the connect path flips status to CONNECTED
        // (McpConnectionManager L385) and only then writes the event (L390), two DB
        // writes later. awaitState returning therefore does not mean the event is
        // queued, so a single read races that window and fails on a loaded
        // machine. The guarantee the flip does carry is tools + allowlist, not this.
        var connectEvents = captureServerEvents("MCP_CONNECT", "fixture", 10, () -> {
            McpConnectionManager.connect(server);
            awaitState("fixture", McpServer.Status.CONNECTED, 10);
        });
        assertFalse(connectEvents.isEmpty(),
                "MCP_CONNECT event must be logged on successful connection");
        assertTrue(connectEvents.getLast().message().contains("fixture"),
                "MCP_CONNECT event must mention server name: " + connectEvents.getLast().message());
    }

    // ==================== unhappy paths ====================

    @Test
    void unreachableHttpServerSurfacesErrorAndRetriesWithBackoff() {
        // Point at a port that is almost certainly not listening.
        var cfg = new JsonObject();
        cfg.addProperty("url", "http://127.0.0.1:1/mcp");
        var server = seedServer("dead-http", McpServer.Transport.HTTP, cfg.toString());

        var attemptLogs = captureServerEvents("MCP_CONNECT", "dead-http", 5, () -> {
            McpConnectionManager.connect(server);
            // Wait until at least one connect attempt has failed and status is ERROR.
            awaitState("dead-http", McpServer.Status.ERROR, 5);
        });
        assertNotNull(McpConnectionManager.lastError("dead-http"),
                "lastError must be set after connect failure");
        assertFalse(attemptLogs.isEmpty(),
                "Failed connect attempts must be logged under MCP_CONNECT");
    }

    @Test
    void stopRemovesToolsAndCancelsRetry() throws Exception {
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

    @Test
    void toolListNeverOutlivesConnectedStatus() throws Exception {
        // The watchdog teardown leaves entry.client attached while dropping the entry
        // out of CONNECTED, so a null-check-only read serves the dead client's list.
        McpConnectionManager.setBackoff(400, 800);  // widen the window setUp shrinks
        var server = seedStdioServer("flaps", DIES_AFTER_HANDSHAKE_SCRIPT);
        McpConnectionManager.connect(server);
        awaitState("flaps", McpServer.Status.CONNECTED, 10);
        assertFalse(McpConnectionManager.tools("flaps").isEmpty(),
                "a CONNECTED server must advertise its tools");

        // Sample across several death/reconnect cycles. Asserts no timing of
        // its own — only that the two reads never disagree, whenever we look.
        var deadline = System.currentTimeMillis() + 3_000;
        var sawNotConnected = false;
        while (System.currentTimeMillis() < deadline) {
            if (McpConnectionManager.status("flaps") != McpServer.Status.CONNECTED) {
                sawNotConnected = true;
                assertFalse(sustainedDisagreement(),
                        "tools() must report nothing while the entry is not CONNECTED");
            }
            Thread.sleep(10);
        }
        // Known-zero guard: without a single non-CONNECTED sample the loop above
        // asserted nothing, and a vacuous pass would read as coverage.
        assertTrue(sawNotConnected, "fixture should have died at least once");
    }

    @Test
    void disconnectDetectionIsEventDrivenNotPolled() throws Exception {
        // JCLAW-752: the watchdog used to be a VT per server parked in
        // Thread.sleep(250) polling client.state(), so detection landed a uniform
        // 0-250 ms after the server died — under 60 ms roughly one death in four.
        // The transport-error callback lands in single-digit ms every time, so a
        // 6-of-8 quorum discriminates the two sharply while still absorbing a
        // couple of scheduling hiccups on a loaded box.
        var server = seedStdioServer("dies", DIES_ON_TOOL_CALL_SCRIPT);
        McpConnectionManager.connect(server);

        var prompt = 0;
        var latencies = new ArrayList<Long>();
        for (var cycle = 0; cycle < 8; cycle++) {
            awaitState("dies", McpServer.Status.CONNECTED, 10);
            var killedAt = System.nanoTime();
            // The fixture exits without answering, so the in-flight call fails as
            // the transport dies — that failure is our zero point.
            assertThrows(Exception.class,
                    () -> McpConnectionManager.callTool("dies", "die", new JsonObject()));
            var deadline = System.currentTimeMillis() + 3_000;
            while (McpConnectionManager.status("dies") == McpServer.Status.CONNECTED
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(1);
            }
            assertNotEquals(McpServer.Status.CONNECTED, McpConnectionManager.status("dies"),
                    "cycle " + cycle + ": the manager never noticed the dead server");
            var millis = (System.nanoTime() - killedAt) / 1_000_000L;
            latencies.add(millis);
            if (millis < 60) prompt++;
        }

        assertTrue(prompt >= 6,
                "disconnect must land on the transport-error callback, not a poll tick; "
                        + "only " + prompt + "/8 deaths were noticed within 60 ms " + latencies);
    }

    // ==================== JCLAW-1442: a lost HTTP session reconnects ====================

    @Test
    void aNotFoundOnASessionBearingCallReconnectsWithoutTheOldSessionId() throws Exception {
        assertSessionLossReconnectsFresh(404);
    }

    @Test
    void aBadRequestOnASessionBearingCallReconnectsWithoutTheOldSessionId() throws Exception {
        assertSessionLossReconnectsFresh(400);
    }

    /** Hands out {@code sess-1}, answers its first tool call with {@code status}, then
     *  hands the next initialize {@code sess-2}; records each initialize's session header. */
    private void assertSessionLossReconnectsFresh(int status) throws Exception {
        var name = "http-sess-" + status + "-" + UUID.randomUUID().toString().substring(0, 8);
        var initSessions = new CopyOnWriteArrayList<Optional<String>>();
        try (var http = new MockWebServer()) {
            http.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    var msg = JsonRpc.decode(request.getBody().utf8());
                    if (!(msg instanceof JsonRpc.Request r)) return new MockResponse.Builder().code(202).build();
                    var sent = Optional.ofNullable(request.getHeaders().get("Mcp-Session-Id"));
                    return switch (r.method()) {
                        case "initialize" -> {
                            initSessions.add(sent);
                            yield rpc(200, r.id(), "{}")
                                    .addHeader("Mcp-Session-Id", "sess-" + initSessions.size()).build();
                        }
                        case "tools/list" -> rpc(200, r.id(), "{\"tools\":[]}").build();
                        default -> sent.equals(Optional.of("sess-1"))
                                ? rpc(status, r.id(), null).build()
                                : rpc(200, r.id(), "{\"content\":[]}").build();
                    };
                }
            });
            http.start();
            var cfg = new JsonObject();
            cfg.addProperty("url", http.url("/mcp").toString());
            McpConnectionManager.connect(seedServer(name, McpServer.Transport.HTTP, cfg.toString()));
            awaitState(name, McpServer.Status.CONNECTED, 10);

            assertThrows(Exception.class, () -> McpConnectionManager.callTool(name, "t", new JsonObject()));

            var deadline = System.currentTimeMillis() + 10_000;
            while ((initSessions.size() < 2 || McpConnectionManager.status(name) != McpServer.Status.CONNECTED)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(2, initSessions.size(), "the session loss must reconnect: " + initSessions);
            assertEquals(Optional.empty(), initSessions.get(0));
            assertEquals(Optional.empty(), initSessions.get(1), "the reconnect must not resend the old session id");
            awaitState(name, McpServer.Status.CONNECTED, 10);
            assertFalse(McpConnectionManager.callTool(name, "t", new JsonObject()).isError());
        } finally {
            McpConnectionManager.stop(name);
            CircuitBreakers.remove("mcp:" + name);
        }
    }

    private static MockResponse.Builder rpc(int code, Object id, @Nullable String result) {
        var body = result != null
                ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}"
                : "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32001,\"message\":\"gone\"}}";
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body);
    }

    /** Status and tools are two reads, so one disagreeing sample can be an
     *  artifact of a legitimate transition landing between them. Require it to
     *  persist — under the bug it lasts the whole reconnect, not 30 ms. */
    private static boolean sustainedDisagreement() throws InterruptedException {
        for (var i = 0; i < 3; i++) {
            if (McpConnectionManager.status("flaps") == McpServer.Status.CONNECTED
                    || McpConnectionManager.tools("flaps").isEmpty()) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    // ==================== JCLAW-32: allowlist + MCP_TOOL_UNREGISTER ====================

    @Test
    void connectWritesAllowlistRowsForExistingAgents() throws Exception {
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
    void stopClearsAllowlistAndEmitsMcpToolUnregisterEvent() throws Exception {
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
            // stop() saves this audit row directly, bypassing EventLogger, so only the table holds it.
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
    void agentCreatedAfterConnectGetsBackfilledGrants() throws Exception {
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
    void invokeViaToolRegistrySucceedsWhenAgentIsAllowed() throws Exception {
        var agentId = commitInFreshTx(() -> seedAgent("alpha").id);
        var server = seedStdioServer("fixture", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);
        awaitState("fixture", McpServer.Status.CONNECTED, 10);

        var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
        var result = ToolRegistry.execute(
                "mcp_fixture_echo", "{\"text\":\"world\"}", agent);
        assertEquals("echo:world", result);

        Tx.run(() -> {
            // McpToolAdapter saves this audit row directly, bypassing EventLogger, so only the table holds it.
            var events = EventLog.find(
                    "category = ?1 AND level = ?2 AND agentId = ?3", "MCP_TOOL_INVOKE", "INFO",
                    String.valueOf(agentId))
                    .<EventLog>fetch();
            assertFalse(events.isEmpty(),
                    "successful invoke must log an MCP_TOOL_INVOKE row at INFO");
            var ev = events.get(events.size() - 1);
            assertEquals("fixture/echo by 'alpha' [allowed]", ev.message);
            assertTrue(ev.details.contains("world"), "details must carry args JSON");
        });
    }

    @Test
    void invokeViaToolRegistryDeniesWhenAllowlistRowMissing() throws Exception {
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
            // McpToolAdapter saves this audit row directly, bypassing EventLogger, so only the table holds it.
            var denied = EventLog.find(
                    "category = ?1 AND level = ?2 AND agentId = ?3", "MCP_TOOL_INVOKE", "WARN",
                    String.valueOf(agentId))
                    .<EventLog>fetch();
            assertFalse(denied.isEmpty(),
                    "denied invoke must log MCP_TOOL_INVOKE at WARN");
            assertTrue(denied.get(denied.size() - 1).message.contains("denied"));
        });
    }

    @Test
    void shutdownTearsDownAllConnections() throws Exception {
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

    // ==================== JCLAW-388: per-server approval flag → isDangerous ====================

    @Test
    void approvalFlaggedServerMakesHandleAndAdapterDangerous() throws Exception {
        // Seed a server opted into the approval gate, connect it, and confirm
        // the wire-format name the LLM emits (the server-level handle
        // mcp_<server>) resolves dangerous=true via ToolRegistry.isDangerous —
        // which is exactly what DangerousActionGate reads. The per-action
        // adapter (mcp_<server>_<tool>) must gate identically as defense-in-depth.
        var server = seedStdioServer("flagged", FIXTURE_SCRIPT, true);
        McpConnectionManager.connect(server);
        awaitState("flagged", McpServer.Status.CONNECTED, 10);

        assertTrue(McpConnectionManager.requiresApproval("flagged"),
                "manager must report the captured approval flag for the live entry");
        assertTrue(ToolRegistry.isDangerous("mcp_flagged"),
                "server-level handle of an approval-flagged server must be dangerous "
                        + "(this is the name the LLM emits, so the gate fires)");
        assertTrue(ToolRegistry.isDangerous("mcp_flagged_echo"),
                "per-action adapter of an approval-flagged server must be dangerous too");
        // DangerousActionGate.guard asks this, with the call's own arguments, before the tool runs.
        assertTrue(ToolRegistry.isDangerous("mcp_flagged", "{\"read\":\"fixture://doc\"}"),
                "a read is gated like any call to a whole approval-flagged server");
    }

    @Test
    void unflaggedServerLeavesHandleAndAdapterNonDangerous() throws Exception {
        // Default requiresApproval=false ⇒ behavior unchanged from before the
        // flag existed: the MCP handle and adapters bypass the gate entirely.
        var server = seedStdioServer("plain", FIXTURE_SCRIPT, false);
        McpConnectionManager.connect(server);
        awaitState("plain", McpServer.Status.CONNECTED, 10);

        assertFalse(McpConnectionManager.requiresApproval("plain"),
                "default-off server must not require approval");
        assertFalse(ToolRegistry.isDangerous("mcp_plain"),
                "unflagged server's handle must stay non-dangerous (no prompt)");
        assertFalse(ToolRegistry.isDangerous("mcp_plain_echo"),
                "unflagged server's adapter must stay non-dangerous (no prompt)");
        assertFalse(ToolRegistry.isDangerous("mcp_plain", "{\"read\":\"fixture://doc\"}"));
    }

    @Test
    void requiresApprovalIsFalseForUnknownServer() {
        // No live entry ⇒ false. A disconnected server has no tools to gate,
        // and the dispatch-path lookup must never NPE on a missing entry.
        assertFalse(McpConnectionManager.requiresApproval("never-connected"),
                "a server with no in-memory entry must resolve to no-approval");
    }

    // ==================== JCLAW-288: synchronous first-attempt connect ====================

    /** Variant of {@link #FIXTURE_SCRIPT} that sleeps {@code DELAY_MS} ms
     *  before responding to {@code initialize}. Stands in for a cold-cache
     *  uvx/npx subprocess whose package install is still running when the
     *  client sends initialize. */
    private static final String SLOW_HANDSHAKE_FIXTURE = """
            const readline = require('readline');
            const DELAY_MS = parseInt(process.env.SLOW_HANDSHAKE_MS || '0', 10);
            const rl = readline.createInterface({ input: process.stdin, terminal: false });
            rl.on('line', (line) => {
              if (!line.trim()) return;
              let m;
              try { m = JSON.parse(line); } catch (e) { return; }
              if (m.method === 'initialize') {
                setTimeout(() => send({ jsonrpc: '2.0', id: m.id,
                  result: { protocolVersion: '2025-06-18', capabilities: { tools: {} },
                            serverInfo: { name: 'slow', version: '0.0.1' } } }), DELAY_MS);
              } else if (m.method === 'tools/list') {
                send({ jsonrpc: '2.0', id: m.id, result: { tools: [] } });
              } else if (m.id !== undefined) {
                send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'Method not found' } });
              }
            });
            function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n'); }
            """;

    /** Subprocess that exits with code 0 immediately after spawn, without
     *  writing anything to stdout. Triggers the "stdio EOF" failure mode
     *  observed for cold-cache uvx subprocesses whose package install
     *  failed before initialize could be answered. */
    private static final String EXIT_IMMEDIATELY_FIXTURE = """
            process.exit(0);
            """;

    @Test
    void connectAndAwaitCompletesOnSuccessfulHandshake() throws Exception {
        var server = seedStdioServer("await-ok", FIXTURE_SCRIPT);
        var future = McpConnectionManager.connectAndAwait(server);
        // Should complete in well under 5 s on a warm Node fixture.
        future.get(5, TimeUnit.SECONDS);
        assertEquals(McpServer.Status.CONNECTED,
                McpConnectionManager.status("await-ok"),
                "future must complete only after first attempt resolves to CONNECTED");
    }

    @Test
    void connectAndAwaitTakesLongerThanDefaultTimeoutWhenInitializeIsSlow() throws Exception {
        // The McpClient steady-state timeout is 30 s; the first-attempt knob
        // is what JCLAW-288 extends. Pin the knob to a small value here so
        // we exercise the long-timeout branch without waiting 120 s, and
        // give the fixture enough delay (1.5 s) to prove the test isn't
        // accidentally passing at the default.
        McpConnectionManager.setFirstAttemptRequestTimeout(Duration.ofSeconds(5));
        var path = writeFixture("slow-handshake", SLOW_HANDSHAKE_FIXTURE);
        var cfg = new JsonObject();
        cfg.addProperty("command", "node");
        var args = new com.google.gson.JsonArray();
        args.add(path.toString());
        cfg.add("args", args);
        var env = new JsonObject();
        env.addProperty("SLOW_HANDSHAKE_MS", "1500");
        cfg.add("env", env);
        var server = seedServer("await-slow", McpServer.Transport.STDIO, cfg.toString());

        var future = McpConnectionManager.connectAndAwait(server);
        future.get(8, TimeUnit.SECONDS);
        assertEquals(McpServer.Status.CONNECTED,
                McpConnectionManager.status("await-slow"),
                "1.5 s handshake must succeed under a 5 s first-attempt timeout");
    }

    @Test
    void connectAndAwaitCompletesOnSubprocessExitedBeforeInitialize() throws Exception {
        var server = seedStdioServer("await-eof", EXIT_IMMEDIATELY_FIXTURE);
        var future = McpConnectionManager.connectAndAwait(server);
        // Subprocess exit should surface within a second or so; the future
        // completes (successfully — it's a signal, not a status) and the
        // entry's live state reports ERROR with the stdio EOF message that
        // matches what production logs.
        future.get(5, TimeUnit.SECONDS);
        assertEquals(McpServer.Status.ERROR,
                McpConnectionManager.status("await-eof"),
                "subprocess exit must surface as ERROR");
        var lastError = McpConnectionManager.lastError("await-eof");
        assertNotNull(lastError, "lastError must be set on failure");
        assertTrue(lastError.contains("EOF") || lastError.contains("stdio"),
                "lastError must point at the EOF, got: " + lastError);
    }

    @Test
    void stopCancelsPendingFirstAttemptFuture() throws Exception {
        // Slow fixture that won't respond within our shrunken window —
        // we just need the future to be pending when stop() runs.
        McpConnectionManager.setFirstAttemptRequestTimeout(Duration.ofSeconds(30));
        var path = writeFixture("slow-cancel", SLOW_HANDSHAKE_FIXTURE);
        var cfg = new JsonObject();
        cfg.addProperty("command", "node");
        var args = new com.google.gson.JsonArray();
        args.add(path.toString());
        cfg.add("args", args);
        var env = new JsonObject();
        env.addProperty("SLOW_HANDSHAKE_MS", "20000");
        cfg.add("env", env);
        var server = seedServer("await-cancel", McpServer.Transport.STDIO, cfg.toString());

        var future = McpConnectionManager.connectAndAwait(server);
        // Give the connector VT a moment to actually start the subprocess.
        Thread.sleep(200);
        McpConnectionManager.stop("await-cancel");
        try {
            future.get(2, TimeUnit.SECONDS);
            fail("future must complete with cancellation, not normally");
        } catch (CancellationException _) {
            // good
        }
    }

    @Test
    void plainConnectStaysFireAndForget() throws Exception {
        // The bootless connect() path (used by startAll on JVM boot) does
        // NOT populate firstAttemptFuture; the entry's volatile field stays
        // null and signalFirstAttemptResolved is a no-op. We can only assert
        // this indirectly: connect() returns without blocking, then we wait
        // for CONNECTED the same way the older tests do.
        var server = seedStdioServer("await-none", FIXTURE_SCRIPT);
        McpConnectionManager.connect(server);
        awaitState("await-none", McpServer.Status.CONNECTED, 5);
    }

    // ==================== JCLAW-1445: tool-list freshness over HTTP ====================

    private record Arrival(String method) {}

    @FunctionalInterface
    private interface Answer {
        MockResponse answer(Object id, int n) throws InterruptedException;
    }

    /** A 2026-07-28 server over HTTP; every request lands in {@code seen}, each method's n-th answered by its {@link Answer}. */
    private static MockWebServer modernHttp(boolean listChanged, List<Arrival> seen, Answer toolsList, Answer listen)
            throws IOException {
        var http = new MockWebServer();
        var counts = new ConcurrentHashMap<String, AtomicInteger>();
        http.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                var msg = JsonRpc.decode(request.getBody().utf8());
                if (!(msg instanceof JsonRpc.Request r)) return new MockResponse.Builder().code(202).build();
                seen.add(new Arrival(r.method()));
                var n = counts.computeIfAbsent(r.method(), _ -> new AtomicInteger()).incrementAndGet();
                return switch (r.method()) {
                    case "server/discover" -> rpc(200, r.id(), "{\"supportedVersions\":[\"2026-07-28\"],"
                            + "\"capabilities\":{\"tools\":{\"listChanged\":" + listChanged + "}}}").build();
                    case "tools/list" -> toolsList.answer(r.id(), n);
                    case "subscriptions/listen" -> listen.answer(r.id(), n);
                    default -> rpc(200, r.id(), "{\"content\":[]}").build();
                };
            }
        });
        http.start();
        return http;
    }

    private static MockResponse toolsListing(Object id, @Nullable Long ttlMs, String... names) {
        var tools = new StringBuilder();
        for (var name : names) {
            if (!tools.isEmpty()) tools.append(',');
            tools.append("{\"name\":\"").append(name).append("\",\"inputSchema\":{\"type\":\"object\"}}");
        }
        return rpc(200, id, "{\"tools\":[" + tools + "]" + (ttlMs != null ? ",\"ttlMs\":" + ttlMs : "") + "}").build();
    }

    private static String sseEvent(String json) {
        return "data: " + json + "\n\n";
    }

    private static String subscriptionNotice(String method, Object id, String params) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":{" + params
                + "\"_meta\":{\"io.modelcontextprotocol/subscriptionId\":" + id + "}}}";
    }

    private static MockResponse.Builder sse(String body) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body);
    }

    private static String ackEvent(Object id) {
        return sseEvent(subscriptionNotice("notifications/subscriptions/acknowledged", id,
                "\"notifications\":{\"toolsListChanged\":true},"));
    }

    private static long count(List<Arrival> seen, String method) {
        return seen.stream().filter(a -> a.method().equals(method)).count();
    }

    private String connectHttp(String prefix, MockWebServer http) {
        var name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        var cfg = new JsonObject();
        cfg.addProperty("url", http.url("/mcp").toString());
        McpConnectionManager.connect(seedServer(name, McpServer.Transport.HTTP, cfg.toString()));
        return name;
    }

    private static void awaitTrue(String what, long maxMillis, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    @Test
    void aListChangedOnTheListenStreamRefetchesOnceAndRepublishes() throws Exception {
        var seen = new CopyOnWriteArrayList<Arrival>();
        String name = null;
        try (var http = modernHttp(true, seen,
                (id, n) -> n == 1 ? toolsListing(id, null, "a") : toolsListing(id, null, "a", "b"),
                (id, n) -> n == 1
                        ? sse(": ping\n\n" + ackEvent(id) + sseEvent(subscriptionNotice(
                                "notifications/tools/list_changed", id, ""))).build()
                        // The reopened stream stays quiet for the rest of the test.
                        : sse(ackEvent(id)).headersDelay(3, TimeUnit.SECONDS).build())) {
            name = connectHttp("listen", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var n = name;
            awaitTrue("the refreshed list", 5000, () -> McpConnectionManager.tools(n).size() == 2);
            assertTrue(toolRegistered(McpServer.toolName(name, "b")), "republished into the registry");
            Thread.sleep(300);
            assertEquals(2, count(seen, "tools/list"), "one tools/list after the connect's: " + seen);
            assertEquals(McpServer.Status.CONNECTED, McpConnectionManager.status(name));
        } finally {
            if (name != null) {
                McpConnectionManager.stop(name);
                CircuitBreakers.remove("mcp:" + name);
            }
        }
    }

    /** mcp.context7.com reported {@code listChanged} false on 2026-10-11. */
    @Test
    void aServerNotOfferingListChangedGetsNoListenRequest() throws Exception {
        var seen = new CopyOnWriteArrayList<Arrival>();
        String name = null;
        try (var http = modernHttp(false, seen, (id, _) -> toolsListing(id, null, "a"),
                (id, _) -> sse(ackEvent(id)).build())) {
            name = connectHttp("nolisten", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            Thread.sleep(300);
            assertEquals(0, count(seen, "subscriptions/listen"), seen.toString());
        } finally {
            if (name != null) {
                McpConnectionManager.stop(name);
                CircuitBreakers.remove("mcp:" + name);
            }
        }
    }

    @Test
    void aDroppedListenStreamIsReopenedAfterTheBackoffNotInATightLoop() throws Exception {
        McpConnectionManager.setBackoff(400, 400);
        var seen = new CopyOnWriteArrayList<Arrival>();
        String name = null;
        try (var http = modernHttp(true, seen, (id, _) -> toolsListing(id, null, "a"), (id, _) -> sse(ackEvent(id)).build())) {
            name = connectHttp("reopen", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            awaitTrue("a reopened listen", 5000, () -> count(seen, "subscriptions/listen") >= 2);
            assertEquals(400, McpConnectionManager.listenReopenDelayMillis(name), "the reopen waited the backoff");
            assertEquals(McpServer.Status.CONNECTED, McpConnectionManager.status(name), "a dropped stream is not a dropped connection");
            assertFalse(McpConnectionManager.callTool(name, "a", new JsonObject()).isError());
        } finally {
            if (name != null) {
                McpConnectionManager.stop(name);
                CircuitBreakers.remove("mcp:" + name);
            }
        }
    }

    @Test
    void aStreamThatHangsUpRightAfterItsAcknowledgmentBacksOffFurtherEachTime() throws Exception {
        McpConnectionManager.setBackoff(150, 2_000);
        var seen = new CopyOnWriteArrayList<Arrival>();
        String name = null;
        // Held 50 ms past the acknowledgment, so the manager hears it before the hang-up.
        try (var http = modernHttp(true, seen, (id, _) -> toolsListing(id, null, "a"), (id, _) -> sse(ackEvent(id) + ": bye\n\n")
                .throttleBody(ackEvent(id).length(), 50, TimeUnit.MILLISECONDS).build())) {
            name = connectHttp("hangup", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            awaitTrue("a fourth listen", 5000, () -> count(seen, "subscriptions/listen") >= 4);
            var delay = McpConnectionManager.listenReopenDelayMillis(name);
            assertTrue(delay >= 600, "the third reopen waited " + delay + " ms, not the initial 150");
        } finally {
            if (name != null) {
                McpConnectionManager.stop(name);
                CircuitBreakers.remove("mcp:" + name);
            }
        }
    }

    @Test
    void aStaleZeroTtlListIsRefetchedOnceOffTheTurnAndTheOldActionsServed() throws Exception {
        var seen = new CopyOnWriteArrayList<Arrival>();
        var refetches = new LinkedBlockingQueue<Integer>();
        var release = new CountDownLatch(1);
        String name = null;
        try (var http = modernHttp(false, seen, heldRefetch(refetches, release), (id, _) -> sse(ackEvent(id)).build())) {
            name = connectHttp("ttl", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            var later = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(61));
            var actions = AppClock.callWith(later, () -> tool.execute("{}", new Agent()));
            assertTrue(actions.contains("\"a\"") && !actions.contains("\"b\""), actions);
            assertEquals(2, refetches.poll(5, TimeUnit.SECONDS));
            release.countDown();
            var n = name;
            awaitTrue("the refreshed list", 5000, () -> McpConnectionManager.tools(n).size() == 2);
            AppClock.callWith(later, () -> tool.execute("{}", new Agent()));
            assertNull(refetches.poll(300, TimeUnit.MILLISECONDS), "the minimum interval holds: " + seen);
        } finally {
            release.countDown();
            if (name != null) {
                McpConnectionManager.stop(name);
                CircuitBreakers.remove("mcp:" + name);
            }
        }
    }

    /** tools/list answering {@code a} with a zero ttlMs, then each refetch, recorded and held until {@code release}, {@code a, b}. */
    private static Answer heldRefetch(BlockingQueue<Integer> refetches, CountDownLatch release) {
        return (id, n) -> {
            if (n == 1) return toolsListing(id, 0L, "a");
            refetches.add(n);
            release.await(5, TimeUnit.SECONDS);
            return toolsListing(id, 0L, "a", "b");
        };
    }

    @Test
    void theSystemPromptCatalogRefreshesAStaleListOnce() throws Exception {
        var seen = new CopyOnWriteArrayList<Arrival>();
        var refetches = new LinkedBlockingQueue<Integer>();
        var release = new CountDownLatch(1);
        String name = null;
        try (var http = modernHttp(false, seen, heldRefetch(refetches, release), (id, _) -> sse(ackEvent(id)).build())) {
            name = connectHttp("catalog", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var later = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(61));
            var catalog = AppClock.callWith(later, () -> Tx.run(() -> McpServerCatalog.formatCatalogForPrompt(Set.of())));
            assertTrue(catalog.contains(name), catalog);
            assertEquals(2, refetches.poll(5, TimeUnit.SECONDS));
            AppClock.callWith(later, () -> Tx.run(() -> McpServerCatalog.formatCatalogForPrompt(Set.of())));
            assertNull(refetches.poll(300, TimeUnit.MILLISECONDS), "exactly one refetch: " + seen);
        } finally {
            release.countDown();
            if (name != null) {
                McpConnectionManager.stop(name);
                CircuitBreakers.remove("mcp:" + name);
            }
        }
    }

    @Test
    void anIdLessListChangedOnAnHttpListenStreamRefetches() throws Exception {
        var seen = new CopyOnWriteArrayList<Arrival>();
        var refetches = new LinkedBlockingQueue<Integer>();
        String name = null;
        try (var http = modernHttp(true, seen, (id, n) -> {
            if (n > 1) refetches.add(n);
            return n == 1 ? toolsListing(id, null, "a") : toolsListing(id, null, "a", "b");
        }, (id, n) -> n == 1
                ? sse(ackEvent(id) + sseEvent("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}"))
                        .build()
                : sse(ackEvent(id)).headersDelay(3, TimeUnit.SECONDS).build())) {
            name = connectHttp("idless", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            assertEquals(2, refetches.poll(5, TimeUnit.SECONDS), "an id-less list_changed counts over HTTP");
            assertNull(refetches.poll(300, TimeUnit.MILLISECONDS), seen.toString());
        } finally {
            if (name != null) {
                McpConnectionManager.stop(name);
                CircuitBreakers.remove("mcp:" + name);
            }
        }
    }

    // ==================== resources over HTTP ====================

    private static final String RESOURCE_CAPS = "{\"tools\":{},\"resources\":{}}";
    private static final String FLIGHTS = "serpapi://engines/google_flights";
    private static final String SEARCH_ACTION =
            "{\"name\":\"search\",\"description\":\"\",\"inputSchema\":{\"type\":\"object\"}}";
    private static final String SERPAPI_RESOURCES = "\"resources\":[{\"uri\":\"serpapi://engines\","
            + "\"name\":\"serpapi-engines-index\",\"mimeType\":\"application/json\"}],"
            + "\"resourceTemplates\":[{\"uriTemplate\":\"serpapi://engines/{engine}\",\"name\":\"engine\"}]";
    private static final String NOT_LISTED =
            "\"resourcesNote\":\"The server's resources and resource templates could not be listed just now.\"";

    /** A handshake-era fixture declaring resources that exits the instant a read arrives. */
    private static final String DIES_ON_READ_SCRIPT = FIXTURE_SCRIPT
            .replace("capabilities: { tools: {} }", "capabilities: { tools: {}, resources: {} }")
            .replace("} else if (m.method === 'tools/call') {",
                    "} else if (m.method === 'resources/read') { process.exit(0); } else if (m.method === 'tools/call') {");

    @FunctionalInterface
    private interface Reply {
        MockResponse reply(JsonRpc.Request request);
    }

    private static MockWebServer capableHttp(String capabilities, List<JsonRpc.Request> seen, Reply reply)
            throws IOException {
        return capableHttp(capabilities, new String[] {"search"}, seen, reply);
    }

    /** A 2026-07-28 server listing {@code tools}, declaring {@code capabilities}; every other request is {@code reply}'s. */
    private static MockWebServer capableHttp(String capabilities, String[] tools, List<JsonRpc.Request> seen,
                                             Reply reply) throws IOException {
        var http = new MockWebServer();
        http.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                var msg = JsonRpc.decode(request.getBody().utf8());
                if (!(msg instanceof JsonRpc.Request r)) return new MockResponse.Builder().code(202).build();
                seen.add(r);
                return switch (r.method()) {
                    case "server/discover" -> rpc(200, r.id(), "{\"supportedVersions\":[\"2026-07-28\"],"
                            + "\"capabilities\":" + capabilities + "}").build();
                    case "tools/list" -> toolsListing(r.id(), null, tools);
                    default -> reply.reply(r);
                };
            }
        });
        http.start();
        return http;
    }

    private static MockResponse rpcError(Object id, int code, String message) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}")
                .build();
    }

    /**
     * SerpAPI's shape: an engine index and a template per engine. google_flights reads; the {@code x://}
     * URIs answer with empty contents; broken is -32603, legacy -32002, and any other URI -32602.
     */
    private static Reply serpapiResources() {
        return r -> switch (r.method()) {
            case "resources/list" -> rpc(200, r.id(), "{\"resources\":[{\"uri\":\"serpapi://engines\","
                    + "\"name\":\"serpapi-engines-index\",\"mimeType\":\"application/json\"}]}").build();
            case "resources/templates/list" -> rpc(200, r.id(), "{\"resourceTemplates\":[{\"uriTemplate\":"
                    + "\"serpapi://engines/{engine}\",\"name\":\"engine\"}]}").build();
            case "resources/read" -> {
                var uri = ((JsonObject) Objects.requireNonNull(r.params())).get("uri").getAsString();
                yield switch (uri) {
                    case FLIGHTS -> rpc(200, r.id(), "{\"contents\":[{\"uri\":\"" + FLIGHTS + "\",\"mimeType\":"
                            + "\"application/json\",\"text\":\"{\\\"departure_id\\\":\\\"airport code\\\"}\"}]}").build();
                    case "x://empty-text" -> rpc(200, r.id(), "{\"contents\":[{\"uri\":\"x://empty-text\",\"text\":\"\"}]}").build();
                    case "x://bare" -> rpc(200, r.id(), "{\"contents\":[{\"uri\":\"x://bare\"}]}").build();
                    case "x://none" -> rpc(200, r.id(), "{\"contents\":[]}").build();
                    case "serpapi://broken" -> rpcError(r.id(), -32603, "SECRET upstream text");
                    case "serpapi://legacy" -> rpcError(r.id(), -32002, "SECRET upstream text");
                    default -> rpcError(r.id(), -32602, "SECRET upstream text");
                };
            }
            default -> rpc(200, r.id(), "{\"content\":[]}").build();
        };
    }

    private static void stopHttp(@Nullable String name) {
        if (name == null) return;
        McpConnectionManager.stop(name);
        CircuitBreakers.remove("mcp:" + name);
    }

    private static String readOf(String uri) {
        return "{\"read\":\"" + uri + "\"}";
    }

    private static long sent(List<JsonRpc.Request> seen, String method) {
        return seen.stream().filter(r -> r.method().equals(method)).count();
    }

    private static String handleDescription(String name) {
        return "MCP server `" + name + "`. Call with no arguments (or `{}`) to list the server's actions. A large"
                + " server lists names and summaries only: call with `{\"describe\": [\"<action>\", ...]}` for the"
                + " input schemas of the actions you will use. Call with `{\"tool\": \"<action>\", \"args\": {...}}`"
                + " to execute one action against the server.";
    }

    @Test
    void aResourceServerListsItsResourcesBesideItsActionsAndReadsOneWholeWithAnAuditRow() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = capableHttp(RESOURCE_CAPS, seen, serpapiResources())) {
            name = connectHttp("serpapi", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            assertTrue(McpConnectionManager.offersResources(name));
            var tool = new McpServerTool(name);

            assertEquals("{\"server\":\"" + name + "\",\"actions\":[" + SEARCH_ACTION + "]," + SERPAPI_RESOURCES + "}",
                    tool.execute("{}", reader));

            var read = new String[1];
            var events = EventLogger.captureForTest(() -> read[0] = tool.execute(readOf(FLIGHTS), reader));
            assertEquals("{\"departure_id\":\"airport code\"}", read[0]);
            var audit = events.stream().filter(e -> e.category().equals("MCP_TOOL_INVOKE")).toList();
            assertEquals(1, audit.size(), events.toString());
            assertEquals("INFO", audit.getFirst().level());
            assertEquals(name + " read \"" + FLIGHTS + "\" by 'reader' [allowed]", audit.getFirst().message());
            assertEquals(String.valueOf(reader.id), audit.getFirst().agentId());
            assertEquals(readOf(FLIGHTS), audit.getFirst().details());

            assertEquals(handleDescription(name) + " The list also shows the server's resources and resource templates:"
                    + " call with `{\"read\": \"<uri>\"}` to read one, by a listed URI or a template filled in.",
                    tool.description());
            @SuppressWarnings("unchecked")
            var props = (Map<String, Object>) tool.parameters().get("properties");
            assertEquals(Set.of("tool", "args", "describe", "read"), props.keySet());
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aReadWithoutAGrantIsRefusedAsAnActionIsAndSendsNoRequest() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("granted"));
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = capableHttp(RESOURCE_CAPS, seen, serpapiResources())) {
            name = connectHttp("grants", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            // Seeded after the connect broadcast, so it holds no row, as a subagent spawned before it would not.
            var stranger = commitInFreshTx(() -> seedAgent("stranger"));
            var tool = new McpServerTool(name);

            var refused = new String[1];
            var events = EventLogger.captureForTest(() -> refused[0] = tool.execute(readOf(FLIGHTS), stranger));
            assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.mcpNotAllowed(name, "read", "stranger")), refused[0]);
            assertEquals(0, sent(seen, "resources/read"));
            var audit = events.stream().filter(e -> e.category().equals("MCP_TOOL_INVOKE")).toList();
            assertEquals(1, audit.size(), events.toString());
            assertEquals("WARN", audit.getFirst().level());
            assertEquals(name + " read \"" + FLIGHTS + "\" by 'stranger' [denied]", audit.getFirst().message());

            assertEquals("{\"departure_id\":\"airport code\"}", tool.execute(readOf(FLIGHTS), reader));
            assertEquals(1, sent(seen, "resources/read"));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void anUnknownUriNamesItAndPointsAtTheListingWithoutTrippingTheBreaker() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = capableHttp(RESOURCE_CAPS, seen, serpapiResources())) {
            name = connectHttp("missing", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            // Three failures in a row open the breaker, so four not-founds would.
            for (var uri : List.of("serpapi://engines/nope", "serpapi://legacy", "serpapi://engines/nope", "serpapi://legacy")) {
                assertEquals("MCP server '" + name + "' has no resource '" + uri + "'. Call mcp_" + name
                        + " with {} to list its resources and templates.", tool.execute(readOf(uri), reader));
            }
            assertEquals(CircuitBreaker.State.CLOSED, McpConnectionManager.breaker(name).state());
            assertEquals(McpServer.Status.CONNECTED, McpConnectionManager.status(name));
            assertNull(McpConnectionManager.lastError(name));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aReadThatAsksForInputGivesTheModelTheQuestionAndLeavesTheBreakerClosed() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = capableHttp(RESOURCE_CAPS, seen, r -> r.method().equals("resources/read")
                ? rpc(200, r.id(), "{\"resultType\":\"input_required\",\"inputRequests\":{\"q\":{\"method\":"
                        + "\"elicitation/create\",\"params\":{\"message\":\"Which file?\",\"requestedSchema\":"
                        + "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}}}}").build()
                : serpapiResources().reply(r))) {
            name = connectHttp("asking", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            // Three failures in a row open the breaker; a server asking a question has not failed.
            for (var i = 0; i < 3; i++) {
                assertEquals("MCP server '" + name + "' asked for input that JClaw did not put to the operator, because"
                        + " JClaw does not ask the operator during a resource read or a listing, so the call ended with"
                        + " nothing sent back. The server asked:\n- \"Which file?\" Fields: \"path\" (string).\nYou can"
                        + " fill the values in as arguments and call again, ask in chat, or use another tool.",
                        tool.execute(readOf("x://a"), reader), "read " + i);
            }
            assertEquals(3, sent(seen, "resources/read"), "each read is sent once, never retried");
            assertEquals(CircuitBreaker.State.CLOSED, McpConnectionManager.breaker(name).state());
            assertEquals(McpServer.Status.CONNECTED, McpConnectionManager.status(name));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aReadRefusalAndEmptyContentsReachTheModelInJClawsOwnWords() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        String name = null;
        try (var http = capableHttp(RESOURCE_CAPS, new CopyOnWriteArrayList<>(), serpapiResources())) {
            name = connectHttp("refusing", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.mcpProtocolFailed(name, "read",
                    "[code=-32603] resources/read was refused")), tool.execute(readOf("serpapi://broken"), reader));
            for (var uri : List.of("x://empty-text", "x://bare", "x://none")) {
                assertEquals("MCP server '" + name + "' returned no contents for '" + uri + "'.",
                        tool.execute(readOf(uri), reader), uri);
            }
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aReadTheServerDiesOnIsAConnectionFailure() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var name = "diesonread-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            McpConnectionManager.connect(seedStdioServer(name, DIES_ON_READ_SCRIPT));
            awaitState(name, McpServer.Status.CONNECTED, 10);
            assertTrue(McpConnectionManager.offersResources(name));
            assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.mcpConnectionFailed(name, "read", "stdio EOF")),
                    new McpServerTool(name).execute(readOf("fixture://doc"), reader));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aGrantOnAnotherServerDoesNotLetAnAgentRead() throws Exception {
        String first = null;
        String second = null;
        try (var firstHttp = capableHttp(RESOURCE_CAPS, new CopyOnWriteArrayList<>(), serpapiResources());
             var secondHttp = capableHttp(RESOURCE_CAPS, new CopyOnWriteArrayList<>(), serpapiResources())) {
            first = connectHttp("first", firstHttp);
            awaitState(first, McpServer.Status.CONNECTED, 10);
            // Seeded between the two connects, so only the second's broadcast grants it a row.
            var stranger = commitInFreshTx(() -> seedAgent("stranger"));
            second = connectHttp("second", secondHttp);
            awaitState(second, McpServer.Status.CONNECTED, 10);

            assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.mcpNotAllowed(first, "read", "stranger")),
                    new McpServerTool(first).execute(readOf(FLIGHTS), stranger));
            assertEquals("{\"departure_id\":\"airport code\"}", new McpServerTool(second).execute(readOf(FLIGHTS), stranger));
        } finally {
            stopHttp(first);
            stopHttp(second);
        }
    }

    @Test
    void failingReadsCountAgainstTheBreakerAsCallsDo() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = capableHttp(RESOURCE_CAPS, seen, serpapiResources())) {
            name = connectHttp("failingreads", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            var refused = ToolErrorTemplates.render(ToolErrorTemplates.mcpProtocolFailed(name, "read",
                    "[code=-32603] resources/read was refused"));
            for (int i = 0; i < 3; i++) assertEquals(refused, tool.execute(readOf("serpapi://broken"), reader));
            assertEquals(CircuitBreaker.State.OPEN, McpConnectionManager.breaker(name).state());

            assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.mcpProtocolFailed(name, "read",
                    "MCP server '" + name + "' is failing fast: too many recent tool-call failures")),
                    tool.execute(readOf(FLIGHTS), reader));
            assertEquals(3, sent(seen, "resources/read"), "the fourth read never reached the server");
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aUriWithALineBreakIsAuditedQuotedOnOneLine() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        String name = null;
        try (var http = capableHttp(RESOURCE_CAPS, new CopyOnWriteArrayList<>(), serpapiResources())) {
            name = connectHttp("forged", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            var events = EventLogger.captureForTest(() -> tool.execute("{\"read\":\"x://a\\nINFO forged\"}", reader));
            assertEquals(List.of(name + " read \"x://a\\nINFO forged\" by 'reader' [allowed]"),
                    events.stream().filter(e -> e.category().equals("MCP_TOOL_INVOKE")).map(EventLogger.Captured::message).toList());
        } finally {
            stopHttp(name);
        }
    }

    // ==================== MCP Apps (SEP-1865) ====================

    private static final String PAGE_URI = "ui://prefab/tool/583d325b0e9c/renderer.html";
    /** What the model reads from {@code search_table}: its text, then the data its view draws. */
    private static final String VIEW_TEXT = "3 results\n\nStructured content (the data the tool's view draws):\n{\"rows\":[1,2,3]}";

    /**
     * SerpAPI's view shape: {@code search_table} draws {@code PAGE_URI}, {@code refresh} is for the view
     * alone, and {@code search} is plain and answers with {@code structuredContent} only.
     */
    private static final String VIEW_TOOLS = "{\"tools\":["
            + "{\"name\":\"search_table\",\"description\":\"Table.\",\"inputSchema\":{\"type\":\"object\"},"
            + "\"_meta\":{\"ui\":{\"resourceUri\":\"" + PAGE_URI + "\"}}},"
            + "{\"name\":\"refresh\",\"description\":\"Refresh.\",\"inputSchema\":{\"type\":\"object\"},"
            + "\"_meta\":{\"ui\":{\"resourceUri\":\"" + PAGE_URI + "\",\"visibility\":[\"app\"]}}},"
            + "{\"name\":\"search\",\"description\":\"\",\"inputSchema\":{\"type\":\"object\"}}]}";

    private static MockWebServer viewHttp(List<JsonRpc.Request> seen) throws IOException {
        return viewHttp(seen, RESOURCE_CAPS, VIEW_TOOLS);
    }

    private static MockWebServer viewHttp(List<JsonRpc.Request> seen, String capabilities, String tools) throws IOException {
        var http = new MockWebServer();
        http.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                var msg = JsonRpc.decode(request.getBody().utf8());
                if (!(msg instanceof JsonRpc.Request r)) return new MockResponse.Builder().code(202).build();
                seen.add(r);
                return switch (r.method()) {
                    case "server/discover" -> rpc(200, r.id(), "{\"supportedVersions\":[\"2026-07-28\"],"
                            + "\"capabilities\":" + capabilities + "}").build();
                    case "tools/list" -> rpc(200, r.id(), tools).build();
                    case "tools/call" -> {
                        var tool = ((JsonObject) Objects.requireNonNull(r.params())).get("name").getAsString();
                        yield rpc(200, r.id(), "search_table".equals(tool)
                                ? "{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\",\"text\":\"3 results\"}],"
                                        + "\"structuredContent\":{\"rows\":[1,2,3]},\"_meta\":{\"k\":\"v\"}}"
                                : "{\"content\":[],\"structuredContent\":{\"organic\":[{\"title\":\"Cats\"}]}}").build();
                    }
                    case "resources/list" -> rpc(200, r.id(), "{\"resources\":[{\"uri\":\"serpapi://engines\","
                            + "\"name\":\"serpapi-engines-index\",\"mimeType\":\"application/json\"},"
                            + "{\"uri\":\"" + PAGE_URI + "\",\"name\":\"Prefab Renderer (search_table)\","
                            + "\"mimeType\":\"text/html;profile=mcp-app\"}]}").build();
                    case "resources/templates/list" -> rpc(200, r.id(), "{\"resourceTemplates\":[]}").build();
                    case "resources/read" -> rpc(200, r.id(), "{\"contents\":[{\"uri\":\"x://disguised\","
                            + "\"mimeType\":\"text/html; charset=utf-8; profile=mcp-app\",\"text\":\"<p>view</p>\"}]}").build();
                    default -> rpc(200, r.id(), "{\"content\":[]}").build();
                };
            }
        });
        http.start();
        return http;
    }

    @Test
    void connectingDeclaresTheUiExtensionOnEveryRequest() throws Exception {
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = viewHttp(seen)) {
            name = connectHttp("declares", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            assertFalse(seen.isEmpty());
            for (var r : seen) {
                var meta = ((JsonObject) Objects.requireNonNull(r.params())).getAsJsonObject("_meta");
                assertEquals("{\"extensions\":{\"io.modelcontextprotocol/ui\":{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}}}",
                        meta.get("io.modelcontextprotocol/clientCapabilities").toString(), r.method());
            }
        } finally {
            stopHttp(name);
        }
    }

    private static final String TABLE_ACTION =
            "{\"name\":\"search_table\",\"description\":\"Table.\",\"inputSchema\":{\"type\":\"object\"}}";

    /** A view server connected with its setting {@code everywhere}, seen by agents seeded before it. */
    private String connectViews(String prefix, MockWebServer http, boolean everywhere) {
        var name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        var cfg = new JsonObject();
        cfg.addProperty("url", http.url("/mcp").toString());
        var row = seedServer(name, McpServer.Transport.HTTP, cfg.toString());
        row.offerViewToolsEverywhere = everywhere;
        McpConnectionManager.connect(row);
        return name;
    }

    private static Long conversation(Agent agent, String channel, @Nullable Long parentId) {
        return commitInFreshTx(() -> {
            var c = services.ConversationService.create(Agent.findById(agent.id), channel, null);
            if (parentId != null) {
                c.parentConversation = models.Conversation.findById(parentId);
                c.save();
            }
            return c.id;
        });
    }

    /** {@code body} as the tool dispatch of a turn on conversation {@code conversationId}. */
    private static <T> T onConversation(Long conversationId, java.util.function.Supplier<T> body) {
        return agents.ToolContext.withConversation(conversationId, body);
    }

    /** {@code body} as a subagent run's own thread, as SubagentSyncRunner and SubagentAsyncRunner run one. */
    private static <T> T asSubagentRun(java.util.function.Supplier<T> body) {
        var runId = -System.nanoTime();
        services.SubagentRegistry.register(runId, new java.util.concurrent.CompletableFuture<Void>());
        try {
            return services.SubagentRegistry.callAsRun(runId, body::get);
        } finally {
            services.SubagentRegistry.unregister(runId);
        }
    }

    private static String catalogOf(String name, String actions) {
        return "{\"server\":\"" + name + "\",\"actions\":[" + actions + "],\"resources\":[{\"uri\":\"serpapi://engines\","
                + "\"name\":\"serpapi-engines-index\",\"mimeType\":\"application/json\"}],\"resourceTemplates\":[]}";
    }

    private static final String PROMPT_HEADER = "| Server | Actions | Description |\n|---|---|---|\n";

    /** The server's row in the prompt's {@code ## MCP Servers} table, counting {@code actions}. */
    private static String promptRow(String name, int actions) {
        return "| `mcp_" + name + "` | " + actions + " action" + (actions == 1 ? "" : "s") + " | MCP server via http. Call `mcp_"
                + name + "` with no arguments to enumerate available actions; call with `{\"tool\": \"<action>\", \"args\":"
                + " {...}}` to execute one. It also lists resources: call with `{\"read\": \"<uri>\"}` to read one. |";
    }

    /** The {@code ## MCP Servers} table row the prompt of a turn on {@code conversationId} gives the server. */
    private static String promptRowOn(Agent agent, Long conversationId, String name) {
        var prompt = Tx.run(() -> {
            models.Conversation c = models.Conversation.findById(conversationId);
            return SystemPromptAssembler.assemble(Agent.findById(agent.id), null, Set.of(), c.channelType, null, c).systemPrompt();
        });
        return mcpSection(prompt).stream().filter(line -> line.contains("`mcp_" + name + "`")).findFirst().orElseThrow();
    }

    @Test
    void onATopLevelWebChatTurnAViewToolIsOfferedAndItsCallCarriesTheView() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var web = conversation(reader, "web", null);
        String name = null;
        try (var http = viewHttp(new CopyOnWriteArrayList<>())) {
            name = connectViews("views", http, false);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            final var server = name;
            assertEquals(catalogOf(server, TABLE_ACTION + "," + SEARCH_ACTION), onConversation(web, () -> tool.execute("{}", reader)));
            assertEquals("{\"server\":\"" + server + "\",\"actions\":[" + TABLE_ACTION + "]}",
                    onConversation(web, () -> tool.execute("{\"describe\":[\"search_table\"]}", reader)));
            var result = onConversation(web,
                    () -> tool.executeRich("{\"tool\":\"search_table\",\"args\":{\"params\":{\"q\":\"cats\"}}}", reader));
            assertEquals(VIEW_TEXT, result.text());
            assertEquals("{\"mcpApp\":{\"server\":\"" + server + "\",\"tool\":\"search_table\",\"resourceUri\":\"" + PAGE_URI
                    + "\",\"arguments\":{\"params\":{\"q\":\"cats\"}},\"result\":{\"content\":[{\"type\":\"text\","
                    + "\"text\":\"3 results\"}],\"structuredContent\":{\"rows\":[1,2,3]},\"_meta\":{\"k\":\"v\"},"
                    + "\"isError\":false}}}", result.structuredJson());
            assertEquals(promptRow(server, 2), promptRowOn(reader, web, server));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void offAWebChatTurnAViewToolIsAbsentAndItsCallRefusedWithoutARequest() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var web = conversation(reader, "web", null);
        var telegram = conversation(reader, "telegram", null);
        var child = conversation(reader, "web", web);
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = viewHttp(seen)) {
            name = connectViews("offweb", http, false);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            final var server = name;
            var refused = "MCP server '" + server + "' has no action named 'search_table'. Available actions: search."
                    + " Call mcp_" + server + " with {\"describe\": [\"<action>\"]} for an action's input schema if needed.";
            Map<String, java.util.function.Function<java.util.function.Supplier<String>, String>> turns = Map.of(
                    "telegram", body -> onConversation(telegram, body),
                    "task run", body -> agents.ToolContext.withScope(null, 7L, body),
                    "session subagent", body -> onConversation(child, body),
                    "inline subagent", body -> asSubagentRun(() -> onConversation(web, body)),
                    "no turn at all", java.util.function.Supplier::get);
            for (var turn : turns.entrySet()) {
                var on = turn.getValue();
                assertEquals(catalogOf(server, SEARCH_ACTION), on.apply(() -> tool.execute("{}", reader)), turn.getKey());
                assertEquals("{\"server\":\"" + server + "\",\"actions\":[],\"unknown\":[\"search_table\"]}",
                        on.apply(() -> tool.execute("{\"describe\":[\"search_table\"]}", reader)), turn.getKey());
                assertEquals(refused, on.apply(() -> tool.execute("{\"tool\":\"search_table\",\"args\":{}}", reader)), turn.getKey());
                assertEquals(catalogOf(server, SEARCH_ACTION),
                        on.apply(() -> tool.execute("{\"args\":{\"tool\":\"search_table\",\"args\":{}}}", reader)),
                        turn.getKey() + ": a nested call to a hidden action gets the list, not the nesting hint");
            }
            assertEquals(0, sent(seen, "tools/call"), "a refused call reached the server");
            assertEquals(promptRow(server, 1), promptRowOn(reader, telegram, server));
            assertEquals(promptRow(server, 1), promptRowOn(reader, child, server));
            assertEquals(PROMPT_HEADER + promptRow(server, 1) + "\n", Tx.run(() -> McpServerCatalog.formatCatalogForPrompt(Set.of())));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void withViewToolsOfferedOnEveryChannelAViewToolIsOfferedOnEveryTurn() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var telegram = conversation(reader, "telegram", null);
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = viewHttp(seen)) {
            name = connectViews("everywhere", http, true);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            assertTrue(McpConnectionManager.offersViewToolsEverywhere(name));
            var tool = new McpServerTool(name);
            final var server = name;
            Map<String, java.util.function.Function<java.util.function.Supplier<String>, String>> turns = Map.of(
                    "telegram", body -> onConversation(telegram, body),
                    "task run", body -> agents.ToolContext.withScope(null, 7L, body),
                    "subagent", body -> asSubagentRun(() -> onConversation(telegram, body)));
            for (var turn : turns.entrySet()) {
                var on = turn.getValue();
                assertEquals(catalogOf(server, TABLE_ACTION + "," + SEARCH_ACTION), on.apply(() -> tool.execute("{}", reader)), turn.getKey());
                assertEquals(VIEW_TEXT, on.apply(() -> tool.execute("{\"tool\":\"search_table\",\"args\":{}}", reader)), turn.getKey());
            }
            assertEquals(3, sent(seen, "tools/call"));
            var rich = onConversation(telegram, () -> tool.executeRich("{\"tool\":\"search_table\",\"args\":{}}", reader));
            assertEquals(VIEW_TEXT, rich.text());
            assertNull(rich.structuredJson(), "no view payload is built where no view draws");
            assertEquals(promptRow(server, 2), promptRowOn(reader, telegram, server));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aServerWhoseActionsAllLinkAViewSaysTheyAreForWebChatOffIt() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var telegram = conversation(reader, "telegram", null);
        var web = conversation(reader, "web", null);
        String name = null;
        try (var http = viewHttp(new CopyOnWriteArrayList<>(), "{\"tools\":{}}", "{\"tools\":[{\"name\":\"search_table\","
                + "\"description\":\"Table.\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"ui\":{\"resourceUri\":\""
                + PAGE_URI + "\"}}}]}")) {
            name = connectViews("viewsonly", http, false);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            var webOnly = "MCP server '" + name + "' offers its actions in web chat only, where their views can be shown.";
            assertEquals(webOnly, onConversation(telegram, () -> tool.execute("{}", reader)));
            assertEquals(webOnly, onConversation(telegram, () -> tool.execute("{\"describe\":[\"search_table\"]}", reader)));
            assertEquals("MCP server '" + name + "' has no action named 'search_table'. Available actions: (none here: this"
                    + " server offers its actions in web chat only, where their views can be shown). Call mcp_" + name
                    + " with {\"describe\": [\"<action>\"]} for an action's input schema if needed.",
                    onConversation(telegram, () -> tool.execute("{\"tool\":\"search_table\",\"args\":{}}", reader)));
            assertEquals("{\"server\":\"" + name + "\",\"actions\":[" + TABLE_ACTION + "]}",
                    onConversation(web, () -> tool.execute("{}", reader)));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void theModelCannotReadAViewPageByItsUriOrUnderAnyOther() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var web = conversation(reader, "web", null);
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = viewHttp(seen)) {
            name = connectViews("pageread", http, true);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            assertEquals("'" + PAGE_URI + "' on MCP server '" + name + "' is a view page, which the web chat draws; it is not"
                    + " a resource to read.", onConversation(web, () -> tool.execute(readOf(PAGE_URI), reader)));
            assertEquals(0, sent(seen, "resources/read"), "a ui:// read reached the server");
            assertEquals("'x://disguised' on MCP server '" + name + "' is a view page, which the web chat draws; it is not"
                    + " a resource to read.", onConversation(web, () -> tool.execute(readOf("x://disguised"), reader)));
            var reads = sent(seen, "resources/read");
            assertEquals("'UI://prefab/x.html' on MCP server '" + name + "' is a view page, which the web chat draws; it is"
                    + " not a resource to read.", onConversation(web, () -> tool.execute(readOf("UI://prefab/x.html"), reader)));
            assertEquals(reads, sent(seen, "resources/read"), "an upper-case ui:// read reached the server");
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aServerWhoseActionsAreAllForItsViewSaysItOffersTheModelNone() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var web = conversation(reader, "web", null);
        String name = null;
        try (var http = viewHttp(new CopyOnWriteArrayList<>(), "{\"tools\":{}}", "{\"tools\":[{\"name\":\"refresh\","
                + "\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"ui\":{\"resourceUri\":\"" + PAGE_URI
                + "\",\"visibility\":[\"app\"]}}}]}")) {
            name = connectViews("apponlyserver", http, true);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            assertEquals("MCP server '" + name + "' offers no actions the model may call.",
                    onConversation(web, () -> tool.execute("{}", reader)));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void anUpperCaseUiSchemeLinksAViewEndToEnd() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var web = conversation(reader, "web", null);
        var telegram = conversation(reader, "telegram", null);
        String name = null;
        try (var http = viewHttp(new CopyOnWriteArrayList<>(), "{\"tools\":{}}", "{\"tools\":[{\"name\":\"search_table\","
                + "\"description\":\"Table.\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"ui\":{\"resourceUri\":"
                + "\"UI://Prefab/Upper.html\"}}},{\"name\":\"search\",\"description\":\"\",\"inputSchema\":{\"type\":\"object\"}}]}")) {
            name = connectViews("upper", http, false);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            assertEquals("{\"server\":\"" + name + "\",\"actions\":[" + SEARCH_ACTION + "]}",
                    onConversation(telegram, () -> tool.execute("{}", reader)), "a view tool off web chat");
            var result = onConversation(web, () -> tool.executeRich("{\"tool\":\"search_table\",\"args\":{}}", reader));
            assertEquals("{\"mcpApp\":{\"server\":\"" + name + "\",\"tool\":\"search_table\",\"resourceUri\":"
                    + "\"UI://Prefab/Upper.html\",\"arguments\":{},\"result\":{\"content\":[{\"type\":\"text\","
                    + "\"text\":\"3 results\"}],\"structuredContent\":{\"rows\":[1,2,3]},\"_meta\":{\"k\":\"v\"},"
                    + "\"isError\":false}}}", result.structuredJson());
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void anEmptyActionListBesideResourcesSaysTheViewToolsAreForWebChat() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var telegram = conversation(reader, "telegram", null);
        String name = null;
        try (var http = viewHttp(new CopyOnWriteArrayList<>(), RESOURCE_CAPS, "{\"tools\":[{\"name\":\"search_table\","
                + "\"description\":\"Table.\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"ui\":{\"resourceUri\":\""
                + PAGE_URI + "\"}}}]}")) {
            name = connectViews("viewsbeside", http, false);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            assertEquals("{\"server\":\"" + name + "\",\"actions\":[],\"resources\":[{\"uri\":\"serpapi://engines\","
                    + "\"name\":\"serpapi-engines-index\",\"mimeType\":\"application/json\"}],\"resourceTemplates\":[],"
                    + "\"actionsNote\":\"This server offers its actions in web chat only, where their views can be shown.\"}",
                    onConversation(telegram, () -> tool.execute("{}", reader)));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aViewToolsResultStandsWhenTellingTheTurnFails() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = viewHttp(seen)) {
            name = connectViews("turnfails", http, false);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var def = McpConnectionManager.tools(name).stream().filter(d -> d.name().equals("search_table")).findFirst().orElseThrow();
            var adapter = new mcp.McpToolAdapter(name, def, McpConnectionManager::callTool, () -> {
                throw new IllegalStateException("database closed");
            });
            var result = adapter.executeRich("{}", reader);
            assertEquals(VIEW_TEXT, result.text());
            assertNull(result.structuredJson());
            assertEquals(1, sent(seen, "tools/call"), "the call ran once and nothing asks the model to retry it");
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void thePromptBreakdownForWebCountsViewTools() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        String name = null;
        try (var http = viewHttp(new CopyOnWriteArrayList<>())) {
            name = connectViews("breakdown", http, false);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            final var server = name;
            // The breakdown reads the agent's own tool switches, and a non-main agent starts with MCP off.
            commitInFreshTx(() -> {
                var granted = mcp.McpGrants.newRow(Agent.findById(reader.id), McpServer.toolName(server, ""));
                granted.enabled = true;
                granted.save();
                return null;
            });
            ToolRegistry.clearDisabledToolsCache();
            var row = (java.util.function.Function<String, String>) channel -> mcpSection(Tx.run(() ->
                    SystemPromptAssembler.assemble(Agent.findById(reader.id), null, Set.of(), channel).systemPrompt()))
                    .stream().filter(line -> line.contains("`mcp_" + server + "`")).findFirst().orElseThrow();
            assertEquals(promptRow(server, 2), row.apply("web"));
            assertEquals(promptRow(server, 1), row.apply("telegram"));
            var section = (java.util.function.Function<String, Integer>) channel -> Tx.run(() ->
                    SystemPromptAssembler.breakdown(Agent.findById(reader.id), null, channel)).sections().stream()
                    .filter(e -> e.name().equals("MCP Servers")).findFirst().orElseThrow().chars();
            assertEquals(section.apply("telegram") + 1, (int) section.apply("web"), "\"2 actions\" is one character longer");
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aPlainToolAnsweringOnlyStructuredContentGivesTheModelThatJsonOnEveryChannelAndTheChatNoView() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var telegram = conversation(reader, "telegram", null);
        String name = null;
        try (var http = viewHttp(new CopyOnWriteArrayList<>())) {
            name = connectViews("plain", http, false);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            var result = onConversation(telegram, () -> tool.executeRich("{\"tool\":\"search\",\"args\":{}}", reader));
            assertEquals("{\"organic\":[{\"title\":\"Cats\"}]}", result.text());
            assertNull(result.structuredJson());
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void anAppOnlyToolAndAViewPageNeverReachTheModelWhereverViewToolsAreOffered() throws Exception {
        var reader = commitInFreshTx(() -> seedAgent("reader"));
        var web = conversation(reader, "web", null);
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = viewHttp(seen)) {
            name = connectViews("apponly", http, true);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            final var server = name;
            assertEquals(catalogOf(server, TABLE_ACTION + "," + SEARCH_ACTION), onConversation(web, () -> tool.execute("{}", reader)));
            assertEquals("{\"server\":\"" + server + "\",\"actions\":[" + TABLE_ACTION + "],\"unknown\":[\"refresh\"]}",
                    onConversation(web, () -> tool.execute("{\"describe\":[\"refresh\",\"search_table\"]}", reader)));
            assertEquals("MCP server '" + server + "' has no action named 'refresh'. Available actions: search_table, search."
                    + " Call mcp_" + server + " with {\"describe\": [\"<action>\"]} for an action's input schema if needed.",
                    onConversation(web, () -> tool.execute("{\"tool\":\"refresh\",\"args\":{}}", reader)));
            assertEquals(0, sent(seen, "tools/call"), "the refused call reached the server");
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aToolsOnlyServerListsExactlyWhatItDidAndSendsNoResourceRequest() throws Exception {
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = capableHttp("{\"tools\":{}}", seen, serpapiResources())) {
            name = connectHttp("toolsonly", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            assertFalse(McpConnectionManager.offersResources(name));
            var tool = new McpServerTool(name);
            assertEquals("{\"server\":\"" + name + "\",\"actions\":[" + SEARCH_ACTION + "]}", tool.execute("{}", new Agent()));
            assertEquals("MCP server '" + name + "' offers no resources.", tool.execute(readOf(FLIGHTS), new Agent()));
            assertEquals(List.of(), seen.stream().map(JsonRpc.Request::method).filter(m -> m.startsWith("resources/")).toList());
            assertEquals(handleDescription(name), tool.description());
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aResourceOnlyServerListsItsResourcesWithNoActionsAndItsSwitchAloneLetsAnAgentRead() throws Exception {
        String name = null;
        try (var http = capableHttp(RESOURCE_CAPS, new String[0], new CopyOnWriteArrayList<>(), serpapiResources())) {
            name = connectHttp("resonly", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            assertEquals("{\"server\":\"" + name + "\",\"actions\":[]," + SERPAPI_RESOURCES + "}", tool.execute("{}", new Agent()));
            // No tools means no grant rows for anyone; the handle being offered is the switch.
            var switchedOn = commitInFreshTx(() -> seedAgent("switched-on"));
            assertEquals("{\"departure_id\":\"airport code\"}", tool.execute(readOf(FLIGHTS), switchedOn));
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void failedListingsNeverOpenTheBreakerForWorkingActionsAndAnOpenBreakerStillRefusesTheListing() throws Exception {
        var seen = new CopyOnWriteArrayList<JsonRpc.Request>();
        String name = null;
        try (var http = capableHttp(RESOURCE_CAPS, seen, r -> r.method().equals("resources/list")
                ? new MockResponse.Builder().code(500).body("SECRET upstream body").build()
                : rpc(200, r.id(), "{\"content\":[]}").build())) {
            name = connectHttp("failing", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            var withoutResources = "{\"server\":\"" + name + "\",\"actions\":[" + SEARCH_ACTION + "]," + NOT_LISTED + "}";

            var listing = new String[1];
            var events = EventLogger.captureForTest(() -> listing[0] = tool.execute("{}", new Agent()));
            assertEquals(withoutResources, listing[0]);
            var warned = events.stream().filter(e -> e.category().equals("MCP_RESOURCES")).toList();
            assertEquals(1, warned.size(), events.toString());
            assertEquals("MCP server '" + name + "' could not list its resources: MCP HTTP 500", warned.getFirst().message());
            assertEquals(withoutResources, tool.execute("{}", new Agent()));
            assertEquals(withoutResources, tool.execute("{}", new Agent()));

            var breaker = McpConnectionManager.breaker(name);
            assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
            assertEquals(0, breaker.stats().failures());
            assertFalse(McpConnectionManager.callTool(name, "search", new JsonObject()).isError());

            breaker.trip();
            var listsBefore = sent(seen, "resources/list");
            assertEquals(withoutResources, tool.execute("{}", new Agent()));
            assertEquals(listsBefore, sent(seen, "resources/list"), "an open breaker sends no listing");
        } finally {
            stopHttp(name);
        }
    }

    @Test
    void aListingTheServerNeverAnswersEndsAtTheListingTimeoutWithTheActionsListed() throws Exception {
        String name = null;
        try (var http = capableHttp(RESOURCE_CAPS, new CopyOnWriteArrayList<>(), r -> r.method().equals("resources/list")
                ? rpc(200, r.id(), "{\"resources\":[]}").headersDelay(8, TimeUnit.SECONDS).build()
                : rpc(200, r.id(), "{\"content\":[]}").build())) {
            name = connectHttp("hunglisting", http);
            awaitState(name, McpServer.Status.CONNECTED, 10);
            var tool = new McpServerTool(name);
            var listing = new String[1];
            var startedAt = System.nanoTime();
            var events = EventLogger.captureForTest(() -> listing[0] = tool.execute("{}", new Agent()));
            var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertEquals("{\"server\":\"" + name + "\",\"actions\":[" + SEARCH_ACTION + "]," + NOT_LISTED + "}", listing[0]);
            assertEquals(List.of("MCP server '" + name + "' could not list its resources: Request resources/list timed out after PT5S"),
                    events.stream().filter(e -> e.category().equals("MCP_RESOURCES")).map(EventLogger.Captured::message).toList());
            assertTrue(elapsedMs >= 5_000 && elapsedMs < 15_000,
                    "the 5 s listing timeout, not the 30 s request budget: " + elapsedMs + " ms");
        } finally {
            stopHttp(name);
        }
    }

    /** The {@code ## MCP Servers} section: its usage line, a blank line, then the table's lines in any order. */
    private static List<String> mcpSection(String prompt) {
        var heading = "\n## MCP Servers\n";
        var start = prompt.indexOf(heading) + heading.length();
        return List.of(prompt.substring(start, prompt.indexOf("\n## ", start)).split("\n"));
    }

    @Test
    void theSystemPromptNamesTheReadFormOnlyForAServerDeclaringResources() throws Exception {
        String capable = null;
        String plain = null;
        try (var withResources = capableHttp(RESOURCE_CAPS, new CopyOnWriteArrayList<>(), serpapiResources());
             var toolsOnly = capableHttp("{\"tools\":{}}", new CopyOnWriteArrayList<>(), serpapiResources())) {
            capable = connectHttp("capable", withResources);
            plain = connectHttp("plain", toolsOnly);
            awaitState(capable, McpServer.Status.CONNECTED, 10);
            awaitState(plain, McpServer.Status.CONNECTED, 10);
            var agent = AgentService.create("prompt-resources-" + UUID.randomUUID().toString().substring(0, 8),
                    "openrouter", "gpt-4.1");

            var usage = "To invoke an action: call `mcp_<server>` with no arguments to list the server's actions. A large"
                    + " server lists names and summaries only: call it with `{\"describe\": [\"<action>\", ...]}` for the"
                    + " input schemas of the actions you will use. Then call with `{\"tool\": \"<action>\", \"args\": {...}}`"
                    + " to execute one.";
            var shared = " A server whose row mentions `read` also lists resources and resource templates; when one"
                    + " documents an action you will call, read it with `{\"read\": \"<uri>\"}` instead of guessing the"
                    + " action's arguments.";
            var header = "| Server | Actions | Description |";
            var rule = "|---|---|---|";
            var plainRow = "| `mcp_" + plain + "` | 1 action | MCP server via http. Call `mcp_" + plain + "` with no arguments"
                    + " to enumerate available actions; call with `{\"tool\": \"<action>\", \"args\": {...}}` to execute one. |";
            var capableRow = "| `mcp_" + capable + "` | 1 action | MCP server via http. Call `mcp_" + capable
                    + "` with no arguments to enumerate available actions; call with `{\"tool\": \"<action>\", \"args\": {...}}`"
                    + " to execute one. It also lists resources: call with `{\"read\": \"<uri>\"}` to read one. |";

            var section = mcpSection(SystemPromptAssembler.assemble(agent, null, Set.of(), null).systemPrompt());
            assertEquals(List.of(usage + shared, ""), section.subList(0, 2));
            assertEquals(Set.of(header, rule, plainRow, capableRow), Set.copyOf(section.subList(2, section.size())));
            assertEquals(6, section.size(), section.toString());

            stopHttp(capable);
            capable = null;
            var without = mcpSection(SystemPromptAssembler.assemble(agent, null, Set.of(), null).systemPrompt());
            assertEquals(List.of(usage, "", header, rule, plainRow), without, "no server offers resources: today's section");
        } finally {
            stopHttp(capable);
            stopHttp(plain);
        }
    }

    // ==================== fixtures ====================

    /** JCLAW-288: shared fixture-file writer used by the synchronous-first-attempt
     *  tests so the body of each test only carries the env vars + script. */
    private Path writeFixture(String name, String script) throws IOException {
        var path = Files.createTempFile("mcp-cm-" + name + "-", ".js");
        Files.writeString(path, script);
        path.toFile().deleteOnExit();
        return path;
    }

    private McpServer seedStdioServer(String name, String script) throws IOException {
        return seedStdioServer(name, script, false);
    }

    private McpServer seedStdioServer(String name, String script, boolean requiresApproval) throws IOException {
        var path = Files.createTempFile("mcp-srv-" + name + "-", ".js");
        Files.writeString(path, script);
        path.toFile().deleteOnExit();
        var cfg = new JsonObject();
        cfg.addProperty("command", "node");
        var args = new com.google.gson.JsonArray();
        args.add(path.toString());
        cfg.add("args", args);
        return seedServer(name, McpServer.Transport.STDIO, cfg.toString(), requiresApproval);
    }

    private McpServer seedServer(String name, McpServer.Transport transport, String cfgJson) {
        return seedServer(name, transport, cfgJson, false);
    }

    private McpServer seedServer(String name, McpServer.Transport transport, String cfgJson,
                                 boolean requiresApproval) {
        return Tx.run(() -> {
            var srv = new McpServer();
            srv.name = name;
            srv.enabled = true;
            srv.transport = transport;
            srv.configJson = cfgJson;
            srv.requiresApproval = requiresApproval;
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

    /** Run {@code block} in a fresh platform thread + Tx (JCLAW-688: platform, not VT — the VT carrier pool starves under suite load, JDK-8373224) so the commit lands
     *  before the calling thread proceeds — needed when seed data must be
     *  visible to a subsequent connector VT (mirrors WebhookControllerTest's
     *  pattern, since UnitTest's outer carrier tx otherwise holds writes
     *  uncommitted from peer threads' POV). */
    private static <T> T commitInFreshTx(java.util.concurrent.Callable<T> block) {
        var holder = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try { holder.set(Tx.run(() -> {
                try { return block.call(); }
                catch (Exception e) { throw new RuntimeException(e); }
            })); }
            catch (Throwable e) { err.set(e); }
        });
        try { t.join(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
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

    /** {@code category} events naming server {@code name} that any thread logs while {@code action} runs,
     *  in record order, polled until one exists — empty if none arrive in time. */
    private static List<EventLogger.Captured> captureServerEvents(String category, String name, long maxSeconds,
            InterruptibleAction action) {
        return EventLogger.captureMatchingForTest(
                e -> e.category().equals(category) && e.message().contains("'" + name + "'"),
                live -> {
                    try {
                        action.run();
                        var deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(maxSeconds);
                        while (live.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private interface InterruptibleAction {
        void run() throws InterruptedException;
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
