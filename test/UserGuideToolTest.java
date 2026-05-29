import org.junit.jupiter.api.*;
import play.test.*;
import tools.UserGuideTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Unit tests for {@link tools.UserGuideTool}. Uses the public Path constructor
 * to point the tool at a temp directory of fixture markdown, so the test is
 * independent of the real docs/user-guide content (and of Play.applicationPath).
 */
class UserGuideToolTest extends UnitTest {

    private static Path docsDir;
    private UserGuideTool tool;

    @BeforeAll
    static void writeFixtures() throws Exception {
        docsDir = Files.createTempDirectory("userguide-fixtures");
        Files.writeString(docsDir.resolve("tasks.md"), """
                # Tasks
                ## Creating a task
                Use the task tool to schedule a recurring or one-off task. Tasks run on a cron schedule.
                ## Deleting a task
                Open the task and click delete to remove it.
                """);
        Files.writeString(docsDir.resolve("agents.md"), """
                # Agents
                ## What is an agent
                An agent is an AI assistant with its own workspace, skills, and channel bindings.
                """);
        Files.writeString(docsDir.resolve("chat.md"), """
                # Chat
                ## Sending a message
                Type in the chat box and press enter to talk to your agent.
                """);
    }

    @BeforeEach
    void setup() {
        tool = new UserGuideTool(docsDir);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (docsDir != null && Files.exists(docsDir)) {
            try (var walk = Files.walk(docsDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best-effort temp cleanup
                    }
                });
            }
        }
    }

    // ==================== Metadata ====================

    @Test
    void nameAndMetadata() {
        assertEquals("jclaw_docs", tool.name());
        assertTrue(tool.parallelSafe(), "user-guide lookup is read-only, should be parallel-safe");
        assertFalse(tool.description().isBlank());
        assertTrue(tool.parameters().containsKey("properties"));
    }

    // ==================== Retrieval ====================

    @Test
    void findsRelevantSectionAndCitesPage() {
        var result = tool.execute("{\"query\": \"how do I create a task?\"}", null);
        assertTrue(result.contains("/guide#tasks"),
                "should cite the tasks section with its in-app deep link: " + result);
        assertTrue(result.toLowerCase().contains("creating a task"),
                "should surface the creating-a-task section heading: " + result);
        assertTrue(result.toLowerCase().contains("cron"), "should include the section body: " + result);
    }

    @Test
    void ranksAgentsPageForAgentQuery() {
        var result = tool.execute("{\"query\": \"what is an agent?\"}", null);
        assertTrue(result.contains("/guide#agents"),
                "should cite the agents section with its in-app deep link: " + result);
    }

    @Test
    void citesInAppDeepLinkNotAnExternalUrl() {
        // Regression: jclaw_docs answers used to render source links like
        // https://jclaw-docs because the tool gave the model nothing real to
        // cite. The output must carry the /guide#<id> path and no http(s) URL
        // the model could copy verbatim.
        var result = tool.execute("{\"query\": \"how do I create a task?\"}", null);
        assertTrue(result.contains("/guide#tasks"), "expected in-app deep link: " + result);
        assertFalse(result.contains("http://") || result.contains("https://"),
                "tool output must not contain absolute/external URLs: " + result);
    }

    @Test
    void noMatchReturnsClearMessage() {
        var result = tool.execute("{\"query\": \"kubernetes helm chart deployment\"}", null);
        assertTrue(result.toLowerCase().contains("no relevant section"),
                "off-topic query should report no match: " + result);
    }

    // ==================== Input validation ====================

    @Test
    void blankQueryRejected() {
        var result = tool.execute("{\"query\": \"   \"}", null);
        assertTrue(result.startsWith("Error"), "blank query should be rejected: " + result);
    }

    @Test
    void missingDirReportsUnavailable() {
        var missing = new UserGuideTool(docsDir.resolve("does-not-exist"));
        var result = missing.execute("{\"query\": \"tasks\"}", null);
        assertTrue(result.toLowerCase().contains("not available"),
                "missing guide dir should report unavailable: " + result);
    }
}
