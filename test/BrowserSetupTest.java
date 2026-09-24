import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.browser.BrowserSetup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** The browser tool's first-use setup as the chat bar and Settings read it. */
class BrowserSetupTest extends UnitTest {

    // Captured from `playwright install chromium` with stdout not a terminal (Playwright 1.63).
    private static final String DOWNLOADING =
            "Downloading Chrome for Testing 153.0.8010.12 (playwright chromium v1243)\u001B[2m from "
                    + "https://cdn.playwright.dev/builds/cft/153.0.8010.12/mac-arm64/chrome-mac-arm64.zip\u001B[22m";
    private static final String TEN_PERCENT =
            "|■■■■■■■■                                                                        |  10% of 182.1 MiB";
    private static final String DONE_ITEM =
            "Chrome for Testing 153.0.8010.12 (playwright chromium v1243) downloaded to /x/chromium-1243";

    @Test
    void installerLinesReadAsAStepAPercentOrNothing() {
        assertEquals("Downloading Chrome for Testing 153.0.8010.12", BrowserSetup.parseInstallLine(DOWNLOADING).step());
        assertEquals(10, BrowserSetup.parseInstallLine(TEN_PERCENT).percent());
        assertEquals(0, BrowserSetup.parseInstallLine(
                "|                                                                                |   0% of 94.3 MiB").percent());
        assertEquals(100, BrowserSetup.parseInstallLine(
                "|■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■| 100% of 1 MiB").percent());
        var done = BrowserSetup.parseInstallLine(DONE_ITEM);
        assertNull(done.step());
        assertNull(done.percent());
    }

    @Test
    void progressFollowsAnInstallTranscript() {
        var p = new BrowserSetup.Progress();
        p.onInstallLine(DOWNLOADING);
        assertFalse(p.active(), "installer output outside a setup must not start one");
        assertNull(p.currentStep());

        p.begin();
        assertTrue(p.active());
        p.step("Downloading the browser driver (Node.js)");
        p.percent(100);
        p.onInstallLine(DOWNLOADING);
        assertTrue(p.active(), "the setup stays in flight between the driver and Chromium");
        assertEquals("Downloading Chrome for Testing 153.0.8010.12", p.currentStep());
        assertEquals(0, p.currentPercent());
        p.onInstallLine(TEN_PERCENT);
        assertEquals(10, p.currentPercent());

        p.failed("exit 1");
        p.end();
        assertFalse(p.active());
        assertNull(p.currentStep());
        assertNull(p.currentPercent());
        assertEquals("exit 1", p.lastError(), "a failure stays readable after the setup ends");
        p.begin();
        assertNull(p.lastError(), "a new attempt clears the previous failure");
    }

    @Test
    void theBrowsersPathIsPlaywrightsOwnPerOsDefault() {
        assertEquals(Path.of("/opt/pw-browsers"),
                BrowserSetup.browsersPath(Map.of("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers"), "Linux", "/home/u"));
        assertNull(BrowserSetup.browsersPath(Map.of("PLAYWRIGHT_BROWSERS_PATH", "0"), "Linux", "/home/u"),
                "\"0\" keeps browsers inside the driver package, which has no fixed location");
        assertEquals(Path.of("/Users/u/Library/Caches/ms-playwright"), BrowserSetup.browsersPath(Map.of(), "Mac OS X", "/Users/u"));
        assertEquals(Path.of("/home/u/.cache/ms-playwright"), BrowserSetup.browsersPath(Map.of(), "Linux", "/home/u"));
        assertEquals(Path.of("/xdg/ms-playwright"), BrowserSetup.browsersPath(Map.of("XDG_CACHE_HOME", "/xdg"), "Linux", "/home/u"));
        assertEquals(Path.of("C:/local/ms-playwright"),
                BrowserSetup.browsersPath(Map.of("LOCALAPPDATA", "C:/local"), "Windows 11", "C:/Users/u"));
    }

    @Test
    void chromiumCountsAsInstalledOnlyWhenEveryPieceFinished() throws IOException {
        var root = Files.createTempDirectory("pw-browsers-test");
        var revisions = Map.of("chromium", "1243", "chromium-headless-shell", "1243", "ffmpeg", "1011");
        for (var dir : new String[] {"chromium-1243", "chromium_headless_shell-1243", "ffmpeg-1011"}) {
            Files.createDirectories(root.resolve(dir));
        }
        assertFalse(BrowserSetup.chromiumInstalledAt(root, revisions), "directories alone are an unfinished install");

        Files.createFile(root.resolve("chromium-1243/INSTALLATION_COMPLETE"));
        Files.createFile(root.resolve("chromium_headless_shell-1243/INSTALLATION_COMPLETE"));
        assertFalse(BrowserSetup.chromiumInstalledAt(root, revisions), "ffmpeg is part of the set");

        Files.createFile(root.resolve("ffmpeg-1011/INSTALLATION_COMPLETE"));
        assertTrue(BrowserSetup.chromiumInstalledAt(root, revisions));
        assertFalse(BrowserSetup.chromiumInstalledAt(root, Map.of("chromium", "9999", "chromium-headless-shell", "1243",
                "ffmpeg", "1011")), "a different revision is a different install");
        assertFalse(BrowserSetup.chromiumInstalledAt(root, Map.of()), "no browsers.json means nothing is known installed");
    }

    @Test
    void theExpectedRevisionsComeFromTheDriverJarsBrowsersJson() {
        var revisions = BrowserSetup.expectedRevisions();
        for (var name : new String[] {"chromium", "chromium-headless-shell", "ffmpeg"}) {
            assertTrue(revisions.containsKey(name), name + " missing from " + revisions);
            assertTrue(revisions.get(name).matches("\\d+"), name + " revision " + revisions.get(name));
        }
    }
}
