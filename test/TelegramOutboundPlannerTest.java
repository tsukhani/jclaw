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
        // JCLAW-123: an image reference now produces two FileSegments — a
        // photo (inline preview) and a document (downloadable original).
        assertEquals(4, segments.size(),
                () -> "text + photo + document + text: " + segments);
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
        assertTrue(segments.get(1) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(2) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(3) instanceof TelegramOutboundPlanner.TextSegment);

        var photoSeg = (TelegramOutboundPlanner.FileSegment) segments.get(1);
        var docSeg = (TelegramOutboundPlanner.FileSegment) segments.get(2);
        assertEquals("capture.png", photoSeg.displayName());
        assertTrue(photoSeg.isImage(), "first image segment must be photo");
        assertFalse(docSeg.isImage(), "second image segment must be document");
        assertEquals(photoSeg.file().getAbsolutePath(), docSeg.file().getAbsolutePath(),
                "photo and document segments must target the same file");
        // JCLAW-126: the quality-duplicate document must be marked background
        // so the channel fires it async and doesn't block subsequent text.
        assertFalse(photoSeg.isBackground(), "photo segment must dispatch synchronously");
        assertTrue(docSeg.isBackground(), "quality-duplicate document must dispatch in background");
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
    public void multipleFilesProduceOneSegmentEachWithDocumentDuplicatesForImages() {
        var agent = AgentService.create("planner-multi", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "a.png", "a");
        AgentService.writeWorkspaceFile(agent.name, "b.pdf", "b");
        AgentService.writeWorkspaceFile(agent.name, "c.png", "c");

        var md = "intro [a.png](<a.png>) middle [b.pdf](<b.pdf>) more [c.png](<c.png>) outro";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);

        // JCLAW-123: images (a.png, c.png) each emit photo+document pairs;
        // PDFs emit a single document. Result: text + photo(a) + doc(a) + text
        // + doc(b) + text + photo(c) + doc(c) + text = 9 segments.
        assertEquals(9, segments.size(),
                () -> "intro, a-photo, a-doc, middle, b-doc, more, c-photo, c-doc, outro: " + segments);

        var fsAPhoto = (TelegramOutboundPlanner.FileSegment) segments.get(1);
        var fsADoc = (TelegramOutboundPlanner.FileSegment) segments.get(2);
        var fsBDoc = (TelegramOutboundPlanner.FileSegment) segments.get(4);
        var fsCPhoto = (TelegramOutboundPlanner.FileSegment) segments.get(6);
        var fsCDoc = (TelegramOutboundPlanner.FileSegment) segments.get(7);

        assertTrue(fsAPhoto.isImage(), "a.png first pass must be photo");
        assertFalse(fsADoc.isImage(), "a.png second pass must be document");
        assertFalse(fsBDoc.isImage(), "b.pdf must be document only");
        assertTrue(fsCPhoto.isImage(), "c.png first pass must be photo");
        assertFalse(fsCDoc.isImage(), "c.png second pass must be document");
    }

    @Test
    public void leadingFileProducesNoEmptyLeadingTextSegment() {
        var agent = AgentService.create("planner-leading", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "first.png", "x");
        var segments = TelegramOutboundPlanner.plan("[first.png](<first.png>) prose", agent.name);
        // JCLAW-123: image produces photo + document, then text tail. No
        // empty leading text segment.
        assertEquals(3, segments.size(),
                () -> "no empty leading text; photo + document + tail: " + segments);
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(1) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(2) instanceof TelegramOutboundPlanner.TextSegment);
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
        assertTrue(segments.size() >= 2);
        int fileCount = (int) segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment)
                .count();
        // JCLAW-123: each image produces photo+document, so two images → 4 segments.
        assertEquals(4, fileCount, "both images should produce photo + document pairs");
    }

    // ── JCLAW-104: dedupe same-file references ──

    @Test
    public void duplicateFileReferenceEmitsPhotoPlusDocumentAndStripsRedundantLink() {
        // JCLAW-104 originally asked that the planner dedupe duplicate file
        // references so Telegram didn't receive two sendPhoto uploads for the
        // same file. JCLAW-123 revisits this: for images, we WANT two
        // deliveries — a photo (inline preview) and a document (downloadable
        // original) — so the user gets both affordances even when the server
        // is behind localhost with no public URL.
        //
        // What we also need: the raw markdown link for the duplicate
        // reference must NOT leak into the text segment. Pre-JCLAW-123 it
        // rendered as a visible-but-broken href against the localhost-only
        // /api/agents/N/files/... URL.
        var agent = AgentService.create("planner-dedupe", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "screenshot-1776748706808.png", "png-bytes");

        var md = """
                ![Screenshot](/api/agents/1/files/screenshot-1776748706808.png)

                Here is the page. You can also reference the [screenshot](/api/agents/1/files/screenshot-1776748706808.png) directly.""";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);

        int fileCount = (int) segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment)
                .count();
        assertEquals(2, fileCount,
                "image must emit one photo + one document; duplicate link must NOT "
                        + "produce a third FileSegment; got: " + segments);

        var photoSegs = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment fs && fs.isImage())
                .count();
        var docSegs = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment fs && !fs.isImage())
                .count();
        assertEquals(1, photoSegs, "exactly one photo segment");
        assertEquals(1, docSegs, "exactly one document segment");

        // The duplicate [screenshot](url) markdown must be stripped from the
        // final text — leaving it in would render as a broken anchor pointing
        // at a localhost-only URL.
        var joinedText = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.TextSegment)
                .map(s -> ((TelegramOutboundPlanner.TextSegment) s).markdown())
                .reduce("", String::concat);
        assertFalse(joinedText.contains("/api/agents/1/files/screenshot-1776748706808.png"),
                "duplicate file URL must not leak into text: " + joinedText);
        assertFalse(joinedText.contains("[screenshot]"),
                "duplicate markdown link must be stripped: " + joinedText);
    }

    @Test
    public void markdownImagePrefixBangIsNotEmittedAsStandaloneText() {
        // JCLAW-104 three-fix patch: post-buildImagePrefix, every screenshot
        // turn's final content leads with "![Screenshot](url)". Pre-patch
        // the planner's LINK_PATTERN matched "[Screenshot](url)" starting
        // at position 1, leaving the `!` at position 0 as "text before the
        // match" — which Telegram then delivered as a standalone message
        // containing literally "!". The LINK_PATTERN fix makes the `!`
        // optional-capture so the match envelope includes it when present.
        var agent = AgentService.create("planner-bang", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "screenshot-999.png", "png");

        var md = """
                ![Screenshot](/api/agents/1/files/screenshot-999.png)

                The page shows a dark header.""";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);

        // No segment should be the bare "!" (possibly with whitespace).
        for (var seg : segments) {
            if (seg instanceof TelegramOutboundPlanner.TextSegment ts) {
                assertNotEquals("!", ts.markdown().trim(),
                        "planner must not emit a standalone '!' bubble; "
                                + "the markdown-image prefix bang belongs to the image match: "
                                + segments);
            }
        }
        // And the file segment must still be produced (JCLAW-123: image
        // produces photo + document = 2 FileSegments).
        long fileCount = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment)
                .count();
        assertEquals(2, fileCount, "image must emit photo + document: " + segments);
    }

    @Test
    public void differentFilesProduceMultipleFileSegments() {
        // Dedupe is per-canonical-file, not per-URL-string: different files
        // with different names must still produce independent FileSegments.
        // This protects against over-zealous deduplication if the fix is
        // ever refactored to match on alt text / display / path prefix.
        var agent = AgentService.create("planner-distinct", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "first.png", "first-bytes");
        AgentService.writeWorkspaceFile(agent.name, "second.png", "second-bytes");

        var md = "![First](/api/agents/1/files/first.png) and "
                + "![Second](/api/agents/1/files/second.png).";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);

        int fileCount = (int) segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment)
                .count();
        // JCLAW-123: each image produces photo + document, so two distinct
        // images now produce four FileSegments (2 photo + 2 document).
        assertEquals(4, fileCount,
                "two distinct images should produce two photo + two document segments: "
                        + segments);
    }
}
