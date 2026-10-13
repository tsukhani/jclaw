import controllers.ApiTasksController;
import models.Task;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.AppClock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * JCLAW-22 (option C): unit tests for the "Next Run" computation in
 * {@link ApiTasksController#nextRunAtForDisplay}. Pure — constructs Tasks in
 * memory, no DB: a CRON case pins its own timezone, since the default zone is
 * a Config read.
 */
class ApiTasksControllerNextRunTest extends UnitTest {

    private static Task interval(long seconds) {
        var t = new Task();
        t.type = Task.Type.INTERVAL;
        t.status = Task.Status.ACTIVE;
        t.intervalSeconds = seconds;
        return t;
    }

    @Test
    void intervalAnchorsOnLastCompletion() {
        // Option C: next fire = lastCompletion + interval, NOT now + interval.
        var t = interval(60);
        var lastFired = Instant.parse("2026-05-30T12:00:00Z");
        assertEquals("2026-05-30T12:01:00Z",
                ApiTasksController.nextRunAtForDisplay(t, lastFired));
    }

    @Test
    void intervalBeforeFirstFireFallsBackToStored() {
        // No completed run yet → creation-time first-fire projection (stored).
        var t = interval(60);
        t.nextRunAt = Instant.parse("2026-05-30T12:00:00Z");
        assertEquals("2026-05-30T12:00:00Z",
                ApiTasksController.nextRunAtForDisplay(t, null));
    }

    @Test
    void runningCronShowsItsNextScheduledFire() {
        var t = new Task();
        t.type = Task.Type.CRON;
        t.status = Task.Status.RUNNING;
        t.cronExpression = "0 0 12 * * 0";
        t.timezone = "UTC";
        t.nextRunAt = Instant.parse("2026-09-27T14:22:48Z");
        var midFire = Clock.fixed(Instant.parse("2026-10-13T10:45:00Z"), ZoneOffset.UTC);
        assertEquals("2026-10-18T12:00:00Z",
                AppClock.callWith(midFire, () -> ApiTasksController.nextRunAtForDisplay(t, null)));
    }

    @Test
    void runningIntervalStillAnchorsOnLastCompletion() {
        var t = interval(60);
        t.status = Task.Status.RUNNING;
        t.nextRunAt = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals("2026-05-30T12:01:00Z",
                ApiTasksController.nextRunAtForDisplay(t, Instant.parse("2026-05-30T12:00:00Z")));
    }

    @Test
    void pausedShowsNoNextFire() {
        var t = interval(60);
        t.paused = true;
        t.nextRunAt = Instant.parse("2026-05-30T12:00:00Z");
        assertNull(ApiTasksController.nextRunAtForDisplay(t, Instant.parse("2026-05-30T12:00:00Z")));
    }

    @Test
    void terminalFallsBackToStoredIgnoringLastFired() {
        var t = interval(60);
        t.status = Task.Status.COMPLETED;
        t.nextRunAt = Instant.parse("2026-05-30T12:00:00Z");
        assertEquals("2026-05-30T12:00:00Z",
                ApiTasksController.nextRunAtForDisplay(t, Instant.parse("2026-05-30T13:00:00Z")));
    }
}
