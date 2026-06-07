import org.junit.jupiter.api.*;
import play.test.*;
import services.ScheduleShorthandParser;
import models.Task;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

class ScheduleShorthandParserTest extends UnitTest {

    @Test
    void nowParsesToImmediate() {
        var spec = ScheduleShorthandParser.parse("now");
        assertEquals(Task.Type.IMMEDIATE, spec.type());
        assertNotNull(spec.scheduledAt());
        assertNull(spec.cronExpression());
        assertNull(spec.intervalSeconds());
        assertEquals("now", spec.scheduleDisplay());
        // scheduledAt should be near "now"
        var deltaMs = Math.abs(spec.scheduledAt().toEpochMilli() - Instant.now().toEpochMilli());
        assertTrue(deltaMs < 5_000, "scheduledAt should be near now, got delta " + deltaMs + "ms");
    }

    @Test
    void nowIsCaseInsensitive() {
        var spec = ScheduleShorthandParser.parse("Now");
        assertEquals(Task.Type.IMMEDIATE, spec.type());
        // Display preserves original case.
        assertEquals("Now", spec.scheduleDisplay());
    }

    @Test
    void bareDurationMinutesParsesToScheduled() {
        var before = Instant.now();
        var spec = ScheduleShorthandParser.parse("30m");
        assertEquals(Task.Type.SCHEDULED, spec.type());
        assertNotNull(spec.scheduledAt());
        // ~30 minutes in the future
        long diffSecs = spec.scheduledAt().getEpochSecond() - before.getEpochSecond();
        assertTrue(diffSecs >= 30 * 60 - 5 && diffSecs <= 30 * 60 + 5,
                "expected ~1800s offset, got " + diffSecs + "s");
        assertEquals("30m", spec.scheduleDisplay());
    }

    @Test
    void bareDurationHoursParsesToScheduled() {
        var before = Instant.now();
        var spec = ScheduleShorthandParser.parse("2h");
        assertEquals(Task.Type.SCHEDULED, spec.type());
        long diffSecs = spec.scheduledAt().getEpochSecond() - before.getEpochSecond();
        assertTrue(diffSecs >= 2 * 3600 - 5 && diffSecs <= 2 * 3600 + 5,
                "expected ~7200s offset, got " + diffSecs + "s");
    }

    @Test
    void bareDurationDaysParsesToScheduled() {
        var before = Instant.now();
        var spec = ScheduleShorthandParser.parse("1d");
        assertEquals(Task.Type.SCHEDULED, spec.type());
        long diffSecs = spec.scheduledAt().getEpochSecond() - before.getEpochSecond();
        assertTrue(diffSecs >= 86400 - 5 && diffSecs <= 86400 + 5,
                "expected ~86400s offset, got " + diffSecs + "s");
    }

    @Test
    void everyDurationParsesToInterval() {
        var spec = ScheduleShorthandParser.parse("every 30m");
        assertEquals(Task.Type.INTERVAL, spec.type());
        assertEquals(Long.valueOf(30 * 60), spec.intervalSeconds());
        assertNull(spec.scheduledAt());
        assertNull(spec.cronExpression());
        assertEquals("every 30m", spec.scheduleDisplay());
    }

    @Test
    void everyHoursParsesToInterval() {
        var spec = ScheduleShorthandParser.parse("every 2h");
        assertEquals(Task.Type.INTERVAL, spec.type());
        assertEquals(Long.valueOf(2 * 3600), spec.intervalSeconds());
    }

    @Test
    void everyDaysParsesToInterval() {
        var spec = ScheduleShorthandParser.parse("every 1d");
        assertEquals(Task.Type.INTERVAL, spec.type());
        assertEquals(Long.valueOf(86400), spec.intervalSeconds());
    }

    @Test
    void springSixFieldCronParsesToCron() {
        // "0 0 9 * * *" = at 09:00:00 every day, Spring 6-field
        var spec = ScheduleShorthandParser.parse("0 0 9 * * *");
        assertEquals(Task.Type.CRON, spec.type());
        assertEquals("0 0 9 * * *", spec.cronExpression());
        assertNull(spec.scheduledAt());
        assertNull(spec.intervalSeconds());
        assertEquals("0 0 9 * * *", spec.scheduleDisplay());
    }

    @Test
    void atShortcutsRouteThroughCron() {
        for (var sc : new String[] { "@hourly", "@daily", "@weekly", "@monthly", "@yearly" }) {
            var spec = ScheduleShorthandParser.parse(sc);
            assertEquals(Task.Type.CRON, spec.type(), "shortcut " + sc + " should route to CRON");
            assertEquals(sc, spec.cronExpression(), "shortcut " + sc + " should be stored verbatim");
        }
    }

    @Test
    void unixFiveFieldRejectedWithPrependHint() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ScheduleShorthandParser.parse("0 9 * * *"));
        // Hint should call out the 5-field count and suggest the fix.
        assertTrue(ex.getMessage().contains("5 fields"));
        assertTrue(ex.getMessage().contains("'0 0 9 * * *'"));
    }

    @Test
    void emptyInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleShorthandParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ScheduleShorthandParser.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> ScheduleShorthandParser.parse(null));
    }

    @Test
    void absoluteDatetimeWithOffsetParsesToScheduledOneShot() {
        var spec = ScheduleShorthandParser.parse("2026-06-13T15:00+08:00");
        assertEquals(Task.Type.SCHEDULED, spec.type(),
                "an absolute date-time is a one-shot (SCHEDULED -> PENDING), not a recurring cron");
        assertEquals(OffsetDateTime.parse("2026-06-13T15:00+08:00").toInstant(), spec.scheduledAt());
        assertNull(spec.cronExpression());
        assertNull(spec.intervalSeconds());
        assertEquals("2026-06-13T15:00+08:00", spec.scheduleDisplay());
    }

    @Test
    void absoluteLocalDatetimeResolvedInGivenZone() {
        var tokyo = ZoneId.of("Asia/Tokyo");
        var spec = ScheduleShorthandParser.parse("2026-06-13T15:00", tokyo);
        assertEquals(Task.Type.SCHEDULED, spec.type());
        assertEquals(LocalDateTime.parse("2026-06-13T15:00").atZone(tokyo).toInstant(),
                spec.scheduledAt(), "a bare local date-time is interpreted in the supplied zone");
    }

    @Test
    void absoluteLocalDatetimeWithSecondsAndSpaceSeparator() {
        var spec = ScheduleShorthandParser.parse("2026-06-13 15:00:00");
        assertEquals(Task.Type.SCHEDULED, spec.type());
        assertNotNull(spec.scheduledAt());
        assertNull(spec.cronExpression());
    }

    @Test
    void malformedAbsoluteDatetimeRejected() {
        // Looks date-time-shaped but month 13 / day 40 are invalid.
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleShorthandParser.parse("2026-13-40T15:00"));
    }

    @Test
    void dateSpecificCronStillParsesAsCron() {
        // Regression: "0 0 15 13 6 *" (15:00 on Jun 13) is a genuine cron and must
        // stay CRON — the one-off fix is the agent choosing an absolute date-time,
        // not the parser reinterpreting cron expressions.
        var spec = ScheduleShorthandParser.parse("0 0 15 13 6 *");
        assertEquals(Task.Type.CRON, spec.type());
        assertEquals("0 0 15 13 6 *", spec.cronExpression());
        assertNull(spec.scheduledAt());
    }

    @Test
    void invalidDurationSuffixRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ScheduleShorthandParser.parse("30s"));
        assertTrue(ex.getMessage().contains("30s"));
        // Message should call out the valid suffixes.
        assertTrue(ex.getMessage().contains("m"));
        assertTrue(ex.getMessage().contains("h"));
        assertTrue(ex.getMessage().contains("d"));
    }

    @Test
    void bareNumberRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ScheduleShorthandParser.parse("30"));
        assertTrue(ex.getMessage().contains("30"));
    }

    @Test
    void garbageRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleShorthandParser.parse("tomorrow"));
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleShorthandParser.parse("every blah"));
    }

    @Test
    void leadingTrailingWhitespaceStripped() {
        var spec = ScheduleShorthandParser.parse("  every 1h  ");
        assertEquals(Task.Type.INTERVAL, spec.type());
        assertEquals(Long.valueOf(3600), spec.intervalSeconds());
        // Display preserves the raw input including whitespace.
        assertEquals("  every 1h  ", spec.scheduleDisplay());
    }
}
