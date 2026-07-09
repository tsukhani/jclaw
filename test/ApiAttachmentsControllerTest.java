import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.Tx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Functional HTTP tests for {@code ApiAttachmentsController.download}
 * (JCLAW-313). The controller resolves a workspace-relative storage path
 * via {@link AgentService#acquireWorkspacePath}, sets RFC 6266 content-
 * disposition headers (inline for image/audio, attachment otherwise),
 * and streams the file.
 *
 * <p>Each test plants a real on-disk file inside {@code workspace-test/}
 * so the {@code renderBinary} path has bytes to serve.
 */
class ApiAttachmentsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void teardown() {
        // Clean up any agent workspace planted by the tests.
        try {
            var root = AgentService.workspaceRoot();
            if (Files.exists(root)) {
                try (var stream = Files.walk(root)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception _) {} });
                }
            }
        } catch (Exception _) {
            // Best-effort cleanup — leftover dirs only affect noise, not correctness.
        }
    }

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    /**
     * Seed an attachment with backing on-disk bytes. Returns the attachment's
     * UUID; the file lives at {@code workspace-test/{agentName}/attachments/{convId}/{uuid}.{ext}}.
     */
    private String seedAttachment(String agentName, String uuid, String originalFilename,
                                   String mimeType, String kind, byte[] bytes,
                                   boolean writeFile) throws Exception {
        // Materialise agent + conversation + message + attachment in one fresh tx.
        var convId = commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = agentName;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var conv = new Conversation();
            conv.agent = agent;
            conv.channelType = "web";
            conv.peerId = "local";
            conv.save();

            var msg = new Message();
            msg.conversation = conv;
            msg.role = "user";
            msg.content = "attachment carrier";
            msg.createdAt = Instant.now();
            msg.save();

            var ext = mimeType.contains("png") ? "png"
                    : mimeType.contains("mp3") ? "mp3"
                    : mimeType.contains("pdf") ? "pdf" : "bin";
            var storagePath = agentName + "/attachments/" + conv.id + "/" + uuid + "." + ext;

            var att = new MessageAttachment();
            att.message = msg;
            att.uuid = uuid;
            att.originalFilename = originalFilename;
            att.storagePath = storagePath;
            att.mimeType = mimeType;
            att.sizeBytes = bytes.length;
            att.kind = kind;
            att.save();

            return conv.id;
        });

        if (writeFile) {
            var ext = mimeType.contains("png") ? "png"
                    : mimeType.contains("mp3") ? "mp3"
                    : mimeType.contains("pdf") ? "pdf" : "bin";
            var relPath = "attachments/" + convId + "/" + uuid + "." + ext;
            Path dest = AgentService.acquireWorkspacePath(agentName, relPath);
            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes);
        }
        return uuid;
    }

    // ===== Auth =====

    @Test
    void downloadRequiresAuth() {
        var response = GET("/api/attachments/" + java.util.UUID.randomUUID());
        assertEquals(401, response.status.intValue());
    }

    // ===== Lookup =====

    @Test
    void downloadReturns404ForUnknownUuid() {
        login();
        var response = GET("/api/attachments/" + java.util.UUID.randomUUID());
        assertEquals(404, response.status.intValue());
    }

    @Test
    void downloadReturns404WhenFileMissingOnDisk() throws Exception {
        login();
        var uuid = seedAttachment("att-agent-missing", java.util.UUID.randomUUID().toString(),
                "ghost.png", "image/png", MessageAttachment.KIND_IMAGE,
                new byte[]{1, 2, 3}, /* writeFile */ false);
        var response = GET("/api/attachments/" + uuid);
        assertEquals(404, response.status.intValue());
    }

    // ===== Inline vs attachment disposition =====

    @Test
    void downloadReturnsInlineForImage() throws Exception {
        login();
        var uuid = seedAttachment("att-agent-img", java.util.UUID.randomUUID().toString(),
                "photo.png", "image/png", MessageAttachment.KIND_IMAGE,
                new byte[]{(byte) 0x89, 'P', 'N', 'G'}, true);
        var response = GET("/api/attachments/" + uuid);
        assertIsOk(response);
        var disposition = response.headers.get("Content-Disposition");
        assertNotNull(disposition, "Content-Disposition header should be set");
        assertTrue(disposition.value().toLowerCase().startsWith("inline"),
                "image disposition should be inline: " + disposition.value());
    }

    @Test
    void downloadReturnsInlineForAudio() throws Exception {
        login();
        var uuid = seedAttachment("att-agent-audio", java.util.UUID.randomUUID().toString(),
                "voice.mp3", "audio/mp3", MessageAttachment.KIND_AUDIO,
                new byte[]{'I', 'D', '3'}, true);
        var response = GET("/api/attachments/" + uuid);
        assertIsOk(response);
        var disposition = response.headers.get("Content-Disposition");
        assertNotNull(disposition, "Content-Disposition header should be set");
        assertTrue(disposition.value().toLowerCase().startsWith("inline"),
                "audio disposition should be inline: " + disposition.value());
    }

    @Test
    void downloadReturnsAttachmentForFile() throws Exception {
        login();
        var uuid = seedAttachment("att-agent-pdf", java.util.UUID.randomUUID().toString(),
                "doc.pdf", "application/pdf", MessageAttachment.KIND_FILE,
                "%PDF-1.4".getBytes(), true);
        var response = GET("/api/attachments/" + uuid);
        assertIsOk(response);
        var disposition = response.headers.get("Content-Disposition");
        assertNotNull(disposition, "Content-Disposition header should be set");
        assertTrue(disposition.value().toLowerCase().startsWith("attachment"),
                "non-media disposition should be attachment: " + disposition.value());
    }

    // ===== RFC 6266 dual filename directives =====

    @Test
    void downloadEmitsBothAsciiAndUtf8FilenameDirectives() throws Exception {
        login();
        // Non-ASCII original filename so the percent-encoded form differs
        // from the ASCII-safe fallback.
        var uuid = seedAttachment("att-agent-unicode", java.util.UUID.randomUUID().toString(),
                "café résumé.pdf", "application/pdf", MessageAttachment.KIND_FILE,
                new byte[]{1}, true);
        var response = GET("/api/attachments/" + uuid);
        assertIsOk(response);
        var disposition = response.headers.get("Content-Disposition").value();
        // ASCII-safe fallback collapses non-ASCII bytes to '_'.
        assertTrue(disposition.contains("filename=\""),
                "expected RFC 6266 ASCII filename directive: " + disposition);
        // UTF-8 form per RFC 5987.
        assertTrue(disposition.contains("filename*=UTF-8''"),
                "expected RFC 5987 UTF-8 filename* directive: " + disposition);
        // Spaces encode as %20, not '+'.
        assertFalse(disposition.contains("filename*=UTF-8''café+résumé"),
                "UTF-8 directive must not use '+' for spaces: " + disposition);
    }

    @Test
    void downloadEmitsFallbackFilenameWhenOriginalIsAllNonAscii() throws Exception {
        login();
        // Wholly non-ASCII filename — the ASCII fallback collapses to all '_'.
        // The percent-encoded form must still carry the UTF-8 bytes.
        var uuid = seedAttachment("att-agent-nonascii", java.util.UUID.randomUUID().toString(),
                "日本語.pdf", "application/pdf", MessageAttachment.KIND_FILE,
                new byte[]{1}, true);
        var response = GET("/api/attachments/" + uuid);
        assertIsOk(response);
        var disposition = response.headers.get("Content-Disposition").value();
        assertTrue(disposition.contains("filename*=UTF-8''"),
                "UTF-8 form must always be present: " + disposition);
    }

    // ===== Workspace-bounded SSRF guard =====

    @Test
    void downloadReturns404WhenStoragePathHasNoAgentPrefix() {
        login();
        // storagePath that doesn't start with "<agent.name>/" trips the
        // prefix check before the workspace resolver runs.
        var uuid = java.util.UUID.randomUUID().toString();
        commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "prefix-agent";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var conv = new Conversation();
            conv.agent = agent;
            conv.channelType = "web";
            conv.peerId = "local";
            conv.save();

            var msg = new Message();
            msg.conversation = conv;
            msg.role = "user";
            msg.content = "x";
            msg.createdAt = Instant.now();
            msg.save();

            var att = new MessageAttachment();
            att.message = msg;
            att.uuid = uuid;
            att.originalFilename = "x.pdf";
            // storagePath claims a different agent's prefix → caught at
            // the startsWith() check.
            att.storagePath = "other-agent/attachments/1/" + uuid + ".pdf";
            att.mimeType = "application/pdf";
            att.sizeBytes = 1;
            att.kind = MessageAttachment.KIND_FILE;
            att.save();
            return att.id;
        });

        var response = GET("/api/attachments/" + uuid);
        assertEquals(404, response.status.intValue());
    }

    @Test
    void downloadReturns403WhenStoragePathTraversesOutsideWorkspace() {
        login();
        // storagePath that starts with the agent prefix but contains ".."
        // segments that escape the workspace. The prefix check passes; the
        // workspace resolver then raises SecurityException → 403.
        var uuid = java.util.UUID.randomUUID().toString();
        commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "traversal-agent";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var conv = new Conversation();
            conv.agent = agent;
            conv.channelType = "web";
            conv.peerId = "local";
            conv.save();

            var msg = new Message();
            msg.conversation = conv;
            msg.role = "user";
            msg.content = "x";
            msg.createdAt = Instant.now();
            msg.save();

            var att = new MessageAttachment();
            att.message = msg;
            att.uuid = uuid;
            att.originalFilename = "secret.txt";
            // Prefix matches so we get past the agent-name check; the
            // remainder traverses up out of the workspace root.
            att.storagePath = "traversal-agent/../../../../etc/passwd";
            att.mimeType = "text/plain";
            att.sizeBytes = 1;
            att.kind = MessageAttachment.KIND_FILE;
            att.save();
            return att.id;
        });

        var response = GET("/api/attachments/" + uuid);
        assertEquals(403, response.status.intValue());
    }

    // ===== Content-Type passthrough + cache header =====

    @Test
    void downloadSetsContentTypeFromAttachmentRow() throws Exception {
        login();
        var uuid = seedAttachment("att-agent-ct", java.util.UUID.randomUUID().toString(),
                "doc.pdf", "application/pdf", MessageAttachment.KIND_FILE,
                new byte[]{1, 2, 3}, true);
        var response = GET("/api/attachments/" + uuid);
        assertIsOk(response);
        assertContentType("application/pdf", response);
        var cacheControl = response.headers.get("Cache-Control");
        assertNotNull(cacheControl, "Cache-Control header should be set");
        assertEquals("private, max-age=300", cacheControl.value());
    }

    // ===== Delete (JCLAW-209): free workspace bytes, retain the record =====

    @Test
    void deleteRequiresAuth() {
        var response = DELETE("/api/attachments/" + java.util.UUID.randomUUID());
        assertEquals(401, response.status.intValue());
    }

    @Test
    void deleteReturns404ForUnknownUuid() {
        login();
        var response = DELETE("/api/attachments/" + java.util.UUID.randomUUID());
        assertEquals(404, response.status.intValue());
    }

    @Test
    void deleteFlagsRowRetainsItAndBlocksDownload() throws Exception {
        login();
        var uuid = seedAttachment("att-agent-del", java.util.UUID.randomUUID().toString(),
                "gen.png", "image/png", MessageAttachment.KIND_IMAGE,
                new byte[]{(byte) 0x89, 'P', 'N', 'G'}, true);

        // Present and downloadable before the delete.
        assertIsOk(GET("/api/attachments/" + uuid));

        var del = DELETE("/api/attachments/" + uuid);
        assertIsOk(del);

        // The row is retained and flagged deleted (read in a fresh tx so the
        // HTTP-committed change is visible).
        var deleted = commitInFreshTx(() -> {
            var att = MessageAttachment.findByUuid(uuid);
            return att != null && att.deleted;
        });
        assertTrue(deleted, "row must be retained and flagged deleted after a delete");

        // Download now 404s — the bytes are gone and the deleted guard fires.
        assertEquals(404, GET("/api/attachments/" + uuid).status.intValue());
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        login();
        var uuid = seedAttachment("att-agent-del2", java.util.UUID.randomUUID().toString(),
                "gen.png", "image/png", MessageAttachment.KIND_IMAGE,
                new byte[]{1, 2, 3}, true);
        assertIsOk(DELETE("/api/attachments/" + uuid));
        // A second delete on the already-deleted row is still a no-op success.
        assertIsOk(DELETE("/api/attachments/" + uuid));
    }

    // --- asciiSafeFilename helper (reflection) ---

    private String asciiSafeFilename(String name) throws Exception {
        var m = controllers.ApiAttachmentsController.class.getDeclaredMethod(
                "asciiSafeFilename", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, name);
    }

    @Test
    void asciiSafeFilenameReturnsDefaultForNull() throws Exception {
        assertEquals("file", asciiSafeFilename(null));
    }

    @Test
    void asciiSafeFilenameReturnsDefaultForEmptyInput() throws Exception {
        assertEquals("file", asciiSafeFilename(""));
    }

    @Test
    void asciiSafeFilenameReplacesQuoteAndBackslashWithUnderscore() throws Exception {
        // The two explicit exclusions in the ASCII range.
        var result = asciiSafeFilename("nasty\"name\\thing.txt");
        assertEquals("nasty_name_thing.txt", result);
    }

    @Test
    void asciiSafeFilenameReplacesControlAndHighBitsWithUnderscore() throws Exception {
        // Char < 0x20 (control chars) and >= 0x7F (DEL + high) become _.
        var withControlAndDel = "okend.txt";
        var result = asciiSafeFilename(withControlAndDel);
        assertEquals("ok__end.txt", result);
    }

    @Test
    void asciiSafeFilenameKeepsExtensionForNonAsciiBase() throws Exception {
        // Non-ASCII chars get replaced but ASCII chars in the extension survive.
        var result = asciiSafeFilename("文件.docx");
        assertFalse(result.isEmpty());
        assertTrue(result.endsWith(".docx"));
    }
}
