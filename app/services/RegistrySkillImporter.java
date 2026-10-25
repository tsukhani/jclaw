package services;

import agents.SkillFrontmatter;
import agents.SkillLoader;
import org.jspecify.annotations.Nullable;
import services.scanners.Scanner;
import services.scanners.ScannerRegistry;
import utils.SecretRedactor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
 *       path agent-workspace promotion uses, except that an import keeps its file
 *       layout: its files are public, so there is no agent secret to sort out.</li>
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
    /** How many unscanned files a warning names before it counts the rest. */
    private static final int NAMED_FILES = 5;

    private RegistrySkillImporter() {}

    /** EXISTS: an upload would replace a library skill, and waits for the operator to confirm. */
    public enum Outcome { IMPORTED, FAILED, EXISTS }

    /**
     * @param flaggedFiles files of an uploaded skill, outside {@code credentials/}, that the secret
     *                     detector flags: they stay readable to every agent, so the operator is told
     * @param warnings     what the operator should know about a skill that did import, in words
     */
    public record ImportResult(Outcome outcome, @Nullable String skillName, @Nullable String message,
                               List<String> flaggedFiles, List<String> warnings) {

        public boolean ok() { return outcome == Outcome.IMPORTED; }

        static ImportResult fail(@Nullable String message) {
            return new ImportResult(Outcome.FAILED, null, message, List.of(), List.of());
        }

        static ImportResult imported(String name, List<String> flaggedFiles, List<String> warnings) {
            return new ImportResult(Outcome.IMPORTED, name, "imported", flaggedFiles, warnings);
        }
    }

    /**
     * Import the catalog skill {@code skillId} into the global registry, fetching
     * its files from the source named by {@code provider}: {@code "clawhub"} uses
     * the ClawHub API (slug = skillId, owner-qualified by {@code owner} since
     * clawhub slugs aren't globally unique); anything else (the GitHub dump)
     * parses {@code source} as {@code owner/repo}. Never throws — failures return
     * {@code ok=false} with a human-readable message.
     */
    public static ImportResult importToGlobal(@Nullable String provider, String source,
                                             String skillId, @Nullable String owner) {
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
                var f = ClawhubSkillFetcher.fetch(skillId, owner, staged);   // skillId == clawhub slug
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

            return conformAndPublish(staged, skillId, source, "%s (%s)".formatted(source, provider), null);
        } finally {
            try {
                SkillPromotionService.deleteRecursive(staged);
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, "Import: temp cleanup failed: " + e.getMessage());
            }
        }
    }

    /**
     * Import an uploaded skill zip: JClaw's export, or a skill built for Claude. It runs the catalog
     * import's checks and keeps its file layout, and the result names any file outside
     * {@code credentials/} that looks like it holds a credential. Never throws.
     *
     * <p>Replacing a library skill of the same name takes {@code replace}, and an upload that
     * declares a version older than the library's is refused even with it, as promotion refuses
     * one: the version manager would otherwise stamp the older content with a higher number.
     */
    public static ImportResult importZip(Path zip, String fileName, boolean replace) {
        Path staged;
        try {
            staged = Files.createTempDirectory("jclaw-upload-");
        } catch (IOException e) {
            return ImportResult.fail("could not stage import: " + e.getMessage());
        }
        try {
            var root = SkillZip.extract(zip, staged);
            var fallbackName = root.equals(staged.toAbsolutePath().normalize())
                    ? fileName.replaceFirst("(?i)\\.(zip|skill)$", "")
                    : root.getFileName().toString();
            var declared = SkillFrontmatter.of(Files.readString(root.resolve("SKILL.md")));
            var declaredVersion = declared != null ? declared.version() : null;
            var result = conformAndPublish(root, fallbackName, "uploaded", "an uploaded zip (" + fileName + ")",
                    name -> replaceCheck(name, declaredVersion, replace));
            var name = result.skillName();
            if (!result.ok() || name == null) return result;
            var flagged = flagCredentialLikeFiles(SkillLoader.globalSkillsPath().resolve(name));
            var warnings = new ArrayList<>(result.warnings());
            if (!flagged.isEmpty()) {
                warnings.add("These files look like they hold a key and stay readable to your agents: %s. %s"
                        .formatted(String.join(", ", flagged), "Move the keys into credentials/."));
            }
            return ImportResult.imported(name, flagged, warnings);
        } catch (IllegalArgumentException e) {
            return ImportResult.fail(e.getMessage());
        } catch (IOException e) {
            return ImportResult.fail("could not read the zip: " + e.getMessage());
        } finally {
            try {
                SkillPromotionService.deleteRecursive(staged);
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, "Import: temp cleanup failed: " + e.getMessage());
            }
        }
    }

    /**
     * Stops an upload that would replace a library skill: refused when it declares an older version,
     * held for confirmation otherwise. Null lets it through.
     */
    private static @Nullable ImportResult replaceCheck(String name, @Nullable String declaredVersion, boolean replace) {
        var existing = SkillLoader.globalSkillsPath().resolve(name).resolve("SKILL.md");
        if (!Files.isRegularFile(existing)) return null;
        var info = SkillLoader.parseSkillFile(existing);
        var libraryVersion = info != null ? info.version() : "0.0.0";
        if (declaredVersion != null) {
            var uploaded = SkillConformanceService.resolveVersion(declaredVersion);
            if (SkillLoader.compareVersions(uploaded, libraryVersion) < 0) {
                return ImportResult.fail("'%s' is in the library at version %s, and this upload is the older %s."
                        .formatted(name, libraryVersion, uploaded));
            }
        }
        if (replace) return null;
        return new ImportResult(Outcome.EXISTS, name,
                "'%s' is already in the library at version %s. Replace it with this upload?".formatted(name, libraryVersion),
                List.of(), List.of());
    }

    /**
     * Conform → reserved-name check → {@code beforePublish}, which may stop the import → scan,
     * sanitize and publish with the layout kept: the steps every import shares.
     */
    private static ImportResult conformAndPublish(Path staged, String fallbackName, String provenance, String origin,
                                                  @Nullable Function<String, @Nullable ImportResult> beforePublish) {
        var conform = SkillConformanceService.conform(staged, fallbackName, provenance);
        var name = conform.skillName();
        if (!conform.ok() || name == null) return ImportResult.fail(conform.message());

        if (RESERVED.contains(name)) {
            return ImportResult.fail("'%s' is a reserved built-in skill and cannot be imported".formatted(name));
        }
        var stop = beforePublish != null ? beforePublish.apply(name) : null;
        if (stop != null) return stop;

        if (!SkillPromotionService.publishToGlobal(staged, name, true)) {
            return ImportResult.fail("skill failed the malware scan or could not be written to the registry");
        }

        EventLogger.info(CATEGORY, "Imported skill '%s' from %s".formatted(name, origin));
        var unscanned = unscannedWarning(anyScannerEnabled(), binaryFiles(SkillLoader.globalSkillsPath().resolve(name)));
        return ImportResult.imported(name, List.of(), unscanned != null ? List.of(unscanned) : List.of());
    }

    private static boolean anyScannerEnabled() {
        return ScannerRegistry.createDefaultScanners().stream().anyMatch(Scanner::isEnabled);
    }

    /** The published skill's binary files, the ones the malware scan reads. */
    private static List<String> binaryFiles(Path skillDir) {
        try {
            return publishedFiles(skillDir).stream().filter(f -> !SkillLoader.isTextFile(f)).toList();
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Import: could not list the published files: " + e.getMessage());
            return List.of();
        }
    }

    /** Every regular file of the published skill, relative and sorted; a symbolic link is not one. */
    private static List<String> publishedFiles(Path skillDir) throws IOException {
        try (var walk = Files.walk(skillDir)) {
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> skillDir.relativize(p).toString().replace(File.separatorChar, '/'))
                    .sorted().toList();
        }
    }

    /** The warning an import carries when no scanner checked its binaries; null when one did, or there were none. */
    public static @Nullable String unscannedWarning(boolean scannerEnabled, List<String> binaryFiles) {
        if (scannerEnabled || binaryFiles.isEmpty()) return null;
        var named = String.join(", ", binaryFiles.subList(0, Math.min(NAMED_FILES, binaryFiles.size())));
        var more = binaryFiles.size() > NAMED_FILES ? " and %d more".formatted(binaryFiles.size() - NAMED_FILES) : "";
        return "No malware scanner is set up, so this skill's binary files were not scanned: %s%s. %s"
                .formatted(named, more, "Set one up in Settings → Malware and Virus Scanners.");
    }

    /** Text files of the published skill, outside {@code credentials/}, that {@link SecretRedactor} would change. */
    private static List<String> flagCredentialLikeFiles(Path skillDir) throws IOException {
        var flagged = new ArrayList<String>();
        for (var relative : publishedFiles(skillDir)) {
            if (relative.startsWith("credentials/") || !SkillLoader.isTextFile(relative)) continue;
            var text = Files.readString(skillDir.resolve(relative));
            if (!SecretRedactor.redact(text).equals(text)) flagged.add(relative);
        }
        return flagged;
    }
}
