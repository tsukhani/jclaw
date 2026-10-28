import models.Agent;
import models.MessageRole;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.TaskExecutor;
import tools.MessageTool;

import java.time.Instant;

/**
 * JCLAW-1017: a task's auto-delivery must be suppressed only when the fire actually delivered
 * its payload itself.
 *
 * <p>Reproduces the filed defect from task 4003 / run 38705: both {@code message} calls came
 * back {@code Error: Slack rejected delivery ... channel_not_found}, and the run still stamped
 * {@code NOT_REQUESTED} — so the digest reached neither Slack nor the dispatcher that would
 * have delivered it correctly.
 *
 * <p>The transcript shape mirrors {@code TaskRunSink}: the call is recorded on an ASSISTANT
 * row in {@code tool_calls}, and its outcome on a following TOOL row whose {@code tool_results}
 * column carries the <em>call id</em> and whose {@code content} carries the result text.
 */
class TaskDeliveryDedupTest extends UnitTest {

    private Task task;
    private int turn;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        var agent = new Agent();
        agent.name = "dedup-agent-" + System.nanoTime();
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();

        task = new Task();
        task.agent = agent;
        task.name = "dedup-task";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.scheduledAt = Instant.now();
        task.nextRunAt = Instant.now();
        task.delivery = "slack:ai-research";
        task.save();
        turn = 0;
    }

    private TaskRun newRun() {
        var run = new TaskRun();
        run.task = task;
        run.startedAt = Instant.now();
        run.status = TaskRun.Status.COMPLETED;
        run.outputSummary = "the digest";
        run.save();
        return run;
    }

    /** One {@code message} call plus the TOOL row answering it, exactly as TaskRunSink writes them. */
    private void messageCall(TaskRun run, String callId, String action, String result) {
        var call = new TaskRunMessage();
        call.taskRun = run;
        call.turnIndex = turn++;
        call.role = MessageRole.ASSISTANT;
        call.toolCalls = ("{\"id\":\"%s\",\"type\":\"function\",\"function\":{\"name\":\"message\","
                + "\"arguments\":\"{\\\"action\\\":\\\"%s\\\"}\"}}").formatted(callId, action);
        call.save();

        var answer = new TaskRunMessage();
        answer.taskRun = run;
        answer.turnIndex = turn++;
        answer.role = MessageRole.TOOL;
        answer.toolResults = callId;   // the call id, not the result — see the class doc
        answer.content = result;
        answer.save();
    }

    private static final String FAILED =
            "Error: Slack rejected delivery to 'UA6PAPH8U': channel_not_found.";
    private static final String SENT =
            "{\"action\":\"sent\",\"channel\":\"slack\",\"target\":\"#ai-research\"}";

    @Test
    void aFireWhoseEveryMessageCallFailedFallsThroughToTheDispatcher() {
        var run = newRun();
        messageCall(run, "functions.message:27", "send", FAILED);
        messageCall(run, "functions.message:28", "send", FAILED);
        assertFalse(TaskExecutor.deliveredViaMessageTool(run.id),
                "every send errored, so nothing was delivered and the dispatcher must run");
    }

    @Test
    void aFireThatDeliveredIsStillDeduped() {
        var run = newRun();
        messageCall(run, "functions.message:1", "send", SENT);
        assertTrue(TaskExecutor.deliveredViaMessageTool(run.id),
                "a successful send means auto-delivery would duplicate it");
    }

    @Test
    void onePassingSendAmongFailuresCountsAsDelivered() {
        var run = newRun();
        messageCall(run, "functions.message:1", "send", FAILED);
        messageCall(run, "functions.message:2", "send", SENT);
        assertTrue(TaskExecutor.deliveredViaMessageTool(run.id),
                "the recipient already has it; re-dispatching would duplicate");
    }

    @Test
    void aNonDeliveringActionDoesNotSuppressDeliveryEvenWhenItSucceeds() {
        // react/pin/unpin/delete name the same tool but push no content, so a fire that only
        // reacted has still delivered nothing.
        var run = newRun();
        messageCall(run, "functions.message:9", "react", "{\"action\":\"react\",\"ok\":true}");
        assertFalse(TaskExecutor.deliveredViaMessageTool(run.id),
                "reacting delivers nothing, so the run's output still needs dispatching");
    }

    @Test
    void aRunWithNoMessageCallsIsNotDeduped() {
        var run = newRun();
        var only = new TaskRunMessage();
        only.taskRun = run;
        only.turnIndex = turn++;
        only.role = MessageRole.ASSISTANT;
        only.content = "no tools used";
        only.save();
        assertFalse(TaskExecutor.deliveredViaMessageTool(run.id));
    }

    @Test
    void anUnansweredMessageCallIsNotTreatedAsDelivered() {
        // The fire died between the call and its result; nothing proves it landed.
        var run = newRun();
        var call = new TaskRunMessage();
        call.taskRun = run;
        call.turnIndex = turn++;
        call.role = MessageRole.ASSISTANT;
        call.toolCalls = "{\"id\":\"functions.message:5\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"message\",\"arguments\":\"{\\\"action\\\":\\\"send\\\"}\"}}";
        call.save();
        assertFalse(TaskExecutor.deliveredViaMessageTool(run.id));
    }

    // --- the tool's own contract, asserted directly ---

    @Test
    void messageToolSeparatesSuccessPayloadsFromErrorStrings() {
        assertTrue(MessageTool.isSuccessResult(SENT));
        assertFalse(MessageTool.isSuccessResult(FAILED), "an 'Error: ...' string is not JSON");
        assertFalse(MessageTool.isSuccessResult(null));
        assertFalse(MessageTool.isSuccessResult(""));
        assertFalse(MessageTool.isSuccessResult("\"sent\""), "a bare JSON string is not the payload");
    }

    @Test
    void messageToolClassifiesWhichActionsDeliver() {
        for (var a : new String[] {"send", "reply", "edit", "poll"}) {
            assertTrue(MessageTool.isDeliveringAction(a), a + " puts content in front of someone");
        }
        for (var a : new String[] {"react", "pin", "unpin", "delete"}) {
            assertFalse(MessageTool.isDeliveringAction(a), a + " delivers nothing");
        }
        assertFalse(MessageTool.isDeliveringAction(null));
        assertFalse(MessageTool.isDeliveringCall("not json"),
                "unreadable arguments are not positive evidence of a delivery");
    }
}
