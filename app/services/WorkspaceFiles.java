package services;

import models.Agent;
import play.Play;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.Caches;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Workspace path-security + file I/O for agents (JCLAW-728), extracted from {@link AgentService}.
 * Owns the on-disk side of an agent's workspace: resolving the (traversal-guarded) workspace root
 * and per-agent directory, the read/write/create/reset helpers controllers and tools call, the
 * short-lived read cache, and the rename-move / delete of a workspace directory.
 *
 * <p>Path containment is delegated to {@link WorkspacePathGuard} (JCLAW-703); this class is the
 * file-I/O layer that <em>uses</em> that guard — it does not re-implement the lexical/canonical
 * boundary checks. {@code AgentService} keeps thin public delegates to these methods so the many
 * external callers of {@code AgentService.workspacePath(...)} etc. keep working unchanged.
 */
public final class WorkspaceFiles {

    private WorkspaceFiles() {}

    private static final String LOG_CATEGORY = "agent";

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
     * on-disk identity of their own ({@link AgentService#create} skips workspace
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
     * filesystem-boundary guard (JCLAW-703). See there for the two-layer
     * lexical + canonical (symlink-catching, missing-suffix walk-up) validation.
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
     * {@link WorkspacePathGuard#acquireContained}. See there for the three-layer
     * defense (lexical + canonical, double-resolve, {@code nlink > 1} hardlink
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

                    You are a general-purpose agent, configured by your operator to get real
                    work done on their behalf. Use your tools and skills freely, prefer taking
                    action over describing it, and report results plainly and honestly.

                    Replace this file with instructions specific to what you want this agent to
                    do — its job, its domain, and how it should behave.
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

    /**
     * JCLAW-533: move a root agent's workspace directory on rename so its skills
     * and workspace files (SOUL/IDENTITY/USER/BOOTSTRAP/AGENT.md) follow the
     * agent. Throws on failure so the caller's transaction rolls the rename back
     * rather than leaving the entity renamed with a stranded directory. Freeing
     * the old name also closes the reuse-leak: a new agent taking it later
     * materialises a fresh, empty workspace via {@link #createWorkspace}.
     */
    static void moveWorkspaceDirectory(Path src, Path dest) {
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
     * Remove an agent's on-disk workspace directory, best-effort. Called from the
     * deletion cascade after DB state is clean, so a failed delete leaves the
     * filesystem in a recoverable state (logged, not thrown).
     */
    static void deleteWorkspaceDirectory(String agentName) {
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
}
