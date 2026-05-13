package models;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.JPA;
import play.db.jpa.Model;

import java.util.List;

/**
 * Per-agent snapshot of shell-command contributions granted when an agent
 * installs a registered skill. One row per (agent, skill, command). Rows are
 * written by the skill-install flow from the registry's blessed
 * {@link SkillRegistryTool} rows, and deleted only when the agent removes the
 * skill from its workspace.
 *
 * <p>This table — NOT the workspace {@code SKILL.md} file — is the authority
 * the shell-exec allowlist consults at call time. Persist-at-install makes the
 * allowlist immune to filesystem tampering (an agent with filesystem-write
 * cannot expand its own allowlist by editing its workspace skill manifest)
 * and means registry un-promotion does not retroactively revoke granted
 * commands (rows persist until the agent explicitly removes the skill).
 *
 * <p>Effective allowlist for an agent is:
 * <pre>global shell.allowlist ∪ (AgentSkillAllowedTool rows WHERE agent = :agent
 *                              AND skillName IN enabled AgentSkillConfig.skillName)</pre>
 */
@Entity
@Table(name = "agent_skill_allowed_tool", indexes = {
        @Index(name = "idx_agent_skill_allowed_tool_agent", columnList = "agent_id"),
        @Index(name = "idx_agent_skill_allowed_tool_unique",
                columnList = "agent_id,skill_name,tool_name", unique = true)
})
// JCLAW-205 follow-up: ShellExecTool consults this table on every
// shell command invocation to validate the allowlist union; that's
// hot read traffic. Writes happen at skill install / removal /
// agent deletion only. Like {@link SkillRegistryTool}, the bulk
// JPQL DELETE sites don't invalidate the L2 entity cache on their
// own, so each one explicitly evicts the region.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class AgentSkillAllowedTool extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    public Agent agent;

    @Column(name = "skill_name", nullable = false)
    public String skillName;

    @Column(name = "tool_name", nullable = false)
    public String toolName;

    public static List<AgentSkillAllowedTool> findByAgent(Agent agent) {
        return AgentSkillAllowedTool.find("agent = ?1", agent).fetch();
    }

    public static List<AgentSkillAllowedTool> findByAgentAndSkill(Agent agent, String skillName) {
        return AgentSkillAllowedTool.find("agent = ?1 AND skillName = ?2", agent, skillName).fetch();
    }

    public static void deleteByAgentAndSkill(Agent agent, String skillName) {
        AgentSkillAllowedTool.delete("agent = ?1 AND skillName = ?2", agent, skillName);
        // Bulk JPQL DELETE bypasses entity lifecycle → manually evict
        // the L2 region so the next ShellExecTool check sees fresh data.
        JPA.em().getEntityManagerFactory().getCache().evict(AgentSkillAllowedTool.class);
    }

    public static void deleteByAgent(Agent agent) {
        AgentSkillAllowedTool.delete("agent = ?1", agent);
        JPA.em().getEntityManagerFactory().getCache().evict(AgentSkillAllowedTool.class);
    }
}
