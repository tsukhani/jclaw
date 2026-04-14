package models;

import jakarta.persistence.*;
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
    }

    public static void deleteByAgent(Agent agent) {
        AgentSkillAllowedTool.delete("agent = ?1", agent);
    }
}
