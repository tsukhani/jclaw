import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import jobs.BootConsistencyCheck;
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
import services.TaskExecutionHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-310: targeted coverage for {@link TaskExecutionHandler} call
 * paths that the existing TaskFireFunctionalTest does not exercise.
 *
 * <p>Drives {@link TaskExecutionHandler#buildTask}'s lambda directly
 * with a stub {@link SchedulerClient} so we can assert the
 * self-rescheduling shape for CRON/INTERVAL Tasks without standing
 * up a live db-scheduler. The same dynamic-Proxy stub pattern used
 * by TaskSchedulingServiceTest / BootConsistencyCheckTest applies
 * here — we capture {@code schedule()} calls and surface a
 * configurable scheduled-rows set for the boot-consistency sweep.
 *
 * <p>What this file pins:
 * <ul>
 *   <li>IMMEDIATE one-shot fire produces exactly one TaskRun and
 *       returns {@link CompletionHandler.OnCompleteRemove}.</li>
 *   <li>SCHEDULED one-shot at a fixed future timestamp produces one
 *       TaskRun and removes its row.</li>
 *   <li>CRON fire self-rescheduling: a successful fire invokes
 *       {@code SchedulerClient.schedule} with the next cron tick.</li>
 *   <li>CRON fire cancelled-skip: a Task whose status flipped to
 *       CANCELLED is skipped without producing a TaskRun.</li>
 *   <li>INTERVAL drift: completion handler schedules the next fire at
 *       roughly {@code completionTime + intervalSeconds}.</li>
 *   <li>Dead-execution recovery: {@link BootConsistencyCheck#sweep}
 *       reschedules an orphan PENDING Task whose previous
 *       scheduled_tasks row vanished.</li>
 *   <li>Undecodable / missing Task ids: handler logs warn and exits
 *       cleanly without producing a TaskRun.</li>
 * </ul>
 */
class TaskExecutionHandlerTest extends UnitTest {

    private com.sun.net.httpserver.HttpServer llmServer;
    private int port;
    private RecordingSchedulerStub stub;

    @BeforeEach
    void setup() throws Exception {
        Thread.sleep(200);
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
        stub = new RecordingSchedulerStub();
        // Wire the static schedulerClient field so the CRON / INTERVAL
        // self-reschedule paths can observe the stub instead of the
        // (null) bootstrap reference.
        TaskExecutionHandler.setSchedulerClient(stub.proxy());
        services.TaskSchedulingServiceTestHooks.setSchedulerClient(stub.proxy());
    }

    @AfterEach
    void teardown() {
        if (llmServer != null) {
            llmServer.stop(0);
            llmServer = null;
        }
        services.TaskSchedulingServiceTestHooks.reset();
        // Reset the static handoff so the next test starts clean.
        TaskExecutionHandler.setSchedulerClient(null);
    }

    // === IMMEDIATE fire produces one TaskRun, OnCompleteRemove ===

    @Test
    void immediateFireProducesOneRunAndRemovesScheduledRow() throws Exception {
        startLlmServer(simpleResponse("ok"));
        configureProvider();

        var agent = createAgent("immediate-handler-agent");
        var task = persistTask(agent, "Immediate", "Do it.",
                Task.Type.IMMEDIATE, null, null, null);

        commitAndReopen();

        var handler = driveFireCaptureHandler(task.id);
        JPA.em().clear();

        // Exactly one TaskRun, in COMPLETED.
        var runs = listRunsForTask(task.id);
        assertEquals(1, runs.size(), "IMMEDIATE fire produces exactly one TaskRun");
        assertEquals(TaskRun.Status.COMPLETED, runs.getFirst().status);

        // Returned CompletionHandler removes the row (OnCompleteRemove).
        assertTrue(handler instanceof CompletionHandler.OnCompleteRemove,
                "IMMEDIATE fire returns OnCompleteRemove; got: " + handler);
    }

    // === SCHEDULED fire at fixed future timestamp ===

    @Test
    void scheduledFireProducesOneRun() throws Exception {
        startLlmServer(simpleResponse("scheduled-done"));
        configureProvider();

        var agent = createAgent("scheduled-handler-agent");
        var future = Instant.now().plusSeconds(3600);
        var task = persistTask(agent, "Scheduled", "Do it later.",
                Task.Type.SCHEDULED, future, null, null);

        commitAndReopen();

        var handler = driveFireCaptureHandler(task.id);
        JPA.em().clear();

        var runs = listRunsForTask(task.id);
        assertEquals(1, runs.size(), "SCHEDULED fire produces exactly one TaskRun");
        assertEquals(TaskRun.Status.COMPLETED, runs.getFirst().status);
        // One-shot → OnCompleteRemove
        assertTrue(handler instanceof CompletionHandler.OnCompleteRemove,
                "SCHEDULED fire returns OnCompleteRemove");
    }

    // === CRON self-rescheduling ===

    @Test
    void cronFireSchedulesNextTickViaCompletionHandler() throws Exception {
        startLlmServer(simpleResponse("cron-done"));
        configureProvider();

        var agent = createAgent("cron-handler-agent");
        // Top-of-every-minute → next tick within ~60s.
        var task = persistTask(agent, "CronTask", "Tick.",
                Task.Type.CRON, null, "0 * * * * *", null);

        commitAndReopen();

        var handler = driveFireCaptureHandler(task.id);
        JPA.em().clear();

        // The fire body ran and produced one TaskRun.
        var runs = listRunsForTask(task.id);
        assertEquals(1, runs.size(), "CRON fire produces a TaskRun");

        // The returned handler is NOT OnCompleteRemove — it's the
        // CRON next-tick lambda.
        assertFalse(handler instanceof CompletionHandler.OnCompleteRemove,
                "CRON fire returns custom CompletionHandler, not OnCompleteRemove");

        // Drive the completion handler to trigger the self-reschedule.
        var ops = new RecordingExecutionOperations(
                new Execution(Instant.now(), instance(task.id)));
        var complete = ExecutionComplete.success(
                ops.execution(), Instant.now().minusSeconds(1), Instant.now());
        var before = Instant.now();
        handler.complete(complete, ops);
        var after = Instant.now();

        // stop-then-schedule sequence — stop removes the current row,
        // schedule inserts the next-tick row.
        assertTrue(ops.stopped, "CRON completion handler must stop() current row");
        assertEquals(1, stub.schedules.size(),
                "CRON completion handler must schedule next tick via SchedulerClient");
        var next = stub.schedules.getFirst();
        assertEquals(task.id.toString(), next.instance.getId(),
                "next tick keeps the same task_instance id");
        // Top-of-minute cron → next tick is within the next 60 seconds.
        long deltaSeconds = next.when.getEpochSecond() - after.getEpochSecond();
        assertTrue(deltaSeconds >= 0 && deltaSeconds <= 60,
                "next CRON tick should fall within the next minute; got " + deltaSeconds + "s");
        assertTrue(next.when.isAfter(before.minusSeconds(1)),
                "next CRON tick should be in the future relative to the call site");
    }

    // === CRON cancelled-skip ===

    @Test
    void cancelledCronTaskIsSkippedWithoutOpeningRun() throws Exception {
        var agent = createAgent("cron-cancelled-agent");
        var task = persistTask(agent, "Cancelled cron", "Should skip.",
                Task.Type.CRON, null, "0 * * * * *", null);
        task.status = Task.Status.CANCELLED;
        task.save();

        commitAndReopen();

        var handler = driveFireCaptureHandler(task.id);
        JPA.em().clear();

        // CANCELLED skip: no TaskRun.
        var runs = listRunsForTask(task.id);
        assertTrue(runs.isEmpty(),
                "CANCELLED CRON Task must not open a TaskRun");
        // The handler is OnCompleteRemove (defaultCompletion path) — no
        // self-reschedule because the cancelled-skip falls through to
        // defaultCompletion() per the handler body.
        assertTrue(handler instanceof CompletionHandler.OnCompleteRemove,
                "cancelled-skip returns OnCompleteRemove; got: " + handler);
        // No SchedulerClient calls from the completion path either.
        assertTrue(stub.schedules.isEmpty(),
                "cancelled-skip must not schedule a next tick");
    }

    // === INTERVAL self-rescheduling at completion-time + intervalSeconds ===

    @Test
    void intervalCompletionHandlerSchedulesNextFireAtIntervalOffset() throws Exception {
        startLlmServer(simpleResponse("ok"));
        configureProvider();

        var agent = createAgent("interval-handler-agent");
        long intervalSecs = 1800L;  // 30 minutes
        var task = persistTask(agent, "IntervalTask", "Tick.",
                Task.Type.INTERVAL, null, null, intervalSecs);

        commitAndReopen();

        var handler = driveFireCaptureHandler(task.id);
        JPA.em().clear();

        // The returned handler is the interval next-fire lambda, not OnCompleteRemove.
        assertFalse(handler instanceof CompletionHandler.OnCompleteRemove,
                "INTERVAL fire returns custom CompletionHandler");

        var ops = new RecordingExecutionOperations(
                new Execution(Instant.now(), instance(task.id)));
        var complete = ExecutionComplete.success(
                ops.execution(), Instant.now().minusSeconds(1), Instant.now());
        var beforeComplete = Instant.now();
        handler.complete(complete, ops);
        var afterComplete = Instant.now();

        assertTrue(ops.stopped, "INTERVAL completion handler must stop() the current row");
        assertEquals(1, stub.schedules.size(),
                "INTERVAL completion handler must schedule next fire");
        var next = stub.schedules.getFirst();
        assertEquals(task.id.toString(), next.instance.getId());

        // Drift-window AC: next fire should land at
        // ~completionTime + intervalSeconds.
        long deltaSeconds = next.when.getEpochSecond() - beforeComplete.getEpochSecond();
        assertTrue(deltaSeconds >= intervalSecs - 1 && deltaSeconds <= intervalSecs + 2,
                "next INTERVAL fire should be ~" + intervalSecs + "s after completion; got "
                        + deltaSeconds + "s");
        // And it should be strictly after the completion call site.
        assertTrue(next.when.isAfter(afterComplete),
                "next INTERVAL fire must be in the future relative to the completion");
    }

    // === Missing-Task / undecodable id: log + exit cleanly ===

    @Test
    void undecodableInstanceIdLogsWarnAndExitsCleanly() throws Exception {
        commitAndReopen();
        var dbTask = TaskExecutionHandler.buildTask();
        var instance = new TaskInstance<Void>(TaskExecutionHandler.TASK_NAME, "not-a-number");
        var ctx = new ExecutionContext(null,
                new Execution(Instant.now(), instance), null, null);

        var errorRef = new AtomicReference<Throwable>();
        var resultRef = new AtomicReference<CompletionHandler<Void>>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                resultRef.set(dbTask.execute(instance, ctx));
            } catch (Throwable ex) {
                errorRef.set(ex);
            }
        });
        t.join(10_000);
        if (errorRef.get() != null) throw new RuntimeException(errorRef.get());
        assertNotNull(resultRef.get(),
                "undecodable id must return a CompletionHandler, not throw");
        assertTrue(resultRef.get() instanceof CompletionHandler.OnCompleteRemove,
                "undecodable id falls through to defaultCompletion()");

        EventLogger.flush();
        var warnings = loadEventsByCategory("task");
        assertTrue(warnings.stream().anyMatch(e ->
                e.message != null && e.message.contains("undecodable task_instance")),
                "undecodable id must log the warn");
    }

    @Test
    void missingTaskIdLogsWarnAndExitsCleanly() throws Exception {
        commitAndReopen();
        var dbTask = TaskExecutionHandler.buildTask();
        var instance = new TaskInstance<Void>(TaskExecutionHandler.TASK_NAME, "999999999");
        var ctx = new ExecutionContext(null,
                new Execution(Instant.now(), instance), null, null);

        var errorRef = new AtomicReference<Throwable>();
        var resultRef = new AtomicReference<CompletionHandler<Void>>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                resultRef.set(dbTask.execute(instance, ctx));
            } catch (Throwable ex) {
                errorRef.set(ex);
            }
        });
        t.join(10_000);
        if (errorRef.get() != null) throw new RuntimeException(errorRef.get());
        assertNotNull(resultRef.get(),
                "missing Task must return a CompletionHandler, not throw");
        assertTrue(resultRef.get() instanceof CompletionHandler.OnCompleteRemove,
                "missing Task id falls through to defaultCompletion()");

        EventLogger.flush();
        var warnings = loadEventsByCategory("task");
        assertTrue(warnings.stream().anyMatch(e ->
                e.message != null
                && e.message.contains("scheduled fire arrived for missing Task id")),
                "missing-Task fire must log the warn");
    }

    // === CRON self-reschedule swallows malformed next-fire computation ===

    @Test
    void cronCompletionHandlerSwallowsSchedulerExceptionsCleanly() throws Exception {
        startLlmServer(simpleResponse("ok"));
        configureProvider();

        var agent = createAgent("cron-throws-agent");
        var task = persistTask(agent, "ThrowyCron", "Tick.",
                Task.Type.CRON, null, "0 * * * * *", null);

        commitAndReopen();

        var handler = driveFireCaptureHandler(task.id);
        JPA.em().clear();

        // Make the stub throw on schedule() — the completion handler
        // must swallow the exception and not propagate (so a transient
        // scheduler outage doesn't blow up the executor thread).
        stub.throwOnSchedule = true;

        var ops = new RecordingExecutionOperations(
                new Execution(Instant.now(), instance(task.id)));
        var complete = ExecutionComplete.success(
                ops.execution(), Instant.now().minusSeconds(1), Instant.now());

        // Should NOT throw — handler swallows and logs.
        try {
            handler.complete(complete, ops);
        } catch (Exception ex) {
            fail("CRON completion handler must swallow scheduler exceptions; threw " + ex);
        }
        assertTrue(ops.stopped,
                "current row must still be stopped even when next-schedule fails");
    }

    // === Dead-execution recovery via BootConsistencyCheck ===

    @Test
    void deadExecutionRecoveryReregistersOrphanPending() throws Exception {
        // Simulate the "JVM crashed mid-fire, scheduled_tasks row gone,
        // Task row left PENDING" scenario. BootConsistencyCheck.sweep
        // should pick this up and register a fresh row.
        var agent = createAgent("dead-recovery-agent");
        var orphan = persistTask(agent, "Dead-row task", "Recover.",
                Task.Type.IMMEDIATE, null, null, null);
        // Stub has no scheduled rows — orphan from scheduler's
        // perspective.

        commitAndReopen();

        int registered = BootConsistencyCheck.sweep(stub.proxy());

        assertEquals(1, registered,
                "orphan PENDING Task should be re-registered by the sweep");
        assertEquals(1, stub.schedules.size(),
                "sweep should call SchedulerClient.schedule for the orphan");
        assertEquals(orphan.id.toString(), stub.schedules.getFirst().instance.getId(),
                "scheduled task_instance must carry the orphan's id");
    }

    // === Helpers ===

    private Agent createAgent(String name) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.save();
        return a;
    }

    private Task persistTask(Agent agent, String name, String description,
                              Task.Type type, Instant scheduledAt,
                              String cronExpression, Long intervalSeconds) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.description = description;
        t.type = type;
        t.scheduledAt = scheduledAt;
        t.cronExpression = cronExpression;
        t.intervalSeconds = intervalSeconds;
        t.status = Task.Status.PENDING;
        t.nextRunAt = scheduledAt != null ? scheduledAt : Instant.now();
        t.createdAt = Instant.now();
        t.updatedAt = Instant.now();
        t.save();
        return t;
    }

    private static TaskInstance<Void> instance(Long taskId) {
        return new TaskInstance<>(TaskExecutionHandler.TASK_NAME, taskId.toString());
    }

    /**
     * Drive one db-scheduler lambda invocation on a virtual thread,
     * return the {@link CompletionHandler} the lambda produced. Mirrors
     * the driver in TaskFireFunctionalTest but exposes the
     * CompletionHandler so callers can probe the self-reschedule path.
     */
    private CompletionHandler<Void> driveFireCaptureHandler(Long taskId) throws Exception {
        var dbTask = TaskExecutionHandler.buildTask();
        var inst = instance(taskId);
        var ctx = new ExecutionContext(null,
                new Execution(Instant.now(), inst), null, null);

        var resultRef = new AtomicReference<CompletionHandler<Void>>();
        var errorRef = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                resultRef.set(dbTask.execute(inst, ctx));
            } catch (Throwable ex) {
                errorRef.set(ex);
            }
        });
        t.join(30_000);
        if (t.isAlive()) throw new AssertionError("execute did not finish within 30s");
        if (errorRef.get() != null) throw new RuntimeException(errorRef.get());
        return resultRef.get();
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

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private List<TaskRun> listRunsForTask(Long taskId) {
        return services.Tx.run(() -> {
            var raw = TaskRun.find("task.id = ?1 ORDER BY startedAt ASC", taskId).fetch();
            var typed = new ArrayList<TaskRun>(raw.size());
            for (var r : raw) typed.add((TaskRun) r);
            return typed;
        });
    }

    private List<EventLog> loadEventsByCategory(String category) {
        return services.Tx.run(() -> {
            var raw = EventLog.find("category = ?1", category).fetch();
            var typed = new ArrayList<EventLog>(raw.size());
            for (var r : raw) typed.add((EventLog) r);
            return typed;
        });
    }

    // === Stubs ===

    /**
     * Subclasses {@link ExecutionOperations} so completion handlers can
     * record their stop/schedule decisions without touching a real
     * TaskRepository. Mirrors TaskRetryRoundtripTest's helper.
     */
    private static class RecordingExecutionOperations extends ExecutionOperations<Void> {
        boolean stopped = false;
        final Execution execution;

        RecordingExecutionOperations(Execution execution) {
            super(null, null, execution);
            this.execution = execution;
        }

        Execution execution() { return execution; }

        @Override public void stop() { stopped = true; }
        @Override public void remove() { stopped = true; }
        @Override public void reschedule(ExecutionComplete c, Instant n) { }
        @Override public void removeAndScheduleNew(SchedulableInstance<?> i) { }
    }

    /**
     * Dynamic-Proxy SchedulerClient stub. Captures schedule() calls for
     * the self-reschedule assertions and surfaces a configurable set of
     * "already-scheduled" ids for the BootConsistencyCheck sweep.
     */
    static class RecordingSchedulerStub {
        static class ScheduleCall {
            final TaskInstance<?> instance;
            final Instant when;
            ScheduleCall(TaskInstance<?> i, Instant w) { instance = i; when = w; }
        }
        final List<ScheduleCall> schedules = new ArrayList<>();
        final List<TaskInstanceId> cancels = new ArrayList<>();
        final List<String> scheduledIds = new ArrayList<>();
        boolean throwOnSchedule = false;

        SchedulerClient proxy() {
            return (SchedulerClient) Proxy.newProxyInstance(
                    SchedulerClient.class.getClassLoader(),
                    new Class<?>[] { SchedulerClient.class },
                    this::dispatch);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object dispatch(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("schedule".equals(name) && args != null && args.length == 2
                    && args[0] instanceof TaskInstance<?> inst
                    && args[1] instanceof Instant when) {
                if (throwOnSchedule) throw new RuntimeException("Stub: schedule failed");
                schedules.add(new ScheduleCall(inst, when));
                return null;
            }
            if ("cancel".equals(name) && args != null && args.length == 1
                    && args[0] instanceof TaskInstanceId id) {
                cancels.add(id);
                return null;
            }
            if ("getScheduledExecutionsForTask".equals(name)
                    && args != null && args.length >= 1
                    && args[0] instanceof String taskName) {
                var out = new ArrayList<com.github.kagkarlsson.scheduler.ScheduledExecution<Object>>();
                for (var id : scheduledIds) {
                    var ti = new TaskInstance(taskName, id);
                    var exec = new Execution(Instant.now(), ti);
                    out.add(new com.github.kagkarlsson.scheduler.ScheduledExecution<>(
                            Object.class, exec));
                }
                return out;
            }
            Class<?> r = method.getReturnType();
            if (r == boolean.class || r == Boolean.class) return false;
            if (r == int.class || r == Integer.class) return 0;
            if (r == long.class || r == Long.class) return 0L;
            if (r == List.class) return List.of();
            if (r == java.util.Optional.class) return java.util.Optional.empty();
            return null;
        }
    }
}
