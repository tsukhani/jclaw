import org.junit.jupiter.api.*;
import play.test.*;
import agents.ToolRegistry;
import tools.PlaywrightBrowserTool;
import models.Agent;
import services.AgentService;

import java.io.IOException;
import java.nio.file.Files;

public class PlaywrightToolTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        agent = AgentService.create("browser-test-agent", "openrouter", "gpt-4.1");
        agent.enabled = true;
        agent.save();
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath("browser-test-agent"));
    }

    /**
     * JCLAW-172: gate live-Playwright tests on the {@code JCLAW_PLAYWRIGHT_TEST}
     * env var. Replaces the previous {@code playwright.enabled} config gate that
     * was tied to a production config key now removed. Set
     * {@code JCLAW_PLAYWRIGHT_TEST=1} (or any truthy value) to opt in when the
     * Chromium driver is installed locally; CI/dev environments without it
     * skip the live tests cleanly.
     */
    private static boolean isPlaywrightTestEnabled() {
        var v = System.getenv("JCLAW_PLAYWRIGHT_TEST");
        return v != null && !v.isBlank() && !"0".equals(v) && !"false".equalsIgnoreCase(v);
    }

    @Test
    public void toolHasCorrectNameAndDescription() {
        var tool = new PlaywrightBrowserTool();
        assertEquals("browser", tool.name());
        assertTrue(tool.description().contains("headless") || tool.description().contains("Headless"));
        assertTrue(tool.description().contains("navigate"));
    }

    @Test
    public void toolParametersContainAllActions() {
        var tool = new PlaywrightBrowserTool();
        var params = tool.parameters();
        assertNotNull(params);
        assertEquals("object", params.get("type"));

        // Check that action enum contains all expected values
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) params.get("properties");
        assertNotNull(props.get("action"));

        @SuppressWarnings("unchecked")
        var actionDef = (java.util.Map<String, Object>) props.get("action");
        @SuppressWarnings("unchecked")
        var enumValues = (java.util.List<String>) actionDef.get("enum");
        assertTrue(enumValues.contains("navigate"));
        assertTrue(enumValues.contains("click"));
        assertTrue(enumValues.contains("fill"));
        assertTrue(enumValues.contains("getText"));
        assertTrue(enumValues.contains("screenshot"));
        assertTrue(enumValues.contains("evaluate"));
        assertTrue(enumValues.contains("close"));
    }

    @Test
    public void unknownActionReturnsError() {
        var tool = new PlaywrightBrowserTool();
        var result = tool.execute("{\"action\": \"unknownAction\"}", agent);
        assertTrue(result.contains("Error") || result.contains("Unknown"));
    }

    @Test
    public void closeOnNonExistentSessionSucceeds() {
        var tool = new PlaywrightBrowserTool();
        var result = tool.execute("{\"action\": \"close\"}", agent);
        assertTrue(result.contains("closed") || result.contains("Browser"));
    }

    @Test
    public void browserToolDisabledForNonMainAgent() {
        var otherAgent = AgentService.create("non-main-agent", "openrouter", "gpt-4.1");
        // Check that AgentToolConfig was created with browser disabled
        var configs = models.AgentToolConfig.findByAgent(otherAgent);
        var browserConfig = configs.stream()
                .filter(c -> "browser".equals(c.toolName))
                .findFirst();
        assertTrue(browserConfig.isPresent());
        assertFalse(browserConfig.get().enabled);

        // Cleanup
        deleteDir(AgentService.workspacePath("non-main-agent"));
    }

    // JCLAW-116: entry-URL SSRF guard. These tests don't need a running
    // Chromium — the guard runs before any session is created or any
    // Playwright API is called. The tool returns the error string directly.

    @Test
    public void navigateRejectsCloudMetadataEndpoint() {
        var tool = new PlaywrightBrowserTool();
        var result = tool.execute(
                "{\"action\":\"navigate\",\"url\":\"http://169.254.169.254/latest/meta-data/\"}",
                agent);
        assertTrue(result.startsWith("Error"),
                "metadata endpoint must be rejected before Playwright is invoked: " + result);
        assertTrue(result.contains("SSRF"),
                "error message names the SSRF guard: " + result);
    }

    @Test
    public void navigateRejectsLoopbackAddress() {
        var tool = new PlaywrightBrowserTool();
        var result = tool.execute(
                "{\"action\":\"navigate\",\"url\":\"http://127.0.0.1:9000/admin\"}",
                agent);
        assertTrue(result.startsWith("Error"));
    }

    @Test
    public void navigateRejectsPrivateNetworkAddress() {
        var tool = new PlaywrightBrowserTool();
        var result = tool.execute(
                "{\"action\":\"navigate\",\"url\":\"http://10.0.0.5/\"}",
                agent);
        assertTrue(result.startsWith("Error"));
    }

    @Test
    public void navigateRejectsFileScheme() {
        var tool = new PlaywrightBrowserTool();
        var result = tool.execute(
                "{\"action\":\"navigate\",\"url\":\"file:///etc/passwd\"}",
                agent);
        assertTrue(result.startsWith("Error"));
    }

    @Test
    public void idleSessionCleanupDoesNotThrow() {
        // Just verify the cleanup method runs without error even with no sessions
        PlaywrightBrowserTool.cleanupIdleSessions();
    }

    @Test
    public void parallelBrowserOpsOnSameAgentDoNotCorruptPage() throws Exception {
        // Regression: when the LLM emits navigate + screenshot in a single
        // streaming round, AgentRunner.executeToolsParallel dispatches them on
        // separate virtual threads. Playwright's Page is not thread-safe, so
        // without per-session serialization one call surfaces as
        //   "Browser error: Object doesn't exist: request@<hash>"
        // This test runs two browser actions concurrently against the same
        // agent and asserts both return non-error results.
        //
        // Skipped when Playwright is not configured (no chromium install in CI).
        if (!isPlaywrightTestEnabled()) {
            return;
        }

        var tool = new PlaywrightBrowserTool();
        // Seed the session with an initial navigate so the screenshot target is
        // a real page rather than about:blank.
        var seed = tool.execute(
                "{\"action\":\"navigate\",\"url\":\"data:text/html,<h1>seed</h1>\"}",
                agent);
        assertFalse(seed.startsWith("Browser error"), "seed navigate failed: " + seed);

        try {
            var results = new String[2];
            var t1 = Thread.ofVirtual().start(() -> results[0] = tool.execute(
                    "{\"action\":\"navigate\",\"url\":\"data:text/html,<h1>one</h1>\"}",
                    agent));
            var t2 = Thread.ofVirtual().start(() -> results[1] = tool.execute(
                    "{\"action\":\"screenshot\"}",
                    agent));
            t1.join();
            t2.join();

            assertFalse(results[0].contains("Object doesn't exist"),
                    "navigate must not trip Playwright's request map: " + results[0]);
            assertFalse(results[1].contains("Object doesn't exist"),
                    "screenshot must not trip Playwright's request map: " + results[1]);
            // Screenshot result must still contain the markdown embed so
            // AgentRunner can prepend the inline image.
            assertTrue(results[1].contains("![Screenshot]("),
                    "screenshot result must still contain the markdown embed: " + results[1]);
        } finally {
            PlaywrightBrowserTool.closeSession(agent.name);
        }
    }

    @Test
    public void screenshotResultIsMinimalAndSuppressesPathEcho() {
        // Post-JCLAW-104 three-fix patch plus Option B: the tool result is a
        // bare acknowledgment plus one targeted directive telling the LLM
        // not to quote the file path. Pre-patch, the instruction prescribed
        // "describe what the page shows" which made every screenshot turn
        // verbose even when the user just asked "take a screenshot." The
        // runtime already guarantees image display (buildImagePrefix) and
        // the download affordance (native save on Telegram photos, markdown
        // link on web). Option B was added after smoke testing surfaced a
        // chatty-model failure mode where the model echoes the URL as a
        // parenthetical in its prose.
        var url = "/api/agents/1/files/screenshot-1000.png";
        var result = PlaywrightBrowserTool.formatScreenshotResult(url);

        assertTrue(result.contains("![Screenshot](" + url + ")"),
                "Result must contain a well-formed markdown image tag so "
                        + "extractImageUrls picks it up for the prefix/suffix pipeline");
        assertTrue(result.contains("Screenshot captured"),
                "Result should name the event so the LLM has a hook for its reply");
        assertTrue(result.contains("Don't quote the file path"),
                "Option B: directive telling the LLM not to echo the URL in its reply — "
                        + "targets the observed Kimi behavior on Telegram");

        // All pre-patch directives are obsolete and must stay out — they
        // each map to a runtime deficiency that's been closed, so putting
        // them back is a regression signal.
        assertFalse(result.contains("Do NOT re-embed"),
                "re-embed dedup lives in buildImagePrefix now");
        assertFalse(result.contains("SHOULD include"),
                "download affordance lives in buildDownloadSuffix / native photo save now");
        assertFalse(result.contains("already displayed"),
                "stale phrasing from pre-fix era");
        assertFalse(result.contains("describe what the page shows"),
                "tool must not prescribe verbosity — lets the LLM infer from user intent");

        // URL appears exactly once — only in the image embed. Option B's
        // new directive references "the file path" generically, never
        // quoting the URL itself.
        int first = result.indexOf(url);
        int second = result.indexOf(url, first + 1);
        assertTrue(first >= 0, "URL must appear in the image embed");
        assertEquals(-1, second, "URL should appear exactly once");
    }

    // ─── Session lifecycle (JCLAW-130 Phase 3) ────────────────────────────
    //
    // The three tests below exercise the navigate-under-failure, tab-reuse,
    // and crash-recovery paths. Each gates on the same JCLAW_PLAYWRIGHT_TEST
    // env var the existing parallelBrowserOpsOnSameAgentDoNotCorruptPage test
    // uses, so headless-CI environments without a Chromium install simply
    // skip them rather than hang on browser launch.

    @Test
    public void navigateUnderNetworkFailureReturnsBrowserError() {
        // Pin: when the underlying chromium navigation can't reach the host
        // (e.g. RFC 6761 .invalid TLD that DNS guarantees never resolves),
        // execute() must surface a "Browser error: ..." string rather than
        // throwing or hanging. This is the regression surface JCLAW-126's
        // OkHttp retry cousin lived in — make sure the Playwright path has
        // the same crisp failure mode.
        if (!isPlaywrightTestEnabled()) {
            return;
        }
        var tool = new PlaywrightBrowserTool();
        try {
            var result = tool.execute(
                    "{\"action\":\"navigate\",\"url\":\"http://nonexistent-host-jclaw-130.invalid/\"}",
                    agent);
            // Either the chromium-level failure surfaces as "Browser error: ..."
            // (PlaywrightException catch in execute) or the SSRF guard rejects
            // the .invalid host before it reaches the browser. Both are valid
            // failure modes — what matters is no exception escapes and the
            // result clearly signals failure.
            assertTrue(result.startsWith("Browser error") || result.startsWith("Error"),
                    "expected error result for unresolvable host, got: " + result);
        } finally {
            PlaywrightBrowserTool.closeSession(agent.name);
        }
    }

    @Test
    public void sequentialNavigatesReuseTheSameSession() throws Exception {
        // The session map keys on agent.name; subsequent calls for the same
        // agent must hit the cached BrowserSession path rather than launching
        // a fresh chromium per call. Verified by reflecting into the private
        // static sessions map and asserting the entry count stays at 1.
        if (!isPlaywrightTestEnabled()) {
            return;
        }
        var tool = new PlaywrightBrowserTool();
        try {
            var first = tool.execute(
                    "{\"action\":\"navigate\",\"url\":\"data:text/html,<title>first</title><h1>1</h1>\"}",
                    agent);
            assertFalse(first.startsWith("Browser error"), "first navigate failed: " + first);

            int sessionsAfterFirst = sessionCount();
            assertEquals(1, sessionsAfterFirst,
                    "first navigate should leave one cached session for the agent");

            var second = tool.execute(
                    "{\"action\":\"navigate\",\"url\":\"data:text/html,<title>second</title><h1>2</h1>\"}",
                    agent);
            assertFalse(second.startsWith("Browser error"), "second navigate failed: " + second);

            int sessionsAfterSecond = sessionCount();
            assertEquals(sessionsAfterFirst, sessionsAfterSecond,
                    "second navigate must reuse the same session — no extra entry");
        } finally {
            PlaywrightBrowserTool.closeSession(agent.name);
        }
    }

    @Test
    public void executeAfterExplicitCloseRecreatesSessionCleanly() {
        // Crash recovery: getOrCreateSession's compute() branch re-launches
        // when the cached page is closed. We can't reliably simulate a SIGSEGV
        // in chromium from a test, but closing the session out-of-band is the
        // same observable state from the tool's POV — the next execute() must
        // see a fresh, working session rather than NPE on the closed Page.
        if (!isPlaywrightTestEnabled()) {
            return;
        }
        var tool = new PlaywrightBrowserTool();
        try {
            var seed = tool.execute(
                    "{\"action\":\"navigate\",\"url\":\"data:text/html,<h1>seed</h1>\"}",
                    agent);
            assertFalse(seed.startsWith("Browser error"), "seed navigate failed: " + seed);

            // Simulate the crash window: external close, then a normal call.
            PlaywrightBrowserTool.closeSession(agent.name);

            var afterClose = tool.execute(
                    "{\"action\":\"navigate\",\"url\":\"data:text/html,<h1>after</h1>\"}",
                    agent);
            assertFalse(afterClose.startsWith("Browser error"),
                    "navigate after close must succeed via session re-creation: " + afterClose);
            assertTrue(afterClose.contains("after") || afterClose.contains("Page:"),
                    "fresh session should serve the post-close navigate: " + afterClose);
        } finally {
            PlaywrightBrowserTool.closeSession(agent.name);
        }
    }

    @Test
    public void closeSessionOnNonExistentAgentIsIdempotent() {
        // Pure unit-test path — no chromium needed. The static remove() on
        // ConcurrentHashMap returns null for a missing key, and the close
        // helper short-circuits in that branch. Calling it twice in a row
        // for an agent that never had a session must not throw.
        PlaywrightBrowserTool.closeSession("agent-that-never-existed");
        PlaywrightBrowserTool.closeSession("agent-that-never-existed");
    }

    /**
     * Reflective view into the private static {@code sessions} map. Used by
     * the tab-reuse test to assert the map size doesn't grow across
     * sequential navigates for the same agent.
     */
    private static int sessionCount() throws Exception {
        var f = PlaywrightBrowserTool.class.getDeclaredField("sessions");
        f.setAccessible(true);
        var map = (java.util.Map<?, ?>) f.get(null);
        return map.size();
    }

    private static void deleteDir(java.nio.file.Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }
}
