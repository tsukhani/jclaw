import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import models.Agent;
import models.EventLog;
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
import services.EventLogger;
import services.TaskExecutionHandler;
import services.Tx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end functional test for the JCLAW-21 task fire pipeline.
 * Drives {@link TaskExecutionHandler#buildTask}'s
 * {@code CustomTask<Void>} directly with a {@link TaskInstance} the
 * way db-scheduler's polling thread would after picking up a row.
 * Exercises every component the prior JCLAW-21 commits wired in:
 *
 * <ul>
 *   <li>{@link TaskExecutionHandler}'s decode-and-dispatch (parses
 *       the {@code task_instance} string back to a Task primary key)</li>
 *   <li>{@link services.TaskExecutor}'s TaskRun lifecycle bookkeeping
 *       (opens RUNNING, closes COMPLETED, durationMs populated)</li>
 *   <li>{@link agents.AgentRunner#runForTask} with
 *       {@link agents.TaskRunSink} writes
 *       (system → user → assistant turns land in
 *       {@code task_run_message})</li>
 *   <li>{@link services.TaskLifecycleEvents} emissions —
 *       TASK_STARTED and TASK_COMPLETED land in
 *       {@code event_log} with structured details</li>
 * </ul>
 *
 * <p>We don't spin up a live db-scheduler instance in the test JVM:
 * the autotest classloader / lifecycle quirk makes
 * {@code DbSchedulerBootstrapJob.scheduler()} unreliable at test
 * method time, and direct invocation gives the same coverage of the
 * production lambda. The self-rescheduling CompletionHandler path
 * (CRON / INTERVAL) is covered by unit tests for the helpers; this
 * test exercises the one-shot IMMEDIATE path that's the dominant
 * shape of a JClaw task fire.
 *
 * <p>LLM provider is mocked by an embedded {@link com.sun.net.httpserver.HttpServer}
 * returning a deterministic OpenAI-compatible response — same
 * pattern {@code AgentRunnerCoreTest} and {@code TaskExecutorTest}
 * use.
 */
class TaskFireFunctionalTest extends UnitTest {

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

    @Test
    void endToEndFireProducesCompletedTaskRunAndLifecycleEvents() throws Exception {
        startLlmServer(simpleResponse("Daily summary: nothing happened yesterday."));
        configureProvider();

        var agent = createAgent("functional-fire-agent", "test-provider", "test-model");
        var task = persistTask(agent, "Daily summary",
                "Summarise yesterday's activity.", Task.Type.IMMEDIATE);

        // Commit so the inner Tx blocks (TaskExecutor, sinks, runForTask)
        // running on a virtual thread see the persisted rows.
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        // Drive the production lambda directly. TaskInstance carries the
        // Task.id as the instance id (same encoding TaskExecutionHandler
        // decodes); the stub ExecutionContext is never read by the
        // execute body so its null fields are safe.
        var dbTask = TaskExecutionHandler.buildTask();
        var instance = new TaskInstance<Void>(TaskExecutionHandler.TASK_NAME,
                task.id.toString());
        var execution = new Execution(Instant.now(), instance);
        var ctx = new ExecutionContext(null, execution, null, null);

        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                dbTask.execute(instance, ctx);
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "execute should complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();

        JPA.em().clear();

        // === TaskRun lifecycle ===
        var runs = listRunsForTask(task.id);
        assertEquals(1, runs.size(), "exactly one TaskRun for this fire");
        var run = runs.getFirst();
        assertEquals(TaskRun.Status.COMPLETED, run.status,
                "TaskRun should close COMPLETED on the happy path");
        assertNotNull(run.startedAt, "startedAt populated");
        assertNotNull(run.completedAt, "completedAt populated");
        assertNotNull(run.durationMs, "durationMs populated");
        assertTrue(run.durationMs >= 0, "durationMs non-negative");
        assertEquals("Daily summary: nothing happened yesterday.", run.outputSummary,
                "outputSummary carries the model's final reply");
        assertNull(run.error, "no error on a clean fire");

        // === task_run_message transcript ===
        var msgs = loadMessages(run.id);
        assertEquals(2, msgs.size(), "user + assistant turns persisted");
        assertEquals(MessageRole.USER, msgs.get(0).role);
        assertEquals("Summarise yesterday's activity.", msgs.get(0).content);
        assertEquals(MessageRole.ASSISTANT, msgs.get(1).role);
        assertEquals("Daily summary: nothing happened yesterday.", msgs.get(1).content);

        // === Lifecycle event_log ===
        EventLogger.flush();
        var startedEvents = loadEventsByCategory("TASK_STARTED");
        var completedEvents = loadEventsByCategory("TASK_COMPLETED");
        assertEquals(1, startedEvents.size(), "exactly one TASK_STARTED for this fire");
        assertEquals(1, completedEvents.size(), "exactly one TASK_COMPLETED for this fire");

        var started = startedEvents.getFirst();
        assertEquals("INFO", started.level);
        assertEquals(agent.name, started.agentId,
                "TASK_STARTED carries the executing agent's name");
        assertTrue(started.message.contains("Daily summary"),
                "TASK_STARTED message references the task name");
        assertTrue(started.details != null && started.details.contains("\"task_id\":" + task.id),
                "TASK_STARTED details carry task_id");
        assertTrue(started.details.contains("\"run_id\":" + run.id),
                "TASK_STARTED details carry run_id");
        assertTrue(started.details.contains("\"type\":\"IMMEDIATE\""),
                "TASK_STARTED details carry the Task.Type");

        var completed = completedEvents.getFirst();
        assertEquals("INFO", completed.level);
        assertTrue(completed.details.contains("\"task_id\":" + task.id),
                "TASK_COMPLETED carries task_id");
        assertTrue(completed.details.contains("\"run_id\":" + run.id),
                "TASK_COMPLETED carries run_id");
        assertTrue(completed.details.contains("\"duration_ms\":"),
                "TASK_COMPLETED carries duration_ms");

        // Sanity: no TASK_FAILED on a clean fire.
        assertTrue(loadEventsByCategory("TASK_FAILED").isEmpty(),
                "TASK_FAILED must not fire on a successful run");
    }

    @Test
    void cancelledTaskIsSkippedWithoutOpeningARun() throws Exception {
        // No LLM server needed — handler short-circuits before runForTask.
        var agent = createAgent("cancel-test-agent", "test-provider", "test-model");
        var task = persistTask(agent, "Was cancelled",
                "Should not run.", Task.Type.IMMEDIATE);
        task.status = Task.Status.CANCELLED;
        task.save();

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var dbTask = TaskExecutionHandler.buildTask();
        var instance = new TaskInstance<Void>(TaskExecutionHandler.TASK_NAME,
                task.id.toString());
        var ctx = new ExecutionContext(null,
                new Execution(Instant.now(), instance), null, null);

        var thread = Thread.ofVirtual().start(() -> dbTask.execute(instance, ctx));
        thread.join(5_000);
        assertFalse(thread.isAlive());

        JPA.em().clear();

        // Skip means: no TaskRun row, no lifecycle events.
        var runs = listRunsForTask(task.id);
        assertTrue(runs.isEmpty(), "CANCELLED skip must not open a TaskRun");
        EventLogger.flush();
        assertTrue(loadEventsByCategory("TASK_STARTED").isEmpty());
        assertTrue(loadEventsByCategory("TASK_COMPLETED").isEmpty());
        assertTrue(loadEventsByCategory("TASK_FAILED").isEmpty());
    }

    @Test
    void pausedTaskIsSkippedWithoutOpeningARun() throws Exception {
        var agent = createAgent("paused-test-agent", "test-provider", "test-model");
        var task = persistTask(agent, "Paused for now",
                "Skip body.", Task.Type.IMMEDIATE);
        task.paused = true;
        task.save();

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var dbTask = TaskExecutionHandler.buildTask();
        var instance = new TaskInstance<Void>(TaskExecutionHandler.TASK_NAME,
                task.id.toString());
        var ctx = new ExecutionContext(null,
                new Execution(Instant.now(), instance), null, null);

        var thread = Thread.ofVirtual().start(() -> dbTask.execute(instance, ctx));
        thread.join(5_000);
        assertFalse(thread.isAlive());

        JPA.em().clear();

        var runs = listRunsForTask(task.id);
        assertTrue(runs.isEmpty(), "paused skip must not open a TaskRun");
        EventLogger.flush();
        assertTrue(loadEventsByCategory("TASK_STARTED").isEmpty(),
                "paused skip must not emit TASK_STARTED — fire body never ran");
    }

    // === Helpers ===

    private Agent createAgent(String name, String provider, String model) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = provider;
        a.modelId = model;
        a.enabled = true;
        a.save();
        return a;
    }

    private Task persistTask(Agent agent, String name, String description, Task.Type type) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.description = description;
        t.type = type;
        t.status = Task.Status.PENDING;
        t.nextRunAt = Instant.now();
        t.createdAt = Instant.now();
        t.updatedAt = Instant.now();
        t.save();
        return t;
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

    private List<TaskRun> listRunsForTask(Long taskId) {
        return Tx.run(() -> {
            var raw = TaskRun.find("task.id = ?1 ORDER BY startedAt ASC", taskId).fetch();
            var typed = new ArrayList<TaskRun>(raw.size());
            for (var r : raw) typed.add((TaskRun) r);
            return typed;
        });
    }

    private List<TaskRunMessage> loadMessages(Long runId) {
        return Tx.run(() -> {
            var raw = TaskRunMessage.find(
                    "taskRun.id = ?1 ORDER BY turnIndex ASC", runId).fetch();
            var typed = new ArrayList<TaskRunMessage>(raw.size());
            for (var r : raw) typed.add((TaskRunMessage) r);
            return typed;
        });
    }

    private List<EventLog> loadEventsByCategory(String category) {
        return Tx.run(() -> {
            var raw = EventLog.find("category = ?1", category).fetch();
            var typed = new ArrayList<EventLog>(raw.size());
            for (var r : raw) typed.add((EventLog) r);
            return typed;
        });
    }
}
