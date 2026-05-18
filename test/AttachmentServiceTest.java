import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.AttachmentService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * JCLAW-315: cover the attachment lifecycle helpers — staging → finalize,
 * base64/data-URL encode, and the image/audio detection used by the model
 * capability gates.
 *
 * <p>Note on AC drift: the JCLAW-315 ticket mentions {@code persistFromUrl}
 * and SSRF protection, but the current {@link AttachmentService} surface
 * is the staging-finalize flow (driven by {@code ApiChatController.uploadChatFiles}).
 * There is no remote-URL ingestion path, so the SSRF / disk-full / MIME
 * mismatch ACs are inapplicable as written; see the test report.
 */
class AttachmentServiceTest extends UnitTest {

    private Agent agent;
    private Conversation conversation;
    private Message message;
    private Path stagingDir;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        agent = new Agent();
        agent.name = "attachment-test-" + UUID.randomUUID().toString().substring(0, 6);
        agent.modelProvider = "openrouter";
        agent.modelId = "openai/gpt-4o";
        agent.save();

        conversation = new Conversation();
        conversation.agent = agent;
        conversation.channelType = "web";
        conversation.peerId = "tester";
        conversation.save();

        message = new Message();
        message.conversation = conversation;
        message.role = MessageRole.USER.value;
        message.content = "see attached";
        message.save();

        // Pre-create the staging dir so each test can drop a synthetic upload.
        stagingDir = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        Files.createDirectories(stagingDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Wipe the agent workspace so files don't bleed between tests.
        var ws = AgentService.workspacePath(agent.name);
        deleteRecursive(ws);
    }

    // ── input validation ────────────────────────────────────────────

    @Test
    void finalizeRejectsBlankAttachmentId() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> AttachmentService.finalizeAttachment(agent, message,
                        new AttachmentService.Input("", "f.png", "image/png", 1L, "IMAGE")));
        assertTrue(ex.getMessage().toLowerCase().contains("attachmentid"),
                "error must mention attachmentId: " + ex.getMessage());
    }

    @Test
    void finalizeRejectsNullAttachmentId() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> AttachmentService.finalizeAttachment(agent, message,
                        new AttachmentService.Input(null, "f.png", "image/png", 1L, "IMAGE")));
        assertTrue(ex.getMessage().toLowerCase().contains("attachmentid"));
    }

    @Test
    void finalizeRejectsMissingStagedFile() {
        // Valid id, but nothing in staging matches it.
        var uuid = UUID.randomUUID().toString();
        var ex = assertThrows(IllegalStateException.class,
                () -> AttachmentService.finalizeAttachment(agent, message,
                        new AttachmentService.Input(uuid, "f.png", "image/png", 1L, "IMAGE")));
        assertTrue(ex.getMessage().contains(uuid),
                "error must include the missing uuid: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("staged"),
                "error must explain it was looking in staging: " + ex.getMessage());
    }

    // ── happy-path MIME sniffing ────────────────────────────────────

    @Test
    void finalizePersistsPngWithImageKind() throws Exception {
        var uuid = stageFile("upload.png", pngBytes());
        var att = AttachmentService.finalizeAttachment(agent, message,
                new AttachmentService.Input(uuid, "screenshot.png", "image/png",
                        (long) pngBytes().length, "IMAGE"));
        assertEquals("image/png", att.mimeType, "Tika must sniff PNG correctly");
        assertEquals(MessageAttachment.KIND_IMAGE, att.kind);
        assertEquals("screenshot.png", att.originalFilename,
                "original filename round-trips from Input");
        assertTrue(att.storagePath.startsWith(agent.name + "/attachments/" + conversation.id + "/"),
                "storage path must land under the conversation: " + att.storagePath);
        assertEquals(pngBytes().length, att.sizeBytes,
                "server-side size must come from on-disk file, not client claim");
    }

    @Test
    void finalizePersistsPdfWithFileKind() throws Exception {
        var uuid = stageFile("doc.pdf", pdfBytes());
        var att = AttachmentService.finalizeAttachment(agent, message,
                new AttachmentService.Input(uuid, "report.pdf", "application/pdf",
                        (long) pdfBytes().length, "FILE"));
        assertEquals("application/pdf", att.mimeType);
        assertEquals(MessageAttachment.KIND_FILE, att.kind);
    }

    @Test
    void finalizePersistsMpegAudioWithAudioKind() throws Exception {
        var uuid = stageFile("clip.mp3", mp3Bytes());
        var att = AttachmentService.finalizeAttachment(agent, message,
                new AttachmentService.Input(uuid, "clip.mp3", "audio/mpeg",
                        (long) mp3Bytes().length, "AUDIO"));
        assertTrue(att.mimeType.startsWith("audio/"),
                "Tika must sniff MP3 as audio/* : got " + att.mimeType);
        assertEquals(MessageAttachment.KIND_AUDIO, att.kind);
    }

    @Test
    void finalizeRespectsAudioWebmHint() throws Exception {
        // Browser voice notes upload as audio-only WebM containers that
        // Tika still classifies as video/webm. The upload endpoint already
        // disambiguates by passing input.mimeType = "audio/webm"; the
        // finalize step must honor that hint and store as KIND_AUDIO.
        var webm = webmBytes();
        var uuid = stageFile("voice.webm", webm);
        var att = AttachmentService.finalizeAttachment(agent, message,
                new AttachmentService.Input(uuid, "voice.webm", "audio/webm",
                        (long) webm.length, "AUDIO"));
        // Either Tika sniffed it as audio/webm directly, or the hint
        // override flipped video/webm → audio/webm. Both are acceptable;
        // assert the persisted shape.
        assertEquals("audio/webm", att.mimeType,
                "audio hint must override video/webm misclassification: " + att.mimeType);
        assertEquals(MessageAttachment.KIND_AUDIO, att.kind);
    }

    @Test
    void finalizeUsesStagedLeafWhenInputFilenameIsNull() throws Exception {
        var uuid = stageFile("real.png", pngBytes());
        // null originalFilename in Input → fall back to the on-disk staged
        // leaf, which is `<uuid>.png` (the staging-side rename happens at
        // upload time). The point is "never null", not "round-trip the
        // user filename" — that's what input.originalFilename() carries.
        var att = AttachmentService.finalizeAttachment(agent, message,
                new AttachmentService.Input(uuid, null, "image/png",
                        (long) pngBytes().length, "IMAGE"));
        assertNotNull(att.originalFilename, "null filename must fall back, not stay null");
        assertTrue(att.originalFilename.startsWith(uuid),
                "fallback must use the staged leaf (uuid-prefixed): " + att.originalFilename);
    }

    // ── read-as-base64 / data-URL ──────────────────────────────────

    @Test
    void readAsBase64RoundTrips() throws Exception {
        var bytes = "hello there".getBytes();
        var uuid = stageFile("greet.bin", bytes);
        var att = AttachmentService.finalizeAttachment(agent, message,
                new AttachmentService.Input(uuid, "greet.bin", "application/octet-stream",
                        (long) bytes.length, "FILE"));

        var b64 = AttachmentService.readAsBase64(att);
        var decoded = java.util.Base64.getDecoder().decode(b64);
        assertArrayEquals(bytes, decoded, "base64 must round-trip the on-disk bytes");
    }

    @Test
    void readAsDataUrlPrefixesMimeType() throws Exception {
        var bytes = pngBytes();
        var uuid = stageFile("p.png", bytes);
        var att = AttachmentService.finalizeAttachment(agent, message,
                new AttachmentService.Input(uuid, "p.png", "image/png",
                        (long) bytes.length, "IMAGE"));

        var dataUrl = AttachmentService.readAsDataUrl(att);
        assertTrue(dataUrl.startsWith("data:image/png;base64,"),
                "data-URL prefix must include MIME type: " + dataUrl.substring(0, Math.min(40, dataUrl.length())));
        // The bytes after the comma must round-trip.
        var b64 = dataUrl.substring("data:image/png;base64,".length());
        assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(b64));
    }

    // ── anyImage / anyAudio gates ──────────────────────────────────

    @Test
    void anyImageDetectsImageInput() {
        var inputs = List.of(
                new AttachmentService.Input("u1", "a.pdf", "application/pdf", 1L, "FILE"),
                new AttachmentService.Input("u2", "b.png", "image/png", 1L, "IMAGE"));
        assertTrue(AttachmentService.anyImage(inputs));
    }

    @Test
    void anyImageReturnsFalseWhenAllNonImage() {
        var inputs = List.of(
                new AttachmentService.Input("u1", "a.pdf", "application/pdf", 1L, "FILE"),
                new AttachmentService.Input("u2", "b.mp3", "audio/mpeg", 1L, "AUDIO"));
        assertFalse(AttachmentService.anyImage(inputs));
    }

    @Test
    void anyImageHandlesNullInput() {
        assertFalse(AttachmentService.anyImage(null),
                "null list must be treated as no images, not throw");
    }

    @Test
    void anyImageHandlesEmptyInput() {
        assertFalse(AttachmentService.anyImage(List.of()));
    }

    @Test
    void anyImageIsCaseInsensitive() {
        var inputs = List.of(
                new AttachmentService.Input("u1", "a.png", "image/png", 1L, "image"));
        assertTrue(AttachmentService.anyImage(inputs),
                "lowercase 'image' must be detected too");
    }

    @Test
    void anyAudioDetectsAudioInput() {
        var inputs = List.of(
                new AttachmentService.Input("u1", "a.png", "image/png", 1L, "IMAGE"),
                new AttachmentService.Input("u2", "b.mp3", "audio/mpeg", 1L, "AUDIO"));
        assertTrue(AttachmentService.anyAudio(inputs));
    }

    @Test
    void anyAudioReturnsFalseWhenAllNonAudio() {
        var inputs = List.of(
                new AttachmentService.Input("u1", "a.png", "image/png", 1L, "IMAGE"));
        assertFalse(AttachmentService.anyAudio(inputs));
    }

    @Test
    void anyAudioHandlesNullInput() {
        assertFalse(AttachmentService.anyAudio(null));
    }

    // ── helpers ────────────────────────────────────────────────────

    /** Stage a file with the given content; return the uuid for finalize. */
    private String stageFile(String filename, byte[] bytes) throws Exception {
        var uuid = UUID.randomUUID().toString();
        var ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.'))
                : "";
        var stagedPath = stagingDir.resolve(uuid + ext);
        Files.write(stagedPath, bytes);
        return uuid;
    }

    /** Minimal valid PNG: 8-byte signature + IHDR + IEND. */
    private static byte[] pngBytes() {
        // Hand-rolled minimal PNG so Tika sniffs it as image/png reliably.
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,    // PNG signature
                0x00, 0x00, 0x00, 0x0D,                                   // IHDR chunk length 13
                0x49, 0x48, 0x44, 0x52,                                   // "IHDR"
                0x00, 0x00, 0x00, 0x01,                                   // width 1
                0x00, 0x00, 0x00, 0x01,                                   // height 1
                0x08, 0x06, 0x00, 0x00, 0x00,                             // bit depth, color type, etc.
                0x1F, 0x15, (byte) 0xC4, (byte) 0x89,                     // IHDR CRC
                0x00, 0x00, 0x00, 0x00,                                   // IEND length 0
                0x49, 0x45, 0x4E, 0x44,                                   // "IEND"
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82                       // IEND CRC
        };
    }

    /** Minimal valid PDF: header + EOF marker is enough for Tika. */
    private static byte[] pdfBytes() {
        return ("%PDF-1.4\n"
                + "1 0 obj<</Type/Catalog>>endobj\n"
                + "trailer<</Root 1 0 R>>\n"
                + "%%EOF\n").getBytes();
    }

    /** Minimal MP3: an MPEG-1 Layer 3 frame header is enough for Tika to
     *  sniff. We include a single bare frame header — Tika's content-type
     *  detection looks at the first few bytes. */
    private static byte[] mp3Bytes() {
        // ID3v2 header → audio/mpeg sniff
        var out = new byte[128];
        out[0] = 'I'; out[1] = 'D'; out[2] = '3';
        out[3] = 0x03; out[4] = 0x00; out[5] = 0x00;
        // size encoded as 4 sync-safe ints — 0 is fine
        out[6] = 0; out[7] = 0; out[8] = 0; out[9] = 0;
        // MPEG-1 Layer 3 frame sync at byte 10
        out[10] = (byte) 0xFF; out[11] = (byte) 0xFB;
        out[12] = (byte) 0x90; out[13] = 0x00;
        return out;
    }

    /** Minimal WebM/Matroska EBML header — Tika sniffs to video/webm by default,
     *  the audio-hint override path is the one we're testing. */
    private static byte[] webmBytes() {
        // EBML magic: 1A 45 DF A3
        return new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3,
                (byte) 0x9F, 0x42, (byte) 0x86, (byte) 0x81, 0x01,
                0x42, (byte) 0xF7, (byte) 0x81, 0x01, 0x42, (byte) 0xF2, (byte) 0x81, 0x04,
                0x42, (byte) 0xF3, (byte) 0x81, 0x08, 0x42, (byte) 0x82, (byte) 0x84,
                'w', 'e', 'b', 'm',
                0x42, (byte) 0x87, (byte) 0x81, 0x02,
                0x42, (byte) 0x85, (byte) 0x81, 0x02};
    }

    private static void deleteRecursive(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(child -> {
                try { Files.deleteIfExists(child); } catch (java.io.IOException ignored) { /* best-effort */ }
            });
        }
    }
}
