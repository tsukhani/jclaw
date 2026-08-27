import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.OcrHealthProbe;

/**
 * JCLAW-1107: the probe must run the same binary Tika will run, or Settings reports
 * OCR unavailable for an install the parser reaches perfectly well — and the reverse.
 *
 * <p>Driven through the pure overload on purpose: os.name and Play.configuration are
 * process-global and this suite runs tests concurrently, so flipping either to reach
 * the Windows branch would corrupt whatever ran alongside it.
 */
class OcrTesseractCommandTest extends UnitTest {

    @Test
    void windowsUsesTheExeSuffix() {
        // Tika's TesseractOCRParser.getTesseractProg() does the same; a bare
        // "tesseract" would rely on ProcessBuilder appending the extension.
        assertEquals("tesseract.exe", OcrHealthProbe.tesseractCommand("Windows 11", null));
        assertEquals("tesseract", OcrHealthProbe.tesseractCommand("Mac OS X", null));
        assertEquals("tesseract", OcrHealthProbe.tesseractCommand("Linux", null));
    }

    @Test
    void aConfiguredDirectoryIsJoinedToTheProgram() {
        var win = OcrHealthProbe.tesseractCommand("Windows 11", "C:\\Program Files\\Tesseract-OCR");
        assertTrue(win.endsWith("tesseract.exe"), win);
        assertTrue(win.contains("Tesseract-OCR"), win);

        var nix = OcrHealthProbe.tesseractCommand("Linux", "/opt/tesseract/bin");
        assertEquals("/opt/tesseract/bin/tesseract", nix);
    }

    @Test
    void anAbsentOrBlankDirectoryFallsBackToAPathLookup() {
        // Blank must not become "/tesseract" — that would probe the filesystem root
        // and report OCR unavailable on a machine where PATH resolves it fine.
        assertEquals("tesseract", OcrHealthProbe.tesseractCommand("Linux", null));
        assertEquals("tesseract", OcrHealthProbe.tesseractCommand("Linux", ""));
        assertEquals("tesseract", OcrHealthProbe.tesseractCommand("Linux", "   "));
    }
}
