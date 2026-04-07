package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.AgentSkill;
import models.Skill;

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
                var skills = AgentSkill.findSkillsForAgent(agent);
                if (skills.isEmpty()) yield "No skills available.";
                var sb = new StringBuilder("Available skills:\n");
                for (var skill : skills) {
                    sb.append("- **%s**: %s\n".formatted(skill.name, skill.description != null ? skill.description : ""));
                }
                yield sb.toString();
            }
            case "readSkill" -> {
                var skillName = args.has("name") ? args.get("name").getAsString() : null;
                if (skillName == null) yield "Error: 'name' parameter required for readSkill.";
                var skill = Skill.findByName(skillName);
                if (skill == null) yield "Error: Skill '%s' not found.".formatted(skillName);
                yield skill.content;
            }
            default -> "Error: Unknown action '%s'".formatted(action);
        };
    }
}
