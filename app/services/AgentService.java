package services;

import llm.ProviderRegistry;
import memory.MemoryStoreFactory;
import models.Agent;
import play.Play;
import play.cache.CacheConfig;
import play.cache.Caches;
import play.db.jpa.JPA;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class AgentService {

    private static final String LOG_CATEGORY = "agent";
    private static final String PARAM_AGENT_ID = "agentId";

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
                .map(llm.LlmTypes.ModelInfo::supportsVision)
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
                .map(llm.LlmTypes.ModelInfo::supportsVideo)
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
    private static java.util.Optional<llm.LlmTypes.ModelInfo> findModel(String providerName, String modelId) {
        if (providerName == null || modelId == null) return java.util.Optional.empty();
        var provider = ProviderRegistry.get(providerName);
        if (provider == null) return java.util.Optional.empty();
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
    private static final play.cache.Cache<String, String> fileCache = Caches.named(
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
            var browserConfig = new models.AgentToolConfig();
            browserConfig.agent = agent;
            browserConfig.toolName = "browser";
            browserConfig.enabled = false;
            browserConfig.save();

            // JCLAW-282: jclaw_api can mutate JClaw's own config — only main
            // is trusted with that authority today. Skill-creator can extend
            // this later by promoting jclaw-api into another agent's workspace.
            var jclawApiConfig = new models.AgentToolConfig();
            jclawApiConfig.agent = agent;
            jclawApiConfig.toolName = tools.JClawApiTool.TOOL_NAME;
            jclawApiConfig.enabled = false;
            jclawApiConfig.save();
        }

        // JCLAW-32: backfill MCP allowlist grants for currently-connected
        // servers. JCLAW-31's broadcast happens on connect; without this,
        // an agent created post-connect would silently see zero MCP tools.
        try {
            mcp.McpAllowlist.backfillForAgent(agent);
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
        return agent;
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
    public static java.util.Set<String> configuredModelKeys() {
        var keys = new java.util.HashSet<String>();
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
     * Delete an agent and every child row that references it. Play 1.x has no
     * JPA cascade configured on the Agent relationships, so each child table
     * must be swept explicitly — otherwise the parent delete trips an H2
     * referential-integrity error.
     *
     * <p>Order matters: deeper descendants (Message → Conversation → Agent)
     * must go first. Task.agent is nullable but still FK-constrained, so tasks
     * tied to this agent are deleted wholesale rather than nulled out (an
     * orphaned task has no meaning without its agent). Config rows under
     * {@code agent.{name}.*} and Memory rows keyed by agent name are also
     * purged to avoid orphaned diagnostic data.
     *
     * <p>Sub-agent descendants (Agent rows whose {@code parent_agent_id}
     * points at this agent or any of its descendants) are recursively deleted
     * first — each gets the same full cleanup sweep, depth-first — so the
     * Agent self-FK is satisfied by the time this agent's own delete fires.
     * Without this step the {@code parent_agent_id} constraint blocks the
     * delete with H2 error code 23503 (referential integrity violation).
     *
     * <p>The on-disk workspace directory is removed last, after DB state is
     * clean, so a failed delete leaves the filesystem in a recoverable state.
     *
     * @param agent the agent to delete (must have a persisted id)
     */
    public static void delete(Agent agent) {
        var agentId = agent.id;
        var agentName = agent.name;

        // Cascade-delete descendant agents first. Sub-agents created by
        // subagent_spawn live in a tree via Agent.parent_agent_id; their
        // workspace, Telegram binding, shell allowlist, etc. all resolve
        // via parent-chain walk from the root. Deleting the root without
        // first deleting the descendants leaves orphan Agent rows whose
        // FK references the now-doomed parent, tripping
        // ConstraintViolationException on the agent.delete() at the end
        // of this method. Recurse depth-first so each descendant gets the
        // same full cleanup sweep (its own conversations, tasks, configs,
        // workspace dir) before its Agent row goes.
        for (var child : findDirectChildren(agentId)) {
            delete(child);
        }

        // Bulk-delete messages and conversations (the high-volume tables) via JPQL,
        // then clear the Hibernate session to evict stale references. This replaces
        // the O(N) per-entity loop with 2 queries regardless of row count.
        // Session clear is necessary because JPQL DELETE bypasses Hibernate's cache.
        var em = JPA.em();
        // MessageAttachment first — FK has no ON DELETE CASCADE (see ConversationService.deleteByIds).
        em.createQuery("DELETE FROM MessageAttachment a WHERE a.message.conversation.id IN " +
                        "(SELECT c.id FROM Conversation c WHERE c.agent.id = :agentId)")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.createQuery("DELETE FROM Message m WHERE m.conversation.id IN " +
                        "(SELECT c.id FROM Conversation c WHERE c.agent.id = :agentId)")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        // SubagentRun rows reference Agent (parent + child) and Conversation
        // (parent + child) via four NOT NULL FKs. Any run where this agent
        // is either side, or where any of its conversations is either side,
        // would block the Conversation and Agent deletes below. The run is
        // historical metadata; once the conversations it references are
        // gone, the transcript is gone too, so the row carries no useful
        // signal. Drop matching rows in one pass before the conversation
        // delete to keep the FK graph satisfied.
        em.createQuery("DELETE FROM SubagentRun r WHERE r.parentAgent.id = :agentId "
                        + "OR r.childAgent.id = :agentId "
                        + "OR r.parentConversation.id IN (SELECT c.id FROM Conversation c WHERE c.agent.id = :agentId) "
                        + "OR r.childConversation.id IN (SELECT c.id FROM Conversation c WHERE c.agent.id = :agentId)")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.createQuery("DELETE FROM Conversation c WHERE c.agent.id = :agentId")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.createQuery("DELETE FROM AgentToolConfig t WHERE t.agent.id = :agentId")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.createQuery("DELETE FROM AgentSkillConfig s WHERE s.agent.id = :agentId")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.createQuery("DELETE FROM AgentSkillAllowedTool a WHERE a.agent.id = :agentId")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.createQuery("DELETE FROM AgentBinding b WHERE b.agent.id = :agentId")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.createQuery("DELETE FROM Task t WHERE t.agent.id = :agentId")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        // Remaining agent-scoped FK tables that also block the agent.delete()
        // below. TelegramTopicBinding before TelegramBinding (it FKs binding_id).
        // ToolApprovalGrant is the one that surfaced (a subagent that requested
        // tool approval carries a grant); the others are the same latent gap.
        em.createQuery("DELETE FROM TelegramTopicBinding tb "
                        + "WHERE tb.agent.id = :agentId OR tb.binding.agent.id = :agentId")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.createQuery("DELETE FROM TelegramBinding b WHERE b.agent.id = :agentId")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.createQuery("DELETE FROM ToolApprovalGrant g WHERE g.agent.id = :agentId")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.createQuery("DELETE FROM Notification n WHERE n.agent.id = :agentId")
                .setParameter(PARAM_AGENT_ID, agentId).executeUpdate();
        em.flush();
        em.clear();

        // Re-fetch agent after session clear (it was detached by em.clear)
        agent = Agent.findById(agentId);

        // Name-keyed side data (no FK, so no Hibernate cascade risk).
        // Memory goes through the MemoryStore abstraction so the cleanup works
        // regardless of which backend is active — a direct Memory.delete() would
        // only wipe the JPA table, silently orphaning Neo4j memory nodes if that
        // backend is ever enabled via memory.backend=neo4j.
        MemoryStoreFactory.get().deleteAll(agentName);
        // Native delete (not a bulk HQL Config.delete): the HQL form makes
        // Hibernate provision an HTE_config id-table whose DDL emits the
        // entity attribute `key` unquoted, which H2 rejects as a reserved
        // word — ~600 failed CREATE statements in the trace log. Native SQL
        // skips the id-table strategy and uses the real `config_key` column.
        em.createNativeQuery("DELETE FROM config WHERE config_key LIKE ?1")
                .setParameter(1, "agent." + agentName + ".%").executeUpdate();
        ConfigService.clearCache();

        agent.delete();
        deleteWorkspaceDirectory(agentName);
        EventLogger.info(LOG_CATEGORY, agentName, null, "Agent deleted");
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
     * that would escape the root. Two-layer validation:
     *
     * <ol>
     *   <li><b>Lexical</b>: collapse {@code ..} via {@code normalize()} and
     *       verify the result starts with {@code root}.</li>
     *   <li><b>Canonical</b>: realpath the deepest existing ancestor of the
     *       target (handles writes whose target doesn't exist yet), append the
     *       missing suffix, and verify the resulting absolute path is still
     *       inside the canonical root. Catches symlink escapes — a symlink
     *       inside the root that points to {@code /etc} would pass step 1 but
     *       fail step 2.</li>
     * </ol>
     *
     * Returns the canonical absolute path on success, or {@code null} on any
     * escape, missing root, or I/O error. Prefer {@link #acquireContained}
     * when the result is about to be opened or executed against — it
     * additionally double-resolves to shrink the validate→use TOCTOU window.
     *
     * @param root         the workspace root (or any other "must stay inside"
     *                     boundary)
     * @param relativePath path relative to {@code root}, possibly containing
     *                     {@code ..} segments
     * @return the canonical absolute path inside {@code root}, or
     *         {@code null} on escape / missing root / I/O error
     */
    public static Path resolveContained(Path root, String relativePath) {
        try {
            // Layer 1: lexical
            var rootAbs = root.toAbsolutePath().normalize();
            var target = rootAbs.resolve(relativePath).normalize();
            if (!target.startsWith(rootAbs)) return null;

            // Make sure the root exists so we can realpath it (idempotent).
            Files.createDirectories(rootAbs);
            var rootReal = rootAbs.toRealPath();

            // Layer 2: canonical with missing-suffix walk-up. toRealPath()
            // throws NoSuchFileException for not-yet-created targets, so for
            // write paths we walk up to the deepest existing ancestor,
            // realpath that, then re-attach the missing tail.
            var existing = target;
            var missingSuffix = new ArrayDeque<Path>();
            while (existing != null && !Files.exists(existing)) {
                missingSuffix.push(existing.getFileName());
                existing = existing.getParent();
            }
            if (existing == null) return null;
            var canonical = existing.toRealPath();
            if (!canonical.startsWith(rootReal)) return null;

            for (var seg : missingSuffix) canonical = canonical.resolve(seg);
            canonical = canonical.normalize();
            return canonical.startsWith(rootReal) ? canonical : null;
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Resolve, validate, then re-resolve immediately to confirm the canonical
     * target hasn't changed between the two resolutions. Additionally rejects
     * regular files whose inode has more than one hardlink. Returns the
     * canonical absolute path. Throws {@link SecurityException} on any escape,
     * mid-resolution divergence, or hardlink violation.
     *
     * <p><b>Three layers of defense</b>:
     * <ol>
     *   <li><b>Lexical + canonical</b> (via {@link #resolveContained}): rejects
     *       textual {@code ..} traversal and symlinks whose realpath escapes
     *       the root.</li>
     *   <li><b>Double-resolve</b>: re-resolves immediately and asserts the
     *       canonical target is unchanged. Achievable Java equivalent of
     *       OpenClaw's post-open re-check (Java NIO can't fstat an open
     *       {@code InputStream}, so we can't truly hold-then-validate; the
     *       double-resolve shrinks the validate→use TOCTOU window from
     *       "unbounded" to "microseconds").</li>
     *   <li><b>Hardlink rejection</b>: a regular file inside a workspace
     *       should never have {@code nlink > 1}. jclaw never creates hardlinks
     *       itself, the default shell allowlist doesn't include {@code ln},
     *       and pnpm-style hardlink dedup happens in dev trees outside any
     *       agent workspace. If we see {@code nlink > 1} here, treat it as an
     *       attempt to read across the sandbox boundary via the inode side
     *       door — hardlinks bypass the symlink check because there's no
     *       "link" to follow; both names point to the same inode. Skipped for
     *       directories (their nlink encodes subdirectory count) and on
     *       non-POSIX filesystems where {@code unix:nlink} isn't supported.</li>
     * </ol>
     *
     * <p>Callers should pass the returned path directly to the file operation
     * with no further work in between. Use from {@code FileSystemTools},
     * {@code DocumentsTool}, {@code ShellExecTool} (workdir), upload handlers,
     * and {@code serveWorkspaceFile}. Use {@link #resolveContained} only when
     * you need a non-throwing yes/no check.
     *
     * @param root         the workspace root the target must stay inside
     * @param relativePath path relative to {@code root}
     * @return the canonical absolute path inside {@code root}, double-resolved
     * @throws SecurityException on escape, mid-resolution divergence, or
     *                           hardlink violation
     */
    public static Path acquireContained(Path root, String relativePath) {
        var first = resolveContained(root, relativePath);
        if (first == null) {
            throw new SecurityException("Path '%s' escapes the workspace.".formatted(relativePath));
        }
        var second = resolveContained(root, relativePath);
        if (second == null || !second.equals(first)) {
            throw new SecurityException(
                    "Path '%s' resolved to a different target between validations (possible TOCTOU)."
                            .formatted(relativePath));
        }
        // Hardlink check: only meaningful for existing regular files. Directories
        // legitimately have nlink > 1 (each subdir contributes a `..` entry), and
        // not-yet-created targets have no inode to inspect.
        try {
            if (Files.exists(second) && Files.isRegularFile(second)) {
                var nlink = Files.getAttribute(second, "unix:nlink");
                if (nlink instanceof Number n && n.intValue() > 1) {
                    throw new SecurityException(
                            "Path '%s' is a hardlink (nlink=%d); rejected to prevent cross-sandbox inode aliasing."
                                    .formatted(relativePath, n.intValue()));
                }
            }
        } catch (UnsupportedOperationException _) {
            // Non-POSIX filesystem (e.g. Windows / FAT). Lexical and canonical
            // layers still apply; just degrade the hardlink check.
        } catch (IOException e) {
            throw new SecurityException(
                    "Failed to inspect '%s': %s".formatted(relativePath, e.getMessage()));
        }
        return second;
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
