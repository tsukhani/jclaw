package controllers;

import agents.SkillLoader;
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

    // Known tool names in JClaw — used for detecting skill dependencies
    private static final java.util.Map<String, String> KNOWN_TOOLS = java.util.Map.ofEntries(
            java.util.Map.entry("exec", "Shell command execution"),
            java.util.Map.entry("shell", "Shell command execution"),
            java.util.Map.entry("filesystem", "File read/write/list"),
            java.util.Map.entry("readFile", "File read (filesystem)"),
            java.util.Map.entry("writeFile", "File write (filesystem)"),
            java.util.Map.entry("listFiles", "File listing (filesystem)"),
            java.util.Map.entry("web_search", "Web search"),
            java.util.Map.entry("web_fetch", "Fetch URL content"),
            java.util.Map.entry("browser", "Browser automation (Playwright)"),
            java.util.Map.entry("task_manager", "Task scheduling"),
            java.util.Map.entry("checklist", "Progress tracking"),
            java.util.Map.entry("skills", "Skill management")
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
            var detectedTools = detectTools(content);

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

    /** PUT /api/skills/{name}/files/{<path>filePath} — Write a text file in a skill folder. */
    public static void writeFile(String name, String filePath) {
        var dir = SkillLoader.globalSkillsPath().resolve(name);
        var target = dir.resolve(filePath).normalize();
        if (!target.startsWith(dir)) { error(403, "Path escapes skill directory"); return; }

        var body = readJsonBody();
        if (body == null || !body.has("content")) badRequest();

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, body.get("content").getAsString());
            if ("SKILL.md".equals(target.getFileName().toString())) {
                SkillLoader.clearCache();
            }
            renderJSON(gson.toJson(java.util.Map.of("status", "ok")));
        } catch (IOException e) {
            error(500, "Failed to write file: " + e.getMessage());
        }
    }

    private static java.util.List<java.util.Map<String, String>> detectTools(String content) {
        var detectedTools = new java.util.ArrayList<java.util.Map<String, String>>();
        var seen = new java.util.HashSet<String>();
        for (var entry : KNOWN_TOOLS.entrySet()) {
            if (content.contains(entry.getKey()) && !seen.contains(entry.getValue())) {
                seen.add(entry.getValue());
                detectedTools.add(java.util.Map.of("name", entry.getKey(), "description", entry.getValue()));
            }
        }
        if (!seen.contains("Shell command execution")
                && (content.contains("```bash") || content.contains("```sh")
                    || content.contains("```shell") || content.contains("run the command")
                    || content.contains("execute the command"))) {
            detectedTools.add(java.util.Map.of("name", "exec", "description", "Shell command execution"));
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
            var detectedTools = detectTools(content);

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

    /** PUT /api/agents/{id}/skills/{name}/files/{filePath} — Write a text file in an agent skill. */
    public static void writeAgentSkillFile(Long id, String name, String filePath) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = AgentService.workspacePath(agent.name).resolve("skills").resolve(name);
        var target = dir.resolve(filePath).normalize();
        if (!target.startsWith(dir)) { error(403, "Path escapes skill directory"); return; }

        var body = readJsonBody();
        if (body == null || !body.has("content")) badRequest();

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, body.get("content").getAsString());
            if ("SKILL.md".equals(target.getFileName().toString())) SkillLoader.clearCache();
            renderJSON(gson.toJson(java.util.Map.of("status", "ok")));
        } catch (IOException e) {
            error(500, "Failed to write file: " + e.getMessage());
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
                promoteInBackground(skillDir, skillName);
            } catch (Exception e) {
                services.EventLogger.error("skills", "Background promotion failed for '%s': %s"
                        .formatted(skillName, e.getMessage()));
            }
        });

        renderJSON(gson.toJson(java.util.Map.of("status", "promoting", "skillName", skillName)));
    }

    /** Background promotion: read files, sanitize via LLM, write to global skills directory. */
    private static void promoteInBackground(Path skillDir, String skillName) {
        services.EventLogger.info("skills", "Starting background promotion of '%s'".formatted(skillName));

        // Read text files from the skill folder
        var textFiles = new LinkedHashMap<String, String>();
        var binaryFiles = new java.util.ArrayList<String>();
        try (var walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                var relName = skillDir.relativize(file).toString();
                try {
                    if (isTextFile(relName)) {
                        textFiles.put(relName, Files.readString(file));
                    } else {
                        binaryFiles.add(relName);
                    }
                } catch (IOException _) {}
            });
        } catch (IOException e) {
            services.EventLogger.error("skills", "Failed to read skill files: " + e.getMessage());
            return;
        }

        // Strip credentials.json deterministically before LLM review
        if (textFiles.containsKey("credentials.json")) {
            textFiles.put("credentials.json", stripCredentialsJson(textFiles.get("credentials.json")));
        }

        // Sanitize remaining text files via the default agent's LLM
        var sanitized = sanitizeWithLlm(textFiles);

        // Determine target folder name, appending " copy" on conflict
        var globalDir = SkillLoader.globalSkillsPath();
        var targetName = skillName;
        while (Files.isDirectory(globalDir.resolve(targetName))) {
            targetName = targetName + " copy";
        }

        // Write sanitized text files and copy binary files
        var targetDir = globalDir.resolve(targetName);
        try {
            Files.createDirectories(targetDir);
            for (var entry : sanitized.entrySet()) {
                var targetFile = targetDir.resolve(entry.getKey());
                Files.createDirectories(targetFile.getParent());
                Files.writeString(targetFile, entry.getValue());
            }
            for (var binFile : binaryFiles) {
                var source = skillDir.resolve(binFile);
                var target = targetDir.resolve(binFile);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            SkillLoader.clearCache();
            services.EventLogger.info("skills", "Promotion of '%s' completed as '%s'".formatted(skillName, targetName));

            // Notify connected frontends
            services.NotificationBus.publish("skill.promoted", java.util.Map.of(
                    "skillName", skillName,
                    "folderName", targetName
            ));
        } catch (IOException e) {
            services.EventLogger.error("skills", "Failed to write promoted skill: " + e.getMessage());
            services.NotificationBus.publish("skill.promote_failed", java.util.Map.of(
                    "skillName", skillName,
                    "error", e.getMessage()
            ));
        }
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
                - SKILL.md — the main skill instructions (may contain hardcoded secrets, tokens, URLs with keys, PII in examples)
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
    private static boolean isTextFile(String name) {
        var lower = name.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".json") || lower.endsWith(".txt")
                || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".xml")
                || lower.endsWith(".sh") || lower.endsWith(".py") || lower.endsWith(".js")
                || lower.endsWith(".ts") || lower.endsWith(".java") || lower.endsWith(".html")
                || lower.endsWith(".css") || lower.endsWith(".toml") || lower.endsWith(".ini")
                || lower.endsWith(".cfg") || lower.endsWith(".conf") || lower.endsWith(".env")
                || lower.endsWith(".properties") || lower.endsWith(".rb") || lower.endsWith(".go")
                || lower.endsWith(".rs") || lower.endsWith(".lua") || lower.endsWith(".sql")
                || !lower.contains(".");  // extensionless files (READMEs, Makefiles, etc.)
    }

    // --- Helpers ---

    private static HashMap<String, Object> skillToMap(SkillLoader.SkillInfo s, boolean isGlobal) {
        var map = new HashMap<String, Object>();
        map.put("name", s.name());
        map.put("description", s.description());
        map.put("isGlobal", isGlobal);
        map.put("location", s.location() != null ? s.location().toString() : "");
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
