package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Provides accurate date/time information and calculations.
 *
 * <p>The system prompt injects only the current <em>date</em> (not time) to
 * preserve prompt-cache stability. Skills that need the current time — or need
 * to do timezone conversions or date arithmetic — should call this tool instead
 * of relying on the LLM to guess.
 */
public class DateTimeTool implements ToolRegistry.Tool {

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' hh:mm a");
    private static final DateTimeFormatter TIME_ONLY_FMT =
            DateTimeFormatter.ofPattern("hh:mm a");

    @Override
    public String name() { return "datetime"; }

    @Override
    public String description() {
        return """
                Get the current date and time, convert between timezones, or perform date/time \
                calculations. This is a single tool with an 'action' parameter. \
                Use action="now" to get the current date and time in a given timezone (default: server timezone). \
                Use action="convert" to convert a timestamp from one timezone to another. \
                Use action="calculate" to add or subtract a duration from a timestamp, or compute the difference \
                between two timestamps.""";
    }

    @Override
    public String summary() {
        return "Get current date/time, convert timezones, calculate durations via the 'action' parameter: now, convert, calculate.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("now", "convert", "calculate"),
                                "description", "The action to perform"),
                        "timezone", Map.of("type", "string",
                                "description", "IANA timezone (e.g. 'Asia/Kuala_Lumpur', 'America/New_York'). Defaults to server timezone."),
                        "timestamp", Map.of("type", "string",
                                "description", "ISO-8601 timestamp for convert/calculate (e.g. '2026-04-13T09:15:00')"),
                        "fromTimezone", Map.of("type", "string",
                                "description", "Source timezone for convert action"),
                        "toTimezone", Map.of("type", "string",
                                "description", "Target timezone for convert action"),
                        "amount", Map.of("type", "integer",
                                "description", "Amount to add (positive) or subtract (negative) for calculate"),
                        "unit", Map.of("type", "string",
                                "enum", List.of("minutes", "hours", "days", "weeks", "months", "years"),
                                "description", "Unit for the amount in calculate"),
                        "endTimestamp", Map.of("type", "string",
                                "description", "Second timestamp for computing the difference between two times")
                ),
                "required", List.of("action")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get("action").getAsString();

        return switch (action) {
            case "now" -> now(args);
            case "convert" -> convert(args);
            case "calculate" -> calculate(args);
            default -> "Error: Unknown action '%s'. Use: now, convert, calculate".formatted(action);
        };
    }

    private String now(com.google.gson.JsonObject args) {
        var zone = resolveZone(args, "timezone");
        var now = ZonedDateTime.now(zone);
        return formatResult(now);
    }

    private String convert(com.google.gson.JsonObject args) {
        if (!args.has("timestamp")) return "Error: 'timestamp' is required for convert";

        var fromZone = resolveZone(args, "fromTimezone");
        var toZone = resolveZone(args, "toTimezone");

        try {
            var ldt = LocalDateTime.parse(args.get("timestamp").getAsString());
            var source = ldt.atZone(fromZone);
            var target = source.withZoneSameInstant(toZone);
            return "Converted: %s (%s) → %s (%s)".formatted(
                    DISPLAY_FMT.format(source), fromZone.getId(),
                    DISPLAY_FMT.format(target), toZone.getId());
        } catch (DateTimeParseException e) {
            return "Error: Invalid timestamp format. Use ISO-8601: YYYY-MM-ddTHH:mm:ss";
        }
    }

    private String calculate(com.google.gson.JsonObject args) {
        var zone = resolveZone(args, "timezone");

        // Difference between two timestamps
        if (args.has("endTimestamp")) {
            return computeDifference(args, zone);
        }

        // Add/subtract duration
        if (!args.has("amount") || !args.has("unit")) {
            return "Error: 'amount' and 'unit' are required for calculate (or provide 'endTimestamp' for difference)";
        }

        var timestamp = args.has("timestamp")
                ? args.get("timestamp").getAsString()
                : null;
        var amount = args.get("amount").getAsInt();
        var unit = args.get("unit").getAsString();

        try {
            var base = timestamp != null
                    ? LocalDateTime.parse(timestamp).atZone(zone)
                    : ZonedDateTime.now(zone);

            var result = switch (unit) {
                case "minutes" -> base.plusMinutes(amount);
                case "hours" -> base.plusHours(amount);
                case "days" -> base.plusDays(amount);
                case "weeks" -> base.plusWeeks(amount);
                case "months" -> base.plusMonths(amount);
                case "years" -> base.plusYears(amount);
                default -> throw new IllegalArgumentException("Unknown unit: " + unit);
            };

            return "Result: %s %+d %s → %s".formatted(
                    DISPLAY_FMT.format(base), amount, unit, formatResult(result));
        } catch (DateTimeParseException e) {
            return "Error: Invalid timestamp format. Use ISO-8601: YYYY-MM-ddTHH:mm:ss";
        }
    }

    private String computeDifference(com.google.gson.JsonObject args, ZoneId zone) {
        try {
            var startStr = args.has("timestamp")
                    ? args.get("timestamp").getAsString()
                    : ZonedDateTime.now(zone).toLocalDateTime().toString();
            var endStr = args.get("endTimestamp").getAsString();

            var start = LocalDateTime.parse(startStr).atZone(zone).toInstant();
            var end = LocalDateTime.parse(endStr).atZone(zone).toInstant();
            var duration = Duration.between(start, end);

            var days = duration.toDays();
            var hours = duration.toHoursPart();
            var minutes = duration.toMinutesPart();

            return "Difference: %d days, %d hours, %d minutes (%d total hours)"
                    .formatted(days, hours, minutes, duration.toHours());
        } catch (DateTimeParseException e) {
            return "Error: Invalid timestamp format. Use ISO-8601: YYYY-MM-ddTHH:mm:ss";
        }
    }

    private ZoneId resolveZone(com.google.gson.JsonObject args, String field) {
        if (args.has(field) && !args.get(field).isJsonNull()) {
            var tz = args.get(field).getAsString();
            if (!tz.isBlank()) {
                try {
                    return ZoneId.of(tz);
                } catch (Exception _) {
                    // Fall through to default
                }
            }
        }
        return ZoneId.systemDefault();
    }

    private String formatResult(ZonedDateTime zdt) {
        return "%s | %s | timezone: %s | iso: %s".formatted(
                DISPLAY_FMT.format(zdt),
                TIME_ONLY_FMT.format(zdt),
                zdt.getZone().getId(),
                zdt.toOffsetDateTime().toString());
    }
}
