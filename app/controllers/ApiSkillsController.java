package controllers;

import agents.SkillLoader;
import com.google.gson.Gson;
import models.Agent;
import models.AgentSkillConfig;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.SkillPromotionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

@With(AuthCheck.class)
public class ApiSkillsController extends Controller {

    private static final Gson gson = INSTANCE;

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
        var path = resolveSkillName(SkillLoader.globalSkillsPath(), name).resolve("SKILL.md");
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

    // Aliases for body-text heuristic detection only — maps informal names to canonical tool names.
    // The canonical tool list itself is derived live from ToolRegistry via ToolCatalog.
    private static final java.util.Map<String, String> TOOL_ALIASES = java.util.Map.ofEntries(
            java.util.Map.entry("shell", "exec"),
            java.util.Map.entry("readFile", "filesystem"),
            java.util.Map.entry("writeFile", "filesystem"),
            java.util.Map.entry("listFiles", "filesystem")
    );

    /** GET /api/skills/{name}/files — List all files in a skill folder with metadata and detected tool dependencies. */
    public static void listFiles(String name) {
        var dir = resolveSkillName(SkillLoader.globalSkillsPath(), name);
        if (!Files.isDirectory(dir)) notFound();
        listSkillFilesFrom(dir);
    }

    /** GET /api/skills/{name}/files/{<path>filePath} — Read a text file from a skill folder. */
    public static void readFile(String name, String filePath) {
        var dir = resolveSkillName(SkillLoader.globalSkillsPath(), name);
        readSkillFileFrom(dir, filePath);
    }

    /**
     * Resolve the tools a skill needs. Prefers the explicit {@code tools:} frontmatter
     * (the authoritative declaration) whenever the skill has one — even if the declared
     * list is empty, which is the correct answer for pure-reasoning skills. Falls back
     * to the body-text heuristic only for legacy skills that predate the declaration.
     */
    private static java.util.List<java.util.Map<String, String>> resolveSkillTools(Path skillDir, String allContent) {
        var skillFile = skillDir.resolve("SKILL.md");
        if (Files.exists(skillFile)) {
            var info = SkillLoader.parseSkillFile(skillFile);
            if (info != null && info.toolsDeclared()) {
                var result = new java.util.ArrayList<java.util.Map<String, String>>();
                for (var name : info.tools()) {
                    var tool = agents.ToolRegistry.listTools().stream()
                            .filter(t -> t.name().equals(name))
                            .findFirst()
                            .orElse(null);
                    result.add(java.util.Map.of(
                            "name", name,
                            "description", tool != null && tool.description() != null ? tool.description() : ""
                    ));
                }
                return result;
            }
        }
        // Legacy skill with no declaration — fall back to the body-text heuristic
        return detectTools(allContent);
    }

    private static java.util.List<java.util.Map<String, String>> detectTools(String content) {
        var detectedTools = new java.util.ArrayList<java.util.Map<String, String>>();
        var seen = new java.util.HashSet<String>();

        // Scan every live tool name from the registry against the body text
        for (var tool : agents.ToolRegistry.listTools()) {
            if (content.contains(tool.name()) && seen.add(tool.name())) {
                detectedTools.add(java.util.Map.of("name", tool.name(),
                        "description", tool.description() != null ? tool.description() : ""));
            }
        }

        // Informal aliases (readFile, shell, writeFile) → map to the canonical tool
        for (var entry : TOOL_ALIASES.entrySet()) {
            if (content.contains(entry.getKey()) && seen.add(entry.getValue())) {
                var canonical = entry.getValue();
                var tool = agents.ToolRegistry.listTools().stream()
                        .filter(t -> t.name().equals(canonical))
                        .findFirst()
                        .orElse(null);
                detectedTools.add(java.util.Map.of("name", canonical,
                        "description", tool != null && tool.description() != null ? tool.description() : ""));
            }
        }

        // Implicit shell usage — bash/sh code fences
        if (seen.add("exec")
                && (content.contains("```bash") || content.contains("```sh")
                    || content.contains("```shell") || content.contains("run the command")
                    || content.contains("execute the command"))) {
            var tool = agents.ToolRegistry.listTools().stream()
                    .filter(t -> t.name().equals("exec"))
                    .findFirst()
                    .orElse(null);
            detectedTools.add(java.util.Map.of("name", "exec",
                    "description", tool != null && tool.description() != null ? tool.description() : "Shell command execution"));
        }
        return detectedTools;
    }

    private static boolean isTextFile(Path p) {
        return SkillLoader.isTextFile(p.getFileName().toString());
    }

    /** Format a list of scanner violations for user-facing error messages. */
    private static String formatViolations(java.util.List<services.SkillBinaryScanner.Violation> violations) {
        return services.SkillPromotionService.formatViolations(violations);
    }

    /** DELETE /api/skills/{name} — Delete a global skill. */
    public static void delete(String name) {
        if ("skill-creator".equals(name)) {
            error(403, "The skill-creator skill is a built-in skill and cannot be deleted.");
            return;
        }
        var dir = resolveSkillName(SkillLoader.globalSkillsPath(), name);
        if (!Files.isDirectory(dir)) notFound();
        deleteSkillDir(dir);
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

        var body = JsonBodyReader.readJsonBody();
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

        var globalDir = resolveSkillName(SkillLoader.globalSkillsPath(), name);
        if (!Files.isDirectory(globalDir)) {
            error(404, "Global skill '%s' not found".formatted(name));
        }

        // Verify the agent has every tool this skill declares it needs
        var toolCheck = services.SkillPromotionService.validateToolRequirements(agent, name);
        if (!toolCheck.ok()) {
            response.status = 400;
            renderText(toolCheck.message());
        }

        // Malware scan before the copy touches the agent workspace
        var copyViolations = services.SkillBinaryScanner.scan(globalDir);
        if (!copyViolations.isEmpty()) {
            response.status = 400;
            renderText("Cannot add skill '%s' to agent '%s': malware detected — %s"
                    .formatted(name, agent.name, formatViolations(copyViolations)));
        }

        var agentSkillsDir = AgentService.workspacePath(agent.name).resolve("skills");
        var targetDir = resolveSkillName(agentSkillsDir, name);
        var replacing = Files.isDirectory(targetDir) && Files.exists(targetDir.resolve("SKILL.md"));

        try {
            services.SkillPromotionService.copyToAgentWorkspace(agent, name);

            // Ensure skill is enabled for this agent
            var config = AgentSkillConfig.findByAgentAndSkill(agent, name);
            if (config != null && !config.enabled) {
                config.enabled = true;
                config.save();
            }

            renderJSON(gson.toJson(java.util.Map.of(
                    "name", name,
                    "status", "ok",
                    "replaced", replacing
            )));
        } catch (IOException e) {
            error(500, "Failed to copy skill: " + e.getMessage());
        }
    }

    /** GET /api/agents/{id}/skills/{name}/files — List files in an agent workspace skill folder. */
    public static void listAgentSkillFiles(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = resolveSkillName(AgentService.workspacePath(agent.name).resolve("skills"), name);
        if (!Files.isDirectory(dir)) notFound();
        listSkillFilesFrom(dir);
    }

    /** GET /api/agents/{id}/skills/{name}/files/{filePath} — Read a text file from an agent skill. */
    public static void readAgentSkillFile(Long id, String name, String filePath) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = resolveSkillName(AgentService.workspacePath(agent.name).resolve("skills"), name);
        readSkillFileFrom(dir, filePath);
    }

    /** DELETE /api/agents/{id}/skills/{name}/delete — Delete a skill from an agent's workspace. */
    public static void deleteAgentSkill(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = resolveSkillName(AgentService.workspacePath(agent.name).resolve("skills"), name);
        if (!Files.isDirectory(dir)) notFound();
        // Revoke the skill's shell-allowlist grants for this agent BEFORE deleting
        // the workspace copy — if the filesystem delete fails halfway we'd rather
        // have a stale directory without grants than the inverse. The revoke is
        // idempotent, so a retry after a transient failure is safe.
        services.SkillPromotionService.revokeAgentAllowlist(agent, name);
        deleteSkillDir(dir);
    }

    /**
     * POST /api/skills/promote — Promote an agent workspace skill to the global registry.
     * Returns immediately and runs LLM sanitization asynchronously on a virtual thread.
     * The frontend polls the skills list to detect completion.
     */
    public static void promote() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("agentId") || !body.has("skillName")) badRequest();

        var agentId = body.get("agentId").getAsLong();
        var skillName = body.get("skillName").getAsString();

        Agent agent = Agent.findById(agentId);
        if (agent == null) notFound();

        var agentName = agent.name;
        var skillDir = AgentService.workspacePath(agentName).resolve("skills").resolve(skillName);
        if (!Files.isDirectory(skillDir) || !Files.exists(skillDir.resolve("SKILL.md"))) {
            error(404, "Skill '%s' not found in agent workspace".formatted(skillName));
        }

        // Return immediately — run sanitization in the background. Pass the
        // requesting agent's id so the service can gate promotion on the
        // skill-creator capability (see SkillPromotionService.promoteInBackground).
        var requestingAgentId = agent.id;
        Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> services.SkillPromotionService.promoteInBackground(
                        skillDir, skillName, requestingAgentId));
            } catch (Exception e) {
                play.Logger.error("Background promotion failed for '%s': %s",
                        skillName, e.getMessage());
            }
        });

        renderJSON(gson.toJson(java.util.Map.of("status", "promoting", "skillName", skillName)));
    }

    /** PUT /api/skills/{name}/rename — Rename a global skill folder. */
    public static void rename(String name) {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("newName")) badRequest();

        var newName = body.get("newName").getAsString().strip();
        if (newName.isEmpty()) badRequest();

        var globalDir = SkillLoader.globalSkillsPath();
        var sourceDir = resolveSkillName(globalDir, name);
        if (!Files.isDirectory(sourceDir)) notFound();

        var targetDir = resolveSkillName(globalDir, newName);
        if (Files.exists(targetDir)) {
            error(409, "A skill with folder name '%s' already exists".formatted(newName));
        }

        try {
            Files.move(sourceDir, targetDir);
            SkillLoader.clearCache();
            renderJSON(gson.toJson(java.util.Map.of("oldName", name, "newName", newName, "status", "ok")));
        } catch (IOException e) {
            error(500, "Failed to rename skill: " + e.getMessage());
        }
    }

    // --- Shared helpers for global / agent-workspace skill operations ---

    /** Walk a skill directory and render files + detected tools as JSON. */
    private static void listSkillFilesFrom(Path dir) {
        try {
            var files = new java.util.ArrayList<java.util.Map<String, Object>>();
            var allTextContent = new StringBuilder();
            try (var walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        var rel = dir.relativize(p).toString();
                        var map = new java.util.HashMap<String, Object>();
                        map.put("path", rel);
                        map.put("name", p.getFileName().toString());
                        try { map.put("size", Files.size(p)); } catch (IOException _) { map.put("size", 0); }
                        var text = isTextFile(p);
                        map.put("isText", text);
                        files.add(map);
                        if (text) {
                            try { allTextContent.append(Files.readString(p)).append("\n"); } catch (IOException _) {}
                        }
                    });
            }

            var content = allTextContent.toString();
            var detectedTools = resolveSkillTools(dir, content);

            // Shell commands declared in the SKILL.md frontmatter — the set
            // this skill will contribute to an installing agent's allowlist.
            // Surfaced here so the detail page can render a "Commands" pill row.
            var skillMd = dir.resolve("SKILL.md");
            java.util.List<String> commands = java.util.List.of();
            String author = "";
            if (Files.exists(skillMd)) {
                var info = SkillLoader.parseSkillFile(skillMd);
                if (info != null) {
                    if (info.commands() != null) commands = info.commands();
                    if (info.author() != null) author = info.author();
                }
            }

            var result = new java.util.HashMap<String, Object>();
            result.put("files", files);
            result.put("tools", detectedTools);
            result.put("commands", commands);
            result.put("author", author);
            renderJSON(gson.toJson(result));
        } catch (IOException e) {
            error(500, "Failed to list skill files: " + e.getMessage());
        }
    }

    /** Read a single file from a skill directory, with path-traversal protection. */
    private static void readSkillFileFrom(Path dir, String filePath) {
        Path target;
        try {
            target = AgentService.acquireContained(dir, filePath);
        } catch (SecurityException e) {
            error(403, "Path escapes skill directory");
            return;
        }
        if (!Files.exists(target)) notFound();

        try {
            renderJSON(gson.toJson(java.util.Map.of(
                    "path", filePath,
                    "content", Files.readString(target)
            )));
        } catch (IOException e) {
            error(500, "Failed to read file: " + e.getMessage());
        }
    }

    /** Recursively delete a skill directory and clear the loader cache. */
    private static void deleteSkillDir(Path dir) {
        try {
            SkillPromotionService.deleteRecursive(dir);
            SkillLoader.clearCache();
            renderJSON(gson.toJson(java.util.Map.of("status", "ok")));
        } catch (IOException e) {
            error(500, "Failed to delete skill: " + e.getMessage());
        }
    }

    // --- Helpers ---

    /**
     * Resolve a skill name to a contained path inside the given root, rejecting
     * any name that escapes (e.g. "../../../etc"). Calls {@code notFound()} on
     * escape so callers never see a null return.
     */
    private static Path resolveSkillName(Path root, String name) {
        var resolved = AgentService.resolveContained(root, name);
        if (resolved == null) {
            notFound();
        }
        return resolved;
    }

    private static HashMap<String, Object> skillToMap(SkillLoader.SkillInfo s, boolean isGlobal) {
        var map = new HashMap<String, Object>();
        map.put("name", s.name());
        map.put("description", s.description());
        map.put("isGlobal", isGlobal);
        map.put("location", s.location() != null ? s.location().toString() : "");
        map.put("tools", s.tools() != null ? s.tools() : List.of());
        map.put("commands", s.commands() != null ? s.commands() : List.of());
        map.put("author", s.author() != null ? s.author() : "");
        map.put("version", s.version() != null ? s.version() : "0.0.0");
        // Folder name = parent directory name of the SKILL.md file
        if (s.location() != null && s.location().getParent() != null) {
            map.put("folderName", s.location().getParent().getFileName().toString());
        } else {
            map.put("folderName", s.name());
        }
        return map;
    }

}
