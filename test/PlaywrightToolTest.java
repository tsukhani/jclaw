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
    public void screenshotResultIncludesDoNotReembedInstruction() {
        // The tool result MUST contain a markdown image tag (so AgentRunner picks it up)
        // AND an explicit instruction telling the LLM the image is already displayed,
        // so the LLM doesn't echo a (potentially broken) duplicate in its reply.
        // See commit history for the duplicate-screenshot bug report.
        var url = "/api/agents/1/files/screenshot-1000.png";
        var result = PlaywrightBrowserTool.formatScreenshotResult(url);

        assertTrue(result.contains("![Screenshot](" + url + ")"),
                "Result must contain a well-formed markdown image tag for the screenshot URL");
        assertTrue(result.contains("Do NOT re-embed"),
                "Result must tell the LLM not to re-embed the image");
        assertTrue(result.contains("already visible"),
                "Result must state that the image is already visible to the user");
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
