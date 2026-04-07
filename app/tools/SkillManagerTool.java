package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.Skill;

import java.util.List;
import java.util.Map;

public class SkillManagerTool implements ToolRegistry.Tool {

    @Override
    public String name() { return "skill_manager"; }

    @Override
    public String description() {
        return "Manage the skills registry. Actions: createSkill, updateSkill, deleteSkill, listAllSkills. " +
               "Skills are reusable instruction sets that can be assigned to agents.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("createSkill", "updateSkill", "deleteSkill", "listAllSkills"),
                                "description", "The action to perform"),
                        "name", Map.of("type", "string",
                                "description", "Skill name (required for create/update/delete)"),
                        "description", Map.of("type", "string",
                                "description", "Short description of what the skill does (for create/update)"),
                        "content", Map.of("type", "string",
                                "description", "Full skill content in markdown (for create/update)"),
                        "isGlobal", Map.of("type", "boolean",
                                "description", "Whether the skill is available to all agents (default: false)")
                ),
                "required", List.of("action")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get("action").getAsString();

        return switch (action) {
            case "createSkill" -> createSkill(args);
            case "updateSkill" -> updateSkill(args);
            case "deleteSkill" -> deleteSkill(args);
            case "listAllSkills" -> listAllSkills();
            default -> "Error: Unknown action '%s'".formatted(action);
        };
    }

    private String createSkill(com.google.gson.JsonObject args) {
        var name = args.has("name") ? args.get("name").getAsString() : null;
        if (name == null || name.isBlank()) return "Error: name is required";

        if (Skill.findByName(name) != null) {
            return "Error: A skill named '%s' already exists".formatted(name);
        }

        var skill = new Skill();
        skill.name = name;
        skill.description = args.has("description") ? args.get("description").getAsString() : "";
        skill.content = args.has("content") ? args.get("content").getAsString() : "";
        skill.isGlobal = args.has("isGlobal") && args.get("isGlobal").getAsBoolean();
        skill.save();

        services.EventLogger.info("skill", null, null,
                "Skill '%s' created (global: %s)".formatted(name, skill.isGlobal));
        return "Skill '%s' created successfully (id: %d, global: %s)".formatted(name, skill.id, skill.isGlobal);
    }

    private String updateSkill(com.google.gson.JsonObject args) {
        var name = args.has("name") ? args.get("name").getAsString() : null;
        if (name == null) return "Error: name is required";

        var skill = Skill.findByName(name);
        if (skill == null) return "Error: Skill '%s' not found".formatted(name);

        if (args.has("description")) skill.description = args.get("description").getAsString();
        if (args.has("content")) skill.content = args.get("content").getAsString();
        if (args.has("isGlobal")) skill.isGlobal = args.get("isGlobal").getAsBoolean();
        skill.save();

        return "Skill '%s' updated successfully".formatted(name);
    }

    private String deleteSkill(com.google.gson.JsonObject args) {
        var name = args.has("name") ? args.get("name").getAsString() : null;
        if (name == null) return "Error: name is required";

        var skill = Skill.findByName(name);
        if (skill == null) return "Error: Skill '%s' not found".formatted(name);

        // Remove all agent assignments first
        models.AgentSkill.delete("skill = ?1", skill);
        skill.delete();

        services.EventLogger.info("skill", null, null, "Skill '%s' deleted".formatted(name));
        return "Skill '%s' deleted successfully".formatted(name);
    }

    private String listAllSkills() {
        var skills = Skill.findAll();
        if (skills.isEmpty()) return "No skills in the registry.";

        var sb = new StringBuilder("Skills in registry:\n");
        for (var s : skills) {
            var skill = (Skill) s;
            sb.append("- **%s** (id: %d, global: %s): %s\n"
                    .formatted(skill.name, skill.id, skill.isGlobal, skill.description));
        }
        return sb.toString();
    }
}
