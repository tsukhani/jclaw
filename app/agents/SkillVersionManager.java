package agents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Skill version lifecycle management: semver comparison, patch bumping,
 * content diffing, frontmatter splitting, and directory hashing.
 *
 * <p>Extracted from {@link SkillLoader} to separate the version/diff concerns
 * from skill loading and caching.
 */
public final class SkillVersionManager {

    private SkillVersionManager() {}

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^version:.*$");

    // --- Semver comparison ---

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
        var parts = v.strip().split("\\.");
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

    // --- Frontmatter splitting ---

    public record FrontmatterSplit(String frontmatter, String body) {}

    public static FrontmatterSplit splitFrontmatter(String content) {
        if (content == null) return new FrontmatterSplit(null, null);
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (matcher.find() && matcher.start() == 0) {
            var end = matcher.end();
            if (end < content.length() && content.charAt(end) == '\n') end++;
            return new FrontmatterSplit(content.substring(0, end), content.substring(end));
        }
        return new FrontmatterSplit(null, content);
    }

    // --- Directory hashing ---

    /**
     * Compute a deterministic SHA-256 hash over every regular file in a skill directory.
     * Files are visited in sorted relative-path order; each file contributes its relative
     * path bytes plus a separator plus its content bytes.
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
                digest.update(rel.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    // --- SKILL.md finalization ---

    /**
     * Deterministically finalize a SKILL.md write inside a skill directory:
     * auto-bump patch on material change, preserve on no-op, inject version
     * if missing. See {@link SkillLoader#finalizeSkillMdWrite} for full contract.
     */
    public static String finalizeSkillMdWrite(Path targetPath, String newContent) {
        if (newContent == null) newContent = "";
        try {
            var llmVersion = extractExplicitVersion(newContent);

            if (!Files.exists(targetPath)) {
                var resolved = resolveVersion(llmVersion, "1.0.0");
                return ensureVersionInFrontmatter(newContent, resolved);
            }

            var oldContent = Files.readString(targetPath);
            var oldInfo = parseFrontmatterStringForVersion(oldContent);
            var oldVersion = oldInfo[0];

            var autoVersion = contentDiffersIgnoringVersion(oldContent, newContent)
                    ? bumpPatch(oldVersion)
                    : oldVersion;
            var resolved = resolveVersion(llmVersion, autoVersion);
            return ensureVersionInFrontmatter(newContent, resolved);
        } catch (IOException e) {
            return newContent;
        }
    }

    /**
     * Return the version the LLM wrote in the new content's frontmatter, or {@code null}
     * if no {@code version:} line is present.
     */
    static String extractExplicitVersion(String content) {
        if (content == null) return null;
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find() || matcher.start() != 0) return null;
        var frontmatter = matcher.group(1);
        return SkillLoader.extractYamlValue(frontmatter, "version");
    }

    /**
     * Choose between an LLM-supplied version and the automatic target. The LLM wins
     * only when it writes a value that is a strict upgrade over the automatic target.
     */
    static String resolveVersion(String llmVersion, String autoVersion) {
        if (llmVersion == null || llmVersion.isBlank()) return autoVersion;
        return compareVersions(llmVersion, autoVersion) > 0 ? llmVersion : autoVersion;
    }

    static String[] parseFrontmatterStringForVersion(String content) {
        if (content == null) return new String[]{"0.0.0"};
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (matcher.find() && matcher.start() == 0) {
            var frontmatter = matcher.group(1);
            var version = SkillLoader.extractYamlValue(frontmatter, "version");
            return new String[]{version != null ? version : "0.0.0"};
        }
        return new String[]{"0.0.0"};
    }

    /**
     * True if the two SKILL.md strings differ in any way other than the {@code version:}
     * frontmatter line.
     */
    public static boolean contentDiffersIgnoringVersion(String a, String b) {
        return !stripVersionLine(a).equals(stripVersionLine(b));
    }

    private static String stripVersionLine(String content) {
        if (content == null) return "";
        return VERSION_LINE.matcher(content).replaceAll("").replaceAll("\\n\\n+", "\n\n");
    }

    /**
     * Ensure the frontmatter of {@code content} has a {@code version:} line with the given
     * value. Inserts or overwrites as needed.
     */
    static String ensureVersionInFrontmatter(String content, String targetVersion) {
        if (content == null) content = "";
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find() || matcher.start() != 0) {
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
                newFrontmatter = frontmatter;
            }
        } else {
            var v = targetVersion != null ? targetVersion : "1.0.0";
            if (frontmatter.contains("description:")) {
                newFrontmatter = frontmatter.replaceFirst("(?m)(^description:.*$)", "$1\nversion: " + v);
            } else {
                newFrontmatter = frontmatter.stripTrailing() + "\nversion: " + v;
            }
        }
        return before + newFrontmatter + after;
    }
}
