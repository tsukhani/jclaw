package services;

import llm.ProviderRegistry;
import memory.MemoryStoreFactory;
import models.Agent;
import models.AgentBinding;
import models.AgentSkillConfig;
import models.AgentToolConfig;
import models.Config;
import models.Conversation;
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

        // Name-keyed side data (no FK, so no Hibernate cascade risk).
        // Memory goes through the MemoryStore abstraction so the cleanup works
        // regardless of which backend is active — a direct Memory.delete() would
        // only wipe the JPA table, silently orphaning Neo4j memory nodes if that
        // backend is ever enabled via memory.backend=neo4j.
        MemoryStoreFactory.get().deleteAll(agentName);
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
            var missingSuffix = new java.util.ArrayDeque<Path>();
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
        } catch (IOException e) {
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
        } catch (UnsupportedOperationException e) {
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
     * target that escapes the workspace root. Returns {@code null} on escape;
     * callers should surface a traversal error. Prefer
     * {@link #acquireWorkspacePath} when the result is about to be used.
     */
    public static Path resolveWorkspacePath(String agentName, String relativePath) {
        return resolveContained(workspacePath(agentName), relativePath);
    }

    /**
     * Resolve and double-validate a path inside an agent's workspace. Throws
     * {@link SecurityException} on any escape. Use this immediately before
     * opening, reading, writing, or execing against the returned path.
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
