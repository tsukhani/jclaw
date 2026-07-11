package services;

import jakarta.persistence.EntityManager;
import llm.LlmTypes;
import llm.ProviderRegistry;
import mcp.McpAllowlist;
import memory.MemoryStoreFactory;
import models.Agent;
import models.AgentToolConfig;
import models.Config;
import play.Play;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.Caches;
import play.db.jpa.JPA;
import services.search.LuceneIndexer;
import tools.JClawApiTool;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class AgentService {

    private static final String LOG_CATEGORY = "agent";
    /** Prefix for the name-partitioned per-agent config keys ({@code agent.<name>.*}). */
    private static final String AGENT_CONFIG_PREFIX = "agent.";

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

    /**
     * Workspace-file cache (JCLAW-202). Caffeine handles concurrent access
     * and size-bound eviction under the hood; no manual LRU bookkeeping or
     * lock acquire/release needed. The previous hand-rolled {@code LinkedHashMap}
     * + {@code ReentrantLock} variant guarded against a {@code ConcurrentHashMap}
     * size-check race that would let workspace files overshoot the cap and
     * leak heap; Caffeine's atomic eviction makes both the lock and the
     * race moot.
     */
    private static final Cache<String, String> fileCache = Caches.named(
            "agent-files",
            CacheConfig.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(30))
                    .maximumSize(500)
                    .build());

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
            createWorkspace(name);
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
        Path workspaceSrc = (nameChanged && rootAgent) ? workspacePath(oldName) : null;

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
            if (rootAgent) moveWorkspaceDirectory(workspaceSrc, workspacePath(name));
        }
        return agent;
    }

    /**
     * JCLAW-533: re-key the name-partitioned {@code agent.<name>.*} config rows
     * when an agent is renamed, so per-agent settings (shell allowlist, queue
     * mode, …) follow the agent instead of stranding under the old name. Uses a
     * SELECT + per-row save rather than a bulk HQL UPDATE — the bulk form would
     * provision an HTE_config id-table whose DDL emits the reserved word
     * {@code key} unquoted (see the note in {@link #delete}).
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
     * JCLAW-533: move a root agent's workspace directory on rename so its skills
     * and workspace files (SOUL/IDENTITY/USER/BOOTSTRAP/AGENT.md) follow the
     * agent. Throws on failure so the caller's transaction rolls the rename back
     * rather than leaving the entity renamed with a stranded directory. Freeing
     * the old name also closes the reuse-leak: a new agent taking it later
     * materialises a fresh, empty workspace via {@link #createWorkspace}.
     */
    private static void moveWorkspaceDirectory(Path src, Path dest) {
        try {
            if (!Files.exists(src)) return;          // workspace never materialised
            if (Files.exists(dest)) {
                throw new IllegalStateException("workspace target already exists: " + dest);
            }
            Files.createDirectories(dest.getParent());
            Files.move(src, dest);
            EventLogger.info(LOG_CATEGORY, "Moved agent workspace %s -> %s"
                    .formatted(src.getFileName(), dest.getFileName()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to move agent workspace on rename: " + e.getMessage(), e);
        }
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
     * Delete an agent and its entire sub-agent subtree. Every FK from a child
     * row back to this agent (or any descendant) now carries
     * {@code ON DELETE CASCADE} (JCLAW-542), so deleting the root Agent row
     * removes all descendant agents and every FK-linked child — conversations,
     * messages, attachments, session compactions, tasks, task runs and their
     * messages, subagent-run audit rows, channel bindings, skill/tool configs,
     * tool-approval grants, and notifications — in one statement whose delete
     * order the database computes. This replaces the ~18 hand-ordered bulk
     * DELETEs (and the recursive per-node sweep) the method used to maintain.
     *
     * <p>Three resources live outside the FK graph and are still cleaned up by
     * an explicit walk over the subtree, since {@code ON DELETE CASCADE} governs
     * only rows in FK-linked tables:
     * <ul>
     *   <li>the on-disk workspace directory of each agent;</li>
     *   <li>{@code agent.{name}.*} config rows (keyed by a string LIKE, not an
     *       FK);</li>
     *   <li>Lucene index docs for each agent's memories — an external store the
     *       DB cascade of the memory rows themselves cannot reach, so deletion
     *       is routed through {@link MemoryStoreFactory}.</li>
     * </ul>
     *
     * <p>Workspace directories are removed last, after DB state is clean, so a
     * failed delete leaves the filesystem in a recoverable state.
     *
     * @param agent the agent to delete (must have a persisted id)
     */
    public static void delete(Agent agent) {
        var rootId = agent.id;

        // Collect the whole sub-agent subtree (root + all transitive
        // descendants) up front, before anything is deleted. We need the set
        // only for the out-of-band cleanup below; the database computes the
        // actual row-delete order via the cascade when the root is removed.
        var subtree = collectSubtree(agent);
        var names = subtree.stream().map(a -> a.name).toList();

        var em = JPA.em();
        // Out-of-band cleanup, per subtree node — the cascade only governs rows
        // in FK-linked tables, never the filesystem, string-keyed config, or the
        // external Lucene index:
        //   - Memory rows cascade at the DB, but their Lucene docs are external,
        //     so route deletion through the store to evict the index entries too.
        //   - agent.<name>.* config rows are keyed by a LIKE, not an FK. Native
        //     SQL, not a bulk HQL Config.delete: the HQL id-table DDL emits the
        //     entity attribute `key` unquoted, which H2 rejects as reserved.
        for (var node : subtree) {
            MemoryStoreFactory.get().deleteAll(String.valueOf(node.id));
            em.createNativeQuery("DELETE FROM config WHERE config_key LIKE ?1")
                    .setParameter(1, AGENT_CONFIG_PREFIX + node.name + ".%").executeUpdate();
        }
        ConfigService.clearCache();
        // JCLAW-673: evict the subtree's SUBAGENT_RUN + TASK full-text docs while
        // the rows still exist. The DB cascade removes the rows but never fires
        // SubagentRun/Task @PostRemove, so their Lucene docs would orphan — the
        // same problem MEMORY avoids above by routing through MemoryStoreFactory.
        evictSubtreeLuceneDocs(em, subtree);
        em.flush();
        em.clear();
        // Re-fetch the root as a managed entity; its delete cascades the whole
        // subtree of Agent rows plus every FK-linked child at the DB level.
        Agent root = Agent.findById(rootId);
        root.delete();
        em.flush();

        // Workspace dirs last, after DB state is clean. One per subtree node.
        for (var name : names) {
            deleteWorkspaceDirectory(name);
            EventLogger.info(LOG_CATEGORY, name, null, "Agent deleted");
        }
    }

    /**
     * JCLAW-673: remove the SUBAGENT_RUN, TASK, and TASK_RUN_MESSAGE Lucene docs
     * for an entire agent subtree before its rows cascade-delete. A cascade /
     * bulk delete never fires the entities' {@code @PostRemove} hooks, so their
     * full-text docs would linger after the rows are gone. Collects the doc ids
     * by querying the still-present rows (SubagentRun on either agent end of the
     * subtree; Task owned by any subtree agent; TaskRunMessage under those
     * tasks' runs, which cascade-delete transitively when the agent is removed),
     * removes each doc, and commits each scope once. No-op scopes (empty result,
     * or a closed index) cost nothing.
     */
    private static void evictSubtreeLuceneDocs(EntityManager em, List<Agent> subtree) {
        var subtreeIds = subtree.stream().map(a -> a.id).toList();
        List<Long> subagentRunIds = em.createQuery(
                "SELECT sr.id FROM SubagentRun sr "
                        + "WHERE sr.parentAgent.id IN :ids OR sr.childAgent.id IN :ids", Long.class)
                .setParameter("ids", subtreeIds).getResultList();
        List<Long> taskIds = em.createQuery(
                "SELECT t.id FROM Task t WHERE t.agent.id IN :ids", Long.class)
                .setParameter("ids", subtreeIds).getResultList();
        List<Long> taskRunMessageIds = em.createQuery(
                "SELECT m.id FROM TaskRunMessage m WHERE m.taskRun.task.agent.id IN :ids", Long.class)
                .setParameter("ids", subtreeIds).getResultList();
        for (Long runId : subagentRunIds) {
            LuceneIndexer.remove(LuceneIndexer.Scope.SUBAGENT_RUN, runId);
        }
        if (!subagentRunIds.isEmpty()) {
            LuceneIndexer.commit(LuceneIndexer.Scope.SUBAGENT_RUN);
        }
        for (Long taskId : taskIds) {
            LuceneIndexer.remove(LuceneIndexer.Scope.TASK, taskId);
        }
        if (!taskIds.isEmpty()) {
            LuceneIndexer.commit(LuceneIndexer.Scope.TASK);
        }
        // TASK_RUN_MESSAGE docs orphan too: an agent delete cascades
        // Task -> TaskRun -> TaskRunMessage, and none of those fire @PostRemove.
        for (Long messageId : taskRunMessageIds) {
            LuceneIndexer.remove(messageId);
        }
        if (!taskRunMessageIds.isEmpty()) {
            LuceneIndexer.commit(LuceneIndexer.Scope.TASK_RUN_MESSAGE);
        }
    }

    /**
     * Direct children of {@code parentId} — Agent rows whose
     * {@code parent_agent_id} equals it. Fetched eagerly into a typed list so
     * {@link #delete} can mutate the underlying rows during iteration without
     * tripping a ConcurrentModificationException from a live result-set cursor.
     * Sub-agents typically have shallow fan-out (1–3 children per spawn round)
     * so the per-call cost is bounded.
     */
    private static List<Agent> findDirectChildren(Long parentId) {
        return Agent.<Agent>find("parentAgent.id = ?1", parentId).fetch();
    }

    /**
     * The agent plus all transitive sub-agent descendants (rows reachable by
     * walking {@code parent_agent_id} downward), gathered depth-first. Used by
     * {@link #delete} to run the out-of-band cleanup (workspace dirs, config
     * rows, Lucene docs) on every node before one cascading root delete clears
     * the DB rows themselves.
     */
    private static List<Agent> collectSubtree(Agent root) {
        var acc = new ArrayList<Agent>();
        collectSubtreeInto(root, acc);
        return acc;
    }

    private static void collectSubtreeInto(Agent node, List<Agent> acc) {
        for (var child : findDirectChildren(node.id)) {
            collectSubtreeInto(child, acc);
        }
        acc.add(node);
    }

    private static void deleteWorkspaceDirectory(String agentName) {
        var dir = workspacePath(agentName);
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) { /* best-effort */ }
            });
        } catch (IOException e) {
            EventLogger.warn(LOG_CATEGORY, "Failed to remove workspace for deleted agent %s: %s"
                    .formatted(agentName, e.getMessage()));
        }
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

    public static Path workspaceRoot() {
        var root = Play.configuration.getProperty("jclaw.workspace.path", "workspace");
        return Path.of(root);
    }

    /**
     * Resolve an agent's workspace path with defense-in-depth against path
     * traversal (JCLAW-115). The controller layer already validates names
     * against a strict slug regex; this method adds a second layer that
     * catches:
     *   - direct service calls bypassing the controller,
     *   - legacy agents whose names predate the regex,
     *   - any future relaxation of the controller-level check.
     *
     * Throws {@link SecurityException} when the resolved path escapes the
     * workspace root — callers should not attempt to recover, the only
     * correct response is to refuse the operation.
     *
     * @param agentName agent whose workspace folder is requested
     * @return the agent's workspace directory path
     */
    public static Path workspacePath(String agentName) {
        var rootName = resolveWorkspaceOwnerName(agentName);
        var contained = resolveContained(workspaceRoot(), rootName);
        if (contained == null) {
            throw new SecurityException(
                    "Agent name '" + rootName + "' resolves outside the workspace root");
        }
        return contained;
    }

    /**
     * Maximum {@link models.Agent#parentAgent} hops walked by
     * {@link #resolveWorkspaceOwnerName} before bailing. A cycle in the
     * parent chain shouldn't be possible — the FK is set exactly once at
     * spawn time and never re-pointed — but defence in depth keeps a
     * corrupted DB from spinning the thread forever.
     */
    private static final int MAX_PARENT_WALK_DEPTH = 32;

    /**
     * Resolve an agent name to the on-disk workspace owner. Subagents
     * (spawned via {@link tools.SubagentSpawnTool}) inherit their parent's
     * workspace because they're delegates of the parent — anything they
     * read or write should land in the parent's tree, and they have no
     * on-disk identity of their own ({@link #create} skips workspace
     * setup when called with {@code createWorkspace=false}).
     *
     * <p>For root agents (no {@code parentAgent}) the lookup is a no-op:
     * the agent's own name is the workspace owner. For spawned subagents
     * the chain is walked to the root and the root's name is returned.
     *
     * <p>For unknown names (no matching {@link models.Agent} row) the
     * input is returned verbatim — this preserves the pre-2026-05
     * behaviour for callers that resolve workspace paths before an agent
     * row has been committed (admin tooling, tests that use
     * {@link play.test.Fixtures#deleteDatabase}).
     *
     * <p>Performance note: one indexed {@code SELECT … WHERE name = ?}
     * per call. Hot paths (tool execution, prompt assembly) hit this on
     * every invocation; the {@code agent.name} column is unique-indexed
     * so the query is O(log n) on a tiny table. {@link Agent#parentAgent}
     * is eagerly fetched by default in this project so the walk doesn't
     * trigger N+1 queries.
     */
    private static String resolveWorkspaceOwnerName(String agentName) {
        return Tx.run(() -> {
            var agent = (Agent) Agent.find("name = ?1", agentName).first();
            if (agent == null) return agentName;
            if (agent.parentAgent == null) return agentName;
            var cursor = agent.parentAgent;
            for (int hops = 0; cursor.parentAgent != null && hops < MAX_PARENT_WALK_DEPTH; hops++) {
                cursor = cursor.parentAgent;
            }
            return cursor.name;
        });
    }

    /**
     * Resolve {@code relativePath} inside {@code root}, rejecting any target
     * that would escape the root. Thin delegate to
     * {@link WorkspacePathGuard#resolveContained} — the general
     * filesystem-boundary guard (JCLAW-703) — retained here as the
     * agent-domain entry point. See there for the two-layer lexical +
     * canonical (symlink-catching, missing-suffix walk-up) validation.
     *
     * @param root         the workspace root (or any other "must stay inside"
     *                     boundary)
     * @param relativePath path relative to {@code root}, possibly containing
     *                     {@code ..} segments
     * @return the canonical absolute path inside {@code root}, or
     *         {@code null} on escape / missing root / I/O error
     */
    public static Path resolveContained(Path root, String relativePath) {
        return WorkspacePathGuard.resolveContained(root, relativePath);
    }

    /**
     * Resolve, double-validate (TOCTOU-window shrink), and hardlink-reject a
     * path inside {@code root}. Thin delegate to
     * {@link WorkspacePathGuard#acquireContained} — retained here as the
     * agent-domain entry point. See there for the three-layer defense
     * (lexical + canonical, double-resolve, {@code nlink > 1} hardlink
     * rejection).
     *
     * @param root         the workspace root the target must stay inside
     * @param relativePath path relative to {@code root}
     * @return the canonical absolute path inside {@code root}, double-resolved
     * @throws SecurityException on escape, mid-resolution divergence, or
     *                           hardlink violation
     */
    public static Path acquireContained(Path root, String relativePath) {
        return WorkspacePathGuard.acquireContained(root, relativePath);
    }

    /**
     * Resolve a relative path inside an agent's workspace and reject any
     * target that escapes the workspace root. Prefer
     * {@link #acquireWorkspacePath} when the result is about to be used.
     *
     * @param agentName    agent whose workspace to resolve within
     * @param relativePath path relative to that workspace
     * @return the canonical absolute path, or {@code null} on escape
     */
    public static Path resolveWorkspacePath(String agentName, String relativePath) {
        return resolveContained(workspacePath(agentName), relativePath);
    }

    /**
     * Resolve and double-validate a path inside an agent's workspace. Use
     * this immediately before opening, reading, writing, or execing against
     * the returned path.
     *
     * @param agentName    agent whose workspace to resolve within
     * @param relativePath path relative to that workspace
     * @return the canonical absolute path, double-resolved
     * @throws SecurityException on any escape, mid-resolution divergence,
     *                           or hardlink violation
     */
    public static Path acquireWorkspacePath(String agentName, String relativePath) {
        return acquireContained(workspacePath(agentName), relativePath);
    }

    public static void createWorkspace(String agentName) {
        writeWorkspaceFiles(agentName, false);
    }

    public static void resetWorkspace(String agentName) {
        writeWorkspaceFiles(agentName, true);
    }

    private static void writeWorkspaceFiles(String agentName, boolean overwrite) {
        var dir = workspacePath(agentName);
        try {
            Files.createDirectories(dir);
            Files.createDirectories(dir.resolve("skills"));

            // Workspace markdown files are injected into the system prompt in the order:
            // SOUL → IDENTITY → USER → BOOTSTRAP → AGENT. Each file is optional: a missing
            // or blank file is silently dropped from the prompt, so operators only need
            // to edit the ones they want to populate.

            writeFile(dir.resolve("SOUL.md"), """
                    # Soul

                    <!-- Define the psyche and character of the entity described in IDENTITY.md.
                         This is the philosophical lens through which AGENT.md instructions are
                         executed. Leave blank to skip. -->
                    """, overwrite);

            writeFile(dir.resolve("IDENTITY.md"), """
                    # Identity

                    Name: %s
                    """.formatted(agentName), overwrite);

            writeFile(dir.resolve("USER.md"), """
                    # User Information

                    <!-- Add information about the user here. The agent will use this context. -->
                    """, overwrite);

            writeFile(dir.resolve("BOOTSTRAP.md"), """
                    # Bootstrap

                    <!-- Priming / initialization context the agent should see before task
                         instructions in AGENT.md. Examples: preconditions, environment
                         assumptions, warm-up context. Leave blank to skip. -->
                    """, overwrite);

            writeFile(dir.resolve("AGENT.md"), """
                    # Agent Instructions

                    You are a helpful AI assistant. Follow these guidelines:

                    - Be concise and accurate
                    - Ask for clarification when the request is ambiguous
                    - Use tools when they would help accomplish the task
                    """, overwrite);

        } catch (IOException e) {
            EventLogger.error(LOG_CATEGORY, "Failed to create workspace for agent %s: %s"
                    .formatted(agentName, e.getMessage()));
        }
    }

    private static void writeFile(Path path, String content, boolean overwrite) throws IOException {
        if (overwrite || !Files.exists(path)) {
            Files.writeString(path, content);
        }
    }

    public static String readWorkspaceFile(String agentName, String filename) {
        var cacheKey = agentName + "/" + filename;
        var cached = fileCache.getIfPresent(cacheKey);
        if (cached != null) return cached;
        // Cache miss: read from disk. Use getIfPresent + put rather than
        // get(key, loader) so the loader's null-on-error path doesn't get
        // memoized (Cache.get(loader) treats null as "skip caching" but a
        // raised IOException would propagate; we want to swallow + log
        // I/O failures and just return null).
        try {
            var path = acquireWorkspacePath(agentName, filename);
            if (Files.exists(path)) {
                var content = Files.readString(path);
                fileCache.put(cacheKey, content);
                return content;
            }
        } catch (SecurityException e) {
            EventLogger.warn(LOG_CATEGORY, "Path traversal blocked for %s/%s: %s"
                    .formatted(agentName, filename, e.getMessage()));
        } catch (IOException e) {
            EventLogger.warn(LOG_CATEGORY, "Failed to read workspace file %s/%s: %s"
                    .formatted(agentName, filename, e.getMessage()));
        }
        return null;
    }

    public static void writeWorkspaceFile(String agentName, String filename, String content) {
        try {
            var path = acquireWorkspacePath(agentName, filename);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            fileCache.invalidate(agentName + "/" + filename);
        } catch (SecurityException e) {
            EventLogger.warn(LOG_CATEGORY, "Path traversal blocked for %s/%s: %s"
                    .formatted(agentName, filename, e.getMessage()));
        } catch (IOException e) {
            EventLogger.error(LOG_CATEGORY, "Failed to write workspace file %s/%s: %s"
                    .formatted(agentName, filename, e.getMessage()));
        }
    }

}
