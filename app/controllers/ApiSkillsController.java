package controllers;

import agents.SkillLoader;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

    // Filesystem layout — skills live under {workspace}/skills/{folder}/SKILL.md.
    private static final String SKILL_MD = "SKILL.md";
    private static final String SKILLS_DIR = "skills";

    // Canonical tool name used both as an alias target and a body-text heuristic.
    private static final String TOOL_FILESYSTEM = "filesystem";

    // JSON request/response payload keys.
    private static final String KEY_STATUS = "status";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_NEW_NAME = "newName";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_SKILL_NAME = "skillName";

    public record SkillView(String name, String description, boolean isGlobal, String location,
                            List<String> tools, List<String> commands, String author, String icon,
                            String version, String folderName) {}

    public record SkillDetailView(String name, String description, boolean isGlobal, String location,
                                  List<String> tools, List<String> commands, String author, String icon,
                                  String version, String folderName, String content) {}

    public record AgentSkillView(String name, String description, boolean isGlobal, String location,
                                 List<String> tools, List<String> commands, String author, String icon,
                                 String version, String folderName, boolean enabled) {}

    public record SkillToolRef(String name, String description) {}

    public record SkillFileEntry(String path, String name, long size, boolean isText) {}

    public record SkillFilesResponse(List<SkillFileEntry> files, List<SkillToolRef> tools,
                                     List<String> commands, String author) {}

    public record SkillFileContentResponse(String path, String content) {}

    public record SkillStatusResponse(String status) {}

    public record SkillToggleRequest(boolean enabled) {}

    public record SkillToggleResponse(String name, boolean enabled, String status) {}

    public record SkillCopyResponse(String name, String status, boolean replaced) {}

    public record SkillPromoteRequest(Long agentId, String skillName) {}

    public record SkillPromoteResponse(String status, String skillName) {}

    public record SkillRenameRequest(String newName) {}

    public record SkillRenameResponse(String oldName, String newName, String status) {}

    /** GET /api/skills — List all global skills. */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SkillView.class))))
    @ChatSafe(summary = "List all skills in the global registry")
    public static void list() {
        var skills = new java.util.ArrayList<SkillLoader.SkillInfo>();
        var globalDir = SkillLoader.globalSkillsPath();
        if (Files.isDirectory(globalDir)) {
            try (var dirs = Files.list(globalDir)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    var skillFile = dir.resolve(SKILL_MD);
                    if (Files.exists(skillFile)) {
                        var info = SkillLoader.parseSkillFile(skillFile);
                        if (info != null) skills.add(info);
                    }
                });
            } catch (IOException _) {
                // ignore
            }
        }
        var result = skills.stream().map(s -> skillToMap(s, true)).toList();
        renderJSON(gson.toJson(result));
    }

    /** GET /api/skills/{name} — Get a global skill with full content. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillDetailView.class)))
    public static void get(String name) {
        var path = resolveSkillName(SkillLoader.globalSkillsPath(), name).resolve(SKILL_MD);
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
            java.util.Map.entry("readFile", TOOL_FILESYSTEM),
            java.util.Map.entry("writeFile", TOOL_FILESYSTEM),
            java.util.Map.entry("listFiles", TOOL_FILESYSTEM)
    );

    /** GET /api/skills/{name}/files — List all files in a skill folder with metadata and detected tool dependencies. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillFilesResponse.class)))
    public static void listFiles(String name) {
        var dir = resolveSkillName(SkillLoader.globalSkillsPath(), name);
        if (!Files.isDirectory(dir)) notFound();
        listSkillFilesFrom(dir);
    }

    /** GET /api/skills/{name}/files/{&lt;path&gt;filePath} — Read a text file from a skill folder. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillFileContentResponse.class)))
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
    private static java.util.List<SkillToolRef> resolveSkillTools(Path skillDir, String allContent) {
        var skillFile = skillDir.resolve(SKILL_MD);
        if (Files.exists(skillFile)) {
            var info = SkillLoader.parseSkillFile(skillFile);
            if (info != null && info.toolsDeclared()) {
                var result = new java.util.ArrayList<SkillToolRef>();
                for (var name : info.tools()) {
                    var tool = lookupToolByName(name);
                    result.add(new SkillToolRef(
                            name,
                            tool != null && tool.description() != null ? tool.description() : ""
                    ));
                }
                return result;
            }
        }
        // Legacy skill with no declaration — fall back to the body-text heuristic
        return detectTools(allContent);
    }

    private static java.util.List<SkillToolRef> detectTools(String content) {
        var detectedTools = new java.util.ArrayList<SkillToolRef>();
        var seen = new java.util.HashSet<String>();

        scanRegisteredToolNames(content, detectedTools, seen);
        scanToolAliases(content, detectedTools, seen);
        scanImplicitShellUsage(content, detectedTools, seen);

        return detectedTools;
    }

    /** Scan every live tool name from the registry against the body text. */
    private static void scanRegisteredToolNames(String content,
            java.util.List<SkillToolRef> detectedTools, java.util.Set<String> seen) {
        for (var tool : agents.ToolRegistry.listTools()) {
            if (content.contains(tool.name()) && seen.add(tool.name())) {
                detectedTools.add(new SkillToolRef(tool.name(),
                        tool.description() != null ? tool.description() : ""));
            }
        }
    }

    /** Informal aliases (readFile, shell, writeFile) → map to the canonical tool. */
    private static void scanToolAliases(String content,
            java.util.List<SkillToolRef> detectedTools, java.util.Set<String> seen) {
        for (var entry : TOOL_ALIASES.entrySet()) {
            if (content.contains(entry.getKey()) && seen.add(entry.getValue())) {
                var canonical = entry.getValue();
                var tool = lookupToolByName(canonical);
                detectedTools.add(new SkillToolRef(canonical,
                        tool != null && tool.description() != null ? tool.description() : ""));
            }
        }
    }

    /** Implicit shell usage — bash/sh code fences. */
    private static void scanImplicitShellUsage(String content,
            java.util.List<SkillToolRef> detectedTools, java.util.Set<String> seen) {
        if (!seen.add("exec")) return;
        if (!(content.contains("```bash") || content.contains("```sh")
                || content.contains("```shell") || content.contains("run the command")
                || content.contains("execute the command"))) {
            return;
        }
        var tool = lookupToolByName("exec");
        detectedTools.add(new SkillToolRef("exec",
                tool != null && tool.description() != null ? tool.description() : "Shell command execution"));
    }

    private static agents.ToolRegistry.Tool lookupToolByName(String name) {
        return agents.ToolRegistry.lookupTool(name);
    }

    private static boolean isTextFile(Path p) {
        return SkillLoader.isTextFile(p.getFileName().toString());
    }

    /** Format a list of scanner violations for user-facing error messages. */
    private static String formatViolations(java.util.List<services.SkillBinaryScanner.Violation> violations) {
        return services.SkillPromotionService.formatViolations(violations);
    }

    /** DELETE /api/skills/{name} — Delete a global skill. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillStatusResponse.class)))
    public static void delete(String name) {
        if ("skill-creator".equals(name)) {
            error(403, "The skill-creator skill is a built-in skill and cannot be deleted.");
        }
        var dir = resolveSkillName(SkillLoader.globalSkillsPath(), name);
        if (!Files.isDirectory(dir)) notFound();
        deleteSkillDir(dir);
    }

    /** GET /api/agents/{id}/skills — List workspace skills for an agent with enabled status. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AgentSkillView.class))))
    @ChatSafe(summary = "List an agent's installed skills and their enabled state")
    public static void listForAgent(Long id) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var agentDir = AgentService.workspacePath(agent.name).resolve(SKILLS_DIR);
        var skills = new java.util.ArrayList<SkillLoader.SkillInfo>();
        if (Files.isDirectory(agentDir)) {
            try (var dirs = Files.list(agentDir)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    var skillFile = dir.resolve(SKILL_MD);
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
            map.put(KEY_ENABLED, configMap.getOrDefault(s.name(), true));
            return map;
        }).toList();
        renderJSON(gson.toJson(result));
    }

    /** PUT /api/agents/{id}/skills/{name} — Enable or disable a skill for an agent.
     *  Toggle-only: the skill must already be installed in the agent's workspace
     *  (SKILL.md present under {@code workspace/<agent>/skills/<name>/}). Use
     *  {@code POST .../copy} first to install a global registry skill onto an
     *  agent that doesn't yet have it. */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SkillToggleRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillToggleResponse.class)))
    @ChatSafe(summary = "Enable or disable an already-installed skill on an agent", body = "enabled (bool)")
    public static void updateForAgent(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has(KEY_ENABLED)) badRequest();
        var enabled = body.get(KEY_ENABLED).getAsBoolean();

        // Guard against the orphan-config failure mode: a caller (LLM,
        // operator, script) calling PUT with enabled=true on a skill that
        // isn't installed in this agent's workspace yet. The endpoint
        // would silently create an AgentSkillConfig row that points at
        // no SKILL.md, SkillLoader wouldn't inject anything, and
        // AgentSkillAllowedTool rows would never be snapshotted — the
        // skill is dead on this agent despite enabled=true in the DB.
        // Only enforce on enable: turning a missing skill off is a no-op
        // we shouldn't reject (lets callers clean up stale configs).
        if (enabled) {
            var agentSkillDir = AgentService.workspacePath(agent.name)
                    .resolve(SKILLS_DIR).resolve(name);
            if (!Files.exists(agentSkillDir.resolve(SKILL_MD))) {
                response.status = 400;
                renderText(("Skill '%s' is not installed on agent '%s'. "
                        + "Install it first via POST /api/agents/%d/skills/%s/copy "
                        + "(copies the global skill into the agent's workspace, "
                        + "syncs the shell allowlist, and runs a malware scan); "
                        + "then this toggle endpoint can flip it on/off.")
                        .formatted(name, agent.name, id, name));
            }
        }

        var config = AgentSkillConfig.findByAgentAndSkill(agent, name);
        if (config == null) {
            config = new AgentSkillConfig();
            config.agent = agent;
            config.skillName = name;
        }
        config.enabled = enabled;
        config.save();

        renderJSON(gson.toJson(java.util.Map.of("name", name, KEY_ENABLED, enabled, KEY_STATUS, "ok")));
    }

    /** POST /api/agents/{id}/skills/{name}/copy — Copy a global skill into the agent's workspace. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillCopyResponse.class)))
    @ChatSafe(summary = "Install (copy) a global skill into an agent's workspace and enable it (use this to add a skill an agent lacks)")
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

        var agentSkillsDir = AgentService.workspacePath(agent.name).resolve(SKILLS_DIR);
        var targetDir = resolveSkillName(agentSkillsDir, name);
        var replacing = Files.isDirectory(targetDir) && Files.exists(targetDir.resolve(SKILL_MD));

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
                    KEY_STATUS, "ok",
                    "replaced", replacing
            )));
        } catch (IOException e) {
            // renderText (not error()) so the frontend's drag-and-drop banner
            // can surface the actual cause — Play's error() returns an HTML
            // error page that the JSON-fetch caller can't parse for messaging.
            response.status = 500;
            renderText("Failed to copy skill: " + e.getMessage());
        }
    }

    /** GET /api/agents/{id}/skills/{name}/files — List files in an agent workspace skill folder. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillFilesResponse.class)))
    public static void listAgentSkillFiles(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = resolveSkillName(AgentService.workspacePath(agent.name).resolve(SKILLS_DIR), name);
        if (!Files.isDirectory(dir)) notFound();
        listSkillFilesFrom(dir);
    }

    /** GET /api/agents/{id}/skills/{name}/files/{filePath} — Read a text file from an agent skill. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillFileContentResponse.class)))
    public static void readAgentSkillFile(Long id, String name, String filePath) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = resolveSkillName(AgentService.workspacePath(agent.name).resolve(SKILLS_DIR), name);
        readSkillFileFrom(dir, filePath);
    }

    /** DELETE /api/agents/{id}/skills/{name}/delete — Delete a skill from an agent's workspace. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillStatusResponse.class)))
    public static void deleteAgentSkill(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = resolveSkillName(AgentService.workspacePath(agent.name).resolve(SKILLS_DIR), name);
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
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SkillPromoteRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillPromoteResponse.class)))
    public static void promote() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("agentId") || !body.has(KEY_SKILL_NAME)) badRequest();

        var agentId = body.get("agentId").getAsLong();
        var skillName = body.get(KEY_SKILL_NAME).getAsString();

        Agent agent = Agent.findById(agentId);
        if (agent == null) notFound();

        var agentName = agent.name;
        var skillDir = AgentService.workspacePath(agentName).resolve(SKILLS_DIR).resolve(skillName);
        if (!Files.isDirectory(skillDir) || !Files.exists(skillDir.resolve(SKILL_MD))) {
            error(404, "Skill '%s' not found in agent workspace".formatted(skillName));
        }

        // Return immediately — run sanitization in the background. Pass the
        // requesting agent's id so the service can gate promotion on the
        // skill-creator capability (see SkillPromotionService.promoteInBackground).
        var requestingAgentId = agent.id;
        Thread.ofVirtual().name("skill-promote").start(() -> {
            try {
                services.Tx.run(() -> services.SkillPromotionService.promoteInBackground(
                        skillDir, skillName, requestingAgentId));
            } catch (Exception e) {
                play.Logger.error("Background promotion failed for '%s': %s",
                        skillName, e.getMessage());
            }
        });

        renderJSON(gson.toJson(java.util.Map.of(KEY_STATUS, "promoting", KEY_SKILL_NAME, skillName)));
    }

    /** PUT /api/skills/{name}/rename — Rename a global skill folder. */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SkillRenameRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillRenameResponse.class)))
    public static void rename(String name) {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has(KEY_NEW_NAME)) badRequest();

        var newName = body.get(KEY_NEW_NAME).getAsString().strip();
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
            renderJSON(gson.toJson(java.util.Map.of("oldName", name, KEY_NEW_NAME, newName, KEY_STATUS, "ok")));
        } catch (IOException e) {
            error(500, "Failed to rename skill: " + e.getMessage());
        }
    }

    // --- Shared helpers for global / agent-workspace skill operations ---

    /** Aggregated file metadata + concatenated text content from a skill dir walk. */
    private record SkillDirWalk(java.util.List<java.util.Map<String, Object>> files, String allTextContent) {}

    /** Frontmatter fields surfaced alongside the file list. */
    private record SkillMeta(java.util.List<String> commands, String author) {
        static SkillMeta empty() { return new SkillMeta(java.util.List.of(), ""); }
    }

    /** Walk a skill directory and render files + detected tools as JSON. */
    @SuppressWarnings("java:S2259")
    private static void listSkillFilesFrom(Path dir) {
        try {
            var walked = walkSkillDir(dir);
            var detectedTools = resolveSkillTools(dir, walked.allTextContent());
            var meta = readSkillMeta(dir);

            var result = new java.util.HashMap<String, Object>();
            result.put("files", walked.files());
            result.put("tools", detectedTools);
            result.put("commands", meta.commands());
            result.put("author", meta.author());
            renderJSON(gson.toJson(result));
        } catch (IOException e) {
            error(500, "Failed to list skill files: " + e.getMessage());
        }
    }

    private static SkillDirWalk walkSkillDir(Path dir) throws IOException {
        var files = new java.util.ArrayList<java.util.Map<String, Object>>();
        var allTextContent = new StringBuilder();
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .sorted()
                .forEach(p -> files.add(buildFileEntry(dir, p, allTextContent)));
        }
        return new SkillDirWalk(files, allTextContent.toString());
    }

    private static java.util.Map<String, Object> buildFileEntry(Path dir, Path p, StringBuilder allTextContent) {
        var rel = dir.relativize(p).toString();
        var map = new java.util.HashMap<String, Object>();
        map.put("path", rel);
        map.put("name", p.getFileName().toString());
        try { map.put("size", Files.size(p)); } catch (IOException _) { map.put("size", 0); }
        var text = isTextFile(p);
        map.put("isText", text);
        if (text) {
            try { allTextContent.append(Files.readString(p)).append("\n"); } catch (IOException _) { /* skip unreadable */ }
        }
        return map;
    }

    /**
     * Shell commands declared in the SKILL.md frontmatter — the set
     * this skill will contribute to an installing agent's allowlist.
     * Surfaced here so the detail page can render a "Commands" pill row.
     */
    private static SkillMeta readSkillMeta(Path dir) {
        var skillMd = dir.resolve(SKILL_MD);
        if (!Files.exists(skillMd)) return SkillMeta.empty();
        var info = SkillLoader.parseSkillFile(skillMd);
        if (info == null) return SkillMeta.empty();
        var commands = info.commands() != null ? info.commands() : java.util.List.<String>of();
        var author = info.author() != null ? info.author() : "";
        return new SkillMeta(commands, author);
    }

    /** Read a single file from a skill directory, with path-traversal protection. */
    @SuppressWarnings("java:S2259")
    private static void readSkillFileFrom(Path dir, String filePath) {
        Path target;
        try {
            target = AgentService.acquireContained(dir, filePath);
        } catch (SecurityException _) {
            error(403, "Path escapes skill directory");
            return;  // javac definite-assignment: target is unassigned on this catch path
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
    @SuppressWarnings("java:S2259")
    private static void deleteSkillDir(Path dir) {
        try {
            SkillPromotionService.deleteRecursive(dir);
            SkillLoader.clearCache();
            renderJSON(gson.toJson(java.util.Map.of(KEY_STATUS, "ok")));
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
    @SuppressWarnings("java:S2259")
    private static Path resolveSkillName(Path root, String name) {
        var resolved = AgentService.resolveContained(root, name);
        if (resolved == null) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }
        return resolved;
    }

    private static HashMap<String, Object> skillToMap(SkillLoader.SkillInfo s, boolean isGlobal) {
        var map = new HashMap<String, Object>();
        map.put("name", s.name());
        map.put(KEY_DESCRIPTION, s.description());
        map.put("isGlobal", isGlobal);
        map.put("location", s.location() != null ? s.location().toString() : "");
        map.put("tools", s.tools() != null ? s.tools() : List.of());
        map.put("commands", s.commands() != null ? s.commands() : List.of());
        map.put("author", s.author() != null ? s.author() : "");
        map.put("icon", s.icon() != null ? s.icon() : "");
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
