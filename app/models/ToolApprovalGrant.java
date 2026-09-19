package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;

import java.util.List;

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
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

    @Column(name = "tool_name", nullable = false)
    public String toolName;

    /**
     * JCLAW-1226: the channel the approval was tapped on, and the chat/channel it was
     * tapped in. Provenance for the operator reviewing the list — the gate keys off
     * {@code (agent, tool)} and decides spendability from the <em>current</em> turn's
     * origin trust, not from these. Nullable: rows written before JCLAW-1226 have neither.
     */
    @Column(name = "granting_channel")
    public String grantingChannel;

    /** The chat/channel id {@link #grantingChannel} raised the prompt in. */
    @Column(name = "granting_peer")
    public String grantingPeer;

    /** True when a durable always-grant exists for {@code (agentId, toolName)}. */
    public static boolean exists(Long agentId, String toolName) {
        return count("agent.id = ?1 AND toolName = ?2", agentId, toolName) > 0;
    }

    /**
     * JCLAW-1062: drop the always-grant for {@code (agentId, toolName)}.
     *
     * <p>Not sufficient on its own: {@link agents.DangerousActionGate} ORs this row
     * against an in-process cache that the tap also wrote, so every caller must pair
     * this with {@link agents.DangerousActionGate#revokeGrant} (JCLAW-1226).
     *
     * @return true if a row was removed; false when no grant existed, which lets the
     * caller answer 404 rather than report a revoke that revoked nothing
     */
    public static boolean revoke(Long agentId, String toolName) {
        return ToolApprovalGrant.delete("agent.id = ?1 AND toolName = ?2", agentId, toolName) > 0;
    }

    /** Standing grants for one agent, newest first. */
    public static List<ToolApprovalGrant> findByAgent(Long agentId) {
        return ToolApprovalGrant.find("agent.id = ?1 ORDER BY toolName", agentId).fetch();
    }

    /** Every standing grant, for the instance-wide roll-up. Ordered so the view is stable. */
    public static List<ToolApprovalGrant> findAllGrants() {
        return ToolApprovalGrant.find("ORDER BY agent.name, toolName").fetch();
    }

    /**
     * Persist an always-grant for {@code (agent, toolName)}, idempotent on the
     * unique key: a no-op if a row already exists.
     *
     * @param grantingChannel the channel the approval was tapped on
     * @param grantingPeer    the chat/channel id it was tapped in, or {@code null}
     */
    public static void upsert(Agent agent, String toolName, String grantingChannel, String grantingPeer) {
        if (exists(agent.id, toolName)) return;
        try {
            var grant = new ToolApprovalGrant();
            grant.agent = agent;
            grant.toolName = toolName;
            grant.grantingChannel = grantingChannel;
            grant.grantingPeer = grantingPeer;
            grant.save();
        } catch (PersistenceException _) {
            // A concurrent upsert inserted the same (agent, tool) first and the unique
            // index rejected this one — idempotent, so treat the collision as success.
        }
    }
}
