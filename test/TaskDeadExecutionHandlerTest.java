import models.Agent;
import models.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.OperatorAlerts;
import services.RestartCutOff;
import services.TaskDeadExecutionHandler;
import services.TaskDeadExecutionHandler.Decision;

import java.time.Instant;
import java.util.List;

/**
 * JF-355: a task fire whose scheduler row went dead is revived only when that repeats nothing. A fire a
 * stopped JVM left ends as {@link RestartCutOff} says, where db-scheduler's default revive ran it again
 * from the start.
 */
class TaskDeadExecutionHandlerTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = new Agent();
        agent.name = "dead-execution-test-agent";
        agent.modelProvider = "test-provider";
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();
    }

    @Test
    void aFireStillLiveOnThisNodeIsRevived() {
        var task = oneShot("jf355-live", Task.Status.RUNNING);

        assertEquals(new Decision.Revive(), TaskDeadExecutionHandler.decide(task.id, true));
        assertEquals(Task.Status.RUNNING, ((Task) Task.findById(task.id)).status, "a live fire is left to finish");
    }

    @Test
    void aOneShotAStoppedJvmLeftIsRemovedAndEndsFailed() {
        var task = oneShot("jf355-dead-oneshot", Task.Status.RUNNING);

        var decision = new Decision[1];
        var alerts = OperatorAlerts.captureTaskAlertsForTest(() -> decision[0] = TaskDeadExecutionHandler.decide(task.id, false));

        assertEquals(new Decision.Stop(), decision[0], "the row is removed, not revived");
        var reloaded = (Task) Task.findById(task.id);
        assertEquals(Task.Status.FAILED, reloaded.status);
        assertEquals(RestartCutOff.ONE_SHOT_ERROR, reloaded.lastError);
        assertEquals(List.of(OperatorAlerts.cutOffAlert("jf355-dead-oneshot", agent.name)),
                alertsNaming(alerts, "jf355-dead-oneshot"));
    }

    @Test
    void aLostOneShotEndsTheSameWay() {
        var task = oneShot("jf355-lost-oneshot", Task.Status.LOST);

        assertEquals(new Decision.Stop(), TaskDeadExecutionHandler.decide(task.id, false));
        assertEquals(Task.Status.FAILED, ((Task) Task.findById(task.id)).status);
    }

    @Test
    void aOneShotTheBootSweepAlreadyEndedIsRemovedWithoutASecondAlert() {
        var task = oneShot("jf355-already-ended", Task.Status.FAILED);

        var decision = new Decision[1];
        var alerts = OperatorAlerts.captureTaskAlertsForTest(() -> decision[0] = TaskDeadExecutionHandler.decide(task.id, false));

        assertEquals(new Decision.Stop(), decision[0]);
        assertEquals(List.of(), alertsNaming(alerts, "jf355-already-ended"));
    }

    @Test
    void aOneShotWhoseFireDiedBeforeItStartedIsRunNow() {
        // A fire moves its task to RUNNING as it opens its run, so PENDING means nothing of it ran.
        var task = oneShot("jf355-never-started", Task.Status.PENDING);

        assertEquals(new Decision.Revive(), TaskDeadExecutionHandler.decide(task.id, false));
        assertEquals(Task.Status.PENDING, ((Task) Task.findById(task.id)).status);
    }

    @Test
    void aRecurringTaskMovesOnToItsNextOccurrenceInsteadOfRunningNow() {
        var task = recurring("jf355-recurring", Task.Status.RUNNING);

        var before = Instant.now();
        var decision = TaskDeadExecutionHandler.decide(task.id, false);
        var after = Instant.now();

        var at = assertInstanceOf(Decision.NextOccurrence.class, decision).at();
        assertFalse(at.isBefore(before.plusSeconds(1800)), "next occurrence, not now: " + at);
        assertFalse(at.isAfter(after.plusSeconds(1800)), "one interval out: " + at);
        assertEquals(Task.Status.ACTIVE, ((Task) Task.findById(task.id)).status);
    }

    @Test
    void aCancelledRecurringTaskIsRemoved() {
        var task = recurring("jf355-cancelled", Task.Status.CANCELLED);

        assertEquals(new Decision.Stop(), TaskDeadExecutionHandler.decide(task.id, false));
        assertEquals(Task.Status.CANCELLED, ((Task) Task.findById(task.id)).status);
    }

    @Test
    void aDeletedTaskIsRemoved() {
        assertEquals(new Decision.Stop(), TaskDeadExecutionHandler.decide(Long.MAX_VALUE, false));
    }

    private Task oneShot(String name, Task.Status status) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.description = "Test task";
        t.type = Task.Type.IMMEDIATE;
        t.status = status;
        t.nextRunAt = Instant.now();
        t.save();
        return t;
    }

    private Task recurring(String name, Task.Status status) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.description = "Test task";
        t.type = Task.Type.INTERVAL;
        t.intervalSeconds = 1800L;
        t.status = status;
        t.nextRunAt = Instant.now();
        t.save();
        return t;
    }

    /** Concurrent test classes may raise task alerts too, so each test reads only its own task's. */
    private static List<String> alertsNaming(List<String> alerts, String taskName) {
        return alerts.stream().filter(a -> a.contains("'" + taskName + "'")).toList();
    }
}
