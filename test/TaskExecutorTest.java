import models.Agent;
import models.MessageRole;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.TaskExecutor;
import services.Tx;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end fire test for {@link TaskExecutor}: persist a {@link Task},
 * mock the LLM provider with an embedded HTTP server, fire the task,
 * and assert the {@link TaskRun} and {@link TaskRunMessage} rows it
 * produces match the expected shape.
 *
 * <p>Same mock-provider pattern as {@code AgentRunnerCoreTest} — no
 * external network, real H2 DB, real {@code ProviderRegistry}.
 *
 * <p>What this test pins:
 * <ul>
 *   <li>{@code runTask} opens a TaskRun in {@code RUNNING}, drives the
 *       loop via {@link agents.AgentRunner#runForTask}, then closes it
 *       to {@code COMPLETED} with a populated {@code outputSummary}
 *       and a non-null {@code durationMs}.</li>
 *   <li>Two task_run_message rows land: a USER row carrying the
 *       prompt and an ASSISTANT row carrying the model's reply, with
 *       {@code turn_index} 0 and 1.</li>
 *   <li>When {@code task.description} is non-blank it's used as the
 *       prompt; falls back to {@code task.name} otherwise.</li>
 *   <li>When the provider isn't configured, the runner emits a
 *       graceful "No LLM provider configured" assistant message
 *       (the {@code AgentRunner.runForTask} provider-missing branch)
 *       and the TaskRun still closes cleanly.</li>
 * </ul>
 *
 * <p>What this test does NOT cover (scope of separate JCLAW-21 commits):
 * <ul>
 *   <li>Tool-call loops — the mock LLM here returns a single
 *       finish_reason=stop response, exercising the no-tool-call
 *       branch only. {@code AgentRunnerCoreTest} covers the tool
 *       recursion; {@code TaskExecutor} reuses the same loop body.</li>
 *   <li>Retry policy — JClawFailureHandler ships separately.</li>
 *   <li>Delivery dispatch — TaskExecutor's job stops at the closed
 *       TaskRun; downstream dispatch is the
 *       {@code TaskExecutionHandler}'s concern.</li>
 * </ul>
 */
class TaskExecutorTest extends UnitTest {

    private com.sun.net.httpserver.HttpServer llmServer;
    private int port;

    @BeforeEach
    void setup() throws Exception {
        Thread.sleep(200);
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
    }

    @AfterEach
    void teardown() {
        if (llmServer != null) {
            llmServer.stop(0);
            llmServer = null;
        }
    }

    // === Happy path: description-as-prompt + assistant reply ===

    @Test
    void runTaskCompletesAndPersistsTranscript() throws Exception {
        startLlmServer(simpleResponse("Hi from the task fire."));
        configureProvider();

        var agent = createAgent("task-agent", "test-provider", "test-model");
        var task = persistTask(agent, "Daily summary",
                "Summarise yesterday's activity and report.", Task.Type.IMMEDIATE);

        // Commit so the virtual thread inside runTask sees the rows.
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var closed = fireOnVirtualThread(task);
        JPA.em().clear();

        var fresh = (TaskRun) TaskRun.findById(closed.id);
        assertEquals(TaskRun.Status.COMPLETED, fresh.status,
                "TaskRun should end in COMPLETED");
        assertNotNull(fresh.completedAt, "completedAt must be stamped");
        assertNotNull(fresh.durationMs, "durationMs must be populated");
        assertTrue(fresh.durationMs >= 0, "durationMs must be non-negative");
        assertEquals("Hi from the task fire.", fresh.outputSummary,
                "outputSummary should carry the model's final reply");
        assertNull(fresh.error, "no error on a clean fire");

        var rows = loadMessages(fresh.id);
        assertEquals(2, rows.size(), "user + assistant turns persisted, got: " + rows.size());

        var user = rows.get(0);
        assertEquals(MessageRole.USER, user.role);
        assertEquals(0, user.turnIndex);
        assertEquals("Summarise yesterday's activity and report.", user.content);

        var assistant = rows.get(1);
        assertEquals(MessageRole.ASSISTANT, assistant.role);
        assertEquals(1, assistant.turnIndex);
        assertEquals("Hi from the task fire.", assistant.content);
    }

    @Test
    void runTaskFallsBackToNameWhenDescriptionIsBlank() throws Exception {
        startLlmServer(simpleResponse("Acknowledged."));
        configureProvider();

        var agent = createAgent("task-agent-2", "test-provider", "test-model");
        var task = persistTask(agent, "Ping the API", "", Task.Type.IMMEDIATE);

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var closed = fireOnVirtualThread(task);
        JPA.em().clear();

        var fresh = (TaskRun) TaskRun.findById(closed.id);
        assertEquals(TaskRun.Status.COMPLETED, fresh.status);

        var rows = loadMessages(fresh.id);
        assertEquals(2, rows.size());
        assertEquals("Ping the API", rows.get(0).content,
                "blank description should fall back to task.name");
    }

    // === Provider-missing graceful degradation ===

    @Test
    void runTaskClosesCleanlyWhenNoProviderConfigured() throws Exception {
        // Don't start an LLM server, don't configure a provider — the
        // runForTask provider-missing branch emits a graceful assistant
        // message and the task closes COMPLETED rather than throwing.
        var agent = createAgent("orphan-agent", "nonexistent-provider", "test-model");
        var task = persistTask(agent, "Will fail", "Try anyway.", Task.Type.IMMEDIATE);

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var closed = fireOnVirtualThread(task);
        JPA.em().clear();

        var fresh = (TaskRun) TaskRun.findById(closed.id);
        assertEquals(TaskRun.Status.COMPLETED, fresh.status,
                "graceful provider-missing branch should still close COMPLETED");

        var rows = loadMessages(fresh.id);
        assertEquals(2, rows.size(), "user + provider-missing assistant turn");
        assertTrue(rows.get(1).content.contains("No LLM provider configured"),
                "assistant message should explain the missing provider, got: "
                        + rows.get(1).content);
    }

    // === Helpers ===

    private Agent createAgent(String name, String provider, String model) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = provider;
        agent.modelId = model;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private Task persistTask(Agent agent, String name, String description, Task.Type type) {
        var task = new Task();
        task.agent = agent;
        task.name = name;
        task.description = description;
        task.type = type;
        task.status = Task.Status.PENDING;
        task.createdAt = Instant.now();
        task.updatedAt = Instant.now();
        task.save();
        return task;
    }

    private void startLlmServer(String staticResponse) throws Exception {
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, staticResponse.getBytes().length);
            exchange.getResponseBody().write(staticResponse.getBytes());
            exchange.close();
        });
        llmServer.start();
        port = llmServer.getAddress().getPort();
    }

    private void configureProvider() {
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:" + port);
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        llm.ProviderRegistry.refresh();
    }

    private static String simpleResponse(String content) {
        return """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(
                content.replace("\"", "\\\""));
    }

    /**
     * Run the fire on a virtual thread so the inner Tx.run calls don't
     * collide with the test thread's open transaction (FunctionalTest
     * carrier-thread Tx issue noted in
     * {@code project_functionaltest_tx_isolation} memory).
     */
    private TaskRun fireOnVirtualThread(Task task) throws Exception {
        var taskId = task.id;
        var resultRef = new AtomicReference<TaskRun>();
        var errorRef = new AtomicReference<Exception>();

        var thread = Thread.ofVirtual().start(() -> {
            try {
                var t = Tx.run(() -> (Task) Task.findById(taskId));
                resultRef.set(TaskExecutor.runTask(t));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "TaskExecutor.runTask should complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }

    private java.util.List<TaskRunMessage> loadMessages(Long taskRunId) {
        return Tx.run(() -> {
            var raw = TaskRunMessage.find(
                    "taskRun.id = ?1 ORDER BY turnIndex ASC", taskRunId).fetch();
            var typed = new java.util.ArrayList<TaskRunMessage>(raw.size());
            for (var row : raw) typed.add((TaskRunMessage) row);
            return typed;
        });
    }
}
