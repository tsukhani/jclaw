package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.EventLog;
import models.Message;
import models.SubagentRun;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.EventLogger;
import services.SubagentRegistry;
import services.search.LuceneIndexer;
import services.search.MessageSearch;
import tools.SubagentSpawnTool;
import utils.ApiResponses;
import utils.JpqlFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
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

    private static final String MISSING_RUN_ID = "Missing run id.";
    private static final Gson gson = INSTANCE;

    private static final String KEY_REASON = "reason";
    private static final String KEY_RUN_ID = "run_id";
    private static final String HDR_TOTAL_COUNT = "X-Total-Count";
    // Run "status" reused as a filter path, request-body field, and sort-whitelist key.
    private static final String STATUS = "status";

    @SuppressWarnings("java:S107") // each query param is its own filter axis; bundling into a DTO would hide them from OpenAPI generation
    public record SubagentRunView(Long id, Long parentAgentId, String parentAgentName,
                                  Long childAgentId, String childAgentName,
                                  Long parentConversationId, Long childConversationId,
                                  String mode, String status, String startedAt,
                                  String endedAt, String outcome, String workdir) {}

    public record KillRequest(String reason) {}

    public record KillResponse(boolean killed, String status, String message) {}

    /** JCLAW-662: one persisted coding-harness step in the replay transcript. */
    public record StepView(int seq, String kind, String text, String createdAt) {}

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
    @Operation(summary = "List subagent runs with optional filters and pagination")
    public static void list(Long parentAgentId, Long parentConversationId,
                            String status, String since, String q,
                            String sort, String dir,
                            Integer limit, Integer offset) {
        Instant sinceInstant = parseSinceFilter(since);
        if (sinceInstant == null && since != null && !since.isBlank()) return;

        SubagentRun.Status statusEnum = parseStatusFilter(status);
        if (statusEnum == null && status != null && !status.isBlank()) return;

        var filter = new JpqlFilter()
                .eq("parentAgent.id", parentAgentId)
                .eq("parentConversation.id", parentConversationId)
                .eq(STATUS, statusEnum)
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
                response.setHeader(HDR_TOTAL_COUNT, "0");
                response.setHeader("Access-Control-Expose-Headers", HDR_TOTAL_COUNT);
                renderJSON("[]");
            }
            where = where.isEmpty() ? "id IN (:fts)" : where + " AND id IN (:fts)";
        }

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, PagedJpqlQuery.MAX_LIMIT) : 100;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        // JCLAW-722: WHERE -> bind -> COUNT -> paginate assembled once. The named
        // :fts id-set (JCLAW-304) binds alongside JpqlFilter's positional ?1..?N
        // values through the same single binding path, so the COUNT and the SELECT
        // can never diverge.
        var page = PagedJpqlQuery.of(SubagentRun.class, "SubagentRun r", "r")
                .where(where)
                .positionalParams(filter.paramList())
                .namedParam("fts", ftsRunIds)
                .orderBy(orderByClause(sort, dir))
                .page(effectiveOffset, effectiveLimit)
                .execute();
        List<SubagentRun> runs = page.rows();

        response.setHeader(HDR_TOTAL_COUNT, String.valueOf(page.total()));
        response.setHeader("Access-Control-Expose-Headers", HDR_TOTAL_COUNT);

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
    @SuppressWarnings("java:S1168") // null vs empty-list is a deliberate tri-state: null = "no q filter, return all rows"; empty = "matched nothing, render zero rows"; non-empty = "narrow" (see callers in list())
    private static List<Long> ftsSubagentRunIds(String q) {
        if (q == null || q.isBlank()) return null;
        try {
            var directIds = MessageSearch.searchIds(
                    LuceneIndexer.Scope.SUBAGENT_RUN, q, 500);
            var messageIds = MessageSearch.searchIds(
                    LuceneIndexer.Scope.CONVERSATION_MESSAGE, q, 500);

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
        } catch (IOException e) {
            EventLogger.warn("search", null, null,
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
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Invalid 'since' value '" + since + "' — expected ISO-8601 instant.");
            return null;
        }
    }

    private static SubagentRun.Status parseStatusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return SubagentRun.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException _) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Invalid 'status' value '" + status
                            + "' — expected one of RUNNING / COMPLETED / FAILED / KILLED / TIMEOUT.");
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
                r.outcome,
                r.workdir
        );
    }

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
    @Operation(summary = "Delete a terminal subagent run and its child agent")
    public static void delete(Long id) {
        if (id == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, MISSING_RUN_ID);
            return;
        }
        var run = SubagentRegistry.findRunById(id);
        if (run == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "Run " + id + " not found.");
            return;
        }
        // Reject mid-flight rows — the stream owner still holds a reference
        // and would NPE on the next persist. Kill-then-delete is the
        // two-step path for live runs.
        if (run.status == SubagentRun.Status.RUNNING) {
            ApiResponses.error(409, ApiResponses.CONFLICT,
                    "Run " + id + " is still RUNNING. Kill it first, then delete.");
            return;
        }
        var childAgent = run.childAgent;
        if (childAgent == null) {
            // Defensive — schema says NOT NULL but historical rows from
            // pre-FK-tightening might exist. Drop just the run row in
            // that case so the operator isn't stuck with an orphan.
            run.delete();
            ApiResponses.ok("id", id);
            return;
        }
        AgentService.delete(childAgent);
        ApiResponses.ok("id", id);
    }

    /** Count of runs removed by a bulk delete. */
    public record DeletedCountResponse(int deleted) {}

    /**
     * DELETE /api/subagent-runs — bulk delete terminal runs, either by an
     * explicit {@code ids} array or by the same {@code filter} the list view
     * uses ("delete all matching"). Mirrors
     * {@link ApiConversationsController#deleteConversations}.
     *
     * <p>RUNNING rows are skipped, never rejected: a "delete all" over a mixed
     * set silently leaves live runs intact (kill-then-delete remains the path
     * for those, exactly as {@link #delete(Long)} enforces per-row). Each
     * removed run cascades through {@link services.AgentService#delete} on its
     * child agent, sweeping the child conversation, transcript, and run row.
     */
    @RequestBody(content = @Content(schema = @Schema(implementation = DeleteBulkRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DeletedCountResponse.class)))
    @Operation(summary = "Bulk delete terminal subagent runs by ids or filter (RUNNING rows are skipped)")
    public static void deleteBulk() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Missing request body.");
            return;
        }

        List<SubagentRun> targets;
        if (body.has("ids")) {
            targets = targetsFromIds(body);
        } else if (body.has("filter")) {
            var f = body.getAsJsonObject("filter");
            String statusRaw = stringField(f, STATUS);
            SubagentRun.Status statusEnum = parseStatusFilter(statusRaw);
            if (statusEnum == null && statusRaw != null && !statusRaw.isBlank()) return;
            String sinceRaw = stringField(f, "since");
            Instant sinceInstant = parseSinceFilter(sinceRaw);
            if (sinceInstant == null && sinceRaw != null && !sinceRaw.isBlank()) return;
            targets = findMatchingRuns(
                    longField(f, "parentAgentId"),
                    longField(f, "parentConversationId"),
                    statusEnum, sinceInstant, stringField(f, "q"));
        } else {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Provide 'ids' or 'filter'.");
            return;
        }

        renderJSON(gson.toJson(new DeletedCountResponse(deleteTerminalRuns(targets))));
    }

    /**
     * Resolve the explicit-ids delete set. An empty {@code ids} array yields an
     * empty list — the caller then reports 0 deleted, identical to the prior
     * inline fast-path, so no special-casing leaks back into {@link #deleteBulk()}.
     */
    private static List<SubagentRun> targetsFromIds(JsonObject body) {
        var ids = new ArrayList<Long>();
        for (var elem : body.getAsJsonArray("ids")) ids.add(elem.getAsLong());
        if (ids.isEmpty()) return List.of();
        return JPA.em()
                .createQuery("SELECT r FROM SubagentRun r WHERE r.id IN :ids", SubagentRun.class)
                .setParameter("ids", ids)
                .getResultList();
    }

    /**
     * Cascade-delete every terminal run in {@code targets}, returning the count
     * actually removed. RUNNING rows are skipped (kill-first), never rejected —
     * each removed run cascades through {@link services.AgentService#delete}.
     */
    private static int deleteTerminalRuns(List<SubagentRun> targets) {
        int deleted = 0;
        for (var run : targets) {
            if (run.status == SubagentRun.Status.RUNNING) continue; // live rows: kill first
            var childAgent = run.childAgent;
            if (childAgent == null) run.delete();
            else AgentService.delete(childAgent);
            deleted++;
        }
        return deleted;
    }

    /** Shape of the {@link #deleteBulk()} request body (ids OR filter). */
    public record DeleteBulkRequest(List<Long> ids, DeleteFilter filter) {
        public record DeleteFilter(Long parentAgentId, Long parentConversationId,
                                   String status, String since, String q) {}
    }

    /**
     * Resolve every run matching the list-view filter, unpaginated, for bulk
     * delete. Mirrors the WHERE-clause + FTS construction in {@link #list} so
     * "delete all matching" targets exactly the rows the operator sees.
     */
    private static List<SubagentRun> findMatchingRuns(Long parentAgentId, Long parentConversationId,
                                                      SubagentRun.Status status, Instant since, String q) {
        var filter = new JpqlFilter()
                .eq("parentAgent.id", parentAgentId)
                .eq("parentConversation.id", parentConversationId)
                .eq(STATUS, status)
                .gte("startedAt", since);
        var ftsRunIds = ftsSubagentRunIds(q);
        var where = filter.toWhereClause();
        if (ftsRunIds != null) {
            if (ftsRunIds.isEmpty()) return List.of();
            where = where.isEmpty() ? "id IN (:fts)" : where + " AND id IN (:fts)";
        }
        var jpql = where.isEmpty()
                ? "SELECT r FROM SubagentRun r"
                : "SELECT r FROM SubagentRun r WHERE " + where;
        var jpaQ = JPA.em().createQuery(jpql, SubagentRun.class);
        var params = filter.paramList();
        for (int i = 0; i < params.size(); i++) {
            jpaQ.setParameter(i + 1, params.get(i));
        }
        if (ftsRunIds != null) jpaQ.setParameter("fts", ftsRunIds);
        return jpaQ.getResultList();
    }

    /**
     * Map the table sort column + direction to an ORDER BY. Closed whitelist +
     * literal ASC/DESC, so no user input reaches the JPQL string. `mode` (from
     * spawn events) and `duration` (computed) aren't DB columns, so they're not
     * sortable server-side. Unknown/absent column → recency default (newest
     * first); a stable id tiebreak keeps paging deterministic.
     */
    private static String orderByClause(String sort, String dir) {
        String col = switch (sort == null ? "" : sort) {
            case "id" -> "r.id";
            case "parent" -> "r.parentAgent.name";
            case "child" -> "r.childAgent.name";
            case STATUS -> "r.status";
            case "started" -> "r.startedAt";
            default -> null;
        };
        if (col == null) return "ORDER BY r.startedAt DESC";
        String direction = "desc".equalsIgnoreCase(dir) ? "DESC" : "ASC";
        return "ORDER BY " + col + " " + direction + ", r.id ASC";
    }

    private static String stringField(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static Long longField(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : null;
    }

    /**
     * POST /api/subagent-runs/{id}/kill — kill a running subagent. Routes
     * through {@link SubagentRegistry#kill} so the behavior matches the
     * {@code /subagent kill} slash command exactly: idempotent for terminal
     * rows, emits SUBAGENT_KILL on success, returns the resulting message
     * in either case.
     */
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = KillRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = KillResponse.class)))
    @Operation(summary = "Kill a running subagent")
    public static void kill(Long id) {
        if (id == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, MISSING_RUN_ID);
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
            ApiResponses.error(404, ApiResponses.NOT_FOUND, result.message());
            return;
        }
        renderJSON(gson.toJson(new KillResponse(
                result.killed(),
                result.finalStatus() != null ? result.finalStatus().name() : null,
                result.message())));
    }

    /**
     * JCLAW-662: GET /api/subagent-runs/{id}/steps — the ordered, persisted step
     * transcript for a coding-harness run. Returns the child-Conversation
     * {@code codingrun_step} rows (stamped by
     * {@link SubagentSpawnTool#dispatchHarnessEvent}) oldest-first — insertion
     * order equals {@code seq} order since each step is persisted as it streams
     * in — so a client that reconnects mid-run can replay what it missed and then
     * resume tailing the live {@link services.NotificationBus} rail.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = StepView.class))))
    @Operation(summary = "Ordered persisted step transcript for a subagent coding run")
    public static void steps(Long id) {
        if (id == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, MISSING_RUN_ID);
            return;
        }
        var run = SubagentRegistry.findRunById(id);
        if (run == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "Run " + id + " not found.");
            return;
        }
        if (run.childConversation == null) {
            renderJSON("[]");
            return;
        }
        List<Message> rows = Message.find(
                "conversation = ?1 AND messageKind = ?2 ORDER BY id ASC",
                run.childConversation, SubagentSpawnTool.MESSAGE_KIND_CODINGRUN_STEP).fetch();
        var result = rows.stream().map(ApiSubagentRunsController::toStepView).toList();
        renderJSON(gson.toJson(result));
    }

    // ----- internals -----

    /** Project a persisted step row into its view — seq/kind live in the JSON metadata, text in content. */
    private static StepView toStepView(Message m) {
        int seq = 0;
        String kind = null;
        if (m.metadata != null) {
            try {
                var obj = JsonParser.parseString(m.metadata).getAsJsonObject();
                if (obj.has("seq") && !obj.get("seq").isJsonNull()) seq = obj.get("seq").getAsInt();
                if (obj.has("kind") && !obj.get("kind").isJsonNull()) kind = obj.get("kind").getAsString();
            } catch (Exception _) {
                // Malformed metadata — fall through with defaults; the row still
                // renders its content in transcript order.
            }
        }
        return new StepView(seq, kind, m.content,
                m.createdAt != null ? m.createdAt.toString() : null);
    }

    private static Map<Long, String> collectModesForRuns(List<SubagentRun> runs) {
        if (runs.isEmpty()) return Map.of();
        var ids = new ArrayList<Long>(runs.size());
        for (var r : runs) {
            if (r.id != null) ids.add(r.id);
        }
        if (ids.isEmpty()) return Map.of();

        // JCLAW-400: bound the SUBAGENT_SPAWN scan to this page's run-ids
        // instead of fetching a 500-row category slice and discarding the
        // rows that aren't on the page. The details payload renders the run
        // id as a quoted string (`"run_id":"<id>"`, see
        // EventLogger.subagentDetails), so one `details LIKE` per page run-id
        // ANDed onto the category filter narrows the DB result to only the
        // rows that can match. The run-id literals are numeric — they carry
        // no LIKE wildcards or special chars — so the only `%` are our own
        // anchoring ones, and the trailing quote in `"run_id":"<id>"` keeps
        // id 5 from matching id 50. The in-Java idSet/most-recent-wins parse
        // below is preserved verbatim so the run→mode mapping is identical.
        var likeClauses = new ArrayList<String>(ids.size());
        var params = new ArrayList<Object>(ids.size() + 1);
        params.add("SUBAGENT_SPAWN");
        int idx = 2;
        for (var id : ids) {
            likeClauses.add("details LIKE ?" + idx++);
            params.add("%\"" + KEY_RUN_ID + "\":\"" + id + "\"%");
        }
        String jpql = "category = ?1 AND (" + String.join(" OR ", likeClauses)
                + ") ORDER BY timestamp DESC";
        @SuppressWarnings("unchecked")
        List<EventLog> events = EventLog.find(jpql, params.toArray()).fetch();
        var idSet = new HashSet<>(ids);
        var result = new HashMap<Long, String>();
        for (var ev : events) {
            applySpawnEventMode(ev, idSet, result);
        }
        return result;
    }

    /** Parse one SUBAGENT_SPAWN details payload and populate the run→mode map (most-recent wins). */
    private static void applySpawnEventMode(EventLog ev, Set<Long> idSet, Map<Long, String> result) {
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
