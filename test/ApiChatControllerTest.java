import org.junit.jupiter.api.*;
import play.test.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;

/**
 * Functional HTTP tests for {@code ApiChatController}: send, streamChat, and
 * uploadChatFiles. Existing slash-command happy paths live in {@link ControllerApiTest}
 * (chatSendRoutesModelStatusThroughFullDetailPath, chatSendRoutesModelNameWriteThroughOverride);
 * this file covers the parameter-binding and error-response surface that those
 * tests don't touch.
 *
 * <p>Real LLM dispatch is intentionally avoided — every send/streamChat path
 * exercised here either short-circuits via slash-command interception or fails
 * before the LLM round (missing agent, missing fields). That keeps the suite
 * deterministic and free of provider configuration coupling.
 */
public class ApiChatControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    // --- Auth + helpers ---

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    private String createAgent(String name) {
        var body = """
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(name);
        var resp = POST("/api/agents", "application/json", body);
        assertIsOk(resp);
        return extractId(getContent(resp));
    }

    private String extractId(String json) {
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Stage a temp file with the given size and return it. Caller deletes. */
    private File makeTempFile(String name, int sizeBytes) throws IOException {
        var dir = Files.createTempDirectory("chat-upload-test");
        var f = dir.resolve(name).toFile();
        var payload = new byte[sizeBytes];
        for (int i = 0; i < sizeBytes; i++) payload[i] = (byte) ('a' + (i % 26));
        Files.write(f.toPath(), payload);
        f.deleteOnExit();
        return f;
    }

    // =====================
    // POST /api/chat/send
    // =====================

    @Test
    public void sendRequiresAuth() {
        var response = POST("/api/chat/send", "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void sendRejectsEmptyBody() {
        login();
        // No agentId, no message: resolveChatContext calls badRequest().
        var response = POST("/api/chat/send", "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void sendRejectsMissingMessage() {
        login();
        var id = createAgent("send-missing-msg");
        var body = """
                {"agentId": %s}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void sendRejectsMissingAgentId() {
        login();
        var body = """
                {"message": "hello"}
                """;
        var response = POST("/api/chat/send", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void sendReturns404ForUnknownAgent() {
        login();
        var body = """
                {"agentId": 999999, "message": "hello"}
                """;
        var response = POST("/api/chat/send", "application/json", body);
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void sendReturns404ForUnknownConversationId() {
        // When the caller pins an explicit conversationId that doesn't exist,
        // resolveChatContext lets it through — the second findById in send()
        // is responsible for the 404. This guards that branch.
        login();
        var id = createAgent("send-unknown-conv");
        var body = """
                {"agentId": %s, "message": "hello", "conversationId": 999999}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void sendSlashHelpReturnsSyntheticResponseWithoutLlm() {
        // /help is the simplest slash-command path: it doesn't need any
        // provider configuration, doesn't hit Commands.executeArgs branches,
        // and never invokes the LLM. The response payload must include the
        // synthetic response text and the agent identity.
        login();
        var id = createAgent("send-slash-help");
        var body = """
                {"agentId": %s, "message": "/help"}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        assertIsOk(response);
        assertContentType("application/json", response);
        var content = getContent(response);
        assertTrue(content.contains("\"agentName\":\"send-slash-help\""),
                "response must echo the agent name: " + content);
        assertTrue(content.contains("\"response\""),
                "response must carry the synthetic slash response: " + content);
    }

    // =====================
    // POST /api/chat/stream — slash command path only
    // =====================

    @Test
    public void streamRequiresAuth() {
        var response = POST("/api/chat/stream", "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void streamRejectsEmptyBody() {
        login();
        var response = POST("/api/chat/stream", "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void streamReturns404ForUnknownAgent() {
        login();
        var body = """
                {"agentId": 999999, "message": "hello"}
                """;
        var response = POST("/api/chat/stream", "application/json", body);
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void streamSlashCommandEmitsSseFrames() {
        // Slash commands short-circuit before runStreaming is invoked, so this
        // path is independent of LLM provider state. The completed-future
        // handoff to await() unblocks immediately because writeSse(terminal=true)
        // completes streamDone before await is reached. The response body
        // should carry both the init frame (with the new conversationId) and
        // the complete frame.
        login();
        var id = createAgent("stream-slash");
        var body = """
                {"agentId": %s, "message": "/help"}
                """.formatted(id);
        var response = POST("/api/chat/stream", "application/json", body);
        assertIsOk(response);
        // SSE content type expected on streaming responses.
        var ct = response.contentType;
        assertTrue(ct != null && ct.startsWith("text/event-stream"),
                "expected text/event-stream content-type, got: " + ct);
        var content = getContent(response);
        assertTrue(content.contains("\"type\":\"init\""),
                "init frame missing from SSE body: " + content);
        assertTrue(content.contains("\"type\":\"complete\""),
                "complete frame missing from SSE body: " + content);
    }

    // =====================
    // POST /api/chat/upload — multipart attachment intake
    // =====================

    @Test
    public void uploadRequiresAuth() {
        var response = POST("/api/chat/upload", "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void uploadRejectsMissingAgentId() {
        login();
        // Body with no agentId param at all → controller's `if (agentId == null) badRequest()`.
        var response = POST("/api/chat/upload", "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void uploadReturns404ForUnknownAgent() {
        login();
        var params = new HashMap<String, String>();
        params.put("agentId", "999999");
        var response = POST("/api/chat/upload", params, new HashMap<String, File>());
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void uploadRejectsZeroFilesForKnownAgent() {
        login();
        var id = createAgent("upload-no-files");
        var params = new HashMap<String, String>();
        params.put("agentId", id);
        var response = POST("/api/chat/upload", params, new HashMap<String, File>());
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void uploadAcceptsValidFileAndReturnsBatchId() throws Exception {
        login();
        var id = createAgent("upload-ok");
        var f = makeTempFile("note.txt", 64);
        try {
            var params = new HashMap<String, String>();
            params.put("agentId", id);
            var files = new HashMap<String, File>();
            files.put("files", f);
            var response = POST("/api/chat/upload", params, files);
            assertIsOk(response);
            assertContentType("application/json", response);
            var content = getContent(response);
            assertTrue(content.contains("\"batchId\""),
                    "response missing batchId: " + content);
            assertTrue(content.contains("\"files\""),
                    "response missing files array: " + content);
            assertTrue(content.contains("\"name\":\"note.txt\""),
                    "uploaded file name should appear: " + content);
            assertTrue(content.contains("\"size\":64"),
                    "uploaded file size should be reported: " + content);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    public void uploadSanitizesUnsafeCharactersInFilename() throws Exception {
        // sanitizeFilename strips characters outside [A-Za-z0-9._\- ]. A name
        // with '$' and '!' must come back as a safe leaf — no '$', no '!', no
        // path traversal segments.
        login();
        var id = createAgent("upload-sanitize");
        var f = makeTempFile("weird$name!.txt", 16);
        try {
            var params = new HashMap<String, String>();
            params.put("agentId", id);
            var files = new HashMap<String, File>();
            files.put("files", f);
            var response = POST("/api/chat/upload", params, files);
            assertIsOk(response);
            var content = getContent(response);
            // Sanitized name preserves '.' and '_' (underscore replaces '$' and '!').
            assertTrue(content.contains("weird_name_.txt"),
                    "expected sanitized leaf 'weird_name_.txt' in response: " + content);
            assertFalse(content.contains("../"),
                    "path-traversal segments must not appear in response: " + content);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
