package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.TaskRunMessage;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.search.MessageSearch;
import services.search.SearchSnippet;
import utils.ApiResponses;

import static controllers.AgentAccess.Level.OWN_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Task transcript full-text search API. Split out of {@code ApiTasksController}
 * (JCLAW-676); the URL path ({@code GET /api/task-runs/search}) is unchanged.
 *
 * <p>Single-operator scope: authenticated via {@link AuthCheck} with no
 * per-caller ownership check.
 */
@With(AuthCheck.class)
public class ApiTaskSearchController extends Controller {

    private static final Gson gson = GSON;

    /**
     * Most hits one search returns, and the default. With every field at its longest (a 255-character task name,
     * a 64-character agent name, 19-digit ids, a snippet of quotes JSON doubles) a hit runs to about 950
     * characters, so a full page comes to about 48,000, under {@link tools.JClawApiTool#MAX_RESPONSE_CHARS}; on
     * 2026-10-27 full contents made a page of 50 hits 961 KB.
     */
    public static final int MAX_LIMIT = 50;

    /**
     * One hit from the transcript search: the matched {@link TaskRunMessage}'s role and a
     * {@link SearchSnippet} of its content, plus enough parent context (task id/name, taskRun id)
     * for the UI to link back. The whole message comes from {@code GET /api/task-runs/{id}/messages}.
     */
    private record TranscriptSearchHit(Long messageId, @Nullable String role, String snippet,
                                        @Nullable String createdAt, @Nullable Long taskRunId,
                                        @Nullable Long taskId, @Nullable String taskName,
                                        @Nullable Long agentId, String agentName) {
        static TranscriptSearchHit of(TaskRunMessage m, String query) {
            var run = m.taskRun;
            var task = run != null ? run.task : null;
            var agent = task != null ? task.agent : null;
            return new TranscriptSearchHit(
                    m.id,
                    m.role != null ? m.role.wireValue() : null,
                    SearchSnippet.around(m.content, query),
                    m.createdAt != null ? m.createdAt.toString() : null,
                    run != null ? run.id : null,
                    task != null ? task.id : null,
                    task != null ? task.name : null,
                    agent != null ? agent.id : null,
                    Agent.nameOf(agent));
        }
    }

    /**
     * Full-text search across task transcripts. Routes through the
     * {@link services.search.MessageSearch} facade, which dispatches
     * to either the direct Lucene 10 backend (default) or Postgres
     * tsvector (operator opt-in via {@code -Djclaw.search.postgres-native=true}).
     * A hit holds every word of the query, each matched by its beginning
     * (a one-character word exactly). It is no QueryParser grammar: quotes and
     * {@code *} are dropped, AND/OR/NOT are words a hit must hold, and there is
     * no phrase match. An agent searches its own tasks' runs only; the operator
     * and {@code main} search every agent's (JF-284).
     *
     * <p>Empty {@code q} returns {@code []} — the search facade
     * intentionally non-exceptional on empty input so the UI can render
     * an empty results panel before the operator types.
     */
    // S1181: Throwable is required — Lucene API removals surface as NoClassDefFoundError / LinkageError / AbstractMethodError.
    // S2259: Play 1.x halt methods (badRequest, notFound, etc.) throw a Result that Sonar can't see across the framework boundary.
    @SuppressWarnings({"java:S2259", "java:S1181"})
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TranscriptSearchHit.class))))
    @Operation(summary = "Full-text search task-run transcripts (q, limit up to 50); each hit carries a snippet "
            + "around the match with task/run context, and the message's full text comes from "
            + "GET /api/task-runs/{id}/messages")
    @AgentAccess(value = OWN_ONLY, reason = "hits are pinned to the caller's own task runs before the limit (JF-284)")
    public static void searchTranscripts(String q, Integer limit) {
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, MAX_LIMIT) : MAX_LIMIT;
        var scoped = RequestPrincipal.scopedAgentId(null);
        if (q == null || q.isBlank()) {
            renderJSON("[]");
        }
        try {
            var hits = scoped == null
                    ? MessageSearch.search(q, effectiveLimit)
                    : MessageSearch.searchForAgent(q, scoped, effectiveLimit);
            renderJSON(gson.toJson(hits.stream().map(m -> TranscriptSearchHit.of(m, q)).toList()));
        } catch (Throwable e) {
            // Error subclasses escape a narrower catch as a generic Play 500 with no diagnostic; log the full stack.
            ApiResponses.errorAndLog(e, 500, ApiResponses.SEARCH_FAILED,
                    "Search failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
