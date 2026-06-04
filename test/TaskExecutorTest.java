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

    // === JCLAW-260: fire-time prompt resolution (pure, no fire) ===

    @Test
    void resolveAgentPromptFlattensMultiStepForAgentTask() {
        var t = new Task();
        t.name = "daily-summary";
        t.description = "[\"Fetch orders\",\"Post summary\"]";
        t.noAgent = false;
        assertEquals("1. Fetch orders\n2. Post summary",
                TaskExecutor.resolveAgentPrompt(t));
    }

    @Test
    void resolveAgentPromptKeepsSingleStepVerbatim() {
        var t = new Task();
        t.name = "x";
        t.description = "Just do the thing";
        t.noAgent = false;
        assertEquals("Just do the thing", TaskExecutor.resolveAgentPrompt(t));
    }

    @Test
    void resolveAgentPromptExemptsNoAgentTasks() {
        // script / noAgent tasks deliver their description verbatim — never
        // flattened or numbered, even when it happens to be a JSON array.
        var t = new Task();
        t.name = "x";
        t.description = "[\"line one\",\"line two\"]";
        t.noAgent = true;
        assertEquals("[\"line one\",\"line two\"]",
                TaskExecutor.resolveAgentPrompt(t));
    }

    @Test
    void resolveAgentPromptFallsBackToNameWhenDescriptionBlank() {
        var t = new Task();
        t.name = "fallback-name";
        t.description = "";
        t.noAgent = false;
        assertEquals("fallback-name", TaskExecutor.resolveAgentPrompt(t));
    }

    @Test
    void resolveAgentPromptAppendsToolDeliveryDirective() {
        // JCLAW-419: tool: delivery injects a directive so the agent actually
        // calls the tool — the typed field DRIVES execution.
        var t = new Task();
        t.name = "email-report";
        t.description = "Generate the report";
        t.noAgent = false;
        t.delivery = "tool:send_gmail_message";
        var prompt = TaskExecutor.resolveAgentPrompt(t);
        assertTrue(prompt.startsWith("Generate the report"), prompt);
        assertTrue(prompt.contains("send_gmail_message"), prompt);
        assertTrue(prompt.contains("deliver it by calling"), prompt);
    }

    @Test
    void resolveAgentPromptNoDirectiveForChannelDelivery() {
        // Channel delivery is handled post-run by the dispatcher — no in-prompt
        // directive (would be noise; the agent doesn't deliver it itself).
        var t = new Task();
        t.name = "x";
        t.description = "Do the thing";
        t.noAgent = false;
        t.delivery = "telegram:12345";
        assertEquals("Do the thing", TaskExecutor.resolveAgentPrompt(t));
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

        // JCLAW-21 lifecycle audit: TASK_STARTED + TASK_COMPLETED
        // bookmarks must land in event_log with the agent name and a
        // structured details payload carrying task_id / run_id.
        services.EventLogger.flush();
        var events = loadEventsByCategory("TASK_STARTED", "TASK_COMPLETED");
        assertTrue(events.stream().anyMatch(e ->
                "TASK_STARTED".equals(e.category)
                && e.message != null
                && e.message.contains("Daily summary")),
                "TASK_STARTED must reference the task name");
        assertTrue(events.stream().anyMatch(e ->
                "TASK_COMPLETED".equals(e.category)
                && e.details != null
                && e.details.contains("\"task_id\":" + task.id)
                && e.details.contains("\"run_id\":" + closed.id)),
                "TASK_COMPLETED details must carry task_id and run_id");
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

    // === Auto-delivery wire (Bug 1 fix) ===

    @Test
    void runTaskAutoDeliversToWebConversationViaTaskDelivery() throws Exception {
        startLlmServer(simpleResponse("Reminder: brush your teeth."));
        configureProvider();

        var agent = createAgent("delivery-agent", "test-provider", "test-model");
        var conv = new models.Conversation();
        conv.agent = agent;
        conv.channelType = "web";
        conv.save();

        var task = persistTask(agent, "brush-teeth",
                "Tell the user to brush their teeth.", Task.Type.SCHEDULED);
        task.delivery = "web:" + conv.id;
        task.save();

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var closed = fireOnVirtualThread(task);
        JPA.em().clear();

        var fresh = (TaskRun) TaskRun.findById(closed.id);
        assertEquals(TaskRun.Status.COMPLETED, fresh.status);
        assertEquals(TaskRun.DeliveryStatus.DELIVERED, fresh.deliveryStatus,
                "auto-delivery must mark the TaskRun delivered");
        assertEquals("web:" + conv.id, fresh.deliveryTarget,
                "deliveryTarget should echo the spec we routed through");
        assertNull(fresh.deliveryError, "no error on successful delivery");

        // The dispatched message lands as a USER row on the target conversation
        // with messageKind="subagent_send" (see DeliveryDispatcher.dispatchWeb).
        var delivered = Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var rows = (java.util.List<Object>) (java.util.List<?>) models.Message.find(
                    "conversation.id = ?1 ORDER BY id DESC", conv.id).fetch();
            return rows.isEmpty() ? null : (models.Message) rows.getFirst();
        });
        assertNotNull(delivered, "auto-delivery must append a Message row to the target conversation");
        assertEquals("Reminder: brush your teeth.", delivered.content);
        assertEquals("subagent_send", delivered.messageKind);

        services.EventLogger.flush();
        var events = loadEventsByCategory("TASK_DELIVERED");
        assertTrue(events.stream().anyMatch(e ->
                        e.details != null
                        && e.details.contains("\"task_id\":" + task.id)
                        && e.details.contains("\"run_id\":" + closed.id)),
                "TASK_DELIVERED lifecycle event must carry task_id and run_id");
    }

    @Test
    void runTaskSkipsAutoDeliveryWhenNoDeliverySpec() throws Exception {
        startLlmServer(simpleResponse("Headless reply."));
        configureProvider();

        var agent = createAgent("headless-agent", "test-provider", "test-model");
        var task = persistTask(agent, "headless", "Do something quiet.", Task.Type.IMMEDIATE);
        // task.delivery left null — no auto-delivery should fire.

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var closed = fireOnVirtualThread(task);
        JPA.em().clear();

        var fresh = (TaskRun) TaskRun.findById(closed.id);
        assertEquals(TaskRun.Status.COMPLETED, fresh.status);
        assertEquals(TaskRun.DeliveryStatus.NOT_REQUESTED, fresh.deliveryStatus,
                "absent delivery spec must surface as NOT_REQUESTED, not DELIVERED");
        assertNull(fresh.deliveryTarget);
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

    private java.util.List<models.EventLog> loadEventsByCategory(String... categories) {
        return Tx.run(() -> {
            var raw = models.EventLog.find(
                    "category IN (?1)", java.util.Arrays.asList(categories)).fetch();
            var typed = new java.util.ArrayList<models.EventLog>(raw.size());
            for (var row : raw) typed.add((models.EventLog) row);
            return typed;
        });
    }
}
