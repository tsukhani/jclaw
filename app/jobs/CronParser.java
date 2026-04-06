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

        var start = LocalDateTime.ofInstant(after, ZoneId.systemDefault())
                .plusMinutes(1)
                .withSecond(0)
                .withNano(0);

        // Search up to 366 days ahead
        for (var candidate = start; candidate.isBefore(start.plusDays(366));
             candidate = candidate.plusMinutes(1)) {

            if (matches(candidate.getMinute(), minuteSpec)
                    && matches(candidate.getHour(), hourSpec)
                    && matches(candidate.getDayOfMonth(), domSpec)
                    && matches(candidate.getMonthValue(), monthSpec)
                    && matchesDow(candidate.getDayOfWeek().getValue() % 7, dowSpec)) {
                return candidate.atZone(ZoneId.systemDefault()).toInstant();
            }
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
