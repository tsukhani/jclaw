package services;

import agents.SkillFrontmatter;
import agents.SkillLoader;
import agents.SkillSpec;
import agents.SkillVersionManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a global skill as the zip Claude accepts: one folder named after the skill, its SKILL.md in
 * the Agent Skills layout and checked by {@link SkillSpec}, every other file as it is. Credential
 * values never leave: a JSON file under {@code credentials/} ships with its values masked, any other
 * file there stays behind, and so does every symbolic link.
 */
public final class SkillExporter {

    private static final String SKILL_MD = "SKILL.md";
    private static final String CREDENTIALS_DIR = "credentials/";
    private static final int MAX_COMPATIBILITY = 500;
    /** What {@link SkillLoader} reports for a skill that declares no version. */
    private static final String UNDECLARED_VERSION = "0.0.0";

    private SkillExporter() {}

    public record Export(String name, byte[] zip) {}

    /** The skill would fail Anthropic's validator; {@link #problems} says why. */
    public static final class NotExportable extends Exception {
        private final List<String> problems;
        private final String summary;

        NotExportable(List<String> problems) {
            this.problems = List.copyOf(problems);
            this.summary = "Claude would not accept this skill: " + String.join("; ", problems);
        }

        public List<String> problems() { return problems; }

        @Override
        public String getMessage() { return summary; }
    }

    public static Export export(Path skillDir) throws IOException, NotExportable {
        var skillMd = exportedSkillMd(Files.readString(skillDir.resolve(SKILL_MD)));
        var frontmatter = SkillFrontmatter.of(skillMd);
        var name = frontmatter != null ? frontmatter.name() : null;
        var problems = SkillSpec.problems(skillMd, name);
        if (name == null || !problems.isEmpty()) throw new NotExportable(problems);

        List<Path> files;
        try (var walk = Files.walk(skillDir)) {
            files = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            put(zip, name + "/" + SKILL_MD, skillMd.getBytes(StandardCharsets.UTF_8));
            for (var file : files) {
                var relative = skillDir.relativize(file).toString().replace(File.separatorChar, '/');
                if (relative.equals(SKILL_MD)) continue;
                if (relative.startsWith(CREDENTIALS_DIR)) {
                    if (!relative.toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                    put(zip, name + "/" + relative, SkillPromotionService.stripCredentialsJson(Files.readString(file))
                            .getBytes(StandardCharsets.UTF_8));
                } else {
                    put(zip, name + "/" + relative, Files.readAllBytes(file));
                }
            }
        }
        return new Export(name, out.toByteArray());
    }

    /**
     * The SKILL.md as it leaves: in the spec layout whichever layout it is stored in, and, when its
     * instructions call JClaw tools by name and it states no {@code compatibility} of its own,
     * saying so there, since Claude has no tool of that name.
     */
    static String exportedSkillMd(String content) {
        var split = SkillVersionManager.splitFrontmatter(content);
        var body = split.body() != null ? split.body() : "";
        var frontmatter = SkillFrontmatter.of(content);
        if (frontmatter == null) frontmatter = fromLineReader(content);
        if (frontmatter.get("compatibility") == null) {
            var called = calledTools(frontmatter.tools(), body);
            var note = "Written for JClaw; its instructions call these JClaw tools: " + String.join(", ", called);
            if (!called.isEmpty() && note.length() <= MAX_COMPATIBILITY) frontmatter.put("compatibility", note);
        }
        return frontmatter.render() + body;
    }

    /** The declared tools the body names in backticks, as skill-creator has a skill name them. */
    private static List<String> calledTools(List<String> tools, String body) {
        var called = new ArrayList<String>();
        for (var tool : tools) {
            if (body.contains("`" + tool + "`")) called.add(tool);
        }
        return called;
    }

    /** A frontmatter YAML refuses, rebuilt from what the line reader makes of it. */
    private static SkillFrontmatter fromLineReader(String content) {
        var frontmatter = SkillFrontmatter.empty();
        var info = SkillLoader.parseSkillContent(content, Path.of(SKILL_MD));
        if (info == null) return frontmatter;
        frontmatter.put(SkillFrontmatter.NAME, info.name()).put(SkillFrontmatter.DESCRIPTION, info.description())
                .setVersion(info.version().equals(UNDECLARED_VERSION) ? null : info.version())
                .putMetadata(SkillFrontmatter.AUTHOR, info.author().isBlank() ? null : info.author())
                .putMetadata(SkillFrontmatter.ICON, info.icon().isBlank() ? null : info.icon());
        if (info.toolsDeclared()) frontmatter.setTools(words(info.tools()));
        if (!info.commands().isEmpty()) frontmatter.setCommands(words(info.commands()));
        if (!info.mcpServers().isEmpty()) frontmatter.setMcpServers(words(info.mcpServers()));
        return frontmatter;
    }

    private static List<String> words(List<String> values) {
        return values.stream().filter(SkillFrontmatter::isWord).toList();
    }

    private static void put(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }
}
