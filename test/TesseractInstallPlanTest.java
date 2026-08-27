import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.TesseractInstaller;

import java.util.Set;

/**
 * JCLAW-1108: which command the install button would run, per platform.
 *
 * <p>Driven through the pure overload: os.name, user.name and PATH are all
 * process-global, and this suite runs tests concurrently, so reaching the Windows
 * or root branches by mutating them would corrupt whatever ran alongside.
 */
class TesseractInstallPlanTest extends UnitTest {

    private static TesseractInstaller.Plan plan(String os, boolean root, String... onPath) {
        var present = Set.of(onPath);
        return TesseractInstaller.planFor(os, present::contains, root);
    }

    @Test
    void windowsUsesWingetSilently() {
        var p = plan("Windows 11", false, "winget");
        assertTrue(p.runnable());
        assertEquals("winget", p.manager());
        assertTrue(p.display().contains("UB-Mannheim.TesseractOCR"), p.display());
        assertTrue(p.display().contains("--silent"), p.display());
        // The UAC prompt is the likeliest failure, so it is stated before the click.
        assertTrue(p.reason().contains("UAC"), p.reason());
    }

    @Test
    void windowsWithoutWingetIsNotRunnable() {
        var p = plan("Windows 11", false);
        assertFalse(p.runnable());
        assertTrue(p.reason().contains("App Installer"), p.reason());
    }

    @Test
    void macUsesBrew() {
        var p = plan("Mac OS X", false, "brew");
        assertTrue(p.runnable());
        assertEquals("brew install tesseract", p.display());
        assertNull(p.reason(), "nothing surprising to warn about on the brew path");
    }

    @Test
    void macWithoutBrewIsNotRunnable() {
        var p = plan("Mac OS X", false);
        assertFalse(p.runnable());
        assertTrue(p.reason().contains("brew.sh"), p.reason());
    }

    @Test
    void linuxNamesTheManagerButRefusesToRunItUnprivileged() {
        // The whole point of the Linux branch: never invoke sudo from a process with
        // no TTY. It cannot prompt, so it would hang or fail obscurely.
        var p = plan("Linux", false, "apt-get");
        assertFalse(p.runnable());
        assertEquals("apt-get", p.manager());
        assertTrue(p.reason().contains("sudo apt-get install"), p.reason());
        assertTrue(p.reason().contains("tesseract-ocr"), p.reason());
    }

    @Test
    void linuxAsRootRunsItDirectly() {
        var p = plan("Linux", true, "dnf");
        assertTrue(p.runnable());
        assertEquals("dnf", p.manager());
        assertTrue(p.display().contains("tesseract"), p.display());
    }

    @Test
    void anUnknownPlatformFallsBackToAdvice() {
        var p = plan("SunOS", false);
        assertFalse(p.runnable());
        assertNull(p.manager());
        assertTrue(p.reason().contains("ocr.tesseract.path"), p.reason());
    }
}
