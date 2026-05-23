import com.github.kagkarlsson.scheduler.SchedulerClient;
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
import services.Tx;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Coverage for the JCLAW-21 entity refactor:
 * INTERVAL Type, intervalSeconds column, paused column.
 *
 * <ul>
 *   <li>{@code register(INTERVAL Task)} schedules first fire at
 *       roughly now and persists the right task_instance.</li>
 *   <li>{@code register(INTERVAL Task)} skips when
 *       {@code intervalSeconds} is null or non-positive.</li>
 *   <li>{@code pause(taskId)} flips {@link Task#paused} to true
 *       without touching scheduled_tasks; {@code resume} clears
 *       the flag.</li>
 *   <li>pause/resume are no-ops when the Task row is missing.</li>
 * </ul>
 *
 * <p>Tests use the same Proxy stub pattern as
 * {@code TaskSchedulingServiceTest} — the live db-scheduler isn't
 * needed to verify the service's call shape, and avoiding it
 * sidesteps the autotest-mode classloader quirk that makes
 * {@code DbSchedulerBootstrapJob.scheduler()} unreliable at
 * test-method time.
 */
class TaskIntervalAndPausedTest extends UnitTest {

    private Agent agent;
    private RecordingStub stub;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = persistAgent();
        stub = new RecordingStub();
        services.TaskSchedulingServiceTestHooks.setSchedulerClient(stub.proxy());
    }

    @AfterEach
    void tearDown() {
        services.TaskSchedulingServiceTestHooks.reset();
    }

    // === INTERVAL Type ===

    @Test
    void registerIntervalSchedulesFirstFireImmediately() {
        var task = persistInterval(60L);

        var before = Instant.now();
        TaskSchedulingService.register(task);
        var after = Instant.now();

        assertEquals(1, stub.schedules.size());
        var s = stub.schedules.getFirst();
        assertEquals(TaskExecutionHandler.TASK_NAME, s.instance.getTaskName());
        assertEquals(task.id.toString(), s.instance.getId());
        assertFalse(s.when.isBefore(before),
                "INTERVAL first fire should not be before the call site");
        assertFalse(s.when.isAfter(after.plusSeconds(1)),
                "INTERVAL first fire should be ~now, got " + s.when);
    }

    @Test
    void registerIntervalSkipsWhenIntervalSecondsIsNull() {
        var task = persistInterval(null);
        TaskSchedulingService.register(task);
        assertTrue(stub.schedules.isEmpty(),
                "INTERVAL Task with null intervalSeconds must not register");
    }

    @Test
    void registerIntervalSkipsWhenIntervalSecondsIsZeroOrNegative() {
        var zero = persistInterval(0L);
        TaskSchedulingService.register(zero);
        assertTrue(stub.schedules.isEmpty(), "intervalSeconds=0 skipped");

        var negative = persistInterval(-1L);
        TaskSchedulingService.register(negative);
        assertTrue(stub.schedules.isEmpty(), "intervalSeconds=-1 skipped");
    }

    // === paused ===

    @Test
    void pauseFlipsTheFlagWithoutTouchingTheScheduler() {
        var task = persistInterval(60L);
        TaskSchedulingService.register(task);
        int schedulesBefore = stub.schedules.size();
        int cancelsBefore = stub.cancels.size();

        TaskSchedulingService.pause(task.id);

        // Pause does not call schedule/cancel/reschedule — it's a
        // pure entity mutation. The CRON cadence (if any) keeps
        // ticking; the fire body is what skips.
        assertEquals(schedulesBefore, stub.schedules.size(),
                "pause should not call schedule()");
        assertEquals(cancelsBefore, stub.cancels.size(),
                "pause should not call cancel()");
        assertTrue(stub.reschedules.isEmpty(),
                "pause should not call reschedule()");

        var fresh = (Task) Tx.run(() -> Task.findById(task.id));
        assertTrue(fresh.paused, "paused flag should be true after pause()");
    }

    @Test
    void resumeFlipsTheFlagBack() {
        var task = persistInterval(60L);
        Tx.run(() -> {
            var t = (Task) Task.findById(task.id);
            t.paused = true;
            t.save();
            return null;
        });

        TaskSchedulingService.resume(task.id);

        var fresh = (Task) Tx.run(() -> Task.findById(task.id));
        assertFalse(fresh.paused, "paused flag should be false after resume()");
    }

    @Test
    void pauseOnUnknownTaskIdIsANoop() {
        // No exception, no scheduler calls.
        TaskSchedulingService.pause(999_999L);
        assertTrue(stub.schedules.isEmpty());
        assertTrue(stub.cancels.isEmpty());
    }

    @Test
    void resumeOnUnknownTaskIdIsANoop() {
        TaskSchedulingService.resume(999_999L);
        assertTrue(stub.schedules.isEmpty());
        assertTrue(stub.cancels.isEmpty());
    }

    // === Helpers ===

    private Agent persistAgent() {
        var a = new Agent();
        a.name = "interval-test-agent";
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.save();
        return a;
    }

    private Task persistInterval(Long intervalSeconds) {
        var t = new Task();
        t.agent = agent;
        t.name = "interval-test-" + System.nanoTime();
        t.description = "Test task";
        t.type = Task.Type.INTERVAL;
        t.intervalSeconds = intervalSeconds;
        t.status = Task.Status.PENDING;
        t.nextRunAt = Instant.now();
        t.createdAt = Instant.now();
        t.updatedAt = Instant.now();
        t.save();
        return t;
    }

    /** Same Proxy-based stub shape as TaskSchedulingServiceTest. */
    static class RecordingStub {
        static class ScheduleCall {
            final TaskInstance<?> instance;
            final Instant when;
            ScheduleCall(TaskInstance<?> i, Instant w) { instance = i; when = w; }
        }
        static class RescheduleCall {
            final TaskInstanceId instanceId;
            final Instant when;
            RescheduleCall(TaskInstanceId id, Instant w) { instanceId = id; when = w; }
        }
        final List<ScheduleCall> schedules = new ArrayList<>();
        final List<TaskInstanceId> cancels = new ArrayList<>();
        final List<RescheduleCall> reschedules = new ArrayList<>();

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
                return null;
            }
            if ("reschedule".equals(name) && args != null && args.length == 2
                    && args[0] instanceof TaskInstanceId id
                    && args[1] instanceof Instant when) {
                reschedules.add(new RescheduleCall(id, when));
                return true;
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
