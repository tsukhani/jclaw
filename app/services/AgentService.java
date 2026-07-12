package services;

import llm.LlmTypes;
import llm.ProviderRegistry;
import mcp.McpAllowlist;
import models.Agent;
import models.AgentToolConfig;
import models.Config;
import play.db.jpa.JPA;
import tools.JClawApiTool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class AgentService {

    private static final String LOG_CATEGORY = "agent";
    /** Prefix for the name-partitioned per-agent config keys ({@code agent.<name>.*}). */
    static final String AGENT_CONFIG_PREFIX = "agent.";

    private AgentService() {}

    /**
     * Does the agent's registered model (default, not conversation override)
     * declare {@code supportsVision}? Callers use this before accepting image
     * attachments on inbound paths (web controller, Telegram webhook) so the
     * user gets a clear reject rather than a silent drop. Returns {@code false}
     * when the provider or model can't be resolved — better to reject an
     * attachment than accept it against an unknown model.
     *
     * @param agent the agent whose default model is being inspected
     * @return true when the agent's default model declares image-input support
     */
    public static boolean supportsVision(Agent agent) {
        if (agent == null) return false;
        return findModel(agent.modelProvider, agent.modelId)
                .map(LlmTypes.ModelInfo::supportsVision)
                .orElse(false);
    }

    /**
     * JCLAW-217: does the agent's default model accept native video input? The
     * video-understanding dispatcher uses this to pick Tier-1 (native video) over
     * the frame-based Tier-2/Tier-3 fallbacks. False when the model can't be
     * resolved — better to fall back to frame sampling than assume native video.
     */
    public static boolean supportsVideo(Agent agent) {
        if (agent == null) return false;
        return findModel(agent.modelProvider, agent.modelId)
                .map(LlmTypes.ModelInfo::supportsVideo)
                .orElse(false);
    }

    /**
     * Resolve a {@code provider:modelId} pair to its registered
     * {@link llm.LlmTypes.ModelInfo}. Empty when either id is null, the
     * provider isn't registered, or the model isn't in that provider's list.
     * Centralises the {@code ProviderRegistry.get → models().stream().filter}
     * lookup shared by {@link #supportsVision}, {@link #normalizeThinkingMode},
     * and {@link #isProviderConfigured}.
     */
    private static Optional<LlmTypes.ModelInfo> findModel(String providerName, String modelId) {
        if (providerName == null || modelId == null) return Optional.empty();
        var provider = ProviderRegistry.get(providerName);
        if (provider == null) return Optional.empty();
        return provider.config().models().stream()
                .filter(m -> m.id().equals(modelId))
                .findFirst();
    }

    public static Agent create(String name, String modelProvider, String modelId) {
        return create(name, modelProvider, modelId, null, null, true);
    }

    public static Agent create(String name, String modelProvider, String modelId, String thinkingMode) {
        return create(name, modelProvider, modelId, thinkingMode, null, true);
    }

    public static Agent create(String name, String modelProvider, String modelId,
                                String thinkingMode, String description) {
        return create(name, modelProvider, modelId, thinkingMode, description, true);
    }

    /**
     * Six-argument variant with explicit control over workspace folder
     * materialisation. {@link tools.SubagentSpawnTool#bootstrapChild} passes
     * {@code createWorkspace=false} because subagents are delegates of their
     * parent agent — they inherit the parent's workspace via
     * {@link #workspacePath(String)}'s parent-chain walk and never need
     * their own on-disk SOUL / IDENTITY / USER / BOOTSTRAP / AGENT skeleton.
     * Operator-created agents (any caller from the admin UI or
     * {@link controllers.ApiAgentsController}) still get the full workspace
     * via the three other {@code create} overloads, which default
     * {@code createWorkspace=true} and preserve the pre-2026-05 behaviour.
     *
     * @param name             agent name (unique within the deployment)
     * @param modelProvider    provider id the agent defaults to
     * @param modelId          model id the agent defaults to
     * @param thinkingMode     reasoning effort default; null clears the field
     * @param description      operator-supplied short description
     * @param createWorkspace  when true, materialise the workspace folder
     *                         (SOUL / IDENTITY / USER / BOOTSTRAP / AGENT
     *                         scaffolding); subagents pass false
     * @return the persisted Agent
     */
    public static Agent create(String name, String modelProvider, String modelId,
                                String thinkingMode, String description,
                                boolean createWorkspace) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = modelProvider;
        agent.modelId = modelId;
        agent.description = normalizeDescription(description);
        // The main agent is a structural singleton and MUST always be enabled — its
        // presence is load-bearing for tier-3 routing fallback, LLM sanitization, and
        // the web chat default selection. Provider misconfiguration will surface as
        // a runtime error at call time, not as a silent disabled state.
        agent.enabled = agent.isMain() || isProviderConfigured(modelProvider, modelId);
        agent.thinkingMode = normalizeThinkingMode(thinkingMode, modelProvider, modelId);
        // JCLAW-465: content compression defaults on for the main agent, off for
        // custom agents (same main-vs-custom split as the jclaw_api tool below).
        agent.compressionEnabled = agent.isMain();
        agent.save();

        if (createWorkspace) {
            WorkspaceFiles.createWorkspace(name);
        }

        // Disable browser tool for non-main agents (security)
        if (!agent.isMain()) {
            var browserConfig = new AgentToolConfig();
            browserConfig.agent = agent;
            browserConfig.toolName = "browser";
            browserConfig.enabled = false;
            browserConfig.save();

            // JCLAW-282: jclaw_api can mutate JClaw's own config — only main
            // is trusted with that authority today. Skill-creator can extend
            // this later by promoting jclaw-api into another agent's workspace.
            var jclawApiConfig = new AgentToolConfig();
            jclawApiConfig.agent = agent;
            jclawApiConfig.toolName = JClawApiTool.TOOL_NAME;
            jclawApiConfig.enabled = false;
            jclawApiConfig.save();
        }

        // JCLAW-32: backfill MCP allowlist grants for currently-connected
        // servers. JCLAW-31's broadcast happens on connect; without this,
        // an agent created post-connect would silently see zero MCP tools.
        try {
            McpAllowlist.backfillForAgent(agent);
        } catch (RuntimeException e) {
            EventLogger.warn("MCP_TOOL_REGISTER",
                    "MCP allowlist backfill failed for new agent '%s': %s"
                            .formatted(name, e.getMessage()));
        }

        EventLogger.info(LOG_CATEGORY, name, null, "Agent '%s' created (provider: %s, model: %s)"
                .formatted(name, modelProvider, modelId));
        return agent;
    }

    public static Agent update(Agent agent, String name, String modelProvider, String modelId,
                                boolean enabled) {
        return update(agent, name, modelProvider, modelId, enabled, agent.thinkingMode,
                agent.description);
    }

    public static Agent update(Agent agent, String name, String modelProvider, String modelId,
                                boolean enabled, String thinkingMode) {
        return update(agent, name, modelProvider, modelId, enabled, thinkingMode,
                agent.description);
    }

    public static Agent update(Agent agent, String name, String modelProvider, String modelId,
                                boolean enabled, String thinkingMode, String description) {
        // JCLAW-533: an agent's workspace directory and its agent.<name>.* config
        // keys are partitioned by the mutable name, so a rename must migrate them
        // (mirroring what delete() cleans up) or they orphan — and a reused name
        // would inherit the previous agent's files. Capture the old name and, for a
        // root agent, its workspace path BEFORE the rename. Subagents share their
        // root's workspace (resolveWorkspaceOwnerName walks to the root), so only
        // root agents own a directory to move.
        var oldName = agent.name;
        boolean nameChanged = !oldName.equals(name);
        boolean rootAgent = agent.parentAgent == null;
        Path workspaceSrc = (nameChanged && rootAgent) ? WorkspaceFiles.workspacePath(oldName) : null;

        agent.name = name;
        agent.modelProvider = modelProvider;
        agent.modelId = modelId;
        agent.description = normalizeDescription(description);
        // The main agent cannot be disabled — see the invariant note in create().
        // The caller's `enabled` argument is ignored for the main agent; a UI that
        // tries to toggle it off is either a bug or a pre-guard bypass, and the API
        // layer additionally rejects such requests with 409 in ApiAgentsController.
        agent.enabled = agent.isMain() || (enabled && isProviderConfigured(modelProvider, modelId));
        agent.thinkingMode = normalizeThinkingMode(thinkingMode, modelProvider, modelId);
        agent.save();

        if (nameChanged) {
            renameAgentConfigKeys(oldName, name);
            // Moved last: a failure throws, rolling back the rename + config re-key
            // (same request transaction) rather than leaving a stranded directory.
            if (rootAgent) {
                WorkspaceFiles.moveWorkspaceDirectory(workspaceSrc, WorkspaceFiles.workspacePath(name));
            }
        }
        return agent;
    }

    /**
     * JCLAW-533: re-key the name-partitioned {@code agent.<name>.*} config rows
     * when an agent is renamed, so per-agent settings (shell allowlist, queue
     * mode, …) follow the agent instead of stranding under the old name. Uses a
     * SELECT + per-row save rather than a bulk HQL UPDATE — the bulk form would
     * provision an HTE_config id-table whose DDL emits the reserved word
     * {@code key} unquoted (see the note in {@link AgentDeletionCascade#delete}).
     */
    private static void renameAgentConfigKeys(String oldName, String newName) {
        var oldPrefix = AGENT_CONFIG_PREFIX + oldName + ".";
        var newPrefix = AGENT_CONFIG_PREFIX + newName + ".";
        // findAll + Java filter rather than a "key LIKE ?" query: `key` is a JPQL
        // reserved word and the config table is tiny. Each per-row save is an
        // UPDATE on the config_key column by id — no id-table, no reserved word.
        boolean any = false;
        for (Config c : Config.<Config>findAll()) {
            if (c.key.startsWith(oldPrefix)) {
                c.key = newPrefix + c.key.substring(oldPrefix.length());
                c.save();
                any = true;
            }
        }
        if (any) ConfigService.clearCache();
    }

    /**
     * Normalise a user-supplied description: null or blank becomes null, anything
     * longer than 255 chars is truncated. The server mirrors the client-side
     * {@code maxlength="255"} so a direct API caller can't sneak past.
     */
    private static String normalizeDescription(String description) {
        if (description == null) return null;
        var trimmed = description.strip();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    /**
     * Validate a requested thinking mode against the model's advertised levels.
     * Null/blank clears the setting. Unknown levels for a non-thinking model
     * collapse to null (silent drop — the model can't reason anyway). Unknown
     * levels for a thinking model also collapse to null rather than 500-ing,
     * which protects against stale frontend state after a model swap.
     *
     * @param requested      operator-supplied thinking mode
     * @param modelProvider  provider id to validate against
     * @param modelId        model id to validate against
     * @return the validated thinking mode, or {@code null} when not
     *         applicable / unknown
     */
    private static String normalizeThinkingMode(String requested, String modelProvider, String modelId) {
        if (requested == null || requested.isBlank()) return null;
        var model = findModel(modelProvider, modelId).orElse(null);
        if (model == null) return null;
        var levels = model.effectiveThinkingLevels();
        return levels.contains(requested) ? requested : null;
    }

    /**
     * Check whether the given provider+model combination is currently
     * configured and available.
     *
     * @param providerName provider id to check
     * @param modelId      model id to check within that provider's list
     * @return true when the provider is registered and lists this model id
     */
    public static boolean isProviderConfigured(String providerName, String modelId) {
        return findModel(providerName, modelId).isPresent();
    }

    /**
     * Snapshot of every currently-configured {@code providerName:modelId} pair.
     * Bulk-check helper for endpoints that need to mark many agents at once
     * (e.g. {@code GET /api/agents}). Each call to
     * {@link #isProviderConfigured} on a single agent is O(M) in the
     * provider's model list; with N agents that is O(N*M) per request, and
     * the registry is a static cache so the cost is pure CPU. Building this
     * set once and doing O(1) hash lookups per agent collapses the overall
     * cost back to O(N+M).
     */
    public static Set<String> configuredModelKeys() {
        var keys = new HashSet<String>();
        for (var p : ProviderRegistry.listAll()) {
            for (var m : p.config().models()) {
                keys.add(p.config().name() + ":" + m.id());
            }
        }
        return keys;
    }

    /**
     * Syncs the enabled state of all agents based on current provider configuration.
     * Agents whose provider+model are configured get enabled; others get disabled.
     * The main agent is exempt — it is always enabled regardless of provider state.
     */
    public static void syncEnabledStates() {
        ProviderRegistry.refresh();
        List<Agent> agents = listAll();

        var toEnable = new ArrayList<Long>();
        var toDisable = new ArrayList<Long>();

        for (var agent : agents) {
            if (agent.isMain()) {
                if (!agent.enabled) toEnable.add(agent.id);
                continue;
            }
            var shouldBeEnabled = isProviderConfigured(agent.modelProvider, agent.modelId);
            if (agent.enabled != shouldBeEnabled) {
                (shouldBeEnabled ? toEnable : toDisable).add(agent.id);
            }
        }

        if (toEnable.isEmpty() && toDisable.isEmpty()) return;
        var em = JPA.em();
        if (!toEnable.isEmpty()) {
            em.createQuery("UPDATE Agent SET enabled = true WHERE id IN :ids")
                    .setParameter("ids", toEnable).executeUpdate();
        }
        if (!toDisable.isEmpty()) {
            em.createQuery("UPDATE Agent SET enabled = false WHERE id IN :ids")
                    .setParameter("ids", toDisable).executeUpdate();
        }
        // JPQL UPDATE bypasses Hibernate's entity cache — clear stale entries
        // so subsequent findByName/findById calls see the updated values.
        em.clear();
    }

    /**
     * Delete an agent and its entire sub-agent subtree. Thin delegate to
     * {@link AgentDeletionCascade#delete} (JCLAW-728) — retained here as the
     * stable entry point for the many external callers. The cascade collaborator
     * owns the cross-subsystem teardown (memory, string-keyed config, Lucene
     * docs, workspace dirs) plus the single cascading DB delete.
     *
     * @param agent the agent to delete (must have a persisted id)
     */
    public static void delete(Agent agent) {
        AgentDeletionCascade.delete(agent);
    }

    public static List<Agent> listAll() {
        return Agent.findAll();
    }

    public static List<Agent> listEnabled() {
        return Agent.findEnabled();
    }

    public static Agent findById(Long id) {
        return Agent.findById(id);
    }

    public static Agent findByName(String name) {
        return Agent.findByName(name);
    }

    // --- Workspace management ---
    // The path-security + file-I/O implementation lives in WorkspaceFiles (JCLAW-728);
    // these are the stable agent-domain entry points the rest of the app calls.

    public static Path workspaceRoot() {
        return WorkspaceFiles.workspaceRoot();
    }

    public static Path workspacePath(String agentName) {
        return WorkspaceFiles.workspacePath(agentName);
    }

    public static Path resolveContained(Path root, String relativePath) {
        return WorkspaceFiles.resolveContained(root, relativePath);
    }

    public static Path acquireContained(Path root, String relativePath) {
        return WorkspaceFiles.acquireContained(root, relativePath);
    }

    public static Path resolveWorkspacePath(String agentName, String relativePath) {
        return WorkspaceFiles.resolveWorkspacePath(agentName, relativePath);
    }

    public static Path acquireWorkspacePath(String agentName, String relativePath) {
        return WorkspaceFiles.acquireWorkspacePath(agentName, relativePath);
    }

    public static void createWorkspace(String agentName) {
        WorkspaceFiles.createWorkspace(agentName);
    }

    public static void resetWorkspace(String agentName) {
        WorkspaceFiles.resetWorkspace(agentName);
    }

    public static String readWorkspaceFile(String agentName, String filename) {
        return WorkspaceFiles.readWorkspaceFile(agentName, filename);
    }

    public static void writeWorkspaceFile(String agentName, String filename, String content) {
        WorkspaceFiles.writeWorkspaceFile(agentName, filename, content);
    }

}
