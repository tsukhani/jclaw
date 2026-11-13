import agents.McpServerCatalog;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.McpConnectionManager;
import mcp.McpGrants;
import mcp.McpServerTool;
import models.Agent;
import models.McpServer;
import models.Task;
import models.TaskRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConversationService;
import services.EventLogger;
import services.TaskToolPolicy;
import services.Tx;
import utils.AppClock;
import utils.CircuitBreakers;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JF-373: a run's tool list that names single actions of an MCP server holds the server's handle to those
 * actions, against a real stdio server. The fixture records every {@code tools/call} it receives, and its
 * {@code history} action returns that record, so a refused call is checked to have reached no server.
 */
class McpTaskActionFenceTest extends UnitTest {

    private static final String SERVER = "jf373fence";
    private static final String HANDLE = "mcp_" + SERVER;

    private static final String FIXTURE_SCRIPT = """
            const readline = require('readline');
            const rl = readline.createInterface({ input: process.stdin, terminal: false });
            const calls = [];
            const tool = (name) => ({ name, description: name + ' action',
                                      inputSchema: { type: 'object', properties: {} } });
            rl.on('line', (line) => {
              if (!line.trim()) return;
              let m;
              try { m = JSON.parse(line); } catch (e) { return; }
              if (m.method === 'initialize') {
                send({ jsonrpc: '2.0', id: m.id,
                  result: { protocolVersion: '2025-06-18', capabilities: { tools: {} },
                            serverInfo: { name: 'fence', version: '0.0.1' } } });
              } else if (m.method === 'tools/list') {
                send({ jsonrpc: '2.0', id: m.id, result: { tools: [
                  tool('search_messages'), tool('label_message'), tool('send_message'), tool('history')] } });
              } else if (m.method === 'tools/call') {
                const name = m.params.name;
                const text = name === 'history' ? calls.join(',') : (calls.push(name), 'ok:' + name);
                send({ jsonrpc: '2.0', id: m.id, result: { content: [{ type: 'text', text }] } });
              } else if (m.id !== undefined) {
                send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'Method not found' } });
              }
            });
            function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n'); }
            """;

    private Agent agent;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH; skipping");
        Fixtures.deleteDatabase();
        ToolRegistrySync.publishForTest(List.of());
        McpConnectionManager.setBackoff(50, 200);
        // Committed before the connect, whose own thread grants each action to every top-level agent it can see.
        agent = Tx.run(() -> {
            var a = new Agent();
            a.name = "jf373-agent";
            a.modelProvider = "openrouter";
            a.modelId = "gpt-4.1";
            a.enabled = true;
            a.save();
            return a;
        });
        var server = seedStdioServer();
        commit();
        McpConnectionManager.connect(server);
        awaitConnected();
        // A custom agent reaches an MCP server only once granted it.
        Tx.run(() -> {
            var row = McpGrants.newRow(agent, HANDLE);
            row.enabled = true;
            row.save();
        });
        commit();
        ToolRegistry.invalidateDisabledToolsCache(agent);
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    @AfterEach
    void tearDown() {
        McpConnectionManager.shutdown();
        CircuitBreakers.remove("mcp:" + SERVER);
        McpConnectionManager.setBackoff(1_000, 30_000);
        McpConnectionManager.setFirstAttemptRequestTimeout(Duration.ofSeconds(120));
        ToolRegistrySync.release();
    }

    private static final String NARROWED = "[\"" + HANDLE + "/search_messages\",\"" + HANDLE + "/label_message\"]";

    // ==================== a task fire's list ====================

    @Test
    void theActionListShowsOnlyTheActionsTheTaskNames() {
        var run = seedTaskRun("labeler", NARROWED);

        var listed = asTaskRun(run, "{}");

        assertEquals(List.of("search_messages", "label_message"), actionNames(listed.text()));
    }

    @Test
    void describingAnUnnamedActionAnswersThatTheTaskDoesNotAllowIt() {
        var run = seedTaskRun("labeler", NARROWED);

        var described = JsonParser.parseString(asTaskRun(run,
                "{\"describe\": [\"send_message\", \"search_messages\", \"no_such_action\"]}").text()).getAsJsonObject();

        assertEquals(List.of("search_messages"), actionNames(described.toString()));
        assertEquals("[\"no_such_action\"]", described.get("unknown").toString());
        assertEquals("[\"send_message\"]", described.get("notAllowed").toString());
        assertEquals("Not on task 'labeler''s tool list; a call to one is refused.",
                described.get("notAllowedNote").getAsString());
    }

    @Test
    void aCallToAnUnnamedActionIsRefusedRecordedAndNeverReachesTheServer() throws Exception {
        var run = seedTaskRun("labeler", NARROWED);

        var result = new ToolRegistry.ToolResult[1];
        var events = EventLogger.captureForTest(() -> result[0] = asTaskRun(run, "{\"tool\": \"send_message\"}"));

        assertEquals("Error: action 'send_message' of MCP server '" + SERVER + "' is not on task 'labeler''s tool "
                        + "list, so it was not run. Do not retry it. The actions of this server it allows: "
                        + "search_messages, label_message.",
                result[0].text());
        assertEquals(ToolRegistry.ToolResult.Outcome.NOT_ENABLED, result[0].outcome());
        assertEquals(List.of("WARN " + TaskToolPolicy.EVENT_CATEGORY + " MCP action 'send_message' on server '"
                        + SERVER + "' refused: not on task 'labeler''s tool list"),
                events.stream().filter(e -> e.category().equals(TaskToolPolicy.EVENT_CATEGORY))
                        .map(e -> e.level() + " " + e.category() + " " + e.message()).toList());
        assertEquals("", serverHistory());
    }

    @Test
    void aCallToANamedActionRunsAgainstTheServer() throws Exception {
        var run = seedTaskRun("labeler", NARROWED);

        var result = asTaskRun(run, "{\"tool\": \"search_messages\", \"args\": {}}");

        assertEquals("ok:search_messages", result.text());
        assertEquals("search_messages", serverHistory());
    }

    @Test
    void aListNamingTheWholeServerKeepsEveryAction() throws Exception {
        var run = seedTaskRun("whole", "[\"" + HANDLE + "\"]");

        assertEquals(List.of("search_messages", "label_message", "send_message", "history"),
                actionNames(asTaskRun(run, "{}").text()));
        assertEquals("ok:send_message", asTaskRun(run, "{\"tool\": \"send_message\", \"args\": {}}").text());
        assertEquals("send_message", serverHistory());
    }

    @Test
    void aTaskWithNoListKeepsEveryAction() {
        var run = seedTaskRun("open", null);

        assertEquals(List.of("search_messages", "label_message", "send_message", "history"),
                actionNames(asTaskRun(run, "{}").text()));
    }

    @Test
    void aNamedActionTheServerNoLongerOffersIsNotListed() {
        var run = seedTaskRun("stale", "[\"" + HANDLE + "/search_messages\",\"" + HANDLE + "/removed_action\"]");

        assertEquals(List.of("search_messages"), actionNames(asTaskRun(run, "{}").text()));
    }

    @Test
    void aListNamingNoneOfTheServersActionsLeavesItNone() {
        var run = seedTaskRun("elsewhere", "[\"web_search\",\"mcp_other/search_messages\"]");

        assertEquals("MCP server '" + SERVER + "' offers no action on task 'elsewhere''s tool list.",
                asTaskRun(run, "{}").text());
    }

    // ==================== a subagent's inherited list ====================

    @Test
    void aSubagentIsHeldToTheActionsItsConversationInherited() throws Exception {
        var conversationId = Tx.run(() -> {
            var conv = ConversationService.create(agent, "web", "u-jf373");
            conv.enabledToolNames = "[\"" + HANDLE + "/label_message\"]";
            conv.save();
            return conv.id;
        });
        var tool = new McpServerTool(SERVER);

        var listed = ToolContext.withScope(conversationId, null, () -> tool.executeRich("{}", agent));
        var refused = ToolContext.withScope(conversationId, null,
                () -> tool.executeRich("{\"tool\": \"search_messages\"}", agent));

        assertEquals(List.of("label_message"), actionNames(listed.text()));
        assertEquals("Error: action 'search_messages' of MCP server '" + SERVER + "' is not on the tool list this "
                        + "subagent inherited, so it was not run. Do not retry it. The actions of this server it "
                        + "allows: label_message.",
                refused.text());
        assertEquals("", serverHistory());
    }

    // ==================== the prompt's catalog ====================

    @Test
    void thePromptCatalogCountsOnlyTheNamedActions() {
        var disabled = Tx.run(() -> ToolRegistry.fencedDisabled(agent, Set.of(HANDLE + "/search_messages")));

        assertFalse(disabled.contains(HANDLE));
        assertEquals(Set.of(HANDLE + "/label_message", HANDLE + "/send_message", HANDLE + "/history"),
                slashEntries(disabled));
        assertEquals("1 action", catalogCount(disabled));
    }

    @Test
    void thePromptCatalogCountsEveryActionWhenTheListNamesTheServer() {
        var disabled = Tx.run(() -> ToolRegistry.fencedDisabled(agent, Set.of(HANDLE)));

        assertEquals(Set.of(), slashEntries(disabled));
        assertEquals("4 actions", catalogCount(disabled));
    }

    // ==================== helpers ====================

    private ToolRegistry.ToolResult asTaskRun(long taskRunId, String args) {
        var tool = new McpServerTool(SERVER);
        return ToolContext.withScope(null, taskRunId, () -> tool.executeRich(args, agent));
    }

    private long seedTaskRun(String name, String enabledToolNames) {
        return Tx.run(() -> {
            var task = new Task();
            task.agent = agent;
            task.name = name;
            task.description = "label the inbox";
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.initialStatusFor(Task.Type.IMMEDIATE);
            task.enabledToolNames = enabledToolNames;
            task.save();
            var run = new TaskRun();
            run.task = task;
            run.startedAt = AppClock.now();
            run.status = TaskRun.Status.RUNNING;
            run.save();
            return run.id;
        });
    }

    /** The actions a listing or a describe answer carries, by name, in order. */
    private static List<String> actionNames(String json) {
        var names = new ArrayList<String>();
        for (var action : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("actions")) {
            names.add(action.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    private static Set<String> slashEntries(Set<String> disabled) {
        var out = new java.util.HashSet<String>();
        for (var name : disabled) if (name.startsWith(HANDLE + "/")) out.add(name);
        return out;
    }

    /** The Actions cell of this server's row in the prompt's MCP catalog. */
    private static String catalogCount(Set<String> disabled) {
        var catalog = Tx.run(() -> McpServerCatalog.formatCatalogForPrompt(disabled));
        for (var row : catalog.split("\n")) {
            if (row.startsWith("| `" + HANDLE + "` |")) return row.split("\\|")[2].trim();
        }
        throw new AssertionError("no row for " + HANDLE + " in:\n" + catalog);
    }

    private static String serverHistory() throws Exception {
        return McpConnectionManager.callTool(SERVER, "history", new JsonObject()).content();
    }

    private McpServer seedStdioServer() throws IOException {
        var path = Files.createTempFile("mcp-srv-" + SERVER + "-", ".js");
        Files.writeString(path, FIXTURE_SCRIPT);
        path.toFile().deleteOnExit();
        var cfg = new JsonObject();
        cfg.addProperty("command", "node");
        var args = new com.google.gson.JsonArray();
        args.add(path.toString());
        cfg.add("args", args);
        return Tx.run(() -> {
            var srv = new McpServer();
            srv.name = SERVER;
            srv.enabled = true;
            srv.transport = McpServer.Transport.STDIO;
            srv.configJson = cfg.toString();
            srv.save();
            return srv;
        });
    }

    private static void awaitConnected() throws InterruptedException {
        var deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            if (McpConnectionManager.status(SERVER) == McpServer.Status.CONNECTED) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + SERVER + " to connect (lastError=" + McpConnectionManager.lastError(SERVER) + ")");
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
