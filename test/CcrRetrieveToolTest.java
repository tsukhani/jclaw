import agents.ToolContext;
import agents.ToolRegistry;
import models.Agent;
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
    void registeredAsSystemTool() {
        var t = ToolRegistry.lookupTool(CcrRetrieveTool.TOOL_NAME);
        assertNotNull(t, "ccr_retrieve must be registered by ToolRegistrationJob");
        assertEquals("System", t.category());
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
