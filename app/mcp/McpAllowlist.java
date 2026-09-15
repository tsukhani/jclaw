package mcp;

import models.Agent;
import models.AgentSkillAllowedTool;
import play.db.jpa.JPA;
import services.Tx;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * top-level agent gets one row per advertised tool. JCLAW-33's admin UI will
 * layer per-agent toggles on top by selectively deleting rows. The wire-format
 * row (agent, skill, tool) doesn't change between the two stories — only
 * the policy that decides which rows to write.
 *
 * <p><b>Spawned subagents are excluded from the broadcast</b> and get their
 * grants once, from {@link #inheritFromParent} at creation — a delegate's reach
 * is bounded above by the agent that spawned it. They also outnumber real agents
 * by two orders of magnitude on a busy instance (each spawn leaves a row
 * behind), and broadcasting to them made every reconnect rewrite
 * {@code subagents × tools} rows — minutes of work before a server could report
 * CONNECTED. The broadcast therefore also leaves their rows ALONE rather than
 * deleting them, so a reconnect mid-run cannot strip a live subagent's access.
 *
 * <p><b>Transactions.</b> Every method here is tx-agnostic: each call
 * issues plain JPA operations and expects to run inside a caller-supplied
 * transaction (so the check + the audit log can land in one atomic write).
 * Callers wrap with {@link services.Tx#run}.
 */
public final class McpAllowlist {

    public static final String SKILL_PREFIX = "mcp:";

    private static final String QUERY_SKILL_NAME = "skillName = ?1";

    /** Agents the broadcast covers: everything that is not a spawned subagent. */
    private static final String TOP_LEVEL_AGENTS = "parentAgent is null";

    /** Rows this server broadcast to top-level agents — the set the broadcast owns. */
    private static final String QUERY_SKILL_TOP_LEVEL = "skillName = ?1 and agent.parentAgent is null";

    /** Same set, phrased for a bulk DELETE: JPQL forbids the implicit join an
     *  {@code agent.parentAgent} path would need there, so it goes via a subquery. */
    private static final String DELETE_SKILL_TOP_LEVEL =
            "skillName = ?1 and agent in (select a from Agent a where a.parentAgent is null)";

    private McpAllowlist() {}

    /**
     * Replace this server's allowlist rows for every top-level agent with the
     * current tool list. Idempotent — clears prior rows for this server
     * scope first, then inserts fresh. Safe to call on every reconnect or
     * when the server's tool list changes via {@code tools/list_changed}.
     *
     * <p>Spawned subagents are outside this scope entirely, read and written —
     * see the class doc. Their rows are neither counted nor deleted here.
     *
     * <p>No-op short-circuit: an unchanged reconnect (or a {@code list_changed}
     * that didn't actually change anything) is the common case. When the
     * incoming tool-name set already matches the broadcast we last wrote — same
     * distinct tools, and exactly {@code agents × tools} rows still present —
     * we skip the delete+reinsert entirely and return the existing row count.
     * The {@code agents × tools} guard keeps this strictly behavior-preserving:
     * if a future per-agent toggle (JCLAW-33) selectively deleted rows, the
     * count won't match and we fall through to a full rewrite as before.
     */
    public static int registerForAllAgents(String serverName, List<McpToolDef> tools) {
        var skillName = SKILL_PREFIX + serverName;

        List<Agent> agents = Agent.<Agent>find(TOP_LEVEL_AGENTS).fetch();
        Set<String> incoming = new HashSet<>();
        for (var tool : tools) incoming.add(tool.name());

        List<AgentSkillAllowedTool> existing =
                AgentSkillAllowedTool.<AgentSkillAllowedTool>find(QUERY_SKILL_TOP_LEVEL, skillName).fetch();
        Set<String> current = new HashSet<>();
        for (var row : existing) current.add(row.toolName);
        if (current.equals(incoming) && existing.size() == agents.size() * incoming.size()) {
            return existing.size();
        }

        deleteAndEvict(DELETE_SKILL_TOP_LEVEL, skillName);
        if (tools.isEmpty()) return 0;
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
        return deleteAndEvict(QUERY_SKILL_NAME, skillName);
    }

    /**
     * Drop every MCP grant held by one agent. A subagent's grants are written
     * once at spawn and the broadcast never revisits them, so each spawn would
     * otherwise leak one row per tool per server permanently — the agent row
     * outlives the run to keep its transcript readable.
     *
     * <p>Scoped with a LIKE on the {@code mcp:} namespace so shell-allowlist
     * rows in the same table, which this class does not own, are left alone.
     *
     * @return rows removed
     */
    public static int revokeForAgent(Agent agent) {
        if (agent == null || agent.id == null) return 0;
        return deleteAndEvict("agent = ?1 and skillName like ?2", agent, SKILL_PREFIX + "%");
    }

    /**
     * Release a finished subagent's MCP grants, at the point its run settles.
     *
     * <p>No-op unless the agent is genuinely a spawned subagent. A spawn may
     * instead name an existing agent to run as ({@code agentId}), and that row
     * is an operator-created agent that deliberately keeps its own
     * {@code parent_agent_id} null — revoking there would strip a real agent's
     * MCP access the first time it was delegated to. The check lives here
     * rather than at each settle point so no future caller has to remember it.
     *
     * @return rows removed
     */
    public static int releaseSubagentGrants(Agent child) {
        if (child == null || !child.isSubagent()) return 0;
        return revokeForAgent(child);
    }

    /**
     * Drop MCP grants belonging to subagents that are no longer running, and
     * rows naming a server that no longer exists.
     *
     * <p>Both are backlog: grants leaked before {@link #revokeForAgent} existed,
     * and rows stranded when a server was renamed or deleted while disconnected
     * (the delete path only clears the name it knew). Left in place they are
     * re-read on every connect, which is what made a large server take minutes
     * to report CONNECTED.
     *
     * @param liveServerNames servers currently configured; rows naming anything
     *                        else are stranded
     * @param runningAgentIds subagents with a run still in flight, whose grants
     *                        must survive
     * @return rows removed
     */
    public static int sweepStaleGrants(Set<String> liveServerNames, Set<Long> runningAgentIds) {
        int removed = 0;
        for (var skillName : distinctMcpSkillNames()) {
            if (!liveServerNames.contains(skillName.substring(SKILL_PREFIX.length()))) {
                removed += deleteAndEvict(QUERY_SKILL_NAME, skillName);
            }
        }
        List<Agent> subagents = Agent.<Agent>find("parentAgent is not null").fetch();
        for (var agent : subagents) {
            if (runningAgentIds.contains(agent.id)) continue;
            removed += revokeForAgent(agent);
        }
        return removed;
    }

    /**
     * Bulk-delete allowlist rows and drop the L2 region.
     *
     * <p>{@link AgentSkillAllowedTool} is a cached entity and a bulk JPQL
     * DELETE bypasses the entity lifecycle, so without the evict a reader can
     * still be served a row this call removed — and the readers here decide
     * whether a tool call is permitted. Same discipline as the model's own
     * {@code deleteByAgentAndSkill}.
     */
    private static int deleteAndEvict(String query, Object... params) {
        var removed = AgentSkillAllowedTool.delete(query, params);
        // Resolved here, not inside the after-commit action: by afterCompletion the
        // EntityManager may already be closing, while the factory's L2 handle outlives it.
        var l2 = JPA.em().getEntityManagerFactory().getCache();
        l2.evict(AgentSkillAllowedTool.class);
        // Again once the delete is durable (JCLAW-1042): a concurrent reader repopulates L2
        // from the rows this transaction has not removed yet, and these rows are what decide
        // whether a tool call is permitted — so a revoked grant stays callable for as long as
        // the stale entry survives.
        Tx.afterCommit(() -> l2.evict(AgentSkillAllowedTool.class));
        return removed;
    }

    /** Distinct {@code mcp:*} scopes present in the table, live or stranded. */
    private static List<String> distinctMcpSkillNames() {
        return JPA.em().createQuery(
                        "select distinct a.skillName from AgentSkillAllowedTool a "
                                + "where a.skillName like :prefix",
                        String.class)
                .setParameter("prefix", SKILL_PREFIX + "%")
                .getResultList();
    }

    /**
     * Grant a spawned subagent exactly its parent's MCP tools — no more.
     *
     * <p>A subagent is a delegate, so its reach has to be bounded above by the
     * agent that spawned it; {@link #backfillForAgent} would instead hand it
     * every tool on every connected server, which on a parent that had been
     * denied a server is an escalation, not a convenience. Copying rows (rather
     * than reading through to the parent at call time) keeps
     * {@link #isAllowed} a single-table check and keeps the child's grants
     * frozen at spawn, so a later widening of the parent does not retroactively
     * widen a run already in flight.
     *
     * @return rows written
     */
    public static int inheritFromParent(Agent child) {
        if (child == null || child.id == null || child.parentAgent == null) return 0;
        List<AgentSkillAllowedTool> parentRows = AgentSkillAllowedTool.<AgentSkillAllowedTool>find(
                "agent = ?1 and skillName like ?2", child.parentAgent, SKILL_PREFIX + "%").fetch();
        for (var parentRow : parentRows) {
            var row = new AgentSkillAllowedTool();
            row.agent = child;
            row.skillName = parentRow.skillName;
            row.toolName = parentRow.toolName;
            row.save();
        }
        return parentRows.size();
    }

    /**
     * Backfill grants for a newly-created agent against every server
     * already connected. Without this an agent created post-connect
     * would silently see zero MCP tools — JCLAW-31's broadcast would
     * have happened before the agent existed.
     *
     * <p>Top-level agents only. A subagent's grants come from
     * {@link #inheritFromParent} instead.
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
