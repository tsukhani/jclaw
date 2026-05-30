import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import jobs.BootConsistencyCheck;
import models.Agent;
import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.DB;
import play.test.Fixtures;
import play.test.UnitTest;
import services.TaskExecutionHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Functional test for {@link BootConsistencyCheck#sweep}. Drives
 * the sweep directly with a recording {@link SchedulerClient} stub
 * to verify:
 *
 * <ul>
 *   <li>Orphan PENDING Tasks (no scheduled_tasks row) get
 *       registered.</li>
 *   <li>PENDING Tasks that are already in scheduled_tasks do
 *       NOT get re-registered (no duplicate fires).</li>
 *   <li>Terminal-status Tasks (RUNNING / COMPLETED / FAILED /
 *       CANCELLED) are skipped — the sweep only acts on PENDING.</li>
 * </ul>
 *
 * <p>Stub pattern: dynamic Proxy with response shape configurable
 * per test (which "already-scheduled" rows to surface in
 * {@code fetchScheduledExecutionsForTask}). Same approach the
 * other Task-related tests use, generalised to also serve as the
 * "already-scheduled" data source.
 */
class BootConsistencyCheckTest extends UnitTest {

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
    void orphanPendingTasksGetRegistered() {
        // Two PENDING Tasks with no scheduled_tasks rows — both
        // should get registered by the sweep.
        var orphanA = persistTask("orphan-a", Task.Status.PENDING);
        var orphanB = persistTask("orphan-b", Task.Status.PENDING);

        int registered = BootConsistencyCheck.sweep(stub.proxy());

        assertEquals(2, registered, "both orphans should register");
        assertEquals(2, stub.schedules.size(),
                "TaskSchedulingService.register should have called schedule() twice");

        var scheduledIds = stub.schedules.stream()
                .map(s -> s.instance.getId()).toList();
        assertTrue(scheduledIds.contains(orphanA.id.toString()),
                "orphan-a registered, got: " + scheduledIds);
        assertTrue(scheduledIds.contains(orphanB.id.toString()),
                "orphan-b registered, got: " + scheduledIds);
    }

    @Test
    void alreadyScheduledTasksAreNotReRegistered() {
        var alreadyScheduled = persistTask("already-scheduled", Task.Status.PENDING);
        var orphan = persistTask("orphan", Task.Status.PENDING);

        // Tell the stub: the already-scheduled Task has a row.
        stub.scheduledIds.add(alreadyScheduled.id.toString());

        int registered = BootConsistencyCheck.sweep(stub.proxy());

        assertEquals(1, registered,
                "only the orphan should register; the already-scheduled is skipped");
        assertEquals(1, stub.schedules.size());
        assertEquals(orphan.id.toString(), stub.schedules.getFirst().instance.getId(),
                "schedule() should have run for the orphan, not the already-scheduled");
    }

    @Test
    void terminalStatusTasksAreNotRegistered() {
        // PENDING gets registered; everything else is skipped.
        var pending = persistTask("pending", Task.Status.PENDING);
        persistTask("running", Task.Status.RUNNING);
        persistTask("completed", Task.Status.COMPLETED);
        persistTask("failed", Task.Status.FAILED);
        persistTask("cancelled", Task.Status.CANCELLED);

        int registered = BootConsistencyCheck.sweep(stub.proxy());

        assertEquals(1, registered,
                "exactly the one PENDING Task should register; the four "
                + "non-PENDING ones are out of scope for the sweep");
        assertEquals(pending.id.toString(),
                stub.schedules.getFirst().instance.getId());
    }

    @Test
    void emptyPendingListIsANoop() {
        // No PENDING Tasks; sweep does nothing.
        persistTask("running", Task.Status.RUNNING);
        persistTask("completed", Task.Status.COMPLETED);

        int registered = BootConsistencyCheck.sweep(stub.proxy());

        assertEquals(0, registered);
        assertTrue(stub.schedules.isEmpty(),
                "sweep must not call schedule() when there are no PENDING tasks");
    }

    @Test
    void lostSweepRunsAtBoot() throws Exception {
        // JCLAW-258: a RUNNING Task whose scheduled_tasks row has a stale
        // heartbeat (the crash-then-restart case) must be reconciled to
        // LOST inside sweep() — operators shouldn't have to wait up to
        // 30 s for the periodic LostTaskScanJob's first tick.
        var stale = persistTask("crash-orphan", Task.Status.RUNNING);
        insertScheduledTaskRow(stale.id, Instant.now().minusSeconds(90));

        BootConsistencyCheck.sweep(stub.proxy());

        assertEquals(Task.Status.LOST,
                ((Task) Task.findById(stale.id)).status,
                "boot sweep must transition stale-heartbeat RUNNING to LOST");
    }

    @Test
    void everyPendingAlreadyScheduledIsANoop() {
        // The "boot after a normal shutdown" case — every PENDING
        // Task still has its scheduled_tasks row because the prior
        // process didn't crash. Sweep should observe this and do
        // nothing.
        var t1 = persistTask("t1", Task.Status.PENDING);
        var t2 = persistTask("t2", Task.Status.PENDING);
        stub.scheduledIds.add(t1.id.toString());
        stub.scheduledIds.add(t2.id.toString());

        int registered = BootConsistencyCheck.sweep(stub.proxy());

        assertEquals(0, registered);
        assertTrue(stub.schedules.isEmpty());
    }

    // === JCLAW-22: reArmOrphans (periodic-sweep half, no LOST detection) ===

    @Test
    void reArmOrphansRegistersOrphans() {
        var orphan = persistTask("orphan", Task.Status.PENDING);

        int registered = BootConsistencyCheck.reArmOrphans(stub.proxy());

        assertEquals(1, registered, "reArmOrphans should register the orphan");
        assertEquals(orphan.id.toString(), stub.schedules.getFirst().instance.getId());
    }

    @Test
    void reArmOrphansDoesNotRunLostDetection() throws Exception {
        // sweep() flips a stale-heartbeat RUNNING task to LOST; reArmOrphans
        // must NOT — LostTaskScanJob owns LOST detection, and the periodic
        // OrphanReArmJob calls only this half to avoid double-scanning.
        var stale = persistTask("crash-orphan", Task.Status.RUNNING);
        insertScheduledTaskRow(stale.id, Instant.now().minusSeconds(90));

        BootConsistencyCheck.reArmOrphans(stub.proxy());

        assertEquals(Task.Status.RUNNING,
                ((Task) Task.findById(stale.id)).status,
                "reArmOrphans must not reconcile RUNNING -> LOST");
    }

    // === Helpers ===

    private Agent persistAgent() {
        var a = new Agent();
        a.name = "boot-consistency-test-agent";
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.save();
        return a;
    }

    private void insertScheduledTaskRow(Long taskId, Instant lastHeartbeat) throws Exception {
        // DB.getDataSource() returns Hikari-pooled connections with autoCommit=false
        // (Hibernate-managed). Force autocommit on so the row lands before the
        // detector's separate-connection SELECT.
        try (var conn = DB.datasource.getConnection()) {
            conn.setAutoCommit(true);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO scheduled_tasks "
                    + "(task_name, task_instance, execution_time, picked, "
                    + " picked_by, last_heartbeat, version) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, TaskExecutionHandler.TASK_NAME);
                ps.setString(2, taskId.toString());
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.setBoolean(4, true);
                ps.setString(5, "test-scheduler");
                ps.setTimestamp(6, Timestamp.from(lastHeartbeat));
                ps.setLong(7, 1L);
                ps.executeUpdate();
            }
        }
    }

    private Task persistTask(String name, Task.Status status) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.description = "Test task";
        t.type = Task.Type.IMMEDIATE;
        t.status = status;
        t.nextRunAt = Instant.now();
        t.createdAt = Instant.now();
        t.updatedAt = Instant.now();
        t.save();
        return t;
    }

    /**
     * Proxy-based SchedulerClient stub that:
     * <ul>
     *   <li>Records {@code schedule()} calls so tests can assert which
     *       Tasks got registered.</li>
     *   <li>Surfaces a configurable set of "already-scheduled"
     *       task_instance ids via
     *       {@code fetchScheduledExecutionsForTask} — the
     *       call site the sweep uses to discover what's already in
     *       scheduled_tasks.</li>
     * </ul>
     */
    static class RecordingSchedulerStub {
        static class ScheduleCall {
            final TaskInstance<?> instance;
            final Instant when;
            ScheduleCall(TaskInstance<?> i, Instant w) { instance = i; when = w; }
        }
        final List<ScheduleCall> schedules = new ArrayList<>();
        final List<String> scheduledIds = new ArrayList<>();

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
                schedules.add(new ScheduleCall(inst, when));
                return null;
            }
            // BootConsistencyCheck calls
            // getScheduledExecutionsForTask(name), the no-data-class
            // overload. Java's Proxy invokes interface default
            // methods through the InvocationHandler too — they don't
            // auto-delegate to fetchScheduledExecutionsForTask
            // unless we call InvocationHandler.invokeDefault. So
            // handle the getScheduledExecutionsForTask call directly,
            // returning ScheduledExecution rows for the configured
            // "already-scheduled" ids.
            if ("getScheduledExecutionsForTask".equals(name)
                    && args != null && args.length >= 1
                    && args[0] instanceof String taskName) {
                var out = new ArrayList<ScheduledExecution<Object>>();
                for (var id : scheduledIds) {
                    var ti = new TaskInstance(taskName, id);
                    var exec = new Execution(Instant.now(), ti);
                    out.add(new ScheduledExecution<>(Object.class, exec));
                }
                return out;
            }
            // Sensible defaults for any other method the test code
            // happens to hit through the facade. Same pattern the
            // other Proxy-based stubs in the test suite use.
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
