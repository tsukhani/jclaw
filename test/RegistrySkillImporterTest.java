import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.RegistrySkillImporter;

import java.util.List;
import java.util.stream.IntStream;

/** The warning an import carries when a skill's binaries went unscanned. */
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
}
