package agents;

import models.Agent;
import models.AgentSkillConfig;
import play.Play;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.Caches;
import services.AgentService;
import services.EventLogger;
import services.Tx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scans global skills/ and workspace/{agent}/skills/ directories,
 * filters by per-agent permissions, and provides skill metadata for system prompt injection.
 */
public class SkillLoader {

    /**
     * Metadata describing a discovered skill on disk.
     *
     * @param name          skill name (from the {@code name:} frontmatter key or
     *                      the SKILL.md directory name).
     * @param description   short prose description (from {@code description:}).
     * @param location      absolute path to the SKILL.md file on disk.
     * @param tools         JClaw tool names this skill consumes (exec, filesystem,
     *                      browser, …). See {@code commands} below for the
     *                      shell-allowlist counterpart.
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
     * @param icon           icon identifier from the {@code icon:} frontmatter key (rendered
     *                      next to the skill in the UI). Empty string when unset.
     * @param mcpServers    JCLAW-281: MCP server dependencies declared via the
     *                      {@code mcp_servers:} frontmatter key. A skill that uses Jira via
     *                      the {@code jira-confluence} MCP server declares
     *                      {@code mcp_servers: [jira-confluence]} once instead of listing
     *                      every Jira action by name in {@code tools}. Empty when the skill
     *                      predates the field or doesn't need any MCP server.
     */
    public record SkillInfo(String name, String description, Path location,
                            List<String> tools, boolean toolsDeclared, String version,
                            List<String> commands, String author, String icon,
                            List<String> mcpServers) {
        public SkillInfo(String name, String description, Path location) {
            this(name, description, location, List.of(), false, DEFAULT_SKILL_VERSION, List.of(), "", "", List.of());
        }

        /** Backwards-compatible 6-arg constructor predating the {@code commands}/{@code author}/{@code icon}/{@code mcpServers} fields. */
        public SkillInfo(String name, String description, Path location,
                         List<String> tools, boolean toolsDeclared, String version) {
            this(name, description, location, tools, toolsDeclared, version, List.of(), "", "", List.of());
        }
    }

    /** Emoji substituted in user-visible skill listings when a SKILL.md omits the {@code icon:} frontmatter key. */
    public static final String DEFAULT_SKILL_ICON = "🎯";

    /** Per-skill markdown manifest filename — the metadata + instructions file at the root of each skill directory. */
    private static final String SKILL_MD_FILENAME = "SKILL.md";
    /** Default semver returned for a skill that omits the {@code version:} frontmatter key. */
    private static final String DEFAULT_SKILL_VERSION = "0.0.0";
    /** Shared value for the skills cache name, the EventLogger category, the workspace/global subdirectory name, and the config-property default. */
    private static final String SKILLS = "skills";
    /** YAML key (also used as the {@code <tools>} XML wrapper) listing JClaw tools a skill consumes. */
    private static final String KEY_TOOLS = "tools";
    /** YAML key holding the skill's human-readable description. */
    private static final String KEY_DESCRIPTION = "description";
    /** YAML key holding the skill's semver version. */
    private static final String KEY_VERSION = "version";
    /** YAML key listing slash-commands the skill registers. */
    private static final String KEY_COMMANDS = "commands";
    /** YAML key (JCLAW-281) listing MCP server dependencies the skill needs. */
    private static final String KEY_MCP_SERVERS = "mcp_servers";

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

    private static final Cache<String, List<SkillInfo>> skillCache = Caches.named(
            SKILLS,
            CacheConfig.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(30))
                    .maximumSize(100)
                    .build());

    private static final int MAX_SKILLS = 150;
    private static final int MAX_SKILLS_CHARS = 30_000;
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);

    public static void clearCache() {
        skillCache.invalidateAll();
    }

    /**
     * Remove AgentSkillConfig records that reference skills no longer on disk.
     * Called at startup to sync DB with the actual skills/ directory.
     */
    public static void syncSkillConfigs() {
        // Collect all skill names that exist on disk (global + all agent workspaces)
        var existingSkills = new HashSet<String>();
        collectSkillNamesUnder(globalSkillsPath(), existingSkills);
        collectWorkspaceSkillNames(AgentService.workspaceRoot(), existingSkills);
        removeOrphanedSkillConfigs(existingSkills);
    }

    /**
     * Add the {@code name()} of every {@code SKILL.md}-bearing immediate
     * subdirectory under {@code dir} to {@code out}. No-op when {@code dir}
     * isn't a directory.
     */
    private static void collectSkillNamesUnder(Path dir, HashSet<String> out) {
        if (!Files.isDirectory(dir)) return;
        try (var dirs = Files.list(dir)) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                var skillFile = d.resolve(SKILL_MD_FILENAME);
                if (Files.exists(skillFile)) {
                    var info = parseSkillFile(skillFile);
                    if (info != null) out.add(info.name());
                }
            });
        } catch (IOException e) {
            EventLogger.warn(SKILLS, "Failed to scan skills directory %s: %s"
                    .formatted(dir, e.getMessage()));
        }
    }

    /**
     * Walk {@code workspaceRoot}'s agent directories and collect skill
     * names from each agent's {@code skills/} subdirectory.
     */
    private static void collectWorkspaceSkillNames(Path workspaceRoot, HashSet<String> out) {
        if (!Files.isDirectory(workspaceRoot)) return;
        try (var agents = Files.list(workspaceRoot)) {
            agents.filter(Files::isDirectory).forEach(agentDir ->
                    collectSkillNamesUnder(agentDir.resolve(SKILLS), out));
        } catch (IOException e) {
            EventLogger.warn(SKILLS, "Failed to scan workspace root %s: %s"
                    .formatted(workspaceRoot, e.getMessage()));
        }
    }

    /** Delete every {@link AgentSkillConfig} whose skill no longer exists on disk. */
    private static void removeOrphanedSkillConfigs(HashSet<String> existingSkills) {
        Tx.run(() -> {
            List<AgentSkillConfig> allConfigs = AgentSkillConfig.findAll();
            int removed = 0;
            for (var config : allConfigs) {
                if (!existingSkills.contains(config.skillName)) {
                    config.delete();
                    removed++;
                }
            }
            if (removed > 0) {
                EventLogger.info(SKILLS, "Removed %d orphaned skill config(s) for deleted skills".formatted(removed));
            }
        });
    }

    public static List<SkillInfo> loadSkills(String agentName) {
        return skillCache.get(agentName, SkillLoader::loadSkillsFromDisk);
    }

    private static List<SkillInfo> loadSkillsFromDisk(String agentName) {
        var allSkills = new ArrayList<SkillInfo>();

        // Only scan agent workspace skills — global skills must be explicitly copied to agents
        var workspaceDir = AgentService.workspacePath(agentName);
        var agentDir = workspaceDir.resolve(SKILLS);
        scanSkillsDirectory(agentDir, allSkills);

        relativizeSkillLocations(allSkills, workspaceDir);
        applyAgentPermissionFilters(allSkills, agentName);
        if (!"main".equalsIgnoreCase(agentName)) {
            filterOutdatedSkillCreator(allSkills, agentName);
        }

        // Sort by name for deterministic ordering — the skills block is part of the
        // LLM prompt prefix, and filesystem iteration order is not stable.
        return allSkills.stream()
                .sorted(Comparator.comparing(SkillInfo::name))
                .limit(MAX_SKILLS)
                .toList();
    }

    /**
     * Make locations relative to the agent's workspace (readFile tool
     * resolves relative to workspace). Preserves every field on the
     * record — earlier truncated constructors silently defaulted later
     * fields to empty, which is how JCLAW-71's icon field leaked back
     * to "" after parsing. JCLAW-281 adds mcpServers as the tenth field.
     */
    private static void relativizeSkillLocations(List<SkillInfo> allSkills, Path workspaceDir) {
        allSkills.replaceAll(s -> {
            if (s.location() != null && s.location().startsWith(workspaceDir)) {
                return new SkillInfo(s.name(), s.description(), workspaceDir.relativize(s.location()),
                        s.tools(), s.toolsDeclared(), s.version(),
                        s.commands(), s.author(), s.icon(), s.mcpServers());
            }
            return s;
        });
    }

    /**
     * Filter by permissions + validate tool requirements (JPA access —
     * may be called from tool execution outside a request thread, so
     * wrap in a Tx).
     */
    private static void applyAgentPermissionFilters(List<SkillInfo> allSkills, String agentName) {
        Tx.run(() -> {
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
            allSkills.removeIf(s -> skillMissingTools(s, disabledTools, agentName));
        });
    }

    /**
     * True when the skill declares tool requirements that aren't all
     * available to {@code agentName} — used as the {@code removeIf}
     * predicate by {@link #applyAgentPermissionFilters}.
     */
    private static boolean skillMissingTools(SkillInfo s, Set<String> disabledTools, String agentName) {
        if (s.tools() == null || s.tools().isEmpty()) return false;
        var result = ToolCatalog.validateSkillTools(disabledTools, s.tools());
        if (!result.isOk()) {
            EventLogger.warn(SKILLS, "Excluding skill '%s' from agent '%s': missing tools %s"
                    .formatted(s.name(), agentName, result.missing()));
            return true;
        }
        return false;
    }

    /**
     * Non-main agents may not use an out-of-date skill-creator. If the
     * global skill-creator exists and is newer than this agent's
     * workspace copy, hide the workspace copy from
     * {@code <available_skills>} so the LLM cannot invoke it. The user
     * must explicitly update by dragging skill-creator from the global
     * registry onto the agent card.
     */
    private static void filterOutdatedSkillCreator(List<SkillInfo> allSkills, String agentName) {
        var globalSkillCreatorPath = globalSkillsPath().resolve("skill-creator").resolve(SKILL_MD_FILENAME);
        if (!Files.exists(globalSkillCreatorPath)) return;
        var globalSkillCreator = parseSkillFile(globalSkillCreatorPath);
        if (globalSkillCreator == null) return;
        var globalVersion = globalSkillCreator.version();
        allSkills.removeIf(s -> {
            if (!"skill-creator".equals(s.name())) return false;
            if (compareVersions(s.version(), globalVersion) < 0) {
                EventLogger.warn(SKILLS,
                        "Excluding out-of-date skill-creator for agent '%s': workspace v%s < global v%s (drag skill-creator from global to update)"
                                .formatted(agentName, s.version(), globalVersion));
                return true;
            }
            return false;
        });
    }

    private static void scanSkillsDirectory(Path skillsDir, List<SkillInfo> skills) {
        if (!Files.isDirectory(skillsDir)) return;
        try (var dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                var skillFile = dir.resolve(SKILL_MD_FILENAME);
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
        return Path.of(Play.configuration.getProperty("jclaw.skills.path", SKILLS));
    }

    /**
     * Format skills as XML for injection into system prompt.
     */
    public static String formatSkillsXml(List<SkillInfo> skills) {
        if (skills.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("<available_skills>\n");

        int totalChars = 0;

        for (var skill : skills) {
            var entry = formatSkillEntry(skill, true);
            if (totalChars + entry.length() > MAX_SKILLS_CHARS) {
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
    public static final String SKILL_MATCHING_INSTRUCTIONS = """
            ## Skills (mandatory)
            Before replying: scan <available_skills> <description> entries.
            - If exactly one skill clearly applies: read its SKILL.md at <location> with the readFile tool, then follow it.
            - If multiple could apply: choose the most specific one, then read/follow it.
            - If platform-specific variants exist (e.g., mac vs linux): select the variant matching the current platform from the Environment section.
            - If the request involves multiple skills: select the skill matching the primary action (the first or most important verb), not secondary follow-up actions.
            - Match by intent, not exact wording. A skill applies when the goal falls within the skill's domain, even if the description includes details the request did not mention. Example: a request to "recommend a restaurant" matches a skill described as "restaurants in City X" — the skill's specifics are implementation details, not prerequisites.
            - If none clearly apply: do not read any SKILL.md.
            - **Skill authoring is always routed through skill-creator.** If you're asked to create, update, modify, edit, rename, refactor, fix, or change a skill (anything under `skills/<name>/`), the applicable skill is `skill-creator` — regardless of whether it's mentioned by name. Read `skill-creator`'s SKILL.md first and follow its workflow. Never edit files under `skills/<name>/` directly via the filesystem tool without going through skill-creator.
            - **When you're asked what skills you have:** render a markdown table with header `| Skill | Description |`, one row per skill, where the Skill cell is `<icon> **<name>**` (icon from `<available_skills>` `<icon>` element) and the Description cell is the skill's `<description>`.
            - **When you're asked what tools you have:** present them grouped by the `###` category headings in the Tool Catalog (System / Files / Web / Utilities), each category followed by its own `| Tool | Purpose |` markdown table. Do not mention tools that are not in the Tool Catalog — those are internal and should stay hidden from your answer.
            Constraints: never read more than one skill up front; only read after selecting.
            """;

    // --- Internal ---

    /** Matches a top-level {@code tools:} key in YAML frontmatter (inline or block form). */
    private static final Pattern TOOLS_KEY_PRESENT = Pattern.compile("(?m)^tools:");

    // Pre-compiled YAML extraction patterns — keys come from a closed set
    private static final Pattern NAME_VALUE = Pattern.compile("^name:\\s*[\"']?(.*?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern DESCRIPTION_VALUE = Pattern.compile("^description:\\s*[\"']?(.*?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern TOOLS_VALUE = Pattern.compile("^tools:\\s*[\"']?(.*?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern VERSION_VALUE = Pattern.compile("^version:\\s*[\"']?(.*?)[\"']?\\s*$", Pattern.MULTILINE);
    // S5998: possessive `++` prevents catastrophic backtracking on pathological YAML
    // input. Equivalent for any valid frontmatter; just refuses to backtrack.
    private static final Pattern NAME_MULTI = Pattern.compile("^name:\\s*[|>]\\s*\\n((?: {2}.*\\n?)++)", Pattern.MULTILINE);
    private static final Pattern DESCRIPTION_MULTI = Pattern.compile("^description:\\s*[|>]\\s*\\n((?: {2}.*\\n?)++)", Pattern.MULTILINE);
    private static final Pattern TOOLS_MULTI = Pattern.compile("^tools:\\s*[|>]\\s*\\n((?: {2}.*\\n?)++)", Pattern.MULTILINE);
    private static final Pattern VERSION_MULTI = Pattern.compile("^version:\\s*[|>]\\s*\\n((?: {2}.*\\n?)++)", Pattern.MULTILINE);
    private static final Pattern TOOLS_INLINE_LIST = Pattern.compile("^tools:\\s*\\[(.*?)\\]\\s*$", Pattern.MULTILINE);
    private static final Pattern TOOLS_BLOCK_LIST = Pattern.compile("^tools:\\s*\\n((?:\\s*-\\s*.*\\n?)++)", Pattern.MULTILINE);
    private static final Pattern COMMANDS_INLINE_LIST = Pattern.compile("^commands:\\s*\\[(.*?)\\]\\s*$", Pattern.MULTILINE);
    private static final Pattern COMMANDS_BLOCK_LIST = Pattern.compile("^commands:\\s*\\n((?:\\s*-\\s*.*\\n?)++)", Pattern.MULTILINE);
    private static final Pattern MCP_SERVERS_INLINE_LIST = Pattern.compile("^mcp_servers:\\s*\\[(.*?)\\]\\s*$", Pattern.MULTILINE);
    private static final Pattern MCP_SERVERS_BLOCK_LIST = Pattern.compile("^mcp_servers:\\s*\\n((?:\\s*-\\s*.*\\n?)++)", Pattern.MULTILINE);

    public static SkillInfo parseSkillFile(Path path) {
        try {
            var content = Files.readString(path);
            var info = parseSkillContent(content, path);
            if (info != null) return info;
            // Fallback: use directory name
            return new SkillInfo(path.getParent().getFileName().toString(), "", path, List.of(), false, DEFAULT_SKILL_VERSION);
        } catch (IOException _) {
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
        if (!matcher.find()) return null;
        var frontmatter = matcher.group(1);
        var name = extractYamlValue(frontmatter, "name");
        if (name == null) return null;
        var description = extractYamlValue(frontmatter, KEY_DESCRIPTION);
        var version = extractYamlValue(frontmatter, KEY_VERSION);
        var author = extractYamlValue(frontmatter, "author");
        var icon = extractYamlValue(frontmatter, "icon");
        // JCLAW-281: MCP server dependencies (sibling to tools:). Empty
        // list when the key is absent — extractYamlList returns List.of()
        // for that case, which matches the "no servers needed" intent.
        return new SkillInfo(name,
                description != null ? description : "",
                locationHint,
                extractYamlList(frontmatter, KEY_TOOLS),
                TOOLS_KEY_PRESENT.matcher(frontmatter).find(),
                version != null ? version : DEFAULT_SKILL_VERSION,
                extractYamlList(frontmatter, KEY_COMMANDS),
                author != null ? author : "",
                icon != null ? icon : "",
                extractYamlList(frontmatter, KEY_MCP_SERVERS));
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
        // Inline form: key: [a, b, c]. Pre-compiled for the known closed-set
        // keys; only an unrecognised custom key pays the per-call compile.
        var inlinePattern = switch (key) {
            case KEY_TOOLS -> TOOLS_INLINE_LIST;
            case KEY_COMMANDS -> COMMANDS_INLINE_LIST;
            case KEY_MCP_SERVERS -> MCP_SERVERS_INLINE_LIST;
            default -> Pattern.compile("^" + Pattern.quote(key) + ":\\s*\\[(.*?)\\]\\s*$", Pattern.MULTILINE);
        };
        var inlineMatcher = inlinePattern.matcher(yaml);
        if (inlineMatcher.find()) return parseInlineYamlList(inlineMatcher.group(1));

        // Block form: key:\n  - a\n  - b
        var blockPattern = switch (key) {
            case KEY_TOOLS -> TOOLS_BLOCK_LIST;
            case KEY_COMMANDS -> COMMANDS_BLOCK_LIST;
            case KEY_MCP_SERVERS -> MCP_SERVERS_BLOCK_LIST;
            default -> Pattern.compile("^" + Pattern.quote(key) + ":\\s*\\n((?:\\s*-\\s*.*\\n?)+)", Pattern.MULTILINE);
        };
        var blockMatcher = blockPattern.matcher(yaml);
        if (blockMatcher.find()) return parseBlockYamlList(blockMatcher.group(1));

        return List.of();
    }

    /** Parse a YAML inline-list body (the contents inside {@code [...]}). */
    private static List<String> parseInlineYamlList(String body) {
        var items = body.strip();
        if (items.isEmpty()) return List.of();
        var result = new ArrayList<String>();
        for (var part : items.split(",")) {
            var cleaned = part.strip().replaceAll("(^[\"'])|([\"']$)", "");
            if (!cleaned.isEmpty()) result.add(cleaned);
        }
        return result;
    }

    /** Parse a YAML block-list body (one {@code "- item"} per line). */
    private static List<String> parseBlockYamlList(String body) {
        var result = new ArrayList<String>();
        for (var line : body.split("\\n")) {
            var trimmed = line.strip();
            if (!trimmed.startsWith("-")) continue;
            var item = trimmed.substring(1).strip().replaceAll("(^[\"'])|([\"']$)", "");
            if (!item.isEmpty()) result.add(item);
        }
        return result;
    }

    // --- Text classification ---

    /** Extensions treated as human-readable text for skill content classification. */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".md", ".json", ".txt", ".yaml", ".yml", ".xml", ".sh", ".py", ".js",
            ".ts", ".java", ".html", ".css", ".toml", ".ini", ".cfg", ".conf", ".env",
            ".properties", ".rb", ".go", ".rs", ".lua", ".sql"
    );

    /** Extensionless filenames that are conventionally plain text. */
    private static final Set<String> KNOWN_TEXT_FILES = Set.of(
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
            case KEY_DESCRIPTION -> DESCRIPTION_VALUE;
            case KEY_TOOLS -> TOOLS_VALUE;
            case KEY_VERSION -> VERSION_VALUE;
            default -> Pattern.compile("^" + Pattern.quote(key) + ":\\s*[\"']?(.*?)[\"']?\\s*$", Pattern.MULTILINE);
        };
        var matcher = valuePattern.matcher(yaml);
        if (matcher.find()) {
            var value = matcher.group(1).strip();
            return value.isEmpty() ? null : value;
        }
        var multiPattern = switch (key) {
            case "name" -> NAME_MULTI;
            case KEY_DESCRIPTION -> DESCRIPTION_MULTI;
            case KEY_TOOLS -> TOOLS_MULTI;
            case KEY_VERSION -> VERSION_MULTI;
            default -> Pattern.compile("^" + Pattern.quote(key) + ":\\s*[|>]\\s*\\n((?: {2}.*\\n?)+)", Pattern.MULTILINE);
        };
        var multiMatcher = multiPattern.matcher(yaml);
        if (multiMatcher.find()) {
            return multiMatcher.group(1).replaceAll("(?m)^ {2}", "").strip();
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
        // JCLAW-281: surface MCP server dependencies as a peer category to
        // tools. A skill that needs Jira declares mcp_servers: [jira-confluence]
        // once; the model uses this to decide whether the skill is reachable
        // given the connected servers, parallel to how it reads <tools> for
        // native tool requirements.
        if (skill.mcpServers() != null && !skill.mcpServers().isEmpty()) {
            sb.append("    <mcp_servers>")
              .append(String.join(", ", skill.mcpServers()))
              .append("</mcp_servers>\n");
        }
        sb.append("  </skill>\n");
        return sb.toString();
    }
}
