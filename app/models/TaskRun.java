package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import play.db.jpa.Model;

import java.time.Instant;

/**
 * One execution of a {@link Task}. Created when db-scheduler fires the task,
 * closed when the agent loop (or script-only path) finishes.
 *
 * <p>A Task has one row in {@code task}; a fire produces one row in
 * {@code task_run} plus N rows in {@code task_run_message}. Recurring tasks
 * (INTERVAL, CRON) produce one TaskRun per fire — the chain of runs across
 * time is the audit trail.
 *
 * <p>{@link #completedAt} is nullable: a row whose status is still
 * {@code RUNNING} has no completion yet, and a fire that died mid-execution
 * (JVM crash before db-scheduler's heartbeat reconciliation) may have a
 * RUNNING status indefinitely until db-scheduler's dead-execution detection
 * reschedules and we leave the dead TaskRun in place as an audit record
 * (with completedAt set to the recovery time by the recovery path).
 *
 * <p>Part of JCLAW-21's Tasks foundation. Schema is managed by Hibernate
 * auto-DDL ({@code jpa.ddl=update}); no separate migration file required.
 */
@Entity
@Table(name = "task_run", indexes = {
        @Index(name = "idx_task_run_task", columnList = "task_id"),
        @Index(name = "idx_task_run_started", columnList = "started_at"),
        @Index(name = "idx_task_run_status", columnList = "status")
})
public class TaskRun extends Model {

    public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }

    public enum DeliveryStatus { NOT_REQUESTED, DELIVERED, NOT_DELIVERED, UNKNOWN }

    @ManyToOne(optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    public Task task;

    @Column(name = "started_at", nullable = false)
    public Instant startedAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.RUNNING;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(columnDefinition = "TEXT")
    public String error;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    public String outputSummary;

    @Column(name = "usage_json", columnDefinition = "TEXT")
    public String usageJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status")
    public DeliveryStatus deliveryStatus;

    @Column(name = "delivery_target", columnDefinition = "TEXT")
    public String deliveryTarget;

    @Column(name = "delivery_error", columnDefinition = "TEXT")
    public String deliveryError;

    @Column(name = "trace_json", columnDefinition = "TEXT")
    public String traceJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (startedAt == null) startedAt = now;
    }
}
