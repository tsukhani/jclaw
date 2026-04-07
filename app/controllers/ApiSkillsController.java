package controllers;

import agents.SkillLoader;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import models.Agent;
import models.AgentSkillConfig;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;
import services.AgentService;

import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

@With(AuthCheck.class)
public class ApiSkillsController extends Controller {

    private static final Gson gson = new Gson();

    /** GET /api/skills — List all global skills. */
    public static void list() {
        var skills = new java.util.ArrayList<SkillLoader.SkillInfo>();
        var globalDir = SkillLoader.globalSkillsPath();
        if (Files.isDirectory(globalDir)) {
            try (var dirs = Files.list(globalDir)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    var skillFile = dir.resolve("SKILL.md");
                    if (Files.exists(skillFile)) {
                        var info = SkillLoader.parseSkillFile(skillFile);
                        if (info != null) skills.add(info);
                    }
                });
            } catch (IOException e) {
                // ignore
            }
        }
        var result = skills.stream().map(s -> skillToMap(s, true)).toList();
        renderJSON(gson.toJson(result));
    }

    /** GET /api/skills/{name} — Get a global skill with full content. */
    public static void get(String name) {
        var path = SkillLoader.globalSkillsPath().resolve(name).resolve("SKILL.md");
        if (!Files.exists(path)) notFound();
        try {
            var info = SkillLoader.parseSkillFile(path);
            var map = skillToMap(info, true);
            map.put("content", Files.readString(path));
            renderJSON(gson.toJson(map));
        } catch (IOException e) {
            error(500, "Failed to read skill: " + e.getMessage());
        }
    }

    /** POST /api/skills — Create a new global skill. */
    public static void create() {
        var body = readJsonBody();
        if (body == null || !body.has("name")) badRequest();

        var name = body.get("name").getAsString();
        var dir = SkillLoader.globalSkillsPath().resolve(name);
        if (Files.exists(dir.resolve("SKILL.md"))) {
            error(409, "Skill '%s' already exists".formatted(name));
        }

        var description = body.has("description") ? body.get("description").getAsString() : "";
        var content = body.has("content") ? body.get("content").getAsString() : "";

        // If content doesn't have frontmatter, add it
        if (!content.startsWith("---")) {
            content = "---\nname: %s\ndescription: %s\n---\n\n%s".formatted(name, description, content);
        }

        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), content);
            SkillLoader.clearCache();
            var info = SkillLoader.parseSkillFile(dir.resolve("SKILL.md"));
            var map = skillToMap(info, true);
            map.put("content", content);
            renderJSON(gson.toJson(map));
        } catch (IOException e) {
            error(500, "Failed to create skill: " + e.getMessage());
        }
    }

    /** PUT /api/skills/{name} — Update a global skill. */
    public static void update(String name) {
        var path = SkillLoader.globalSkillsPath().resolve(name).resolve("SKILL.md");
        if (!Files.exists(path)) notFound();

        var body = readJsonBody();
        if (body == null) badRequest();

        try {
            var content = body.has("content") ? body.get("content").getAsString() : Files.readString(path);
            Files.writeString(path, content);
            SkillLoader.clearCache();
            var info = SkillLoader.parseSkillFile(path);
            var map = skillToMap(info, true);
            map.put("content", content);
            renderJSON(gson.toJson(map));
        } catch (IOException e) {
            error(500, "Failed to update skill: " + e.getMessage());
        }
    }

    /** DELETE /api/skills/{name} — Delete a global skill. */
    public static void delete(String name) {
        var dir = SkillLoader.globalSkillsPath().resolve(name);
        if (!Files.isDirectory(dir)) notFound();

        try {
            // Delete all files in the skill directory
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException _) {}
                });
            }
            SkillLoader.clearCache();
            renderJSON(gson.toJson(java.util.Map.of("status", "ok")));
        } catch (IOException e) {
            error(500, "Failed to delete skill: " + e.getMessage());
        }
    }

    /** GET /api/agents/{id}/skills — List workspace skills for an agent with enabled status. */
    public static void listForAgent(Long id) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var agentDir = AgentService.workspacePath(agent.name).resolve("skills");
        var skills = new java.util.ArrayList<SkillLoader.SkillInfo>();
        if (Files.isDirectory(agentDir)) {
            try (var dirs = Files.list(agentDir)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    var skillFile = dir.resolve("SKILL.md");
                    if (Files.exists(skillFile)) {
                        var info = SkillLoader.parseSkillFile(skillFile);
                        if (info != null) skills.add(info);
                    }
                });
            } catch (IOException _) {}
        }

        var configs = AgentSkillConfig.findByAgent(agent);
        var configMap = new HashMap<String, Boolean>();
        for (var c : configs) {
            configMap.put(c.skillName, c.enabled);
        }

        var result = skills.stream().map(s -> {
            var map = skillToMap(s, false);
            map.put("enabled", configMap.getOrDefault(s.name(), true));
            return map;
        }).toList();
        renderJSON(gson.toJson(result));
    }

    /** PUT /api/agents/{id}/skills/{name} — Enable or disable a skill for an agent. */
    public static void updateForAgent(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var body = readJsonBody();
        if (body == null || !body.has("enabled")) badRequest();
        var enabled = body.get("enabled").getAsBoolean();

        var config = AgentSkillConfig.findByAgentAndSkill(agent, name);
        if (config == null) {
            config = new AgentSkillConfig();
            config.agent = agent;
            config.skillName = name;
        }
        config.enabled = enabled;
        config.save();

        renderJSON(gson.toJson(java.util.Map.of("name", name, "enabled", enabled, "status", "ok")));
    }

    /** POST /api/agents/{id}/skills/{name}/copy — Copy a global skill into the agent's workspace. */
    public static void copyToAgent(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var globalDir = SkillLoader.globalSkillsPath().resolve(name);
        if (!Files.isDirectory(globalDir)) {
            error(404, "Global skill '%s' not found".formatted(name));
        }

        var targetDir = AgentService.workspacePath(agent.name).resolve("skills").resolve(name);
        if (Files.isDirectory(targetDir) && Files.exists(targetDir.resolve("SKILL.md"))) {
            error(409, "Skill '%s' already exists in agent workspace".formatted(name));
        }

        try {
            Files.createDirectories(targetDir);
            try (var walk = Files.walk(globalDir)) {
                walk.forEach(source -> {
                    var target = targetDir.resolve(globalDir.relativize(source));
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(target);
                        } else {
                            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException ex) {
                        throw new RuntimeException("Failed to copy " + source, ex);
                    }
                });
            }
            SkillLoader.clearCache();

            // Ensure skill is enabled for this agent
            var config = AgentSkillConfig.findByAgentAndSkill(agent, name);
            if (config != null && !config.enabled) {
                config.enabled = true;
                config.save();
            }

            renderJSON(gson.toJson(java.util.Map.of("name", name, "status", "ok")));
        } catch (IOException e) {
            error(500, "Failed to copy skill: " + e.getMessage());
        }
    }

    // --- Helpers ---

    private static HashMap<String, Object> skillToMap(SkillLoader.SkillInfo s, boolean isGlobal) {
        var map = new HashMap<String, Object>();
        map.put("name", s.name());
        map.put("description", s.description());
        map.put("isGlobal", isGlobal);
        map.put("location", s.location() != null ? s.location().toString() : "");
        return map;
    }

    private static com.google.gson.JsonObject readJsonBody() {
        try {
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception _) {
            return null;
        }
    }
}
