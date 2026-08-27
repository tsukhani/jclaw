import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.OcrInstallHint;

/**
 * JCLAW-1108: the install instructions must name the platform the operator is on.
 * The old text offered brew and apt to everyone, which on Windows is advice for two
 * operating systems they are not running.
 *
 * <p>Driven through the pure overload: os.name is process-global and this suite runs
 * tests concurrently, so setting it to reach a branch would corrupt whatever ran
 * alongside.
 */
class OcrInstallHintTest extends UnitTest {

    @Test
    void windowsGetsWingetPlusTheTwoThingsThatActuallyTripPeopleUp() {
        var h = OcrInstallHint.forOs("Windows 11");
        assertTrue(h.contains("winget install -e --id UB-Mannheim.TesseractOCR"), h);
        // The installer does not add itself to PATH, and a PATH change is invisible to
        // terminals already open — between them the commonest reasons a correct install
        // still reports "not detected".
        assertTrue(h.contains("NEW terminal"), h);
        assertTrue(h.contains("ocr.tesseract.path"), h);
        assertFalse(h.contains("brew"), "Windows must not be told to use Homebrew: " + h);
        assertFalse(h.contains("apt-get"), "Windows must not be told to use apt: " + h);
    }

    @Test
    void macGetsBrewOnly() {
        var h = OcrInstallHint.forOs("Mac OS X");
        assertTrue(h.contains("brew install tesseract"), h);
        assertFalse(h.contains("winget"), h);
        assertFalse(h.contains("apt-get"), h);
    }

    @Test
    void linuxGetsItsPackageManagers() {
        var h = OcrInstallHint.forOs("Linux");
        assertTrue(h.contains("apt-get install tesseract-ocr"), h);
        assertTrue(h.contains("dnf install tesseract"), h);
        assertFalse(h.contains("winget"), h);
    }

    @Test
    void everyPlatformSaysToRestart() {
        // Tika decides hasTesseract in its constructor and TikaHolder.PARSER is built
        // at class-init, so an install alone never switches OCR on in a running JVM.
        for (var os : new String[]{"Windows 11", "Mac OS X", "Linux", "SunOS"}) {
            assertTrue(OcrInstallHint.forOs(os).toLowerCase().contains("restart"),
                    os + ": " + OcrInstallHint.forOs(os));
        }
    }
}
