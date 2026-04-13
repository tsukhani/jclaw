package services;

import agents.SkillLoader;
import agents.ToolCatalog;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import models.Agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domain logic for promoting agent workspace skills to the global registry
 * and copying global skills into agent workspaces.
 *
 * <p>This service owns the multi-step workflows (hash comparison, version
 * checks, malware scanning, directory structure enforcement, LLM sanitization,
 * atomic staging) that were previously inlined in the controller.
 */
public class SkillPromotionService {

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

        var parts = new java.util.ArrayList<String>();
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
                        throw new RuntimeException("Failed to copy " + source, ex);
                    }
                });
            }

            atomicSwap(targetDir, stagingDir, backupDir, replacing);
            SkillLoader.clearCache();
        } catch (IOException | RuntimeException e) {
            if (Files.exists(stagingDir)) {
                try { deleteRecursive(stagingDir); } catch (IOException _) {}
            }
            throw e instanceof IOException ioe ? ioe : new IOException(e.getMessage(), e);
        }
    }

    // --- Promote: agent workspace skill → global registry ---

    /**
     * Run the full promotion pipeline in the current thread. This is designed
     * to be called from a virtual thread — the controller returns 202 immediately.
     */
    public static void promoteInBackground(Path skillDir, String skillName) {
        EventLogger.info("skills", "Starting background promotion of '%s'".formatted(skillName));

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
        var sourceBinaryFiles = new java.util.ArrayList<String>();
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

        var binaryFiles = new java.util.ArrayList<String>();
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
                if (Files.isDirectory(dir) && Files.list(dir).findAny().isEmpty()) {
                    Files.delete(dir);
                }
            }

            atomicSwap(targetDir, stagingDir, backupDir, replacingExisting);
            SkillLoader.clearCache();

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
            return new Gson().newBuilder().setPrettyPrinting().create().toJson(stripped);
        } catch (Exception _) {
            return "{}";
        }
    }

    public static String formatViolations(List<SkillBinaryScanner.Violation> violations) {
        return violations.stream()
                .map(SkillBinaryScanner.Violation::describe)
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static final int SANITIZE_BATCH_SIZE = 5;
    private static final int SANITIZE_TIMEOUT_SECONDS = 300;

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
        Agent mainAgent = Agent.findByName(Agent.MAIN_AGENT_NAME);
        if (mainAgent == null) {
            EventLogger.warn("skills", "Sanitization skipped: main agent not found");
            return fileContents;
        }

        var provider = ProviderRegistry.get(mainAgent.modelProvider);
        if (provider == null) {
            EventLogger.warn("skills", "Sanitization skipped: no provider for agent " + mainAgent.name);
            return fileContents;
        }

        EventLogger.info("skills", "Starting LLM sanitization of %d file(s) via %s / %s"
                .formatted(fileContents.size(), provider.config().name(), mainAgent.modelId));

        for (var entry : fileContents.entrySet()) {
            EventLogger.info("skills", "Sanitizing file: %s (%d chars)"
                    .formatted(entry.getKey(), entry.getValue().length()));
        }

        // Batch files to avoid overwhelming the model with a single massive payload
        var entries = new java.util.ArrayList<>(fileContents.entrySet());
        var result = new LinkedHashMap<String, String>();
        int batchCount = (entries.size() + SANITIZE_BATCH_SIZE - 1) / SANITIZE_BATCH_SIZE;

        for (int i = 0; i < entries.size(); i += SANITIZE_BATCH_SIZE) {
            var batch = entries.subList(i, Math.min(i + SANITIZE_BATCH_SIZE, entries.size()));
            int batchNum = (i / SANITIZE_BATCH_SIZE) + 1;

            EventLogger.info("skills", "Sending batch %d/%d (%d files)"
                    .formatted(batchNum, batchCount, batch.size()));

            var batchResult = sanitizeBatch(provider, mainAgent.modelId, batch, fileContents);
            result.putAll(batchResult);
        }

        EventLogger.info("skills", "Sanitization complete");
        return result;
    }

    private static LinkedHashMap<String, String> sanitizeBatch(
            llm.LlmProvider provider, String modelId,
            java.util.List<java.util.Map.Entry<String, String>> batch,
            LinkedHashMap<String, String> originals) {

        var sb = new StringBuilder();
        for (var entry : batch) {
            sb.append("=== FILE: %s ===\n".formatted(entry.getKey()));
            sb.append(entry.getValue());
            sb.append("\n\n");
        }

        var messages = java.util.List.of(
                ChatMessage.system(SANITIZE_SYSTEM_PROMPT),
                ChatMessage.user(sb.toString())
        );

        try {
            // No reasoning (null thinkingMode) — sanitization is classification, not reasoning.
            // Extended timeout — large payloads need more time to process.
            var response = provider.chat(modelId, messages, null, null, null, SANITIZE_TIMEOUT_SECONDS);
            var text = response.choices().get(0).message().content().toString().strip();

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
