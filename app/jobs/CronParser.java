package jobs;

import java.time.*;
import java.time.temporal.ChronoUnit;

/**
 * Simple cron expression parser for next execution time computation.
 * Supports standard 5-field cron: minute hour day-of-month month day-of-week
 * Supports: numbers, '*', '/' (step), ',' (list)
 */
public class CronParser {

    public static Instant nextExecution(String cron) {
        return nextExecution(cron, Instant.now());
    }

    public static Instant nextExecution(String cron, Instant after) {
        var parts = cron.trim().split("\\s+");
        if (parts.length < 5) return null;

        var minuteSpec = parts[0];
        var hourSpec = parts[1];
        var domSpec = parts[2];
        var monthSpec = parts[3];
        var dowSpec = parts[4];

        var candidate = LocalDateTime.ofInstant(after, ZoneId.systemDefault())
                .plusMinutes(1)
                .withSecond(0)
                .withNano(0);

        var end = candidate.plusDays(366);

        // Search up to 366 days ahead with coarse-granularity skipping
        while (candidate.isBefore(end)) {

            // Skip entire months that don't match
            if (!matches(candidate.getMonthValue(), monthSpec)) {
                candidate = candidate.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
                continue;
            }

            // Skip entire days where day-of-month or day-of-week don't match
            if (!matches(candidate.getDayOfMonth(), domSpec)
                    || !matchesDow(candidate.getDayOfWeek().getValue() % 7, dowSpec)) {
                candidate = candidate.plusDays(1).withHour(0).withMinute(0);
                continue;
            }

            // Skip hours that don't match
            if (!matches(candidate.getHour(), hourSpec)) {
                candidate = candidate.plusHours(1).withMinute(0);
                continue;
            }

            // Check minute
            if (matches(candidate.getMinute(), minuteSpec)) {
                return candidate.atZone(ZoneId.systemDefault()).toInstant();
            }

            candidate = candidate.plusMinutes(1);
        }

        return null;
    }

    private static boolean matches(int value, String spec) {
        if ("*".equals(spec)) return true;

        for (var part : spec.split(",")) {
            if (part.contains("/")) {
                var stepParts = part.split("/");
                var base = "*".equals(stepParts[0]) ? 0 : Integer.parseInt(stepParts[0]);
                var step = Integer.parseInt(stepParts[1]);
                if ((value - base) >= 0 && (value - base) % step == 0) return true;
            } else {
                if (Integer.parseInt(part) == value) return true;
            }
        }
        return false;
    }

    private static boolean matchesDow(int value, String spec) {
        // 0 = Sunday in cron, Java DayOfWeek.getValue() % 7 gives 0=Sunday
        return matches(value, spec);
    }
}
