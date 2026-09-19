import jobs.EventLogCleanupJob;
import jobs.LatencyMetricCleanupJob;
import jobs.RetentionDays;
import jobs.TaskCleanupJob;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;

/**
 * JCLAW-1231: the three cleanup jobs used to carry a retention resolver each, and the
 * copies disagreed on what 0 meant (JCLAW-1269). This pins the shared rule and the
 * per-job parameters that are allowed to differ.
 */
class RetentionDaysTest extends UnitTest {

    private static final String CATEGORY = "RETENTION_DAYS_TEST";
    private static final String KEY = "test.retentionDays.jclaw1231";

    @Test
    void absentBlankAndNonNumericFallBackToTheDefault() {
        assertEquals(30, RetentionDays.resolve(null, 30, 3650, CATEGORY, KEY));
        assertEquals(30, RetentionDays.resolve("   ", 30, 3650, CATEGORY, KEY));
        assertEquals(14, RetentionDays.resolve("fourteen", 14, 3650, CATEGORY, KEY));
    }

    @Test
    void aValidWindowIsHonouredEvenWithSurroundingWhitespace() {
        assertEquals(7, RetentionDays.resolve("7", 30, 3650, CATEGORY, KEY));
        assertEquals(90, RetentionDays.resolve(" 90 ", 30, 3650, CATEGORY, KEY));
    }

    @Test
    void zeroAndNegativeBothDisableCleanup() {
        // JCLAW-1269: a cutoff at or after now takes the whole table, so neither value
        // may resolve to a window — 0 is the documented "off" switch on all three keys.
        assertEquals(RetentionDays.DISABLED, RetentionDays.resolve("0", 30, 3650, CATEGORY, KEY));
        assertEquals(RetentionDays.DISABLED, RetentionDays.resolve("-5", 30, 3650, CATEGORY, KEY));
    }

    @Test
    void theCeilingIsPerCallerAndNoCeilingLetsALargeWindowThrough() {
        assertEquals(30, RetentionDays.resolve("10000", 30, 3650, CATEGORY, KEY));
        assertEquals(10000, RetentionDays.resolve("10000", 30, RetentionDays.NO_CEILING, CATEGORY, KEY));
    }

    @Test
    void allThreeJobsAgreeOnTheDisabledSentinel() {
        assertEquals(RetentionDays.DISABLED, EventLogCleanupJob.RETENTION_DISABLED);
        assertEquals(RetentionDays.DISABLED, TaskCleanupJob.RETENTION_DISABLED);
        assertEquals(RetentionDays.DISABLED, LatencyMetricCleanupJob.RETENTION_DISABLED);
    }

    @Test
    void fromConfigReadsTheKeyAndKeepsTheJobDefaultWhenUnset() {
        // Scoped to a key no other test class writes: play1 runs test classes concurrently
        // against one Config table.
        ConfigService.delete(KEY);
        assertEquals(21, RetentionDays.fromConfig(KEY, 21, 3650, CATEGORY));
        ConfigService.set(KEY, "3");
        try {
            assertEquals(3, RetentionDays.fromConfig(KEY, 21, 3650, CATEGORY));
        } finally {
            ConfigService.delete(KEY);
        }
    }
}
