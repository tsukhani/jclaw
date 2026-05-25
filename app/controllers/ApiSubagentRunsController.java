package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.EventLog;
import models.SubagentRun;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.With;
import services.SubagentRegistry;
import utils.JpqlFilter;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final String KEY_REASON = "reason";
    private static final String KEY_RUN_ID = "run_id";

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
    public static void list(Long parentAgentId, Long parentConversationId,
                            String status, String since, String q,
                            Integer limit, Integer offset) {
        Instant sinceInstant = parseSinceFilter(since);
        if (sinceInstant == null && since != null && !since.isBlank()) return;

        SubagentRun.Status statusEnum = parseStatusFilter(status);
        if (statusEnum == null && status != null && !status.isBlank()) return;

        var filter = new JpqlFilter()
                .eq("parentAgent.id", parentAgentId)
                .eq("parentConversation.id", parentConversationId)
                .eq("status", statusEnum)
                .gte("startedAt", sinceInstant);

        // JCLAW-304: q resolves against TWO Lucene scopes and unions the
        // resulting run-id sets — SUBAGENT_RUN directly (label + outcome
        // virtual doc) plus CONVERSATION_MESSAGE → distinct child
        // conversation ids → SubagentRun ids whose childConversation
        // matches. Null when q is absent/blank (no FTS filter); empty
        // list when q matched nothing across both scopes (caller short-
        // circuits to zero rows); non-empty narrows via `r.id IN (:fts)`.
        var ftsRunIds = ftsSubagentRunIds(q);

        var where = filter.toWhereClause();
        if (ftsRunIds != null) {
            if (ftsRunIds.isEmpty()) {
                response.setHeader("X-Total-Count", "0");
                response.setHeader("Access-Control-Expose-Headers", "X-Total-Count");
                renderJSON("[]");
            }
            where = where.isEmpty() ? "id IN (:fts)" : where + " AND id IN (:fts)";
        }

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 100;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        // Switch to JPA.em().createQuery so we can bind the named :fts
        // parameter alongside JpqlFilter's positional ?1..?N values. The
        // legacy SubagentRun.find() shorthand only supports positional
        // params and JCLAW-304's IN-list shape would have to expand to a
        // variable number of positional placeholders — uglier, and the
        // direct-JPA path matches what ApiConversationsController and
        // ApiTasksController already do for the same q-intersection
        // pattern.
        var jpql = where.isEmpty()
                ? "SELECT r FROM SubagentRun r ORDER BY r.startedAt DESC"
                : "SELECT r FROM SubagentRun r WHERE " + where + " ORDER BY r.startedAt DESC";
        var jpaQ = JPA.em().createQuery(jpql, SubagentRun.class);
        var params = filter.paramList();
        for (int i = 0; i < params.size(); i++) {
            jpaQ.setParameter(i + 1, params.get(i));
        }
        if (ftsRunIds != null) jpaQ.setParameter("fts", ftsRunIds);

        String countJpql = where.isEmpty()
                ? "SELECT COUNT(r) FROM SubagentRun r"
                : "SELECT COUNT(r) FROM SubagentRun r WHERE " + where;
        var countQ = JPA.em().createQuery(countJpql, Long.class);
        for (int i = 0; i < params.size(); i++) {
            countQ.setParameter(i + 1, params.get(i));
        }
        if (ftsRunIds != null) countQ.setParameter("fts", ftsRunIds);
        long total = countQ.getSingleResult();

        List<SubagentRun> runs = jpaQ.setFirstResult(effectiveOffset)
                .setMaxResults(effectiveLimit).getResultList();

        response.setHeader("X-Total-Count", String.valueOf(total));
        response.setHeader("Access-Control-Expose-Headers", "X-Total-Count");

        // Bulk-lookup modes from the SUBAGENT_SPAWN events so we don't issue
        // one query per row. Cheap because the events table is indexed on
        // category, and the result-set size is bounded by `effectiveLimit`.
        Map<Long, String> modeByRunId = collectModesForRuns(runs);

        var result = runs.stream().map(r -> toView(r, modeByRunId)).toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * JCLAW-304: union of (a) direct SUBAGENT_RUN scope hits on the
     * label + outcome virtual document and (b) runs whose child
     * conversation transcript matches via the CONVERSATION_MESSAGE
     * scope. Returns null when q is null/blank (caller treats null as
     * "no FTS filter"). Returns an empty list when the union was empty
     * (caller short-circuits). Returns the union otherwise.
     *
     * <p>This double-scope union is the operationally useful shape
     * because subagent runs are the one entity that carries BOTH its
     * own narrative content AND a full transcript via the child
     * conversation. Operators rarely remember the run label; they
     * remember what went wrong inside the run.
     */
    private static List<Long> ftsSubagentRunIds(String q) {
        if (q == null || q.isBlank()) return null;
        try {
            var directIds = services.search.MessageSearch.searchIds(
                    services.search.LuceneIndexer.Scope.SUBAGENT_RUN, q, 500);
            var messageIds = services.search.MessageSearch.searchIds(
                    services.search.LuceneIndexer.Scope.CONVERSATION_MESSAGE, q, 500);

            Set<Long> union = new HashSet<>(directIds);
            if (!messageIds.isEmpty()) {
                @SuppressWarnings("unchecked")
                var transcriptRunIds = (List<Long>) JPA.em()
                        .createQuery("SELECT r.id FROM SubagentRun r WHERE r.childConversation.id IN "
                                + "(SELECT m.conversation.id FROM Message m WHERE m.id IN :ids)")
                        .setParameter("ids", messageIds)
                        .getResultList();
                union.addAll(transcriptRunIds);
            }
            return union.isEmpty() ? List.of() : List.copyOf(union);
        } catch (java.io.IOException e) {
            services.EventLogger.warn("search", null, null,
                    "FTS lookup failed for subagent runs q='%s': %s"
                            .formatted(q, e.getMessage()));
            return null;
        }
    }

    private static Instant parseSinceFilter(String since) {
        if (since == null || since.isBlank()) return null;
        try {
            return Instant.parse(since);
        } catch (Exception _) {
            response.status = 400;
            renderJSON(gson.toJson(new ErrorResponse(
                    "Invalid 'since' value '" + since + "' — expected ISO-8601 instant.")));
            return null;
        }
    }

    private static SubagentRun.Status parseStatusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return SubagentRun.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException _) {
            response.status = 400;
            renderJSON(gson.toJson(new ErrorResponse(
                    "Invalid 'status' value '" + status
                            + "' — expected one of RUNNING / COMPLETED / FAILED / KILLED / TIMEOUT.")));
            return null;
        }
    }

    private static SubagentRunView toView(SubagentRun r, Map<Long, String> modeByRunId) {
        return new SubagentRunView(
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
    }

    /**
     * POST /api/subagent-runs/{id}/kill — kill a running subagent. Routes
     * through {@link SubagentRegistry#kill} so the behavior matches the
     * {@code /subagent kill} slash command exactly: idempotent for terminal
     * rows, emits SUBAGENT_KILL on success, returns the resulting message
     * in either case.
     */
    /**
     * DELETE /api/subagent-runs/{id} — remove a subagent run row and the
     * child agent it spawned (which cascades to the child conversation,
     * its messages, the run row itself, and any other rows the agent
     * delete chain touches).
     *
     * <p>Restricted to terminal runs so an active stream isn't left
     * pointing at a deleted row. Operators wanting to drop a RUNNING
     * row should kill it first via {@link #kill(Long)} and then delete.
     *
     * <p>Cleanup happens by delegating to {@link services.AgentService#delete}
     * on the child Agent — the child is unique to the run (post the
     * JCLAW spawn fix that stopped reusing pre-existing operator agents
     * as children), and {@code AgentService.delete}'s SubagentRun cascade
     * sweeps the run row as part of the agent's FK chain.
     */
    @ApiResponse(responseCode = "200")
    public static void delete(Long id) {
        if (id == null) {
            response.status = 400;
            renderJSON(gson.toJson(new ErrorResponse("Missing run id.")));
            return;
        }
        var run = (SubagentRun) SubagentRun.findById(id);
        if (run == null) {
            response.status = 404;
            renderJSON(gson.toJson(new ErrorResponse("Run " + id + " not found.")));
            return;
        }
        // Reject mid-flight rows — the stream owner still holds a reference
        // and would NPE on the next persist. Kill-then-delete is the
        // two-step path for live runs.
        if (run.status == SubagentRun.Status.RUNNING) {
            response.status = 409;
            renderJSON(gson.toJson(new ErrorResponse(
                    "Run " + id + " is still RUNNING. Kill it first, then delete.")));
            return;
        }
        var childAgent = run.childAgent;
        if (childAgent == null) {
            // Defensive — schema says NOT NULL but historical rows from
            // pre-FK-tightening might exist. Drop just the run row in
            // that case so the operator isn't stuck with an orphan.
            run.delete();
            renderJSON("{\"status\":\"deleted\",\"id\":" + id + "}");
            return;
        }
        services.AgentService.delete(childAgent);
        renderJSON("{\"status\":\"deleted\",\"id\":" + id + "}");
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = KillResponse.class)))
    public static void kill(Long id) {
        if (id == null) {
            response.status = 400;
            renderJSON(gson.toJson(new ErrorResponse("Missing run id.")));
            return;
        }
        String reason = "Killed by operator via admin page";
        var body = JsonBodyReader.readJsonBody();
        if (body != null && body.has(KEY_REASON) && !body.get(KEY_REASON).isJsonNull()) {
            var supplied = body.get(KEY_REASON).getAsString();
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
            applySpawnEventMode(ev, idSet, result);
        }
        return result;
    }

    /** Parse one SUBAGENT_SPAWN details payload and populate the run→mode map (most-recent wins). */
    private static void applySpawnEventMode(EventLog ev, java.util.Set<Long> idSet, Map<Long, String> result) {
        if (ev.details == null) return;
        try {
            var obj = JsonParser.parseString(ev.details).getAsJsonObject();
            if (!obj.has(KEY_RUN_ID) || obj.get(KEY_RUN_ID).isJsonNull()) return;
            Long parsedId = parseLongOrNull(obj.get(KEY_RUN_ID).getAsString());
            if (parsedId == null) return;
            if (!idSet.contains(parsedId)) return;
            if (result.containsKey(parsedId)) return; // keep first (most recent due to ORDER BY)
            if (obj.has("mode") && !obj.get("mode").isJsonNull()) {
                result.put(parsedId, obj.get("mode").getAsString());
            }
        } catch (Exception _) {
            // Malformed details — skip, the row simply won't carry a mode.
        }
    }

    /** Long.parseLong wrapped to return null on malformed input — keeps applySpawnEventMode free of nested try blocks. */
    private static Long parseLongOrNull(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
