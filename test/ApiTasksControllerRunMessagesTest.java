import org.junit.jupiter.api.*;
import play.test.*;
import models.MessageRole;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Functional HTTP tests for {@code GET /api/task-runs/{id}/messages}
 * (JCLAW-22 slice P): the turn-by-turn execution trace for one TaskRun —
 * its {@code task_run_message} rows in turn order. 404 when the run is
 * missing, {@code []} when it produced no messages.
 */
class ApiTasksControllerRunMessagesTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """));
    }

    private Long seedAgent() {
        var resp = POST("/api/agents", "application/json", """
                {"name": "trace-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """);
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private Long seedTask(Long agentId) {
        // "1d" keeps the scheduler's fire far in the future so it can't race the test.
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "trace-task", "schedule": "1d"}
                """.formatted(agentId));
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private static String extractId(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new AssertionError("no id in: " + json);
        return m.group(1);
    }

    /**
     * Seed a TaskRun (plus two task_run_message rows when {@code withMessages})
     * in a fresh tx so the controller thread sees them — the FunctionalTest
     * tx-isolation pattern (see project_functionaltest_tx_isolation). Returns
     * the TaskRun id.
     */
    private static long seedRun(Long taskId, boolean withMessages) {
        var ref = new AtomicReference<Long>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var task = (Task) Task.findById(taskId);
                    var run = new TaskRun();
                    run.task = task;
                    run.startedAt = Instant.now();
                    run.completedAt = Instant.now();
                    run.status = TaskRun.Status.COMPLETED;
                    run.save();
                    if (withMessages) {
                        saveMsg(run, 0, MessageRole.USER, "the user prompt");
                        saveMsg(run, 1, MessageRole.ASSISTANT, "the assistant reply");
                    }
                    ref.set(run.id);
                });
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private static void saveMsg(TaskRun run, int turn, MessageRole role, String content) {
        var m = new TaskRunMessage();
        m.taskRun = run;
        m.turnIndex = turn;
        m.role = role;
        m.content = content;
        m.save();
    }

    @Test
    void returnsMessagesInTurnOrder() {
        var agent = seedAgent();
        var taskId = seedTask(agent);
        var runId = seedRun(taskId, true);

        var resp = GET("/api/task-runs/" + runId + "/messages");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("the user prompt"), body);
        assertTrue(body.contains("the assistant reply"), body);
        assertTrue(body.indexOf("the user prompt") < body.indexOf("the assistant reply"),
                "turn 0 (USER) must appear before turn 1 (ASSISTANT); body=" + body);
        assertTrue(body.contains("\"role\":\"USER\""), body);
        assertTrue(body.contains("\"turnIndex\":0"), body);
    }

    @Test
    void returnsEmptyListForRunWithNoMessages() {
        var agent = seedAgent();
        var taskId = seedTask(agent);
        var runId = seedRun(taskId, false);

        var resp = GET("/api/task-runs/" + runId + "/messages");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    @Test
    void returns404ForMissingRun() {
        var resp = GET("/api/task-runs/999999/messages");
        assertStatus(404, resp);
    }
}
