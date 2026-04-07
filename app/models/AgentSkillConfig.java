package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.util.List;

/**
 * Tracks which skills are enabled/disabled for each agent.
 * If no config exists for a skill, it is enabled by default.
 */
@Entity
@Table(name = "agent_skill_config", indexes = {
        @Index(name = "idx_agent_skill_config_agent", columnList = "agent_id"),
        @Index(name = "idx_agent_skill_config_unique", columnList = "agent_id,skill_name", unique = true)
})
public class AgentSkillConfig extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    public Agent agent;

    @Column(name = "skill_name", nullable = false)
    public String skillName;

    @Column(nullable = false)
    public boolean enabled = true;

    public static List<AgentSkillConfig> findByAgent(Agent agent) {
        return AgentSkillConfig.find("agent = ?1", agent).fetch();
    }

    public static AgentSkillConfig findByAgentAndSkill(Agent agent, String skillName) {
        return AgentSkillConfig.find("agent = ?1 AND skillName = ?2", agent, skillName).first();
    }
}
