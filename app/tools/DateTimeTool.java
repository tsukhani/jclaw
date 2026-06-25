package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import agents.ToolAction;
import com.google.gson.JsonObject;

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

    // Action names dispatched in execute()
    private static final String ACTION_NOW = "now";
    private static final String ACTION_CONVERT = "convert";
    private static final String ACTION_CALCULATE = "calculate";

    // JSON argument keys
    private static final String ARG_ACTION = "action";
    private static final String ARG_TIMEZONE = "timezone";
    private static final String ARG_TIMESTAMP = "timestamp";
    private static final String ARG_AMOUNT = "amount";
    private static final String ARG_END_TIMESTAMP = "endTimestamp";

    private static final String ERR_INVALID_TIMESTAMP =
            "Error: Invalid timestamp format. Use ISO-8601: YYYY-MM-ddTHH:mm:ss";

    @Override
    public String name() { return "datetime"; }

    @Override
    public String category() { return "Utilities"; }

    @Override
    public String icon() { return "clock"; }

    @Override
    public String shortDescription() {
        return "Get the current time, convert between timezones, and calculate date differences.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction(ACTION_NOW,       "Return the current date and time in the specified timezone"),
                new ToolAction(ACTION_CONVERT,   "Convert a timestamp from one timezone to another"),
                new ToolAction(ACTION_CALCULATE, "Add or subtract a duration from a timestamp, or compute the difference between two timestamps")
        );
    }

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
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_ACTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of(ACTION_NOW, ACTION_CONVERT, ACTION_CALCULATE),
                                SchemaKeys.DESCRIPTION, "The action to perform"),
                        ARG_TIMEZONE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "IANA timezone (e.g. 'Asia/Kuala_Lumpur', 'America/New_York'). Defaults to server timezone."),
                        ARG_TIMESTAMP, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "ISO-8601 timestamp for convert/calculate (e.g. '2026-04-13T09:15:00')"),
                        "fromTimezone", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Source timezone for convert action"),
                        "toTimezone", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Target timezone for convert action"),
                        ARG_AMOUNT, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION, "Amount to add (positive) or subtract (negative) for calculate"),
                        "unit", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of("minutes", "hours", "days", "weeks", "months", "years"),
                                SchemaKeys.DESCRIPTION, "Unit for the amount in calculate"),
                        ARG_END_TIMESTAMP, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Second timestamp for computing the difference between two times")
                ),
                SchemaKeys.REQUIRED, List.of(ARG_ACTION)
        );
    }

    /** Pure compute — no I/O, no shared state. Safe for parallel calls. */
    @Override public boolean parallelSafe() { return true; }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        // The LLM sometimes calls datetime without the (schema-required) "action"
        // — e.g. {} or just a timezone — which unambiguously means "give me the
        // current time". Default to "now" rather than NPE on the missing key
        // (a missing-action NPE otherwise traps the agent in a retry loop).
        var action = args.has(ARG_ACTION) && !args.get(ARG_ACTION).isJsonNull()
                ? args.get(ARG_ACTION).getAsString()
                : ACTION_NOW;

        return switch (action) {
            case ACTION_NOW -> now(args);
            case ACTION_CONVERT -> convert(args);
            case ACTION_CALCULATE -> calculate(args);
            default -> "Error: Unknown action '%s'. Use: now, convert, calculate".formatted(action);
        };
    }

    private String now(JsonObject args) {
        var zone = resolveZone(args, ARG_TIMEZONE);
        var now = ZonedDateTime.now(zone);
        return formatResult(now);
    }

    private String convert(JsonObject args) {
        if (!args.has(ARG_TIMESTAMP)) return "Error: 'timestamp' is required for convert";

        var fromZone = resolveZone(args, "fromTimezone");
        var toZone = resolveZone(args, "toTimezone");

        try {
            var ldt = LocalDateTime.parse(args.get(ARG_TIMESTAMP).getAsString());
            var source = ldt.atZone(fromZone);
            var target = source.withZoneSameInstant(toZone);
            return "Converted: %s (%s) → %s (%s)".formatted(
                    DISPLAY_FMT.format(source), fromZone.getId(),
                    DISPLAY_FMT.format(target), toZone.getId());
        } catch (DateTimeParseException _) {
            return ERR_INVALID_TIMESTAMP;
        }
    }

    private String calculate(JsonObject args) {
        var zone = resolveZone(args, ARG_TIMEZONE);

        // Difference between two timestamps
        if (args.has(ARG_END_TIMESTAMP)) {
            return computeDifference(args, zone);
        }

        // Add/subtract duration
        if (!args.has(ARG_AMOUNT) || !args.has("unit")) {
            return "Error: 'amount' and 'unit' are required for calculate (or provide 'endTimestamp' for difference)";
        }

        var timestamp = args.has(ARG_TIMESTAMP)
                ? args.get(ARG_TIMESTAMP).getAsString()
                : null;
        var amount = args.get(ARG_AMOUNT).getAsInt();
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
        } catch (DateTimeParseException _) {
            return ERR_INVALID_TIMESTAMP;
        }
    }

    private String computeDifference(JsonObject args, ZoneId zone) {
        try {
            var startStr = args.has(ARG_TIMESTAMP)
                    ? args.get(ARG_TIMESTAMP).getAsString()
                    : ZonedDateTime.now(zone).toLocalDateTime().toString();
            var endStr = args.get(ARG_END_TIMESTAMP).getAsString();

            var start = LocalDateTime.parse(startStr).atZone(zone).toInstant();
            var end = LocalDateTime.parse(endStr).atZone(zone).toInstant();
            var duration = Duration.between(start, end);

            var days = duration.toDays();
            var hours = duration.toHoursPart();
            var minutes = duration.toMinutesPart();

            return "Difference: %d days, %d hours, %d minutes (%d total hours)"
                    .formatted(days, hours, minutes, duration.toHours());
        } catch (DateTimeParseException _) {
            return ERR_INVALID_TIMESTAMP;
        }
    }

    private ZoneId resolveZone(JsonObject args, String field) {
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
