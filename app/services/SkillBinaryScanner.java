package services;

import agents.SkillLoader;
import services.scanners.Scanner;
import services.scanners.ScannerRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Single choke-point for malware scanning of binary files inside a skill directory.
 * Called from both the copy-to-agent and promote-to-global flows in
 * {@code ApiSkillsController}.
 *
 * <p>Walks a skill folder, identifies binary files via
 * {@link SkillLoader#isTextFile(String)}, hashes each with SHA-256, and dispatches
 * to every enabled {@link Scanner} backend. Scanners are independent and composed
 * under OR semantics: if any enabled scanner flags a file, a {@link Violation} is
 * recorded attributing the match to that scanner. A file flagged by multiple
 * scanners produces one violation per scanner so the audit log shows exactly
 * which source caught what.
 *
 * <p>Each scanner's {@link Scanner#isEnabled()} check decides whether it runs —
 * a scanner with no API key configured returns false and is silently skipped.
 * If no scanner is enabled (fresh install with no keys), binary scanning is a
 * no-op and the skill install proceeds. See the Scanner javadoc for the full
 * fail-open contract.
 *
 * <p>This class never throws on I/O or scanner errors — scanning failures fail
 * <b>open</b> to preserve availability. Every scan attempt is audit-logged via
 * {@link EventLogger}.
 */
public class SkillBinaryScanner {

    public record Violation(String relativePath, String sha256, String scanner, String reason) {
        /** Short, user-facing description suitable for error messages. */
        public String describe() {
            return "%s: %s (%s)".formatted(relativePath, reason, scanner);
        }
    }

    /**
     * Scan every binary file in {@code skillDir} recursively.
     *
     * @return list of violations (empty = clean or all scanners failed open)
     */
    public static List<Violation> scan(Path skillDir) {
        return scan(skillDir, ScannerRegistry.createDefaultScanners());
    }

    public static List<Violation> scan(Path skillDir, List<Scanner> scanners) {
        var violations = new ArrayList<Violation>();
        if (skillDir == null || !Files.isDirectory(skillDir)) return violations;

        var skillName = skillDir.getFileName() != null ? skillDir.getFileName().toString() : "?";
        int scanned = 0;

        // Snapshot enabled scanners once per scan so every file is checked by the same set —
        // avoids weird mid-scan config flips and makes the audit log consistent.
        var availableScanners = scanners != null ? scanners : List.<Scanner>of();
        var activeScanners = availableScanners.stream().filter(Scanner::isEnabled).toList();
        if (activeScanners.isEmpty()) {
            return violations;
        }

        try (var walk = Files.walk(skillDir)) {
            var binaries = walk.filter(Files::isRegularFile)
                    .filter(p -> !SkillLoader.isTextFile(skillDir.relativize(p).toString()))
                    .toList();

            for (var file : binaries) {
                var relName = skillDir.relativize(file).toString();
                String sha256;
                try {
                    sha256 = sha256Of(file);
                } catch (IOException | NoSuchAlgorithmException e) {
                    EventLogger.warn("scanner",
                            "Skipping '%s' in skill '%s': hash failed (%s) — failing open"
                                    .formatted(relName, skillName, e.getMessage()));
                    continue;
                }

                scanned++;
                boolean flagged = false;
                for (var scanner : activeScanners) {
                    var verdict = scanner.lookup(sha256);
                    if (verdict.malicious()) {
                        flagged = true;
                        EventLogger.warn("scanner",
                                "Malware detected in skill '%s': %s (%s, %s)".formatted(
                                        skillName, relName, verdict.reason(), scanner.name()));
                        violations.add(new Violation(
                                relName, sha256, scanner.name(), verdict.reason()));
                    }
                }
                if (!flagged) {
                    EventLogger.info("scanner",
                            "Scanned '%s' in skill '%s': clean (%d scanner(s), sha256=%s)".formatted(
                                    relName, skillName, activeScanners.size(), sha256.substring(0, 12)));
                }
            }
        } catch (IOException e) {
            EventLogger.warn("scanner",
                    "Walk failed for skill '%s': %s — failing open".formatted(skillName, e.getMessage()));
        }

        if (scanned > 0 && violations.isEmpty()) {
            EventLogger.info("scanner",
                    "Skill '%s' scan complete: %d binary file(s) clean".formatted(skillName, scanned));
        }
        return violations;
    }

    private static String sha256Of(Path file) throws IOException, NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
