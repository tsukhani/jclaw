import channels.SlackOutboundPlanner;
import channels.TelegramOutboundPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;

/**
 * Unit tests for {@link SlackOutboundPlanner#fileSegments} (JCLAW-345). The link
 * detection + workspace-path resolution is the shared {@link TelegramOutboundPlanner}
 * logic (tested in {@code TelegramOutboundPlannerTest}); here we pin the
 * Slack-specific flattening: a media reference must upload each file ONCE (Telegram
 * emits an image as both an inline photo and a downloadable document pointing at the
 * same file), and plain prose uploads nothing.
 */
class SlackOutboundPlannerTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void imageLinkYieldsExactlyOneUpload() {
        // An image produces 2 Telegram FileSegments (photo + background doc) for the
        // same file; Slack dedups to a single upload.
        var agent = AgentService.create("sk-out-img", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "capture.png", "fake-image-bytes");

        var files = SlackOutboundPlanner.fileSegments(
                "Here is the shot: [capture.png](<capture.png>) as requested.", agent.name);

        assertEquals(1, files.size(), () -> "image must dedup to one upload, got: " + files);
        assertEquals("capture.png", files.get(0).displayName());
        assertTrue(files.get(0).file().exists(), "resolved file must exist");
    }

    @Test
    void nonImageFileLinkYieldsOneUpload() {
        var agent = AgentService.create("sk-out-doc", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "report.pdf", "fake-pdf");

        var files = SlackOutboundPlanner.fileSegments("See [the report](<report.pdf>).", agent.name);

        assertEquals(1, files.size());
        assertTrue(files.get(0).file().getName().contains("report"));
    }

    @Test
    void plainTextYieldsNoUploads() {
        var agent = AgentService.create("sk-out-text", "openrouter", "gpt-4.1");
        assertTrue(SlackOutboundPlanner.fileSegments("Just some prose, no files.", agent.name).isEmpty());
    }

    @Test
    void externalUrlIsNotUploaded() {
        // External https links are left in the prose, never fetched + re-uploaded.
        var agent = AgentService.create("sk-out-ext", "openrouter", "gpt-4.1");
        assertTrue(SlackOutboundPlanner.fileSegments(
                "See [docs](https://example.com/x.png) here.", agent.name).isEmpty());
    }

    @Test
    void nullAgentYieldsNoUploads() {
        assertTrue(SlackOutboundPlanner.fileSegments("[file](x.png)", null).isEmpty());
    }
}
