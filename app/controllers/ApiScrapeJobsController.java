package controllers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.ScrapeJob;
import models.ScrapeJobPage;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.scrape.ScrapeJobFiles;
import services.scrape.ScrapeJobService;
import tools.scrape.ScrapeJobRequest;
import utils.ApiResponses;
import utils.JpqlFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static controllers.AgentAccess.Level.OWN_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Background scrape jobs (JCLAW-1272): start one from the operator's form, watch it, read its pages,
 * download the combined file, stop it, delete it. An agent starts a job through {@code web_scrape}
 * rather than here, and reaches only its own jobs; {@code main} reaches every agent's.
 */
@With(AuthCheck.class)
public class ApiScrapeJobsController extends Controller {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_PAGE_LIMIT = 100;
    private static final int MAX_PAGE_LIMIT = 500;
    /** Names the form field a 400 is about, beside the message, so the form can mark that field. */
    private static final String FIELD = "field";
    private static final String AGENT_ID = "agentId";
    private static final String STATE = "state";

    /**
     * @param options      the request the job runs with, as {@code web_scrape} arguments
     * @param combinedFile the workspace path of the file combining every page, once it exists
     */
    public record ScrapeJobView(Long id, Long agentId, String agentName, @Nullable Long conversationId,
                                String url, String state, int pagesRead, int pagesFetched, int pagesDiscovered,
                                @Nullable String stopReason, @Nullable String errorMessage, @Nullable String summary,
                                String folder, @Nullable String combinedFile, JsonObject options,
                                String createdAt, @Nullable String startedAt, @Nullable String completedAt) {

        static ScrapeJobView of(ScrapeJob job) {
            var options = JsonParser.parseString(job.options).getAsJsonObject();
            var format = ScrapeJobRequest.fromJson(job.options).output().format();
            var combined = ScrapeJobFiles.combinedFile(job.id, format);
            return new ScrapeJobView(job.id, job.agent.id, job.agent.name,
                    job.conversation == null ? null : job.conversation.id,
                    job.url, job.state.name(), job.pagesRead, job.pagesFetched, job.pagesDiscovered,
                    job.stopReason, job.errorMessage, job.summary, ScrapeJobFiles.folder(job.id),
                    ScrapeJobFiles.existing(job.agent.name, combined) == null ? null : combined, options,
                    job.createdAt.toString(), iso(job.startedAt), iso(job.completedAt));
        }
    }

    /** @param hasContent false when the page was not retrieved, or its file has since gone */
    public record ScrapeJobPageView(Long id, int index, String url, int depth, String servedBy, String outcome,
                                    @Nullable String reason, int chars, boolean hasContent, String fetchedAt) {

        static ScrapeJobPageView of(ScrapeJobPage page) {
            return new ScrapeJobPageView(page.id, page.pageIndex, page.url, page.crawlDepth, page.servedBy.name(),
                    page.outcome.name(), page.reason, page.chars, page.file != null, page.fetchedAt.toString());
        }
    }

    public record ScrapeJobPageContent(Long id, String url, String format, String content) {}

    /** The New scrape form's body: {@code web_scrape}'s arguments plus the agent that owns the results. */
    public record ScrapeJobStart(Long agentId, String url, @Nullable Integer maxPages, @Nullable Integer maxDepth,
                                 @Nullable Integer maxMinutes, @Nullable Boolean sameHostOnly,
                                 @Nullable Boolean respectRobots, @Nullable Boolean seedFromSitemap,
                                 @Nullable String language, @Nullable String format, @Nullable JsonObject extract,
                                 @Nullable Boolean metadata) {}

    private static @Nullable String iso(java.time.@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** GET /api/scrape-jobs — newest first. */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ScrapeJobView.class))))
    @Operation(summary = "List background scrape jobs, newest first, filtered by agent and state")
    @AgentAccess(value = OWN_ONLY,
            reason = "the agent filter is pinned to the caller before the query runs (JCLAW-1272)")
    public static void list(Long agentId, String state, Integer limit, Integer offset) {
        var filter = new JpqlFilter()
                .eq("agent.id", scopedAgentId(agentId))
                .eq(STATE, parseState(state));
        long total = ScrapeJob.count(filter.toWhereClause(), filter.params());
        List<ScrapeJob> jobs = ScrapeJob.find(filter.toWhereClause() + " ORDER BY id DESC", filter.params())
                .from(offset == null || offset < 0 ? 0 : offset)
                .fetch(limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT));
        response.setHeader("X-Total-Count", String.valueOf(total));
        response.setHeader("Access-Control-Expose-Headers", "X-Total-Count");
        renderJSON(GSON.toJson(jobs.stream().map(ScrapeJobView::of).toList()));
    }

    /** GET /api/scrape-jobs/{id} */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ScrapeJobView.class)))
    @Operation(summary = "Get one background scrape job with its counts and options")
    @AgentAccess(value = OWN_ONLY, reason = "one job the calling agent owns; main reaches every agent's (JCLAW-1272)")
    public static void show(Long id) {
        renderJSON(GSON.toJson(ScrapeJobView.of(requireJob(id))));
    }

    /** GET /api/scrape-jobs/{id}/pages?after=N — the pages after index N, in the order they were read. */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ScrapeJobPageView.class))))
    @Operation(summary = "List a scrape job's pages in the order they were read, after a cursor")
    @AgentAccess(value = OWN_ONLY, reason = "one job the calling agent owns; main reaches every agent's (JCLAW-1272)")
    public static void pages(Long id, Integer after, Integer limit) {
        var job = requireJob(id);
        List<ScrapeJobPage> pages = ScrapeJobPage.find("job = ?1 AND pageIndex > ?2 ORDER BY pageIndex",
                        job, after == null ? 0 : after)
                .fetch(limit == null || limit <= 0 ? DEFAULT_PAGE_LIMIT : Math.min(limit, MAX_PAGE_LIMIT));
        renderJSON(GSON.toJson(pages.stream().map(ScrapeJobPageView::of).toList()));
    }

    /** GET /api/scrape-jobs/{id}/pages/{pageId}/content */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ScrapeJobPageContent.class)))
    @Operation(summary = "Get one scraped page's content as text")
    @AgentAccess(value = OWN_ONLY, reason = "one job the calling agent owns; main reaches every agent's (JCLAW-1272)")
    public static void pageContent(Long id, Long pageId) {
        var job = requireJob(id);
        ScrapeJobPage page = ScrapeJobPage.findById(pageId);
        if (page == null || !page.job.id.equals(job.id)) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "Scrape job %d has no page %d.".formatted(id, pageId));
            throw ApiResponses.unreachable();
        }
        var file = page.file == null ? null : ScrapeJobFiles.existing(job.agent.name, page.file);
        if (file == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "Page %d has no content: it was not retrieved, "
                    .formatted(pageId) + "or its file was deleted.");
            throw ApiResponses.unreachable();
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ApiResponses.error(500, ApiResponses.IO_ERROR, "Could not read page %d: %s".formatted(pageId, e.getMessage()));
            throw ApiResponses.unreachable();
        }
        var format = ScrapeJobRequest.fromJson(job.options).output().format();
        renderJSON(GSON.toJson(new ScrapeJobPageContent(page.id, page.url,
                format.name().toLowerCase(Locale.ROOT), content)));
    }

    /** GET /api/scrape-jobs/{id}/download — the file combining every page, as an attachment. */
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/plain",
            schema = @Schema(implementation = String.class)))
    @Operation(summary = "Download the file combining every page of a scrape job")
    @AgentAccess(value = OWN_ONLY, reason = "one job the calling agent owns; main reaches every agent's (JCLAW-1272)")
    public static void download(Long id) {
        var job = requireJob(id);
        var format = ScrapeJobRequest.fromJson(job.options).output().format();
        var path = ScrapeJobFiles.combinedFile(job.id, format);
        var file = ScrapeJobFiles.existing(job.agent.name, path);
        if (file == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND,
                    "Scrape job %d has no combined file yet; it is written when the job ends.".formatted(id));
            throw ApiResponses.unreachable();
        }
        var name = file.getFileName().toString();
        renderBinary(file.toFile(), "scrape-" + job.id + name.substring(name.indexOf('.')));
    }

    /** POST /api/scrape-jobs — the operator's New scrape form. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ScrapeJobView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ScrapeJobStart.class)))
    @Operation(summary = "Start a background scrape job for an agent; its limits may exceed the agent ceilings")
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "an agent starts a job through web_scrape, whose limits are clamped to the ceilings")
    public static void start() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "The request body must be a JSON object.");
            throw ApiResponses.unreachable();
        }
        var agent = requireAgent(body);
        ScrapeJobRequest request;
        try {
            request = ScrapeJobRequest.forOperator(body);
        } catch (IllegalArgumentException e) {
            var message = String.valueOf(e.getMessage());
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, message, FIELD, message.split("[ :]", 2)[0]);
            throw ApiResponses.unreachable();
        }
        var job = ScrapeJobService.submit(agent, null, request, false);
        renderJSON(GSON.toJson(ScrapeJobView.of(ScrapeJob.findById(job.id))));
    }

    /** POST /api/scrape-jobs/{id}/cancel */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ScrapeJobView.class)))
    @Operation(summary = "Stop a waiting or running scrape job; one running finishes the fetches in flight")
    @AgentAccess(value = OWN_ONLY, reason = "one job the calling agent owns; main reaches every agent's (JCLAW-1272)")
    public static void cancel(Long id) {
        var job = requireJob(id);
        switch (ScrapeJobService.cancel(job.id)) {
            case NOT_FOUND -> {
                notFound();
                throw ApiResponses.unreachable();
            }
            case ALREADY_FINISHED -> ApiResponses.error(409, ApiResponses.CONFLICT,
                    "Scrape job %d has already ended (%s).".formatted(id, job.state));
            // The same managed row the service changed: this request's transaction is the one it joined.
            case STOPPING, CANCELLED -> renderJSON(GSON.toJson(ScrapeJobView.of(job)));
        }
    }

    /** DELETE /api/scrape-jobs/{id} */
    @Operation(summary = "Delete a finished scrape job, its page records and its workspace folder")
    @AgentAccess(value = OWN_ONLY, reason = "one job the calling agent owns; main reaches every agent's (JCLAW-1272)")
    public static void delete(Long id) {
        var job = requireJob(id);
        switch (ScrapeJobService.delete(job.id)) {
            case NOT_FOUND -> {
                notFound();
                throw ApiResponses.unreachable();
            }
            case NOT_FINISHED -> ApiResponses.error(409, ApiResponses.CONFLICT,
                    "Scrape job %d is still %s; stop it before deleting it.".formatted(id,
                            job.state.name().toLowerCase(Locale.ROOT)));
            case DELETED -> ApiResponses.ok();
        }
    }

    private static Agent requireAgent(JsonObject body) {
        var raw = body.get(AGENT_ID);
        Agent agent = null;
        try {
            if (raw != null && !raw.isJsonNull()) agent = Agent.findById(raw.getAsLong());
        } catch (RuntimeException _) {
            agent = null;
        }
        if (agent == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "agentId must name an existing agent.",
                    FIELD, AGENT_ID);
            throw ApiResponses.unreachable();
        }
        return agent;
    }

    private static ScrapeJob.@Nullable State parseState(@Nullable String state) {
        if (state == null || state.isBlank()) return null;
        try {
            return ScrapeJob.State.valueOf(state.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Unknown state '%s'.".formatted(state),
                    FIELD, STATE);
            throw ApiResponses.unreachable();
        }
    }

    /** The agent a list is scoped to: the caller itself, unless it is {@code main} or the operator. */
    private static @Nullable Long scopedAgentId(@Nullable Long requested) {
        if (!RequestPrincipal.isAgentOriginated()) return requested;
        var caller = RequestPrincipal.callingAgent();
        if (caller == null) {
            ApiResponses.error(403, ApiResponses.AGENT_SCOPE,
                    "This request is agent-originated but names no agent, so it cannot be scoped.");
            throw ApiResponses.unreachable();
        }
        return caller.isMain() ? requested : caller.id;
    }

    /** The addressed job, or the request ends: 404 when it does not exist, 403 when another agent owns it. */
    private static ScrapeJob requireJob(Long id) {
        ScrapeJob job = id == null ? null : ScrapeJob.findById(id);
        if (job == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        if (!RequestPrincipal.mayReachAgentScopedRow(job.agent)) {
            ApiResponses.error(403, ApiResponses.AGENT_SCOPE, "Scrape job " + id + " belongs to another agent.");
        }
        return job;
    }
}
