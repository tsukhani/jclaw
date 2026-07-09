package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import play.mvc.Controller;
import play.mvc.With;
import services.EventLogger;
import services.TaskRunQueryService;
import services.TaskRunRegistry;
import services.TaskService;
import utils.ApiResponses;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * TaskRun API — the run-history read surface (per-task runs, per-run message
 * trace, the calendar/timeline recent-runs feed) plus the in-flight
 * run-cancel action. Split out of {@code ApiTasksController} (JCLAW-676) along
 * the {@code /api/task-runs/*} route group; every URL path is unchanged.
 *
 * <p>Single-operator scope: like the rest of the Tasks API, id-addressed
 * mutations authenticate via {@link AuthCheck} with no per-caller ownership
 * check (one admin owns every agent and task).
 */
@With(AuthCheck.class)
public class ApiTaskRunsController extends Controller {

    private static final Gson gson = INSTANCE;

    /**
     * Operator-facing view of one TaskRun row. Returns the audit fields
     * (status + timestamps + duration) plus the operator-relevant payload
     * (error, outputSummary, delivery state, trace). Verbose fields like
     * {@code error} and {@code traceJson} are returned raw — the UI
     * decides how to render or truncate them. Keeping the API honest
     * about what's there avoids a second round-trip for "show me the
     * full error" UX.
     */
    private record TaskRunView(Long id, String status,
                               String startedAt, String completedAt, Long durationMs,
                               String error, String outputSummary, String latestTurnPreview,
                               String deliveryStatus, String deliveryTarget, String deliveryError,
                               String traceJson, String createdAt) {
        static TaskRunView of(TaskRun r) {
            return new TaskRunView(
                    r.id,
                    r.status != null ? r.status.name() : null,
                    r.startedAt != null ? r.startedAt.toString() : null,
                    r.completedAt != null ? r.completedAt.toString() : null,
                    r.durationMs,
                    r.error,
                    r.outputSummary,
                    TaskRunQueryService.latestTurnPreviewFor(r),
                    r.deliveryStatus != null ? r.deliveryStatus.name() : null,
                    r.deliveryTarget,
                    r.deliveryError,
                    r.traceJson,
                    r.createdAt != null ? r.createdAt.toString() : null);
        }
    }

    /**
     * Operator-facing view of one {@code task_run_message} row — the
     * turn-by-turn execution trace surfaced in the Tasks UI PeekPanel
     * (JCLAW-22 slice P). Carries the LLM-visible turn fields (role,
     * content, reasoning, tool calls/results) plus the truncation flag.
     * The structured-only {@code toolResultStructured} column is omitted —
     * the agent never sees it and operators don't need it.
     */
    private record TaskRunMessageView(Long id, int turnIndex, String role, String content,
                                      String reasoning, String toolCalls, String toolResults,
                                      boolean truncated, String createdAt) {
        static TaskRunMessageView of(TaskRunMessage m) {
            return new TaskRunMessageView(
                    m.id, m.turnIndex,
                    m.role != null ? m.role.name() : null,
                    m.content, m.reasoning, m.toolCalls, m.toolResults,
                    m.truncated,
                    m.createdAt != null ? m.createdAt.toString() : null);
        }
    }

    /**
     * Paginated TaskRun history for one Task. Sorted most-recent first
     * (startedAt DESC). Returns {@code []} for a task that exists but
     * has no runs yet. Returns 404 only if the Task itself is missing.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TaskRunView.class))))
    @Operation(summary = "Paginated TaskRun history for one task (startedAt DESC), 404 if task missing")
    public static void runs(Long id, Integer limit, Integer offset) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        List<TaskRun> rows = TaskRunQueryService.runsForTask(task, effectiveOffset, effectiveLimit);

        renderJSON(gson.toJson(rows.stream().map(TaskRunView::of).toList()));
    }

    /**
     * Turn-by-turn execution trace for one TaskRun — its
     * {@code task_run_message} rows in turn order. Powers the Tasks UI
     * PeekPanel (JCLAW-22 slice P). Returns {@code []} for a run that
     * produced no messages (e.g. a reminder fire, which skips the LLM);
     * 404 only when the TaskRun itself is missing.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TaskRunMessageView.class))))
    @Operation(summary = "Turn-by-turn message trace for one TaskRun (turnIndex order), 404 if run missing")
    public static void runMessages(Long id) {
        TaskRun run = TaskService.findRunById(id);
        if (run == null) notFound();

        List<TaskRunMessage> rows = TaskRunQueryService.messagesForRun(run);

        renderJSON(gson.toJson(rows.stream().map(TaskRunMessageView::of).toList()));
    }

    /**
     * One TaskRun for the Timeline view (JCLAW-22 slice TL): the run plus its
     * parent task name — enough to plot a bar (startedAt position, duration
     * width, status colour) and link to the run's trace.
     */
    private record RecentRunView(Long id, Long taskId, String taskName, String status,
                                 String startedAt, String completedAt, Long durationMs) {
        static RecentRunView of(TaskRun r) {
            var task = r.task;
            return new RecentRunView(
                    r.id,
                    task != null ? task.id : null,
                    task != null ? task.name : null,
                    r.status != null ? r.status.name() : null,
                    r.startedAt != null ? r.startedAt.toString() : null,
                    r.completedAt != null ? r.completedAt.toString() : null,
                    r.durationMs);
        }
    }

    /**
     * Recent TaskRuns across all tasks for the Calendar's Week/Day grids —
     * most-recent first, each carrying its parent task name so the UI lays out
     * per-day swimlanes without an N+1 round-trip.
     *
     * <p>Two windowing modes:
     * <ul>
     *   <li><b>Range</b> — pass ISO-8601 {@code from} (and optional {@code to},
     *       default now); returns runs with {@code from <= startedAt < to}.
     *       The Week/Day views use this so they can navigate to arbitrary
     *       past/future dates.</li>
     *   <li><b>Rolling</b> — omit {@code from}; returns the last {@code hours}
     *       (default 24, capped at 30 days).</li>
     * </ul>
     * {@code limit} defaults to 200, capped at 500.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = RecentRunView.class))))
    @Operation(summary = "Recent TaskRuns across all tasks for the calendar/timeline (range via from/to or rolling last hours)")
    public static void recentRuns(Integer hours, Integer limit, String from, String to) {
        int lim = (limit != null && limit > 0) ? Math.min(limit, 500) : 200;
        var window = resolveRunWindow(hours, from, to);
        List<TaskRun> rows = TaskRunQueryService.recentRuns(window.since(), window.until(), lim);
        renderJSON(gson.toJson(rows.stream().map(RecentRunView::of).toList()));
    }

    /**
     * The startedAt window {@link #recentRuns} queries over: {@code since} is
     * always set; {@code until} is null in rolling mode (no upper bound) and
     * set in range mode.
     */
    private record RunWindow(Instant since, Instant until) {}

    /**
     * Resolve the {@link RunWindow} from the request: an ISO-8601 {@code from}
     * (with optional {@code to}, default now) yields a bounded range; an absent
     * {@code from} yields a rolling window of the last {@code hours} (default
     * 24, capped at 30 days) with no upper bound. A malformed instant 400s.
     */
    @SuppressWarnings("java:S2259")
    private static RunWindow resolveRunWindow(Integer hours, String from, String to) {
        if (from != null && !from.isBlank()) {
            try {
                var since = Instant.parse(from);
                var until = (to != null && !to.isBlank()) ? Instant.parse(to) : Instant.now();
                return new RunWindow(since, until);
            } catch (DateTimeException _) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "from/to must be ISO-8601 instants");
                throw new AssertionError("unreachable: error() throws");
            }
        }
        int h = (hours != null && hours > 0) ? Math.min(hours, 24 * 30) : 24;
        return new RunWindow(Instant.now().minusSeconds((long) h * 3600), null);
    }

    /**
     * JCLAW-414: cancel an in-progress task run. Flips the run's cooperative-
     * cancellation flag ({@link services.TaskRunRegistry}) so the agent tool
     * loop bails at its next safe checkpoint (between LLM rounds / tool calls —
     * cooperative, never {@code Thread.interrupt}), and stamps the run CANCELLED
     * immediately for instant UI feedback. The recurring schedule and next-run
     * time are untouched — only this one fire is cancelled. 404 when no such
     * run; 400 when the run is not currently RUNNING (already terminal).
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskRunView.class)))
    @Operation(summary = "Cancel an in-progress task run by runId (cooperative flag + stamp CANCELLED), 400 if not RUNNING")
    public static void cancelRun(Long runId) {
        TaskRun run = TaskService.findRunById(runId);
        if (run == null) notFound();
        if (run.status != TaskRun.Status.RUNNING) {
            // Only an in-flight run can be cancelled; a terminal run has nothing
            // to stop. (S2259: notFound() above halts on null, so run is non-null.)
            badRequest();
        }

        // Flip the cooperative flag first so the loop's next checkpoint observes
        // it (no-op if the run isn't in-flight on this JVM), then stamp the row
        // CANCELLED so the UI reflects it at once. The tool loop's onCancelled is
        // idempotent and won't double-write once this terminal status lands.
        TaskRunRegistry.requestCancel(runId);
        run.completedAt = Instant.now();
        run.durationMs = run.startedAt != null
                ? Duration.between(run.startedAt, run.completedAt).toMillis() : null;
        run.status = TaskRun.Status.CANCELLED;
        run.outputSummary = "Cancelled by operator";
        run.save();

        EventLogger.info("TASK_MGMT_RUN_CANCEL",
                run.task != null && run.task.agent != null ? run.task.agent.name : null, null,
                "Task run id %d cancelled by operator".formatted(runId));

        renderJSON(gson.toJson(TaskRunView.of(run)));
    }
}
