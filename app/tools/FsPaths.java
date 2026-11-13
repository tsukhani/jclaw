package tools;

import com.google.gson.JsonObject;
import models.Agent;
import org.jspecify.annotations.Nullable;
import services.AgentService;
import utils.ErrorTemplate;
import utils.ToolErrorTemplates;
import utils.WorkspacePathGuard;

import java.nio.file.Path;

/**
 * Path resolution and sandbox/safety checks for {@link FileSystemTools}: resolving a tool
 * call's {@code path} argument to a contained workspace {@link Path}, classifying whether an
 * action mutates, and enforcing the skill-creator read-only guard.
 */
final class FsPaths {

    private FsPaths() {}

    record TargetPath(@Nullable Path workspace, @Nullable Path target, @Nullable ErrorTemplate error) {
        static TargetPath ok(Path workspace, Path target) { return new TargetPath(workspace, target, null); }
        static TargetPath err(ErrorTemplate error) { return new TargetPath(null, null, error); }

        /** Valid only once {@link #error()} has been checked non-null. */
        ErrorTemplate resolvedError() {
            if (error == null) throw new IllegalStateException("path resolved");
            return error;
        }

        /** Valid only once {@link #error()} has been checked null — {@code err()} carries no paths. */
        Path resolvedWorkspace() { return require(workspace); }

        /** Valid only once {@link #error()} has been checked null — {@code err()} carries no paths. */
        Path resolvedTarget() { return require(target); }

        private Path require(@Nullable Path p) {
            if (p == null) throw new IllegalStateException("path unresolved: " + error);
            return p;
        }
    }

    static TargetPath resolveTargetPath(JsonObject args, Agent agent, String action) {
        if (!args.has("path")) {
            return TargetPath.err(ToolErrorTemplates.fsPathMissing(action));
        }
        var relativePath = args.get("path").getAsString();
        var workspace = AgentService.workspacePath(agent.name);
        try {
            var target = AgentService.acquireWorkspacePath(agent.name, relativePath);
            return TargetPath.ok(workspace, target);
        } catch (SecurityException e) {
            return TargetPath.err(ToolErrorTemplates.fsPathRefused(e.getMessage()));
        }
    }

    static boolean isMutatingAction(String action) {
        return FileSystemTools.ACTION_WRITE_FILE.equals(action) || FileSystemTools.ACTION_APPEND_FILE.equals(action)
                || FileSystemTools.ACTION_EDIT_FILE.equals(action) || FileSystemTools.ACTION_EDIT_LINES.equals(action);
    }

    /**
     * JF-322: a skill's credentials folder is closed to every file action but a whole-file write, which stores a
     * value and reports nothing of what was there. Returns the refusal's template, or null when the action may run.
     */
    static @Nullable ErrorTemplate checkSkillCredentials(String action, Path workspace, Path target, String path) {
        if (FileSystemTools.ACTION_WRITE_FILE.equals(action)) return null;
        return WorkspacePathGuard.isInSkillCredentials(workspace, target) ? ToolErrorTemplates.fsCredentials(path) : null;
    }

    /**
     * The built-in skills ({@link utils.BuiltInSkills}) are read-only for every agent except 'main' (JF-392): another
     * agent uses its copies and authors skills of its own, but alters none of them. Returns the refusal's template if
     * blocked, or null if the path may change.
     */
    static @Nullable ErrorTemplate checkBuiltInSkillReadOnly(Agent agent, Path workspace, Path target) {
        if ("main".equalsIgnoreCase(agent.name)) return null;
        return WorkspacePathGuard.isInBuiltInSkill(workspace, target) ? ToolErrorTemplates.fsReadOnly(agent.name) : null;
    }
}
