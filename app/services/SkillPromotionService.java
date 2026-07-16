package services;

import agents.SkillLoader;
import agents.ToolCatalog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.AgentSkillConfig;
import models.SkillRegistryTool;
import play.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    // Canonical file/path constants for the skill folder layout.
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String SKILLS_DIR = "skills";
    private static final String TOOLS_DIR_PREFIX = "tools/";
    private static final String CREDENTIALS_DIR_PREFIX = "credentials/";

    // Event-log category for all skill-related entries emitted by this service.
    private static final String EVENT_CATEGORY_SKILLS = "skills";

    // Notification keys used when publishing promotion failures.
    private static final String NOTIFICATION_PROMOTE_FAILED = "skill.promote_failed";
    private static final String KEY_SKILL_NAME = "skillName";
    private static final String KEY_ERROR = "error";

    private SkillPromotionService() {}

    /**
     * Outcome of tool validation for a skill.
     *
     * @param ok      true when every tool the skill declares is enabled on
     *                the target agent
     * @param message human-readable explanation; empty when {@code ok} is
     *                true, otherwise names the missing tool(s)
     */
    public record ToolValidationResult(boolean ok, String message) {}

    // --- Copy: global skill → agent workspace ---

    /**
     * Validate that the agent has every tool this skill declares it needs.
     * Always returns a {@link ToolValidationResult}: {@code ok()} is true with a
     * null message on success; false with a message naming the missing tool(s)
     * on failure.
     */
    public static ToolValidationResult validateToolRequirements(Agent agent, String skillName) {
        var globalDir = SkillLoader.globalSkillsPath().resolve(skillName);
        var skillFile = globalDir.resolve(SKILL_FILE_NAME);
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
        var agentSkillsDir = AgentService.workspacePath(agent.name).resolve(SKILLS_DIR);
        var targetDir = agentSkillsDir.resolve(skillName);
        var replacing = Files.isDirectory(targetDir) && Files.exists(targetDir.resolve(SKILL_FILE_NAME));

        var stagingDir = agentSkillsDir.resolve(skillName + ".copying-" + System.currentTimeMillis());
        var backupDir = agentSkillsDir.resolve(skillName + ".replacing-" + System.currentTimeMillis());

        AtomicDirSwap.stageAndSwap(targetDir, stagingDir, backupDir, replacing, staging -> {
            try (var walk = Files.walk(globalDir)) {
                walk.forEach(source -> {
                    var staged = staging.resolve(globalDir.relativize(source));
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
                        throw new UncheckedIOException(
                                "Failed to copy " + source.toAbsolutePath() + ": " + ex,
                                ex);
                    }
                });
            }
        });
        // Refresh the skill cache now the swap is visible — used to live inside the
        // (now generic) stageAndSwap; the orchestrator owns this policy post-swap.
        SkillLoader.clearCache();

        // ── Snapshot per-agent allowlist contribution from the registry ──
        // AgentSkillAllowedTool rows for this (agent, skill) are the canonical
        // allowlist source consulted by ShellExecTool at call time. They are
        // sourced from the registry *once* at install — subsequent registry
        // un-promotion does NOT retroactively revoke, and workspace SKILL.md
        // edits do NOT expand the set. Both properties are load-bearing for
        // the threat model. Runs after the swap, so a sync failure leaves the
        // installed skill in place (staging is already gone).
        syncAgentAllowlistFromRegistry(agent, skillName);
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
        EventLogger.info(EVENT_CATEGORY_SKILLS,
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
        EventLogger.info(EVENT_CATEGORY_SKILLS,
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
        var workspaceSkillMd = AgentService.workspacePath(agent.name)
                .resolve(SKILLS_DIR).resolve(SKILL_CREATOR_NAME).resolve(SKILL_FILE_NAME);
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
        EventLogger.info(EVENT_CATEGORY_SKILLS, "Starting background promotion of '%s'".formatted(skillName));

        // ── Capability gate: only agents with skill-creator installed + enabled may promote ──
        if (!checkCapabilityGate(skillName, agentId)) return;

        // ── Hash-based noop check ──
        if (skipForGlobalCheck(skillDir, skillName)) return;

        publishToGlobal(skillDir, skillName);
    }

    /**
     * Shared publish core behind both agent promotion and registry import:
     * malware scan → read → enforce structure → strip credentials → LLM-sanitize
     * → atomic write to the global registry. The caller owns any source-specific
     * gating beforehand — promotion's capability + hash-noop checks, or the
     * registry importer's conformance pass (which has already rewritten SKILL.md
     * to the skill-creator contract by the time we get here). Returns true on a
     * completed write, false when a gate (malware scan, unreadable files) refused;
     * the refusal is already logged + notified.
     */
    public static boolean publishToGlobal(Path skillDir, String skillName) {
        // ── Malware scan ──
        if (!checkMalwareScan(skillDir, skillName)) return false;

        // ── Read source files ──
        var sourceTextFiles = new LinkedHashMap<String, String>();
        var sourceBinaryFiles = new ArrayList<String>();
        if (!readSourceFiles(skillDir, sourceTextFiles, sourceBinaryFiles)) return false;

        // ── Enforce directory structure ──
        var textFiles = enforceTextFileStructure(sourceTextFiles);
        var binaryFiles = enforceBinaryFileStructure(sourceBinaryFiles);

        // ── Strip credentials ──
        stripCredentialFiles(textFiles);

        // ── Preserve SKILL.md frontmatter ──
        SkillLoader.FrontmatterSplit originalSplit = stashFrontmatter(textFiles);

        // ── LLM sanitization ──
        var sanitized = sanitizeWithLlm(textFiles);

        // ── Reinject frontmatter ──
        reinjectFrontmatter(originalSplit, sanitized);

        // ── Atomic write to global registry ──
        writeToGlobalRegistry(skillDir, skillName, sanitized, binaryFiles);
        return true;
    }

    /**
     * Validate the requesting agent exists and has skill-creator installed.
     * Returns true when promotion may proceed, false when refused (with the
     * appropriate event-log + notification already emitted).
     */
    private static boolean checkCapabilityGate(String skillName, Long agentId) {
        // Note: Agent.findById() inherits from play.db.jpa.Model and returns JPABase;
        // explicit type is required so downstream callers see the Agent API.
        Agent agent = Agent.findById(agentId);
        if (agent == null) {
            EventLogger.warn(EVENT_CATEGORY_SKILLS, "Promotion of '%s' refused: requesting agent id=%d not found"
                    .formatted(skillName, agentId));
            NotificationBus.publish(NOTIFICATION_PROMOTE_FAILED, Map.of(
                    KEY_SKILL_NAME, skillName,
                    KEY_ERROR, "Requesting agent not found."
            ));
            return false;
        }
        if (!hasSkillCreatorCapability(agent)) {
            EventLogger.warn(EVENT_CATEGORY_SKILLS,
                    "Promotion of '%s' refused: agent '%s' does not have the skill-creator capability"
                            .formatted(skillName, agent.name));
            NotificationBus.publish(NOTIFICATION_PROMOTE_FAILED, Map.of(
                    KEY_SKILL_NAME, skillName,
                    KEY_ERROR, "Agent '" + agent.name + "' lacks the skill-creator capability. "
                            + "Install the skill-creator skill in this agent's workspace first."
            ));
            return false;
        }
        return true;
    }

    /** Returns true when promotion should short-circuit (identical or downgrade). */
    private static boolean skipForGlobalCheck(Path skillDir, String skillName) {
        var globalDirCheck = SkillLoader.globalSkillsPath().resolve(skillName);
        if (!Files.isDirectory(globalDirCheck) || !Files.exists(globalDirCheck.resolve(SKILL_FILE_NAME))) {
            return false;
        }
        return isIdenticalToGlobal(skillDir, globalDirCheck, skillName)
                || isDowngrade(skillDir, globalDirCheck, skillName);
    }

    /** Returns true when no malware was found; false (with notification emitted) when violations exist. */
    private static boolean checkMalwareScan(Path skillDir, String skillName) {
        var violations = SkillBinaryScanner.scan(skillDir);
        if (violations.isEmpty()) return true;
        EventLogger.warn(EVENT_CATEGORY_SKILLS, "Promotion of '%s' refused: malware detected in %d file(s)"
                .formatted(skillName, violations.size()));
        NotificationBus.publish(NOTIFICATION_PROMOTE_FAILED, Map.of(
                KEY_SKILL_NAME, skillName,
                KEY_ERROR, "Malware detected — " + formatViolations(violations)
                        + ". Remove the flagged file(s) and try again."
        ));
        return false;
    }

    /** Returns false on IO error (with event-log emitted). */
    private static boolean readSourceFiles(Path skillDir,
                                            LinkedHashMap<String, String> textFiles,
                                            ArrayList<String> binaryFiles) {
        try (var walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                var relName = skillDir.relativize(file).toString();
                try {
                    if (SkillLoader.isTextFile(relName)) {
                        textFiles.put(relName, Files.readString(file));
                    } else {
                        binaryFiles.add(relName);
                    }
                } catch (IOException e) {
                    // Don't silently drop a file the promotion is meant to carry:
                    // an unreadable source is a real gap the operator should see.
                    Logger.warn("Skipping unreadable skill file '%s' during promotion: %s",
                            relName, e.getMessage());
                }
            });
            return true;
        } catch (IOException e) {
            EventLogger.error(EVENT_CATEGORY_SKILLS, "Failed to read skill files: " + e.getMessage());
            return false;
        }
    }

    private static LinkedHashMap<String, String> enforceTextFileStructure(
            LinkedHashMap<String, String> sourceTextFiles) {
        var textFiles = new LinkedHashMap<String, String>();
        for (var entry : sourceTextFiles.entrySet()) {
            textFiles.put(enforceTextFilePath(entry.getKey()), entry.getValue());
        }
        return textFiles;
    }

    private static ArrayList<String> enforceBinaryFileStructure(ArrayList<String> sourceBinaryFiles) {
        var binaryFiles = new ArrayList<String>();
        for (var binFile : sourceBinaryFiles) {
            if (binFile.startsWith(TOOLS_DIR_PREFIX)) {
                binaryFiles.add(binFile);
            } else {
                var fileName = baseName(binFile);
                binaryFiles.add(TOOLS_DIR_PREFIX + fileName);
                EventLogger.info(EVENT_CATEGORY_SKILLS, "Relocated binary '%s' → 'tools/%s'".formatted(binFile, fileName));
            }
        }
        return binaryFiles;
    }

    private static void stripCredentialFiles(LinkedHashMap<String, String> textFiles) {
        for (var key : textFiles.keySet().stream().toList()) {
            if (key.startsWith(CREDENTIALS_DIR_PREFIX)) {
                textFiles.put(key, stripCredentialsJson(textFiles.get(key)));
            }
        }
    }

    /**
     * Pull SKILL.md frontmatter out of the text payload and replace the entry
     * with the body alone. Returns the original split so {@link #reinjectFrontmatter}
     * can put it back after LLM sanitization. Returns null when SKILL.md is
     * absent (no frontmatter to preserve).
     */
    private static SkillLoader.FrontmatterSplit stashFrontmatter(LinkedHashMap<String, String> textFiles) {
        if (!textFiles.containsKey(SKILL_FILE_NAME)) return null;
        var originalSplit = SkillLoader.splitFrontmatter(textFiles.get(SKILL_FILE_NAME));
        if (originalSplit.frontmatter() != null) {
            textFiles.put(SKILL_FILE_NAME, originalSplit.body() != null ? originalSplit.body() : "");
        }
        return originalSplit;
    }

    private static void reinjectFrontmatter(SkillLoader.FrontmatterSplit originalSplit,
                                             LinkedHashMap<String, String> sanitized) {
        if (originalSplit == null || originalSplit.frontmatter() == null
                || !sanitized.containsKey(SKILL_FILE_NAME)) {
            return;
        }
        var sanitizedSplit = SkillLoader.splitFrontmatter(sanitized.get(SKILL_FILE_NAME));
        var sanitizedBody = sanitizedSplit.frontmatter() != null ? sanitizedSplit.body() : sanitized.get(SKILL_FILE_NAME);
        if (sanitizedBody == null) sanitizedBody = "";
        sanitized.put(SKILL_FILE_NAME, originalSplit.frontmatter() + sanitizedBody);
    }

    // --- Internal helpers ---

    // Public for SkillPromotionServiceCoverageTest (default-package test cannot access package-private)
    public static boolean isIdenticalToGlobal(Path skillDir, Path globalDir, String skillName) {
        try {
            var workspaceHash = SkillLoader.hashSkillDirectory(skillDir);
            var globalHash = SkillLoader.hashSkillDirectory(globalDir);
            var wsInfo = SkillLoader.parseSkillFile(skillDir.resolve(SKILL_FILE_NAME));
            var globalInfo = SkillLoader.parseSkillFile(globalDir.resolve(SKILL_FILE_NAME));
            EventLogger.info(EVENT_CATEGORY_SKILLS,
                    "Promote hash-check '%s': workspace[v=%s, hash=%s] global[v=%s, hash=%s] equal=%s"
                            .formatted(skillName,
                                    wsInfo != null ? wsInfo.version() : "?",
                                    workspaceHash.isEmpty() ? "EMPTY" : workspaceHash.substring(0, 12),
                                    globalInfo != null ? globalInfo.version() : "?",
                                    globalHash.isEmpty() ? "EMPTY" : globalHash.substring(0, 12),
                                    workspaceHash.equals(globalHash)));
            if (workspaceHash.equals(globalHash) && !workspaceHash.isEmpty()) {
                EventLogger.info(EVENT_CATEGORY_SKILLS, "Promotion of '%s' skipped: workspace copy is identical to global".formatted(skillName));
                NotificationBus.publish("skill.promote_noop", Map.of(
                        KEY_SKILL_NAME, skillName,
                        "reason", "Workspace copy is identical to the global skill — nothing to promote."
                ));
                return true;
            }
        } catch (IOException e) {
            EventLogger.warn(EVENT_CATEGORY_SKILLS, "Hash check failed for '%s': %s".formatted(skillName, e.getMessage()));
        }
        return false;
    }

    // Public for SkillPromotionServiceCoverageTest (default-package test cannot access package-private)
    public static boolean isDowngrade(Path skillDir, Path globalDir, String skillName) {
        var workspaceInfo = SkillLoader.parseSkillFile(skillDir.resolve(SKILL_FILE_NAME));
        var globalInfo = SkillLoader.parseSkillFile(globalDir.resolve(SKILL_FILE_NAME));
        if (workspaceInfo != null && globalInfo != null) {
            int cmp = SkillLoader.compareVersions(workspaceInfo.version(), globalInfo.version());
            if (cmp < 0) {
                EventLogger.warn(EVENT_CATEGORY_SKILLS, "Promotion of '%s' refused: workspace version %s < global version %s"
                        .formatted(skillName, workspaceInfo.version(), globalInfo.version()));
                NotificationBus.publish(NOTIFICATION_PROMOTE_FAILED, Map.of(
                        KEY_SKILL_NAME, skillName,
                        KEY_ERROR, "Workspace version " + workspaceInfo.version()
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

        // Stamp the system-managed version onto SKILL.md before staging: 1.0.0
        // for a brand-new skill, an auto patch-bump for a materially-changed
        // re-publish. Conformance + promotion deliberately leave version: out of
        // frontmatter, so this write path is where it's assigned — without it an
        // imported skill reads back as 0.0.0.
        stampSkillVersion(sanitized, targetDir);

        try {
            AtomicDirSwap.stageAndSwap(targetDir, stagingDir, backupDir, replacingExisting, staging -> {
                Files.createDirectories(staging.resolve("credentials"));
                Files.createDirectories(staging.resolve("tools"));
                writeSanitizedTextFiles(staging, sanitized);
                stageBinaryFiles(skillDir, staging, binaryFiles);
                pruneEmptyConventionDirs(staging);
            });
            SkillLoader.clearCache();

            // ── Update registry allowlist blessings ──
            // Rewrite SkillRegistryTool rows from the just-promoted SKILL.md's
            // {@code commands:} frontmatter list. Idempotent: clear prior rows
            // for this skill, insert new ones. Un-promotion (future delete
            // endpoint) must mirror the delete.
            syncRegistryToolRows(skillName, targetDir);

            var action = replacingExisting ? "replaced" : "created";
            EventLogger.info(EVENT_CATEGORY_SKILLS, "Promotion of '%s' completed (%s)".formatted(skillName, action));
            NotificationBus.publish("skill.promoted", Map.of(
                    KEY_SKILL_NAME, skillName,
                    "folderName", skillName,
                    "replaced", replacingExisting
            ));
        } catch (IOException e) {
            // stageAndSwap already removed the staging dir on failure.
            EventLogger.error(EVENT_CATEGORY_SKILLS, "Failed to write promoted skill: " + e.getMessage());
            NotificationBus.publish(NOTIFICATION_PROMOTE_FAILED, Map.of(
                    KEY_SKILL_NAME, skillName,
                    KEY_ERROR, e.getMessage()
            ));
        }
    }

    /**
     * Assign the system-managed {@code version:} to the staged SKILL.md via the
     * shared finalize path ({@link SkillLoader#finalizeSkillMdWrite}): INITIAL
     * 1.0.0 when {@code targetDir} has no prior SKILL.md, an auto patch-bump when
     * a material change replaces an existing one, preserved otherwise. No-op when
     * SKILL.md isn't in the payload.
     */
    // Public for SkillPromotionServiceCoverageTest (default-package test cannot access package-private)
    public static void stampSkillVersion(LinkedHashMap<String, String> sanitized, Path targetDir) {
        var skillMd = sanitized.get(SKILL_FILE_NAME);
        if (skillMd == null) return;
        sanitized.put(SKILL_FILE_NAME,
                SkillLoader.finalizeSkillMdWrite(targetDir.resolve(SKILL_FILE_NAME), skillMd));
    }

    private static void writeSanitizedTextFiles(Path stagingDir,
                                                 LinkedHashMap<String, String> sanitized) throws IOException {
        for (var entry : sanitized.entrySet()) {
            var stagedFile = stagingDir.resolve(entry.getKey());
            Files.createDirectories(stagedFile.getParent());
            Files.writeString(stagedFile, entry.getValue());
        }
    }

    private static void stageBinaryFiles(Path skillDir, Path stagingDir,
                                          List<String> binaryFiles) throws IOException {
        for (var sourceName : binaryFiles) {
            var sourceOpt = resolveBinarySource(skillDir, sourceName);
            if (sourceOpt.isPresent() && Files.exists(sourceOpt.get())) {
                var source = sourceOpt.get();
                var staged = stagingDir.resolve(sourceName);
                Files.createDirectories(staged.getParent());
                Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Resolve a binary's on-disk location. Tries the canonical path first; if
     * the file isn't there (the structure enforcer relocated it into tools/),
     * falls back to walking skillDir for any file with the same basename.
     */
    private static Optional<Path> resolveBinarySource(Path skillDir, String sourceName) throws IOException {
        var source = skillDir.resolve(sourceName);
        if (Files.exists(source)) return Optional.of(source);
        var fileName = baseName(sourceName);
        try (var srcWalk = Files.walk(skillDir)) {
            return srcWalk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equals(fileName))
                    .findFirst();
        }
    }

    /** Remove credentials/ and tools/ from staging when nothing was placed in them. */
    private static void pruneEmptyConventionDirs(Path stagingDir) throws IOException {
        for (var subDir : List.of("credentials", "tools")) {
            var dir = stagingDir.resolve(subDir);
            if (!Files.isDirectory(dir)) continue;
            boolean empty;
            try (var entries = Files.list(dir)) {
                empty = entries.findAny().isEmpty();
            }
            if (empty) {
                Files.delete(dir);
            }
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
        var skillMd = globalSkillDir.resolve(SKILL_FILE_NAME);
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
        EventLogger.info(EVENT_CATEGORY_SKILLS,
                "Registry allowlist for '%s': %d command(s) declared".formatted(skillName, declared.size()));
    }

    /**
     * Atomically swap a staging directory into the target location, backing up
     * any existing target first. Thin delegate to {@link AtomicDirSwap#atomicSwap}
     * (JCLAW-727) — retained here as the stable entry point for existing callers.
     */
    public static void atomicSwap(Path targetDir, Path stagingDir, Path backupDir,
                            boolean replacing) throws IOException {
        AtomicDirSwap.atomicSwap(targetDir, stagingDir, backupDir, replacing);
    }

    /**
     * Last path segment of a {@code /}-separated relative path.
     * {@code lastIndexOf('/')} returns -1 when there's no slash, so
     * {@code +1} yields 0 and the whole string is returned — no
     * {@code contains("/")} guard needed.
     */
    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Recursively delete {@code dir}. Thin delegate to
     * {@link AtomicDirSwap#deleteRecursive} (JCLAW-727) — retained here as the
     * stable entry point for existing callers (controllers, importer, tests).
     */
    public static void deleteRecursive(Path dir) throws IOException {
        AtomicDirSwap.deleteRecursive(dir);
    }

    private static final Set<String> CREDENTIAL_EXTENSIONS = Set.of(
            ".json", ".txt", ".env", ".yaml", ".yml", ".properties"
    );

    public static String enforceTextFilePath(String path) {
        if (path.startsWith(TOOLS_DIR_PREFIX) || path.startsWith(CREDENTIALS_DIR_PREFIX)) return path;
        if (path.equals(SKILL_FILE_NAME)) return path;
        var lower = path.toLowerCase();
        for (var ext : CREDENTIAL_EXTENSIONS) {
            if (lower.endsWith(ext)) return CREDENTIALS_DIR_PREFIX + path;
        }
        return path;
    }

    public static String stripCredentialsJson(String content) {
        try {
            var json = JsonParser.parseString(content).getAsJsonObject();
            var stripped = new JsonObject();
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

    /**
     * LLM sanitizer pass over the skill's text payload. Thin delegate to
     * {@link SkillSanitizer#sanitize} (JCLAW-727) — retained as the stable,
     * reflectively-invoked entry point; the pipeline batches, prompts, and
     * falls back to originals there.
     */
    static LinkedHashMap<String, String> sanitizeWithLlm(LinkedHashMap<String, String> fileContents) {
        return SkillSanitizer.sanitize(fileContents);
    }
}
