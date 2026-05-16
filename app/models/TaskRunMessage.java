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
import java.util.List;

/**
 * One turn within a {@link TaskRun}'s transcript. Plays the same role for
 * task fires that {@link Message} plays for conversation messages: stores
 * the per-turn content, tool-call metadata, and ordering.
 *
 * <p>Reuses {@link MessageRole} for the role column — the semantic meaning
 * is identical to a Conversation's Message (USER, ASSISTANT, TOOL, SYSTEM)
 * even though the storage table is separate. Coupling the enum is
 * intentional: a divergence in role vocabulary between conversations and
 * task runs would create a translation layer with no business reason to
 * exist.
 *
 * <p>The composite index on {@code (task_run_id, turn_index)} matches the
 * PeekPanel's expected query shape: "load all messages for this TaskRun
 * in order." The content column will additionally be registered with H2's
 * FullTextLucene (FTL_*) index from {@code FullTextSearchInitJob} in a
 * subsequent JCLAW-21 commit; no JPA-level {@code @Index} is declared for
 * it because Hibernate doesn't manage the FTL_* index.
 *
 * <p>Part of JCLAW-21's Tasks foundation. Schema is managed by Hibernate
 * auto-DDL ({@code jpa.ddl=update}); no separate migration file required.
 */
@Entity
@Table(name = "task_run_message", indexes = {
        @Index(name = "idx_task_run_message_run_turn", columnList = "task_run_id,turn_index")
})
public class TaskRunMessage extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "task_run_id", nullable = false)
    public TaskRun taskRun;

    @Column(name = "turn_index", nullable = false)
    public int turnIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public MessageRole role;

    @Column(columnDefinition = "TEXT")
    public String content;

    @Column(name = "tool_name")
    public String toolName;

    @Column(name = "tool_args_json", columnDefinition = "TEXT")
    public String toolArgsJson;

    @Column(name = "tool_result", columnDefinition = "TEXT")
    public String toolResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    /**
     * All messages for the given TaskRun in turn order. The PeekPanel
     * (JCLAW-22) loads via this method to render the per-fire transcript.
     */
    public static List<TaskRunMessage> findByTaskRun(TaskRun taskRun) {
        return TaskRunMessage.<TaskRunMessage>find(
                "taskRun = ?1 ORDER BY turnIndex ASC", taskRun).fetch();
    }
}
