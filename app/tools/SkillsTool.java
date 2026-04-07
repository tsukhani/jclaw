package tools;

import agents.SkillLoader;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import services.AgentService;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public class SkillsTool implements ToolRegistry.Tool {

    @Override
    public String name() { return "skills"; }

    @Override
    public String description() {
        return "List available skills or read a skill's full content. Actions: listSkills, readSkill.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("listSkills", "readSkill"),
                                "description", "The action to perform"),
                        "name", Map.of("type", "string",
                                "description", "Skill name (for readSkill)")
                ),
                "required", List.of("action")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get("action").getAsString();

        return switch (action) {
            case "listSkills" -> {
                SkillLoader.clearCache();
                var skills = SkillLoader.loadSkills(agent.name);
                if (skills.isEmpty()) yield "No skills available.";
                var sb = new StringBuilder("Available skills:\n");
                for (var skill : skills) {
                    sb.append("- **%s**: %s\n".formatted(skill.name(), skill.description()));
                }
                yield sb.toString();
            }
            case "readSkill" -> {
                var skillName = args.has("name") ? args.get("name").getAsString() : null;
                if (skillName == null) yield "Error: 'name' parameter required for readSkill.";
                SkillLoader.clearCache();
                var skills = SkillLoader.loadSkills(agent.name);
                var skill = skills.stream().filter(s -> s.name().equals(skillName)).findFirst();
                if (skill.isEmpty()) yield "Error: Skill '%s' not found or not available.".formatted(skillName);
                if (skill.get().location() == null) yield "Error: Skill '%s' has no file location.".formatted(skillName);
                try {
                    // Location is relative to agent workspace — resolve to absolute path
                    var path = AgentService.workspacePath(agent.name).resolve(skill.get().location());
                    yield Files.readString(path);
                } catch (IOException e) {
                    yield "Error reading skill: %s".formatted(e.getMessage());
                }
            }
            default -> "Error: Unknown action '%s'".formatted(action);
        };
    }
}
