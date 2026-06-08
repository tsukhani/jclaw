package services;

import models.Task;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * JCLAW-294: parse the operator-friendly {@code schedule} string into a
 * concrete {@link Task.Type} plus the type-specific fire-time field.
 *
 * <p>Replaces the old typed quartet of TaskTool actions
 * ({@code createTask} / {@code scheduleTask} /
 * {@code scheduleRecurringTask} / {@code scheduleIntervalTask}) with a
 * single {@code schedule} parameter and removes the matching three
 * typed body fields ({@code executionTime}, {@code cronExpression},
 * {@code intervalSeconds}) from the API surface. Both the chat tool
 * and the upcoming POST / PATCH endpoints route here.
 *
 * <h2>Accepted forms</h2>
 * <table border="1">
 *   <caption>Schedule shorthand → {@link Task.Type} + derived fields</caption>
 *   <tr><th>Input</th><th>Type</th><th>Sets</th></tr>
 *   <tr><td>{@code "now"} (case-insensitive)</td><td>IMMEDIATE</td><td>scheduledAt = now</td></tr>
 *   <tr><td>{@code "30m"}, {@code "2h"}, {@code "1d"}</td><td>SCHEDULED</td><td>scheduledAt = now + duration</td></tr>
 *   <tr><td>{@code "2026-06-13T15:00"} (ISO date-time, optional seconds / offset)</td><td>SCHEDULED</td><td>scheduledAt = that instant (a bare local form is resolved in the task zone)</td></tr>
 *   <tr><td>{@code "every 30m"}, {@code "every 2h"}, {@code "every 1d"}</td><td>INTERVAL</td><td>intervalSeconds = duration</td></tr>
 *   <tr><td>Spring 6-field cron, or {@code @hourly}/{@code @daily}/{@code @weekly}/{@code @monthly}/{@code @yearly}</td><td>CRON</td><td>cronExpression = input</td></tr>
 * </table>
 *
 * <p>The original input string is preserved in {@link ScheduleSpec#scheduleDisplay}
 * for round-trip rendering — operators see the same form they typed,
 * not a normalized echo.
 *
 * <h2>Duration suffixes</h2>
 * Only {@code m} (minutes), {@code h} (hours), {@code d} (days) per the
 * spec. No {@code s} (seconds — too short to be useful for scheduled
 * tasks) and no {@code w} / {@code y} (use cron). Single-unit only —
 * no {@code 1h30m} composite, by design.
 *
 * <h2>Cron detection heuristic</h2>
 * A token starting with {@code @} OR a string with 5+ whitespace-
 * separated tokens is treated as cron. The 5-token bound catches
 * Unix 5-field expressions so {@link JClawCronUtils#validate} can
 * surface the "prepend 0" hint instead of failing as an unknown
 * shorthand. Five-or-six tokens followed by cron-validation is
 * deterministic for everything in the spec.
 */
public final class ScheduleShorthandParser {

    private static final Pattern DURATION = Pattern.compile("^(\\d+)([mhd])$");

    /** Leading ISO date-time (date, then 'T' or space, then HH:mm) — an
     *  absolute one-off SCHEDULED fire rather than a recurring cron. */
    private static final Pattern ABS_DATETIME =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{1,2}:\\d{2}");

    private ScheduleShorthandParser() {}

    /**
     * Bundle returned to callers — exactly the four fields the Task
     * entity stores for scheduling.
     *
     * @param type            resolved {@link Task.Type} (IMMEDIATE,
     *                        SCHEDULED, INTERVAL, or CRON)
     * @param scheduledAt     concrete fire instant for IMMEDIATE / SCHEDULED;
     *                        {@code null} for INTERVAL / CRON
     * @param cronExpression  Spring 6-field cron expression for CRON;
     *                        {@code null} otherwise
     * @param intervalSeconds interval duration in seconds for INTERVAL;
     *                        {@code null} otherwise
     * @param scheduleDisplay operator's raw input verbatim — preserved so
     *                        round-trip rendering shows the form the
     *                        operator typed, not a normalised echo
     */
    public record ScheduleSpec(
            Task.Type type,
            Instant scheduledAt,
            String cronExpression,
            Long intervalSeconds,
            String scheduleDisplay) {}

    /**
     * Parse a schedule shorthand string. Throws
     * {@link IllegalArgumentException} on any input that doesn't
     * match an accepted form; callers surface this as a 400 at the
     * API boundary.
     */
    public static ScheduleSpec parse(String input) {
        // Pass null so the default zone is resolved lazily — only an absolute
        // local date-time needs it, so a pure form ("now"/"30m"/cron) stays
        // free of the ConfigService lookup currentDefault() performs.
        return parse(input, null);
    }

    /**
     * Zone-aware parse. {@code zone} resolves only an absolute <em>local</em>
     * date-time (no offset) into a concrete {@link Instant}; every other form
     * is zone-independent. A {@code null} zone falls back to
     * {@link TimezoneResolver#currentDefault()} (resolved lazily, only when an
     * absolute local date-time is actually present). Callers that know the
     * task's timezone (the chat tool, the create/update endpoints) pass it so
     * "{@code 2026-06-13T15:00}" means 3 pm in the user's zone, not the server's.
     */
    public static ScheduleSpec parse(String input, ZoneId zone) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Schedule expression is empty");
        }
        var trimmed = input.strip();
        var lower = trimmed.toLowerCase();

        if ("now".equals(lower)) {
            return new ScheduleSpec(Task.Type.IMMEDIATE, Instant.now(), null, null, input);
        }

        if (lower.startsWith("every ")) {
            var durPart = trimmed.substring(6).strip();
            long secs = parseDurationSeconds(durPart);
            return new ScheduleSpec(Task.Type.INTERVAL, null, null, secs, input);
        }

        // Absolute one-off date/time -> SCHEDULED one-shot (fires once, then
        // COMPLETED). Detected before the cron heuristic so a space-separated
        // form ("2026-06-13 15:00") isn't mistaken for a multi-token cron.
        if (ABS_DATETIME.matcher(trimmed).lookingAt()) {
            var when = parseAbsoluteDatetime(trimmed,
                    zone != null ? zone : TimezoneResolver.currentDefault());
            return new ScheduleSpec(Task.Type.SCHEDULED, when, null, null, input);
        }

        // Cron path: @-shortcut OR multi-token whitespace expression
        // (5 tokens for legacy Unix detection, 6 for Spring). Both go
        // through JClawCronUtils.validate which handles the 5-token
        // case with the prepend-0 hint.
        if (trimmed.startsWith("@") || trimmed.split("\\s+").length >= 5) {
            JClawCronUtils.validate(trimmed);
            return new ScheduleSpec(Task.Type.CRON, null, trimmed, null, input);
        }

        // Bare duration: SCHEDULED at now + duration.
        long secs = parseDurationSeconds(trimmed);
        return new ScheduleSpec(Task.Type.SCHEDULED, Instant.now().plusSeconds(secs), null, null, input);
    }

    /**
     * Resolve an absolute ISO date-time to an {@link Instant}. An explicit
     * offset ({@code 2026-06-13T15:00+08:00} or a trailing {@code Z}) is
     * honoured as-is; a bare local date-time ({@code 2026-06-13T15:00}, with
     * optional seconds and a {@code T} or space separator) is interpreted in
     * {@code zone}. Anything else throws with a form hint.
     */
    private static Instant parseAbsoluteDatetime(String raw, ZoneId zone) {
        var iso = raw.replaceFirst(" ", "T");
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException _) {
            // No explicit offset — fall through and resolve as a local time.
        }
        try {
            return LocalDateTime.parse(iso).atZone(zone).toInstant();
        } catch (DateTimeParseException _) {
            throw new IllegalArgumentException(
                    ("Unrecognized date-time '%s'. Use an ISO form like "
                            + "'2026-06-13T15:00' (interpreted in the task's timezone) or "
                            + "with an explicit offset like '2026-06-13T15:00+08:00'.")
                            .formatted(raw));
        }
    }

    private static long parseDurationSeconds(String s) {
        var m = DURATION.matcher(s.toLowerCase());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    ("Unrecognized duration '%s'. Use a number followed by 'm' "
                            + "(minutes), 'h' (hours), or 'd' (days). Examples: "
                            + "'30m', '2h', '1d'.").formatted(s));
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "m" -> n * 60L;
            case "h" -> n * 3600L;
            case "d" -> n * 86400L;
            default -> throw new IllegalStateException("unreachable suffix: " + m.group(2));
        };
    }
}
