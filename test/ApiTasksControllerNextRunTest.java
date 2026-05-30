import org.junit.jupiter.api.*;
import play.test.*;
import controllers.ApiTasksController;
import models.Task;

import java.time.Instant;

/**
 * JCLAW-22 (option C): unit tests for the INTERVAL "Next Run" computation
 * in {@link ApiTasksController#nextRunAtForDisplay}. Pure — constructs Tasks
 * in memory, no DB. CRON behaviour is unchanged by option C and depends on
 * {@code TimezoneResolver}, so it isn't re-exercised here.
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
