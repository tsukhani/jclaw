package agents;

import models.Agent;
import models.AgentSkillConfig;
import play.Play;
import services.AgentService;
import services.EventLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Scans global skills/ and workspace/{agent}/skills/ directories,
 * filters by per-agent permissions, and provides skill metadata for system prompt injection.
 */
public class SkillLoader {

    /**
     * @param toolsDeclared true when the SKILL.md frontmatter contained a {@code tools:} key
     *                      (even if the list was empty). false means the skill predates the
     *                      declaration system and callers should fall back to heuristics.
     * @param version       semver string from frontmatter ({@code version:}), or {@code "0.0.0"}
     *                      if the key was absent.
     */
    public record SkillInfo(String name, String description, Path location,
                            List<String> tools, boolean toolsDeclared, String version) {
        public SkillInfo(String name, String description, Path location) {
            this(name, description, location, List.of(), false, "0.0.0");
        }
    }

    /** Compare two semver strings ("1.2.3" vs "1.10.0"). Returns negative/0/positive like Comparable. */
    public static int compareVersions(String a, String b) {
        var pa = parseVersion(a);
        var pb = parseVersion(b);
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(pa[i], pb[i]);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    /** Parse "X.Y.Z" into int[3]. Missing or malformed components default to 0. */
    public static int[] parseVersion(String v) {
        var out = new int[]{0, 0, 0};
        if (v == null) return out;
        var parts = v.trim().split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try { out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*", "")); }
            catch (NumberFormatException _) {}
        }
        return out;
    }

    /** Bump the patch component of a semver string. "1.2.3" → "1.2.4". */
    public static String bumpPatch(String v) {
        var p = parseVersion(v);
        return "%d.%d.%d".formatted(p[0], p[1], p[2] + 1);
    }

    private static final ConcurrentHashMap<String, CachedSkills> skillCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30_000;

    private record CachedSkills(List<SkillInfo> skills, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static final int MAX_SKILLS = 150;
    private static final int MAX_SKILLS_CHARS = 30_000;
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);

    public static void clearCache() {
        skillCache.clear();
    }

    /**
     * Remove AgentSkillConfig records that reference skills no longer on disk.
     * Called at startup to sync DB with the actual skills/ directory.
     */
    public static void syncSkillConfigs() {
        // Collect all skill names that exist on disk (global + all agent workspaces)
        var existingSkills = new HashSet<String>();

        // Global skills
        var globalDir = globalSkillsPath();
        if (Files.isDirectory(globalDir)) {
            try (var dirs = Files.list(globalDir)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    if (Files.exists(dir.resolve("SKILL.md"))) {
                        var info = parseSkillFile(dir.resolve("SKILL.md"));
                        if (info != null) existingSkills.add(info.name());
                    }
                });
            } catch (IOException ignored) {}
        }

        // Agent workspace skills
        var workspaceRoot = AgentService.workspaceRoot();
        if (Files.isDirectory(workspaceRoot)) {
            try (var agents = Files.list(workspaceRoot)) {
                agents.filter(Files::isDirectory).forEach(agentDir -> {
                    var skillsDir = agentDir.resolve("skills");
                    if (Files.isDirectory(skillsDir)) {
                        try (var dirs = Files.list(skillsDir)) {
                            dirs.filter(Files::isDirectory).forEach(dir -> {
                                if (Files.exists(dir.resolve("SKILL.md"))) {
                                    var info = parseSkillFile(dir.resolve("SKILL.md"));
                                    if (info != null) existingSkills.add(info.name());
                                }
                            });
                        } catch (IOException ignored) {}
                    }
                });
            } catch (IOException ignored) {}
        }

        // Remove orphaned configs
        services.Tx.run(() -> {
            List<AgentSkillConfig> allConfigs = AgentSkillConfig.findAll();
            int removed = 0;
            for (var config : allConfigs) {
                if (!existingSkills.contains(config.skillName)) {
                    config.delete();
                    removed++;
                }
            }
            if (removed > 0) {
                EventLogger.info("skills", "Removed %d orphaned skill config(s) for deleted skills".formatted(removed));
            }
        });
    }

    public static List<SkillInfo> loadSkills(String agentName) {
        var cached = skillCache.get(agentName);
        if (cached != null && !cached.isExpired()) {
            return cached.skills();
        }
        var skills = loadSkillsFromDisk(agentName);
        skillCache.put(agentName, new CachedSkills(skills, System.currentTimeMillis() + CACHE_TTL_MS));
        return skills;
    }

    private static List<SkillInfo> loadSkillsFromDisk(String agentName) {
        var allSkills = new ArrayList<SkillInfo>();

        // Only scan agent workspace skills — global skills must be explicitly copied to agents
        var workspaceDir = AgentService.workspacePath(agentName);
        var agentDir = workspaceDir.resolve("skills");
        scanSkillsDirectory(agentDir, allSkills);

        // Make locations relative to the agent's workspace (readFile tool resolves relative to workspace)
        allSkills.replaceAll(s -> {
            if (s.location() != null && s.location().startsWith(workspaceDir)) {
                return new SkillInfo(s.name(), s.description(), workspaceDir.relativize(s.location()),
                        s.tools(), s.toolsDeclared(), s.version());
            }
            return s;
        });

        // Filter by permissions + validate tool requirements (JPA access — may be called from tool execution
        // outside a request thread, so wrap in a Tx)
        services.Tx.run(() -> {
            var agent = Agent.findByName(agentName);
            if (agent == null) return;

            var configs = AgentSkillConfig.findByAgent(agent);
            var disabledSkills = new HashSet<String>();
            for (var c : configs) {
                if (!c.enabled) disabledSkills.add(c.skillName);
            }
            if (!disabledSkills.isEmpty()) {
                allSkills.removeIf(s -> disabledSkills.contains(s.name()));
            }

            // Defense-in-depth: exclude any skill whose declared tools are not all available to this agent
            // (catches skills authored outside the drag-drop API flow, e.g. by skill-creator writing files
            // directly).
            allSkills.removeIf(s -> {
                if (s.tools() == null || s.tools().isEmpty()) return false;
                var result = ToolCatalog.validateSkillTools(agent, s.tools());
                if (!result.isOk()) {
                    EventLogger.warn("skills", "Excluding skill '%s' from agent '%s': missing tools %s"
                            .formatted(s.name(), agentName, result.missing()));
                    return true;
                }
                return false;
            });
        });

        // Non-main agents may not use an out-of-date skill-creator. If the global
        // skill-creator exists and is newer than this agent's workspace copy, hide the
        // workspace copy from <available_skills> so the LLM cannot invoke it. The user
        // must explicitly update by dragging skill-creator from the global registry onto
        // the agent card.
        if (!"main".equalsIgnoreCase(agentName)) {
            var globalSkillCreatorPath = globalSkillsPath().resolve("skill-creator").resolve("SKILL.md");
            if (Files.exists(globalSkillCreatorPath)) {
                var globalSkillCreator = parseSkillFile(globalSkillCreatorPath);
                if (globalSkillCreator != null) {
                    var globalVersion = globalSkillCreator.version();
                    allSkills.removeIf(s -> {
                        if (!"skill-creator".equals(s.name())) return false;
                        if (compareVersions(s.version(), globalVersion) < 0) {
                            EventLogger.warn("skills",
                                    "Excluding out-of-date skill-creator for agent '%s': workspace v%s < global v%s (drag skill-creator from global to update)"
                                            .formatted(agentName, s.version(), globalVersion));
                            return true;
                        }
                        return false;
                    });
                }
            }
        }

        return allSkills.stream().limit(MAX_SKILLS).toList();
    }

    private static void scanSkillsDirectory(Path skillsDir, List<SkillInfo> skills) {
        if (!Files.isDirectory(skillsDir)) return;
        try (var dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                var skillFile = dir.resolve("SKILL.md");
                if (Files.exists(skillFile)) {
                    var info = parseSkillFile(skillFile);
                    if (info != null) {
                        skills.add(info);
                    }
                }
            });
        } catch (IOException e) {
            EventLogger.warn("agent", "Failed to scan skills directory %s: %s"
                    .formatted(skillsDir, e.getMessage()));
        }
    }

    public static Path globalSkillsPath() {
        return Path.of(Play.configuration.getProperty("jclaw.skills.path", "skills"));
    }

    /**
     * Format skills as XML for injection into system prompt.
     */
    public static String formatSkillsXml(List<SkillInfo> skills) {
        if (skills.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("<available_skills>\n");

        int totalChars = 0;
        boolean compact = false;

        for (var skill : skills) {
            var entry = formatSkillEntry(skill, !compact);
            if (totalChars + entry.length() > MAX_SKILLS_CHARS && !compact) {
                compact = true;
                sb.setLength(0);
                sb.append("<available_skills>\n");
                totalChars = 0;
                for (var s : skills) {
                    var compactEntry = formatSkillEntry(s, false);
                    if (totalChars + compactEntry.length() > MAX_SKILLS_CHARS) break;
                    sb.append(compactEntry);
                    totalChars += compactEntry.length();
                }
                break;
            }
            sb.append(entry);
            totalChars += entry.length();
        }

        sb.append("</available_skills>");
        return sb.toString();
    }

    /**
     * System prompt instructions for skill matching.
     */
    public static String skillMatchingInstructions() {
        return """
                ## Skills (mandatory)
                Before replying: scan <available_skills> <description> entries.
                - If exactly one skill clearly applies: read its SKILL.md at <location> with the readFile tool, then follow it.
                - If multiple could apply: choose the most specific one, then read/follow it.
                - If platform-specific variants exist (e.g., mac vs linux): select the variant matching the current platform from the Environment section.
                - If the user's request involves multiple skills: select the skill matching the user's primary action (the first or most important verb), not secondary follow-up actions.
                - Match by intent, not exact wording. A skill applies when the user's goal falls within the skill's domain, even if the description includes details the user did not mention. Example: a user asking "recommend a restaurant" matches a skill described as "restaurants in City X" — the skill's specifics are implementation details, not prerequisites.
                - If none clearly apply: do not read any SKILL.md.
                - **Skill authoring is always routed through skill-creator.** If the user asks to create, update, modify, edit, rename, refactor, fix, or change a skill (anything under `skills/<name>/`), the applicable skill is `skill-creator` — regardless of whether the user mentions it by name. Read `skill-creator`'s SKILL.md first and follow its workflow. Never edit files under `skills/<name>/` directly via the filesystem tool without going through skill-creator.
                Constraints: never read more than one skill up front; only read after selecting.
                """;
    }

    // --- Internal ---

    /** Matches a top-level {@code tools:} key in YAML frontmatter (inline or block form). */
    private static final Pattern TOOLS_KEY_PRESENT = Pattern.compile("(?m)^tools:");

    public static SkillInfo parseSkillFile(Path path) {
        try {
            var content = Files.readString(path);
            var info = parseSkillContent(content, path);
            if (info != null) return info;
            // Fallback: use directory name
            return new SkillInfo(path.getParent().getFileName().toString(), "", path, List.of(), false, "0.0.0");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Parse a SKILL.md content string without reading from disk. Returns null if no
     * {@code name:} key is found (caller decides how to handle the fallback).
     */
    public static SkillInfo parseSkillContent(String content, Path locationHint) {
        if (content == null) return null;
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (matcher.find()) {
            var frontmatter = matcher.group(1);
            var name = extractYamlValue(frontmatter, "name");
            var description = extractYamlValue(frontmatter, "description");
            var toolsDeclared = TOOLS_KEY_PRESENT.matcher(frontmatter).find();
            var tools = extractYamlList(frontmatter, "tools");
            var version = extractYamlValue(frontmatter, "version");
            if (name != null) {
                return new SkillInfo(name, description != null ? description : "", locationHint,
                        tools, toolsDeclared, version != null ? version : "0.0.0");
            }
        }
        return null;
    }

    /**
     * Extract a YAML list value for the given key. Supports both inline form
     * {@code key: [a, b, c]} and block form:
     * <pre>
     * key:
     *   - a
     *   - b
     * </pre>
     */
    public static List<String> extractYamlList(String yaml, String key) {
        // Inline form: key: [a, b, c]
        var inlinePattern = Pattern.compile("^" + Pattern.quote(key) + ":\\s*\\[(.*?)\\]\\s*$",
                Pattern.MULTILINE);
        var inlineMatcher = inlinePattern.matcher(yaml);
        if (inlineMatcher.find()) {
            var items = inlineMatcher.group(1).trim();
            if (items.isEmpty()) return List.of();
            var result = new ArrayList<String>();
            for (var part : items.split(",")) {
                var cleaned = part.trim().replaceAll("^[\"']|[\"']$", "");
                if (!cleaned.isEmpty()) result.add(cleaned);
            }
            return result;
        }

        // Block form: key:\n  - a\n  - b
        var blockPattern = Pattern.compile("^" + Pattern.quote(key) + ":\\s*\\n((?:\\s*-\\s*.*\\n?)+)",
                Pattern.MULTILINE);
        var blockMatcher = blockPattern.matcher(yaml);
        if (blockMatcher.find()) {
            var body = blockMatcher.group(1);
            var result = new ArrayList<String>();
            for (var line : body.split("\\n")) {
                var trimmed = line.trim();
                if (trimmed.startsWith("-")) {
                    var item = trimmed.substring(1).trim().replaceAll("^[\"']|[\"']$", "");
                    if (!item.isEmpty()) result.add(item);
                }
            }
            return result;
        }

        return List.of();
    }

    /**
     * Split a SKILL.md content string into its raw frontmatter block (including both
     * {@code ---} delimiters and the trailing newline) and the remaining body. Returns
     * {@code null} for either side if no frontmatter is present.
     *
     * <p>Used by the promote flow to preserve the frontmatter deterministically across
     * LLM sanitization: the frontmatter is held aside, only the body is sent to the LLM,
     * and the original frontmatter is reinjected afterward.
     */
    /**
     * Compute a deterministic SHA-256 hash over every regular file in a skill directory.
     * Files are visited in sorted relative-path order; each file contributes its relative
     * path bytes plus a separator plus its content bytes. Used to byte-compare two skill
     * directories regardless of filesystem iteration order.
     */
    public static String hashSkillDirectory(Path skillDir) throws IOException {
        if (!Files.isDirectory(skillDir)) return "";
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
        try (var walk = Files.walk(skillDir)) {
            var files = walk.filter(Files::isRegularFile)
                    .sorted(java.util.Comparator.comparing(p -> skillDir.relativize(p).toString()))
                    .toList();
            for (var file : files) {
                var rel = skillDir.relativize(file).toString();
                digest.update(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        }
        var sb = new StringBuilder();
        for (var b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Deterministically finalize a SKILL.md write inside a skill directory:
     *
     * <ul>
     *   <li>If no previous file exists → this is a fresh skill. Ensure the frontmatter's
     *       {@code version:} is present; if missing, inject {@code version: 1.0.0}. The
     *       LLM's declared version is otherwise preserved (a fresh skill can start at any
     *       sensible version).</li>
     *   <li>If a previous file exists and the body or non-version frontmatter fields
     *       changed → auto-bump the patch component of the PREVIOUS file's version and
     *       inject it into the new content, ignoring whatever the LLM wrote for
     *       {@code version:}.</li>
     *   <li>If nothing material changed (same frontmatter excluding version, same body) →
     *       write the incoming content with the old version reinstated. This keeps the
     *       version stable against LLM-initiated no-ops.</li>
     * </ul>
     *
     * The caller is expected to write the returned string to disk.
     */
    public static String finalizeSkillMdWrite(Path targetPath, String newContent) {
        if (newContent == null) newContent = "";
        try {
            if (!Files.exists(targetPath)) {
                // Fresh skill — always start at 1.0.0 regardless of anything the LLM wrote
                return ensureVersionInFrontmatter(newContent, "1.0.0");
            }
            var oldContent = Files.readString(targetPath);
            var oldInfo = parseFrontmatterStringForVersion(oldContent);
            var oldVersion = oldInfo != null && oldInfo.length > 0 ? oldInfo[0] : "0.0.0";

            if (contentDiffersIgnoringVersion(oldContent, newContent)) {
                var bumped = bumpPatch(oldVersion);
                return ensureVersionInFrontmatter(newContent, bumped);
            } else {
                // No material change — reinstate the old version, regardless of what the LLM wrote
                return ensureVersionInFrontmatter(newContent, oldVersion);
            }
        } catch (IOException e) {
            // If we can't read the old file, fall back to the incoming content unchanged
            return newContent;
        }
    }

    /** Extract just the {@code version:} value from an arbitrary SKILL.md string, or null. */
    private static String[] parseFrontmatterStringForVersion(String content) {
        if (content == null) return new String[]{"0.0.0"};
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (matcher.find() && matcher.start() == 0) {
            var frontmatter = matcher.group(1);
            var version = extractYamlValue(frontmatter, "version");
            return new String[]{version != null ? version : "0.0.0"};
        }
        return new String[]{"0.0.0"};
    }

    /**
     * True if the two SKILL.md strings differ in any way other than the {@code version:}
     * frontmatter line. Used by {@link #finalizeSkillMdWrite} to decide whether a bump
     * is warranted.
     */
    private static boolean contentDiffersIgnoringVersion(String a, String b) {
        return stripVersionLine(a).equals(stripVersionLine(b)) == false;
    }

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^version:.*$");

    private static String stripVersionLine(String content) {
        if (content == null) return "";
        return VERSION_LINE.matcher(content).replaceAll("").replaceAll("\\n\\n+", "\n\n");
    }

    /**
     * Ensure the frontmatter of {@code content} has a {@code version:} line with the given
     * value. If {@code targetVersion} is null, only adds a line if none exists (defaulting
     * to {@code 1.0.0}); otherwise overwrites any existing line (or inserts one before the
     * closing {@code ---}).
     */
    private static String ensureVersionInFrontmatter(String content, String targetVersion) {
        if (content == null) content = "";
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find() || matcher.start() != 0) {
            // No frontmatter at all — prepend a minimal one
            var v = targetVersion != null ? targetVersion : "1.0.0";
            return "---\nversion: %s\n---\n\n%s".formatted(v, content);
        }
        var frontmatter = matcher.group(1);
        var before = content.substring(0, matcher.start(1));
        var after = content.substring(matcher.end(1));
        var hasVersion = VERSION_LINE.matcher(frontmatter).find();
        String newFrontmatter;
        if (hasVersion) {
            if (targetVersion != null) {
                newFrontmatter = VERSION_LINE.matcher(frontmatter).replaceFirst("version: " + targetVersion);
            } else {
                newFrontmatter = frontmatter; // already has a version, caller didn't specify → leave alone
            }
        } else {
            var v = targetVersion != null ? targetVersion : "1.0.0";
            // Insert version line right after the description: line if present, else at the end
            if (frontmatter.contains("description:")) {
                newFrontmatter = frontmatter.replaceFirst("(?m)(^description:.*$)", "$1\nversion: " + v);
            } else {
                newFrontmatter = frontmatter.stripTrailing() + "\nversion: " + v;
            }
        }
        return before + newFrontmatter + after;
    }

    public record FrontmatterSplit(String frontmatter, String body) {}

    public static FrontmatterSplit splitFrontmatter(String content) {
        if (content == null) return new FrontmatterSplit(null, null);
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (matcher.find() && matcher.start() == 0) {
            var end = matcher.end();
            // Include the trailing newline after the closing --- if present
            if (end < content.length() && content.charAt(end) == '\n') end++;
            return new FrontmatterSplit(content.substring(0, end), content.substring(end));
        }
        return new FrontmatterSplit(null, content);
    }

    public static String extractYamlValue(String yaml, String key) {
        var pattern = Pattern.compile("^" + Pattern.quote(key) + ":\\s*[\"']?(.*?)[\"']?\\s*$",
                Pattern.MULTILINE);
        var matcher = pattern.matcher(yaml);
        if (matcher.find()) {
            var value = matcher.group(1).trim();
            return value.isEmpty() ? null : value;
        }
        var multiPattern = Pattern.compile("^" + Pattern.quote(key) + ":\\s*[|>]\\s*\\n((?:  .*\\n?)+)",
                Pattern.MULTILINE);
        var multiMatcher = multiPattern.matcher(yaml);
        if (multiMatcher.find()) {
            return multiMatcher.group(1).replaceAll("(?m)^  ", "").trim();
        }
        return null;
    }

    private static String formatSkillEntry(SkillInfo skill, boolean full) {
        var sb = new StringBuilder();
        sb.append("  <skill>\n");
        sb.append("    <name>").append(skill.name()).append("</name>\n");
        if (full) {
            sb.append("    <description>").append(skill.description()).append("</description>\n");
        }
        sb.append("    <location>").append(skill.location() != null ? skill.location() : "").append("</location>\n");
        if (skill.tools() != null && !skill.tools().isEmpty()) {
            sb.append("    <tools>").append(String.join(", ", skill.tools())).append("</tools>\n");
        }
        sb.append("  </skill>\n");
        return sb.toString();
    }
}
