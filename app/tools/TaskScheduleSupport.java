package tools;

import models.Task;
import services.ScheduleShorthandParser;
import services.TimezoneResolver;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;

/**
 * JCLAW-726: the schedule-parsing concern lifted out of {@link TaskTool} —
 * turning the LLM's {@code schedule} shorthand + optional {@code timezone}
 * into a {@link ScheduleShorthandParser.ScheduleSpec}, copying that spec onto a
 * {@link Task}, and formatting a scheduled fire instant for the agent-facing
 * response. Pure functions over their arguments; no JSON / arg-accessor
 * coupling (the caller extracts the raw strings).
 */
final class TaskScheduleSupport {

    private TaskScheduleSupport() {}

    /**
     * Parse the {@code schedule} shorthand in the effective timezone. The zone
     * is resolved up front (per-request {@code timezone} → operator default →
     * JVM default via {@link TimezoneResolver}) so an absolute date-time
     * schedule ("2026-06-13T15:00") is interpreted in the same zone the task is
     * saved with. Throws {@link IllegalArgumentException} on a malformed
     * schedule (the caller maps it to a tool error).
     */
    static ScheduleShorthandParser.ScheduleSpec parse(String scheduleShorthand, String timezone) {
        var zone = TimezoneResolver.resolve(timezone);
        return ScheduleShorthandParser.parse(scheduleShorthand, zone);
    }

    /**
     * JCLAW-261: validate an IANA zone id. Returns the trimmed value when
     * valid, null when absent or blank. Throws {@link IllegalArgumentException}
     * on an invalid value so the caller can surface a clear tool error.
     */
    static String parseTimezone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var trimmed = raw.trim();
        try {
            ZoneId.of(trimmed);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "Invalid IANA timezone '" + trimmed + "': " + e.getMessage()
                            + ". Use a value like 'America/New_York' or 'Asia/Tokyo'.");
        }
        return trimmed;
    }

    /** Copy a parsed ScheduleSpec onto a Task (5 derived fields + nextRunAt). */
    static void applyScheduleSpec(Task task, ScheduleShorthandParser.ScheduleSpec spec) {
        task.type = spec.type();
        // Re-derive status from the (possibly new) type, but ONLY when the
        // task is still alive — terminal states (COMPLETED, FAILED, CANCELLED,
        // LOST) must not get resurrected to PENDING/ACTIVE by a schedule edit.
        if (task.status == Task.Status.PENDING || task.status == Task.Status.ACTIVE) {
            task.status = Task.initialStatusFor(spec.type());
        }
        task.scheduledAt = spec.scheduledAt();
        task.cronExpression = spec.cronExpression();
        task.intervalSeconds = spec.intervalSeconds();
        task.scheduleDisplay = spec.scheduleDisplay();
        task.nextRunAt = spec.scheduledAt() != null ? spec.scheduledAt() : Instant.now();
    }

    /**
     * Format a SCHEDULED task's fire instant in the task's effective IANA zone
     * (per-task override → operator default → JVM default via
     * {@link TimezoneResolver}). The returned string is a
     * {@link java.time.ZonedDateTime#toString() ZonedDateTime ISO-8601
     * representation} — local time + offset + bracketed zone, e.g.
     * {@code 2026-05-26T23:17:31.538+08:00[Asia/Kuala_Lumpur]} — so the agent
     * can't silently re-label the UTC {@code Z}-suffixed
     * {@link java.time.Instant#toString() Instant.toString()} as the operator's
     * local zone.
     */
    static String formatScheduledAt(Task task) {
        var zone = TimezoneResolver.resolve(task);
        return task.scheduledAt.atZone(zone).toString();
    }
}
