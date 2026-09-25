import jobs.EventLogCleanupJob;
import models.EventLog;
import org.junit.jupiter.api.Test;
import play.jobs.OnApplicationStart;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JCLAW-748: {@code logs.retentionDays} is operator-editable, and the
 * bare {@code Integer.parseInt} it used to feed threw out of every 24h tick on
 * a typo — silently stopping event-log retention for good.
 */
class EventLogCleanupJobTest extends UnitTest {

    @Test
    void nonNumericRetentionFallsBackToDefault() {
        int days = assertDoesNotThrow(() -> EventLogCleanupJob.resolveRetentionDays("thirty"),
                "a non-numeric retention value must not throw out of the daily sweep");
        assertEquals(30, days, "the fallback must be the 30-day default");
    }

    @Test
    void absentOrBlankRetentionFallsBackToDefault() {
        assertEquals(30, EventLogCleanupJob.resolveRetentionDays(null),
                "an unset key must use the default");
        assertEquals(30, EventLogCleanupJob.resolveRetentionDays("  "),
                "a blank value must use the default");
    }

    @Test
    void validRetentionIsHonored() {
        assertEquals(7, EventLogCleanupJob.resolveRetentionDays("7"),
                "a valid value must be parsed as before");
        assertEquals(90, EventLogCleanupJob.resolveRetentionDays(" 90 "),
                "surrounding whitespace must not defeat a valid value");
    }

    @Test
    void cleanupDropsRowsPastRetentionAndKeepsTheRest() {
        Fixtures.deleteDatabase();
        seedEntry("stale-entry", Instant.now().minus(60, ChronoUnit.DAYS));
        seedEntry("fresh-entry", Instant.now().minus(1, ChronoUnit.DAYS));

        new EventLogCleanupJob().doJob();

        // Counted, not findById: the job deletes with a bulk JPQL statement, which
        // leaves the persistence context holding the row it just removed.
        assertEquals(0, EventLog.count("message = ?1", "stale-entry"),
                "an entry older than the retention window must be deleted");
        assertEquals(1, EventLog.count("message = ?1", "fresh-entry"),
                "an entry inside the retention window must survive");
    }

    @Test
    void zeroRetentionDisablesCleanup() {
        assertCleanupIsDisabledFor("0");
    }

    @Test
    void negativeRetentionDisablesCleanup() {
        assertCleanupIsDisabledFor("-1");
    }

    @Test
    void cleanupIsScheduledToRunAtStartup() {
        // JCLAW-1067: @Every("24h") alone first fires a full day after boot, so on an
        // instance restarted more often than that the job never ran once and retention
        // was never applied. The startup trigger is the fix; this pins it.
        assertTrue(EventLogCleanupJob.class.isAnnotationPresent(OnApplicationStart.class),
                "retention must not depend on 24h of uninterrupted uptime");
        assertTrue(EventLogCleanupJob.class.getAnnotation(OnApplicationStart.class).async(),
                "startup run must be async so a cleanup failure cannot block boot");
    }

    /** JCLAW-1269: {@code set}, not {@code setWithSideEffects} — the API refuses anything below
     *  1, and what is under test is the resolver holding for a value that reached the table
     *  around it. */
    private static void assertCleanupIsDisabledFor(String retentionDays) {
        Fixtures.deleteDatabase();
        seedEntry("stale-entry", Instant.now().minus(60, ChronoUnit.DAYS));
        seedEntry("fresh-entry", Instant.now().minus(1, ChronoUnit.DAYS));
        ConfigService.set(EventLogCleanupJob.CONFIG_KEY, retentionDays);
        try {
            new EventLogCleanupJob().doJob();

            assertEquals(1, EventLog.count("message = ?1", "stale-entry"),
                    "logs.retentionDays=" + retentionDays + " must disable cleanup, not empty the log");
            assertEquals(1, EventLog.count("message = ?1", "fresh-entry"),
                    "a row inside every window must survive a disabled cleanup");
        } finally {
            // The key is a row in the shared Config table with a 60s cache, and play1 runs
            // test classes concurrently.
            ConfigService.delete(EventLogCleanupJob.CONFIG_KEY);
        }
    }

    /** JCLAW-1067: {@code timestamp} is what the cutoff filters on, so set it explicitly. */
    private static long seedEntry(String message, Instant when) {
        var e = new EventLog();
        e.timestamp = when;
        e.level = "INFO";
        e.category = "test";
        e.message = message;
        e.save();
        return e.id;
    }
}
