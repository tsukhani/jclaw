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
class TelegramOutboundPlannerTest extends UnitTest {

    // ── Null / trivial inputs ──

    @Test
    void emptyMarkdownReturnsEmptyList() {
        var segments = TelegramOutboundPlanner.plan("", "agent-x");
        assertTrue(segments.isEmpty());
    }

    @Test
    void nullAgentSkipsFileDetection() {
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
    void markdownWithoutLinksReturnsSingleTextSegment() {
        var segments = TelegramOutboundPlanner.plan("Just some prose.", "agent-x");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
    }

    // ── External URLs stay as text (they're real links, not workspace paths) ──

    @Test
    void httpUrlLinksStayInTextSegment() {
        var md = "See [example](https://example.com) for docs.";
        var segments = TelegramOutboundPlanner.plan(md, "agent-x");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
        var ts = (TelegramOutboundPlanner.TextSegment) segments.get(0);
        assertEquals(md, ts.markdown(),
                "external URL must pass through as markdown text for the formatter");
    }

    @Test
    void nonApiAbsolutePathLinksStayInText() {
        // Random absolute paths aren't workspace files — only the JClaw API
        // files URL shape is (tested below). Everything else stays as text.
        var md = "Look at [file](/etc/passwd) maybe.";
        var segments = TelegramOutboundPlanner.plan(md, "agent-x");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
    }

    @Test
    void apiFilesUrlIsResolvedAsWorkspaceFile() {
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
    void anchorLinksStayInText() {
        var md = "Jump to [section](#intro) here.";
        var segments = TelegramOutboundPlanner.plan(md, "agent-x");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
    }

    // ── Resolved workspace files ──

    @Test
    void angleBracketFormResolvesToFileSegment() {
        // Seed a real file in the agent's workspace, then assert the planner
        // produces a FileSegment wrapping it.
        var agent = AgentService.create("planner-angle", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "capture.png", "fake-image-bytes");

        var md = "Here is the shot: [capture.png](<capture.png>) as requested.";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        // JCLAW-123: an image reference produces two FileSegments — a photo
        // (inline preview) and a background document (downloadable original).
        // JCLAW-364: the lead-in prose "Here is the shot:" folds into the
        // photo's caption, so the leading TextSegment is gone — the trailing
        // " as requested." stays a standalone text segment.
        assertEquals(3, segments.size(),
                () -> "photo(captioned) + document + text: " + segments);
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(1) instanceof TelegramOutboundPlanner.FileSegment);
        assertTrue(segments.get(2) instanceof TelegramOutboundPlanner.TextSegment);

        var photoSeg = (TelegramOutboundPlanner.FileSegment) segments.get(0);
        var docSeg = (TelegramOutboundPlanner.FileSegment) segments.get(1);
        assertEquals("capture.png", photoSeg.displayName());
        assertTrue(photoSeg.isImage(), "first image segment must be photo");
        assertEquals(TelegramOutboundPlanner.MediaKind.PHOTO, photoSeg.kind());
        assertEquals("Here is the shot:", photoSeg.caption(),
                "lead-in prose must fold into the photo caption");
        assertFalse(docSeg.isImage(), "second image segment must be document");
        assertNull(docSeg.caption(), "background document must not carry the caption");
        assertEquals(photoSeg.file().getAbsolutePath(), docSeg.file().getAbsolutePath(),
                "photo and document segments must target the same file");
        // JCLAW-126: the quality-duplicate document must be marked background
        // so the channel fires it async and doesn't block subsequent text.
        assertFalse(photoSeg.isBackground(), "photo segment must dispatch synchronously");
        assertTrue(docSeg.isBackground(), "quality-duplicate document must dispatch in background");
    }

    @Test
    void plainFormResolvesToFileSegment() {
        var agent = AgentService.create("planner-plain", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "report.pdf", "fake-pdf-bytes");

        var md = "Your report: [report.pdf](report.pdf)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        // JCLAW-364: "Your report:" folds into the document's caption, leaving
        // a single document segment (no standalone leading text).
        assertEquals(1, segments.size(), () -> "captioned document only: " + segments);
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.FileSegment);
        var fs = (TelegramOutboundPlanner.FileSegment) segments.get(0);
        assertFalse(fs.isImage(), "pdf must be classified as document");
        assertEquals(TelegramOutboundPlanner.MediaKind.DOCUMENT, fs.kind());
        assertEquals("Your report:", fs.caption(),
                "lead-in prose must fold into the document caption");
    }

    @Test
    void imageExtensionClassificationIsCaseInsensitive() {
        var agent = AgentService.create("planner-case", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "SHOT.JPG", "bytes");
        var segments = TelegramOutboundPlanner.plan("[img](<SHOT.JPG>)", agent.name);
        var fs = (TelegramOutboundPlanner.FileSegment) segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment)
                .findFirst().orElseThrow();
        assertTrue(fs.isImage(), "uppercase .JPG must still be image");
    }

    @Test
    void webpAndGifAreImagesPdfAndHtmlAreDocuments() {
        assertTrue(TelegramOutboundPlanner.isImageFilename("foo.webp"));
        assertTrue(TelegramOutboundPlanner.isImageFilename("foo.gif"));
        assertTrue(TelegramOutboundPlanner.isImageFilename("foo.jpeg"));
        assertFalse(TelegramOutboundPlanner.isImageFilename("foo.pdf"));
        assertFalse(TelegramOutboundPlanner.isImageFilename("foo.html"));
        assertFalse(TelegramOutboundPlanner.isImageFilename("foo.docx"));
        assertFalse(TelegramOutboundPlanner.isImageFilename("foo"));
        assertFalse(TelegramOutboundPlanner.isImageFilename(null));
    }

    // ── JCLAW-364: MIME/extension routing to native media kinds ──

    @Test
    void classifyRoutesByExtensionWithDocumentFallback() {
        // Images → PHOTO.
        assertEquals(TelegramOutboundPlanner.MediaKind.PHOTO,
                TelegramOutboundPlanner.classify("a.png"));
        // OGG/Opus → VOICE (case-insensitive).
        assertEquals(TelegramOutboundPlanner.MediaKind.VOICE,
                TelegramOutboundPlanner.classify("note.ogg"));
        assertEquals(TelegramOutboundPlanner.MediaKind.VOICE,
                TelegramOutboundPlanner.classify("NOTE.OPUS"));
        // Music / spoken audio → AUDIO.
        assertEquals(TelegramOutboundPlanner.MediaKind.AUDIO,
                TelegramOutboundPlanner.classify("song.mp3"));
        assertEquals(TelegramOutboundPlanner.MediaKind.AUDIO,
                TelegramOutboundPlanner.classify("clip.m4a"));
        // Video → VIDEO.
        assertEquals(TelegramOutboundPlanner.MediaKind.VIDEO,
                TelegramOutboundPlanner.classify("clip.mp4"));
        assertEquals(TelegramOutboundPlanner.MediaKind.VIDEO,
                TelegramOutboundPlanner.classify("clip.mov"));
        // Unknown / extension-less / null → DOCUMENT fallback.
        assertEquals(TelegramOutboundPlanner.MediaKind.DOCUMENT,
                TelegramOutboundPlanner.classify("report.pdf"));
        assertEquals(TelegramOutboundPlanner.MediaKind.DOCUMENT,
                TelegramOutboundPlanner.classify("mystery.xyz"));
        assertEquals(TelegramOutboundPlanner.MediaKind.DOCUMENT,
                TelegramOutboundPlanner.classify("noext"));
        assertEquals(TelegramOutboundPlanner.MediaKind.DOCUMENT,
                TelegramOutboundPlanner.classify(null));
    }

    @Test
    void oggFileRoutesToVoiceSegment() {
        var agent = AgentService.create("planner-voice", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "memo.ogg", "opus-bytes");
        var segments = TelegramOutboundPlanner.plan("[memo](<memo.ogg>)", agent.name);
        var fs = onlyFileSegment(segments);
        assertEquals(TelegramOutboundPlanner.MediaKind.VOICE, fs.kind());
        assertFalse(fs.isImage(), "voice must not classify as image");
    }

    @Test
    void mp3FileRoutesToAudioSegment() {
        var agent = AgentService.create("planner-audio", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "track.mp3", "mp3-bytes");
        var segments = TelegramOutboundPlanner.plan("[track](<track.mp3>)", agent.name);
        var fs = onlyFileSegment(segments);
        assertEquals(TelegramOutboundPlanner.MediaKind.AUDIO, fs.kind());
    }

    @Test
    void mp4FileRoutesToVideoSegment() {
        var agent = AgentService.create("planner-video", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "demo.mp4", "mp4-bytes");
        var segments = TelegramOutboundPlanner.plan("[demo](<demo.mp4>)", agent.name);
        var fs = onlyFileSegment(segments);
        assertEquals(TelegramOutboundPlanner.MediaKind.VIDEO, fs.kind());
    }

    @Test
    void unknownExtensionRoutesToDocumentSegment() {
        var agent = AgentService.create("planner-unknown", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "data.bin", "bytes");
        var segments = TelegramOutboundPlanner.plan("[data](<data.bin>)", agent.name);
        var fs = onlyFileSegment(segments);
        assertEquals(TelegramOutboundPlanner.MediaKind.DOCUMENT, fs.kind());
        assertFalse(fs.isImage());
    }

    @Test
    void adjacentProseFoldsIntoVoiceCaption() {
        var agent = AgentService.create("planner-voice-caption", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "memo.ogg", "opus-bytes");
        var segments = TelegramOutboundPlanner.plan(
                "Here is the recap: [memo](<memo.ogg>)", agent.name);
        // Single voice segment — the lead-in prose folded into the caption, so
        // there's no standalone leading text bubble.
        assertEquals(1, segments.size(), () -> "captioned voice only: " + segments);
        var fs = onlyFileSegment(segments);
        assertEquals(TelegramOutboundPlanner.MediaKind.VOICE, fs.kind());
        assertEquals("Here is the recap:", fs.caption());
    }

    @Test
    void overlongProseStaysSeparateAndIsNotCaptioned() {
        var agent = AgentService.create("planner-longcap", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "track.mp3", "mp3-bytes");
        // > 1024 chars: too long for a Telegram caption — must remain a
        // standalone text message rather than be silently truncated.
        var longProse = "x".repeat(1100);
        var segments = TelegramOutboundPlanner.plan(
                longProse + " [track](<track.mp3>)", agent.name);
        assertEquals(2, segments.size(), () -> "leading text + audio: " + segments);
        assertTrue(segments.get(0) instanceof TelegramOutboundPlanner.TextSegment);
        var fs = (TelegramOutboundPlanner.FileSegment) segments.get(1);
        assertEquals(TelegramOutboundPlanner.MediaKind.AUDIO, fs.kind());
        assertNull(fs.caption(), "over-limit prose must not become a caption");
    }

    /** Return the single FileSegment in {@code segments}, failing if not exactly one. */
    private static TelegramOutboundPlanner.FileSegment onlyFileSegment(
            java.util.List<TelegramOutboundPlanner.Segment> segments) {
        var files = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment)
                .map(s -> (TelegramOutboundPlanner.FileSegment) s)
                .toList();
        assertEquals(1, files.size(), () -> "expected exactly one FileSegment: " + segments);
        return files.get(0);
    }

    // ── Fallback: unresolvable paths ──

    @Test
    void nonexistentWorkspacePathFallsBackToText() {
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
    void pathTraversalAttemptIsRejected() {
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
    void multipleFilesProduceOneSegmentEachWithDocumentDuplicatesForImages() {
        var agent = AgentService.create("planner-multi", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "a.png", "a");
        AgentService.writeWorkspaceFile(agent.name, "b.pdf", "b");
        AgentService.writeWorkspaceFile(agent.name, "c.png", "c");

        var md = "intro [a.png](<a.png>) middle [b.pdf](<b.pdf>) more [c.png](<c.png>) outro";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);

        // JCLAW-123: images (a.png, c.png) each emit photo+document pairs;
        // PDFs emit a single document. JCLAW-364: each lead-in prose run folds
        // into the following foreground media's caption, so the standalone
        // "intro", "middle", "more" text segments are gone. Result:
        // photo(a,"intro") + bgdoc(a) + doc(b,"middle") + photo(c,"more") +
        // bgdoc(c) + text(" outro") = 6 segments.
        assertEquals(6, segments.size(),
                () -> "a-photo, a-doc, b-doc, c-photo, c-doc, outro: " + segments);

        var fsAPhoto = (TelegramOutboundPlanner.FileSegment) segments.get(0);
        var fsADoc = (TelegramOutboundPlanner.FileSegment) segments.get(1);
        var fsBDoc = (TelegramOutboundPlanner.FileSegment) segments.get(2);
        var fsCPhoto = (TelegramOutboundPlanner.FileSegment) segments.get(3);
        var fsCDoc = (TelegramOutboundPlanner.FileSegment) segments.get(4);

        assertTrue(fsAPhoto.isImage(), "a.png first pass must be photo");
        assertEquals("intro", fsAPhoto.caption(), "intro prose folds into a.png photo caption");
        assertFalse(fsADoc.isImage(), "a.png second pass must be document");
        assertNull(fsADoc.caption(), "background document keeps no caption");
        assertFalse(fsBDoc.isImage(), "b.pdf must be document only");
        assertEquals("middle", fsBDoc.caption(), "middle prose folds into b.pdf document caption");
        assertTrue(fsCPhoto.isImage(), "c.png first pass must be photo");
        assertEquals("more", fsCPhoto.caption(), "more prose folds into c.png photo caption");
        assertFalse(fsCDoc.isImage(), "c.png second pass must be document");
        assertEquals(TelegramOutboundPlanner.TextSegment.class, segments.get(5).getClass(),
                "trailing outro stays a standalone text segment");
    }

    @Test
    void leadingFileProducesNoEmptyLeadingTextSegment() {
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
    void adjacentFilesWithNoTextBetweenDontCreateEmptyTextSegment() {
        var agent = AgentService.create("planner-adjacent", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "a.png", "a");
        AgentService.writeWorkspaceFile(agent.name, "b.png", "b");
        // Two file links back-to-back with only a space between them — that
        // single-space text segment is semantically empty for Telegram but
        // shouldn't cause a zero-length send.
        var md = "[a.png](<a.png>) [b.png](<b.png>)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        // JCLAW-365: the two adjacent photos coalesce into one album; the
        // single-space separator is a no-op and is dropped (never emitted as a
        // zero-length text segment). Each image still keeps its background
        // original document, emitted after the album.
        var group = onlyMediaGroup(segments);
        assertEquals(2, group.items().size(), () -> "both photos must group into one album: " + segments);
        long bgDocs = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment fs && fs.isBackground())
                .count();
        assertEquals(2, bgDocs, () -> "both background original documents preserved: " + segments);
        boolean blankText = segments.stream()
                .anyMatch(s -> s instanceof TelegramOutboundPlanner.TextSegment ts && ts.markdown().isBlank());
        assertFalse(blankText, () -> "the single-space separator must not survive as a blank text segment: " + segments);
    }

    // ── JCLAW-104: dedupe same-file references ──

    @Test
    void duplicateFileReferenceEmitsPhotoPlusDocumentAndStripsRedundantLink() {
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
    void markdownImagePrefixBangIsNotEmittedAsStandaloneText() {
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

    // ── JCLAW-365: media-group coalescing ──

    /** Count MediaGroupSegments in {@code segments}. */
    private static long mediaGroupCount(java.util.List<TelegramOutboundPlanner.Segment> segments) {
        return segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.MediaGroupSegment)
                .count();
    }

    /** The single MediaGroupSegment in {@code segments}, failing if not exactly one. */
    private static TelegramOutboundPlanner.MediaGroupSegment onlyMediaGroup(
            java.util.List<TelegramOutboundPlanner.Segment> segments) {
        var groups = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.MediaGroupSegment)
                .map(s -> (TelegramOutboundPlanner.MediaGroupSegment) s)
                .toList();
        assertEquals(1, groups.size(), () -> "expected exactly one MediaGroupSegment: " + segments);
        return groups.get(0);
    }

    @Test
    void threeImagesCoalesceIntoOneMediaGroup() {
        var agent = AgentService.create("planner-album-3", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "a.png", "a");
        AgentService.writeWorkspaceFile(agent.name, "b.png", "b");
        AgentService.writeWorkspaceFile(agent.name, "c.png", "c");

        var md = "[a.png](<a.png>) [b.png](<b.png>) [c.png](<c.png>)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);

        var group = onlyMediaGroup(segments);
        assertEquals(3, group.items().size(), () -> "three photos must group into one album: " + segments);
        for (var item : group.items()) {
            assertEquals(TelegramOutboundPlanner.MediaKind.PHOTO, item.kind());
        }
        // JCLAW-123 background-original documents must survive — one per image,
        // emitted after the album.
        long bgDocs = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment fs
                        && fs.isBackground())
                .count();
        assertEquals(3, bgDocs, () -> "each image keeps its background original document: " + segments);
    }

    @Test
    void mediaGroupCaptionRidesFirstItem() {
        var agent = AgentService.create("planner-album-cap", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "a.png", "a");
        AgentService.writeWorkspaceFile(agent.name, "b.png", "b");

        // Lead-in prose folds into the first photo's caption, which becomes the
        // album caption.
        var md = "Here are the shots: [a.png](<a.png>) [b.png](<b.png>)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);

        var group = onlyMediaGroup(segments);
        assertEquals(2, group.items().size());
        assertEquals("Here are the shots:", group.caption(),
                "lead-in prose must become the album caption (first item)");
    }

    @Test
    void singleImageDoesNotCoalesce() {
        var agent = AgentService.create("planner-album-1", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "solo.png", "x");
        var segments = TelegramOutboundPlanner.plan("[solo.png](<solo.png>)", agent.name);
        // A lone image keeps the existing single-send path: photo + background
        // document, no MediaGroupSegment.
        assertEquals(0, mediaGroupCount(segments),
                () -> "a single image must not coalesce into a media group: " + segments);
        long photos = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment fs && fs.isImage())
                .count();
        assertEquals(1, photos, () -> "single image keeps its standalone photo segment: " + segments);
    }

    @Test
    void photoDocumentMixGroupsOnlyThePhotos() {
        var agent = AgentService.create("planner-album-mix", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "a.png", "a");
        AgentService.writeWorkspaceFile(agent.name, "doc.pdf", "d");
        AgentService.writeWorkspaceFile(agent.name, "b.png", "b");

        // a.png and b.png are separated by a document — they are NOT a
        // consecutive photo run, so neither side reaches 2 and no album forms.
        var md = "[a.png](<a.png>) [doc.pdf](<doc.pdf>) [b.png](<b.png>)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        assertEquals(0, mediaGroupCount(segments),
                () -> "a document between two photos breaks the run; no album: " + segments);

        // Now two photos truly adjacent + a trailing document → the photos group,
        // the document stays individual.
        var agent2 = AgentService.create("planner-album-mix2", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent2.name, "a.png", "a");
        AgentService.writeWorkspaceFile(agent2.name, "b.png", "b");
        AgentService.writeWorkspaceFile(agent2.name, "doc.pdf", "d");
        var md2 = "[a.png](<a.png>) [b.png](<b.png>) [doc.pdf](<doc.pdf>)";
        var segments2 = TelegramOutboundPlanner.plan(md2, agent2.name);
        var group = onlyMediaGroup(segments2);
        assertEquals(2, group.items().size(), () -> "the two adjacent photos must group: " + segments2);
        long standaloneDocs = segments2.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment fs
                        && !fs.isImage() && !fs.isBackground())
                .count();
        assertEquals(1, standaloneDocs,
                () -> "the pdf must stay an individual foreground document, not grouped: " + segments2);
    }

    @Test
    void elevenPhotosChunkIntoTwoGroups() {
        var agent = AgentService.create("planner-album-11", "openrouter", "gpt-4.1");
        var md = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            AgentService.writeWorkspaceFile(agent.name, "p" + i + ".png", "x");
            md.append("[p").append(i).append(".png](<p").append(i).append(".png>) ");
        }
        var segments = TelegramOutboundPlanner.plan(md.toString(), agent.name);

        var groups = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.MediaGroupSegment)
                .map(s -> (TelegramOutboundPlanner.MediaGroupSegment) s)
                .toList();
        assertEquals(1, groups.size(), () -> "11 photos -> one 10-item album + a lone 11th: " + segments);
        assertEquals(10, groups.get(0).items().size(), "the album holds 10 (the Telegram cap)");
        // The remaining (11th) photo is a single FOREGROUND FileSegment, NOT a 1-item
        // group (sendMediaGroup rejects < 2). Background original-quality documents
        // (JCLAW-123) are emitted per image separately and are excluded here.
        var foreground = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment)
                .map(s -> (TelegramOutboundPlanner.FileSegment) s)
                .filter(f -> !f.isBackground())
                .toList();
        assertEquals(1, foreground.size(), () -> "the 11th photo must be a lone foreground FileSegment: " + segments);
        assertEquals("p10.png", foreground.get(0).displayName(), "the lone send must be the 11th photo");
    }

    @Test
    void videoAndPhotoGroupTogether() {
        var agent = AgentService.create("planner-album-av", "openrouter", "gpt-4.1");
        AgentService.writeWorkspaceFile(agent.name, "clip.mp4", "v");
        AgentService.writeWorkspaceFile(agent.name, "shot.png", "p");

        // Telegram groups photos AND videos in one album — a video followed by a
        // photo is a valid 2-item group.
        var md = "[clip.mp4](<clip.mp4>) [shot.png](<shot.png>)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        var group = onlyMediaGroup(segments);
        assertEquals(2, group.items().size(), () -> "video + photo must group together: " + segments);
        assertEquals(TelegramOutboundPlanner.MediaKind.VIDEO, group.items().get(0).kind());
        assertEquals(TelegramOutboundPlanner.MediaKind.PHOTO, group.items().get(1).kind());
    }

    @Test
    void differentFilesProduceMultipleFileSegments() {
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

        // JCLAW-123: each image produces a foreground photo + a background
        // document. JCLAW-365: the lead-in/connector prose folds into the
        // captions (the " and " connector folds into second.png's caption),
        // leaving the two foreground photos consecutive — so they now coalesce
        // into ONE media-group album. Dedupe correctness is still the point of
        // this test: both DISTINCT files must survive (the album carries two
        // distinct items, and each keeps its own background original document).
        var group = onlyMediaGroup(segments);
        assertEquals(2, group.items().size(),
                () -> "two distinct images must group into a 2-item album: " + segments);
        assertEquals("First", group.items().get(0).displayName(),
                () -> "first distinct file preserved in album order: " + segments);
        assertEquals("Second", group.items().get(1).displayName(),
                () -> "second distinct file preserved in album order: " + segments);
        long bgDocs = segments.stream()
                .filter(s -> s instanceof TelegramOutboundPlanner.FileSegment fs && fs.isBackground())
                .count();
        assertEquals(2, bgDocs,
                () -> "each distinct image keeps its background original document: " + segments);
    }
}
