import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.HandoffShell;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Covers the shell the restart and upgrade handoffs are run through. The Windows
 * branches are the point: they cannot be reached from the host the suite runs on,
 * and getting them wrong is invisible until an operator on Windows clicks Upgrade.
 */
class HandoffShellTest extends UnitTest {

    private static final String GIT_BASH = "C:\\Program Files\\Git\\bin\\bash.exe";

    private static UnaryOperator<String> env(Map<String, String> entries) {
        return entries::get;
    }

    @Test
    void posixHostsHandOffThroughBinSh() {
        assertEquals("/bin/sh",
                HandoffShell.locate("Mac OS X", env(Map.of()), path -> fail("must not probe on POSIX")));
        assertEquals("/bin/sh",
                HandoffShell.locate("Linux", env(Map.of()), path -> fail("must not probe on POSIX")));
    }

    @Test
    void windowsFindsGitBashWhereTheInstallerPutsIt() {
        var found = HandoffShell.locate("Windows 11",
                env(Map.of("ProgramFiles", "C:\\Program Files")),
                Set.of(GIT_BASH)::contains);

        assertEquals(GIT_BASH, found);
    }

    @Test
    void windowsPrefersTheGitBashThisInstanceWasStartedFrom() {
        // EXEPATH is set by the Git Bash the app is already running under, so it
        // names the same MSYS tree jclaw.sh was launched by — a second Git install
        // under Program Files may be a different version entirely.
        var found = HandoffShell.locate("Windows 11",
                env(Map.of("EXEPATH", "D:\\portable\\Git", "ProgramFiles", "C:\\Program Files")),
                Set.of("D:\\portable\\Git\\bin\\bash.exe", GIT_BASH)::contains);

        assertEquals("D:\\portable\\Git\\bin\\bash.exe", found);
    }

    @Test
    void windowsNeverHandsOffToWslBash() {
        // System32\bash.exe is WSL's launcher. It would run the helper against the
        // distro's filesystem, where the Windows install being upgraded does not
        // exist — a silent no-op at best, an upgrade of the wrong tree at worst.
        var found = HandoffShell.locate("Windows 11",
                env(Map.of("SystemRoot", "C:\\Windows",
                        "PATH", "C:\\Windows\\System32;C:\\Windows")),
                Set.of("C:\\Windows\\System32\\bash.exe")::contains);

        assertNull(found);

        // ...but the skip is by directory, not by string prefix: C:\WindowsApps is
        // an ordinary PATH entry that happens to start with the same characters.
        assertEquals("C:\\WindowsApps\\bash.exe", HandoffShell.locate("Windows 11",
                env(Map.of("SystemRoot", "C:\\Windows", "PATH", "C:\\WindowsApps")),
                Set.of("C:\\WindowsApps\\bash.exe")::contains));
    }

    @Test
    void windowsFallsBackToAnyBashOnPath() {
        var found = HandoffShell.locate("Windows 11",
                env(Map.of("SystemRoot", "C:\\Windows",
                        "PATH", "C:\\Windows\\System32;C:\\tools\\msys64\\usr\\bin")),
                Set.of("C:\\tools\\msys64\\usr\\bin\\bash.exe")::contains);

        assertEquals("C:\\tools\\msys64\\usr\\bin\\bash.exe", found);
    }

    @Test
    void windowsReportsNoShellRatherThanGuessing() {
        assertNull(HandoffShell.locate("Windows 11", env(Map.of()), path -> false));
    }

    @Test
    void windowsArgvIsRewrittenIntoAPathMsysCanOpen() {
        var line = HandoffShell.commandLine(
                List.of("C:\\Users\\a\\.jclaw\\jclaw.sh", "upgrade", "--yes"), 2, true);

        assertEquals("sleep 2; exec 'C:/Users/a/.jclaw/jclaw.sh' 'upgrade' '--yes'", line);
    }

    @Test
    void posixArgvIsLeftAlone() {
        assertEquals("sleep 2; exec '/opt/jclaw/jclaw.sh' 'restart'",
                HandoffShell.commandLine(List.of("/opt/jclaw/jclaw.sh", "restart"), 2, false));
    }

    @Test
    void quotingSurvivesPathsWithSpacesAndQuotes() {
        assertEquals("'/opt/jclaw/jclaw.sh' 'restart'",
                HandoffShell.shellQuote(List.of("/opt/jclaw/jclaw.sh", "restart")));

        // An install under "Application Support" must not word-split into a
        // different command than plan() resolved.
        assertEquals("'/Users/a/Application Support/jclaw.sh' '--dev'",
                HandoffShell.shellQuote(List.of("/Users/a/Application Support/jclaw.sh", "--dev")));

        // A single quote in the path can't be allowed to close our quoting and
        // let the remainder be read as shell syntax.
        assertEquals("'/tmp/it'\\''s/jclaw.sh'",
                HandoffShell.shellQuote(List.of("/tmp/it's/jclaw.sh")));
    }
}
