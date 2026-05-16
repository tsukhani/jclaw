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
 * task fires that {@link Message} plays for conversation messages.
 *
 * <p>Column shape deliberately matches {@code Message} (minus
 * conversation-only fields like attachments, subagentRunId, messageKind,
 * metadata): {@code content}, {@code tool_calls}, {@code tool_results},
 * {@code tool_result_structured}, {@code usage_json}, {@code reasoning},
 * {@code truncated}. Same data shape means JCLAW-22's PeekPanel can render
 * both Message rows and TaskRunMessage rows through one component, and the
 * existing pattern AgentRunner uses to write Message rows ports cleanly
 * to TaskRunSink writing TaskRunMessage rows.
 *
 * <p>{@link MessageRole} is reused (USER, ASSISTANT, TOOL, SYSTEM) — a
 * divergence in role vocabulary between conversations and task runs would
 * create a translation layer with no business reason to exist.
 *
 * <p>The composite index on {@code (task_run_id, turn_index)} matches the
 * PeekPanel's expected "load all messages for this TaskRun in order"
 * query. The content column will additionally be registered with H2's
 * FullTextLucene (FTL_*) index from {@code FullTextSearchInitJob} in a
 * subsequent JCLAW-21 commit; no JPA-level {@code @Index} is declared
 * for it because Hibernate doesn't manage the FTL_* index.
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

    @Column(name = "tool_calls", columnDefinition = "TEXT")
    public String toolCalls;

    @Column(name = "tool_results", columnDefinition = "TEXT")
    public String toolResults;

    /**
     * Optional structured JSON payload for tool-result rows (mirrors
     * {@link Message#toolResultStructured}). Null for tools that don't
     * produce structured output. The LLM never sees this column — it
     * only rehydrates the plain-text {@link #content}.
     */
    @Column(name = "tool_result_structured", columnDefinition = "TEXT")
    public String toolResultStructured;

    /** JSON-serialized usage metrics (tokens, cost, duration) from the LLM response. */
    @Column(name = "usage_json", columnDefinition = "TEXT")
    public String usageJson;

    /**
     * Streamed reasoning / extended-thinking text for assistant turns that
     * ran with thinking enabled. Null for user/tool rows and for assistant
     * turns that emitted no reasoning.
     */
    @Column(columnDefinition = "TEXT")
    public String reasoning;

    /**
     * Mirror of {@link Message#truncated} — true when the LLM hit
     * {@code finish_reason=length}. Default false; only the assistant
     * turn that hit the cap flips it.
     */
    @Column(name = "truncated", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    public boolean truncated;

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
