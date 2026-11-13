import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.RegistrySkillImporter;
import utils.BuiltInSkills;

import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The warning an import carries when a skill's binaries went unscanned, and the names no import may take. */
class RegistrySkillImporterTest extends UnitTest {

    private static final String SETTINGS = " Set one up in Settings → Malware and Virus Scanners.";

    private static List<String> fonts(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "fonts/f" + i + ".ttf").toList();
    }

    @Test
    void binaryFilesNoScannerCheckedAreNamed() {
        assertEquals("No malware scanner is set up, so this skill's binary files were not scanned: tools/wacli." + SETTINGS,
                RegistrySkillImporter.unscannedWarning(false, List.of("tools/wacli")));
    }

    @Test
    void thereIsNoWarningWhenAScannerRanOrThereWasNothingToScan() {
        assertNull(RegistrySkillImporter.unscannedWarning(true, List.of("tools/wacli")));
        assertNull(RegistrySkillImporter.unscannedWarning(false, List.of()));
    }

    @Test
    void aLongListNamesFiveFilesAndCountsTheRest() {
        assertEquals("No malware scanner is set up, so this skill's binary files were not scanned: "
                        + "fonts/f1.ttf, fonts/f2.ttf, fonts/f3.ttf, fonts/f4.ttf, fonts/f5.ttf." + SETTINGS,
                RegistrySkillImporter.unscannedWarning(false, fonts(5)));
        assertEquals("No malware scanner is set up, so this skill's binary files were not scanned: "
                        + "fonts/f1.ttf, fonts/f2.ttf, fonts/f3.ttf, fonts/f4.ttf, fonts/f5.ttf and 1 more." + SETTINGS,
                RegistrySkillImporter.unscannedWarning(false, fonts(6)));
    }

    /** JF-392: no import may take a built-in skill's name, task-creator included. */
    @Test
    void noImportMayTakeABuiltInSkillsName() throws Exception {
        var dir = Files.createTempDirectory("jf392-import-");
        try {
            for (var name : BuiltInSkills.NAMES) {
                var zip = dir.resolve(name + ".zip");
                try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                    out.putNextEntry(new ZipEntry(name + "/SKILL.md"));
                    // The shipped copy is in JClaw's layout, so conformance asks no model and the name check is reached.
                    out.write(Files.readAllBytes(Play.applicationPath.toPath().resolve("skills").resolve(name).resolve("SKILL.md")));
                    out.closeEntry();
                }
                var result = RegistrySkillImporter.importZip(zip, name + ".zip", false);
                assertFalse(result.ok(), name);
                assertEquals("'" + name + "' is a reserved built-in skill and cannot be imported", result.message(), name);
            }
        } finally {
            try (var walk = Files.walk(dir)) {
                for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
