import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Rung 3 tells one story on every surface a challenge compares (JCLAW-1305), measured by the stealth
 * sidecar's own {@code --self-check}: a real Patchright browser, launched the way a render is, loads a
 * loopback fixture and reports what the page, a cross-site iframe, their dedicated Workers and each
 * request header said.
 *
 * <p>Live-browser, so it runs only under {@code JCLAW_PLAYWRIGHT_TEST}. The verdict logic it relies on is
 * held without a browser by {@code ScrapeSidecarContractTest.theFingerprintCheckNamesEachSurfaceThatDisagrees}.
 */
class StealthFingerprintTest extends UnitTest {

    private static boolean isPlaywrightTestEnabled() {
        var v = System.getenv("JCLAW_PLAYWRIGHT_TEST");
        return v != null && !v.isBlank() && !"0".equals(v) && !"false".equalsIgnoreCase(v);
    }

    /** The self-check's JSON for {@code language}, or a skip when uv or Patchright's Chromium is absent. */
    private static JsonObject selfCheck(String language) throws Exception {
        var dir = new File(Play.applicationPath, "sidecar/stealth");
        Process proc;
        try {
            proc = new ProcessBuilder("uv", "run", "serve.py", "--self-check", "--language", language)
                    .directory(dir).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        } catch (IOException e) {
            proc = Assumptions.abort("uv is not on PATH: " + e.getMessage());
        }
        // The first run in a fresh checkout installs Patchright before it launches anything.
        if (!proc.waitFor(240, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            fail("the stealth self-check did not finish in 240 s");
        }
        var stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        var lines = stdout.lines().toList();
        assertFalse(lines.isEmpty(), "the self-check printed nothing; exit " + proc.exitValue());
        var result = JsonParser.parseString(lines.getLast()).getAsJsonObject();
        if (result.has("error")) {
            var error = result.get("error").getAsString();
            Assumptions.assumeFalse(error.contains("Executable doesn't exist"),
                    "Patchright's Chromium is not installed; run 'uv run patchright install chromium' in sidecar/stealth");
            fail("the self-check could not run: " + error);
        }
        return result;
    }

    @Test
    void thePageItsWorkerAndItsHeadersTellOneStory() throws Exception {
        Assumptions.assumeTrue(isPlaywrightTestEnabled(), "JCLAW_PLAYWRIGHT_TEST is not set");
        // Not the host's language, so a surface that kept the host's value cannot pass by coincidence.
        var result = selfCheck("de-DE");
        var observed = result.getAsJsonObject("observed");
        assertEquals("[]", result.get("problems").toString(), "observed: " + observed);

        // Asserted here too, so a regression in the Python verdict cannot turn this green on its own.
        assertEquals("chromium", result.get("channel").getAsString(),
                "the fingerprint is the full build's; the headless-shell fallback launched instead");
        var main = observed.getAsJsonObject("main");
        // macOS gives an isolated iframe the OS's Intl; the sidecar README records why that stays.
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var thread : List.of("main", "worker", "frame", "frameWorker")) {
            var surfaces = observed.getAsJsonObject(thread);
            assertEquals("de-DE", surfaces.get("language").getAsString(), thread + ": " + observed);
            if (!(mac && thread.startsWith("frame"))) {
                assertEquals("de-DE", surfaces.get("intl").getAsString(), thread + ": " + observed);
            }
            assertFalse(surfaces.get("userAgent").getAsString().contains("HeadlessChrome"), thread + ": " + observed);
            assertFalse(surfaces.get("brands").toString().contains("HeadlessChrome"), thread + ": " + observed);
            assertEquals(main.get("userAgent"), surfaces.get("userAgent"), thread + ": " + observed);
            assertEquals(main.get("brands"), surfaces.get("brands"), thread + ": " + observed);
            assertEquals(main.get("languages"), surfaces.get("languages"), thread + ": " + observed);
        }
        assertEquals("[1920,1080]", main.get("screen").toString());
        assertEquals("[1920,1080]", main.get("viewport").toString());
        assertTrue(main.get("pointerFine").getAsBoolean() && main.get("hover").getAsBoolean());
        var workerFetch = observed.getAsJsonObject("headers").getAsJsonObject("/echo/worker");
        assertTrue(workerFetch.get("accept-language").getAsString().startsWith("de-DE,de;q=0.9"),
                "the Worker's request states the page's languages: " + workerFetch);
        assertFalse(observed.get("webrtcUdp").getAsBoolean(), "WebRTC sent UDP that no route gate sees");
    }
}
