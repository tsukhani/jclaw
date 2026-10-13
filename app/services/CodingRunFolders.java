package services;

import org.jspecify.annotations.Nullable;
import tools.SubagentSpawnTool;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Where a coding-harness run works: one folder per run, under a parent directory. The parent is
 * the operator's {@code subagent.acp.workdir}, else {@code <workspace>/<agent>/coding}. A run gets
 * a new folder named from its task unless the spawn names one, which is how a chat continues in
 * a project.
 */
public final class CodingRunFolders {

    private static final int MAX_NAME = 100;

    private CodingRunFolders() {}

    /** One run's folder, and whether it was there before the run. */
    public record Folder(Path dir, boolean existing) {}

    /** Why {@code value} cannot be the parent directory, or {@code null} when it can; blank means the default. */
    public static @Nullable String parentRejection(String value) {
        var v = value.strip();
        if (v.isEmpty()) return null;
        Path dir;
        try {
            dir = expand(v);
        } catch (InvalidPathException _) {
            return SubagentSpawnTool.ACP_WORKDIR_KEY + " is not a usable path.";
        }
        if (!dir.isAbsolute()) {
            // A relative path would resolve against the server's own directory.
            return SubagentSpawnTool.ACP_WORKDIR_KEY + " must be an absolute path or start with ~/; blank means "
                    + "each agent's own workspace.";
        }
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            return SubagentSpawnTool.ACP_WORKDIR_KEY + " names a file, not a directory.";
        }
        return null;
    }

    /** The parent directory for {@code agentName}'s runs, or {@code null} when nothing names one. */
    public static @Nullable Path parent(@Nullable String agentName) {
        var configured = ConfigService.get(SubagentSpawnTool.ACP_WORKDIR_KEY);
        if (configured != null && !configured.isBlank()) return expand(configured.strip());
        return agentName == null ? null : AgentService.workspacePath(agentName).resolve("coding");
    }

    /** Why {@code name} cannot name a run's folder, or {@code null} when it can. */
    public static @Nullable String nameRejection(String name) {
        var n = name.strip();
        var isPath = n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.indexOf('\0') >= 0;
        if (n.isEmpty() || n.length() > MAX_NAME || n.equals(".") || n.equals("..") || n.startsWith("~") || isPath) {
            return "'folder' must be one folder name of at most " + MAX_NAME + " characters, not a path.";
        }
        return null;
    }

    /**
     * Create and return one run's folder under {@code parent}: the named one, reused when it is
     * already there, else a new one named from the task. A new folder never takes an existing
     * name — collisions get -2, -3, … — so two runs' files do not interleave unless asked to.
     *
     * @throws IOException when the folder cannot be created, or {@code name} resolves outside
     *                     {@code parent} (through a symlink, say)
     */
    public static Folder open(Path parent, String task, @Nullable String name) throws IOException {
        Files.createDirectories(parent);
        if (name != null && !name.isBlank()) {
            var dir = WorkspacePathGuard.resolveContained(parent, name.strip());
            if (dir == null) throw new IOException("'" + name.strip() + "' does not resolve inside " + parent);
            var existing = Files.isDirectory(dir);
            Files.createDirectories(dir);
            return new Folder(dir, existing);
        }
        var slug = SubagentSpawnTool.codingSlug(task);
        var dir = parent.resolve(slug);
        for (int n = 2; Files.exists(dir); n++) {
            dir = parent.resolve(slug + "-" + n);
        }
        Files.createDirectories(dir);
        return new Folder(dir, false);
    }

    private static Path expand(String value) {
        return expand(value, Path.of(System.getProperty("user.home")));
    }

    /** {@code value} with a leading {@code ~} or {@code ~/} read as {@code home}. Public for the default-package test. */
    public static Path expand(String value, Path home) {
        if (value.equals("~")) return home;
        return value.startsWith("~/") ? home.resolve(value.substring(2)) : Path.of(value);
    }
}
