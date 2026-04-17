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
     * @param commands      shell-allowlist contributions declared by this skill — typically the
     *                      basenames of executables bundled under the skill's {@code tools/}
     *                      directory. Distinct from {@code tools}: {@code tools} names the
     *                      JClaw tools the skill consumes (exec, filesystem, browser...),
     *                      while {@code commands} names shell binaries the skill provides.
     *                      Blessed at promotion time and snapshotted per-agent at install time;
     *                      feeds the ShellExecTool allowlist union. Empty/null when the skill
     *                      declares no executables.
     * @param author        name of the agent that authored the skill, from the {@code author:}
     *                      frontmatter key. Empty string when the skill predates the field
     *                      (caller should render no attribution rather than guessing).
     */
    public record SkillInfo(String name, String description, Path location,
                            List<String> tools, boolean toolsDeclared, String version,
                            List<String> commands, String author, String icon) {
        public SkillInfo(String name, String description, Path location) {
            this(name, description, location, List.of(), false, "0.0.0", List.of(), "", "");
        }

        /** Backwards-compatible 6-arg constructor for call sites predating the {@code commands}/{@code author}/{@code icon} fields. */
        public SkillInfo(String name, String description, Path location,
                         List<String> tools, boolean toolsDeclared, String version) {
            this(name, description, location, tools, toolsDeclared, version, List.of(), "", "");
        }

        /** Backwards-compatible 7-arg constructor for call sites predating the {@code author}/{@code icon} fields. */
        public SkillInfo(String name, String description, Path location,
                         List<String> tools, boolean toolsDeclared, String version,
                         List<String> commands) {
            this(name, description, location, tools, toolsDeclared, version, commands, "", "");
        }

        /** Backwards-compatible 8-arg constructor for call sites predating the {@code icon} field. */
        public SkillInfo(String name, String description, Path location,
                         List<String> tools, boolean toolsDeclared, String version,
                         List<String> commands, String author) {
            this(name, description, location, tools, toolsDeclared, version, commands, author, "");
        }
    }

    /** Emoji substituted in user-visible skill listings when a SKILL.md omits the {@code icon:} frontmatter key. */
    public static final String DEFAULT_SKILL_ICON = "🎯";

    /** Compare two semver strings. Delegates to {@link SkillVersionManager}. */
    public static int compareVersions(String a, String b) {
        return SkillVersionManager.compareVersions(a, b);
    }

    /** Parse "X.Y.Z" into int[3]. Delegates to {@link SkillVersionManager}. */
    public static int[] parseVersion(String v) {
        return SkillVersionManager.parseVersion(v);
    }

    /** Bump the patch component. Delegates to {@link SkillVersionManager}. */
    public static String bumpPatch(String v) {
        return SkillVersionManager.bumpPatch(v);
    }

    private static final int SKILL_CACHE_MAX_SIZE = 100;
    private static final java.util.concurrent.ConcurrentHashMap<String, CachedSkills> skillCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30_000;

    private record CachedSkills(List<SkillInfo> skills, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    /** Evict expired entries and trim to max size. */
    private static void evictSkillCache() {
        skillCache.entrySet().removeIf(e -> e.getValue().isExpired());
        while (skillCache.size() > SKILL_CACHE_MAX_SIZE) {
            var it = skillCache.entrySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
            else break;
        }
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
            } catch (IOException e) {
                EventLogger.warn("skills", "Failed to scan global skills directory %s: %s"
                        .formatted(globalDir, e.getMessage()));
            }
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
                        } catch (IOException e) {
                            EventLogger.warn("skills", "Failed to scan agent skills directory %s: %s"
                                    .formatted(skillsDir, e.getMessage()));
                        }
                    }
                });
            } catch (IOException e) {
                EventLogger.warn("skills", "Failed to scan workspace root %s: %s"
                        .formatted(workspaceRoot, e.getMessage()));
            }
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
        if (skillCache.size() > SKILL_CACHE_MAX_SIZE) evictSkillCache();
        return skills;
    }

    private static List<SkillInfo> loadSkillsFromDisk(String agentName) {
        var allSkills = new ArrayList<SkillInfo>();

        // Only scan agent workspace skills — global skills must be explicitly copied to agents
        var workspaceDir = AgentService.workspacePath(agentName);
        var agentDir = workspaceDir.resolve("skills");
        scanSkillsDirectory(agentDir, allSkills);

        // Make locations relative to the agent's workspace (readFile tool resolves relative to workspace).
        // Preserve every field on the record — the 6-arg constructor used previously
        // silently defaulted commands/author/icon to empty, which is how JCLAW-71's
        // icon field leaked back to "" after parsing.
        allSkills.replaceAll(s -> {
            if (s.location() != null && s.location().startsWith(workspaceDir)) {
                return new SkillInfo(s.name(), s.description(), workspaceDir.relativize(s.location()),
                        s.tools(), s.toolsDeclared(), s.version(),
                        s.commands(), s.author(), s.icon());
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
            var disabledTools = ToolRegistry.loadDisabledTools(agent);
            allSkills.removeIf(s -> {
                if (s.tools() == null || s.tools().isEmpty()) return false;
                var result = ToolCatalog.validateSkillTools(disabledTools, s.tools());
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

        // Sort by name for deterministic ordering — the skills block is part of the
        // LLM prompt prefix, and filesystem iteration order is not stable.
        return allSkills.stream()
                .sorted(java.util.Comparator.comparing(SkillInfo::name))
                .limit(MAX_SKILLS)
                .toList();
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
                - **When the user asks what skills you have:** render a markdown table with header `| Skill | Description |`, one row per skill, where the Skill cell is `<icon> **<name>**` (icon from `<available_skills>` `<icon>` element) and the Description cell is the skill's `<description>`.
                - **When the user asks what tools you have:** present them grouped by the `###` category headings in the Tool Catalog (System / Files / Web / Utilities), each category followed by its own `| Tool | Purpose |` markdown table. Do not mention tools that are not in the Tool Catalog — those are internal and should stay invisible to the user.
                Constraints: never read more than one skill up front; only read after selecting.
                """;
    }

    // --- Internal ---

    /** Matches a top-level {@code tools:} key in YAML frontmatter (inline or block form). */
    private static final Pattern TOOLS_KEY_PRESENT = Pattern.compile("(?m)^tools:");

    // Pre-compiled YAML extraction patterns — keys come from a closed set
    private static final Pattern NAME_VALUE = Pattern.compile("^name:\\s*[\"']?(.*?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern DESCRIPTION_VALUE = Pattern.compile("^description:\\s*[\"']?(.*?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern TOOLS_VALUE = Pattern.compile("^tools:\\s*[\"']?(.*?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern VERSION_VALUE = Pattern.compile("^version:\\s*[\"']?(.*?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern NAME_MULTI = Pattern.compile("^name:\\s*[|>]\\s*\\n((?:  .*\\n?)+)", Pattern.MULTILINE);
    private static final Pattern DESCRIPTION_MULTI = Pattern.compile("^description:\\s*[|>]\\s*\\n((?:  .*\\n?)+)", Pattern.MULTILINE);
    private static final Pattern TOOLS_MULTI = Pattern.compile("^tools:\\s*[|>]\\s*\\n((?:  .*\\n?)+)", Pattern.MULTILINE);
    private static final Pattern VERSION_MULTI = Pattern.compile("^version:\\s*[|>]\\s*\\n((?:  .*\\n?)+)", Pattern.MULTILINE);
    private static final Pattern TOOLS_INLINE_LIST = Pattern.compile("^tools:\\s*\\[(.*?)\\]\\s*$", Pattern.MULTILINE);
    private static final Pattern TOOLS_BLOCK_LIST = Pattern.compile("^tools:\\s*\\n((?:\\s*-\\s*.*\\n?)+)", Pattern.MULTILINE);

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
            var commands = extractYamlList(frontmatter, "commands");
            var author = extractYamlValue(frontmatter, "author");
            var icon = extractYamlValue(frontmatter, "icon");
            if (name != null) {
                return new SkillInfo(name, description != null ? description : "", locationHint,
                        tools, toolsDeclared, version != null ? version : "0.0.0",
                        commands, author != null ? author : "",
                        icon != null ? icon : "");
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
        var inlinePattern = "tools".equals(key) ? TOOLS_INLINE_LIST
                : Pattern.compile("^" + Pattern.quote(key) + ":\\s*\\[(.*?)\\]\\s*$", Pattern.MULTILINE);
        var inlineMatcher = inlinePattern.matcher(yaml);
        if (inlineMatcher.find()) {
            var items = inlineMatcher.group(1).strip();
            if (items.isEmpty()) return List.of();
            var result = new ArrayList<String>();
            for (var part : items.split(",")) {
                var cleaned = part.strip().replaceAll("^[\"']|[\"']$", "");
                if (!cleaned.isEmpty()) result.add(cleaned);
            }
            return result;
        }

        // Block form: key:\n  - a\n  - b
        var blockPattern = "tools".equals(key) ? TOOLS_BLOCK_LIST
                : Pattern.compile("^" + Pattern.quote(key) + ":\\s*\\n((?:\\s*-\\s*.*\\n?)+)", Pattern.MULTILINE);
        var blockMatcher = blockPattern.matcher(yaml);
        if (blockMatcher.find()) {
            var body = blockMatcher.group(1);
            var result = new ArrayList<String>();
            for (var line : body.split("\\n")) {
                var trimmed = line.strip();
                if (trimmed.startsWith("-")) {
                    var item = trimmed.substring(1).strip().replaceAll("^[\"']|[\"']$", "");
                    if (!item.isEmpty()) result.add(item);
                }
            }
            return result;
        }

        return List.of();
    }

    // --- Text classification ---

    /** Extensions treated as human-readable text for skill content classification. */
    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
            ".md", ".json", ".txt", ".yaml", ".yml", ".xml", ".sh", ".py", ".js",
            ".ts", ".java", ".html", ".css", ".toml", ".ini", ".cfg", ".conf", ".env",
            ".properties", ".rb", ".go", ".rs", ".lua", ".sql"
    );

    /** Extensionless filenames that are conventionally plain text. */
    private static final java.util.Set<String> KNOWN_TEXT_FILES = java.util.Set.of(
            "readme", "makefile", "dockerfile", "license", "changelog",
            "gemfile", "rakefile", "procfile", "vagrantfile"
    );

    /**
     * True when {@code name} (a filename or relative path) should be treated as text.
     * Used to split skill contents into text files (eligible for LLM sanitization and
     * content editing) and binaries (eligible for malware scanning).
     */
    public static boolean isTextFile(String name) {
        var lower = name.toLowerCase();
        for (var ext : TEXT_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        var baseName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        return KNOWN_TEXT_FILES.contains(baseName);
    }

    /** Delegates to {@link SkillVersionManager}. */
    public static String hashSkillDirectory(Path skillDir) throws IOException {
        return SkillVersionManager.hashSkillDirectory(skillDir);
    }

    /** Delegates to {@link SkillVersionManager}. */
    public static String finalizeSkillMdWrite(Path targetPath, String newContent) {
        return SkillVersionManager.finalizeSkillMdWrite(targetPath, newContent);
    }

    public record FrontmatterSplit(String frontmatter, String body) {}

    /** Delegates to {@link SkillVersionManager}. */
    public static FrontmatterSplit splitFrontmatter(String content) {
        var result = SkillVersionManager.splitFrontmatter(content);
        return new FrontmatterSplit(result.frontmatter(), result.body());
    }

    public static String extractYamlValue(String yaml, String key) {
        var valuePattern = switch (key) {
            case "name" -> NAME_VALUE;
            case "description" -> DESCRIPTION_VALUE;
            case "tools" -> TOOLS_VALUE;
            case "version" -> VERSION_VALUE;
            default -> Pattern.compile("^" + Pattern.quote(key) + ":\\s*[\"']?(.*?)[\"']?\\s*$", Pattern.MULTILINE);
        };
        var matcher = valuePattern.matcher(yaml);
        if (matcher.find()) {
            var value = matcher.group(1).strip();
            return value.isEmpty() ? null : value;
        }
        var multiPattern = switch (key) {
            case "name" -> NAME_MULTI;
            case "description" -> DESCRIPTION_MULTI;
            case "tools" -> TOOLS_MULTI;
            case "version" -> VERSION_MULTI;
            default -> Pattern.compile("^" + Pattern.quote(key) + ":\\s*[|>]\\s*\\n((?:  .*\\n?)+)", Pattern.MULTILINE);
        };
        var multiMatcher = multiPattern.matcher(yaml);
        if (multiMatcher.find()) {
            return multiMatcher.group(1).replaceAll("(?m)^  ", "").strip();
        }
        return null;
    }

    /**
     * Format a single skill as the {@code <skill>…</skill>} block embedded inside
     * {@code <available_skills>}. Package-accessible so {@link SystemPromptAssembler}
     * can measure per-skill char sizes for its introspection breakdown without
     * re-implementing the wire format.
     */
    static String formatSkillEntry(SkillInfo skill, boolean full) {
        var sb = new StringBuilder();
        sb.append("  <skill>\n");
        sb.append("    <name>").append(skill.name()).append("</name>\n");
        var icon = skill.icon() == null || skill.icon().isEmpty() ? DEFAULT_SKILL_ICON : skill.icon();
        sb.append("    <icon>").append(icon).append("</icon>\n");
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
