package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.EventLog;
import play.mvc.Controller;
import play.mvc.With;
import utils.ApiResponses;
import utils.JpqlFilter;

import java.time.Instant;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

@With(AuthCheck.class)
public class ApiLogsController extends Controller {

    private static final Gson gson = INSTANCE;

    public record LogEntry(Long id, String timestamp, String level, String category,
                           String agentId, String channel, String message, String details) {}

    public record LogListResponse(List<LogEntry> events, int limit, int offset) {}

    // S107: nine @QueryParam args is the Play 1.x action-method convention —
    // each maps 1:1 to a request query parameter the operator may set
    // independently. Bundling into a DTO would force callers (and OpenAPI
    // generation) to flatten on the wire anyway.
    @SuppressWarnings("java:S107")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = LogListResponse.class)))
    public static void list(String category, String level, String agentId, String channel,
                            String since, String until, String search,
                            Integer limit, Integer offset) {
        var filter = new JpqlFilter()
                .eq("category", category)
                .eq("level", level)
                .eq("agentId", agentId)
                .eq("channel", channel)
                .gte("timestamp", parseInstantFilter("since", since))
                .lte("timestamp", parseInstantFilter("until", until))
                .like("LOWER(message)", search != null && !search.isBlank() ? "%" + search.toLowerCase() + "%" : null);

        var where = filter.toWhereClause();
        var query = where.isEmpty() ? "ORDER BY timestamp DESC" : where + " ORDER BY timestamp DESC";

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 100;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        var jpql = EventLog.find(query, filter.params());
        List<EventLog> events = jpql.from(effectiveOffset).fetch(effectiveLimit);

        var entries = events.stream().map(e -> new LogEntry(
                e.id,
                e.timestamp.toString(),
                e.level,
                e.category,
                e.agentId,
                e.channel,
                e.message,
                e.details
        )).toList();

        renderJSON(gson.toJson(new LogListResponse(entries, effectiveLimit, effectiveOffset)));
    }

    /**
     * Parse an ISO-8601 {@code since}/{@code until} filter bound. Blank or absent
     * yields {@code null} (no bound). An unparseable value renders a 400 rather
     * than letting {@link Instant#parse}'s {@code DateTimeParseException} bubble
     * out as a 500 — mirroring {@code ApiSubagentRunsController.parseSinceFilter}.
     */
    private static Instant parseInstantFilter(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception _) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Invalid '" + name + "' value '" + value + "' — expected ISO-8601 instant.");
            return null;
        }
    }
}
