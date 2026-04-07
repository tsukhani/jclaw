package agents;

import services.AgentService;
import services.EventLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans workspace/{agentId}/skills/SKILL.md files, parses YAML frontmatter,
 * returns skill metadata for system prompt injection.
 */
public class SkillLoader {

    public record SkillInfo(String name, String description, Path location) {}

    private static final java.util.concurrent.ConcurrentHashMap<String, CachedSkills> skillCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30_000;

    private record CachedSkills(List<SkillInfo> skills, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static final int MAX_SKILLS = 150;
    private static final int MAX_SKILLS_CHARS = 30_000;
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);

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
        var skillsDir = AgentService.workspacePath(agentName).resolve("skills");
        if (!Files.isDirectory(skillsDir)) {
            return List.of();
        }

        var skills = new ArrayList<SkillInfo>();
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
            EventLogger.warn("agent", "Failed to scan skills for %s: %s"
                    .formatted(agentName, e.getMessage()));
        }

        return skills.stream().limit(MAX_SKILLS).toList();
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
                // Switch to compact format
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

    private static SkillInfo parseSkillFile(Path path) {
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
        // Simple single-line YAML value extraction (handles key: value and key: "value")
        var pattern = Pattern.compile("^" + Pattern.quote(key) + ":\\s*[\"']?(.*?)[\"']?\\s*$",
                Pattern.MULTILINE);
        var matcher = pattern.matcher(yaml);
        if (matcher.find()) {
            var value = matcher.group(1).trim();
            return value.isEmpty() ? null : value;
        }
        // Handle multiline value with | or >
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
                    """.formatted(skill.name(), skill.description(), skill.location());
        }
        return """
                  <skill>
                    <name>%s</name>
                    <location>%s</location>
                  </skill>
                """.formatted(skill.name(), skill.location());
    }
}
