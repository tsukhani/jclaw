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

    public enum Type { IMMEDIATE, SCHEDULED, CRON }
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

    @Column(name = "scheduled_at")
    public Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.PENDING;

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

    public static List<Task> findPendingDue() {
        return Task.find("status = ?1 AND nextRunAt <= ?2",
                Status.PENDING, Instant.now()).fetch();
    }

    public static List<Task> findByStatus(Status status) {
        return Task.find("status", status).fetch();
    }

    public static List<Task> findRecurring() {
        return Task.find("type = ?1 AND status != ?2",
                Type.CRON, Status.CANCELLED).fetch();
    }
}
