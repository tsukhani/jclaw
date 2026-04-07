package services;

import llm.ProviderRegistry;
import models.Agent;
import play.Play;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class AgentService {

    private static final java.util.concurrent.ConcurrentHashMap<String, CachedFile> fileCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FILE_CACHE_TTL_MS = 30_000;

    private record CachedFile(String content, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    public static Agent create(String name, String modelProvider, String modelId, boolean isDefault) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = modelProvider;
        agent.modelId = modelId;
        agent.enabled = isProviderConfigured(modelProvider, modelId);
        agent.isDefault = isDefault;
        agent.save();

        createWorkspace(name);

        EventLogger.info("agent", name, null, "Agent '%s' created (provider: %s, model: %s)"
                .formatted(name, modelProvider, modelId));
        return agent;
    }

    public static Agent update(Agent agent, String name, String modelProvider, String modelId,
                                boolean enabled, boolean isDefault) {
        agent.name = name;
        agent.modelProvider = modelProvider;
        agent.modelId = modelId;
        agent.enabled = enabled && isProviderConfigured(modelProvider, modelId);
        agent.isDefault = isDefault;
        agent.save();
        return agent;
    }

    private static boolean isProviderConfigured(String providerName, String modelId) {
        var provider = ProviderRegistry.get(providerName);
        return provider != null
                && provider.models().stream().anyMatch(m -> m.id().equals(modelId));
    }

    /**
     * Syncs the enabled state of all agents based on current provider configuration.
     * Agents whose provider+model are configured get enabled; others get disabled.
     */
    public static void syncEnabledStates() {
        ProviderRegistry.refresh();
        List<Agent> agents = listAll();
        for (var agent : agents) {
            var shouldBeEnabled = isProviderConfigured(agent.modelProvider, agent.modelId);
            if (agent.enabled != shouldBeEnabled) {
                agent.enabled = shouldBeEnabled;
                agent.save();
            }
        }
    }

    public static void delete(Agent agent) {
        EventLogger.info("agent", agent.name, null, "Agent deleted");
        agent.delete();
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
