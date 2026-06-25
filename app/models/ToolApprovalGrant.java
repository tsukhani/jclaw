package models;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Column;
import play.db.jpa.Model;

/**
 * JCLAW-385: a durable, restart-surviving record that a dangerous tool has
 * been approved "always" for an agent. Presence of a {@code (agent, toolName)}
 * row means {@link agents.DangerousActionGate} must not re-prompt for that
 * pair — the persisted twin of the in-process session set, so an
 * {@code APPROVED_ALWAYS} tap keeps suppressing the prompt after a JVM restart
 * (when the in-memory set is empty again).
 *
 * <p>Only {@code APPROVED_ALWAYS} writes a row; {@code APPROVED_SESSION} stays
 * in-process only and dies with the JVM, matching the deliberately ephemeral
 * session scope.
 */
@Entity
@Table(name = "tool_approval_grant", indexes = {
        @Index(name = "idx_tool_approval_grant_agent", columnList = "agent_id"),
        @Index(name = "idx_tool_approval_grant_unique", columnList = "agent_id,tool_name", unique = true)
})
public class ToolApprovalGrant extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    public Agent agent;

    @Column(name = "tool_name", nullable = false)
    public String toolName;

    /** True when a durable always-grant exists for {@code (agentId, toolName)}. */
    public static boolean exists(Long agentId, String toolName) {
        return count("agent.id = ?1 AND toolName = ?2", agentId, toolName) > 0;
    }

    /**
     * Persist an always-grant for {@code (agent, toolName)}, idempotent on the
     * unique key: a no-op if a row already exists.
     */
    public static void upsert(Agent agent, String toolName) {
        if (exists(agent.id, toolName)) return;
        try {
            var grant = new ToolApprovalGrant();
            grant.agent = agent;
            grant.toolName = toolName;
            grant.save();
        } catch (jakarta.persistence.PersistenceException _) {
            // A concurrent upsert inserted the same (agent, tool) first and the unique
            // index rejected this one — idempotent, so treat the collision as success.
        }
    }
}
