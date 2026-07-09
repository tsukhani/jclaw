package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jobs.TaskCleanupJob;
import models.Task;
import models.TaskRun;
import play.mvc.Controller;
import play.mvc.With;
import services.EventLogger;
import services.TaskStatsService;
import services.TimezoneResolver;

import java.time.LocalDate;

import static utils.GsonHolder.INSTANCE;

/**
 * Task dashboard-stats API — the run-derived KPI aggregates and the stats-reset
 * action. Split out of {@code ApiTasksController} (JCLAW-676); the URL paths
 * ({@code GET /api/tasks/stats}, {@code POST /api/task-runs/reset}) are
 * unchanged.
 *
 * <p>Single-operator scope: authenticated via {@link AuthCheck} with no
 * per-caller ownership check.
 */
@With(AuthCheck.class)
public class ApiTaskStatsController extends Controller {

    private static final Gson gson = INSTANCE;

    /**
     * Operator dashboard KPIs for the Tasks surface (JCLAW-22 slice K):
     * today's fire count, success rate, and average duration, plus the
     * current pending-queue depth, running count, and failed-needing-
     * attention count. "Today" is midnight in the operator's effective
     * default zone. {@code successRate} / {@code avgDurationMs} are null
     * when there's nothing to average yet (no terminal / completed runs
     * today) so the UI renders an em dash rather than 0.
     */
    private record TaskStatsView(long runsToday, Double successRate, Double avgDurationMs,
                                 long pendingCount, long runningCount, long activeCount,
                                 long failedCount, int retentionDays) {}

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskStatsView.class)))
    @Operation(summary = "Task dashboard KPIs (runs today, success rate, avg duration, pending/running/active/failed counts, retention days)")
    public static void stats(String payloadType, String excludePayloadType) {
        var zone = TimezoneResolver.currentDefault();
        var since = LocalDate.now(zone).atStartOfDay(zone).toInstant();

        // payloadType / excludePayloadType scope these aggregates exactly the
        // way they scope GET /api/tasks: the /tasks page passes
        // excludePayloadType=reminder so reminder tasks AND their runs never
        // inflate the automation KPIs, and /reminders passes payloadType=reminder
        // for the inverse. Both null/blank counts everything (headless callers).
        long runsToday = TaskStatsService.countRunsSince(since, null, payloadType, excludePayloadType);
        long completedToday = TaskStatsService.countRunsSince(since, TaskRun.Status.COMPLETED, payloadType, excludePayloadType);
        long failedRunsToday = TaskStatsService.countRunsSince(since, TaskRun.Status.FAILED, payloadType, excludePayloadType);

        Double avgDurationMs = TaskStatsService.avgCompletedDuration(since, payloadType, excludePayloadType);

        long terminalToday = completedToday + failedRunsToday;
        Double successRate = terminalToday > 0
                ? (double) completedToday / terminalToday
                : null;

        var payload = new TaskStatsView(
                runsToday, successRate, avgDurationMs,
                TaskStatsService.countTasks(Task.Status.PENDING, payloadType, excludePayloadType),
                // RUNNING is the only live-execution stat that lives on the
                // TaskRun, not the Task: a recurring task stays ACTIVE (a
                // one-shot stays PENDING) while its run executes, so count
                // RUNNING runs — the same signal the UI's runningRunId uses —
                // rather than Task.Status.RUNNING, which nothing currently sets.
                TaskStatsService.countRunningRuns(payloadType, excludePayloadType),
                TaskStatsService.countTasks(Task.Status.ACTIVE, payloadType, excludePayloadType),
                TaskStatsService.countTasks(Task.Status.FAILED, payloadType, excludePayloadType),
                // JCLAW-259: carry the effective retention TTL so the page
                // header renders it without a separate config fetch (which
                // 404s when the key is unset). Resolved server-side so the
                // default lives only in TaskCleanupJob, never in the client.
                TaskCleanupJob.resolveRetentionDays());
        renderJSON(gson.toJson(payload));
    }

    /**
     * Reset the run-derived dashboard KPIs by hard-deleting terminal
     * (non-RUNNING) task runs and their transcripts, scoped by the same
     * {@code payloadType} filter {@link #stats} uses (the Tasks page passes
     * {@code excludePayloadType=reminder}, so a reset there never touches
     * reminder history). In-flight RUNNING runs are preserved so a reset during
     * an active fire doesn't orphan it. The task-status counts (pending /
     * running / failed) are untouched — they reflect live Task state, not run
     * history.
     */
    @Operation(summary = "Reset dashboard KPIs by deleting terminal (non-RUNNING) task runs and transcripts, scoped by payloadType")
    public static void resetStats(String payloadType, String excludePayloadType) {
        int deleted = TaskStatsService.resetTerminalRuns(payloadType, excludePayloadType);

        EventLogger.info("TASK_MGMT_RESET_STATS", null, null,
                "Reset task stats: deleted %d terminal task run(s)".formatted(deleted));
        renderJSON("{\"status\":\"reset\",\"deletedRuns\":" + deleted + "}");
    }
}
