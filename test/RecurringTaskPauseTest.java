import models.Agent;
import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.JClawFailureHandler;
import services.JClawFailureHandler.Decision;
import services.OperatorAlerts;
import services.TaskSchedulingService;
import services.Tx;
import tools.TaskTool;
import utils.AppClock;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * A recurring task whose scheduled runs keep failing, after their retries, is paused once
 * {@code tasks.pauseAfterFailedOccurrences} of them fail in a row; resuming starts the count again.
 */
class RecurringTaskPauseTest extends UnitTest {

    private static final Clock FIXED_NOW = Clock.fixed(Instant.parse("2026-10-27T03:00:00Z"), ZoneOffset.UTC);
    private static final String ERROR = "HTTP 401 Unauthorized";

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.delete(JClawFailureHandler.PAUSE_AFTER_KEY);
        agent = new Agent();
        agent.name = "pause-test-agent";
        agent.modelProvider = "test-provider";
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();
    }

    @AfterEach
    void teardown() {
        ConfigService.delete(JClawFailureHandler.PAUSE_AFTER_KEY);
    }

    @Test
    void theThirdFailedRunInARowPausesTheTaskWithOneAlertNamingThePause() {
        ConfigService.set(JClawFailureHandler.PAUSE_AFTER_KEY, "3");
        var task = recurringTask("Morning briefing");
        var states = new ArrayList<String>();

        var alerts = OperatorAlerts.captureTaskAlertsForTest(() -> {
            for (int i = 0; i < 3; i++) {
                failOccurrence(task.id, new RuntimeException(ERROR));
                var t = reload(task.id);
                states.add(t.failedOccurrences + ":" + t.paused + ":" + t.pauseReason);
            }
        });

        var reason = "Paused after 3 scheduled runs in a row failed. Last error (permanent error): " + ERROR;
        assertEquals(List.of("1:false:null", "2:false:null", "3:true:" + reason), states);
        var next = FIXED_NOW.instant().plusSeconds(3600).atZone(ZoneId.of("UTC"));
        var failed = OperatorAlerts.occurrenceAlert("Morning briefing", "pause-test-agent", "permanent error", ERROR, next);
        assertEquals(List.of(failed, failed,
                        "JClaw alert: task 'Morning briefing' (agent pause-test-agent) failed (permanent error): "
                                + ERROR + ". It has failed 3 scheduled runs in a row, so it is paused until it is"
                                + " resumed on the Tasks page."),
                alerts);
        assertEquals(Task.Status.ACTIVE, reload(task.id).status, "a pause keeps the task's state");
    }

    @Test
    void aSettingOfOnePausesAtTheFirstFailedRun() {
        ConfigService.set(JClawFailureHandler.PAUSE_AFTER_KEY, "1");
        var task = recurringTask("hourly-sync");

        var alerts = OperatorAlerts.captureTaskAlertsForTest(() -> failOccurrence(task.id, new RuntimeException(ERROR)));

        assertTrue(reload(task.id).paused);
        assertEquals(1, alerts.size(), () -> String.valueOf(alerts));
        assertTrue(alerts.getFirst().contains("It has failed 1 scheduled runs in a row, so it is paused"), alerts::toString);
    }

    @Test
    void aRetryStillToComeIsNotAFailedRun() {
        ConfigService.set(JClawFailureHandler.PAUSE_AFTER_KEY, "1");
        var task = recurringTask("flaky-sync");

        var decision = AppClock.callWith(FIXED_NOW,
                () -> JClawFailureHandler.decide(task.id, new SocketTimeoutException("read timed out")));

        assertInstanceOf(Decision.Reschedule.class, decision);
        var fresh = reload(task.id);
        assertEquals(0, fresh.failedOccurrences);
        assertFalse(fresh.paused);
    }

    @Test
    void aTaskWhoseRetriesRunOutCountsAsOneFailedRun() {
        ConfigService.set(JClawFailureHandler.PAUSE_AFTER_KEY, "2");
        var task = recurringTask("exhausted-sync");
        setRetryCount(task.id, 3);

        var decision = AppClock.callWith(FIXED_NOW,
                () -> JClawFailureHandler.decide(task.id, new SocketTimeoutException("read timed out")));

        assertInstanceOf(Decision.NextOccurrence.class, decision);
        var fresh = reload(task.id);
        assertEquals(1, fresh.failedOccurrences);
        assertFalse(fresh.paused);
    }

    @Test
    void withTheKeyAbsentAFailingTaskIsNeverPaused() {
        var task = recurringTask("never-paused");

        var alerts = OperatorAlerts.captureTaskAlertsForTest(() -> {
            for (int i = 0; i < 5; i++) failOccurrence(task.id, new RuntimeException(ERROR));
        });

        var fresh = reload(task.id);
        assertFalse(fresh.paused);
        assertNull(fresh.pauseReason);
        assertEquals(5, fresh.failedOccurrences);
        assertEquals(5, alerts.stream().filter(a -> a.contains("It runs again at")).count(), alerts::toString);
    }

    @Test
    void withTheKeyAtZeroAFailingTaskIsNeverPaused() {
        ConfigService.set(JClawFailureHandler.PAUSE_AFTER_KEY, "0");
        var task = recurringTask("zero-never-paused");

        for (int i = 0; i < 5; i++) failOccurrence(task.id, new RuntimeException(ERROR));

        assertFalse(reload(task.id).paused);
    }

    @Test
    void aOneShotTaskThatFailsIsNotCountedOrPaused() {
        ConfigService.set(JClawFailureHandler.PAUSE_AFTER_KEY, "1");
        var task = recurringTask("one-off");
        Tx.run(() -> {
            var t = (Task) Task.findById(task.id);
            t.type = Task.Type.IMMEDIATE;
            t.status = Task.Status.PENDING;
            t.intervalSeconds = null;
            t.save();
            return null;
        });

        var alerts = OperatorAlerts.captureTaskAlertsForTest(() -> {
            var decision = AppClock.callWith(FIXED_NOW, () -> JClawFailureHandler.decide(task.id, new RuntimeException(ERROR)));
            assertInstanceOf(Decision.Fail.class, decision);
        });

        var fresh = reload(task.id);
        assertEquals(Task.Status.FAILED, fresh.status);
        assertEquals(0, fresh.failedOccurrences);
        assertFalse(fresh.paused);
        assertEquals(List.of(), alerts);
    }

    @Test
    void resumingThroughTaskManagerClearsTheCountAndTheReason() {
        var task = recurringTask("paused-by-failures");
        markPausedByFailures(task.id);

        var reply = new TaskTool().execute("""
                {"action":"resume","name":"paused-by-failures"}""", agent);

        assertEquals("Task 'paused-by-failures' resumed.", reply);
        var fresh = reload(task.id);
        assertFalse(fresh.paused);
        assertEquals(0, fresh.failedOccurrences);
        assertNull(fresh.pauseReason);
    }

    @Test
    void anOperatorsPauseRecordsNoReason() {
        var task = recurringTask("paused-by-hand");

        TaskSchedulingService.pause(task.id);

        var fresh = reload(task.id);
        assertTrue(fresh.paused);
        assertNull(fresh.pauseReason);
    }

    // === Helpers ===

    private Task recurringTask(String name) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.description = "Recurring work.";
        t.type = Task.Type.INTERVAL;
        t.intervalSeconds = 3600L;
        t.status = Task.Status.ACTIVE;
        t.timezone = "UTC";
        t.maxRetries = 3;
        t.nextRunAt = FIXED_NOW.instant();
        t.save();
        return t;
    }

    /** One scheduled run that fails for good: a permanent error ends it without a retry. */
    private static void failOccurrence(Long taskId, Throwable error) {
        var decision = AppClock.callWith(FIXED_NOW, () -> JClawFailureHandler.decide(taskId, error));
        assertInstanceOf(Decision.NextOccurrence.class, decision);
    }

    private static void setRetryCount(Long taskId, int retryCount) {
        Tx.run(() -> {
            var t = (Task) Task.findById(taskId);
            t.retryCount = retryCount;
            t.save();
            return null;
        });
    }

    private static void markPausedByFailures(Long taskId) {
        Tx.run(() -> {
            var t = (Task) Task.findById(taskId);
            t.failedOccurrences = 3;
            t.paused = true;
            t.pauseReason = "Paused after 3 scheduled runs in a row failed. Last error (permanent error): " + ERROR;
            t.save();
            return null;
        });
    }

    private static Task reload(Long taskId) {
        return Tx.run(() -> {
            var t = (Task) Task.findById(taskId);
            play.db.jpa.JPA.em().refresh(t);
            return t;
        });
    }
}
