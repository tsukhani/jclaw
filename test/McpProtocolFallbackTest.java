import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mcp.McpConnectionManager;
import mcp.McpException;
import mcp.jsonrpc.JsonRpc;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import models.McpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.EventLogger;
import services.McpServerService;
import services.OperatorAlerts;
import services.Tx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * JCLAW-1443 through {@link McpConnectionManager}: the per-server protocol setting, the automatic
 * fallback to the handshake, and a modern HTTP server that stops answering 2026-07-28.
 *
 * <p>The stdio fixture behaves like the two dual-era servers the JCLAW-1441 spike measured: the
 * first request a process answers fixes its era, so a process that answered discovery refuses
 * {@code initialize} with -32022, and a fresh one opened with {@code initialize} accepts it. Each
 * process logs {@code <pid> <method>} to {@code MCP_LOG}, so a test reads which process was sent what.
 * Skipped when {@code node} is not on PATH.
 */
class McpProtocolFallbackTest extends UnitTest {

    private static final String DUAL_ERA_FIXTURE = """
            const fs = require('fs');
            const readline = require('readline');
            const SCENARIO = process.env.SCENARIO || '';
            let era = null;
            const rl = readline.createInterface({ input: process.stdin, terminal: false });
            rl.on('line', (line) => {
              if (!line.trim()) return;
              let m;
              try { m = JSON.parse(line); } catch (e) { return; }
              fs.appendFileSync(process.env.MCP_LOG, process.pid + ' ' + (m.method || 'reply') + '\\n');
              if (m.id === undefined || !m.method) return;
              const modern = !!(m.params && m.params._meta
                  && m.params._meta['io.modelcontextprotocol/protocolVersion'] === '2026-07-28');
              if (m.method === 'server/discover') {
                era = era || 'modern';
                return ok(m, { supportedVersions: ['2026-07-28'], capabilities: { tools: {} },
                               serverInfo: { name: 'dual', version: '1' } });
              }
              if (m.method === 'initialize') {
                if (era === 'modern') return err(m, -32022, 'Unsupported protocol version', { supported: ['2026-07-28'] });
                if (SCENARIO.includes('handshake-broken')) return err(m, -32603, 'handshake broken');
                era = 'handshake';
                return ok(m, { protocolVersion: '2025-06-18', capabilities: { tools: {} },
                               serverInfo: { name: 'dual', version: '1' } });
              }
              if (era === 'modern' && !modern) return err(m, -32020, 'header mismatch');
              if (m.method === 'tools/list') {
                if (era === 'modern' && SCENARIO.includes('modern-tools-fail')) return err(m, -32603, 'tools broke');
                return ok(m, { tools: [{ name: 'echo', inputSchema: { type: 'object' } }] });
              }
              if (m.method === 'tools/call') {
                const name = m.params.name;
                if (era === 'modern' && name === 'mismatch') return err(m, -32020, 'header mismatch');
                if (name === 'needs') return err(m, -32021, 'missing capability', { requiredCapabilities: { elicitation: {} } });
                return ok(m, { content: [{ type: 'text', text: era + ':' + name }] });
              }
              return err(m, -32601, 'Method not found');
            });
            function ok(m, result) { send({ jsonrpc: '2.0', id: m.id, result }); }
            function err(m, code, message, data) {
              send({ jsonrpc: '2.0', id: m.id, error: data ? { code, message, data } : { code, message } });
            }
            function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n'); }
            """;

    private final List<Path> temps = new ArrayList<>();
    private Path log;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH; skipping");
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ToolRegistrySync.publishForTest(List.of());
        McpConnectionManager.setBackoff(50, 200);
        McpConnectionManager.setFirstAttemptRequestTimeout(Duration.ofSeconds(10));
        log = temp("mcp-log-", ".txt");
    }

    @AfterEach
    void tearDown() throws Exception {
        McpConnectionManager.shutdown();
        EventLogger.clear();
        McpConnectionManager.setBackoff(1_000, 30_000);
        McpConnectionManager.setFirstAttemptRequestTimeout(Duration.ofSeconds(120));
        for (var p : temps) Files.deleteIfExists(p);
        ToolRegistrySync.release();
    }

    // ==================== the setting ====================

    @Test
    void aServerFromBeforeTheSettingReadsAutomaticAndIsProbed() throws Exception {
        var server = seed("legacy", "", McpServer.Protocol.AUTOMATIC);
        commitInFreshTx(() -> JPA.em().createQuery("UPDATE McpServer s SET s.protocol = NULL WHERE s.id = :id")
                .setParameter("id", server.id).executeUpdate());
        var row = reload(server.id);
        assertNull(row.protocol, "precondition: the column is empty, as on a row from before it existed");
        assertEquals(McpServer.Protocol.AUTOMATIC, row.protocolSetting());
        assertEquals("AUTOMATIC", McpServerService.View.of(row).protocol());

        McpConnectionManager.connect(row);
        awaitState("legacy", McpServer.Status.CONNECTED);
        assertEquals("server/discover", processes().getFirst().methods().getFirst());
        await(() -> "2026-07-28".equals(column(server.id, "protocolVersion")), "the detected version is stored");
    }

    @Test
    void switchingAConnectedServerToHandshakeStartsANewProcessOpenedWithInitialize() throws Exception {
        var server = seed("switch", "", McpServer.Protocol.AUTOMATIC);
        McpConnectionManager.connect(server);
        awaitState("switch", McpServer.Status.CONNECTED);
        assertEquals("modern:echo", McpConnectionManager.callTool("switch", "echo", new JsonObject()).content());

        server.protocol = McpServer.Protocol.HANDSHAKE;
        McpServerService.syncRuntime(server);
        awaitState("switch", McpServer.Status.CONNECTED);

        assertEquals("handshake:echo", McpConnectionManager.callTool("switch", "echo", new JsonObject()).content());
        var procs = processes();
        assertEquals(2, procs.size(), procs.toString());
        assertEquals("initialize", procs.getLast().methods().getFirst());
        assertFalse(procs.getLast().methods().contains("server/discover"));
    }

    @Test
    void testConnectionProbesAServerSetToHandshakeAndLeavesItsConnectionAlone() throws Exception {
        var server = seed("tested", "", McpServer.Protocol.HANDSHAKE);
        McpConnectionManager.connect(server);
        awaitState("tested", McpServer.Status.CONNECTED);

        var result = McpServerService.testConnection(server);

        assertTrue(result.success(), result.message());
        assertTrue(result.offersModern(), result.message());
        assertEquals("2026-07-28", result.protocolVersion());
        assertTrue(result.message().contains("offers 2026-07-28"), result.message());
        assertEquals(McpServer.Status.CONNECTED, McpConnectionManager.status("tested"));
        assertEquals("handshake:echo", McpConnectionManager.callTool("tested", "echo", new JsonObject()).content());
        assertEquals("HANDSHAKE", column(server.id, "protocol"));
    }

    // ==================== automatic fallback ====================

    @Test
    void aFailedFirstModernToolsListFallsBackToTheHandshakeAndSwitchesTheSetting() throws Exception {
        var server = seed("fallback", "modern-tools-fail", McpServer.Protocol.AUTOMATIC);
        var events = new AtomicReference<List<EventLogger.Captured>>();
        var alerts = OperatorAlerts.captureMcpAlertsForTest(() -> events.set(protocolEvents("fallback", () -> {
            McpConnectionManager.connect(server);
            awaitState("fallback", McpServer.Status.CONNECTED);
        })));

        assertEquals("handshake:echo", McpConnectionManager.callTool("fallback", "echo", new JsonObject()).content());
        assertEquals(1, events.get().size(), events.get().toString());
        assertEquals("WARN", events.get().getFirst().level());
        assertEquals(1, alerts.size(), alerts.toString());
        assertTrue(alerts.getFirst().contains("'fallback'"), alerts.getFirst());
        assertEquals("HANDSHAKE", column(server.id, "protocol"));
        assertNotNull(column(server.id, "protocolSwitchedAt"));
        assertTrue(String.valueOf(column(server.id, "protocolSwitchReason")).contains("tools/list"));
        assertEquals("2025-06-18", column(server.id, "protocolVersion"));
        var procs = processes();
        assertEquals(List.of("server/discover", "tools/list"), procs.getFirst().methods());
        assertEquals("initialize", procs.getLast().methods().getFirst());
    }

    @Test
    void whenTheHandshakeFailsTooTheSettingStaysAutomatic() throws Exception {
        var server = seed("broken", "modern-tools-fail,handshake-broken", McpServer.Protocol.AUTOMATIC);
        var events = new AtomicReference<List<EventLogger.Captured>>();
        var alerts = OperatorAlerts.captureMcpAlertsForTest(() -> events.set(protocolEvents("broken", () -> {
            McpConnectionManager.connect(server);
            awaitState("broken", McpServer.Status.ERROR);
        })));

        assertTrue(Objects.requireNonNull(McpConnectionManager.lastError("broken")).contains("handshake broken"),
                McpConnectionManager.lastError("broken"));
        assertEquals("AUTOMATIC", column(server.id, "protocol"));
        assertNull(column(server.id, "protocolSwitchedAt"));
        assertEquals(List.of(), events.get());
        assertEquals(List.of(), alerts);
        // The ordinary backoff runs, and each retry probes again.
        await(() -> processes().size() >= 4, "the backoff must retry");
        assertEquals("server/discover", processes().get(2).methods().getFirst());
    }

    @Test
    void aCallRefusedWithMinus32020FailsWithTryAgainAndTheNextGoesOverTheHandshake() throws Exception {
        var server = seed("mismatch", "", McpServer.Protocol.AUTOMATIC);
        McpConnectionManager.connect(server);
        awaitState("mismatch", McpServer.Status.CONNECTED);

        var events = new AtomicReference<List<EventLogger.Captured>>();
        var thrown = new AtomicReference<McpException>();
        var alerts = OperatorAlerts.captureMcpAlertsForTest(() -> events.set(protocolEvents("mismatch", () ->
                thrown.set(assertThrows(McpException.class,
                        () -> McpConnectionManager.callTool("mismatch", "mismatch", new JsonObject()))))));

        assertTrue(thrown.get().getMessage().contains("try it again"), thrown.get().getMessage());
        assertTrue(thrown.get().getMessage().contains("switched it to the Handshake protocol"), thrown.get().getMessage());
        assertEquals("handshake:echo", McpConnectionManager.callTool("mismatch", "echo", new JsonObject()).content());
        assertEquals(1, events.get().size(), events.get().toString());
        assertEquals(1, alerts.size());
        assertEquals("HANDSHAKE", column(server.id, "protocol"));
        assertEquals("tools/call", processes().getFirst().methods().getLast(), "the refused call is not replayed");
    }

    @Test
    void aCallRefusedForAMissingCapabilityFailsAlone() throws Exception {
        var server = seed("needs", "", McpServer.Protocol.AUTOMATIC);
        McpConnectionManager.connect(server);
        awaitState("needs", McpServer.Status.CONNECTED);

        var ex = assertThrows(McpException.class, () -> McpConnectionManager.callTool("needs", "needs", new JsonObject()));

        assertTrue(ex.getMessage().contains("elicitation"), ex.getMessage());
        assertEquals("modern:echo", McpConnectionManager.callTool("needs", "echo", new JsonObject()).content());
        assertEquals(1, processes().size(), "no reconnect");
        assertEquals("AUTOMATIC", column(server.id, "protocol"));
    }

    @Test
    void afterAFallbackAConnectSkipsTheProbeUntilTheSettingIsAutomaticAgain() throws Exception {
        var server = seed("after", "modern-tools-fail", McpServer.Protocol.AUTOMATIC);
        McpConnectionManager.connect(server);
        awaitState("after", McpServer.Status.CONNECTED);
        await(() -> "HANDSHAKE".equals(column(server.id, "protocol")), "the fallback switches the setting");

        var switched = reload(server.id);
        McpConnectionManager.connect(switched);
        awaitState("after", McpServer.Status.CONNECTED);
        assertEquals("initialize", processes().getLast().methods().getFirst(), "a switched server is not probed");

        var restored = reload(server.id);
        restored.protocol = McpServer.Protocol.AUTOMATIC;
        McpConnectionManager.connect(restored);
        // The fixture fails modern tools/list again, so the probe leads to another fallback.
        await(() -> processes().stream().filter(p -> p.methods().getFirst().equals("server/discover")).count() == 2,
                "setting it back to Automatic probes again");
    }

    // ==================== a modern HTTP server that stops answering 2026-07-28 ====================

    @Test
    void aModernHttpServerThatStartsAnsweringMinus32000IsDetectedAgain() throws Exception {
        var modernOn = new AtomicBoolean(true);
        var methods = new CopyOnWriteArrayList<String>();
        try (var http = new MockWebServer()) {
            http.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    var msg = JsonRpc.decode(request.getBody().utf8());
                    if (!(msg instanceof JsonRpc.Request r)) return new MockResponse.Builder().code(202).build();
                    methods.add(r.method());
                    var modern = "2026-07-28".equals(request.getHeaders().get("MCP-Protocol-Version"));
                    if (modern && !modernOn.get()) return rpc(400, r.id(), null, -32000);
                    return switch (r.method()) {
                        case "server/discover" -> rpc(200, r.id(), "{\"supportedVersions\":[\"2026-07-28\"]}", 0);
                        case "initialize" -> rpc(200, r.id(), "{\"protocolVersion\":\"2025-06-18\"}", 0);
                        case "tools/list" -> rpc(200, r.id(), "{\"tools\":[]}", 0);
                        default -> {
                            var name = ((JsonObject) Objects.requireNonNull(r.params())).get("name").getAsString();
                            yield name.equals("bad") ? rpc(400, r.id(), null, -32602)
                                    : rpc(200, r.id(), "{\"content\":[{\"type\":\"text\",\"text\":\""
                                            + (modern ? "modern" : "handshake") + "\"}]}", 0);
                        }
                    };
                }
            });
            http.start();
            var cfg = new JsonObject();
            cfg.addProperty("url", http.url("/mcp").toString());
            var server = seedRow("rollback", McpServer.Transport.HTTP, cfg.toString(), McpServer.Protocol.AUTOMATIC);
            McpConnectionManager.connect(server);
            awaitState("rollback", McpServer.Status.CONNECTED);
            assertEquals("modern", McpConnectionManager.callTool("rollback", "t", new JsonObject()).content());

            // A 400 carrying a standard code answers that one request.
            var bad = assertThrows(McpException.class, () -> McpConnectionManager.callTool("rollback", "bad", new JsonObject()));
            assertEquals(-32602, bad.code());
            assertEquals("modern", McpConnectionManager.callTool("rollback", "t", new JsonObject()).content());
            assertFalse(methods.contains("initialize"));

            modernOn.set(false);
            assertThrows(McpException.class, () -> McpConnectionManager.callTool("rollback", "t", new JsonObject()));
            await(() -> methods.contains("initialize")
                    && McpConnectionManager.status("rollback") == McpServer.Status.CONNECTED, "the era is detected again");
            assertEquals("handshake", McpConnectionManager.callTool("rollback", "t", new JsonObject()).content());
            assertEquals("AUTOMATIC", column(server.id, "protocol"), "the probe decides; no setting changes");
        } finally {
            McpConnectionManager.stop("rollback");
        }
    }

    // ==================== schema ====================

    @Test
    void theNewColumnsAreNullableSoTheUpgradeSucceedsOnPopulatedRows() {
        var nullable = Tx.run(() -> JPA.em().createNativeQuery(
                "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'MCP_SERVER'"
                        + " AND COLUMN_NAME IN ('PROTOCOL', 'PROTOCOL_VERSION', 'PROTOCOL_SWITCHED_AT',"
                        + " 'PROTOCOL_SWITCH_REASON', 'PROBE_TIMED_OUT_AT')").getResultList());
        assertEquals(5, nullable.size(), "all five columns exist");
        for (var row : nullable) {
            var cols = (Object[]) row;
            assertEquals("YES", cols[1], cols[0] + " must be nullable");
        }
    }

    // ==================== fixtures ====================

    private record Process(String pid, List<String> methods) {}

    /** Each fixture process and the methods it was sent, in spawn order. */
    private List<Process> processes() {
        var out = new ArrayList<Process>();
        try {
            for (var line : Files.readAllLines(log)) {
                var parts = line.split(" ", 2);
                var known = out.stream().filter(p -> p.pid().equals(parts[0])).findFirst();
                if (known.isPresent()) {
                    known.get().methods().add(parts[1]);
                } else {
                    out.add(new Process(parts[0], new ArrayList<>(List.of(parts[1]))));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private McpServer seed(String name, String scenario, McpServer.Protocol protocol) throws IOException {
        var script = temp("mcp-dual-", ".js");
        Files.writeString(script, DUAL_ERA_FIXTURE);
        var cfg = new JsonObject();
        cfg.addProperty("command", "node");
        var args = new JsonArray();
        args.add(script.toString());
        cfg.add("args", args);
        var env = new JsonObject();
        env.addProperty("MCP_LOG", log.toString());
        env.addProperty("SCENARIO", scenario);
        cfg.add("env", env);
        return seedRow(name, McpServer.Transport.STDIO, cfg.toString(), protocol);
    }

    private static McpServer seedRow(String name, McpServer.Transport transport, String cfgJson,
                                     McpServer.Protocol protocol) {
        return commitInFreshTx(() -> {
            var srv = new McpServer();
            srv.name = name;
            srv.enabled = true;
            srv.transport = transport;
            srv.configJson = cfgJson;
            srv.protocol = protocol;
            srv.save();
            return srv;
        });
    }

    private static McpServer reload(Long id) {
        return commitInFreshTx(() -> McpServer.<McpServer>findById(id));
    }

    /** One column read past the entity cache, which the manager's partial updates do not go through. */
    private static Object column(Long id, String field) {
        var value = commitInFreshTx(() -> JPA.em()
                .createQuery("SELECT s." + field + " FROM McpServer s WHERE s.id = :id")
                .setParameter("id", id).getSingleResult());
        return value instanceof Enum<?> e ? e.name() : value;
    }

    /** Seeds and reads go through their own committed transaction, outside the test's, so the
     *  manager's connect threads see the rows and the test sees what they wrote. */
    private static <T> T commitInFreshTx(Callable<T> block) {
        var holder = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                holder.set(Tx.run(() -> {
                    try {
                        return block.call();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            } catch (Throwable e) {
                err.set(e);
            }
        });
        try {
            t.join();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return holder.get();
    }

    private Path temp(String prefix, String suffix) throws IOException {
        var p = Files.createTempFile(prefix, suffix);
        temps.add(p);
        return p;
    }

    private interface Body {
        void run() throws Exception;
    }

    private static List<EventLogger.Captured> protocolEvents(String name, Body body) {
        return EventLogger.captureMatchingForTest(
                e -> e.category().equals("MCP_PROTOCOL") && e.message().contains("'" + name + "'"),
                _ -> {
                    try {
                        body.run();
                        Thread.sleep(300);  // a second switch, were there one, would land in this window
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static void awaitState(String name, McpServer.Status target) throws InterruptedException {
        var deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20);
        while (System.currentTimeMillis() < deadline) {
            if (McpConnectionManager.status(name) == target) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + name + " to reach " + target + " (current="
                + McpConnectionManager.status(name) + ", lastError=" + McpConnectionManager.lastError(name) + ")");
    }

    private static void await(BooleanSupplier cond, String why) throws InterruptedException {
        var deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20);
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("Timed out: " + why);
    }

    private static MockResponse rpc(int code, Object id, @Nullable String result, int errorCode) {
        var body = result != null
                ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}"
                : "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + errorCode
                        + ",\"message\":\"Unsupported protocol version\"}}";
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
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
