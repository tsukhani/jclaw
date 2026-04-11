package services;

import llm.ProviderRegistry;
import models.Agent;
import models.AgentBinding;
import models.AgentSkillConfig;
import models.AgentToolConfig;
import models.Config;
import models.Conversation;
import models.Memory;
import models.Message;
import models.Task;
import play.Play;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class AgentService {

    private static final java.util.concurrent.ConcurrentHashMap<String, CachedFile> fileCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FILE_CACHE_TTL_MS = 30_000;

    private record CachedFile(String content, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    public static Agent create(String name, String modelProvider, String modelId, String thinkingMode) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = modelProvider;
        agent.modelId = modelId;
        // The main agent is a structural singleton and MUST always be enabled — its
        // presence is load-bearing for tier-3 routing fallback, LLM sanitization, and
        // the web chat default selection. Provider misconfiguration will surface as
        // a runtime error at call time, not as a silent disabled state.
        agent.enabled = agent.isMain() || isProviderConfigured(modelProvider, modelId);
        agent.thinkingMode = thinkingMode;
        agent.save();

        createWorkspace(name);

        // Disable browser tool for non-main agents (security)
        if (!agent.isMain()) {
            var browserConfig = new models.AgentToolConfig();
            browserConfig.agent = agent;
            browserConfig.toolName = "browser";
            browserConfig.enabled = false;
            browserConfig.save();
        }

        EventLogger.info("agent", name, null, "Agent '%s' created (provider: %s, model: %s)"
                .formatted(name, modelProvider, modelId));
        return agent;
    }

    public static Agent update(Agent agent, String name, String modelProvider, String modelId,
                                boolean enabled, String thinkingMode) {
        agent.name = name;
        agent.modelProvider = modelProvider;
        agent.modelId = modelId;
        // The main agent cannot be disabled — see the invariant note in create().
        // The caller's `enabled` argument is ignored for the main agent; a UI that
        // tries to toggle it off is either a bug or a pre-guard bypass, and the API
        // layer additionally rejects such requests with 409 in ApiAgentsController.
        agent.enabled = agent.isMain() || (enabled && isProviderConfigured(modelProvider, modelId));
        agent.thinkingMode = thinkingMode;
        agent.save();
        return agent;
    }

    private static boolean isProviderConfigured(String providerName, String modelId) {
        var provider = ProviderRegistry.get(providerName);
        return provider != null
                && provider.config().models().stream().anyMatch(m -> m.id().equals(modelId));
    }

    /**
     * Syncs the enabled state of all agents based on current provider configuration.
     * Agents whose provider+model are configured get enabled; others get disabled.
     * The main agent is exempt — it is always enabled regardless of provider state.
     */
    public static void syncEnabledStates() {
        ProviderRegistry.refresh();
        List<Agent> agents = listAll();
        for (var agent : agents) {
            if (agent.isMain()) {
                // Main is always enabled. If it was ever persisted as disabled (e.g.
                // by a pre-fix boot), heal it on the next sync pass.
                if (!agent.enabled) {
                    agent.enabled = true;
                    agent.save();
                }
                continue;
            }
            var shouldBeEnabled = isProviderConfigured(agent.modelProvider, agent.modelId);
            if (agent.enabled != shouldBeEnabled) {
                agent.enabled = shouldBeEnabled;
                agent.save();
            }
        }
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
     * <p>The on-disk workspace directory is removed last, after DB state is
     * clean, so a failed delete leaves the filesystem in a recoverable state.
     */
    public static void delete(Agent agent) {
        var agentId = agent.id;
        var agentName = agent.name;

        // Iterate + delete each child row individually rather than using bulk HQL
        // deletes. Play 1.x's Model.delete(hql, params) runs a raw SQL DELETE but
        // does NOT evict entities from the Hibernate session cache, so any child
        // entity previously loaded or created in this request (e.g. the
        // AgentToolConfig for the browser tool, seeded by AgentService.create())
        // stays in the session pointing at the agent. When the final agent.delete()
        // triggers a flush, Hibernate walks those stale cached entities and trips a
        // TransientPropertyValueException. Per-entity deletes keep the session
        // coherent at the cost of a few extra SQL round-trips — fine here because
        // row counts per agent are small.

        // Conversations cascade through their messages (Message → Conversation FK optional=false).
        List<Conversation> conversations = Conversation.find("agent.id = ?1", agentId).fetch();
        for (Conversation convo : conversations) {
            List<Message> messages = Message.find("conversation.id = ?1", convo.id).fetch();
            for (Message msg : messages) msg.delete();
            convo.delete();
        }

        List<AgentToolConfig> toolConfigs = AgentToolConfig.find("agent.id = ?1", agentId).fetch();
        for (AgentToolConfig c : toolConfigs) c.delete();

        List<AgentSkillConfig> skillConfigs = AgentSkillConfig.find("agent.id = ?1", agentId).fetch();
        for (AgentSkillConfig c : skillConfigs) c.delete();

        List<AgentBinding> bindings = AgentBinding.find("agent.id = ?1", agentId).fetch();
        for (AgentBinding b : bindings) b.delete();

        List<Task> tasks = Task.find("agent.id = ?1", agentId).fetch();
        for (Task t : tasks) t.delete();

        // Name-keyed side data (no FK, so no cascade risk — bulk delete is fine).
        Memory.delete("agentId = ?1", agentName);
        Config.delete("key LIKE ?1", "agent." + agentName + ".%");
        ConfigService.clearCache();

        agent.delete();
        deleteWorkspaceDirectory(agentName);
        EventLogger.info("agent", agentName, null, "Agent deleted");
    }

    private static void deleteWorkspaceDirectory(String agentName) {
        var dir = workspacePath(agentName);
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) { /* best-effort */ }
            });
        } catch (IOException e) {
            EventLogger.warn("agent", "Failed to remove workspace for deleted agent %s: %s"
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

    public static Path workspacePath(String agentName) {
        return workspaceRoot().resolve(agentName);
    }

    /**
     * Resolve a relative path inside an agent's workspace and reject any target
     * that escapes the workspace root (via {@code ..} or absolute paths). Returns
     * {@code null} on escape; callers should surface a traversal error.
     */
    public static Path resolveWorkspacePath(String agentName, String relativePath) {
        var workspace = workspacePath(agentName).toAbsolutePath().normalize();
        var target = workspace.resolve(relativePath).normalize();
        return target.startsWith(workspace) ? target : null;
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

            writeFile(dir.resolve("AGENT.md"), """
                    # Agent Instructions

                    You are a helpful AI assistant. Follow these guidelines:

                    - Be concise and accurate
                    - Ask for clarification when the request is ambiguous
                    - Use tools when they would help accomplish the task
                    """, overwrite);

            writeFile(dir.resolve("IDENTITY.md"), """
                    # Identity

                    Name: %s
                    """.formatted(agentName), overwrite);

            writeFile(dir.resolve("USER.md"), """
                    # User Information

                    <!-- Add information about the user here. The agent will use this context. -->
                    """, overwrite);

        } catch (IOException e) {
            EventLogger.error("agent", "Failed to create workspace for agent %s: %s"
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
        var cached = fileCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.content();
        }
        var path = workspacePath(agentName).resolve(filename);
        try {
            if (Files.exists(path)) {
                var content = Files.readString(path);
                fileCache.put(cacheKey, new CachedFile(content, System.currentTimeMillis() + FILE_CACHE_TTL_MS));
                return content;
            }
        } catch (IOException e) {
            EventLogger.warn("agent", "Failed to read workspace file %s/%s: %s"
                    .formatted(agentName, filename, e.getMessage()));
        }
        return null;
    }

    public static void writeWorkspaceFile(String agentName, String filename, String content) {
        var path = workspacePath(agentName).resolve(filename);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            fileCache.remove(agentName + "/" + filename);
        } catch (IOException e) {
            EventLogger.error("agent", "Failed to write workspace file %s/%s: %s"
                    .formatted(agentName, filename, e.getMessage()));
        }
    }

}
