import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.JClawCronUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * JCLAW-261 coverage for {@link JClawCronUtils#nextExecution(String, ZoneId)}:
 * the zone-aware overload must interpret the cron expression's wall-clock
 * fields in the supplied zone, so "every day at 9 am" fires at 9 am local
 * to that zone regardless of the JVM default.
 *
 * <p>Asserts the converted UTC instant rather than comparing zone objects:
 * "next 09:00 in Asia/Tokyo" and "next 09:00 in America/New_York" land at
 * different UTC instants (Tokyo is 9 hours ahead of UTC; NYC is 4-5 hours
 * behind), and the offset is the unambiguous signal that the zone parameter
 * was honored.
 */
class JClawCronUtilsTest extends UnitTest {

    /** "Every day at 09:00:00" in Spring 6-field format. */
    private static final String CRON_9_AM_DAILY = "0 0 9 * * *";

    @Test
    void nextExecutionInTokyoFiresAt9AmTokyoLocal() {
        var tokyoNext = JClawCronUtils.nextExecution(CRON_9_AM_DAILY, ZoneId.of("Asia/Tokyo"));
        assertNotNull(tokyoNext);
        // Convert back to Tokyo local time and assert hour == 9, minute == 0.
        var local = LocalDateTime.ofInstant(tokyoNext, ZoneId.of("Asia/Tokyo"));
        assertEquals(9, local.getHour(),
                "next fire's hour in Asia/Tokyo must be 09 — that's the zone the cron was interpreted in");
        assertEquals(0, local.getMinute());
        assertEquals(0, local.getSecond());
    }

    @Test
    void nextExecutionInNewYorkFiresAt9AmEasternLocal() {
        var nyNext = JClawCronUtils.nextExecution(CRON_9_AM_DAILY, ZoneId.of("America/New_York"));
        assertNotNull(nyNext);
        var local = LocalDateTime.ofInstant(nyNext, ZoneId.of("America/New_York"));
        assertEquals(9, local.getHour());
        assertEquals(0, local.getMinute());
    }

    @Test
    void differentZonesYieldDifferentUtcInstants() {
        // Same cron expression, different zones → different UTC fire
        // instants. This is the operationally important property: a
        // task scheduled for 9 am NYC and one scheduled for 9 am Tokyo
        // must NOT collapse to the same Instant just because the JVM
        // default zone happens to be one or the other.
        var tokyo = JClawCronUtils.nextExecution(CRON_9_AM_DAILY, ZoneId.of("Asia/Tokyo"));
        var nyc = JClawCronUtils.nextExecution(CRON_9_AM_DAILY, ZoneId.of("America/New_York"));
        assertNotNull(tokyo);
        assertNotNull(nyc);
        assertNotEquals(tokyo, nyc,
                "9am Tokyo and 9am NYC are different UTC instants — proving the zone arg is honored");
    }

    @Test
    void nullZoneFallsBackToJvmDefault() {
        // Passing null is documented as "use the JVM default" — same
        // behavior as the legacy single-arg overload. Asserting the
        // result equals the legacy call's result keeps both paths
        // in lockstep.
        var withNull = JClawCronUtils.nextExecution(CRON_9_AM_DAILY, null);
        var legacy = JClawCronUtils.nextExecution(CRON_9_AM_DAILY);
        assertEquals(legacy, withNull,
                "null zone must match the no-zone overload's behavior");
    }

    @Test
    void invalidExpressionReturnsNullEvenWithValidZone() {
        // Parse failures still return null on the zone-aware path —
        // callers (TaskSchedulingService, TaskExecutionHandler) treat
        // null as "skip and log", preserving the pre-JCLAW-261 contract.
        var result = JClawCronUtils.nextExecution("not a real cron", ZoneId.of("UTC"));
        assertNull(result);
    }

    @Test
    void blankExpressionReturnsNull() {
        assertNull(JClawCronUtils.nextExecution("", ZoneId.of("UTC")));
        assertNull(JClawCronUtils.nextExecution("   ", ZoneId.of("UTC")));
        assertNull(JClawCronUtils.nextExecution(null, ZoneId.of("UTC")));
    }

    @Test
    void lastWeekdayOfMonthModifierIsSupported() {
        // JCLAW-438: task_manager emits "<n>L" for "the last <weekday> of the
        // month" (e.g. "last Friday" payroll reminders) instead of the lossy
        // dom=25-31 range. The cron engine (db-scheduler CronSchedule) must
        // accept L and resolve to the LAST such weekday; this guards against a
        // future engine swap silently dropping L support and stranding tasks.
        var zone = ZoneId.of("Asia/Kuala_Lumpur");
        JClawCronUtils.validate("0 0 17 * * 5L"); // must not throw at the API/tool boundary
        var next = JClawCronUtils.nextExecution("0 0 17 * * 5L", zone);
        assertNotNull(next, "last-Friday cron must resolve a next fire");
        var local = LocalDateTime.ofInstant(next, zone);
        assertEquals(java.time.DayOfWeek.FRIDAY, local.getDayOfWeek(), "5L must fire on a Friday");
        assertEquals(17, local.getHour());
        // The LAST Friday of its month: a week later is necessarily next month.
        assertNotEquals(local.getMonth(), local.plusDays(7).getMonth(),
                "5L must be the last Friday — seven days later lands in the next month");
    }

    @Test
    void nthWeekdayOfMonthModifierIsSupported() {
        // "#" (Nth weekday) also flows through, e.g. "0 0 9 * * 1#2" = 2nd Monday.
        var zone = ZoneId.of("Asia/Kuala_Lumpur");
        JClawCronUtils.validate("0 0 9 * * 1#2");
        var next = JClawCronUtils.nextExecution("0 0 9 * * 1#2", zone);
        assertNotNull(next);
        var local = LocalDateTime.ofInstant(next, zone);
        assertEquals(java.time.DayOfWeek.MONDAY, local.getDayOfWeek());
        assertTrue(local.getDayOfMonth() >= 8 && local.getDayOfMonth() <= 14,
                "2nd Monday must fall on day 8-14; got " + local.getDayOfMonth());
    }

    @Test
    void zoneAwareResultIsFutureRelativeToNow() {
        // Defensive sanity: regardless of zone, the next fire must be
        // strictly after now() — the simulatedSuccess(Instant.now()) seed
        // ensures the previous fire is treated as already-occurred.
        var nowEpoch = Instant.now().getEpochSecond();
        var next = JClawCronUtils.nextExecution(CRON_9_AM_DAILY, ZoneOffset.UTC);
        assertNotNull(next);
        assertTrue(next.getEpochSecond() > nowEpoch,
                "next execution must be strictly in the future");
    }
}
