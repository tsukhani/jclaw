import agents.ToolContext;
import agents.ToolRegistry;
import models.Agent;
import models.CompressionMetric;
import models.Conversation;
import models.MessageRole;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.compression.ContentHash;
import tools.CcrRetrieveTool;

import java.time.Instant;
import java.util.List;

/**
 * JCLAW-462: ccr_retrieve tool. Verifies it re-finds the original tool-result
 * content by hash within the active conversation (no cache table — the Message
 * row is the source), and its error paths.
 */
class CcrRetrieveToolTest extends UnitTest {

    private Agent agent;
    private Conversation conv;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        new jobs.ToolRegistrationJob().doJob();
        agent = AgentService.create("ccr-agent", "openrouter", "gpt-4.1");
        conv = ConversationService.create(agent, "web", "u-ccr");
    }

    @Test
    void registeredAsSystemTool() throws Exception {
        // Category is a property of the tool itself, so it needs no registry.
        assertEquals("System", new CcrRetrieveTool().category());
        // Only the registration is a registry fact, and reading it needs the
        // registry in a known state — concurrent classes republish it (JCLAW-894).
        // This assertion is where the shared-static defect was first found: it is
        // the same shape that failed on the v0.17.18 push.
        ToolRegistrySync.withCanonicalTools(() ->
                assertNotNull(ToolRegistry.lookupTool(CcrRetrieveTool.TOOL_NAME),
                        "ccr_retrieve must be registered by ToolRegistrationJob"));
    }

    @Test
    void retrievesOriginalByHash() {
        var content = "[{\"id\":1,\"name\":\"alpha\"},{\"id\":2,\"name\":\"beta\"},{\"id\":3,\"name\":\"gamma\"}]";
        ConversationService.appendToolResult(conv, "call_1", content);
        var handle = ContentHash.handle(content);

        var result = ToolContext.withConversation(conv.id,
                () -> new CcrRetrieveTool().execute("{\"hash\":\"" + handle + "\"}", agent));
        assertEquals(content, result, "should return the full original content verbatim");
    }

    @Test
    void retrievesTaskRunOriginalByHash() {
        // JCLAW-462: task fires run on a stub Conversation (null id) and persist
        // tool turns to task_run_message — ccr_retrieve must scan that schema
        // when scoped by task-run id instead of conversation id.
        var content = "[{\"id\":1,\"name\":\"alpha\"},{\"id\":2,\"name\":\"beta\"},{\"id\":3,\"name\":\"gamma\"}]";
        var run = seedTaskRunWithToolResult(content);
        var handle = ContentHash.handle(content);

        var result = ToolContext.withScope(null, run.id,
                () -> new CcrRetrieveTool().execute("{\"hash\":\"" + handle + "\"}", agent));
        assertEquals(content, result, "should return the full original task-run tool result verbatim");
    }

    private TaskRun seedTaskRunWithToolResult(String content) {
        var task = new Task();
        task.agent = agent;
        task.name = "ccr-task-" + System.nanoTime();
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.scheduledAt = Instant.now();
        task.nextRunAt = Instant.now();
        task.save();

        var run = new TaskRun();
        run.task = task;
        run.startedAt = Instant.now();
        run.status = TaskRun.Status.RUNNING;
        run.save();

        var msg = new TaskRunMessage();
        msg.taskRun = run;
        msg.turnIndex = 0;
        msg.role = MessageRole.TOOL;
        msg.content = content;
        msg.save();
        return run;
    }

    @Test
    void aFetchRecordsTheCallingAgentAndTheToolWhoseResultCameBack() {
        var content = "x".repeat(5_000);
        ConversationService.appendAssistantMessage(conv, null, toolCallJson("call_1", "web_fetch"));
        ConversationService.appendToolResult(conv, "call_1", content);

        var result = retrieve(ContentHash.handle(content));

        assertEquals(content, result);
        var row = onlyCcrRowOfThisAgent();
        assertEquals(Boolean.TRUE, row.ccrHit);
        assertEquals("web_fetch", row.toolName);
        assertEquals(ContentHash.handle(content), row.resultHash);
    }

    @Test
    void aFetchByAShorterPrefixRecordsTheResultsFullHandle() {
        var content = "v".repeat(5_000);
        ConversationService.appendAssistantMessage(conv, null, toolCallJson("call_1", "exec"));
        ConversationService.appendToolResult(conv, "call_1", content);

        assertEquals(content, retrieve(ContentHash.handle(content).substring(0, 8)));

        assertEquals(ContentHash.handle(content), onlyCcrRowOfThisAgent().resultHash);
    }

    @Test
    void aMissRecordsTheAgentAndNoTool() {
        ConversationService.appendAssistantMessage(conv, null, toolCallJson("call_1", "web_fetch"));
        ConversationService.appendToolResult(conv, "call_1", "{\"a\":1}");

        var result = retrieve("deadbeefdeadbeef");

        assertEquals("No original found for hash \"deadbeefdeadbeef\" in this conversation"
                + " (it may have been trimmed from history).", result);
        var row = onlyCcrRowOfThisAgent();
        assertEquals(Boolean.FALSE, row.ccrHit);
        assertNull(row.toolName);
        assertNull(row.resultHash);
    }

    @Test
    void aCallIdReusedByALaterResponseNamesTheCallNearestBeforeTheResult() {
        // Providers that number calls per response (call_0, call_1, ...) reuse ids across turns.
        var content = "y".repeat(5_000);
        ConversationService.appendAssistantMessage(conv, null, toolCallJson("call_0", "exec"));
        ConversationService.appendToolResult(conv, "call_0", content);
        ConversationService.appendAssistantMessage(conv, null, toolCallJson("call_0", "web_search"));
        ConversationService.appendToolResult(conv, "call_0", "later result");

        retrieve(ContentHash.handle(content));

        assertEquals("exec", onlyCcrRowOfThisAgent().toolName);
    }

    @Test
    void fetchingBackAFetchedCopyNamesTheToolThatFirstProducedIt() {
        var content = "z".repeat(5_000);
        ConversationService.appendAssistantMessage(conv, null, toolCallJson("call_1", "documents"));
        ConversationService.appendToolResult(conv, "call_1", content);
        ConversationService.appendAssistantMessage(conv, null, toolCallJson("call_2", CcrRetrieveTool.TOOL_NAME));
        ConversationService.appendToolResult(conv, "call_2", content);

        assertEquals(content, retrieve(ContentHash.handle(content)));

        assertEquals("documents", onlyCcrRowOfThisAgent().toolName);
    }

    @Test
    void aResultWithNoCallRowIsAHitWithNoTool() {
        var content = "w".repeat(5_000);
        ConversationService.appendToolResult(conv, "call_9", content);

        assertEquals(content, retrieve(ContentHash.handle(content)));

        var row = onlyCcrRowOfThisAgent();
        assertEquals(Boolean.TRUE, row.ccrHit);
        assertNull(row.toolName);
    }

    @Test
    void aTaskRunFetchRecordsTheToolWhoseResultCameBack() {
        var content = "[{\"id\":1,\"name\":\"alpha\"},{\"id\":2,\"name\":\"beta\"}]";
        var run = seedTaskRunWithToolResult(content);
        var call = new TaskRunMessage();
        call.taskRun = run;
        call.turnIndex = 0;
        call.role = MessageRole.ASSISTANT;
        call.toolCalls = toolCallJson("call_t", "web_search");
        call.save();
        var result = new TaskRunMessage();
        result.taskRun = run;
        result.turnIndex = 0;
        result.role = MessageRole.TOOL;
        result.toolResults = "call_t";
        result.content = "t".repeat(5_000);
        result.save();

        ToolContext.withScope(null, run.id, () -> new CcrRetrieveTool().execute(
                "{\"hash\":\"" + ContentHash.handle(result.content) + "\"}", agent));

        assertEquals("web_search", onlyCcrRowOfThisAgent().toolName);
    }

    @Test
    void aMissingConversationRecordsNoRetrieval() {
        var result = ToolContext.withConversation(Long.MAX_VALUE,
                () -> new CcrRetrieveTool().execute("{\"hash\":\"abc123\"}", agent));

        assertEquals("Error: conversation not found.", result);
        assertEquals(0L, CompressionMetric.count("kind = ?1 AND agentId = ?2",
                CompressionMetric.Kind.CCR_RETRIEVAL, String.valueOf(agent.id)));
    }

    private String retrieve(String handle) {
        return ToolContext.withConversation(conv.id,
                () -> new CcrRetrieveTool().execute("{\"hash\":\"" + handle + "\"}", agent));
    }

    private static String toolCallJson(String id, String name) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
                + "\",\"arguments\":\"{}\"}}";
    }

    /** The retrieval row this test's agent recorded; concurrent classes record their own agents' rows. */
    private CompressionMetric onlyCcrRowOfThisAgent() {
        List<CompressionMetric> rows = CompressionMetric.find("kind = ?1 AND agentId = ?2",
                CompressionMetric.Kind.CCR_RETRIEVAL, String.valueOf(agent.id)).fetch();
        assertEquals(1, rows.size(), "one retrieval row for agent " + agent.id);
        return rows.getFirst();
    }

    @Test
    void reportsMissForUnknownHash() {
        ConversationService.appendToolResult(conv, "call_1", "{\"a\":1}");
        var result = ToolContext.withConversation(conv.id,
                () -> new CcrRetrieveTool().execute("{\"hash\":\"deadbeefdeadbeef\"}", agent));
        assertTrue(result.startsWith("No original found"), result);
    }

    @Test
    void errorsWithoutConversationContext() {
        // Called outside any tool dispatch — ToolContext is empty.
        var result = new CcrRetrieveTool().execute("{\"hash\":\"abc123\"}", agent);
        assertTrue(result.startsWith("Error: ccr_retrieve has no active conversation or task-run context"), result);
    }
}
