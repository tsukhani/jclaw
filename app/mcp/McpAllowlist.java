package mcp;

import models.Agent;
import models.AgentSkillAllowedTool;

import java.util.List;

/**
 * Per-agent MCP tool allowlist (JCLAW-32).
 *
 * <p>Reuses the {@link AgentSkillAllowedTool} table — the existing
 * Confused-Deputy-Proof authority for shell allowlists — by namespacing
 * each MCP server's grants under {@code skill_name = "mcp:<server>"}. The
 * table comment makes the strong claim that this table, NOT in-memory
 * state, is the call-time authority; this class extends that guarantee
 * to MCP tool invocations.
 *
 * <p>{@code tool_name} stored here is the <em>inner</em> MCP tool name
 * (e.g. {@code "create_issue"}), not the prefixed adapter name
 * ({@code "mcp_github_create_issue"}). The prefix is redundant given
 * {@code skill_name} already carries the server.
 *
 * <p><b>Granting model.</b> JCLAW-32 broadcasts: on connect, every existing
 * agent gets one row per advertised tool. JCLAW-33's admin UI will layer
 * per-agent toggles on top by selectively deleting rows. The wire-format
 * row (agent, skill, tool) doesn't change between the two stories — only
 * the policy that decides which rows to write.
 *
 * <p><b>Transactions.</b> Every method here is tx-agnostic: each call
 * issues plain JPA operations and expects to run inside a caller-supplied
 * transaction (so the check + the audit log can land in one atomic write).
 * Callers wrap with {@link services.Tx#run}.
 */
public final class McpAllowlist {

    public static final String SKILL_PREFIX = "mcp:";

    private McpAllowlist() {}

    /**
     * Replace this server's allowlist rows for ALL existing agents with the
     * current tool list. Idempotent — clears prior rows for this server
     * scope first, then inserts fresh. Safe to call on every reconnect or
     * when the server's tool list changes via {@code tools/list_changed}.
     */
    public static int registerForAllAgents(String serverName, List<McpToolDef> tools) {
        var skillName = SKILL_PREFIX + serverName;
        AgentSkillAllowedTool.delete("skillName = ?1", skillName);
        if (tools.isEmpty()) return 0;
        List<Agent> agents = Agent.findAll();
        int written = 0;
        for (var agent : agents) {
            for (var tool : tools) {
                var row = new AgentSkillAllowedTool();
                row.agent = agent;
                row.skillName = skillName;
                row.toolName = tool.name();
                row.save();
                written++;
            }
        }
        return written;
    }

    /** Remove every allowlist row this server contributed. Returns the row count. */
    public static int unregister(String serverName) {
        var skillName = SKILL_PREFIX + serverName;
        return AgentSkillAllowedTool.delete("skillName = ?1", skillName);
    }

    /**
     * Backfill grants for a newly-created agent against every server
     * already connected. Without this an agent created post-connect
     * would silently see zero MCP tools — JCLAW-31's broadcast would
     * have happened before the agent existed.
     */
    public static int backfillForAgent(Agent agent) {
        if (agent == null || agent.id == null) return 0;
        int written = 0;
        for (var serverName : McpConnectionManager.connectedServerNames()) {
            var tools = McpConnectionManager.tools(serverName);
            if (tools.isEmpty()) continue;
            var skillName = SKILL_PREFIX + serverName;
            for (var tool : tools) {
                var row = new AgentSkillAllowedTool();
                row.agent = agent;
                row.skillName = skillName;
                row.toolName = tool.name();
                row.save();
                written++;
            }
        }
        return written;
    }

    /**
     * Confused-Deputy-Proof gate: does {@code agent} hold a row granting
     * {@code toolName} on {@code serverName}? Returns {@code false} when no
     * row exists, including the case where the agent or server doesn't
     * exist at all.
     */
    public static boolean isAllowed(Agent agent, String serverName, String toolName) {
        if (agent == null || agent.id == null) return false;
        var skillName = SKILL_PREFIX + serverName;
        return AgentSkillAllowedTool.count(
                "agent = ?1 AND skillName = ?2 AND toolName = ?3",
                agent, skillName, toolName) > 0;
    }
}
