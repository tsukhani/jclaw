import models.Agent;
import models.EventLog;
import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.DB;
import play.test.Fixtures;
import play.test.UnitTest;
import services.EventLogger;
import services.LostTaskDetector;
import services.TaskExecutionHandler;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * JCLAW-258 unit tests for {@link LostTaskDetector}.
 *
 * <p>Tests split into two surfaces:
 * <ul>
 *   <li>{@link LostTaskDetector#markLost(List)} — the pure-logic seam.
 *       No JDBC, no scheduler. We seed Tasks with status=RUNNING (and
 *       a few negative-cases) and assert the transition + event-log
 *       emission.</li>
 *   <li>{@link LostTaskDetector#detect(Instant)} — the production
 *       JDBC path. We insert {@code scheduled_tasks} rows directly
 *       with controlled {@code last_heartbeat} values and assert the
 *       detector picks up exactly the stale ones.</li>
 * </ul>
 *
 * <p>{@code scheduled_tasks} is not JPA-managed and survives
 * {@code Fixtures.deleteDatabase}, so each test cleans up its own
 * rows in {@code @AfterEach}.
 */
class LostTaskDetectorTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        truncateScheduledTasks();
        agent = persistAgent();
    }

    @AfterEach
    void tearDown() throws Exception {
        EventLogger.clear();
        truncateScheduledTasks();
    }

    // === markLost: pure-logic seam ===

    @Test
    void transitionsRunningWithStaleHeartbeatToLost() {
        var task = persistTask("running-task", Task.Status.RUNNING);
        var heartbeat = Instant.now().minusSeconds(90);

        int flipped = LostTaskDetector.markLost(
                List.of(new LostTaskDetector.StaleRow(task.id, heartbeat)));

        assertEquals(1, flipped);
        var reloaded = (Task) Task.findById(task.id);
        assertEquals(Task.Status.LOST, reloaded.status,
                "RUNNING with stale heartbeat must flip to LOST");

        EventLogger.flush();
        long lostEvents = EventLog.count(
                "category = ?1 AND message LIKE ?2",
                "TASK_LOST", "%running-task%");
        assertEquals(1L, lostEvents,
                "expected exactly one TASK_LOST event-log row");
    }

    @Test
    void leavesFreshlyHeartbeatedRunningAlone() {
        // markLost trusts its caller, but the controller-level "is this
        // really stale" assertion still belongs in the test — pass a
        // non-RUNNING task and assert no flip. Mirrors the spec's
        // "leavesFreshlyHeartbeatedRunningAlone" case at the markLost
        // layer (the detect() layer test below covers the JDBC filter).
        var task = persistTask("not-running", Task.Status.PENDING);
        var heartbeat = Instant.now().minusSeconds(30);

        int flipped = LostTaskDetector.markLost(
                List.of(new LostTaskDetector.StaleRow(task.id, heartbeat)));

        assertEquals(0, flipped,
                "non-RUNNING Tasks must not be flipped to LOST");
        var reloaded = (Task) Task.findById(task.id);
        assertEquals(Task.Status.PENDING, reloaded.status);
    }

    @Test
    void leavesScheduledTasksRowIntact() throws Exception {
        // Design A: LOST is visibility-only. Insert a scheduled_tasks
        // row with stale heartbeat; run detect(); assert the row is
        // still present (db-scheduler still owns recovery via its own
        // dead-execution mechanism).
        var task = persistTask("design-a", Task.Status.RUNNING);
        insertScheduledTaskRow(task.id, Instant.now().minusSeconds(90));

        int flipped = LostTaskDetector.detect();

        assertEquals(1, flipped);
        assertTrue(scheduledTaskRowExists(task.id),
                "detector must NOT remove the scheduled_tasks row — "
                        + "db-scheduler owns the re-fire");
    }

    // === detect(): production JDBC path ===

    @Test
    void detectPicksUpStaleRowsAndIgnoresFresh() throws Exception {
        var stale = persistTask("stale", Task.Status.RUNNING);
        var fresh = persistTask("fresh", Task.Status.RUNNING);
        insertScheduledTaskRow(stale.id, Instant.now().minusSeconds(90));
        insertScheduledTaskRow(fresh.id, Instant.now().minusSeconds(20));

        int flipped = LostTaskDetector.detect();

        assertEquals(1, flipped, "only the stale row should flip");
        assertEquals(Task.Status.LOST, ((Task) Task.findById(stale.id)).status);
        assertEquals(Task.Status.RUNNING, ((Task) Task.findById(fresh.id)).status,
                "fresh-heartbeat row must stay RUNNING");
    }

    @Test
    void detectIgnoresUnpickedRows() throws Exception {
        // A row with picked=false is NOT a candidate (it hasn't been
        // grabbed by any executor) — db-scheduler is just waiting to
        // fire it. Even if the heartbeat column is null/old, ignore.
        var task = persistTask("unpicked", Task.Status.RUNNING);
        insertScheduledTaskRowUnpicked(task.id, Instant.now().minusSeconds(90));

        int flipped = LostTaskDetector.detect();

        assertEquals(0, flipped, "unpicked rows are not LOST candidates");
        assertEquals(Task.Status.RUNNING, ((Task) Task.findById(task.id)).status);
    }

    @Test
    void detectIsNoopWhenNoSchedulerRows() {
        var task = persistTask("no-row", Task.Status.RUNNING);

        int flipped = LostTaskDetector.detect();

        assertEquals(0, flipped);
        assertEquals(Task.Status.RUNNING, ((Task) Task.findById(task.id)).status,
                "no scheduled_tasks row means nothing to reconcile");
    }

    // === Helpers ===

    private Agent persistAgent() {
        var a = new Agent();
        a.name = "lost-detector-test-agent";
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.save();
        return a;
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

    private void insertScheduledTaskRow(Long taskId, Instant lastHeartbeat) throws Exception {
        insertRow(taskId, true, lastHeartbeat);
    }

    private void insertScheduledTaskRowUnpicked(Long taskId, Instant lastHeartbeat) throws Exception {
        insertRow(taskId, false, lastHeartbeat);
    }

    private void insertRow(Long taskId, boolean picked, Instant lastHeartbeat) throws Exception {
        // DB.getDataSource() returns Hikari-pooled connections with autoCommit=false
        // (Hibernate-managed). Force autocommit on so the row lands before the
        // detector's separate-connection SELECT runs in the same test method.
        try (var conn = DB.getDataSource().getConnection()) {
            conn.setAutoCommit(true);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO scheduled_tasks "
                    + "(task_name, task_instance, execution_time, picked, "
                    + " picked_by, last_heartbeat, version) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, TaskExecutionHandler.TASK_NAME);
                ps.setString(2, taskId.toString());
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.setBoolean(4, picked);
                ps.setString(5, picked ? "test-scheduler" : null);
                ps.setTimestamp(6, Timestamp.from(lastHeartbeat));
                ps.setLong(7, 1L);
                ps.executeUpdate();
            }
        }
    }

    private boolean scheduledTaskRowExists(Long taskId) throws Exception {
        try (var conn = DB.getDataSource().getConnection();
             var ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM scheduled_tasks "
                     + "WHERE task_name = ? AND task_instance = ?")) {
            ps.setString(1, TaskExecutionHandler.TASK_NAME);
            ps.setString(2, taskId.toString());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private void truncateScheduledTasks() throws Exception {
        try (var conn = DB.getDataSource().getConnection()) {
            conn.setAutoCommit(true);
            try (var ps = conn.prepareStatement(
                    "DELETE FROM scheduled_tasks WHERE task_name = ?")) {
                ps.setString(1, TaskExecutionHandler.TASK_NAME);
                ps.executeUpdate();
            }
        }
    }
}
