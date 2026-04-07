package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.util.List;

@Entity
@Table(name = "agent_skill", indexes = {
        @Index(name = "idx_agent_skill_agent", columnList = "agent_id"),
        @Index(name = "idx_agent_skill_unique", columnList = "agent_id,skill_id", unique = true)
})
public class AgentSkill extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    public Agent agent;

    @ManyToOne(optional = false)
    @JoinColumn(name = "skill_id", nullable = false)
    public Skill skill;

    public static List<AgentSkill> findByAgent(Agent agent) {
        return AgentSkill.find("agent = ?1", agent).fetch();
    }

    public static AgentSkill findByAgentAndSkill(Agent agent, Skill skill) {
        return AgentSkill.find("agent = ?1 AND skill = ?2", agent, skill).first();
    }

    public static List<Skill> findSkillsForAgent(Agent agent) {
        var assigned = AgentSkill.find("agent = ?1", agent).<AgentSkill>fetch();
        var skills = new java.util.ArrayList<>(Skill.findGlobal());
        for (var as : assigned) {
            if (!skills.contains(as.skill)) {
                skills.add(as.skill);
            }
        }
        return skills;
    }
}
