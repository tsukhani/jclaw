package services;

import agents.SkillLoader;
import agents.ToolCatalog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.AgentSkillConfig;
import models.SkillRegistryTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.joining;

/**
 * Domain logic for promoting agent workspace skills to the global registry
 * and copying global skills into agent workspaces.
 *
 * <p>This service owns the multi-step workflows (hash comparison, version
 * checks, malware scanning, directory structure enforcement, LLM sanitization,
 * atomic staging) that were previously inlined in the controller.
 */
public class SkillPromotionService {

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private SkillPromotionService() {}

    // --- Result types ---

    /** Outcome of a copy or promote operation. */
    public sealed interface Result {
        record Ok(Map<String, Object> data) implements Result {}
        record Noop(String reason) implements Result {}
        record Failed(int statusCode, String message) implements Result {}
    }

    /** Outcome of tool validation for a skill. */
    public record ToolValidationResult(boolean ok, String message) {}

    // --- Copy: global skill → agent workspace ---

    /**
     * Validate that the agent has every tool this skill declares it needs.
     * Returns null if validation passes, or an error message if it fails.
     */
    public static ToolValidationResult validateToolRequirements(Agent agent, String skillName) {
        var globalDir = SkillLoader.globalSkillsPath().resolve(skillName);
        var skillFile = globalDir.resolve("SKILL.md");
        if (!Files.exists(skillFile)) return new ToolValidationResult(true, null);

        var info = SkillLoader.parseSkillFile(skillFile);
        if (info == null || info.tools() == null || info.tools().isEmpty()) {
            return new ToolValidationResult(true, null);
        }

        var validation = ToolCatalog.validateSkillTools(agent, info.tools());
        if (validation.isOk()) return new ToolValidationResult(true, null);

        var parts = new ArrayList<String>();
        if (!validation.disabled().isEmpty()) {
            parts.add("disabled: [" + String.join(", ", validation.disabled()) + "]");
        }
        if (!validation.unknown().isEmpty()) {
            parts.add("unknown: [" + String.join(", ", validation.unknown()) + "]");
        }
        return new ToolValidationResult(false,
                "Cannot add skill '%s' to agent '%s': missing tools — %s. Enable the required tools for this agent and try again."
                        .formatted(skillName, agent.name, String.join("; ", parts)));
    }

    /**
     * Copy a global skill into the agent's workspace using atomic staging.
     * Assumes tool validation and malware scanning have already passed.
     */
    public static void copyToAgentWorkspace(Agent agent, String skillName) throws IOException {
        var globalDir = SkillLoader.globalSkillsPath().resolve(skillName);
        var agentSkillsDir = AgentService.workspacePath(agent.name).resolve("skills");
        var targetDir = agentSkillsDir.resolve(skillName);
        var replacing = Files.isDirectory(targetDir) && Files.exists(targetDir.resolve("SKILL.md"));

        var stagingDir = agentSkillsDir.resolve(skillName + ".copying-" + System.currentTimeMillis());
        var backupDir = agentSkillsDir.resolve(skillName + ".replacing-" + System.currentTimeMillis());

        try {
            Files.createDirectories(stagingDir);
            try (var walk = Files.walk(globalDir)) {
                walk.forEach(source -> {
                    var staged = stagingDir.resolve(globalDir.relativize(source));
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(staged);
                        } else {
                            Files.createDirectories(staged.getParent());
                            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException ex) {
                        // Append the cause's message so the eventual log/banner
                        // shows the actual reason (Permission denied, file not
                        // found, target read-only filesystem, etc.) rather than
                        // just the source path. The cause itself is preserved
                        // for stack-trace diagnostics.
                        throw new java.io.UncheckedIOException(
                                "Failed to copy " + source.toAbsolutePath() + ": " + ex,
                                ex);
                    }
                });
            }

            atomicSwap(targetDir, stagingDir, backupDir, replacing);
            SkillLoader.clearCache();

            // ── Snapshot per-agent allowlist contribution from the registry ──
            // AgentSkillAllowedTool rows for this (agent, skill) are the canonical
            // allowlist source consulted by ShellExecTool at call time. They are
            // sourced from the registry *once* at install — subsequent registry
            // un-promotion does NOT retroactively revoke, and workspace SKILL.md
            // edits do NOT expand the set. Both properties are load-bearing for
            // the threat model.
            syncAgentAllowlistFromRegistry(agent, skillName);
        } catch (IOException | RuntimeException e) {
            if (Files.exists(stagingDir)) {
                try { deleteRecursive(stagingDir); } catch (IOException _) {}
            }
            throw e instanceof IOException ioe ? ioe : new IOException(e.getMessage(), e);
        }
    }

    /**
     * Mirror the registry's blessed command list for {@code skillName} into the
     * per-agent allowlist table. Idempotent: clears any prior rows for this
     * (agent, skill) pair, then inserts one row per registry-declared command.
     * Called at install time (both fresh and re-install) and by
     * {@link #revokeAgentAllowlist} via the skill-delete path.
     */
    public static void syncAgentAllowlistFromRegistry(Agent agent, String skillName) {
        AgentSkillAllowedTool.deleteByAgentAndSkill(agent, skillName);
        var blessed = SkillRegistryTool.findBySkill(skillName);
        for (var src : blessed) {
            var row = new AgentSkillAllowedTool();
            row.agent = agent;
            row.skillName = skillName;
            row.toolName = src.toolName;
            row.save();
        }
        EventLogger.info("skills",
                "Agent '%s' skill '%s' allowlist: %d command(s) snapshotted from registry"
                        .formatted(agent.name, skillName, blessed.size()));
    }

    /**
     * Remove all {@link AgentSkillAllowedTool} rows for {@code (agent, skillName)}.
     * Call from the skill-delete path so an agent removing a skill from its
     * workspace also loses the associated shell-allowlist grants.
     */
    public static void revokeAgentAllowlist(Agent agent, String skillName) {
        AgentSkillAllowedTool.deleteByAgentAndSkill(agent, skillName);
        EventLogger.info("skills",
                "Agent '%s' skill '%s' allowlist revoked".formatted(agent.name, skillName));
    }

    // --- Promote: agent workspace skill → global registry ---

    /**
     * Skill name used as the promotion capability gate. Only agents with this
     * skill installed and enabled may promote workspace skills to the global
     * registry. The bootstrap seed in {@code DefaultConfigJob} ensures the
     * main agent has this skill enabled at first boot so the delegation graph
     * has a root.
     */
    public static final String SKILL_CREATOR_NAME = "skill-creator";

    /**
     * True when {@code agent} may promote: skill-creator must be present in the
     * agent's workspace AND not explicitly disabled via AgentSkillConfig.
     * Absence of an AgentSkillConfig row is treated as "enabled by default,"
     * matching {@link agents.SkillLoader}'s existing semantics.
     */
    public static boolean hasSkillCreatorCapability(Agent agent) {
        if (agent == null) return false;
        var workspaceSkillMd = services.AgentService.workspacePath(agent.name)
                .resolve("skills").resolve(SKILL_CREATOR_NAME).resolve("SKILL.md");
        if (!Files.exists(workspaceSkillMd)) return false;
        var cfg = AgentSkillConfig.findByAgentAndSkill(agent, SKILL_CREATOR_NAME);
        return cfg == null || cfg.enabled;
    }

    /**
     * Run the full promotion pipeline in the current thread. This is designed
     * to be called from a virtual thread — the controller returns 202 immediately.
     *
     * <p>The {@code agentId} parameter is the agent that requested this promotion.
     * Promotion is gated on that agent having {@code skill-creator} installed
     * and enabled; requests from agents without it are rejected before any
     * filesystem or network work happens. The agent's name is also attached to
     * failure notifications so operators can tell which agent tried what.
     */
    public static void promoteInBackground(Path skillDir, String skillName, Long agentId) {
        EventLogger.info("skills", "Starting background promotion of '%s'".formatted(skillName));

        // ── Capability gate: only agents with skill-creator installed + enabled may promote ──
        // Note: Agent.findById() inherits from play.db.jpa.Model and returns JPABase;
        // explicit type is required so downstream callers see the Agent API.
        Agent agent = Agent.findById(agentId);
        if (agent == null) {
            EventLogger.warn("skills", "Promotion of '%s' refused: requesting agent id=%d not found"
                    .formatted(skillName, agentId));
            NotificationBus.publish("skill.promote_failed", Map.of(
                    "skillName", skillName,
                    "error", "Requesting agent not found."
            ));
            return;
        }
        if (!hasSkillCreatorCapability(agent)) {
            EventLogger.warn("skills",
                    "Promotion of '%s' refused: agent '%s' does not have the skill-creator capability"
                            .formatted(skillName, agent.name));
            NotificationBus.publish("skill.promote_failed", Map.of(
                    "skillName", skillName,
                    "error", "Agent '" + agent.name + "' lacks the skill-creator capability. "
                            + "Install the skill-creator skill in this agent's workspace first."
            ));
            return;
        }

        // ── Hash-based noop check ──
        var globalDirCheck = SkillLoader.globalSkillsPath().resolve(skillName);
        if (Files.isDirectory(globalDirCheck) && Files.exists(globalDirCheck.resolve("SKILL.md"))) {
            if (isIdenticalToGlobal(skillDir, globalDirCheck, skillName)) return;
            if (isDowngrade(skillDir, globalDirCheck, skillName)) return;
        }

        // ── Malware scan ──
        var violations = SkillBinaryScanner.scan(skillDir);
        if (!violations.isEmpty()) {
            EventLogger.warn("skills", "Promotion of '%s' refused: malware detected in %d file(s)"
                    .formatted(skillName, violations.size()));
            NotificationBus.publish("skill.promote_failed", Map.of(
                    "skillName", skillName,
                    "error", "Malware detected — " + formatViolations(violations)
                            + ". Remove the flagged file(s) and try again."
            ));
            return;
        }

        // ── Read source files ──
        var sourceTextFiles = new LinkedHashMap<String, String>();
        var sourceBinaryFiles = new ArrayList<String>();
        try (var walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                var relName = skillDir.relativize(file).toString();
                try {
                    if (SkillLoader.isTextFile(relName)) {
                        sourceTextFiles.put(relName, Files.readString(file));
                    } else {
                        sourceBinaryFiles.add(relName);
                    }
                } catch (IOException _) {}
            });
        } catch (IOException e) {
            EventLogger.error("skills", "Failed to read skill files: " + e.getMessage());
            return;
        }

        // ── Enforce directory structure ──
        var textFiles = new LinkedHashMap<String, String>();
        for (var entry : sourceTextFiles.entrySet()) {
            textFiles.put(enforceTextFilePath(entry.getKey()), entry.getValue());
        }

        var binaryFiles = new ArrayList<String>();
        for (var binFile : sourceBinaryFiles) {
            if (binFile.startsWith("tools/")) {
                binaryFiles.add(binFile);
            } else {
                var fileName = binFile.contains("/") ? binFile.substring(binFile.lastIndexOf('/') + 1) : binFile;
                binaryFiles.add("tools/" + fileName);
                EventLogger.info("skills", "Relocated binary '%s' → 'tools/%s'".formatted(binFile, fileName));
            }
        }

        // ── Strip credentials ──
        for (var key : textFiles.keySet().stream().toList()) {
            if (key.startsWith("credentials/")) {
                textFiles.put(key, stripCredentialsJson(textFiles.get(key)));
            }
        }

        // ── Preserve SKILL.md frontmatter ──
        SkillLoader.FrontmatterSplit originalSplit = null;
        if (textFiles.containsKey("SKILL.md")) {
            originalSplit = SkillLoader.splitFrontmatter(textFiles.get("SKILL.md"));
            if (originalSplit.frontmatter() != null) {
                textFiles.put("SKILL.md", originalSplit.body() != null ? originalSplit.body() : "");
            }
        }

        // ── LLM sanitization ──
        var sanitized = sanitizeWithLlm(textFiles);

        // ── Reinject frontmatter ──
        if (originalSplit != null && originalSplit.frontmatter() != null && sanitized.containsKey("SKILL.md")) {
            var sanitizedSplit = SkillLoader.splitFrontmatter(sanitized.get("SKILL.md"));
            var sanitizedBody = sanitizedSplit.frontmatter() != null ? sanitizedSplit.body() : sanitized.get("SKILL.md");
            if (sanitizedBody == null) sanitizedBody = "";
            sanitized.put("SKILL.md", originalSplit.frontmatter() + sanitizedBody);
        }

        // ── Atomic write to global registry ──
        writeToGlobalRegistry(skillDir, skillName, sanitized, binaryFiles);
    }

    // --- Internal helpers ---

    private static boolean isIdenticalToGlobal(Path skillDir, Path globalDir, String skillName) {
        try {
            var workspaceHash = SkillLoader.hashSkillDirectory(skillDir);
            var globalHash = SkillLoader.hashSkillDirectory(globalDir);
            var wsInfo = SkillLoader.parseSkillFile(skillDir.resolve("SKILL.md"));
            var globalInfo = SkillLoader.parseSkillFile(globalDir.resolve("SKILL.md"));
            EventLogger.info("skills",
                    "Promote hash-check '%s': workspace[v=%s, hash=%s] global[v=%s, hash=%s] equal=%s"
                            .formatted(skillName,
                                    wsInfo != null ? wsInfo.version() : "?",
                                    workspaceHash.isEmpty() ? "EMPTY" : workspaceHash.substring(0, 12),
                                    globalInfo != null ? globalInfo.version() : "?",
                                    globalHash.isEmpty() ? "EMPTY" : globalHash.substring(0, 12),
                                    workspaceHash.equals(globalHash)));
            if (workspaceHash.equals(globalHash) && !workspaceHash.isEmpty()) {
                EventLogger.info("skills", "Promotion of '%s' skipped: workspace copy is identical to global".formatted(skillName));
                NotificationBus.publish("skill.promote_noop", Map.of(
                        "skillName", skillName,
                        "reason", "Workspace copy is identical to the global skill — nothing to promote."
                ));
                return true;
            }
        } catch (IOException e) {
            EventLogger.warn("skills", "Hash check failed for '%s': %s".formatted(skillName, e.getMessage()));
        }
        return false;
    }

    private static boolean isDowngrade(Path skillDir, Path globalDir, String skillName) {
        var workspaceInfo = SkillLoader.parseSkillFile(skillDir.resolve("SKILL.md"));
        var globalInfo = SkillLoader.parseSkillFile(globalDir.resolve("SKILL.md"));
        if (workspaceInfo != null && globalInfo != null) {
            int cmp = SkillLoader.compareVersions(workspaceInfo.version(), globalInfo.version());
            if (cmp < 0) {
                EventLogger.warn("skills", "Promotion of '%s' refused: workspace version %s < global version %s"
                        .formatted(skillName, workspaceInfo.version(), globalInfo.version()));
                NotificationBus.publish("skill.promote_failed", Map.of(
                        "skillName", skillName,
                        "error", "Workspace version " + workspaceInfo.version()
                                + " is older than the global version " + globalInfo.version()
                                + ". Update the agent's copy from the global skill (it will overwrite the workspace copy) before promoting."
                ));
                return true;
            }
        }
        return false;
    }

    private static void writeToGlobalRegistry(Path skillDir, String skillName,
                                               LinkedHashMap<String, String> sanitized,
                                               List<String> binaryFiles) {
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
            for (var sourceName : binaryFiles) {
                var source = skillDir.resolve(sourceName);
                if (!Files.exists(source)) {
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
                    Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            // Remove empty subdirectories from staging
            for (var subDir : List.of("credentials", "tools")) {
                var dir = stagingDir.resolve(subDir);
                if (Files.isDirectory(dir)) {
                    boolean empty;
                    try (var entries = Files.list(dir)) {
                        empty = entries.findAny().isEmpty();
                    }
                    if (empty) {
                        Files.delete(dir);
                    }
                }
            }

            atomicSwap(targetDir, stagingDir, backupDir, replacingExisting);
            SkillLoader.clearCache();

            // ── Update registry allowlist blessings ──
            // Rewrite SkillRegistryTool rows from the just-promoted SKILL.md's
            // {@code commands:} frontmatter list. Idempotent: clear prior rows
            // for this skill, insert new ones. Un-promotion (future delete
            // endpoint) must mirror the delete.
            syncRegistryToolRows(skillName, targetDir);

            var action = replacingExisting ? "replaced" : "created";
            EventLogger.info("skills", "Promotion of '%s' completed (%s)".formatted(skillName, action));
            NotificationBus.publish("skill.promoted", Map.of(
                    "skillName", skillName,
                    "folderName", skillName,
                    "replaced", replacingExisting
            ));
        } catch (IOException e) {
            if (Files.exists(stagingDir)) {
                try { deleteRecursive(stagingDir); } catch (IOException _) {}
            }
            EventLogger.error("skills", "Failed to write promoted skill: " + e.getMessage());
            NotificationBus.publish("skill.promote_failed", Map.of(
                    "skillName", skillName,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Refresh {@link SkillRegistryTool} rows for this skill from the on-disk
     * SKILL.md at {@code globalSkillDir}. Clears existing rows for the skill
     * and inserts one row per entry in the frontmatter {@code commands:} list.
     * Missing or empty {@code commands:} frontmatter produces zero rows
     * (skill declares no shell-allowlist contribution — the legitimate case
     * for skills that only ship prompt instructions).
     */
    private static void syncRegistryToolRows(String skillName, Path globalSkillDir) {
        var skillMd = globalSkillDir.resolve("SKILL.md");
        var info = Files.exists(skillMd) ? SkillLoader.parseSkillFile(skillMd) : null;
        var declared = (info != null && info.commands() != null) ? info.commands() : List.<String>of();

        SkillRegistryTool.deleteBySkill(skillName);
        for (var cmd : declared) {
            if (cmd == null || cmd.isBlank()) continue;
            var row = new SkillRegistryTool();
            row.skillName = skillName;
            row.toolName = cmd.strip();
            row.save();
        }
        EventLogger.info("skills",
                "Registry allowlist for '%s': %d command(s) declared".formatted(skillName, declared.size()));
    }

    /**
     * Atomically swap a staging directory into the target location, backing up
     * any existing target first. On failure, the backup is restored.
     */
    public static void atomicSwap(Path targetDir, Path stagingDir, Path backupDir,
                            boolean replacing) throws IOException {
        if (replacing) {
            Files.move(targetDir, backupDir);
        }
        try {
            Files.move(stagingDir, targetDir);
        } catch (IOException swapEx) {
            if (replacing && Files.isDirectory(backupDir)) {
                try { Files.move(backupDir, targetDir); } catch (IOException _) {}
            }
            throw swapEx;
        }
        if (replacing && Files.isDirectory(backupDir)) {
            deleteRecursive(backupDir);
        }
    }

    public static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        }
    }

    private static final Set<String> CREDENTIAL_EXTENSIONS = Set.of(
            ".json", ".txt", ".env", ".yaml", ".yml", ".properties"
    );

    public static String enforceTextFilePath(String path) {
        if (path.startsWith("tools/") || path.startsWith("credentials/")) return path;
        if (path.equals("SKILL.md")) return path;
        var lower = path.toLowerCase();
        for (var ext : CREDENTIAL_EXTENSIONS) {
            if (lower.endsWith(ext)) return "credentials/" + path;
        }
        return path;
    }

    public static String stripCredentialsJson(String content) {
        try {
            var json = JsonParser.parseString(content).getAsJsonObject();
            var stripped = new com.google.gson.JsonObject();
            for (var entry : json.entrySet()) {
                stripped.addProperty(entry.getKey(), "[CREDENTIAL]");
            }
            return PRETTY_GSON.toJson(stripped);
        } catch (Exception _) {
            return "{}";
        }
    }

    public static String formatViolations(List<SkillBinaryScanner.Violation> violations) {
        return violations.stream()
                .map(SkillBinaryScanner.Violation::describe)
                .collect(joining("; "));
    }

    private static final int DEFAULT_BATCH_KB = 100;
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private static final String SANITIZE_SYSTEM_PROMPT = """
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

    static LinkedHashMap<String, String> sanitizeWithLlm(LinkedHashMap<String, String> fileContents) {
        // Resolve provider and model — prefer dedicated sanitization config, fall back to main agent
        var configProvider = ConfigService.get("skillsPromotion.provider");
        var configModel = ConfigService.get("skillsPromotion.model");
        int batchKb = ConfigService.getInt("skillsPromotion.batchSizeKb", DEFAULT_BATCH_KB);
        int timeoutSeconds = ConfigService.getInt("skillsPromotion.timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);

        LlmProvider provider = null;
        String modelId = null;

        if (configProvider != null && !configProvider.isBlank()) {
            provider = ProviderRegistry.get(configProvider);
            modelId = configModel;
        }

        // Fall back to main agent's provider/model if not explicitly configured
        if (provider == null || modelId == null || modelId.isBlank()) {
            Agent mainAgent = Agent.findByName(Agent.MAIN_AGENT_NAME);
            if (mainAgent == null) {
                EventLogger.warn("skills", "Sanitization skipped: main agent not found");
                return fileContents;
            }
            if (provider == null) provider = ProviderRegistry.get(mainAgent.modelProvider);
            if (modelId == null || modelId.isBlank()) modelId = mainAgent.modelId;
        }

        if (provider == null) {
            EventLogger.warn("skills", "Sanitization skipped: no provider configured");
            return fileContents;
        }

        EventLogger.info("skills", "Starting LLM sanitization of %d file(s) via %s / %s (batch=%dKB, timeout=%ds)"
                .formatted(fileContents.size(), provider.config().name(), modelId, batchKb, timeoutSeconds));

        for (var entry : fileContents.entrySet()) {
            EventLogger.info("skills", "Sanitizing file: %s (%d chars)"
                    .formatted(entry.getKey(), entry.getValue().length()));
        }

        // Batch files by KB to avoid overwhelming the model with a single massive payload
        var batches = buildBatchesBySize(fileContents, batchKb * 1024);
        var result = new LinkedHashMap<String, String>();

        for (int i = 0; i < batches.size(); i++) {
            EventLogger.info("skills", "Sending batch %d/%d (%d files)"
                    .formatted(i + 1, batches.size(), batches.get(i).size()));

            var batchResult = sanitizeBatch(provider, modelId, batches.get(i), fileContents, timeoutSeconds);
            result.putAll(batchResult);
        }

        EventLogger.info("skills", "Sanitization complete");
        return result;
    }

    private static List<List<Map.Entry<String, String>>> buildBatchesBySize(
            LinkedHashMap<String, String> fileContents, int maxBatchBytes) {

        var batches = new ArrayList<List<Map.Entry<String, String>>>();
        var currentBatch = new ArrayList<Map.Entry<String, String>>();
        int currentSize = 0;

        for (var entry : fileContents.entrySet()) {
            int entrySize = entry.getValue().length() * 2; // rough byte estimate (UTF-16 chars → bytes)
            // If this single file exceeds the limit, send it alone
            if (!currentBatch.isEmpty() && currentSize + entrySize > maxBatchBytes) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentSize = 0;
            }
            currentBatch.add(entry);
            currentSize += entrySize;
        }
        if (!currentBatch.isEmpty()) batches.add(currentBatch);
        return batches;
    }

    private static LinkedHashMap<String, String> sanitizeBatch(
            LlmProvider provider, String modelId,
            List<Map.Entry<String, String>> batch,
            LinkedHashMap<String, String> originals, int timeoutSeconds) {

        var sb = new StringBuilder();
        for (var entry : batch) {
            sb.append("=== FILE: %s ===\n".formatted(entry.getKey()));
            sb.append(entry.getValue());
            sb.append("\n\n");
        }

        var messages = List.of(
                ChatMessage.system(SANITIZE_SYSTEM_PROMPT),
                ChatMessage.user(sb.toString())
        );

        try {
            // No reasoning (null thinkingMode) — sanitization is classification, not reasoning.
            // Skill promotion is a programmatic operation with no chat-channel
            // context; dispatcher_wait records under "unknown".
            var response = provider.chat(modelId, messages, null, null, null, timeoutSeconds, null);
            var text = response.choices().getFirst().message().content().toString().strip();

            EventLogger.info("skills",
                    "LLM sanitization batch response (%d chars)".formatted(text.length()));

            if (text.startsWith("```")) {
                text = text.replaceFirst("^```(?:json)?\\s*\\n?", "").replaceFirst("\\n?```$", "").strip();
            }

            var json = JsonParser.parseString(text).getAsJsonObject();
            var result = new LinkedHashMap<String, String>();
            for (var entry : batch) {
                if (json.has(entry.getKey())) {
                    var sanitized = json.get(entry.getKey()).getAsString();
                    var changed = !sanitized.equals(entry.getValue());
                    result.put(entry.getKey(), sanitized);
                    EventLogger.info("skills", "  %s: %s"
                            .formatted(entry.getKey(), changed ? "REDACTED (content changed)" : "clean (no changes)"));
                } else {
                    result.put(entry.getKey(), originals.get(entry.getKey()));
                    EventLogger.info("skills", "  %s: not in LLM response, kept original"
                            .formatted(entry.getKey()));
                }
            }
            return result;
        } catch (Exception e) {
            EventLogger.warn("skills", "LLM sanitization batch failed, using originals: " + e.getMessage());
            var result = new LinkedHashMap<String, String>();
            for (var entry : batch) {
                result.put(entry.getKey(), originals.get(entry.getKey()));
            }
            return result;
        }
    }
}
