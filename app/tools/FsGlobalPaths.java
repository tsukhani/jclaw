package tools;

import models.Agent;
import org.jspecify.annotations.Nullable;
import services.AgentService;
import services.ConfigService;
import services.WorkspaceFiles;
import utils.ErrorTemplate;
import utils.SandboxHiddenRoots;
import utils.ToolErrorTemplates;
import utils.WorkspacePathGuard;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * JF-394: an absolute or home-relative path the Main Agent's file tool takes while its {@code allowGlobalPaths} toggle
 * is on. It reaches what the Main Agent's shell reaches: everything but what {@link HarnessSandbox.MainFence} keeps
 * from it, JClaw's own folders hidden and its program files read-only. A path inside its own workspace resolves as a
 * relative one, so every workspace rule stays.
 */
final class FsGlobalPaths {

    private FsGlobalPaths() {}

    /** The target a global path resolved to, or the refusal; exactly one is set. */
    record Resolution(@Nullable Path target, @Nullable ErrorTemplate error) {
        static Resolution ok(Path target) { return new Resolution(target, null); }
        static Resolution refused(ErrorTemplate error) { return new Resolution(null, error); }
    }

    static boolean isGlobal(String path) {
        return path.startsWith("/") || path.equals("~") || path.startsWith("~/");
    }

    /** The Main Agent alone, under the key its {@code exec} reads for its working folder. */
    static boolean allowed(Agent agent) {
        return agent.isMain()
                && "true".equals(ConfigService.get("agent." + agent.name + ".shell.allowGlobalPaths", "false"));
    }

    static Resolution resolve(Agent agent, String path, boolean mutating) {
        var home = System.getProperty("user.home", "");
        var expanded = path.equals("~") ? home : path.startsWith("~/") ? home + path.substring(1) : path;
        // Contained by the filesystem root, so the guard adds only what it does for a workspace: a dangling link
        // refused, two resolutions that must agree, and a file with a second hard link refused (JF-400).
        var root = Objects.requireNonNull(AgentService.workspacePath(agent.name).toAbsolutePath().getRoot());
        Path target;
        try {
            if (WorkspacePathGuard.resolveContained(root, expanded) == null) {
                return Resolution.refused(ToolErrorTemplates.fsPathRefused(
                        "'%s' passes through a link that leads nowhere".formatted(path)));
            }
            target = WorkspacePathGuard.acquireContained(root, expanded);
        } catch (InvalidPathException | SecurityException e) {
            return Resolution.refused(ToolErrorTemplates.fsPathRefused(e.getMessage()));
        }
        var workspace = SandboxHiddenRoots.canonical(AgentService.workspacePath(agent.name));
        if (target.startsWith(workspace)) {
            try {
                return Resolution.ok(AgentService.acquireWorkspacePath(agent.name, workspace.relativize(target).toString()));
            } catch (SecurityException e) {
                return Resolution.refused(ToolErrorTemplates.fsPathRefused(e.getMessage()));
            }
        }
        var fence = HarnessSandbox.MainFence.current(WorkspaceFiles.workspaceRoot().toFile());
        for (var hidden : fence.hidden()) {
            if (target.startsWith(hidden) && (mutating || fence.readable().stream().noneMatch(target::startsWith))) {
                return Resolution.refused(ToolErrorTemplates.fsJclawFolder(path));
            }
        }
        if (mutating && !target.startsWith(fence.writable()) && fence.readOnly().stream().anyMatch(target::startsWith)) {
            return Resolution.refused(ToolErrorTemplates.fsProgramFiles(path));
        }
        var workspaces = SandboxHiddenRoots.canonical(WorkspaceFiles.workspaceRoot());
        if (target.startsWith(workspaces) && target.getNameCount() > workspaces.getNameCount()) {
            var owner = workspaces.resolve(target.getName(workspaces.getNameCount()));
            if (WorkspacePathGuard.isInSkillCredentials(owner, target)) {
                return Resolution.refused(ToolErrorTemplates.fsCredentials(path));
            }
        }
        return Resolution.ok(target);
    }
}
