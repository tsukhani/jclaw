package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.List;

/**
 * Tracks which tools are enabled for each agent.
 * If no config exists for an agent, all tools are enabled by default.
 *
 * <p>A row addresses its tool one of two ways: a native tool by {@link #toolName}, an MCP
 * tool by {@link #mcpServer} + {@link #mcpAction} (JCLAW-983). Exactly one of the two is
 * populated, so each unique index below constrains its own row kind and exempts the other
 * through the NULL — NULLs compare distinct on both H2 and PostgreSQL.
 */
@Entity
@Table(name = "agent_tool_config", indexes = {
        @Index(name = "idx_agent_tool_agent", columnList = "agent_id"),
        @Index(name = "idx_agent_tool_unique", columnList = "agent_id,tool_name", unique = true),
        @Index(name = "idx_agent_tool_mcp_unique", columnList = "agent_id,mcp_server_id,mcp_action", unique = true)
})
// JCLAW-205: Hibernate L2 cache via Caffeine. Per-agent tool overrides
// are read on every chat turn to compute the disabled-tool set.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class AgentToolConfig extends AgentFeatureConfig {

    /** The tool's registry name for a native tool; null on an MCP row. */
    @Column(name = "tool_name")
    public String toolName;

    /**
     * The MCP server this row grants, or null for a native tool.
     *
     * <p>Keyed by id rather than by the server's name because an MCP tool's name is built
     * from that name: keying by name made the join a string prefix, so a rename stranded
     * every grant under a handle nothing would emit again and a delete left them behind
     * (JCLAW-982 measured 49,018 such rows). {@code ON DELETE CASCADE} per the JCLAW-540/542
     * policy, so deleting a server removes its grants with no application code involved.
     *
     * <p>Lazy: {@code computeDisabledTools} resolves the name through this association on
     * every cache miss, and {@link McpServer} is L2-cached, so the proxy initializes from
     * the cache rather than a per-row select.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mcp_server_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    public McpServer mcpServer;

    /**
     * The action within {@link #mcpServer}, empty for the server-level handle; null on a
     * native row. Empty rather than null because the server-level row is the one the
     * operator's per-server toggle writes, and a NULL there would exempt it from
     * {@code idx_agent_tool_mcp_unique}.
     */
    @Column(name = "mcp_action")
    public String mcpAction;

    /**
     * The registry tool name this row grants. Derived for an MCP row, which is why a
     * rename needs to write nothing.
     */
    public String handle() {
        return mcpServer == null ? toolName : McpServer.toolName(mcpServer.name, mcpAction);
    }

    public static List<AgentToolConfig> findByAgent(Agent agent) {
        return AgentToolConfig.find("agent = ?1", agent).fetch();
    }

    public static AgentToolConfig findByAgentAndTool(Agent agent, String toolName) {
        return AgentToolConfig.find("agent = ?1 AND toolName = ?2", agent, toolName).first();
    }
}
