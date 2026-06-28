package services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Imports a skill from the external catalog into JClaw's global registry:
 * <ol>
 *   <li>Fetch the skill's files into a staging dir, routed by source provider:
 *       {@link GithubSkillFetcher} for the static GitHub dump (mastra),
 *       {@link ClawhubSkillFetcher} for the dynamic ClawHub registry;</li>
 *   <li>{@link SkillConformanceService} rewrites it to the skill-creator contract
 *       (tool-name mapping, frontmatter normalization) and REJECTS anything that
 *       still can't conform;</li>
 *   <li>{@link SkillPromotionService#publishToGlobal} runs the shared malware
 *       scan + secret sanitization + atomic write to the global registry — the
 *       exact path agent-workspace promotion uses.</li>
 * </ol>
 * The fetch is the only provider-specific stage; conform → scan → sanitize →
 * publish is shared. The operator chose direct-to-global, so a successful import
 * is live immediately; the conformance gate + scan + sanitize are the safety
 * stack standing in for the human review that workspace promotion would provide.
 */
public final class RegistrySkillImporter {

    private static final String CATEGORY = "skills";
    /** Built-in skills that must never be overwritten by an import. */
    private static final Set<String> RESERVED = Set.of("skill-creator", "jclaw-api");

    private RegistrySkillImporter() {}

    public record ImportResult(boolean ok, String skillName, String message) {
        static ImportResult fail(String message) { return new ImportResult(false, null, message); }
    }

    /**
     * Import the catalog skill {@code skillId} into the global registry, fetching
     * its files from the source named by {@code provider}: {@code "clawhub"} uses
     * the ClawHub API (slug = skillId); anything else (the GitHub dump) parses
     * {@code source} as {@code owner/repo}. Never throws — failures return
     * {@code ok=false} with a human-readable message.
     */
    public static ImportResult importToGlobal(String provider, String source, String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return ImportResult.fail("missing skill id");
        }

        Path staged;
        try {
            staged = Files.createTempDirectory("jclaw-import-");
        } catch (IOException e) {
            return ImportResult.fail("could not stage import: " + e.getMessage());
        }

        try {
            // Fetch — the only provider-specific stage.
            boolean fetchOk;
            String fetchMsg;
            if ("clawhub".equalsIgnoreCase(provider)) {
                var f = ClawhubSkillFetcher.fetch(skillId, staged);   // skillId == clawhub slug
                fetchOk = f.ok();
                fetchMsg = f.message();
            } else {
                var parts = source == null ? new String[0] : source.split("/");
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    return ImportResult.fail("invalid source '%s' (expected owner/repo)".formatted(source));
                }
                var f = GithubSkillFetcher.fetch(parts[0], parts[1], skillId, staged);
                fetchOk = f.ok();
                fetchMsg = f.message();
            }
            if (!fetchOk) return ImportResult.fail(fetchMsg);

            var conform = SkillConformanceService.conform(staged, skillId, source);
            if (!conform.ok()) return ImportResult.fail(conform.message());

            var name = conform.skillName();
            if (RESERVED.contains(name)) {
                return ImportResult.fail("'%s' is a reserved built-in skill and cannot be imported".formatted(name));
            }

            if (!SkillPromotionService.publishToGlobal(staged, name)) {
                return ImportResult.fail("skill failed the malware scan or could not be written to the registry");
            }

            EventLogger.info(CATEGORY, "Imported skill '%s' from %s (%s)".formatted(name, source, provider));
            return new ImportResult(true, name, "imported");
        } finally {
            try {
                SkillPromotionService.deleteRecursive(staged);
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, "Import: temp cleanup failed: " + e.getMessage());
            }
        }
    }
}
