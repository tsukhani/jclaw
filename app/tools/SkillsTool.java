package tools;

import agents.SkillLoader;
import agents.ToolCatalog;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import services.AgentService;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SkillsTool implements ToolRegistry.Tool {

    @Override
    public String name() { return "skills"; }

    @Override
    public String category() { return "Utilities"; }

    @Override
    public String icon() { return "skills"; }

    @Override
    public String shortDescription() {
        return "Runtime introspection: discover which tools and skills are currently available to the agent.";
    }

    @Override
    public java.util.List<agents.ToolAction> actions() {
        return java.util.List.of(
                new agents.ToolAction("listTools",  "List all tools currently enabled for this agent"),
                new agents.ToolAction("listSkills", "List all skills currently available to this agent"),
                new agents.ToolAction("readSkill",  "Read the full content of a specific skill by name")
        );
    }

    @Override
    public boolean isSystem() { return true; }

    @Override
    public String description() {
        return "Runtime introspection: discover which tools and skills are currently available to this agent. Actions: listTools, listSkills, readSkill.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("listTools", "listSkills", "readSkill"),
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
            case "listTools" -> {
                // Filter out system tools (including this one) so the user-visible reply
                // doesn't advertise introspection plumbing. loadDisabledTools queries the
                // DB — needs a transaction context. Group by Tool.category() using the
                // canonical ordering shared with the system-prompt Tool Catalog.
                var disabled = services.Tx.run(() -> ToolRegistry.loadDisabledTools(agent));
                var tools = ToolRegistry.listTools().stream()
                        .filter(t -> !t.isSystem())
                        .filter(t -> !disabled.contains(t.name()))
                        .toList();
                if (tools.isEmpty()) yield "No tools are currently enabled for this agent.";
                var byCategory = new LinkedHashMap<String, List<ToolRegistry.Tool>>();
                for (var cat : ToolCatalog.CANONICAL_CATEGORY_ORDER) byCategory.put(cat, new ArrayList<>());
                for (var t : tools) byCategory.computeIfAbsent(t.category(), _ -> new ArrayList<>()).add(t);
                var sb = new StringBuilder("Available tools (%d):\n".formatted(tools.size()));
                for (var entry : byCategory.entrySet()) {
                    var bucket = entry.getValue();
                    if (bucket.isEmpty()) continue;
                    sb.append("\n### ").append(entry.getKey()).append("\n");
                    for (var t : bucket) {
                        sb.append("- **%s**: %s\n".formatted(t.name(), t.description()));
                    }
                }
                yield sb.toString();
            }
            case "listSkills" -> {
                SkillLoader.clearCache();
                var skills = SkillLoader.loadSkills(agent.name);
                if (skills.isEmpty()) yield "No skills are currently available for this agent.";
                // Emit a markdown table so the chat page's fixed-layout CSS gives
                // every skill name the same column width — no mid-word wrapping and
                // the Skill column lines up with the Tool column in listTools.
                var sb = new StringBuilder("Available skills (%d):\n\n".formatted(skills.size()));
                sb.append("| Skill | Description |\n");
                sb.append("|---|---|\n");
                for (var skill : skills) {
                    var icon = skill.icon() == null || skill.icon().isEmpty() ? SkillLoader.DEFAULT_SKILL_ICON : skill.icon();
                    var desc = skill.description() == null ? "" : skill.description().replace("\n", " ");
                    sb.append("| %s **%s** | %s |\n".formatted(icon, skill.name(), desc));
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
