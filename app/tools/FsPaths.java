package tools;

import com.google.gson.JsonObject;
import models.Agent;
import org.jspecify.annotations.Nullable;
import services.AgentService;
import utils.WorkspacePathGuard;

import java.nio.file.Path;

/**
 * Path resolution and sandbox/safety checks for {@link FileSystemTools}: resolving a tool
 * call's {@code path} argument to a contained workspace {@link Path}, classifying whether an
 * action mutates, and enforcing the skill-creator read-only guard.
 */
final class FsPaths {

    private FsPaths() {}

    record TargetPath(@Nullable Path workspace, @Nullable Path target, @Nullable String error) {
        static TargetPath ok(Path workspace, Path target) { return new TargetPath(workspace, target, null); }
        static TargetPath err(String error) { return new TargetPath(null, null, error); }

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
            return TargetPath.err("Error: action '%s' requires a 'path' field".formatted(action));
        }
        var relativePath = args.get("path").getAsString();
        var workspace = AgentService.workspacePath(agent.name);
        try {
            var target = AgentService.acquireWorkspacePath(agent.name, relativePath);
            return TargetPath.ok(workspace, target);
        } catch (SecurityException e) {
            return TargetPath.err(FsSupport.ERROR_PREFIX_COLON + e.getMessage());
        }
    }

    static boolean isMutatingAction(String action) {
        return FileSystemTools.ACTION_WRITE_FILE.equals(action) || FileSystemTools.ACTION_APPEND_FILE.equals(action)
                || FileSystemTools.ACTION_EDIT_FILE.equals(action) || FileSystemTools.ACTION_EDIT_LINES.equals(action);
    }

    /**
     * Skill-creator is read-only for every agent except 'main'. Only the main agent may
     * modify the skill-creator skill itself; other agents can use it to create and refactor
     * OTHER skills but cannot alter skill-creator. Returns an error string if blocked, or
     * null if the path is OK to mutate.
     */
    static @Nullable String checkSkillCreatorReadOnly(Agent agent, Path workspace, Path target) {
        if ("main".equalsIgnoreCase(agent.name)) return null;
        var skillCreatorDir = WorkspacePathGuard.resolveContained(workspace, "skills/skill-creator");
        if (skillCreatorDir != null && target.startsWith(skillCreatorDir)) {
            return "Error: The 'skill-creator' skill is read-only for agent '"
                    + agent.name
                    + "'. Only the 'main' agent can modify skill-creator. "
                    + "To get an updated skill-creator, ask the user to drag skill-creator "
                    + "from the global skills registry onto this agent's card.";
        }
        return null;
    }
}
