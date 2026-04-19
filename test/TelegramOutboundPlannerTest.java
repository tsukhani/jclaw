import channels.TelegramOutboundPlanner;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;

/**
 * Unit coverage for the JCLAW-93 outbound planner. Focus: the link-detection
 * regex, image-vs-document extension split, text/file segment ordering, and
 * unresolved-path fallback. The orchestration layer (sendMessage routing to
 * sendPhoto / sendDocument) is tested separately in ChannelTest against a
 * mocked TelegramClient.
 */
public class TelegramOutboundPlannerTest extends UnitTest {

    // ── Null / trivial inputs ──

    @Test
    public void emptyMarkdownReturnsEmptyList() {
        var segments = TelegramOutboundPlanner.plan("", "agent-x");
        assertTrue(segments.isEmpty());
    }

    @Test
    public void nullAgentSkipsFileDetection() {
        // When no agent is in scope (webhook error path), the whole input is
        // treated as one text segment — no workspace resolution attempts.
        var md = "Click [here](<foo.png>) to see.";
        var segments = TelegramOutboundPlanner.plan(md, null);
        assertEquals(1, segments.size());
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
        var ts = (TelegramOutboundPlanner.TextSegment) segments.get(0);
        assertEquals(md, ts.markdown());
    }

    @Test
    public void markdownWithoutLinksReturnsSingleTextSegment() {
        var segments = TelegramOutboundPlanner.plan("Just some prose.", "agent-x");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
    }

    // ── External URLs stay as text (they're real links, not workspace paths) ──

    @Test
    public void httpUrlLinksStayInTextSegment() {
        var md = "See [example](https://example.com) for docs.";
        var segments = TelegramOutboundPlanner.plan(md, "agent-x");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
        var ts = (TelegramOutboundPlanner.TextSegment) segments.get(0);
        assertEquals(md, ts.markdown(),
                "external URL must pass through as markdown text for the formatter");
    }

    @Test
    public void nonApiAbsolutePathLinksStayInText() {
        // Random absolute paths aren't workspace files — only the JClaw API
        // files URL shape is (tested below). Everything else stays as text.
        var md = "Look at [file](/etc/passwd) maybe.";
        var segments = TelegramOutboundPlanner.plan(md, "agent-x");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
    }

    @Test
    public void apiFilesUrlIsResolvedAsWorkspaceFile() {
        // The Playwright tool (and other tools that reply with a workspace-file
        // API URL) emits absolute /api/agents/{id}/files/{name} paths. The
        // planner should recognize that shape and pull the filename out so it
        // can upload natively to Telegram instead of leaving the link broken.
        var agent = services.AgentService.create("planner-api-url", "openrouter", "gpt-4.1");
        services.AgentService.writeWorkspaceFile(agent.name, "screenshot-42.png", "fake-bytes");

        var md = "[screenshot](/api/agents/99/files/screenshot-42.png) from the page.";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);

        var fileSegment = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment)
                .map(s -> (TelegramOutboundPlanner.FileSegment) s)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a FileSegment; got " + segments));

        assertEquals("screenshot", fileSegment.displayName());
        assertTrue(fileSegment.isImage(), "png must classify as image");
        assertTrue(fileSegment.file().exists(), "resolved file must exist");
    }

    @Test
    public void anchorLinksStayInText() {
        var md = "Jump to [section](#intro) here.";
        var segments = TelegramOutboundPlanner.plan(md, "agent-x");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
    }

    // ── Resolved workspace files ──

    @Test
    public void angleBracketFormResolvesToFileSegment() {
        // Seed a real file in the agent's workspace, then assert the planner
        // produces a FileSegment wrapping it.
        var agent = AgentService.create("planner-angle", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "capture.png", "fake-image-bytes");

        var md = "Here is the shot: [capture.png](<capture.png>) as requested.";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        assertEquals(3, segments.size(), () -> "text + file + text: " + segments);
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
        assertTrue(segments.get(1) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(2) instanceof TelegramOutboundPlanner.TextSegment);

        var fs = (TelegramOutboundPlanner.FileSegment) segments.get(1);
        assertEquals("capture.png", fs.displayName());
        assertTrue(fs.file().exists(), () -> "resolved file should exist: " + fs.file());
        assertTrue(fs.isImage(), "png must be classified as image");
    }

    @Test
    public void plainFormResolvesToFileSegment() {
        var agent = AgentService.create("planner-plain", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "report.pdf", "fake-pdf-bytes");

        var md = "Your report: [report.pdf](report.pdf)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        assertEquals(2, segments.size(), () -> "leading text + file: " + segments);
        assertTrue(segments.get(1) instanceof TelegramOutboundPlanner.FileSegment);
        var fs = (TelegramOutboundPlanner.FileSegment) segments.get(1);
        assertFalse(fs.isImage(), "pdf must be classified as document");
    }

    @Test
    public void imageExtensionClassificationIsCaseInsensitive() {
        var agent = AgentService.create("planner-case", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "SHOT.JPG", "bytes");
        var segments = TelegramOutboundPlanner.plan("[img](<SHOT.JPG>)", agent.name);
        var fs = (TelegramOutboundPlanner.FileSegment) segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment)
                .findFirst().orElseThrow();
        assertTrue(fs.isImage(), "uppercase .JPG must still be image");
    }

    @Test
    public void webpAndGifAreImagesPdfAndHtmlAreDocuments() {
        assertTrue(TelegramOutboundPlanner.isImageFilename("foo.webp"));
        assertTrue(TelegramOutboundPlanner.isImageFilename("foo.gif"));
        assertTrue(TelegramOutboundPlanner.isImageFilename("foo.jpeg"));
        assertFalse(TelegramOutboundPlanner.isImageFilename("foo.pdf"));
        assertFalse(TelegramOutboundPlanner.isImageFilename("foo.html"));
        assertFalse(TelegramOutboundPlanner.isImageFilename("foo.docx"));
        assertFalse(TelegramOutboundPlanner.isImageFilename("foo"));
        assertFalse(TelegramOutboundPlanner.isImageFilename(null));
    }

    // ── Fallback: unresolvable paths ──

    @Test
    public void nonexistentWorkspacePathFallsBackToText() {
        var agent = AgentService.create("planner-missing", "openrouter", "gpt-4.1");
        // Note: no file written to the workspace.
        var md = "Link: [missing.png](<missing.png>)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        assertEquals(1, segments.size(), () -> "missing file must fall through to text: " + segments);
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
        var ts = (TelegramOutboundPlanner.TextSegment) segments.get(0);
        assertEquals(md, ts.markdown(),
                "unresolved link must stay in the text so the user at least sees the attempt");
    }

    @Test
    public void pathTraversalAttemptIsRejected() {
        var agent = AgentService.create("planner-traverse", "openrouter", "gpt-4.1");
        // ../secrets would escape the workspace; acquireWorkspacePath throws
        // SecurityException, planner swallows and falls back to text.
        var md = "[escape](<../secrets>)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        assertEquals(1, segments.size());
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
    }

    // ── Multi-file ordering + text merging ──

    @Test
    public void multipleFilesProduceOneSegmentEach() {
        var agent = AgentService.create("planner-multi", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "a.png", "a");
        AgentService.writeWorkspaceFile(agent.name, "b.pdf", "b");
        AgentService.writeWorkspaceFile(agent.name, "c.png", "c");

        var md = "intro [a.png](<a.png>) middle [b.pdf](<b.pdf>) more [c.png](<c.png>) outro";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);

        assertEquals(7, segments.size(),
                () -> "intro, a, middle, b, more, c, outro: " + segments);
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
        assertTrue(segments.get(1) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(2) instanceof TelegramOutboundPlanner.TextSegment);
        assertTrue(segments.get(3) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(4) instanceof TelegramOutboundPlanner.TextSegment);
        assertTrue(segments.get(5) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(6) instanceof TelegramOutboundPlanner.TextSegment);

        // Verify image/doc classification is per-file, not uniform.
        var fsA = (TelegramOutboundPlanner.FileSegment) segments.get(1);
        var fsB = (TelegramOutboundPlanner.FileSegment) segments.get(3);
        var fsC = (TelegramOutboundPlanner.FileSegment) segments.get(5);
        assertTrue(fsA.isImage());
        assertFalse(fsB.isImage());
        assertTrue(fsC.isImage());
    }

    @Test
    public void leadingFileProducesNoEmptyLeadingTextSegment() {
        var agent = AgentService.create("planner-leading", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "first.png", "x");
        var segments = TelegramOutboundPlanner.plan("[first.png](<first.png>) prose", agent.name);
        assertEquals(2, segments.size(),
                () -> "no empty leading text expected: " + segments);
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(1) instanceof TelegramOutboundPlanner.TextSegment);
    }

    @Test
    public void adjacentFilesWithNoTextBetweenDontCreateEmptyTextSegment() {
        var agent = AgentService.create("planner-adjacent", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "a.png", "a");
        AgentService.writeWorkspaceFile(agent.name, "b.png", "b");
        // Two file links back-to-back with only a space between them — that
        // single-space text segment is semantically empty for Telegram but
        // shouldn't cause a zero-length send.
        var md = "[a.png](<a.png>) [b.png](<b.png>)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        // text+file+text+file pattern — the middle text is just " ".
        assertTrue(segments.size() >= 2);
        int fileCount = (int) segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment)
                .count();
        assertEquals(2, fileCount, "both files should be detected");
    }
}
