package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.TaskRunMessage;
import play.mvc.Controller;
import play.mvc.With;
import services.search.MessageSearch;
import utils.ApiResponses;

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
     * One hit from the transcript search: the matched
     * {@link TaskRunMessage} content + role plus enough parent context
     * (task id/name, taskRun id) for the UI to link back.
     */
    private record TranscriptSearchHit(Long messageId, String role, String content,
                                        String createdAt, Long taskRunId,
                                        Long taskId, String taskName,
                                        Long agentId, String agentName) {
        static TranscriptSearchHit of(TaskRunMessage m) {
            var run = m.taskRun;
            var task = run != null ? run.task : null;
            var agent = task != null ? task.agent : null;
            return new TranscriptSearchHit(
                    m.id,
                    m.role != null ? m.role.name() : null,
                    m.content,
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
     * Query syntax accepts Lucene's standard QueryParser grammar:
     * phrase quoting, AND/OR/NOT, and prefix wildcards.
     *
     * <p>Empty {@code q} returns {@code []} — the search facade
     * intentionally non-exceptional on empty input so the UI can render
     * an empty results panel before the operator types.
     */
    // S1181: Throwable is required — Lucene API removals surface as NoClassDefFoundError / LinkageError / AbstractMethodError.
    // S2259: Play 1.x halt methods (badRequest, notFound, etc.) throw a Result that Sonar can't see across the framework boundary.
    @SuppressWarnings({"java:S2259", "java:S1181"})
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TranscriptSearchHit.class))))
    @Operation(summary = "Full-text search task-run transcripts (q, limit) returning matching messages with task/run context")
    public static void searchTranscripts(String q, Integer limit) {
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;
        if (q == null || q.isBlank()) {
            renderJSON("[]");
        }
        try {
            var hits = MessageSearch.search(q, effectiveLimit);
            renderJSON(gson.toJson(hits.stream().map(TranscriptSearchHit::of).toList()));
        } catch (Throwable e) {
            // Throwable (not just Exception) because Lucene API removals
            // surface as NoClassDefFoundError / LinkageError / AbstractMethodError
            // — Error subclasses that escape a narrower catch and yield a
            // generic Play 500 page with no useful diagnostic. Log the full
            // stack so version-bump incompats are debuggable.
            ApiResponses.errorAndLog(e, 500, "search_failed",
                    "Search failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
