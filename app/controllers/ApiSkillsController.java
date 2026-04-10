package controllers;

import agents.SkillLoader;
import agents.ToolCatalog;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import llm.LlmTypes.ChatMessage;
import llm.LlmProvider;
import llm.ProviderRegistry;
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
import java.util.LinkedHashMap;
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
        var dir = SkillLoader.globalSkillsPath().resolve(name);
        if (!Files.isDirectory(dir)) notFound();

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

            var result = new java.util.HashMap<String, Object>();
            result.put("files", files);
            result.put("tools", detectedTools);
            renderJSON(gson.toJson(result));
        } catch (IOException e) {
            error(500, "Failed to list skill files: " + e.getMessage());
        }
    }

    /** GET /api/skills/{name}/files/{<path>filePath} — Read a text file from a skill folder. */
    public static void readFile(String name, String filePath) {
        var dir = SkillLoader.globalSkillsPath().resolve(name);
        var target = dir.resolve(filePath).normalize();
        if (!target.startsWith(dir)) { error(403, "Path escapes skill directory"); return; }
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
        var name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".json")
                || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml")
                || name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".py")
                || name.endsWith(".sh") || name.endsWith(".html") || name.endsWith(".css")
                || name.endsWith(".csv") || name.endsWith(".toml") || name.endsWith(".ini")
                || name.endsWith(".cfg") || name.endsWith(".conf") || name.endsWith(".env")
                || name.endsWith(".log") || name.endsWith(".sql");
    }

    /** DELETE /api/skills/{name} — Delete a global skill. */
    public static void delete(String name) {
        if ("skill-creator".equals(name)) {
            error(403, "The skill-creator skill is a built-in skill and cannot be deleted.");
            return;
        }
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

        // Verify the agent has every tool this skill declares it needs
        var globalSkillFile = globalDir.resolve("SKILL.md");
        if (Files.exists(globalSkillFile)) {
            var info = SkillLoader.parseSkillFile(globalSkillFile);
            if (info != null && info.tools() != null && !info.tools().isEmpty()) {
                var validation = ToolCatalog.validateSkillTools(agent, info.tools());
                if (!validation.isOk()) {
                    var parts = new java.util.ArrayList<String>();
                    if (!validation.disabled().isEmpty()) {
                        parts.add("disabled: [" + String.join(", ", validation.disabled()) + "]");
                    }
                    if (!validation.unknown().isEmpty()) {
                        parts.add("unknown: [" + String.join(", ", validation.unknown()) + "]");
                    }
                    error(400, "Cannot add skill '%s' to agent '%s': missing tools — %s. Enable the required tools for this agent and try again."
                            .formatted(name, agent.name, String.join("; ", parts)));
                }
            }
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

    /** GET /api/agents/{id}/skills/{name}/files — List files in an agent workspace skill folder. */
    public static void listAgentSkillFiles(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = AgentService.workspacePath(agent.name).resolve("skills").resolve(name);
        if (!Files.isDirectory(dir)) notFound();

        try {
            var files = new java.util.ArrayList<java.util.Map<String, Object>>();
            var allTextContent = new StringBuilder();
            try (var walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).sorted().forEach(p -> {
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

            var result = new java.util.HashMap<String, Object>();
            result.put("files", files);
            result.put("tools", detectedTools);
            renderJSON(gson.toJson(result));
        } catch (IOException e) {
            error(500, "Failed to list agent skill files: " + e.getMessage());
        }
    }

    /** GET /api/agents/{id}/skills/{name}/files/{filePath} — Read a text file from an agent skill. */
    public static void readAgentSkillFile(Long id, String name, String filePath) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = AgentService.workspacePath(agent.name).resolve("skills").resolve(name);
        var target = dir.resolve(filePath).normalize();
        if (!target.startsWith(dir)) { error(403, "Path escapes skill directory"); return; }
        if (!Files.exists(target)) notFound();

        try {
            renderJSON(gson.toJson(java.util.Map.of("path", filePath, "content", Files.readString(target))));
        } catch (IOException e) {
            error(500, "Failed to read file: " + e.getMessage());
        }
    }

    /** DELETE /api/agents/{id}/skills/{name}/delete — Delete a skill from an agent's workspace. */
    public static void deleteAgentSkill(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = AgentService.workspacePath(agent.name).resolve("skills").resolve(name);
        if (!Files.isDirectory(dir)) notFound();

        try {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException _) {}
                });
            }
            SkillLoader.clearCache();
            renderJSON(gson.toJson(java.util.Map.of("status", "ok")));
        } catch (IOException e) {
            error(500, "Failed to delete agent skill: " + e.getMessage());
        }
    }

    /**
     * POST /api/skills/promote — Promote an agent workspace skill to the global registry.
     * Returns immediately and runs LLM sanitization asynchronously on a virtual thread.
     * The frontend polls the skills list to detect completion.
     */
    public static void promote() {
        var body = readJsonBody();
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

        // Return immediately — run sanitization in the background
        Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> promoteInBackground(skillDir, skillName));
            } catch (Exception e) {
                play.Logger.error("Background promotion failed for '%s': %s",
                        skillName, e.getMessage());
            }
        });

        renderJSON(gson.toJson(java.util.Map.of("status", "promoting", "skillName", skillName)));
    }

    /** Background promotion: read files, sanitize via LLM, write to global skills directory. */
    private static final java.util.Set<String> CREDENTIAL_EXTENSIONS = java.util.Set.of(
            ".json", ".txt", ".env", ".yaml", ".yml", ".properties"
    );

    private static void promoteInBackground(Path skillDir, String skillName) {
        services.EventLogger.info("skills", "Starting background promotion of '%s'".formatted(skillName));

        // Read all files from the skill folder
        var sourceTextFiles = new LinkedHashMap<String, String>();
        var sourceBinaryFiles = new java.util.ArrayList<String>();
        try (var walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                var relName = skillDir.relativize(file).toString();
                try {
                    if (isTextFile(relName)) {
                        sourceTextFiles.put(relName, Files.readString(file));
                    } else {
                        sourceBinaryFiles.add(relName);
                    }
                } catch (IOException _) {}
            });
        } catch (IOException e) {
            services.EventLogger.error("skills", "Failed to read skill files: " + e.getMessage());
            return;
        }

        // ── Enforce standard directory structure ──
        // Relocate files that are not in the correct folder:
        //   - SKILL.md stays in root
        //   - Credential text files (json, txt, env, yaml, properties) → credentials/
        //   - Binary files anywhere → tools/
        //   - Text files in tools/ stay in tools/

        // Relocate misplaced text files
        var textFiles = new LinkedHashMap<String, String>();
        for (var entry : sourceTextFiles.entrySet()) {
            textFiles.put(enforceTextFilePath(entry.getKey()), entry.getValue());
        }

        // Relocate misplaced binary files — all binaries must be in tools/
        var binaryFiles = new java.util.ArrayList<String>();
        for (var binFile : sourceBinaryFiles) {
            if (binFile.startsWith("tools/")) {
                binaryFiles.add(binFile);
            } else {
                var fileName = binFile.contains("/") ? binFile.substring(binFile.lastIndexOf('/') + 1) : binFile;
                binaryFiles.add("tools/" + fileName);
                services.EventLogger.info("skills", "Relocated binary '%s' → 'tools/%s'".formatted(binFile, fileName));
            }
        }

        // Strip credentials deterministically before LLM review
        for (var key : textFiles.keySet().stream().toList()) {
            if (key.startsWith("credentials/")) {
                textFiles.put(key, stripCredentialsJson(textFiles.get(key)));
            }
        }

        // Hold SKILL.md frontmatter aside before sanitization so name/description/tools
        // are preserved bit-for-bit — the LLM only sees and sanitizes the body.
        SkillLoader.FrontmatterSplit originalSplit = null;
        if (textFiles.containsKey("SKILL.md")) {
            originalSplit = SkillLoader.splitFrontmatter(textFiles.get("SKILL.md"));
            if (originalSplit.frontmatter() != null) {
                textFiles.put("SKILL.md", originalSplit.body() != null ? originalSplit.body() : "");
            }
        }

        // Sanitize text files via the default agent's LLM
        var sanitized = sanitizeWithLlm(textFiles);

        // Reinject the original frontmatter onto the sanitized SKILL.md body. Defensive:
        // if the LLM emitted its own frontmatter, strip it before prepending ours.
        if (originalSplit != null && originalSplit.frontmatter() != null && sanitized.containsKey("SKILL.md")) {
            var sanitizedSplit = SkillLoader.splitFrontmatter(sanitized.get("SKILL.md"));
            var sanitizedBody = sanitizedSplit.frontmatter() != null ? sanitizedSplit.body() : sanitized.get("SKILL.md");
            if (sanitizedBody == null) sanitizedBody = "";
            sanitized.put("SKILL.md", originalSplit.frontmatter() + sanitizedBody);
        }

        // Promote replaces any existing global skill with the same name. We stage the new
        // contents in a sibling directory first so a partial failure can't destroy the
        // existing skill: only after the staging dir is fully populated do we swap it in.
        var globalDir = SkillLoader.globalSkillsPath();
        var targetDir = globalDir.resolve(skillName);
        var stagingDir = globalDir.resolve(skillName + ".promoting-" + System.currentTimeMillis());
        var backupDir = globalDir.resolve(skillName + ".replacing-" + System.currentTimeMillis());
        var replacingExisting = Files.isDirectory(targetDir);

        try {
            Files.createDirectories(stagingDir);
            Files.createDirectories(stagingDir.resolve("credentials"));
            Files.createDirectories(stagingDir.resolve("tools"));

            for (var entry : sanitized.entrySet()) {
                var stagedFile = stagingDir.resolve(entry.getKey());
                Files.createDirectories(stagedFile.getParent());
                Files.writeString(stagedFile, entry.getValue());
            }
            for (int i = 0; i < binaryFiles.size(); i++) {
                var sourceName = binaryFiles.get(i);
                var source = skillDir.resolve(sourceName);
                if (!Files.exists(source)) {
                    // File was relocated — find the original in the source dir
                    var fileName = sourceName.contains("/") ? sourceName.substring(sourceName.lastIndexOf('/') + 1) : sourceName;
                    try (var srcWalk = Files.walk(skillDir)) {
                        source = srcWalk.filter(Files::isRegularFile)
                                .filter(f -> f.getFileName().toString().equals(fileName))
                                .findFirst().orElse(null);
                    }
                }
                if (source != null && Files.exists(source)) {
                    var staged = stagingDir.resolve(sourceName);
                    Files.createDirectories(staged.getParent());
                    Files.copy(source, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            // Remove empty subdirectories from staging
            for (var subDir : List.of("credentials", "tools")) {
                var dir = stagingDir.resolve(subDir);
                if (Files.isDirectory(dir) && Files.list(dir).findAny().isEmpty()) {
                    Files.delete(dir);
                }
            }

            // Swap: move existing → backup, staging → target, delete backup
            if (replacingExisting) {
                Files.move(targetDir, backupDir);
            }
            try {
                Files.move(stagingDir, targetDir);
            } catch (IOException swapEx) {
                // Roll back: restore the original if we had one
                if (replacingExisting && Files.isDirectory(backupDir)) {
                    try { Files.move(backupDir, targetDir); } catch (IOException _) {}
                }
                throw swapEx;
            }
            if (replacingExisting && Files.isDirectory(backupDir)) {
                deleteRecursive(backupDir);
            }

            SkillLoader.clearCache();
            var action = replacingExisting ? "replaced" : "created";
            services.EventLogger.info("skills", "Promotion of '%s' completed (%s)".formatted(skillName, action));

            services.NotificationBus.publish("skill.promoted", java.util.Map.of(
                    "skillName", skillName,
                    "folderName", skillName,
                    "replaced", replacingExisting
            ));
        } catch (IOException e) {
            // Clean up any stranded staging dir
            if (Files.exists(stagingDir)) {
                try { deleteRecursive(stagingDir); } catch (IOException _) {}
            }
            services.EventLogger.error("skills", "Failed to write promoted skill: " + e.getMessage());
            services.NotificationBus.publish("skill.promote_failed", java.util.Map.of(
                    "skillName", skillName,
                    "error", e.getMessage()
            ));
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        }
    }

    /**
     * Enforce standard skill directory structure for text file paths:
     * - SKILL.md stays in root
     * - Credential files (json, txt, env, yaml, properties) in root → credentials/
     * - Text files in tools/ stay in tools/
     * - Text files in credentials/ stay in credentials/
     */
    private static String enforceTextFilePath(String path) {
        // Already in a subfolder — keep it
        if (path.startsWith("tools/") || path.startsWith("credentials/")) return path;
        // SKILL.md stays in root
        if (path.equals("SKILL.md")) return path;
        // Credential-like files in root → move to credentials/
        var lower = path.toLowerCase();
        for (var ext : CREDENTIAL_EXTENSIONS) {
            if (lower.endsWith(ext)) return "credentials/" + path;
        }
        // Other text files in root (e.g., README.md) stay in root
        return path;
    }

    /** PUT /api/skills/{name}/rename — Rename a global skill folder. */
    public static void rename(String name) {
        var body = readJsonBody();
        if (body == null || !body.has("newName")) badRequest();

        var newName = body.get("newName").getAsString().trim();
        if (newName.isEmpty()) badRequest();

        var globalDir = SkillLoader.globalSkillsPath();
        var sourceDir = globalDir.resolve(name);
        if (!Files.isDirectory(sourceDir)) notFound();

        var targetDir = globalDir.resolve(newName);
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

    // --- LLM sanitization ---

    private static LinkedHashMap<String, String> sanitizeWithLlm(LinkedHashMap<String, String> fileContents) {
        // Find the default agent's provider
        Agent defaultAgent = Agent.findDefault();
        if (defaultAgent == null) {
            services.EventLogger.warn("skills", "Sanitization skipped: no default agent configured");
            return fileContents;
        }

        var provider = ProviderRegistry.get(defaultAgent.modelProvider);
        if (provider == null) {
            services.EventLogger.warn("skills", "Sanitization skipped: no provider for agent " + defaultAgent.name);
            return fileContents;
        }

        services.EventLogger.info("skills", "Starting LLM sanitization of %d file(s) via %s / %s"
                .formatted(fileContents.size(), provider.config().name(), defaultAgent.modelId));

        // Build file listing for the prompt
        var sb = new StringBuilder();
        for (var entry : fileContents.entrySet()) {
            sb.append("=== FILE: %s ===\n".formatted(entry.getKey()));
            sb.append(entry.getValue());
            sb.append("\n\n");
        }

        var systemPrompt = """
                You are a security reviewer. You will receive text files from an AI agent's skill folder.
                A skill folder has this structure:
                - SKILL.md — the main skill instructions, BODY ONLY (the YAML frontmatter has already been extracted and will be reinjected verbatim after your review, so do NOT emit or fabricate frontmatter for SKILL.md)
                - credentials.json — already pre-stripped, no action needed
                - tools/ — optional tool scripts that may contain hardcoded secrets or personal data

                Your job is to identify and redact any secrets, API keys, tokens, passwords, bearer tokens, \
                webhook URLs, personal information (names, emails, phone numbers, addresses, usernames), \
                or other sensitive data that may have been embedded in the files.

                Replace each redacted value with a descriptive placeholder like [API_KEY], [PASSWORD], [EMAIL], \
                [PHONE_NUMBER], [PERSONAL_NAME], [TOKEN], [WEBHOOK_URL], [USERNAME], etc.

                Return ONLY a valid JSON object mapping each filename to its sanitized content. \
                Do not include any other text, markdown formatting, or code fences. \
                Example: {"SKILL.md": "sanitized content here"}

                If no sensitive data is found in a file, return its content unchanged.
                """;

        var messages = List.of(
                ChatMessage.system(systemPrompt),
                ChatMessage.user(sb.toString())
        );

        try {
            for (var entry : fileContents.entrySet()) {
                services.EventLogger.info("skills", "Sanitizing file: %s (%d chars)"
                        .formatted(entry.getKey(), entry.getValue().length()));
            }

            var response = provider.chat(defaultAgent.modelId, messages, null, null, null);
            var text = response.choices().get(0).message().content().toString().trim();

            services.EventLogger.info("skills",
                    "LLM sanitization response (%d chars)".formatted(text.length()),
                    text);

            // Strip markdown code fences if the LLM wrapped it
            if (text.startsWith("```")) {
                text = text.replaceFirst("^```(?:json)?\\s*\\n?", "").replaceFirst("\\n?```$", "").trim();
            }

            var json = JsonParser.parseString(text).getAsJsonObject();
            var result = new LinkedHashMap<String, String>();
            for (var entry : fileContents.entrySet()) {
                if (json.has(entry.getKey())) {
                    var sanitized = json.get(entry.getKey()).getAsString();
                    var changed = !sanitized.equals(entry.getValue());
                    result.put(entry.getKey(), sanitized);
                    services.EventLogger.info("skills", "  %s: %s"
                            .formatted(entry.getKey(), changed ? "REDACTED (content changed)" : "clean (no changes)"));
                } else {
                    result.put(entry.getKey(), entry.getValue());
                    services.EventLogger.info("skills", "  %s: not in LLM response, kept original"
                            .formatted(entry.getKey()));
                }
            }

            services.EventLogger.info("skills", "Sanitization complete");
            return result;
        } catch (Exception e) {
            // If LLM fails, return original content rather than blocking the promotion
            services.EventLogger.warn("skills", "LLM sanitization failed, using original content: " + e.getMessage());
            return fileContents;
        }
    }

    /** Deterministically strip all values from credentials.json, preserving keys as documentation. */
    private static String stripCredentialsJson(String content) {
        try {
            var json = JsonParser.parseString(content).getAsJsonObject();
            var stripped = new com.google.gson.JsonObject();
            for (var entry : json.entrySet()) {
                stripped.addProperty(entry.getKey(), "[CREDENTIAL]");
            }
            return new Gson().newBuilder().setPrettyPrinting().create().toJson(stripped);
        } catch (Exception _) {
            // Not valid JSON — return empty object with a comment
            return "{}";
        }
    }

    /** Check if a file is likely a text file based on extension. */
    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
            ".md", ".json", ".txt", ".yaml", ".yml", ".xml", ".sh", ".py", ".js",
            ".ts", ".java", ".html", ".css", ".toml", ".ini", ".cfg", ".conf", ".env",
            ".properties", ".rb", ".go", ".rs", ".lua", ".sql"
    );

    private static final java.util.Set<String> KNOWN_TEXT_FILES = java.util.Set.of(
            "readme", "makefile", "dockerfile", "license", "changelog",
            "gemfile", "rakefile", "procfile", "vagrantfile"
    );

    private static boolean isTextFile(String name) {
        var lower = name.toLowerCase();
        // Check known text extensions
        for (var ext : TEXT_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        // Check known extensionless text files by filename
        var baseName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        return KNOWN_TEXT_FILES.contains(baseName);
    }

    // --- Helpers ---

    private static HashMap<String, Object> skillToMap(SkillLoader.SkillInfo s, boolean isGlobal) {
        var map = new HashMap<String, Object>();
        map.put("name", s.name());
        map.put("description", s.description());
        map.put("isGlobal", isGlobal);
        map.put("location", s.location() != null ? s.location().toString() : "");
        map.put("tools", s.tools() != null ? s.tools() : List.of());
        // Folder name = parent directory name of the SKILL.md file
        if (s.location() != null && s.location().getParent() != null) {
            map.put("folderName", s.location().getParent().getFileName().toString());
        } else {
            map.put("folderName", s.name());
        }
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
