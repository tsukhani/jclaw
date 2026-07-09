package tools;

import agents.SkillLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Write/create family for {@link FileSystemTools}: {@code writeFile} (the single sink through
 * which all content-producing actions land on disk, so the SKILL.md version-bump pipeline
 * fires exactly once) and {@code appendFile}. {@link #writeFile} is shared by the edit and
 * patch families for that reason.
 */
final class FsWriter {

    private FsWriter() {}

    static String writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());

            // Deterministic version handling for skill definitions: any write to
            // workspace/{agent}/skills/{skill-name}/SKILL.md is routed through
            // SkillLoader.finalizeSkillMdWrite, which auto-bumps the patch version on
            // material changes and ignores whatever the LLM wrote in the version: field.
            String finalContent = content;
            String versionNote = "";
            if (isSkillDefinitionFile(path)) {
                var previousInfo = Files.exists(path) ? SkillLoader.parseSkillFile(path) : null;
                finalContent = SkillLoader.finalizeSkillMdWrite(path, content);
                var newInfo = SkillLoader.parseSkillContent(finalContent, path);
                if (newInfo != null) {
                    if (previousInfo == null) {
                        versionNote = " (new skill at version " + newInfo.version() + ")";
                    } else if (!previousInfo.version().equals(newInfo.version())) {
                        versionNote = " (version bumped " + previousInfo.version()
                                + " → " + newInfo.version() + ")";
                    } else {
                        versionNote = " (no material change; version " + newInfo.version() + " preserved)";
                    }
                }
            }

            Files.writeString(path, finalContent);
            return "File written successfully: " + path.getFileName() + versionNote;
        } catch (IOException e) {
            return "Error writing file: %s".formatted(e.getMessage());
        }
    }

    /**
     * Append content to the tail of an existing file, or create it if missing.
     * Primary use case is letting the LLM build up a large file across multiple
     * tool calls when a single {@code writeFile} would exceed its output token
     * budget. Skill definition files route through {@code writeFile} for their
     * version-bump pipeline, so appending to a SKILL.md is explicitly rejected —
     * an append is ambiguous against the version-management semantics and the
     * LLM should use writeFile (or editFile) for skill authoring.
     */
    static String appendFile(Path path, String content) {
        try {
            if (isSkillDefinitionFile(path)) {
                return "Error: appendFile is not supported for SKILL.md files. "
                        + "Use writeFile for a full replacement (version bumps are handled automatically) "
                        + "or editFile to patch specific sections.";
            }
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                Files.writeString(path, content,
                        StandardOpenOption.APPEND);
                long size = Files.size(path);
                return "Appended %d chars to %s (total %d bytes)"
                        .formatted(content.length(), path.getFileName(), size);
            }
            Files.writeString(path, content);
            return "File created (appendFile on missing file): " + path.getFileName()
                    + " (" + content.length() + " chars)";
        } catch (IOException e) {
            return "Error appending to file: %s".formatted(e.getMessage());
        }
    }

    /**
     * True when {@code path} points at a SKILL.md directly inside a skill folder —
     * i.e., the path ends in {@code .../skills/{skillName}/SKILL.md}. Used to scope
     * the deterministic version bump logic to actual skill definition writes.
     */
    private static boolean isSkillDefinitionFile(Path path) {
        if (!"SKILL.md".equals(path.getFileName().toString())) return false;
        var parent = path.getParent();
        if (parent == null) return false;
        var grandparent = parent.getParent();
        if (grandparent == null) return false;
        return "skills".equals(grandparent.getFileName().toString());
    }
}
