import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import models.Agent;
import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.TaskExecutionHandler;
import services.TaskSchedulingService;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link TaskSchedulingService}'s call shape, using a
 * dynamic-proxy {@link SchedulerClient} stub injected via the
 * package-private test-supplier hook on the service.
 *
 * <p>SchedulerClient has 30+ methods most of which the service
 * doesn't call; a {@link Proxy}-based stub captures only the calls
 * we care about (schedule / cancel / reschedule) and returns sane
 * defaults for the rest, instead of forcing a brittle full
 * interface implementation.
 *
 * <p>The autotest classloader / lifecycle makes the live
 * {@code DbSchedulerBootstrapJob.scheduler()} observably null at
 * test method time even though the bootstrap log shows the
 * scheduler started; a stub avoids that whole problem and gives us
 * a tight contract check: did the service call the right method
 * with the right arguments?
 *
 * <p>The live-scheduler round-trip ({@code register} → row in
 * {@code scheduled_tasks} → fire → row removed) is exercised
 * end-to-end via the running prod-mode app, not in unit-test mode.
 */
class TaskSchedulingServiceTest extends UnitTest {

    private Agent agent;
    private RecordingSchedulerStub stub;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = persistAgent();
        stub = new RecordingSchedulerStub();
        services.TaskSchedulingServiceTestHooks.setSchedulerClient(stub.proxy());
    }

    @AfterEach
    void tearDown() {
        services.TaskSchedulingServiceTestHooks.reset();
    }

    @Test
    void registerSchedulesAScheduledTaskAtItsScheduledTime() {
        var future = Instant.now().plus(Duration.ofHours(1));
        var task = persistTask(Task.Type.SCHEDULED, future, null);

        TaskSchedulingService.register(task);

        assertEquals(1, stub.schedules.size(), "expected one schedule call");
        var s = stub.schedules.getFirst();
        assertEquals(TaskExecutionHandler.TASK_NAME, s.instance.getTaskName());
        assertEquals(task.id.toString(), s.instance.getId());
        assertEquals(future, s.when);
    }

    @Test
    void registerSchedulesAnImmediateTaskAtRoughlyNow() {
        var task = persistTask(Task.Type.IMMEDIATE, null, null);

        var beforeCall = Instant.now();
        TaskSchedulingService.register(task);
        var afterCall = Instant.now();

        assertEquals(1, stub.schedules.size());
        var when = stub.schedules.getFirst().when;
        assertFalse(when.isBefore(beforeCall),
                "IMMEDIATE schedule should not be before the call site");
        assertFalse(when.isAfter(afterCall.plusSeconds(1)),
                "IMMEDIATE schedule should be ~now");
    }

    @Test
    void registerSkipsTerminalStatusTask() {
        var task = persistTask(Task.Type.SCHEDULED, Instant.now().plus(Duration.ofHours(1)), null);
        task.status = Task.Status.CANCELLED;
        task.save();

        TaskSchedulingService.register(task);
        assertTrue(stub.schedules.isEmpty(),
                "register() on a CANCELLED Task should be a no-op");
    }

    @Test
    void registerSkipsCronWithBlankExpression() {
        var task = persistTask(Task.Type.CRON, null, "");

        TaskSchedulingService.register(task);
        assertTrue(stub.schedules.isEmpty(),
                "CRON Task with blank cronExpression should not register");
    }

    @Test
    void cancelRemovesTheRow() {
        TaskSchedulingService.cancel(42L);

        assertEquals(1, stub.cancels.size());
        var id = stub.cancels.getFirst();
        assertEquals(TaskExecutionHandler.TASK_NAME, id.getTaskName());
        assertEquals("42", id.getId());
    }

    @Test
    void cancelIsIdempotentWhenNoRowExists() {
        stub.throwNotFoundOnCancel = true;
        TaskSchedulingService.cancel(99L);  // must not throw
        assertEquals(1, stub.cancels.size());
    }

    @Test
    void cancelSwallowsCurrentlyExecutingRow() {
        stub.throwCurrentlyExecutingOnCancel = true;
        TaskSchedulingService.cancel(77L);  // must not throw — row locked mid-fire
        assertEquals(1, stub.cancels.size());
    }

    @Test
    void updateCancelsThenRegisters() {
        var future = Instant.now().plus(Duration.ofHours(2));
        var task = persistTask(Task.Type.SCHEDULED, future, null);

        TaskSchedulingService.update(task);

        assertEquals(1, stub.cancels.size(), "update() must cancel first");
        assertEquals(1, stub.schedules.size(), "update() must then schedule");
        assertEquals(task.id.toString(), stub.cancels.getFirst().getId());
        assertEquals(task.id.toString(), stub.schedules.getFirst().instance.getId());
    }

    @Test
    void runNowReschedulesExistingRow() {
        stub.rescheduleReturns = true;

        var beforeCall = Instant.now();
        TaskSchedulingService.runNow(123L);

        assertEquals(1, stub.reschedules.size());
        var r = stub.reschedules.getFirst();
        assertEquals("123", r.instanceId.getId());
        assertFalse(r.when.isBefore(beforeCall), "runNow should set execution_time to ~now");
        assertTrue(stub.schedules.isEmpty(), "no fresh schedule when reschedule succeeded");
    }

    @Test
    void runNowFallsThroughToRegisterWhenNoRowExists() {
        stub.rescheduleReturns = false;
        var task = persistTask(Task.Type.IMMEDIATE, null, null);

        TaskSchedulingService.runNow(task.id);

        assertEquals(1, stub.reschedules.size(), "reschedule attempted first");
        assertEquals(1, stub.schedules.size(), "schedule fired as fallback");
        assertEquals(task.id.toString(), stub.schedules.getFirst().instance.getId());
    }

    @Test
    void runNowOnUnknownTaskIdIsANoopBeyondReschedule() {
        stub.rescheduleReturns = false;
        TaskSchedulingService.runNow(999_999L);

        assertEquals(1, stub.reschedules.size());
        assertTrue(stub.schedules.isEmpty(), "no fallback schedule when Task missing");
    }

    // === Helpers ===

    private Agent persistAgent() {
        var a = new Agent();
        a.name = "task-scheduling-test-agent";
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.save();
        return a;
    }

    private Task persistTask(Task.Type type, Instant scheduledAt, String cronExpression) {
        var t = new Task();
        t.agent = agent;
        t.name = "sched-test-" + System.nanoTime();
        t.description = "Test task for scheduling service";
        t.type = type;
        t.scheduledAt = scheduledAt;
        t.cronExpression = cronExpression;
        t.status = Task.Status.PENDING;
        t.createdAt = Instant.now();
        t.updatedAt = Instant.now();
        t.save();
        return t;
    }

    /**
     * Recording stub backed by a dynamic Proxy. Captures three method
     * calls — {@code schedule(TaskInstance, Instant)},
     * {@code cancel(TaskInstanceId)},
     * {@code reschedule(TaskInstanceId, Instant)} — that
     * TaskSchedulingService actually invokes. All other
     * SchedulerClient methods return sane defaults (null/false/0/
     * empty list) and never throw, so the service can call this
     * without worrying about which underlying method is hit.
     */
    static class RecordingSchedulerStub {
        static class ScheduleCall {
            final TaskInstance<?> instance;
            final Instant when;
            ScheduleCall(TaskInstance<?> instance, Instant when) {
                this.instance = instance;
                this.when = when;
            }
        }
        static class RescheduleCall {
            final TaskInstanceId instanceId;
            final Instant when;
            RescheduleCall(TaskInstanceId id, Instant when) {
                this.instanceId = id;
                this.when = when;
            }
        }

        final List<ScheduleCall> schedules = new ArrayList<>();
        final List<TaskInstanceId> cancels = new ArrayList<>();
        final List<RescheduleCall> reschedules = new ArrayList<>();
        boolean throwNotFoundOnCancel = false;
        boolean throwCurrentlyExecutingOnCancel = false;
        boolean rescheduleReturns = true;

        SchedulerClient proxy() {
            return (SchedulerClient) Proxy.newProxyInstance(
                    SchedulerClient.class.getClassLoader(),
                    new Class<?>[] { SchedulerClient.class },
                    this::dispatch);
        }

        private Object dispatch(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("schedule".equals(name) && args != null && args.length == 2
                    && args[0] instanceof TaskInstance<?> inst
                    && args[1] instanceof Instant when) {
                schedules.add(new ScheduleCall(inst, when));
                return null;
            }
            if ("cancel".equals(name) && args != null && args.length == 1
                    && args[0] instanceof TaskInstanceId id) {
                cancels.add(id);
                if (throwNotFoundOnCancel) {
                    throw new TaskInstanceNotFoundException(id.getTaskName(), id.getId());
                }
                if (throwCurrentlyExecutingOnCancel) {
                    throw new TaskInstanceCurrentlyExecutingException(id.getTaskName(), id.getId());
                }
                return null;
            }
            if ("reschedule".equals(name) && args != null && args.length == 2
                    && args[0] instanceof TaskInstanceId id
                    && args[1] instanceof Instant when) {
                reschedules.add(new RescheduleCall(id, when));
                return rescheduleReturns;
            }
            // Defaults for unhandled methods so the proxy never NPEs the caller.
            Class<?> ret = method.getReturnType();
            if (ret == boolean.class || ret == Boolean.class) return false;
            if (ret == int.class || ret == Integer.class) return 0;
            if (ret == long.class || ret == Long.class) return 0L;
            if (ret == List.class) return List.of();
            if (ret == java.util.Optional.class) return java.util.Optional.empty();
            return null;
        }
    }
}
