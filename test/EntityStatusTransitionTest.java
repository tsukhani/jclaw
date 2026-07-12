import models.SubagentRun;
import models.Task;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-733: the guarded lifecycle transitions on {@link Task} and
 * {@link SubagentRun}. Pure in-memory unit tests — {@code transitionTo} only
 * mutates the status field, so no persistence (and no Lucene index hook) is
 * involved. Verifies legal edges pass, same-state is a no-op, and an
 * out-of-lifecycle move throws without mutating the entity.
 */
class EntityStatusTransitionTest extends UnitTest {

    // ── Task ──

    @Test
    void taskCancelFromActiveIsLegal() {
        var t = new Task();
        t.type = Task.Type.CRON;
        t.status = Task.Status.ACTIVE;
        t.transitionTo(Task.Status.CANCELLED);
        assertEquals(Task.Status.CANCELLED, t.status);
    }

    @Test
    void taskReviveFromCancelledIsLegal() {
        var t = new Task();
        t.type = Task.Type.CRON;
        t.status = Task.Status.CANCELLED;
        t.transitionTo(Task.initialStatusFor(t.type)); // recurring -> ACTIVE
        assertEquals(Task.Status.ACTIVE, t.status);
    }

    @Test
    void taskRetryFromFailedIsLegal() {
        var t = new Task();
        t.type = Task.Type.SCHEDULED;
        t.status = Task.Status.FAILED;
        t.transitionTo(Task.initialStatusFor(t.type)); // one-shot -> PENDING
        assertEquals(Task.Status.PENDING, t.status);
    }

    @Test
    void taskIllegalTransitionThrowsAndLeavesStatusUnchanged() {
        var t = new Task();
        t.status = Task.Status.PENDING;
        assertThrows(IllegalStateException.class,
                () -> t.transitionTo(Task.Status.COMPLETED)); // skips RUNNING
        assertEquals(Task.Status.PENDING, t.status, "status must not change on a rejected transition");
    }

    @Test
    void taskSameStateIsNoOp() {
        var t = new Task();
        t.status = Task.Status.RUNNING;
        t.transitionTo(Task.Status.RUNNING);
        assertEquals(Task.Status.RUNNING, t.status);
    }

    // ── SubagentRun ──

    @Test
    void runSettleFromRunningIsLegal() {
        var r = new SubagentRun();
        r.status = SubagentRun.Status.RUNNING;
        r.transitionTo(SubagentRun.Status.COMPLETED);
        assertEquals(SubagentRun.Status.COMPLETED, r.status);
    }

    @Test
    void runKilledFromRunningIsLegal() {
        var r = new SubagentRun();
        r.status = SubagentRun.Status.RUNNING;
        r.transitionTo(SubagentRun.Status.KILLED);
        assertEquals(SubagentRun.Status.KILLED, r.status);
    }

    @Test
    void runReSettleFromTerminalThrows() {
        var r = new SubagentRun();
        r.status = SubagentRun.Status.COMPLETED;
        assertThrows(IllegalStateException.class,
                () -> r.transitionTo(SubagentRun.Status.KILLED));
        assertEquals(SubagentRun.Status.COMPLETED, r.status, "a terminal outcome must not be overwritten");
    }
}
