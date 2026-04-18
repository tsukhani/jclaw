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
        if (!"true".equals(services.ConfigService.get("playwright.enabled", "false"))) {
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
    public void screenshotResultContainsImageEmbedAndGuidance() {
        // The tool result MUST contain a markdown image tag (so AgentRunner picks
        // it up and prepends the inline image) AND guidance that tells the LLM:
        //   - the image is already displayed (don't re-embed as an image), and
        //   - the URL should be included in the reply as a plain link so the user
        //     has a referenceable pointer to the file alongside the inline image.
        var url = "/api/agents/1/files/screenshot-1000.png";
        var result = PlaywrightBrowserTool.formatScreenshotResult(url);

        assertTrue(result.contains("![Screenshot](" + url + ")"),
                "Result must contain a well-formed markdown image tag for the screenshot URL");
        assertTrue(result.contains("Do NOT re-embed"),
                "Result must tell the LLM not to re-embed the image");
        assertTrue(result.contains("already displayed"),
                "Result must state that the image is already displayed to the user");
        assertTrue(result.contains("<img>"),
                "Result must also forbid HTML <img> re-embeds (the dedup catches both forms)");
        // URL appears twice: once inside the ![](...) image embed and once
        // inside a ready-to-copy markdown link template, so the LLM has an
        // unambiguous clickable reference to cite.
        int first = result.indexOf(url);
        int second = result.indexOf(url, first + 1);
        assertTrue(first >= 0 && second > first,
                "Result must reference the URL both inside the image embed and as a link template");
        assertTrue(result.contains("[screenshot](" + url + ")"),
                "Result must provide a ready-to-copy markdown link template for the LLM to echo");
        assertTrue(result.contains("include the link"),
                "Result must explicitly invite the LLM to include the link in its reply");
        assertFalse(result.contains("Display it with:"),
                "Result must NOT invite the LLM to display the image (the buggy prior wording)");
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
