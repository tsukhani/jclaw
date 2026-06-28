package controllers;

import agents.SkillLoader;
import agents.ToolRegistry;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.AgentSkillConfig;
import play.Logger;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.RegistrySkillImporter;
import services.SkillBinaryScanner;
import services.SkillPromotionService;
import services.catalog.CatalogPage;
import services.catalog.CatalogQuery;
import services.catalog.CatalogRegistry;
import services.Tx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public record SkillImportRequest(String source, String skillId) {}

    public record SkillPromoteResponse(String status, String skillName) {}

    public record SkillRenameRequest(String newName) {}

    public record SkillRenameResponse(String oldName, String newName, String status) {}

    /** GET /api/skills — List all global skills. */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SkillView.class))))
    @Operation(summary = "List all skills in the global registry")
    public static void list() {
        var skills = new ArrayList<SkillLoader.SkillInfo>();
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

    /**
     * GET /api/skills/catalog/search — Search the external importable-skills
     * catalog (the skills.sh / mastra-ai GitHub-scraped snapshot). The snapshot
     * is downloaded and indexed lazily on the first call (then disk-cached), so
     * the first search is slower than later ones. A blank {@code q} browses the
     * most-installed skills.
     */
    /** GET /api/skills/catalogs — List the configured catalogs for the selector. */
    @Operation(summary = "List the configured skill catalogs (static dumps + dynamic registries)")
    public static void catalogs() {
        var list = CatalogRegistry.all().stream()
                .map(c -> Map.of("id", c.id(), "displayName", c.displayName(),
                        "type", c.type().name().toLowerCase()))
                .toList();
        renderJSON(gson.toJson(list));
    }

    /**
     * GET /api/skills/catalog/search — Browse/search a specific catalog. {@code catalog}
     * selects the source (default = first/static). Static catalogs honor
     * {@code category}/{@code page}/{@code pageSize} (topical facets + paging);
     * dynamic catalogs honor {@code cursor} (live Next/Prev nav).
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CatalogPage.class)))
    @Operation(summary = "Browse or search a skill catalog")
    public static void catalogSearch(String catalog, String q, String category,
                                     Integer page, Integer pageSize, String cursor, String sort) {
        var c = CatalogRegistry.byId(catalog);
        var query = new CatalogQuery(q, category, page != null ? page : 0,
                pageSize != null ? pageSize : 20, cursor, sort);
        renderJSON(gson.toJson(c.query(query)));
    }

    /**
     * POST /api/skills/catalog/refresh — re-pull a static catalog's dump from its
     * update URL (drops the disk cache; the next browse re-downloads + re-indexes).
     * A dynamic catalog is always live, so it reports {@code refreshed=false}
     * (not applicable).
     */
    @Operation(summary = "Refresh a static dump catalog from its update URL")
    public static void catalogRefresh() {
        var body = JsonBodyReader.readJsonBody();
        var catalogId = body != null && body.has("catalog") ? body.get("catalog").getAsString() : null;
        var c = CatalogRegistry.byId(catalogId);
        var refreshed = c.refresh();
        renderJSON(gson.toJson(Map.of("catalog", c.id(),
                "type", c.type().name().toLowerCase(), "refreshed", refreshed)));
    }

    /**
     * POST /api/skills/catalog/import — Import a catalog skill from GitHub into
     * the global registry. The skill is conformed to the skill-creator contract
     * (tool-name mapping + frontmatter normalization), malware-scanned, and
     * secret-sanitized before it is written. Runs synchronously — the conformance
     * + sanitization LLM passes plus the GitHub fetch make this a multi-second call.
     */
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SkillImportRequest.class)))
    @Operation(summary = "Import a catalog skill from GitHub into the global registry")
    public static void catalogImport() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("source") || !body.has("skillId")) badRequest();

        var source = body.get("source").getAsString();
        var skillId = body.get("skillId").getAsString();

        var result = RegistrySkillImporter.importToGlobal(source, skillId);
        if (!result.ok()) {
            renderJSON(gson.toJson(Map.of(KEY_STATUS, "failed", "message", result.message())));
        }
        renderJSON(gson.toJson(Map.of(KEY_STATUS, "imported", KEY_SKILL_NAME, result.skillName())));
    }

    /** GET /api/skills/{name} — Get a global skill with full content. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillDetailView.class)))
    @Operation(summary = "Get a global skill with its full SKILL.md content")
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
    private static final Map<String, String> TOOL_ALIASES = Map.ofEntries(
            Map.entry("shell", "exec"),
            Map.entry("readFile", TOOL_FILESYSTEM),
            Map.entry("writeFile", TOOL_FILESYSTEM),
            Map.entry("listFiles", TOOL_FILESYSTEM)
    );

    /** GET /api/skills/{name}/files — List all files in a skill folder with metadata and detected tool dependencies. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillFilesResponse.class)))
    @Operation(summary = "List files in a global skill folder with metadata and detected tool dependencies")
    public static void listFiles(String name) {
        var dir = resolveSkillName(SkillLoader.globalSkillsPath(), name);
        if (!Files.isDirectory(dir)) notFound();
        listSkillFilesFrom(dir);
    }

    /** GET /api/skills/{name}/files/{&lt;path&gt;filePath} — Read a text file from a skill folder. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillFileContentResponse.class)))
    @Operation(summary = "Read a text file from a global skill folder")
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
    private static List<SkillToolRef> resolveSkillTools(Path skillDir, String allContent) {
        var skillFile = skillDir.resolve(SKILL_MD);
        if (Files.exists(skillFile)) {
            var info = SkillLoader.parseSkillFile(skillFile);
            if (info != null && info.toolsDeclared()) {
                var result = new ArrayList<SkillToolRef>();
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

    private static List<SkillToolRef> detectTools(String content) {
        var detectedTools = new ArrayList<SkillToolRef>();
        var seen = new HashSet<String>();

        scanRegisteredToolNames(content, detectedTools, seen);
        scanToolAliases(content, detectedTools, seen);
        scanImplicitShellUsage(content, detectedTools, seen);

        return detectedTools;
    }

    /** Scan every live tool name from the registry against the body text. */
    private static void scanRegisteredToolNames(String content,
            List<SkillToolRef> detectedTools, Set<String> seen) {
        for (var tool : ToolRegistry.listTools()) {
            if (content.contains(tool.name()) && seen.add(tool.name())) {
                detectedTools.add(new SkillToolRef(tool.name(),
                        tool.description() != null ? tool.description() : ""));
            }
        }
    }

    /** Informal aliases (readFile, shell, writeFile) → map to the canonical tool. */
    private static void scanToolAliases(String content,
            List<SkillToolRef> detectedTools, Set<String> seen) {
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
            List<SkillToolRef> detectedTools, Set<String> seen) {
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

    private static ToolRegistry.Tool lookupToolByName(String name) {
        return ToolRegistry.lookupTool(name);
    }

    private static boolean isTextFile(Path p) {
        return SkillLoader.isTextFile(p.getFileName().toString());
    }

    /** Format a list of scanner violations for user-facing error messages. */
    private static String formatViolations(List<SkillBinaryScanner.Violation> violations) {
        return SkillPromotionService.formatViolations(violations);
    }

    /** DELETE /api/skills/{name} — Delete a global skill. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillStatusResponse.class)))
    @Operation(summary = "Delete a global skill (rejects the built-in skill-creator)")
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
    @Operation(summary = "List an agent's installed skills and their enabled state")
    public static void listForAgent(Long id) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var agentDir = AgentService.workspacePath(agent.name).resolve(SKILLS_DIR);
        var skills = new ArrayList<SkillLoader.SkillInfo>();
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
    @Operation(summary = "Enable or disable an already-installed skill on an agent")
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

        renderJSON(gson.toJson(Map.of("name", name, KEY_ENABLED, enabled, KEY_STATUS, "ok")));
    }

    /** POST /api/agents/{id}/skills/{name}/copy — Copy a global skill into the agent's workspace. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillCopyResponse.class)))
    @Operation(summary = "Install (copy) a global skill into an agent's workspace and enable it (use this to add a skill an agent lacks)")
    public static void copyToAgent(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();

        var globalDir = resolveSkillName(SkillLoader.globalSkillsPath(), name);
        if (!Files.isDirectory(globalDir)) {
            error(404, "Global skill '%s' not found".formatted(name));
        }

        // Verify the agent has every tool this skill declares it needs
        var toolCheck = SkillPromotionService.validateToolRequirements(agent, name);
        if (!toolCheck.ok()) {
            response.status = 400;
            renderText(toolCheck.message());
        }

        // Malware scan before the copy touches the agent workspace
        var copyViolations = SkillBinaryScanner.scan(globalDir);
        if (!copyViolations.isEmpty()) {
            response.status = 400;
            renderText("Cannot add skill '%s' to agent '%s': malware detected — %s"
                    .formatted(name, agent.name, formatViolations(copyViolations)));
        }

        var agentSkillsDir = AgentService.workspacePath(agent.name).resolve(SKILLS_DIR);
        var targetDir = resolveSkillName(agentSkillsDir, name);
        var replacing = Files.isDirectory(targetDir) && Files.exists(targetDir.resolve(SKILL_MD));

        try {
            SkillPromotionService.copyToAgentWorkspace(agent, name);

            // Ensure skill is enabled for this agent
            var config = AgentSkillConfig.findByAgentAndSkill(agent, name);
            if (config != null && !config.enabled) {
                config.enabled = true;
                config.save();
            }

            renderJSON(gson.toJson(Map.of(
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
    @Operation(summary = "List files in an agent workspace skill folder with metadata and detected tool dependencies")
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
    @Operation(summary = "Read a text file from an agent workspace skill")
    public static void readAgentSkillFile(Long id, String name, String filePath) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = resolveSkillName(AgentService.workspacePath(agent.name).resolve(SKILLS_DIR), name);
        readSkillFileFrom(dir, filePath);
    }

    /** DELETE /api/agents/{id}/skills/{name}/delete — Delete a skill from an agent's workspace. */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillStatusResponse.class)))
    @Operation(summary = "Delete a skill from an agent's workspace and revoke its shell-allowlist grants")
    public static void deleteAgentSkill(Long id, String name) {
        Agent agent = Agent.findById(id);
        if (agent == null) notFound();
        var dir = resolveSkillName(AgentService.workspacePath(agent.name).resolve(SKILLS_DIR), name);
        if (!Files.isDirectory(dir)) notFound();
        // Revoke the skill's shell-allowlist grants for this agent BEFORE deleting
        // the workspace copy — if the filesystem delete fails halfway we'd rather
        // have a stale directory without grants than the inverse. The revoke is
        // idempotent, so a retry after a transient failure is safe.
        SkillPromotionService.revokeAgentAllowlist(agent, name);
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
    @Operation(summary = "Promote an agent workspace skill to the global registry (sanitizes asynchronously)")
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
                Tx.run(() -> SkillPromotionService.promoteInBackground(
                        skillDir, skillName, requestingAgentId));
            } catch (Exception e) {
                Logger.error("Background promotion failed for '%s': %s",
                        skillName, e.getMessage());
            }
        });

        renderJSON(gson.toJson(Map.of(KEY_STATUS, "promoting", KEY_SKILL_NAME, skillName)));
    }

    /** PUT /api/skills/{name}/rename — Rename a global skill folder. */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SkillRenameRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillRenameResponse.class)))
    @Operation(summary = "Rename a global skill folder")
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
            renderJSON(gson.toJson(Map.of("oldName", name, KEY_NEW_NAME, newName, KEY_STATUS, "ok")));
        } catch (IOException e) {
            error(500, "Failed to rename skill: " + e.getMessage());
        }
    }

    // --- Shared helpers for global / agent-workspace skill operations ---

    /** Aggregated file metadata + concatenated text content from a skill dir walk. */
    private record SkillDirWalk(List<Map<String, Object>> files, String allTextContent) {}

    /** Frontmatter fields surfaced alongside the file list. */
    private record SkillMeta(List<String> commands, String author) {
        static SkillMeta empty() { return new SkillMeta(List.of(), ""); }
    }

    /** Walk a skill directory and render files + detected tools as JSON. */
    @SuppressWarnings("java:S2259")
    private static void listSkillFilesFrom(Path dir) {
        try {
            var walked = walkSkillDir(dir);
            var detectedTools = resolveSkillTools(dir, walked.allTextContent());
            var meta = readSkillMeta(dir);

            var result = new HashMap<String, Object>();
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
        var files = new ArrayList<Map<String, Object>>();
        var allTextContent = new StringBuilder();
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .sorted()
                .forEach(p -> files.add(buildFileEntry(dir, p, allTextContent)));
        }
        return new SkillDirWalk(files, allTextContent.toString());
    }

    private static Map<String, Object> buildFileEntry(Path dir, Path p, StringBuilder allTextContent) {
        var rel = dir.relativize(p).toString();
        var map = new HashMap<String, Object>();
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
        var commands = info.commands() != null ? info.commands() : List.<String>of();
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
            renderJSON(gson.toJson(Map.of(
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
            renderJSON(gson.toJson(Map.of(KEY_STATUS, "ok")));
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
