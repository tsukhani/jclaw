package models;

import jakarta.persistence.*;
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
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

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

    @Column(name = "retry_count", nullable = false)
    public int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    public int maxRetries = 3;

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;

    @Column(name = "next_run_at")
    public Instant nextRunAt;

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
