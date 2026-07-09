package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.persistence.Query;
import jobs.TaskCleanupJob;
import models.Agent;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import play.db.jpa.JPA;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.DeliveryAdvisor;
import services.DeliverySpec;
import services.EventLogger;
import services.JClawCronUtils;
import services.ScheduleShorthandParser;
import services.TaskRunRegistry;
import services.TaskSchedulingService;
import services.TaskService;
import services.TimezoneResolver;
import services.Tx;
import services.search.LuceneIndexer;
import services.search.MessageSearch;
import utils.ApiResponses;
import utils.JpqlFilter;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static utils.GsonHolder.INSTANCE;

/**
 * Tasks API.
 *
 * <h2>Single-operator scope</h2>
 * Id-addressed mutations ({@link #update}, {@link #cancel}, {@link #retry},
 * {@link #run}, {@link #pause}, {@link #resume}) authenticate via {@link AuthCheck}
 * and don't add a per-caller ownership check: JClaw is single-operator Personal
 * Edition — one admin owns every agent and task, so there is no other user to scope
 * against. The {@link #list} endpoint honors an optional {@code agentId} filter and
 * otherwise returns all tasks.
 */
@With(AuthCheck.class)
public class ApiTasksController extends Controller {

    private static final Gson gson = INSTANCE;

    // JSON body keys reused across create/update parsers and per-field tests.
    private static final String KEY_NAME = "name";
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_SCHEDULE = "schedule";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_DELIVERY = "delivery";
    private static final String KEY_PAYLOAD_TYPE = "payloadType";
    private static final String KEY_MODEL_PROVIDER = "modelProvider";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_ENABLED_TOOL_NAMES = "enabledToolNames";
    private static final String KEY_WORKDIR = "workdir";
    private static final String KEY_PRE_CHECK = "preCheck";
    private static final String KEY_SCRIPT = "script";
    private static final String KEY_NO_AGENT = "noAgent";
    private static final String KEY_AUTO_DELETE = "autoDeleteOnComplete";

    /** JPQL bind-parameter names (the RUNNING status filter on TaskRun→Task, and
     *  the {@code r.status} filter shared by the run-count aggregates) plus the
     *  TaskRun→Task alias path, factored out of the run-stats / reset queries
     *  (S1192). */
    private static final String PARAM_RUNNING = "running";
    private static final String RUN_TASK_ALIAS = "r.task";
    private static final String PARAM_RSTATUS = "rstatus";
    private static final String KEY_CONTEXT_FROM_TASK_IDS = "contextFromTaskIds";
    private static final String KEY_REPEAT_LIMIT = "repeatLimit";
    private static final String KEY_PAUSED = "paused";
    private static final String KEY_TIMEZONE = "timezone";

    public record TaskRequest(String name, Long agentId, String schedule, String description,
                              String delivery, String payloadType, String modelProvider, String modelId,
                              List<String> enabledToolNames, String workdir, String preCheck,
                              String script, Boolean noAgent, Boolean autoDeleteOnComplete,
                              List<Long> contextFromTaskIds, Integer repeatLimit,
                              Boolean paused, String timezone) {}

    private record TaskView(Long id, String name, String description, String type, String status,
                            String cronExpression, Long intervalSeconds, String scheduleDisplay,
                            int retryCount, int maxRetries, String lastError,
                            String nextRunAt, String lastFiredAt, String createdAt,
                            Long agentId, String agentName,
                            boolean paused,
                            String delivery, String payloadType,
                            String modelProvider, String modelId,
                            String enabledToolNames, String workdir,
                            String preCheck, String script, boolean noAgent,
                            boolean autoDeleteOnComplete,
                            String contextFromTaskIds, Integer repeatLimit,
                            String timezone, String effectiveTimezone,
                            Long runningRunId) {
        static TaskView of(Task t) {
            return of(t, null, null);
        }

        static TaskView of(Task t, Instant lastFiredAt) {
            return of(t, lastFiredAt, null);
        }

        /**
         * Fullest variant, populated by {@link #list} via two single bulk SQL
         * passes:
         * <ul>
         *   <li>{@code lastFiredAt} — the latest {@link TaskRun#completedAt} for
         *       this task. Drives the Reminders "Fired" column without an N+1
         *       round-trip; {@code null} when the task has no completed run.</li>
         *   <li>{@code runningRunId} (JCLAW-414) — the id of this task's
         *       currently-RUNNING {@link TaskRun}, or {@code null} when none is
         *       in flight. Lets the Tasks Actions column swap the "Run now" bolt
         *       for a cancel control (POST /api/task-runs/{id}/cancel) whether
         *       the row is collapsed or expanded.</li>
         * </ul>
         */
        static TaskView of(Task t, Instant lastFiredAt, Long runningRunId) {
            return new TaskView(t.id, t.name, t.description, t.type.name(), t.status.name(),
                    t.cronExpression, t.intervalSeconds, t.scheduleDisplay,
                    t.retryCount, t.maxRetries, t.lastError,
                    nextRunAtForDisplay(t, lastFiredAt),
                    lastFiredAt != null ? lastFiredAt.toString() : null,
                    t.createdAt.toString(),
                    t.agent != null ? t.agent.id : null,
                    t.agent != null ? t.agent.name : null,
                    t.paused,
                    t.delivery, t.payloadType,
                    t.modelProvider, t.modelId,
                    t.enabledToolNames, t.workdir,
                    t.preCheck, t.script, t.noAgent,
                    t.autoDeleteOnComplete,
                    t.contextFromTaskIds, t.repeatLimit,
                    t.timezone,
                    // JCLAW-261: precomputed effective zone so the UI can
                    // render the actual fire-time zone for each task
                    // without re-running the resolver client-side. Returns
                    // a stable IANA id even when t.timezone is null
                    // (falls back through Config / conf / JVM default).
                    TimezoneResolver.resolve(t).getId(),
                    runningRunId);
        }

    }

    /**
     * Authoritative next-fire instant for the UI. {@code Task.nextRunAt} is
     * stamped at create/update time and never refreshed by the scheduler —
     * db-scheduler owns the live next-fire in its own {@code scheduled_tasks}
     * row, which JCLAW-22 deliberately does NOT read (that would couple the
     * API to the scheduler's internal schema). Instead:
     * <ul>
     *   <li><b>Paused</b> → no next fire (null); it won't fire until resumed.</li>
     *   <li><b>Terminal</b> → the stored value; no further fire is expected.</li>
     *   <li><b>CRON</b> → recomputed from the expression in the task's
     *       effective zone (JCLAW-261), so the column shows e.g. "tomorrow
     *       09:00" rather than the create timestamp.</li>
     *   <li><b>INTERVAL</b> (JCLAW-22 option C) → anchored on the previous
     *       completion: {@code lastFiredAt + intervalSeconds}, because that's
     *       where db-scheduler sets the next fire. {@code now + intervalSeconds}
     *       would jitter a full interval ahead on every page load. Before the
     *       first fire there's no completion to anchor on, so it falls back to
     *       the stored creation-time first-fire projection.</li>
     * </ul>
     *
     * @param lastFiredAt the most-recent {@link TaskRun#completedAt} for this
     *     task (from {@link #list}'s bulk pass), or null when it has never
     *     completed a run.
     */
    public static String nextRunAtForDisplay(Task t, Instant lastFiredAt) {
        // Paused recurring tasks won't fire until resumed.
        if (t.paused) return null;
        // Only live tasks (PENDING one-shot / ACTIVE recurring) have a
        // meaningful "next fire" — terminal-state tasks fall through to the
        // stored value since no further fire is expected.
        if (t.status != Task.Status.PENDING && t.status != Task.Status.ACTIVE) {
            return t.nextRunAt != null ? t.nextRunAt.toString() : null;
        }
        try {
            if (t.type == Task.Type.CRON && t.cronExpression != null) {
                // JCLAW-261: render the next fire using the same zone the
                // scheduler will fire under, so the column matches the real time.
                var zone = TimezoneResolver.resolve(t);
                var next = JClawCronUtils.nextExecution(t.cronExpression, zone);
                if (next != null) return next.toString();
            }
            if (t.type == Task.Type.INTERVAL && t.intervalSeconds != null && t.intervalSeconds > 0) {
                // Option C: db-scheduler anchors the next INTERVAL fire at the
                // previous completion, so the live next-fire is
                // lastFiredAt + intervalSeconds. Before the first fire fall
                // back to the stored creation-time first-fire projection.
                if (lastFiredAt != null) {
                    return lastFiredAt.plusSeconds(t.intervalSeconds).toString();
                }
                return t.nextRunAt != null ? t.nextRunAt.toString() : null;
            }
        } catch (RuntimeException _) {
            // Fall through to the stored value — better to show something
            // than swallow the row on a malformed expression that somehow
            // got past validation.
        }
        return t.nextRunAt != null ? t.nextRunAt.toString() : null;
    }

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
                    latestTurnPreviewFor(r),
                    r.deliveryStatus != null ? r.deliveryStatus.name() : null,
                    r.deliveryTarget,
                    r.deliveryError,
                    r.traceJson,
                    r.createdAt != null ? r.createdAt.toString() : null);
        }
    }

    /** Max characters of an in-flight run's preview clip (one-liner in the row). */
    private static final int RUN_PREVIEW_MAX = 160;

    /**
     * One-line preview of the newest turn of a still-RUNNING run, so the Tasks
     * run-history row can show a live clip of an in-flight fire — which has no
     * {@code outputSummary} yet (that's stamped only at completion). Returns
     * null for terminal runs (the row renders {@code outputSummary} instead)
     * and for a run with no text turn yet. One indexed lookup, and only for the
     * at-most-one RUNNING run in a history page.
     */
    private static String latestTurnPreviewFor(TaskRun r) {
        if (r.status != TaskRun.Status.RUNNING) return null;
        var latest = (TaskRunMessage) TaskRunMessage.find(
                "taskRun = ?1 AND content IS NOT NULL ORDER BY turnIndex DESC", r).first();
        if (latest == null || latest.content == null || latest.content.isBlank()) return null;
        var text = latest.content.strip();
        return text.length() > RUN_PREVIEW_MAX ? text.substring(0, RUN_PREVIEW_MAX) + "…" : text;
    }

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
                    agent != null ? agent.name : null);
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

    /**
     * JCLAW-261: returns the sorted IANA timezone list for the task
     * create/edit form's dropdown. Also reports the currently-effective
     * default (Config row → application.conf → JVM default) so the UI
     * can highlight the operator's working zone without re-implementing
     * the fallback chain client-side.
     */
    @SuppressWarnings("java:S2259")
    @Operation(summary = "List IANA timezone ids plus the effective task-scheduling and app default zones")
    public static void timezones() {
        var ids = new ArrayList<>(ZoneId.getAvailableZoneIds());
        ids.sort(String::compareTo);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("timezones", ids);
        // `default` = effective task-scheduling zone (tasks.defaultTimezone chain).
        // `appDefault` = effective operator wall-clock zone (app.timezone chain,
        // falling back to the server's JVM zone) — used by Settings → General.
        payload.put("default", TimezoneResolver.currentDefault().getId());
        payload.put("appDefault", TimezoneResolver.appZone().getId());
        renderJSON(gson.toJson(payload));
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

        @SuppressWarnings("unchecked")
        List<TaskRun> rows = (List<TaskRun>) (List<?>) TaskRun.find(
                "task = ?1 ORDER BY startedAt DESC", task)
                .from(effectiveOffset)
                .fetch(effectiveLimit);

        renderJSON(gson.toJson(rows.stream().map(TaskRunView::of).toList()));
    }

    /**
     * JCLAW-455: preflight Slack delivery reachability for one Task. Returns
     * {@code {"advisory": "<text>"}} when the task's declared Slack channel target
     * isn't reachable (private/uninvited channel, or a public channel the bot hasn't
     * joined), else {@code {"advisory": null}}. The Tasks page fetches this lazily on
     * row-expand and renders it below the delivery value. Probes are 60 s-cached
     * server-side; 404 only if the Task is missing.
     */
    @SuppressWarnings("java:S2259")
    @Operation(summary = "Preflight Slack delivery reachability advisory for one task (null when reachable / N/A)")
    public static void deliveryAdvisory(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        var advisory = DeliveryAdvisor.advisoryFor(task.agent, task.delivery);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("advisory", advisory);
        renderJSON(gson.toJson(payload));
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

        @SuppressWarnings("unchecked")
        List<TaskRunMessage> rows = (List<TaskRunMessage>) (List<?>) TaskRunMessage.find(
                "taskRun = ?1 ORDER BY turnIndex ASC", run).fetch();

        renderJSON(gson.toJson(rows.stream().map(TaskRunMessageView::of).toList()));
    }

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
        long runsToday = countRunsSince(since, null, payloadType, excludePayloadType);
        long completedToday = countRunsSince(since, TaskRun.Status.COMPLETED, payloadType, excludePayloadType);
        long failedRunsToday = countRunsSince(since, TaskRun.Status.FAILED, payloadType, excludePayloadType);

        Double avgDurationMs = avgCompletedDuration(since, payloadType, excludePayloadType);

        long terminalToday = completedToday + failedRunsToday;
        Double successRate = terminalToday > 0
                ? (double) completedToday / terminalToday
                : null;

        var payload = new TaskStatsView(
                runsToday, successRate, avgDurationMs,
                countTasks(Task.Status.PENDING, payloadType, excludePayloadType),
                // RUNNING is the only live-execution stat that lives on the
                // TaskRun, not the Task: a recurring task stays ACTIVE (a
                // one-shot stays PENDING) while its run executes, so count
                // RUNNING runs — the same signal the UI's runningRunId uses —
                // rather than Task.Status.RUNNING, which nothing currently sets.
                countRunningRuns(payloadType, excludePayloadType),
                countTasks(Task.Status.ACTIVE, payloadType, excludePayloadType),
                countTasks(Task.Status.FAILED, payloadType, excludePayloadType),
                // JCLAW-259: carry the effective retention TTL so the page
                // header renders it without a separate config fetch (which
                // 404s when the key is unset). Resolved server-side so the
                // default lives only in TaskCleanupJob, never in the client.
                TaskCleanupJob.resolveRetentionDays());
        renderJSON(gson.toJson(payload));
    }

    private static long countRunsSince(Instant since, TaskRun.Status status,
                                       String payloadType, String excludePayloadType) {
        var jpql = "SELECT COUNT(r) FROM TaskRun r WHERE r.startedAt >= :since"
                + (status != null ? " AND r.status = :rstatus" : "")
                + payloadTypeWhere(RUN_TASK_ALIAS, payloadType, excludePayloadType);
        var q = JPA.em().createQuery(jpql, Long.class).setParameter("since", since);
        if (status != null) q.setParameter(PARAM_RSTATUS, status);
        bindPayloadType(q, payloadType, excludePayloadType);
        return q.getSingleResult();
    }

    private static Double avgCompletedDuration(Instant since, String payloadType, String excludePayloadType) {
        var jpql = "SELECT AVG(r.durationMs) FROM TaskRun r "
                + "WHERE r.startedAt >= :since AND r.status = :rstatus AND r.durationMs IS NOT NULL"
                + payloadTypeWhere(RUN_TASK_ALIAS, payloadType, excludePayloadType);
        var q = JPA.em().createQuery(jpql)
                .setParameter("since", since)
                .setParameter(PARAM_RSTATUS, TaskRun.Status.COMPLETED);
        bindPayloadType(q, payloadType, excludePayloadType);
        return (Double) q.getSingleResult();
    }

    private static long countTasks(Task.Status status, String payloadType, String excludePayloadType) {
        var jpql = "SELECT COUNT(t) FROM Task t WHERE t.status = :status"
                + payloadTypeWhere("t", payloadType, excludePayloadType);
        var q = JPA.em().createQuery(jpql, Long.class).setParameter("status", status);
        bindPayloadType(q, payloadType, excludePayloadType);
        return q.getSingleResult();
    }

    /**
     * Count task runs currently in flight ({@link TaskRun.Status#RUNNING}).
     * Unlike {@link #countRunsSince} this is deliberately not bounded by
     * "today" — a run that started before midnight and is still executing
     * must still count. Scoped by payloadType like the other aggregates.
     */
    private static long countRunningRuns(String payloadType, String excludePayloadType) {
        var jpql = "SELECT COUNT(r) FROM TaskRun r WHERE r.status = :rstatus"
                + payloadTypeWhere(RUN_TASK_ALIAS, payloadType, excludePayloadType);
        var q = JPA.em().createQuery(jpql, Long.class)
                .setParameter(PARAM_RSTATUS, TaskRun.Status.RUNNING);
        bindPayloadType(q, payloadType, excludePayloadType);
        return q.getSingleResult();
    }

    /**
     * JPQL fragment scoping an aggregate by payloadType, mirroring the
     * {@code GET /api/tasks} list filter: {@code payloadType} pins to one kind
     * (e.g. reminder); {@code excludePayloadType} excludes it while still
     * matching the NULL payloadType of ordinary automation tasks. {@code alias}
     * is the entity path to the column ({@code "t"} for Task, {@code "r.task"}
     * for a TaskRun join).
     */
    private static String payloadTypeWhere(String alias, String payloadType, String excludePayloadType) {
        var sb = new StringBuilder();
        if (payloadType != null && !payloadType.isBlank()) {
            sb.append(" AND ").append(alias).append(".payloadType = :pt");
        }
        if (excludePayloadType != null && !excludePayloadType.isBlank()) {
            sb.append(" AND (").append(alias).append(".payloadType <> :ept OR ")
              .append(alias).append(".payloadType IS NULL)");
        }
        return sb.toString();
    }

    private static void bindPayloadType(Query q,
                                        String payloadType, String excludePayloadType) {
        if (payloadType != null && !payloadType.isBlank()) q.setParameter("pt", payloadType);
        if (excludePayloadType != null && !excludePayloadType.isBlank()) q.setParameter("ept", excludePayloadType);
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
        var query = (window.until() != null)
                ? TaskRun.find("startedAt >= ?1 AND startedAt < ?2 ORDER BY startedAt DESC", window.since(), window.until())
                : TaskRun.find("startedAt >= ?1 ORDER BY startedAt DESC", window.since());
        @SuppressWarnings("unchecked")
        List<TaskRun> rows = (List<TaskRun>) (List<?>) query.fetch(lim);
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

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TaskView.class))))
    @Operation(summary = "List tasks filtered by status/type/agent/payloadType and full-text q, paginated with X-Total-Count")
    public static void list(String status, String type, Long agentId, String q,
                             String payloadType, String excludePayloadType,
                             Integer limit, Integer offset) {
        var filter = new JpqlFilter()
                .eq("status", status != null && !status.isBlank() ? Task.Status.valueOf(status.toUpperCase()) : null)
                .eq("type", type != null && !type.isBlank() ? Task.Type.valueOf(type.toUpperCase()) : null)
                .eq("agent.id", agentId)
                // payloadType filters: the frontend's /tasks page passes
                // excludePayloadType=reminder so reminders don't appear in
                // the automation-tasks list; /reminders passes
                // payloadType=reminder for the inverse. Both null/blank for
                // headless callers and the Dashboard tile counts.
                .eq(KEY_PAYLOAD_TYPE, payloadType)
                .notEqOrNull(KEY_PAYLOAD_TYPE, excludePayloadType);

        // JCLAW-304: q resolves against the TASK Lucene scope (virtual
        // doc of name + description). Null when q is absent/blank;
        // empty list when q matched nothing — caller short-circuits to
        // zero rows. Non-empty narrows the result set via `t.id IN (:fts)`.
        var ftsTaskIds = ftsTaskIds(q);

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;
        int effectiveOffset = (offset != null && offset >= 0) ? offset : 0;

        var where = filter.toWhereClause();
        if (ftsTaskIds != null) {
            if (ftsTaskIds.isEmpty()) {
                response.setHeader("X-Total-Count", "0");
                renderJSON("[]");
            }
            where = where.isEmpty() ? "t.id IN (:fts)" : where + " AND t.id IN (:fts)";
        }
        String jpql = where.isEmpty()
                ? "SELECT t FROM Task t LEFT JOIN FETCH t.agent ORDER BY t.createdAt DESC"
                : "SELECT t FROM Task t LEFT JOIN FETCH t.agent WHERE " + where + " ORDER BY t.createdAt DESC";
        var jpaQ = JPA.em().createQuery(jpql, Task.class);
        var params = filter.paramList();
        for (int i = 0; i < params.size(); i++) {
            jpaQ.setParameter(i + 1, params.get(i));
        }
        if (ftsTaskIds != null) jpaQ.setParameter("fts", ftsTaskIds);
        List<Task> tasks = jpaQ.setFirstResult(effectiveOffset)
                .setMaxResults(effectiveLimit).getResultList();

        // X-Total-Count: mirror the convention used by /api/conversations
        // (consumed by the dashboard's per-status task counts and any
        // future paginated UI). Computed via a separate COUNT query
        // applying the same WHERE clause but ignoring limit/offset, so
        // the header reflects the true total irrespective of pagination.
        String countJpql = where.isEmpty()
                ? "SELECT COUNT(t) FROM Task t"
                : "SELECT COUNT(t) FROM Task t WHERE " + where;
        var countQ = JPA.em().createQuery(countJpql, Long.class);
        for (int i = 0; i < params.size(); i++) {
            countQ.setParameter(i + 1, params.get(i));
        }
        if (ftsTaskIds != null) countQ.setParameter("fts", ftsTaskIds);
        Long total = countQ.getSingleResult();
        response.setHeader("X-Total-Count", String.valueOf(total));

        // Bulk-fetch the latest TaskRun.completedAt for each task on the page
        // so the Reminders "Fired" column (and any future "last run" surface)
        // doesn't need an N+1 round-trip. GROUP BY task id, MAX(completedAt)
        // gives the most-recent successful fire instant per task; tasks with
        // no completed runs are absent from the map (column renders empty).
        var taskIds = tasks.stream().map(t -> t.id).filter(Objects::nonNull).toList();
        Map<Long, Instant> lastFiredAt = Map.of();
        if (!taskIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            var rows = (List<Object[]>) JPA.em().createQuery(
                    "SELECT r.task.id, MAX(r.completedAt) FROM TaskRun r "
                            + "WHERE r.task.id IN :ids AND r.completedAt IS NOT NULL "
                            + "GROUP BY r.task.id")
                    .setParameter("ids", taskIds)
                    .getResultList();
            var map = HashMap.<Long, Instant>newHashMap(rows.size());
            for (var row : rows) {
                map.put((Long) row[0], (Instant) row[1]);
            }
            lastFiredAt = map;
        }
        final var fired = lastFiredAt;

        // JCLAW-414: bulk-fetch each task's currently-RUNNING TaskRun id (one
        // query, no N+1) so the Actions column can show a cancel control while
        // a run is in flight. A task has at most one RUNNING run in practice;
        // MAX(id) is a stable pick if a stale row ever overlaps.
        Map<Long, Long> runningByTask = Map.of();
        if (!taskIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            var runningRows = (List<Object[]>) JPA.em().createQuery(
                    "SELECT r.task.id, MAX(r.id) FROM TaskRun r "
                            + "WHERE r.task.id IN :ids AND r.status = :running "
                            + "GROUP BY r.task.id")
                    .setParameter("ids", taskIds)
                    .setParameter(PARAM_RUNNING, TaskRun.Status.RUNNING)
                    .getResultList();
            var rmap = HashMap.<Long, Long>newHashMap(runningRows.size());
            for (var row : runningRows) {
                rmap.put((Long) row[0], (Long) row[1]);
            }
            runningByTask = rmap;
        }
        final var running = runningByTask;
        renderJSON(gson.toJson(tasks.stream()
                .map(t -> TaskView.of(t, fired.get(t.id), running.get(t.id))).toList()));
    }

    /**
     * JCLAW-304: resolve a {@code q} keyword to the matching Task ids
     * via the TASK Lucene scope. Same null / empty / non-empty contract
     * as the conversation-list helper above — see
     * {@code ApiConversationsController.ftsConversationIds} for the
     * shared semantic. FTS backend errors fall through as "no FTS
     * filter" so the operator sees equality-only results rather than a
     * 500 on a stray Lucene IO hiccup.
     */
    @SuppressWarnings("java:S1168") // null vs empty-list is a deliberate tri-state: null = "no q filter"; empty = "matched nothing, render zero rows"; non-empty = "narrow" (see list())
    private static List<Long> ftsTaskIds(String q) {
        if (q == null || q.isBlank()) return null;
        try {
            var ids = MessageSearch.searchIds(
                    LuceneIndexer.Scope.TASK, q, 500);
            return ids.isEmpty() ? List.of() : ids;
        } catch (IOException e) {
            EventLogger.warn("search", null, null,
                    "FTS lookup failed for tasks q='%s': %s".formatted(q, e.getMessage()));
            return null;
        }
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = TaskRequest.class)))
    @Operation(summary = "Create a task from a JSON body (agentId, name, schedule + optional fields) and register it with the scheduler")
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var agent = requireAgentFromBody(body);
        var name = requireTaskName(body);
        var spec = requireScheduleSpec(body);
        rejectInvalidDelivery(body);

        rejectDuplicateRecurringTask(name, agent, spec);

        var saved = Tx.run(() -> persistNewTask(body, agent, name, spec));

        TaskSchedulingService.register(saved);

        EventLogger.info("TASK_MGMT_CREATE", agent.name, null,
                "Task '%s' (id=%d, type=%s) created via API"
                        .formatted(saved.name, saved.id, saved.type));

        renderJSON(gson.toJson(TaskView.of(saved)));
    }

    /**
     * Resolve and require the {@code agentId} from the request body.
     *
     * <p>agentId resolution. Single-operator Personal Edition: the one admin
     * principal (admitted by AuthCheck) can address any agent. The 400 here is
     * "you typed an id that doesn't resolve"; a non-existent task addressed in
     * /api/tasks/{id} would be 404, but a non-existent agent named in the
     * request body is bad input.
     */
    @SuppressWarnings("java:S2259")
    private static Agent requireAgentFromBody(JsonObject body) {
        if (!body.has(KEY_AGENT_ID) || body.get(KEY_AGENT_ID).isJsonNull()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "agentId is required");
            throw new AssertionError("unreachable: error() throws");
        }
        var agent = AgentService.findById(body.get(KEY_AGENT_ID).getAsLong());
        if (agent == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "agentId does not resolve to an existing agent");
            throw new AssertionError("unreachable: error() throws");
        }
        return agent;
    }

    @SuppressWarnings("java:S2259")
    private static String requireTaskName(JsonObject body) {
        if (!body.has(KEY_NAME) || body.get(KEY_NAME).isJsonNull()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "name is required");
            throw new AssertionError("unreachable: error() throws");
        }
        var name = body.get(KEY_NAME).getAsString();
        if (name == null || name.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "name must be non-blank");
            throw new AssertionError("unreachable: error() throws");
        }
        return name;
    }

    /**
     * JCLAW-417: reject a malformed {@code delivery} at the write boundary via
     * {@link services.DeliverySpec#validate}. Structural only — tool
     * resolution and channel configuration are caller/runtime concerns. No-op
     * when {@code delivery} is absent or explicit-null (the latter clears it).
     */
    private static void rejectInvalidDelivery(JsonObject body) {
        var delivery = readOptionalString(body, KEY_DELIVERY);
        if (delivery == null) return;
        var err = DeliverySpec.validate(delivery);
        if (err != null) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, err);
    }

    @SuppressWarnings("java:S2259")
    private static ScheduleShorthandParser.ScheduleSpec requireScheduleSpec(JsonObject body) {
        if (!body.has(KEY_SCHEDULE) || body.get(KEY_SCHEDULE).isJsonNull()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "schedule is required");
            throw new AssertionError("unreachable: error() throws");
        }
        try {
            var zone = TimezoneResolver.resolve(readOptionalString(body, KEY_TIMEZONE));
            return ScheduleShorthandParser.parse(body.get(KEY_SCHEDULE).getAsString(), zone);
        } catch (IllegalArgumentException e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Invalid schedule: " + e.getMessage());
            throw new AssertionError("unreachable: error() throws");
        }
    }

    /**
     * Recurring-type duplicate detection per AC: 409 with the
     * conflicting Task ID. Only CRON / INTERVAL — IMMEDIATE and
     * SCHEDULED are one-shot, so duplicate names are inert and not
     * worth rejecting.
     */
    private static void rejectDuplicateRecurringTask(String name, Agent agent,
                                                      ScheduleShorthandParser.ScheduleSpec spec) {
        if (spec.type() != Task.Type.CRON && spec.type() != Task.Type.INTERVAL) return;
        List<Task> conflicts = TaskService.findRecurringConflicts(name, agent);
        if (!conflicts.isEmpty()) {
            var conflictId = conflicts.getFirst().id;
            ApiResponses.error(409, ApiResponses.CONFLICT,
                    "A recurring task named '%s' already exists for this agent (id=%d)".formatted(name, conflictId),
                    "conflictingTaskId", conflictId);
        }
    }

    /**
     * Rename variant of {@link #rejectDuplicateRecurringTask}: re-checks the
     * duplicate-name rule against the task's current agent + type, excluding the
     * task itself (filtered out by id in the controller) so renaming to its own
     * name is a no-op.
     * Only CRON / INTERVAL are checked — one-shot names are inert (parity with
     * create). 409 with the conflicting Task id. Relies on {@code update}'s
     * ambient action transaction (no {@code Tx.run} wrap — it runs alongside the
     * raw {@code findById}/{@code save} that {@code update} already performs).
     */
    private static void rejectDuplicateRecurringRename(String name, Task task) {
        if (task.type != Task.Type.CRON && task.type != Task.Type.INTERVAL) return;
        List<Task> conflicts = TaskService.findRecurringConflicts(name, task.agent)
                .stream().filter(t -> !t.id.equals(task.id)).toList();
        if (!conflicts.isEmpty()) {
            var conflictId = conflicts.getFirst().id;
            ApiResponses.error(409, ApiResponses.CONFLICT,
                    "A recurring task named '%s' already exists for this agent (id=%d)".formatted(name, conflictId),
                    "conflictingTaskId", conflictId);
        }
    }

    private static Task persistNewTask(JsonObject body, Agent agent, String name,
                                       ScheduleShorthandParser.ScheduleSpec spec) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.description = readOptionalString(body, KEY_DESCRIPTION);
        if (t.description == null) t.description = "";

        t.type = spec.type();
        // Status depends on type: CRON / INTERVAL start ACTIVE (ongoing
        // recurrence), IMMEDIATE / SCHEDULED start PENDING (waiting to fire).
        // The Task entity's default is PENDING; override here for recurring.
        t.status = Task.initialStatusFor(spec.type());
        t.scheduledAt = spec.scheduledAt();
        t.cronExpression = spec.cronExpression();
        t.intervalSeconds = spec.intervalSeconds();
        t.scheduleDisplay = spec.scheduleDisplay();
        // nextRunAt is no longer authoritative under db-scheduler (see
        // JCLAW-21), but keep it populated for the Tasks-page render
        // until the column is dropped.
        t.nextRunAt = spec.scheduledAt() != null ? spec.scheduledAt() : Instant.now();

        // Plumbing-ahead fields (consumed by JCLAW-295/296/297/298).
        t.delivery = readOptionalString(body, KEY_DELIVERY);
        t.payloadType = readOptionalString(body, KEY_PAYLOAD_TYPE);
        t.modelProvider = readOptionalString(body, KEY_MODEL_PROVIDER);
        t.modelId = readOptionalString(body, KEY_MODEL_ID);
        t.enabledToolNames = readOptionalString(body, KEY_ENABLED_TOOL_NAMES);
        t.workdir = readOptionalString(body, KEY_WORKDIR);
        t.preCheck = readOptionalString(body, KEY_PRE_CHECK);
        t.script = readOptionalString(body, KEY_SCRIPT);
        if (body.has(KEY_NO_AGENT) && !body.get(KEY_NO_AGENT).isJsonNull()) {
            t.noAgent = body.get(KEY_NO_AGENT).getAsBoolean();
        }
        // Reminders default to auto-delete-after-fire (a fired one-off reminder
        // has served its purpose); regular tasks keep their audit history. An
        // explicit body value overrides.
        if (body.has(KEY_AUTO_DELETE) && !body.get(KEY_AUTO_DELETE).isJsonNull()) {
            t.autoDeleteOnComplete = body.get(KEY_AUTO_DELETE).getAsBoolean();
        } else {
            t.autoDeleteOnComplete = "reminder".equalsIgnoreCase(t.payloadType);
        }
        t.contextFromTaskIds = readOptionalString(body, KEY_CONTEXT_FROM_TASK_IDS);
        if (body.has(KEY_REPEAT_LIMIT) && !body.get(KEY_REPEAT_LIMIT).isJsonNull()) {
            t.repeatLimit = body.get(KEY_REPEAT_LIMIT).getAsInt();
        }
        // JCLAW-261: optional per-task IANA timezone. Only meaningful for
        // CRON / SCHEDULED — INTERVAL / IMMEDIATE ignore it at fire time
        // — but we accept and persist for all types since the column is
        // cheap and the UI may surface it. Validation here is strict:
        // an invalid value short-circuits the request with 400 rather
        // than silently falling through at fire time.
        var tzRaw = readOptionalString(body, KEY_TIMEZONE);
        if (tzRaw != null && !tzRaw.isBlank()) {
            try {
                ZoneId.of(tzRaw.trim());
            } catch (DateTimeException e) {
                response.status = 400;
                renderText("Invalid IANA timezone '" + tzRaw + "': " + e.getMessage()
                        + ". Use a value from GET /api/timezones (e.g. 'America/New_York', 'Asia/Tokyo').");
            }
            t.timezone = tzRaw.trim();
        } else {
            t.timezone = null;
        }

        t.save();
        return t;
    }

    private static String readOptionalString(JsonObject body, String key) {
        if (!body.has(key)) return null;
        var el = body.get(key);
        if (el.isJsonNull()) return null;
        var s = el.getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = TaskRequest.class)))
    @Operation(summary = "Patch a task by id from a JSON body of changed fields, re-registering the scheduler when the schedule changes")
    public static void update(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        rejectInvalidDelivery(body);

        boolean scheduleChanged = applyScheduleUpdate(task, body);
        boolean nameChanged = applyNameUpdate(task, body);
        boolean fieldsChanged = applyOptionalFieldUpdates(task, body);
        boolean anyChange = scheduleChanged || nameChanged || fieldsChanged;

        if (!anyChange) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "No patchable fields in body");
        }

        task.save();
        if (scheduleChanged) {
            // cancel-then-register against db-scheduler. No-op in tests
            // where SchedulerClient isn't wired (null-soft per the
            // service's internal client() guard).
            TaskSchedulingService.update(task);
        }

        EventLogger.info("TASK_MGMT_UPDATE",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) updated via API".formatted(task.name, task.id));

        renderJSON(gson.toJson(TaskView.of(task)));
    }

    /**
     * Schedule re-parse: when present, drives type + scheduledAt +
     * cronExpression + intervalSeconds + scheduleDisplay together.
     * Passing through ScheduleShorthandParser revalidates cron
     * expressions (with the prepend-0 hint for Unix 5-field) per AC.
     *
     * @return true if the schedule field was applied.
     */
    @SuppressWarnings("java:S2259")
    private static boolean applyScheduleUpdate(Task task, JsonObject body) {
        if (!body.has(KEY_SCHEDULE) || body.get(KEY_SCHEDULE).isJsonNull()) return false;
        final ScheduleShorthandParser.ScheduleSpec spec;
        try {
            spec = ScheduleShorthandParser.parse(body.get(KEY_SCHEDULE).getAsString());
        } catch (IllegalArgumentException e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Invalid schedule: " + e.getMessage());
            return false; // unreachable — error() throws
        }
        task.type = spec.type();
        task.scheduledAt = spec.scheduledAt();
        task.cronExpression = spec.cronExpression();
        task.intervalSeconds = spec.intervalSeconds();
        task.scheduleDisplay = spec.scheduleDisplay();
        // nextRunAt mirrors the new fire time so the Tasks page render
        // stays in sync — the scheduled_tasks row is the source of
        // truth at fire time but operators read nextRunAt from the UI.
        task.nextRunAt = spec.scheduledAt() != null ? spec.scheduledAt() : Instant.now();
        return true;
    }

    /**
     * Name update: present-and-non-blank renames the task. Unlike description,
     * name cannot be cleared — present-and-null or present-and-blank is a 400
     * (mirrors create's {@link #requireTaskName}). A rename of a recurring task
     * re-checks the duplicate-name rule via {@link #rejectDuplicateRecurringRename}.
     * Must run AFTER {@link #applyScheduleUpdate} so that check sees the task's
     * effective type when the same PATCH also changes the schedule.
     *
     * @return true if the name field was present (and applied).
     */
    private static boolean applyNameUpdate(Task task, JsonObject body) {
        if (!body.has(KEY_NAME)) return false;
        var el = body.get(KEY_NAME);
        var name = el.isJsonNull() ? null : el.getAsString();
        if (name == null || name.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "name must be non-blank");
            return false; // unreachable — error() throws
        }
        rejectDuplicateRecurringRename(name, task);
        task.name = name;
        return true;
    }

    /**
     * Apply every non-schedule patchable field present in the body.
     *
     * @return true if any field was touched.
     */
    private static boolean applyOptionalFieldUpdates(Task task, JsonObject body) {
        // Split per-field-kind so each helper stays well under the cognitive-
        // complexity bar; the boolean OR reduction below preserves "any field
        // touched" semantics without short-circuiting.
        boolean changed = applyOptionalStringFields(task, body);
        changed |= applyOptionalBooleanFields(task, body);
        changed |= applyOptionalIntegerFields(task, body);
        return changed;
    }

    /**
     * Optional-string fields: present-and-null clears, present-and-blank
     * also clears (readOptionalString collapses both to null). Description
     * uniquely coerces null → "" instead of leaving it null.
     */
    private static boolean applyOptionalStringFields(Task task, JsonObject body) {
        boolean changed = false;
        if (body.has(KEY_DESCRIPTION)) {
            var v = readOptionalString(body, KEY_DESCRIPTION);
            task.description = v != null ? v : "";
            changed = true;
        }
        if (body.has(KEY_DELIVERY))            { task.delivery          = readOptionalString(body, KEY_DELIVERY);            changed = true; }
        if (body.has(KEY_PAYLOAD_TYPE))        { task.payloadType       = readOptionalString(body, KEY_PAYLOAD_TYPE);        changed = true; }
        if (body.has(KEY_MODEL_PROVIDER))      { task.modelProvider     = readOptionalString(body, KEY_MODEL_PROVIDER);      changed = true; }
        if (body.has(KEY_MODEL_ID))            { task.modelId           = readOptionalString(body, KEY_MODEL_ID);            changed = true; }
        if (body.has(KEY_ENABLED_TOOL_NAMES))  { task.enabledToolNames  = readOptionalString(body, KEY_ENABLED_TOOL_NAMES);  changed = true; }
        if (body.has(KEY_WORKDIR))             { task.workdir           = readOptionalString(body, KEY_WORKDIR);             changed = true; }
        if (body.has(KEY_PRE_CHECK))           { task.preCheck          = readOptionalString(body, KEY_PRE_CHECK);           changed = true; }
        if (body.has(KEY_SCRIPT))              { task.script            = readOptionalString(body, KEY_SCRIPT);              changed = true; }
        if (body.has(KEY_CONTEXT_FROM_TASK_IDS)){ task.contextFromTaskIds= readOptionalString(body, KEY_CONTEXT_FROM_TASK_IDS);changed = true; }
        return changed;
    }

    /**
     * Boolean fields. Explicit null is rejected (no meaningful semantic) —
     * callers should omit instead.
     */
    private static boolean applyOptionalBooleanFields(Task task, JsonObject body) {
        boolean changed = false;
        if (body.has(KEY_PAUSED) && !body.get(KEY_PAUSED).isJsonNull()) {
            task.paused = body.get(KEY_PAUSED).getAsBoolean();
            changed = true;
        }
        if (body.has(KEY_NO_AGENT) && !body.get(KEY_NO_AGENT).isJsonNull()) {
            task.noAgent = body.get(KEY_NO_AGENT).getAsBoolean();
            changed = true;
        }
        if (body.has(KEY_AUTO_DELETE) && !body.get(KEY_AUTO_DELETE).isJsonNull()) {
            task.autoDeleteOnComplete = body.get(KEY_AUTO_DELETE).getAsBoolean();
            changed = true;
        }
        return changed;
    }

    /** Integer fields. Explicit null clears (sets back to unlimited). */
    private static boolean applyOptionalIntegerFields(Task task, JsonObject body) {
        if (!body.has(KEY_REPEAT_LIMIT)) return false;
        var el = body.get(KEY_REPEAT_LIMIT);
        task.repeatLimit = el.isJsonNull() ? null : el.getAsInt();
        return true;
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Cancel a PENDING or ACTIVE task by id (sets status CANCELLED and unschedules)")
    public static void cancel(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        // Both PENDING (one-shot waiting) and ACTIVE (recurring ongoing)
        // are cancellable; other states (RUNNING, terminal) are not.
        if (task.status != Task.Status.PENDING && task.status != Task.Status.ACTIVE) {
            badRequest();
        }
        task.status = Task.Status.CANCELLED;
        task.save();
        TaskSchedulingService.cancel(task.id);
        EventLogger.info("TASK_MGMT_DELETE",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) cancelled via API".formatted(task.name, task.id));
        renderJSON(gson.toJson(TaskView.of(task)));
    }

    /**
     * DELETE /api/tasks/{id} — hard-delete a task and its run history.
     *
     * <p>Distinct from {@link #cancel(Long)}: cancel sets the task to
     * {@link Task.Status#CANCELLED} (row stays, can be revived via
     * runNow); delete removes the Task row entirely along with every
     * {@link TaskRun} and {@link TaskRunMessage} that references it.
     * Use cancel for "stop running, I might want this back"; use
     * delete for "I'm done with this, get rid of the row".
     *
     * <p>FK chain (NOT NULL on both sides):
     * {@code TaskRunMessage → TaskRun → Task}.  JPQL bulk deletes
     * cover the descendants in two queries regardless of run-count,
     * then the scheduler row is dropped (idempotent), then the Task
     * row itself.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200")
    @Operation(summary = "Hard-delete a task by id along with its runs, messages, notifications, and scheduler row")
    public static void delete(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();

        var agentName = task.agent != null ? task.agent.name : null;
        var taskName = task.name;
        var taskId = task.id;
        final String taskIdParam = "taskId";

        var em = JPA.em();
        em.createQuery("DELETE FROM TaskRunMessage m WHERE m.taskRun.task.id = :taskId")
                .setParameter(taskIdParam, taskId).executeUpdate();
        em.createQuery("DELETE FROM TaskRun r WHERE r.task.id = :taskId")
                .setParameter(taskIdParam, taskId).executeUpdate();
        // Cascade-delete user-visible notifications that originated from this
        // task. Reminder tasks (payloadType="reminder") emit one Notification
        // per fire; when the operator deletes the task the toast/Reminders
        // surface should clear in lockstep so the row doesn't reappear after
        // the next poll. Safe for non-reminder tasks too — they don't write
        // Notifications, so this is a no-op.
        em.createQuery("DELETE FROM Notification n WHERE n.sourceTaskId = :taskId")
                .setParameter(taskIdParam, taskId).executeUpdate();
        em.flush();

        // Idempotent — harmless if the task is already in a terminal
        // state and its scheduler row was already removed by cancel.
        TaskSchedulingService.cancel(taskId);

        task.delete();
        EventLogger.info("TASK_MGMT_HARD_DELETE", agentName, null,
                "Task '%s' (id=%d) hard-deleted via API".formatted(taskName, taskId));
        renderJSON("{\"status\":\"deleted\",\"id\":" + taskId + "}");
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
        var em = JPA.em();
        var msgQ = em.createQuery("DELETE FROM TaskRunMessage m WHERE m.taskRun.status <> :running"
                        + payloadTypeWhere("m.taskRun.task", payloadType, excludePayloadType))
                .setParameter(PARAM_RUNNING, TaskRun.Status.RUNNING);
        bindPayloadType(msgQ, payloadType, excludePayloadType);
        msgQ.executeUpdate();

        var runQ = em.createQuery("DELETE FROM TaskRun r WHERE r.status <> :running"
                        + payloadTypeWhere(RUN_TASK_ALIAS, payloadType, excludePayloadType))
                .setParameter(PARAM_RUNNING, TaskRun.Status.RUNNING);
        bindPayloadType(runQ, payloadType, excludePayloadType);
        int deleted = runQ.executeUpdate();
        em.flush();

        EventLogger.info("TASK_MGMT_RESET_STATS", null, null,
                "Reset task stats: deleted %d terminal task run(s)".formatted(deleted));
        renderJSON("{\"status\":\"reset\",\"deletedRuns\":" + deleted + "}");
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Pause a PENDING or ACTIVE task by id so it won't fire until resumed")
    public static void pause(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        // Pause only applies to live tasks (PENDING one-shot waiting / ACTIVE
        // recurring ongoing); pausing a terminal Task has no effect since the
        // scheduler row is already gone.
        if (task.status != Task.Status.PENDING && task.status != Task.Status.ACTIVE) {
            badRequest();
        }
        TaskSchedulingService.pause(task.id);
        EventLogger.info("TASK_MGMT_PAUSE",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) paused via API".formatted(task.name, task.id));
        // Re-read so the response reflects the flipped flag.
        renderJSON(gson.toJson(TaskView.of(TaskService.findById(task.id))));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Resume a paused PENDING or ACTIVE task by id so it fires on schedule again")
    public static void resume(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        // Resume mirrors pause — accept PENDING or ACTIVE alive states.
        if (task.status != Task.Status.PENDING && task.status != Task.Status.ACTIVE) {
            badRequest();
        }
        TaskSchedulingService.resume(task.id);
        EventLogger.info("TASK_MGMT_RESUME",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) resumed via API".formatted(task.name, task.id));
        renderJSON(gson.toJson(TaskView.of(TaskService.findById(task.id))));
    }

    /**
     * POST /api/tasks/{id}/reenable — re-arm a CANCELLED task's schedule
     * <em>without</em> an immediate fire. Revives status to the type's
     * initial live state (recurring → ACTIVE, one-shot → PENDING), clears any
     * stale paused flag, and registers a fresh scheduler row at the next
     * natural fire ({@link services.TaskSchedulingService#register}): CRON
     * resumes at its next cron occurrence, INTERVAL at the next interval
     * boundary, a SCHEDULED one-shot at its original time (or immediately if
     * that time has already passed).
     *
     * <p>Distinct from {@link #run} (revives AND fires immediately) and
     * {@link #retry} (the FAILED/LOST "reset retryCount and rerun" path).
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Re-arm a CANCELLED task's schedule at its next natural fire without firing immediately")
    public static void reenable(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        // Re-enable only applies to a CANCELLED task: reviving a live Task
        // would double-schedule, and reviving a COMPLETED/FAILED one would
        // resurrect a finished fire. Anything else is a client error.
        if (task.status != Task.Status.CANCELLED) {
            badRequest();
        }
        task.status = Task.initialStatusFor(task.type);
        task.paused = false;
        task.save();
        TaskSchedulingService.register(task);
        EventLogger.info("TASK_MGMT_REENABLE",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) re-enabled via API".formatted(task.name, task.id));
        renderJSON(gson.toJson(TaskView.of(TaskService.findById(task.id))));
    }

    /**
     * Operator-initiated immediate fire — distinct from {@link #retry} which
     * is the FAILED-only "fix and rerun" path. Accepts any current status
     * per AC; revives a CANCELLED task to PENDING first so
     * {@link services.TaskExecutionHandler}'s CANCELLED-skip doesn't
     * swallow the fire. Other terminal states (COMPLETED, FAILED) don't
     * trigger that skip so their status stays intact — re-firing a
     * COMPLETED task is a deliberate operator action and the audit-log
     * record alone is the trail.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Fire a task immediately by id, reviving a CANCELLED task first so the run isn't skipped")
    public static void run(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();

        boolean revivedFromCancel = false;
        if (task.status == Task.Status.CANCELLED) {
            // Recurring tasks resume as ACTIVE, one-shot as PENDING.
            task.status = Task.initialStatusFor(task.type);
            task.save();
            revivedFromCancel = true;
        }

        TaskSchedulingService.runNow(task.id);

        EventLogger.info("TASK_MGMT_MANUAL_RUN",
                task.agent != null ? task.agent.name : null, null,
                ("Task '%s' (id=%d) run-now via API" + (revivedFromCancel ? " (revived from CANCELLED)" : ""))
                        .formatted(task.name, task.id));

        renderJSON(gson.toJson(TaskView.of(task)));
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

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TaskView.class)))
    @Operation(summary = "Retry a FAILED or LOST task by id (reset retryCount/lastError and re-register to fire now)")
    public static void retry(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        // JCLAW-258 extends retry to accept LOST in addition to FAILED.
        // FAILED: no scheduled_tasks row (it was removed when the failure
        // terminated the previous fire) — register() inserts a fresh row.
        // LOST: scheduled_tasks row still exists (Design A keeps it intact
        // for db-scheduler's own recovery); operator click pre-empts that
        // auto-recovery by cancelling the stale row and registering a
        // fresh one via update().
        boolean wasLost = task.status == Task.Status.LOST;
        if (task.status != Task.Status.FAILED && !wasLost) {
            badRequest();
        }
        task.retryCount = 0;
        // Recurring tasks return to ACTIVE, one-shot to PENDING.
        task.status = Task.initialStatusFor(task.type);
        task.nextRunAt = Instant.now();
        task.lastError = null;
        task.save();
        if (wasLost) {
            // LOST rows are picked=true with a stale heartbeat — the regular
            // cancel API refuses to touch them. forceRemoveStaleRow deletes
            // the row directly so the subsequent register inserts a clean
            // new fire row without tripping the PK constraint.
            TaskSchedulingService.forceRemoveStaleRow(task.id);
        }
        TaskSchedulingService.register(task);
        // /retry is operator-initiated re-firing — same intent as /run from
        // the audit timeline's POV. The mechanical distinction (resets
        // retryCount/lastError, FAILED/LOST-only guard) is internal; the
        // operator simply "ran a previously-stuck task again", which maps
        // to the MANUAL_RUN category.
        EventLogger.info("TASK_MGMT_MANUAL_RUN",
                task.agent != null ? task.agent.name : null, null,
                "Task '%s' (id=%d) retried via API (was %s)"
                        .formatted(task.name, task.id, wasLost ? "LOST" : "FAILED"));
        renderJSON(gson.toJson(TaskView.of(task)));
    }

}
