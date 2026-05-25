package services;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;

import java.time.Instant;
import java.time.ZoneId;

/**
 * JCLAW-294 cron migration: delegate cron parsing to db-scheduler's
 * {@link CronSchedule} so JClaw stops carrying its own parser.
 *
 * <p>db-scheduler's Spring-style 6-field cron format — seconds, minutes,
 * hours, day-of-month, month, day-of-week — supports the same surface
 * as Spring's {@code CronExpression}: ranges, step values (e.g.
 * "every 5 minutes" written as "0 STEP/5 * * * *"), comma lists, and
 * at-shortcuts ({@code @hourly}, {@code @daily}, {@code @weekly},
 * {@code @monthly}, {@code @yearly}). Operators can use any of these
 * directly.
 *
 * <h3>Why these helpers</h3>
 * {@link CronSchedule} is the right primitive but its
 * {@code getNextExecutionTime} requires an {@link ExecutionComplete}
 * — overkill for our "next fire after now" question.
 * {@link ExecutionComplete#simulatedSuccess(Instant)} bridges that
 * cleanly. Wrapping the call in {@link #nextExecution(String)} also
 * lets callers stay null-soft instead of catching parse exceptions.
 *
 * <h3>Unix 5-field rejection</h3>
 * {@link #validate(String)} catches the common Unix-style 5-field
 * input ({@code "0 9 * * *"}) and surfaces an actionable message
 * (prepend {@code 0 } for the seconds position). Without this, the
 * 5-field expression flows through CronSchedule's constructor and
 * fails with a generic parse error that doesn't tell the operator
 * what to do.
 */
public final class JClawCronUtils {

    private JClawCronUtils() {}

    /**
     * Compute the next fire instant after now for the given cron
     * expression in the JVM-default zone. Returns null on blank input
     * or any parse failure — callers (TaskSchedulingService.computeNextRunAt,
     * TaskExecutionHandler.scheduleCronNextCompletion) treat null as
     * "skip and log", matching the pre-migration behavior.
     *
     * <p>Prefer {@link #nextExecution(String, ZoneId)} for per-task
     * timezone support (JCLAW-261). This single-arg overload is kept
     * for legacy call sites that don't yet have a {@link models.Task}
     * context to resolve a zone from.
     */
    public static Instant nextExecution(String expr) {
        return nextExecution(expr, null);
    }

    /**
     * JCLAW-261: zone-aware variant. When {@code zone} is non-null, the
     * cron expression is interpreted in that zone — so {@code "0 0 9 * * *"}
     * fires at 09:00 wall-clock in the supplied zone, regardless of the
     * JVM default. A null zone falls back to {@code ZoneId.systemDefault()}
     * (the same behavior as the no-zone constructor).
     */
    public static Instant nextExecution(String expr, ZoneId zone) {
        if (expr == null || expr.isBlank()) return null;
        try {
            var schedule = zone != null
                    ? new CronSchedule(expr, zone)
                    : new CronSchedule(expr);
            return schedule.getNextExecutionTime(
                    ExecutionComplete.simulatedSuccess(Instant.now()));
        } catch (RuntimeException _) {
            // Bad expression — caller logs and skips via null check.
            return null;
        }
    }

    /**
     * Validate a cron expression at the API/tool boundary. Throws
     * {@link IllegalArgumentException} with an actionable message
     * for the two most common operator mistakes: empty input and
     * Unix 5-field expressions. Pass-through for valid Spring 6-field
     * and at-shortcuts.
     */
    public static void validate(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Cron expression is empty");
        }
        var trimmed = expr.strip();
        // @-shortcuts are single-token; numeric expressions have 6 fields.
        if (!trimmed.startsWith("@")) {
            var fields = trimmed.split("\\s+");
            if (fields.length == 5) {
                throw new IllegalArgumentException(
                        ("Cron expression '%s' has 5 fields (Unix style). JClaw uses "
                                + "Spring-style 6-field cron — prepend '0 ' for the seconds "
                                + "position (e.g. '0 %s').").formatted(expr, expr));
            }
        }
        try {
            new CronSchedule(trimmed);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid cron expression '%s': %s".formatted(expr, e.getMessage()), e);
        }
    }
}
