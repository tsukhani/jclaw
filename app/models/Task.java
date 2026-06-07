package models;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "task", indexes = {
        @Index(name = "idx_task_status_next_run", columnList = "status,next_run_at")
})
public class Task extends Model {

    /**
     * Fire-shape enum. JCLAW-21 introduced INTERVAL as the fourth value
     * to express "recurring every N seconds" — complements CRON
     * (calendar-aligned recurrence) for the simple-fixed-period case
     * where a cron expression is overkill (e.g. "every 5 minutes" is
     * cleaner as INTERVAL than the cron equivalent).
     */
    public enum Type { IMMEDIATE, SCHEDULED, INTERVAL, CRON }

    /**
     * Task lifecycle state.
     *
     * <p>Two non-running "alive" states distinguish one-shot vs recurring:
     * <ul>
     *   <li>{@code PENDING} — one-shot ({@code IMMEDIATE} / {@code SCHEDULED})
     *       waiting to fire. Terminal-bound: transitions {@code PENDING →
     *       RUNNING → COMPLETED} (or {@code FAILED}). Once a one-shot fires
     *       successfully it stops being PENDING forever.</li>
     *   <li>{@code ACTIVE} — recurring ({@code CRON} / {@code INTERVAL})
     *       in its steady-state. Cycle: {@code ACTIVE → RUNNING → ACTIVE}.
     *       The Task row is the recurrence config; the {@code scheduled_tasks}
     *       row carries the next fire time. Recurring tasks never reach
     *       {@code COMPLETED} unless explicitly cancelled.</li>
     * </ul>
     *
     * <p>JCLAW-258 introduced {@code LOST} between {@code RUNNING} and
     * {@code FAILED}: a Task observed as {@code RUNNING} whose
     * db-scheduler {@code scheduled_tasks.last_heartbeat} has gone stale
     * (older than {@link services.LostTaskDetector#STALE_THRESHOLD}) is
     * reconciled to LOST for operator visibility. db-scheduler's own
     * dead-execution detection (at the longer heartbeat-misses threshold)
     * subsequently re-fires the row, transitioning LOST → RUNNING →
     * COMPLETED/FAILED. LOST is therefore visibility-only, not terminal —
     * Design A in the ticket. Enum ordering keeps status-pill rendering
     * on a natural progression (idle → ongoing → mid-fire → trouble →
     * terminal).
     */
    public enum Status { PENDING, ACTIVE, RUNNING, LOST, COMPLETED, FAILED, CANCELLED }

    /**
     * Initial status for a freshly-created Task. Recurring types
     * ({@code CRON} / {@code INTERVAL}) start at {@link Status#ACTIVE};
     * one-shot types ({@code IMMEDIATE} / {@code SCHEDULED}) start at
     * {@link Status#PENDING}. Used by every code path that constructs
     * new Tasks (and by {@code retry} / un-cancel paths to put a
     * resumed task back into its "alive" state).
     */
    public static Status initialStatusFor(Type type) {
        return (type == Type.CRON || type == Type.INTERVAL) ? Status.ACTIVE : Status.PENDING;
    }

    @ManyToOne
    @JoinColumn(name = "agent_id")
    public Agent agent;

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Type type;

    @Column(name = "cron_expression")
    public String cronExpression;

    /**
     * For {@link Type#INTERVAL} Tasks: the recurrence period in
     * seconds. Mandatory when {@code type=INTERVAL}; null otherwise.
     * Validation happens at scheduling time
     * ({@code TaskSchedulingService.computeFirstFire}) — the entity
     * itself accepts null so a malformed Task can be persisted long
     * enough to be flagged in the UI rather than failing at save.
     */
    @Column(name = "interval_seconds")
    public Long intervalSeconds;

    @Column(name = "scheduled_at")
    public Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.PENDING;

    /**
     * Pause flag. When {@code true}, {@code TaskExecutionHandler}
     * skips the fire body and re-schedules the next occurrence (for
     * INTERVAL/CRON) or removes the row (for IMMEDIATE/SCHEDULED)
     * without invoking {@code TaskExecutor.runTask}. Operators flip
     * this via {@code TaskSchedulingService.pause(taskId)} /
     * {@code resume(taskId)}; toggling does NOT cancel the
     * scheduled_tasks row, so resuming on a CRON Task picks up its
     * existing cadence without operator-side rescheduling.
     */
    @Column(nullable = false)
    public boolean paused = false;

    /**
     * When true, a one-shot reminder is hard-deleted (task + run history) after
     * a successful COMPLETED fire — a fired one-off reminder has served its
     * purpose and need not linger. Defaults true for reminders at creation and
     * false for regular tasks (which keep their audit history). Enforcement
     * (in {@code TaskExecutor}) is gated on reminder + one-shot type, so it
     * never removes a recurring reminder (which never completes) or a regular
     * task. The user-visible Notification (the nudge) is preserved.
     */
    // @ColumnDefault is load-bearing, not cosmetic: without a DEFAULT, Hibernate
    // emits `ADD COLUMN ... boolean NOT NULL`, which fails when the task table
    // already has rows (can't backfill NULL into a NOT NULL column) — the DDL
    // rolls back, the column never lands, and every Task query then errors on
    // the missing column. The DEFAULT lets the migration backfill existing rows.
    // Any future NOT-NULL column added to an already-populated table needs this.
    @Column(name = "auto_delete_on_complete", nullable = false)
    @ColumnDefault("false")
    public boolean autoDeleteOnComplete = false;

    @Column(name = "retry_count", nullable = false)
    public int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    public int maxRetries = 3;

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;

    @Column(name = "next_run_at")
    public Instant nextRunAt;

    /**
     * Operator's raw schedule input ("now", "every 30m", "0 0 9 * * *",
     * "@daily", etc.) preserved verbatim for round-trip rendering in the
     * UI and CLI. Operator types "every 30m" → sees "every 30m" back,
     * not the normalized "intervalSeconds=1800". Set by
     * {@link services.ScheduleShorthandParser}; consumed by the Tasks
     * page's schedule column and the chat tool's listRecurringTasks
     * output.
     */
    @Column(name = "schedule_display")
    public String scheduleDisplay;

    /**
     * JCLAW-294 plumbing for JCLAW-295 (delivery layer). Channel +
     * identifier route for the task's output, e.g. "telegram:12345" or
     * "email:foo@bar.com". Stored verbatim; parsed by the
     * {@code DeliveryDispatcher} that JCLAW-295 wires in. Null = no
     * delivery (operator reads the TaskRun row directly).
     */
    @Column(name = "delivery")
    public String delivery;

    /**
     * JCLAW-294 plumbing for JCLAW-295 (delivery layer). Output format
     * hint for the delivery layer — "text", "json", "markdown". Channel
     * adapters render the TaskRun's outputSummary accordingly.
     */
    @Column(name = "payload_type", length = 50)
    public String payloadType;

    /**
     * JCLAW-294 plumbing for JCLAW-296 (cost-saving extensions). When
     * set, overrides the agent's default LLM provider for this task's
     * fires. AgentRunner.runForTask currently reads agent.modelProvider;
     * JCLAW-296 will check task.modelProvider first and fall back.
     */
    @Column(name = "model_provider", length = 100)
    public String modelProvider;

    /**
     * JCLAW-294 plumbing for JCLAW-296 (cost-saving extensions). Model
     * id override paired with modelProvider above.
     */
    @Column(name = "model_id", length = 100)
    public String modelId;

    /**
     * JCLAW-294 plumbing for JCLAW-297 (security: per-task toolset
     * restriction). JSON-encoded array of tool names the agent is
     * allowed to use during this task's fires. Null = no per-task
     * restriction (agent's full toolset applies). ToolRegistry filtering
     * is wired by JCLAW-297.
     */
    @Column(name = "enabled_tool_names", columnDefinition = "TEXT")
    public String enabledToolNames;

    /**
     * JCLAW-294 plumbing for JCLAW-298 (advanced features). Working
     * directory for the task fire — script-mode and file-scoped tasks
     * resolve relative paths against this. Filesystem paths can be
     * long; sized at 500 chars rather than the default 255.
     */
    @Column(name = "workdir", length = 500)
    public String workdir;

    /**
     * JCLAW-294 plumbing for JCLAW-296 (pre-check). Pre-fire condition
     * expression/script. If non-null, evaluated before each fire; a
     * falsy result skips the fire without consuming a retry budget.
     * JCLAW-296 owns the evaluator wiring.
     */
    @Column(name = "pre_check", columnDefinition = "TEXT")
    public String preCheck;

    /**
     * JCLAW-294 plumbing for JCLAW-296 (script mode). Shell script body.
     * When non-null and {@link #noAgent} is true, the task fire execs
     * this script instead of invoking the LLM agent — the Hermes-parity
     * cost-saving path for tasks that don't need reasoning. JCLAW-296
     * owns the exec wiring.
     */
    @Column(name = "script", columnDefinition = "TEXT")
    public String script;

    /**
     * JCLAW-294 plumbing for JCLAW-296 (no-agent payload). When true,
     * the task fire skips the LLM round-trip entirely — either runs
     * {@link #script} (if set) or delivers {@link #description}
     * verbatim. Default false: tasks go through the agent like JCLAW-21.
     *
     * <p>{@code @ColumnDefault("false")} is load-bearing: without it,
     * Hibernate's hbm2ddl=update on existing DBs fails to add this NOT
     * NULL column (existing rows have nothing to populate it with).
     * The DEFAULT clause lets the ALTER succeed by backfilling
     * existing rows before the constraint is enforced.
     */
    @Column(name = "no_agent", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    public boolean noAgent = false;

    /**
     * JCLAW-294 plumbing for JCLAW-298 (contextFrom). JSON-encoded array
     * of Long Task ids whose latest TaskRun outputs should be available
     * as context for this task's fires. JCLAW-298 owns the
     * context-assembly wiring.
     */
    @Column(name = "context_from_task_ids", columnDefinition = "TEXT")
    public String contextFromTaskIds;

    /**
     * JCLAW-294 plumbing for JCLAW-298 (repeat limit). Max fires for a
     * recurring (CRON/INTERVAL) task before auto-cancellation. Null =
     * unlimited (the JCLAW-21 default). JCLAW-298 wires the fire-counter
     * check in TaskExecutionHandler.
     */
    @Column(name = "repeat_limit")
    public Integer repeatLimit;

    /**
     * JCLAW-261: optional IANA timezone (e.g. {@code "America/New_York"},
     * {@code "Asia/Tokyo"}) for {@link Type#CRON} and {@link Type#SCHEDULED}
     * fire-time resolution. When non-null, the scheduler interprets the
     * cron expression / {@link #scheduledAt} wall-clock in this zone.
     * When null, the resolver falls back through:
     * <ol>
     *   <li>Config row {@code tasks.defaultTimezone} (operator-set default)</li>
     *   <li>application.conf {@code tasks.defaultTimezone}</li>
     *   <li>{@code ZoneId.systemDefault()}</li>
     * </ol>
     * {@link Type#INTERVAL} and {@link Type#IMMEDIATE} ignore this field —
     * their fire schedule is duration-based, with no wall-clock dependence.
     */
    @Column(name = "timezone", length = 64)
    public String timezone;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * JCLAW-304: mirror this row into the Lucene full-text index under
     * {@link services.search.LuceneIndexer.Scope#TASK} as a virtual
     * document combining {@link #name} and {@link #description}. Same
     * no-throw contract as the TaskRunMessage hook — the indexer
     * catches and logs failures internally so a transient FS issue
     * never aborts the parent JPA transaction.
     */
    @PostPersist
    @PostUpdate
    void onIndexUpsert() {
        if (id != null) {
            var n = name != null ? name : "";
            var d = description != null ? description : "";
            services.search.LuceneIndexer.upsert(
                    services.search.LuceneIndexer.Scope.TASK,
                    id, n + " " + d);
        }
    }

    @PostRemove
    void onIndexRemove() {
        if (id != null) {
            services.search.LuceneIndexer.remove(
                    services.search.LuceneIndexer.Scope.TASK, id);
        }
    }

    /**
     * Identify the Task entity to Hibernate by NAME rather than by class literal.
     * In Play 1.x the {@code ApplicationClassloader} can cycle (dev-mode source
     * reload, prod-mode init/teardown races between precompile and runtime),
     * leaving Hibernate's metamodel holding {@code Task.class} from one
     * classloader instance while a runtime call site captures the literal from
     * another. Both classes are named {@code models.Task} but
     * {@code clazz1 != clazz2}, so the typed query fails with
     * "Type specified for TypedQuery [models.Task] is incompatible with the
     * query return type of the same name". Using
     * {@link org.hibernate.query.NativeQuery#addEntity(String)} sidesteps the
     * mismatch — Hibernate looks the entity up in its own metadata by string
     * name, so whichever Class instance the metamodel holds is the one applied.
     */
    private static final String TASK_ENTITY_NAME = "models.Task";

    @SuppressWarnings("unchecked")
    public static List<Task> findByStatus(Status status) {
        return play.db.jpa.JPA.em()
                .createNativeQuery("SELECT * FROM task WHERE status = ?1")
                .unwrap(org.hibernate.query.NativeQuery.class)
                .addEntity(TASK_ENTITY_NAME)
                .setParameter(1, status.name())
                .getResultList();
    }

    /**
     * Recurring (CRON + INTERVAL) Tasks for the given agent that are
     * still active. Agent-scoped because per the multi-tenancy stance
     * one agent must never see another agent's recurring schedule —
     * see CLAUDE memory project_multi_tenancy_design.
     */
    public static List<Task> findRecurring(models.Agent agent) {
        return Task.find(
                "agent = ?1 AND type IN (?2, ?3) AND status != ?4",
                agent, Type.CRON, Type.INTERVAL, Status.CANCELLED).fetch();
    }
}
