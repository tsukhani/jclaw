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
    public static List<Task> findPendingDue() {
        // Status passed as String (.name()) to avoid the same classloader trap
        // hitting the enum-parameter binding path. Native SQL compares VARCHAR
        // to VARCHAR; no enum reflection involved.
        return play.db.jpa.JPA.em()
                .createNativeQuery("SELECT * FROM task WHERE status = ?1 AND next_run_at <= ?2")
                .unwrap(org.hibernate.query.NativeQuery.class)
                .addEntity(TASK_ENTITY_NAME)
                .setParameter(1, Status.PENDING.name())
                .setParameter(2, Instant.now())
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    public static List<Task> findByStatus(Status status) {
        return play.db.jpa.JPA.em()
                .createNativeQuery("SELECT * FROM task WHERE status = ?1")
                .unwrap(org.hibernate.query.NativeQuery.class)
                .addEntity(TASK_ENTITY_NAME)
                .setParameter(1, status.name())
                .getResultList();
    }

    public static List<Task> findRecurring() {
        return Task.find("type = ?1 AND status != ?2",
                Type.CRON, Status.CANCELLED).fetch();
    }
}
