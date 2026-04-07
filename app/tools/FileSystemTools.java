package tools;

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
            Files.writeString(path, content);
            return "File written successfully: %s".formatted(path.getFileName());
        } catch (IOException e) {
            return "Error writing file: %s".formatted(e.getMessage());
        }
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
