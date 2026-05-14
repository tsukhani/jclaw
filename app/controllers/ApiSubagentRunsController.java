package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.EventLog;
import models.SubagentRun;
import play.mvc.Controller;
import play.mvc.With;
import services.SubagentRegistry;
import utils.JpqlFilter;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static utils.GsonHolder.INSTANCE;

/**
 * JCLAW-271: REST surface for the SubagentRuns admin page. Lists
 * {@link SubagentRun} rows with optional filters (parent_agent_id, status,
 * since) and offers a single-purpose kill endpoint that delegates to the
 * shared {@link SubagentRegistry} kill primitive — same code path as the
 * {@code /subagent kill} slash command, so an operator gets identical
 * semantics regardless of where they invoke from.
 *
 * <p>Mode is read from the most recent {@code SUBAGENT_SPAWN} EventLog row
 * for each run id, since {@link SubagentRun} doesn't carry mode as a column
 * (the typed {@link services.EventLogger#recordSubagentSpawn} helper stashes
 * it in the JSON details payload). Best-effort: when no event row exists
 * (test fixtures that bypass the spawn tool, JVM restart that pre-dated
 * JCLAW-272's emission), mode is rendered as null.
 */
@With(AuthCheck.class)
public class ApiSubagentRunsController extends Controller {

    private static final Gson gson = INSTANCE;

    @SuppressWarnings("java:S107") // each query param is its own filter axis; bundling into a DTO would hide them from OpenAPI generation
    public record SubagentRunView(Long id, Long parentAgentId, String parentAgentName,
                                  Long childAgentId, String childAgentName,
                                  Long parentConversationId, Long childConversationId,
                                  String mode, String status, String startedAt,
                                  String endedAt, String outcome) {}

    public record KillRequest(String reason) {}

    public record KillResponse(boolean killed, String status, String message) {}

    public record ErrorResponse(String error) {}

    /**
     * GET /api/subagent-runs — list runs with optional filters.
     *
     * <p>Filter shape mirrors {@link ApiLogsController#list} for consistency:
     * each param is independently optional, AND-ed together at the DB layer
     * via {@link JpqlFilter}. {@code since} is parsed as ISO-8601; a
     * malformed value returns 400 rather than silently dropping the filter
     * (operators would otherwise see a broader result set than they asked
     * for and assume the filter applied).
     *
     * <p>Pagination follows the existing convention: capped at 500,
     * defaults to 100, surfaced as {@code X-Total-Count} header for
     * client-side range display.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SubagentRunView.class))))
    public static void list(Long parentAgentId, String status, String since,
                            Integer limit, Integer offset) {
        Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = Instant.parse(since);
            } catch (Exception e) {
                response.status = 400;
                renderJSON(gson.toJson(new ErrorResponse(
                        "Invalid 'since' value '" + since + "' — expected ISO-8601 instant.")));
                return;
            }
        }

        SubagentRun.Status statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = SubagentRun.Status.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                response.status = 400;
                renderJSON(gson.toJson(new ErrorResponse(
                        "Invalid 'status' value '" + status
                                + "' — expected one of RUNNING / COMPLETED / FAILED / KILLED / TIMEOUT.")));
                return;
            }
        }

        var filter = new JpqlFilter()
                .eq("parentAgent.id", parentAgentId)
                .eq("status", statusEnum)
                .gte("startedAt", sinceInstant);

        var where = filter.toWhereClause();
        var jpql = where.isEmpty()
                ? "ORDER BY startedAt DESC"
                : where + " ORDER BY startedAt DESC";

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 100;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        long total = where.isEmpty()
                ? SubagentRun.count()
                : SubagentRun.count(where, filter.params());
        @SuppressWarnings("unchecked")
        List<SubagentRun> runs = SubagentRun.find(jpql, filter.params())
                .from(effectiveOffset).fetch(effectiveLimit);

        response.setHeader("X-Total-Count", String.valueOf(total));
        response.setHeader("Access-Control-Expose-Headers", "X-Total-Count");

        // Bulk-lookup modes from the SUBAGENT_SPAWN events so we don't issue
        // one query per row. Cheap because the events table is indexed on
        // category, and the result-set size is bounded by `effectiveLimit`.
        Map<Long, String> modeByRunId = collectModesForRuns(runs);

        var result = runs.stream().map(r -> {
            var view = new SubagentRunView(
                    r.id,
                    r.parentAgent != null ? r.parentAgent.id : null,
                    r.parentAgent != null ? r.parentAgent.name : null,
                    r.childAgent != null ? r.childAgent.id : null,
                    r.childAgent != null ? r.childAgent.name : null,
                    r.parentConversation != null ? r.parentConversation.id : null,
                    r.childConversation != null ? r.childConversation.id : null,
                    modeByRunId.get(r.id),
                    r.status != null ? r.status.name() : null,
                    r.startedAt != null ? r.startedAt.toString() : null,
                    r.endedAt != null ? r.endedAt.toString() : null,
                    r.outcome
            );
            return view;
        }).toList();

        renderJSON(gson.toJson(result));
    }

    /**
     * POST /api/subagent-runs/{id}/kill — kill a running subagent. Routes
     * through {@link SubagentRegistry#kill} so the behavior matches the
     * {@code /subagent kill} slash command exactly: idempotent for terminal
     * rows, emits SUBAGENT_KILL on success, returns the resulting message
     * in either case.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = KillResponse.class)))
    public static void kill(Long id) {
        if (id == null) {
            response.status = 400;
            renderJSON(gson.toJson(new ErrorResponse("Missing run id.")));
            return;
        }
        String reason = "Killed by operator via admin page";
        var body = JsonBodyReader.readJsonBody();
        if (body != null && body.has("reason") && !body.get("reason").isJsonNull()) {
            var supplied = body.get("reason").getAsString();
            if (supplied != null && !supplied.isBlank()) reason = supplied;
        }
        var result = SubagentRegistry.kill(id, reason);
        if (!result.killed() && result.finalStatus() == null) {
            // Row not found — surface as 404 so the admin page can present
            // it as a stale-cache hint rather than a generic failure.
            response.status = 404;
            renderJSON(gson.toJson(new ErrorResponse(result.message())));
            return;
        }
        renderJSON(gson.toJson(new KillResponse(
                result.killed(),
                result.finalStatus() != null ? result.finalStatus().name() : null,
                result.message())));
    }

    // ----- internals -----

    private static Map<Long, String> collectModesForRuns(List<SubagentRun> runs) {
        if (runs.isEmpty()) return Map.of();
        var ids = new java.util.ArrayList<Long>(runs.size());
        for (var r : runs) {
            if (r.id != null) ids.add(r.id);
        }
        if (ids.isEmpty()) return Map.of();

        // Pull every SUBAGENT_SPAWN event whose details JSON has a matching
        // run_id. We accept a wide LIKE plus an in-Java filter because the
        // run_id values in the payload are quoted strings; constructing a
        // JPQL IN-clause that matches "run_id":"<numeric>" for many ids
        // requires N OR-ed LIKEs and gets ugly fast. The event count for
        // this page is bounded (limit param caps the run-list size), and
        // SUBAGENT_SPAWN is a slim category — the scan is cheap.
        @SuppressWarnings("unchecked")
        List<EventLog> events = EventLog.find(
                "category = ?1 ORDER BY timestamp DESC",
                "SUBAGENT_SPAWN").fetch(Math.max(500, ids.size() * 4));
        var idSet = new java.util.HashSet<>(ids);
        var result = new HashMap<Long, String>();
        for (var ev : events) {
            if (ev.details == null) continue;
            try {
                var obj = JsonParser.parseString(ev.details).getAsJsonObject();
                if (!obj.has("run_id") || obj.get("run_id").isJsonNull()) continue;
                Long parsedId;
                try {
                    parsedId = Long.parseLong(obj.get("run_id").getAsString());
                } catch (NumberFormatException _) { continue; }
                if (!idSet.contains(parsedId)) continue;
                if (result.containsKey(parsedId)) continue; // keep first (most recent due to ORDER BY)
                if (obj.has("mode") && !obj.get("mode").isJsonNull()) {
                    result.put(parsedId, obj.get("mode").getAsString());
                }
            } catch (Exception _) {
                // Malformed details — skip, the row simply won't carry a mode.
            }
        }
        return result;
    }
}
