package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import mcp.McpAllowlist;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
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
 * <p>MCP grants share the table: {@link McpAllowlist} writes one row per MCP tool under skill
 * {@code mcp:<server>}, and those tool names are never shell commands.
 *
 * <p>Effective allowlist for an agent is:
 * <pre>global shell.allowlist ∪ (rows WHERE agent = :agent AND skillName NOT LIKE 'mcp:%'
 *                              AND skillName is not disabled in AgentSkillConfig)</pre>
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
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

    @Column(name = "skill_name", nullable = false)
    public String skillName;

    @Column(name = "tool_name", nullable = false)
    public String toolName;

    /** The agent's shell-command grants: every row except MCP grants. */
    public static List<AgentSkillAllowedTool> findShellGrantsByAgent(Agent agent) {
        return AgentSkillAllowedTool.find("agent = ?1 AND skillName NOT LIKE ?2", agent, McpAllowlist.SKILL_PREFIX + "%").fetch();
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
}
