package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.time.Instant;

/**
 * Audit row for a single subagent invocation (JCLAW-264). When a parent agent
 * spawns a child as a subagent (JCLAW-265), one row is written at spawn time
 * with {@link Status#RUNNING} and the start timestamp, then updated when the
 * child terminates with the terminal status and {@link #outcome} (the child's
 * final reply on success, or an error message on failure).
 *
 * <p>The four FKs make the audit log self-contained: which agent spawned
 * which, in which conversation, and where the child's own conversation lives.
 * All four are required (subagents always run inside conversations and always
 * have a parent).
 *
 * <p>Indexed on {@code parent_agent_id} for "show me everything a given
 * parent has spawned," {@code started_at} for time-ordered scans, and
 * {@code status} for filtering running vs. terminal rows.
 *
 * <p>This entity carries no spawning logic — that's JCLAW-265's job.
 */
@Entity
@Table(name = "subagent_run", indexes = {
        @Index(name = "idx_subagent_run_parent_agent", columnList = "parent_agent_id"),
        @Index(name = "idx_subagent_run_started_at", columnList = "started_at"),
        @Index(name = "idx_subagent_run_status", columnList = "status")
})
public class SubagentRun extends Model {

    public enum Status { RUNNING, COMPLETED, FAILED, KILLED, TIMEOUT }

    @ManyToOne(optional = false)
    @JoinColumn(name = "parent_agent_id", nullable = false)
    public Agent parentAgent;

    @ManyToOne(optional = false)
    @JoinColumn(name = "child_agent_id", nullable = false)
    public Agent childAgent;

    @ManyToOne(optional = false)
    @JoinColumn(name = "parent_conversation_id", nullable = false)
    public Conversation parentConversation;

    @ManyToOne(optional = false)
    @JoinColumn(name = "child_conversation_id", nullable = false)
    public Conversation childConversation;

    @Column(name = "started_at", nullable = false, updatable = false)
    public Instant startedAt;

    @Column(name = "ended_at")
    public Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.RUNNING;

    /**
     * The child's final reply when {@link #status} is {@link Status#COMPLETED},
     * or an error / interruption message for the terminal failure statuses.
     * Null while the run is still {@link Status#RUNNING}.
     */
    @Column(columnDefinition = "TEXT")
    public String outcome;

    /**
     * JCLAW-273: yield-resume flag for async spawns. When the parent agent
     * invokes {@code yield_to_subagent} mid-turn with this run id, the tool
     * flips this column to {@code true} and the parent's {@code AgentRunner}
     * loop exits without emitting a final assistant reply. The async-spawn
     * announce VT reads this column after the child terminates and switches
     * the announce-message role from {@code SYSTEM} (the default JCLAW-270
     * fire-and-forget shape) to {@code USER}, then re-invokes
     * {@code AgentRunner.run} on the parent conversation so the parent's
     * logical turn resumes with the child's reply as its next user input.
     *
     * <p>Defaults to {@code false} for every plain async spawn that the
     * parent never yields into; that branch keeps the JCLAW-270 semantics
     * verbatim (system-role announce, no resume call).
     */
    @Column(nullable = false)
    public boolean yielded = false;

    @PrePersist
    void onCreate() {
        if (startedAt == null) startedAt = Instant.now();
    }
}
