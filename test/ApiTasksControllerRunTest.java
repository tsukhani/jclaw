import controllers.RequestPrincipal;
import models.Agent;
import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConversationService;
import services.EventLogger;
import services.TaskRunReports;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Functional HTTP tests for {@code POST /api/tasks/{id}/run} (JCLAW-294
 * commit 6). Covers all four operator-facing scenarios per the AC:
 * any-state acceptance for PENDING, COMPLETED, FAILED, CANCELLED — with
 * special verification that a CANCELLED task gets revived to PENDING so
 * TaskExecutionHandler doesn't swallow the fire.
 *
 * <p>The scheduler side is null in tests (no live SchedulerClient
 * bootstrap), so TaskSchedulingService.runNow no-ops — what we verify
 * here is the controller's status-handling + audit emission.
 */
class ApiTasksControllerRunTest extends FunctionalTest {

    private static final String AGENT_NAME = "task-run-agent";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
    }

    @AfterEach
    void teardown() {
        EventLogger.flush();
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """);
        assertIsOk(resp);
    }

    private Long seedAgent() {
        return seedAgent(AGENT_NAME);
    }

    private Long seedAgent(String name) {
        var resp = POST("/api/agents", "application/json", """
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(name));
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private Long seedTask(Long agentId, String name, String schedule) {
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "%s", "schedule": "%s"}
                """.formatted(agentId, name, schedule));
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private static String extractId(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new AssertionError("no id in: " + json);
        return m.group(1);
    }

    /**
     * Apply a Task mutation in a fresh transaction so it commits before the
     * next HTTP call sees it. FunctionalTest's carrier thread is already
     * inside a JPA tx; an inline {@code task.save()} would nest in that tx
     * and stay uncommitted, so the controller's HTTP handler (running on
     * {@code TestEngine.functionalTestsExecutor}) wouldn't see the change.
     * Pattern documented in CLAUDE memory project_functionaltest_tx_isolation.
     */
    private static void mutateAndCommit(Long taskId, java.util.function.Consumer<Task> mutator) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var task = (Task) Task.findById(taskId);
                    mutator.accept(task);
                    task.save();
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
    }

    @Test
    void anAgentRunningATaskFromItsOwnChatIsToldThereHowTheRunEnds() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "report-me", "every 1h");
        var chat = committed(() -> ConversationService.create(Agent.findById(agentId), "web", null).id);

        var resp = asAgent(() -> POST(agentRequest(agentId, chat), "/api/tasks/" + taskId + "/run", "application/json", "{}"));

        assertIsOk(resp);
        assertEquals(TaskRunReports.AGENT_NOTE, resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
        var task = committed(() -> (Task) Task.findById(taskId));
        assertEquals(chat, task.reportConversationId);
        assertEquals(Boolean.FALSE, task.reportOwnerInitiated, "the request carries no channel sender");
    }

    @Test
    void aChatThatIsNotTheCallingAgentsOrNoChatAtAllAsksForNoReport() {
        var agentId = seedAgent();
        var otherId = seedAgent("task-run-other-agent");
        var taskId = seedTask(agentId, "not-yours", "every 1h");
        var othersChat = committed(() -> ConversationService.create(Agent.findById(otherId), "web", null).id);

        var naming = asAgent(() -> POST(agentRequest(agentId, othersChat), "/api/tasks/" + taskId + "/run", "application/json", "{}"));
        var unnamed = asAgent(() -> POST(agentRequest(agentId, null), "/api/tasks/" + taskId + "/run", "application/json", "{}"));

        assertIsOk(naming);
        assertIsOk(unnamed);
        assertNull(naming.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
        assertNull(unnamed.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
        assertNull(committed(() -> ((Task) Task.findById(taskId)).reportConversationId));
    }

    @Test
    void runningAPausedTaskFromAChatSaysItIsPausedAndPromisesNoReport() {
        var agentId = seedAgent();
        var taskId = seedTask(agentId, "held-run", "every 1h");
        mutateAndCommit(taskId, t -> t.paused = true);
        var chat = committed(() -> ConversationService.create(Agent.findById(agentId), "web", null).id);

        var resp = asAgent(() -> POST(agentRequest(agentId, chat), "/api/tasks/" + taskId + "/run", "application/json", "{}"));

        assertIsOk(resp);
        assertEquals("Task 'held-run' is paused, so nothing runs until it is resumed.",
                resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
        assertNull(committed(() -> ((Task) Task.findById(taskId)).reportConversationId));
    }

    @Test
    void anotherAgentRunningTheTaskIsNotToldHowItEnds() {
        var ownerId = seedAgent();
        var otherId = seedAgent("task-run-other-agent");
        var taskId = seedTask(ownerId, "someone-elses", "every 1h");
        var otherChat = committed(() -> ConversationService.create(Agent.findById(otherId), "web", null).id);

        var resp = asAgent(() -> POST(agentRequest(otherId, otherChat), "/api/tasks/" + taskId + "/run", "application/json", "{}"));

        assertIsOk(resp);
        assertNull(resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
        assertNull(committed(() -> ((Task) Task.findById(taskId)).reportConversationId));
    }

    private static Http.Request agentRequest(Long agentId, Long conversationId) {
        var request = newRequest();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        if (conversationId != null) {
            request.headers.put(RequestPrincipal.CALLER_CONVERSATION_HEADER,
                    new Http.Header(RequestPrincipal.CALLER_CONVERSATION_HEADER, String.valueOf(conversationId)));
        }
        return request;
    }

    /** A bearer call leaves an agent-principal cookie in the JVM-global jar; clear it so no sibling inherits it. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        } finally {
            clearCookies();
        }
    }

    private static <T> T committed(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(services.Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    @Test
    void runPendingTask() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "pending-run", "every 1h");

        var resp = POST("/api/tasks/" + taskId + "/run", "application/json", "");
        assertIsOk(resp);
        // Alive recurring tasks stay ACTIVE after run — the entity state
        // doesn't change, only the scheduled_tasks fire time does.
        assertContentMatch("\"status\":\"ACTIVE\"", resp);
    }

    @Test
    void runCompletedTaskKeepsCompletedStatus() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "completed-run", "now");

        // Simulate the task finishing — JCLAW-21 flips IMMEDIATE/SCHEDULED
        // to COMPLETED on successful fire. Apply that directly here since
        // the test doesn't drive a real fire.
        mutateAndCommit(taskId, t -> t.status = Task.Status.COMPLETED);

        var resp = POST("/api/tasks/" + taskId + "/run", "application/json", "");
        assertIsOk(resp);
        // COMPLETED stays COMPLETED (per the docstring on /run — re-firing
        // a terminal task is a deliberate operator action; the audit log
        // is the trail). TaskExecutionHandler doesn't skip COMPLETED so
        // the fire would actually execute.
        assertContentMatch("\"status\":\"COMPLETED\"", resp);
    }

    @Test
    void runFailedTaskKeepsFailedStatus() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "failed-run", "every 1h");

        mutateAndCommit(taskId, t -> {
            t.status = Task.Status.FAILED;
            t.lastError = "permanent: bad config";
        });

        var resp = POST("/api/tasks/" + taskId + "/run", "application/json", "");
        assertIsOk(resp);
        // FAILED stays FAILED on /run — that's distinct from /retry which
        // resets retryCount/lastError and flips back to PENDING.
        assertContentMatch("\"status\":\"FAILED\"", resp);
        assertContentMatch("\"lastError\":\"permanent: bad config\"", resp);
    }

    @Test
    void runCancelledTaskRevivesToPending() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "cancelled-run", "every 1h");

        // Cancel via API to set up the CANCELLED state.
        assertIsOk(POST("/api/tasks/" + taskId + "/cancel", "application/json", ""));

        var events = EventLogger.captureMatchingForTest(e -> AGENT_NAME.equals(e.agentId()), _ -> {
            var resp = POST("/api/tasks/" + taskId + "/run", "application/json", "");
            assertIsOk(resp);
            // CANCELLED must flip back to the alive-state for the task's type —
            // ACTIVE for INTERVAL recurring (this case), PENDING for one-shot —
            // otherwise TaskExecutionHandler swallows the fire at the
            // CANCELLED-skip branch.
            assertContentMatch("\"status\":\"ACTIVE\"", resp);
        });

        // The audit message should call out the revival so operators can
        // grep for unusual flips.
        long count = events.stream()
                .filter(e -> e.category().equals("TASK_MGMT_MANUAL_RUN")
                        && e.message().contains("revived from CANCELLED"))
                .count();
        assertEquals(1L, count, "expected one audit row mentioning the revival");
    }

    @Test
    void runNonexistentTaskIs404() {
        var resp = POST("/api/tasks/999999/run", "application/json", "");
        assertStatus(404, resp);
    }

    @Test
    void emitsTaskMgmtManualRunEvent() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "audited-run", "now");

        var events = EventLogger.captureMatchingForTest(e -> AGENT_NAME.equals(e.agentId()),
                _ -> assertIsOk(POST("/api/tasks/" + taskId + "/run", "application/json", "")));
        long count = events.stream()
                .filter(e -> e.category().equals("TASK_MGMT_MANUAL_RUN") && e.message().contains("audited-run"))
                .count();
        assertEquals(1L, count, "expected exactly one TASK_MGMT_MANUAL_RUN event");
    }
}
