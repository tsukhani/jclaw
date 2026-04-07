package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

/**
 * Tracks which tools are enabled for each agent.
 * If no config exists for an agent, all tools are enabled by default.
 */
@Entity
@Table(name = "agent_tool_config", indexes = {
        @Index(name = "idx_agent_tool_agent", columnList = "agent_id"),
        @Index(name = "idx_agent_tool_unique", columnList = "agent_id,tool_name", unique = true)
})
public class AgentToolConfig extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    public Agent agent;

    @Column(name = "tool_name", nullable = false)
    public String toolName;

    @Column(nullable = false)
    public boolean enabled = true;

    public static java.util.List<AgentToolConfig> findByAgent(Agent agent) {
        return AgentToolConfig.find("agent = ?1", agent).fetch();
    }

    public static AgentToolConfig findByAgentAndTool(Agent agent, String toolName) {
        return AgentToolConfig.find("agent = ?1 AND toolName = ?2", agent, toolName).first();
    }
}
