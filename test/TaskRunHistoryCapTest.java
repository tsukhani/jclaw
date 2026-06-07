import models.Agent;
import models.Task;
import models.TaskRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.TaskExecutor;

import java.time.Instant;
import java.util.ArrayList;

/**
 * Per-task run-history cap ({@link TaskExecutor#MAX_RUNS_PER_TASK}): a
 * frequently recurring task must never keep more than the N most recent
 * {@code task_run} rows in the database. {@link TaskExecutor#pruneRunHistory}
 * is the cap enforcement, invoked right after each new run opens in
 * {@link TaskExecutor#runTask}.
 */
class TaskRunHistoryCapTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void keepsOnlyTheMostRecentRunsPerTask() {
        var task = persistTask("cap-task");
        var base = Instant.parse("2026-01-01T00:00:00Z");
        var ids = new ArrayList<Long>();
        // N + 2 fires, oldest (i=0) .. newest by startedAt.
        for (int i = 0; i < TaskExecutor.MAX_RUNS_PER_TASK + 2; i++) {
            ids.add(persistRun(task, base.plusSeconds(i)));
        }

        TaskExecutor.pruneRunHistory(task.id);

        assertEquals((long) TaskExecutor.MAX_RUNS_PER_TASK,
                TaskRun.count("task.id = ?1", task.id),
                "only the N most recent runs are kept");
        assertEquals(0L, TaskRun.count("id = ?1", ids.get(0)), "oldest run pruned");
        assertEquals(0L, TaskRun.count("id = ?1", ids.get(1)), "second-oldest run pruned");
        assertEquals(1L, TaskRun.count("id = ?1", ids.get(2)), "third-oldest run retained");
        assertEquals(1L, TaskRun.count("id = ?1", ids.getLast()), "newest run retained");
    }

    @Test
    void leavesRunsUntouchedWhenAtOrUnderTheCap() {
        var task = persistTask("under-cap");
        var base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < TaskExecutor.MAX_RUNS_PER_TASK; i++) {
            persistRun(task, base.plusSeconds(i));
        }

        TaskExecutor.pruneRunHistory(task.id);

        assertEquals((long) TaskExecutor.MAX_RUNS_PER_TASK,
                TaskRun.count("task.id = ?1", task.id),
                "exactly-at-cap leaves every run");
    }

    @Test
    void prunesOnlyTheTargetTask() {
        var a = persistTask("task-a");
        var b = persistTask("task-b");
        var base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < TaskExecutor.MAX_RUNS_PER_TASK + 3; i++) persistRun(a, base.plusSeconds(i));
        for (int i = 0; i < 4; i++) persistRun(b, base.plusSeconds(i));

        TaskExecutor.pruneRunHistory(a.id);

        assertEquals((long) TaskExecutor.MAX_RUNS_PER_TASK,
                TaskRun.count("task.id = ?1", a.id), "target task capped");
        assertEquals(4L, TaskRun.count("task.id = ?1", b.id),
                "another task's runs are not pruned");
    }

    private static Task persistTask(String name) {
        var agent = new Agent();
        agent.name = name + "-agent";
        agent.modelProvider = "test-provider";
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.type = Task.Type.CRON;
        t.cronExpression = "*/30 * * * *";
        t.status = Task.Status.ACTIVE;
        t.save();
        return t;
    }

    private static Long persistRun(Task task, Instant startedAt) {
        var r = new TaskRun();
        r.task = task;
        r.startedAt = startedAt;
        r.status = TaskRun.Status.COMPLETED;
        r.completedAt = startedAt.plusSeconds(1);
        r.durationMs = 1000L;
        r.save();
        return r.id;
    }
}
