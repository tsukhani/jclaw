import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import models.Agent;
import models.EventLog;
import models.Task;
import models.TaskRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.EventLogger;
import services.JClawFailureHandler;
import services.TaskExecutionHandler;
import services.Tx;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Functional roundtrip for the JCLAW-21 retry policy.
 *
 * <p>Drives the same pair of calls db-scheduler would make when a
 * task fire fails with a transient error and is then re-fired
 * after the backoff:
 *
 * <ol>
 *   <li><b>First attempt</b>: invoke
 *       {@link JClawFailureHandler#onFailure} with a
 *       {@link SocketTimeoutException} (classified transient by
 *       {@link utils.TransientErrorClassifier}). Asserts that:
 *       <ul>
 *         <li>The {@link RecordingExecutionOperations stub} recorded
 *             a {@code reschedule} call at the backoff instant
 *             (30s for the first retry), not {@code stop}.</li>
 *         <li>The Task row's retryCount bumped to 1, status stayed
 *             PENDING (a retryable failure), and lastError captured
 *             the exception signal.</li>
 *         <li>{@code TASK_FAILED} did NOT emit — the failure was
 *             transient with retry budget remaining, so the
 *             lifecycle bookmark must not fire.</li>
 *       </ul>
 *   </li>
 *
 *   <li><b>Second attempt</b>: re-invoke
 *       {@link TaskExecutionHandler#buildTask}'s {@code execute}
 *       lambda directly with the same {@link TaskInstance}, this
 *       time with a working LLM mock. Asserts that:
 *       <ul>
 *         <li>A TaskRun opens, runs through the production code
 *             path, and closes COMPLETED with the second-attempt
 *             outputSummary.</li>
 *         <li>{@code TASK_STARTED} and {@code TASK_COMPLETED} land
 *             in event_log; {@code TASK_FAILED} still hasn't.</li>
 *         <li>Task row's retryCount remained at 1 (incremented by
 *             the first attempt; not reset on success — operators
 *             can see "fire #2 of #3 attempts" in the row).</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Driving the FailureHandler directly bypasses db-scheduler's
 * polling layer in the same spirit as
 * {@code TaskFireFunctionalTest} — we exercise the production
 * code path byte-for-byte without fighting the autotest lifecycle
 * around the live scheduler.
 */
class TaskRetryRoundtripTest extends UnitTest {

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
    void transientFailureReschedulesAndSecondFireSucceeds() throws Exception {
        var agent = createAgent("retry-test-agent", "test-provider", "test-model");
        var task = persistTask(agent, "Retry me",
                "First attempt should transient-fail, second should succeed.",
                Task.Type.IMMEDIATE);

        // Commit so the inner VT-side Tx blocks see the row.
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var taskInstance = new TaskInstance<Void>(
                TaskExecutionHandler.TASK_NAME, task.id.toString());

        // === First attempt: simulate db-scheduler dispatching to FailureHandler ===

        var firstAttemptStart = Instant.now().minusSeconds(2);
        var firstAttemptEnd = Instant.now();
        var transientError = new SocketTimeoutException("read timed out");
        var execution = new Execution(firstAttemptStart, taskInstance);
        var executionComplete = ExecutionComplete.failure(
                execution, firstAttemptStart, firstAttemptEnd, transientError);
        var ops = new RecordingExecutionOperations(execution);

        var beforeReschedule = Instant.now();
        new JClawFailureHandler().onFailure(executionComplete, ops);

        // The Reschedule path ran, not the Stop path.
        assertEquals(1, ops.reschedules.size(),
                "transient + budget left → exactly one reschedule");
        assertFalse(ops.stopped, "must not stop the scheduler row on retry");
        var rescheduledTo = ops.reschedules.getFirst();
        // First retry position is index 0 → 30 seconds.
        long deltaSecs = rescheduledTo.getEpochSecond() - beforeReschedule.getEpochSecond();
        assertTrue(deltaSecs >= 29 && deltaSecs <= 32,
                "first retry should reschedule ~30s out, got " + deltaSecs + "s");
        // Sanity: rescheduledTo is after beforeReschedule even with a 1s margin.
        assertTrue(rescheduledTo.isAfter(beforeReschedule));

        // Task row reflects the retry-pending state.
        JPA.em().clear();
        var afterFirstAttempt = Tx.run(() -> (Task) Task.findById(task.id));
        assertEquals(1, afterFirstAttempt.retryCount,
                "retryCount bumps to 1 after first transient failure");
        assertEquals(Task.Status.PENDING, afterFirstAttempt.status,
                "row stays PENDING for the retry — only permanent fails go to FAILED");
        assertNotNull(afterFirstAttempt.lastError);
        assertTrue(afterFirstAttempt.lastError.contains("read timed out")
                || afterFirstAttempt.lastError.contains("SocketTimeout"),
                "lastError captures the exception signal, got: " + afterFirstAttempt.lastError);

        // TASK_FAILED must NOT have fired — transient retries emit WARN
        // under the "task" category instead.
        EventLogger.flush();
        assertTrue(loadEventsByCategory("TASK_FAILED").isEmpty(),
                "TASK_FAILED is the permanent-failure lifecycle bookmark; "
                + "must not fire on a transient retry");
        // Also no TASK_STARTED yet — the first attempt never reached
        // TaskExecutor.runTask (the handler short-circuits would only
        // be invoked AFTER runTask runs; in our scenario db-scheduler
        // didn't even open the lambda body before the transient error).
        assertTrue(loadEventsByCategory("TASK_STARTED").isEmpty(),
                "first-attempt did not reach TaskExecutor — no TASK_STARTED");

        // === Second attempt: LLM is now up, drive the production lambda ===

        // The first-attempt failure handler ran on this thread, so its
        // Tx.run-wrapped writes (retryCount, lastError) nested inside
        // the JUnit outer Tx and still hold row locks on the Task row
        // from the VT thread's perspective. Commit + begin to flush
        // and release the locks before driving the VT thread's
        // TaskExecutor.runTask, which now writes Task.status =
        // COMPLETED on the success path.
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        startLlmServer(simpleResponse("Task completed on retry."));
        configureProvider();

        var dbTask = TaskExecutionHandler.buildTask();
        var ctx = new ExecutionContext(null, execution, null, null);

        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                dbTask.execute(taskInstance, ctx);
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "second-attempt execute should complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();

        JPA.em().clear();

        // === Assert second-attempt outcomes ===

        var runs = listRunsForTask(task.id);
        assertEquals(1, runs.size(),
                "only the SECOND attempt opens a TaskRun — first never reached TaskExecutor");
        var run = runs.getFirst();
        assertEquals(TaskRun.Status.COMPLETED, run.status,
                "second-attempt fire should close COMPLETED");
        assertEquals("Task completed on retry.", run.outputSummary);

        // Lifecycle: both STARTED and COMPLETED for the successful run.
        EventLogger.flush();
        var startedEvents = loadEventsByCategory("TASK_STARTED");
        var completedEvents = loadEventsByCategory("TASK_COMPLETED");
        assertEquals(1, startedEvents.size(),
                "TASK_STARTED for the successful second attempt");
        assertEquals(1, completedEvents.size(),
                "TASK_COMPLETED for the successful second attempt");
        // Still no TASK_FAILED — the retry succeeded.
        assertTrue(loadEventsByCategory("TASK_FAILED").isEmpty(),
                "retry succeeded; TASK_FAILED must not appear");

        // Task row: retryCount preserved (not reset on success), status
        // unchanged from PENDING because the fire body succeeded and
        // the Task row itself is unmanaged by the success path
        // (TaskRun carries the COMPLETED state; Task.status is the
        // operator-visible "is this Task active?" flag).
        var afterSecond = Tx.run(() -> (Task) Task.findById(task.id));
        assertEquals(1, afterSecond.retryCount,
                "retryCount preserved across the successful retry — "
                + "operators can see 'fire #2 of N attempts' in the row");
    }

    @Test
    void permanentFailureMarksFailedAndEmitsLifecycleEvent() {
        var agent = createAgent("permanent-fail-agent", "test-provider", "test-model");
        var task = persistTask(agent, "Will fail permanently",
                "Some bad request.", Task.Type.IMMEDIATE);
        // Open a TaskRun row first — the lifecycle event helper looks
        // it up to populate the failed-event details.
        var run = Tx.run(() -> {
            var r = new TaskRun();
            r.task = (Task) Task.findById(task.id);
            r.startedAt = Instant.now();
            r.status = TaskRun.Status.RUNNING;
            r.save();
            return r;
        });

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var taskInstance = new TaskInstance<Void>(
                TaskExecutionHandler.TASK_NAME, task.id.toString());
        var execution = new Execution(Instant.now(), taskInstance);
        var permanent = new RuntimeException("HTTP 401 Unauthorized");
        var executionComplete = ExecutionComplete.failure(
                execution, Instant.now().minusSeconds(1), Instant.now(), permanent);
        var ops = new RecordingExecutionOperations(execution);

        new JClawFailureHandler().onFailure(executionComplete, ops);

        // Stop path, not reschedule.
        assertEquals(0, ops.reschedules.size(),
                "permanent error → no reschedule");
        assertTrue(ops.stopped, "permanent error must stop the scheduler row");

        // Task row flipped to FAILED with the error message.
        JPA.em().clear();
        var fresh = Tx.run(() -> (Task) Task.findById(task.id));
        assertEquals(Task.Status.FAILED, fresh.status);
        assertEquals("HTTP 401 Unauthorized", fresh.lastError);

        // TASK_FAILED lifecycle bookmark fired.
        EventLogger.flush();
        var failedEvents = loadEventsByCategory("TASK_FAILED");
        assertEquals(1, failedEvents.size(),
                "TASK_FAILED bookmark fires on permanent failure");
        var failed = failedEvents.getFirst();
        assertEquals("ERROR", failed.level);
        assertTrue(failed.message.contains("Will fail permanently"));
        assertTrue(failed.details.contains("\"classification\":\"permanent error\""),
                "TASK_FAILED details include the classification, got: "
                        + failed.details);
        assertTrue(failed.details.contains("\"error_message\":\"HTTP 401 Unauthorized\""),
                "TASK_FAILED details include the raw error_message, got: "
                        + failed.details);
        assertTrue(failed.details.contains("\"run_id\":" + run.id),
                "TASK_FAILED links to the open TaskRun via run_id");
    }

    // === Helpers ===

    /**
     * Subclasses {@link ExecutionOperations} so we can record the
     * reschedule/stop calls JClawFailureHandler makes without
     * standing up a real TaskRepository. The base class has virtual
     * methods (not final), so direct override is the cleanest stub
     * — Proxy doesn't work on concrete classes.
     */
    private static class RecordingExecutionOperations extends ExecutionOperations<Void> {
        final List<Instant> reschedules = new ArrayList<>();
        boolean stopped = false;

        RecordingExecutionOperations(Execution execution) {
            // TaskRepository + SchedulerListeners are null because we
            // override every method that would dereference them.
            super(null, null, execution);
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void remove() {
            stopped = true;
        }

        @Override
        public void reschedule(ExecutionComplete completed, Instant nextExecutionTime) {
            reschedules.add(nextExecutionTime);
        }

        @Override
        public void removeAndScheduleNew(SchedulableInstance<?> schedulableInstance) {
            // Not used by JClawFailureHandler — defensive override so
            // a future change doesn't silently NPE through the base
            // class's TaskRepository field.
            reschedules.add(Instant.now());
        }
    }

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

    private List<EventLog> loadEventsByCategory(String category) {
        return Tx.run(() -> {
            var raw = EventLog.find("category = ?1", category).fetch();
            var typed = new ArrayList<EventLog>(raw.size());
            for (var r : raw) typed.add((EventLog) r);
            return typed;
        });
    }
}
