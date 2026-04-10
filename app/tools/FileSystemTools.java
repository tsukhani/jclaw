package tools;

import agents.SkillLoader;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import services.AgentService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class FileSystemTools implements ToolRegistry.Tool {

    @Override
    public String name() { return "filesystem"; }

    @Override
    public String description() {
        return """
                Read, write, and list files in the agent's workspace directory. \
                Actions: readFile, writeFile, listFiles. All paths are relative to the workspace.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("readFile", "writeFile", "listFiles"),
                                "description", "The file operation to perform"),
                        "path", Map.of("type", "string",
                                "description", "File or directory path relative to workspace"),
                        "content", Map.of("type", "string",
                                "description", "Content to write (for writeFile action)")
                ),
                "required", List.of("action", "path")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get("action").getAsString();
        var relativePath = args.get("path").getAsString();

        var workspace = AgentService.workspacePath(agent.name);
        var target = workspace.resolve(relativePath).normalize();

        // Path traversal prevention
        if (!target.startsWith(workspace)) {
            return "Error: Path '%s' escapes the workspace directory.".formatted(relativePath);
        }

        // skill-creator is read-only for every agent except 'main'. Only the main agent
        // may modify the skill-creator skill itself; other agents can use it to create
        // and refactor OTHER skills but cannot alter skill-creator. To get an updated
        // skill-creator, drag it from the global skills registry onto the agent card.
        if ("writeFile".equals(action) && !"main".equalsIgnoreCase(agent.name)) {
            var skillCreatorDir = workspace.resolve("skills").resolve("skill-creator");
            if (target.startsWith(skillCreatorDir)) {
                return "Error: The 'skill-creator' skill is read-only for agent '"
                        + agent.name
                        + "'. Only the 'main' agent can modify skill-creator. "
                        + "To get an updated skill-creator, ask the user to drag skill-creator "
                        + "from the global skills registry onto this agent's card.";
            }
        }

        return switch (action) {
            case "readFile" -> readFile(target);
            case "writeFile" -> {
                var content = args.has("content") ? args.get("content").getAsString() : "";
                yield writeFile(target, content);
            }
            case "listFiles" -> listFiles(target);
            default -> "Error: Unknown action '%s'".formatted(action);
        };
    }

    private static final long MAX_FILE_READ_BYTES = 1_048_576; // 1MB

    private String readFile(Path path) {
        try {
            if (!Files.exists(path)) return "Error: File not found: %s".formatted(path.getFileName());
            if (Files.size(path) > MAX_FILE_READ_BYTES) {
                return "Error: File exceeds read limit (%d bytes). File size: %d bytes."
                        .formatted(MAX_FILE_READ_BYTES, Files.size(path));
            }
            return Files.readString(path);
        } catch (IOException e) {
            return "Error reading file: %s".formatted(e.getMessage());
        }
    }

    private String writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());

            // Deterministic version handling for skill definitions: any write to
            // workspace/{agent}/skills/{skill-name}/SKILL.md is routed through
            // SkillLoader.finalizeSkillMdWrite, which auto-bumps the patch version on
            // material changes and ignores whatever the LLM wrote in the version: field.
            String finalContent = content;
            String versionNote = "";
            if (isSkillDefinitionFile(path)) {
                var previousInfo = Files.exists(path) ? SkillLoader.parseSkillFile(path) : null;
                finalContent = SkillLoader.finalizeSkillMdWrite(path, content);
                var newInfo = SkillLoader.parseSkillContent(finalContent, path);
                if (newInfo != null) {
                    if (previousInfo == null) {
                        versionNote = " (new skill at version " + newInfo.version() + ")";
                    } else if (!previousInfo.version().equals(newInfo.version())) {
                        versionNote = " (version bumped " + previousInfo.version()
                                + " → " + newInfo.version() + ")";
                    } else {
                        versionNote = " (no material change; version " + newInfo.version() + " preserved)";
                    }
                }
            }

            Files.writeString(path, finalContent);
            return "File written successfully: " + path.getFileName() + versionNote;
        } catch (IOException e) {
            return "Error writing file: %s".formatted(e.getMessage());
        }
    }

    /**
     * True when {@code path} points at a SKILL.md directly inside a skill folder —
     * i.e., the path ends in {@code .../skills/{skillName}/SKILL.md}. Used to scope
     * the deterministic version bump logic to actual skill definition writes.
     */
    private static boolean isSkillDefinitionFile(Path path) {
        if (!"SKILL.md".equals(path.getFileName().toString())) return false;
        var parent = path.getParent();
        if (parent == null) return false;
        var grandparent = parent.getParent();
        if (grandparent == null) return false;
        return "skills".equals(grandparent.getFileName().toString());
    }

    private String listFiles(Path dir) {
        try {
            if (!Files.isDirectory(dir)) return "Error: Not a directory: %s".formatted(dir.getFileName());
            try (var stream = Files.list(dir)) {
                var entries = stream.map(p -> {
                    var name = p.getFileName().toString();
                    return Files.isDirectory(p) ? name + "/" : name;
                }).sorted().toList();
                return entries.isEmpty() ? "(empty directory)" : String.join("\n", entries);
            }
        } catch (IOException e) {
            return "Error listing directory: %s".formatted(e.getMessage());
        }
    }
}
