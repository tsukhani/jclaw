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

    public record SkillInfo(String name, String description, Path location) {}

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

        // 1. Scan global skills directory
        var globalDir = globalSkillsPath();
        scanSkillsDirectory(globalDir, allSkills);

        // 2. Scan agent-specific skills directory
        var agentDir = AgentService.workspacePath(agentName).resolve("skills");
        scanSkillsDirectory(agentDir, allSkills);

        // 3. Filter by permissions
        var agent = Agent.findByName(agentName);
        if (agent != null) {
            var configs = AgentSkillConfig.findByAgent(agent);
            var disabledSkills = new HashSet<String>();
            for (var c : configs) {
                if (!c.enabled) disabledSkills.add(c.skillName);
            }
            if (!disabledSkills.isEmpty()) {
                allSkills.removeIf(s -> disabledSkills.contains(s.name()));
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
                - If none clearly apply: do not read any SKILL.md.
                Constraints: never read more than one skill up front; only read after selecting.
                """;
    }

    // --- Internal ---

    public static SkillInfo parseSkillFile(Path path) {
        try {
            var content = Files.readString(path);
            var matcher = FRONTMATTER_PATTERN.matcher(content);
            if (matcher.find()) {
                var frontmatter = matcher.group(1);
                var name = extractYamlValue(frontmatter, "name");
                var description = extractYamlValue(frontmatter, "description");
                if (name != null) {
                    return new SkillInfo(name, description != null ? description : "", path);
                }
            }
            // Fallback: use directory name
            return new SkillInfo(path.getParent().getFileName().toString(), "", path);
        } catch (IOException e) {
            return null;
        }
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
        if (full) {
            return """
                      <skill>
                        <name>%s</name>
                        <description>%s</description>
                        <location>%s</location>
                      </skill>
                    """.formatted(skill.name(), skill.description(),
                    skill.location() != null ? skill.location() : "");
        }
        return """
                  <skill>
                    <name>%s</name>
                    <location>%s</location>
                  </skill>
                """.formatted(skill.name(),
                skill.location() != null ? skill.location() : "");
    }
}
