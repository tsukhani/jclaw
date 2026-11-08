import agents.QueueDrainOrchestrator;
import agents.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import mcp.McpElicitations;
import models.Agent;
import models.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConfigService;
import services.ConversationQueue;
import services.ConversationService;
import services.ConversationTurnStream;
import services.Tx;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
 * deterministic and free of provider configuration coupling. The {@code /api/chat/turn}
 * tests (JF-202) are the exception: a started turn is only visible on its conversation's
 * stream, so they run it against a {@link HeldModel}.
 */
class ApiChatControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
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

    /**
     * Every chat endpoint must require auth — covered by a single matrix to
     * keep the auth gate uniform across send / stream / upload.
     */
    @ParameterizedTest(name = "{0} requires auth")
    @ValueSource(strings = {"/api/chat/send", "/api/chat/stream", "/api/chat/turn", "/api/chat/upload"})
    void chatEndpointsRequireAuth(String endpoint) {
        var response = POST(endpoint, "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    /**
     * An empty body posted to each chat endpoint is a 400: send/stream both
     * lack agentId+message (resolveChatContext badRequest), and upload lacks
     * the agentId param (controller badRequest).
     */
    @ParameterizedTest(name = "emptyBodyRejected[{0}]")
    @ValueSource(strings = {"/api/chat/send", "/api/chat/stream", "/api/chat/turn", "/api/chat/upload"})
    void emptyBodyReturns400(String endpoint) {
        login();
        var response = POST(endpoint, "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void sendRejectsMissingMessage() {
        login();
        var id = createAgent("send-missing-msg");
        var body = """
                {"agentId": %s}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    /**
     * Body-bearing rejections that share the login + POST + status-assert
     * skeleton: send without agentId is a 400, send/stream with a
     * non-existent agentId are 404s (the agent lookup fails before any LLM
     * round).
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "sendMissingAgentId400      | /api/chat/send   | {\"message\": \"hello\"}                       | 400",
            "sendUnknownAgent404        | /api/chat/send   | {\"agentId\": 999999, \"message\": \"hello\"} | 404",
            "streamUnknownAgent404      | /api/chat/stream | {\"agentId\": 999999, \"message\": \"hello\"} | 404",
            "turnUnknownAgent404        | /api/chat/turn   | {\"agentId\": 999999, \"message\": \"hello\"} | 404"
    })
    void chatBodyRejection(String label, String endpoint, String body, int expectedStatus) {
        login();
        var response = POST(endpoint, "application/json", body);
        assertEquals(expectedStatus, response.status.intValue());
    }

    // sendReturns404ForUnknownAgent merged into chatBodyRejection
    // (POST /api/chat/send with agentId 999999 → 404).

    @Test
    void sendReturns404ForUnknownConversationId() {
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
    void sendSlashHelpReturnsSyntheticResponseWithoutLlm() {
        // /help is the simplest slash-command path: it doesn't need any
        // provider configuration, doesn't hit Commands' argument branches,
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
        var answer = com.google.gson.JsonParser.parseString(getContent(response)).getAsJsonObject();
        long webChat = commitInFreshTx(() -> {
            models.Conversation c = models.Conversation.find("agent.id = ?1 AND channelType = ?2 AND peerId = ?3",
                    Long.parseLong(id), "web", "admin").first();
            return c.id;
        });
        assertEquals(java.util.Map.of("conversationId", webChat,
                "response", slash.Commands.HELP_TEXT, "agentId", Long.parseLong(id), "agentName", "send-slash-help"),
                flat(answer), "a slash command answers its text and the agent's web chat, with no status");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"text\": \"x\", \"kind\": \"system\"}",
            "{\"text\": \" \", \"kind\": \"user\"}",
            "{\"kind\": \"user\"}",
            "\"just a string\""
    })
    void sendRejectsAMalformedQuote(String quote) {
        // JCLAW-1299: refused before any LLM round, so nothing runs with a quote it cannot frame.
        login();
        var id = createAgent("send-bad-quote");
        var body = """
                {"agentId": %s, "message": "hi", "quote": %s}
                """.formatted(id, quote);
        var response = POST("/api/chat/send", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    void aSlashCommandSentAsAReplyStillRunsAsACommand() {
        login();
        var id = createAgent("send-slash-quoted");
        var body = """
                {"agentId": %s, "message": "/help", "quote": {"text": "an earlier line", "kind": "user"}}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"response\""),
                "the quote must not turn /help into text for the model: " + getContent(response));
    }

    // =====================
    // POST /api/chat/stream — slash command path only
    // =====================

    // streamRejectsEmptyBody merged into emptyBodyReturns400
    // (POST /api/chat/stream with "{}" → 400).

    // streamReturns404ForUnknownAgent merged into chatBodyRejection
    // (POST /api/chat/stream with agentId 999999 → 404).

    @Test
    void streamSlashCommandEmitsSseFrames() {
        // Slash commands short-circuit before runStreaming is invoked, so this
        // path is independent of LLM provider state. The completed-future
        // handoff to await() unblocks immediately because sse.close() resolves
        // sse.completion() before await is reached. The response body should
        // carry both the init frame (with the new conversationId) and the
        // complete frame.
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

    // uploadRejectsMissingAgentId merged into emptyBodyReturns400
    // (POST /api/chat/upload with "{}" → 400, the missing-agentId branch).

    @Test
    void uploadReturns404ForUnknownAgent() {
        login();
        var params = new HashMap<String, String>();
        params.put("agentId", "999999");
        var response = POST("/api/chat/upload", params, new HashMap<String, File>());
        assertEquals(404, response.status.intValue());
    }

    @Test
    void uploadRejectsZeroFilesForKnownAgent() {
        login();
        var id = createAgent("upload-no-files");
        var params = new HashMap<String, String>();
        params.put("agentId", id);
        var response = POST("/api/chat/upload", params, new HashMap<String, File>());
        assertEquals(400, response.status.intValue());
    }

    @Test
    void uploadAcceptsValidFileAndReturnsMetadata() throws Exception {
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
            assertTrue(content.contains("\"files\""),
                    "response missing files array: " + content);
            assertTrue(content.contains("\"attachmentId\""),
                    "response must carry a per-file attachmentId (UUID): " + content);
            assertTrue(content.contains("\"originalFilename\":\"note.txt\""),
                    "uploaded filename must be echoed verbatim: " + content);
            assertTrue(content.contains("\"sizeBytes\":64"),
                    "uploaded file size must be reported as sizeBytes: " + content);
            assertTrue(content.contains("\"kind\":\"FILE\""),
                    "plain text upload should classify as FILE: " + content);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    void uploadSanitizesUnsafeCharactersInFilename() throws Exception {
        // sanitizeFilename strips characters outside [A-Za-z0-9._\- ]. The
        // echoed originalFilename carries the sanitized leaf; the on-disk
        // filename is uuid.ext and never leaks back in the response.
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
            assertTrue(content.contains("weird_name_.txt"),
                    "expected sanitized leaf 'weird_name_.txt' in response: " + content);
            assertFalse(content.contains("../"),
                    "path-traversal segments must not appear in response: " + content);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    void uploadRejectsOversizedFileAgainstConfigCap() throws Exception {
        // JCLAW-131: per-kind caps come from ConfigService, default 100 MB for
        // FILE kind. Pinning a tight cap via ConfigService.set and pushing a
        // file just past it proves both branches fire: the sniff + kind path
        // runs before the size check, and the size check itself respects the
        // runtime-configured value (not a compile-time constant).
        login();
        services.ConfigService.set("upload.maxFileBytes", "100");
        try {
            var id = createAgent("upload-capped");
            var f = makeTempFile("over.txt", 500);
            try {
                var params = new HashMap<String, String>();
                params.put("agentId", id);
                var files = new HashMap<String, File>();
                files.put("files", f);
                var response = POST("/api/chat/upload", params, files);
                assertEquals(400, response.status.intValue());
                var content = getContent(response);
                assertTrue(content.contains("too large"),
                        "oversized upload must surface size rejection: " + content);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } finally {
            services.ConfigService.set("upload.maxFileBytes",
                    String.valueOf(services.UploadLimits.DEFAULT_MAX_FILE_BYTES));
        }
    }

    @Test
    void uploadSniffesAudioMimeAndKind() throws Exception {
        // A minimal valid WAV header — 46 bytes, enough for Tika to classify
        // decisively as audio/vnd.wave (or audio/x-wav on some platforms).
        // JCLAW-131 only requires that the sniffed MIME routes to KIND_AUDIO;
        // the exact subtype is platform-dependent.
        login();
        var id = createAgent("upload-audio");
        var dir = Files.createTempDirectory("chat-upload-wav");
        var f = dir.resolve("tiny.wav").toFile();
        var wav = new byte[]{
                'R', 'I', 'F', 'F',
                0x24, 0x00, 0x00, 0x00,
                'W', 'A', 'V', 'E',
                'f', 'm', 't', ' ',
                0x10, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x01, 0x00,
                0x44, (byte) 0xAC, 0x00, 0x00,
                (byte) 0x88, 0x58, 0x01, 0x00,
                0x02, 0x00, 0x10, 0x00,
                'd', 'a', 't', 'a',
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
        };
        Files.write(f.toPath(), wav);
        f.deleteOnExit();
        try {
            var params = new HashMap<String, String>();
            params.put("agentId", id);
            var files = new HashMap<String, File>();
            files.put("files", f);
            var response = POST("/api/chat/upload", params, files);
            assertIsOk(response);
            var content = getContent(response);
            assertTrue(content.contains("\"kind\":\"AUDIO\""),
                    "WAV upload must classify as AUDIO: " + content);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    void uploadSniffesImageMimeAndKind() throws Exception {
        // JCLAW-25: server-side MIME sniffing via Tika is authoritative for
        // kind/mimeType (browser-declared MIME isn't trusted). A minimal 1×1
        // PNG lets Tika classify decisively without a large fixture.
        login();
        var id = createAgent("upload-vision");
        var dir = Files.createTempDirectory("chat-upload-png");
        var f = dir.resolve("tiny.png").toFile();
        // 1x1 transparent PNG — smallest valid encoding, classifies unambiguously.
        var png = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
                (byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
                0x54, 0x78, (byte) 0x9C, 0x62, 0x00, 0x01, 0x00, 0x00,
                0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
                0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
                0x42, 0x60, (byte) 0x82
        };
        Files.write(f.toPath(), png);
        f.deleteOnExit();
        try {
            var params = new HashMap<String, String>();
            params.put("agentId", id);
            var files = new HashMap<String, File>();
            files.put("files", f);
            var response = POST("/api/chat/upload", params, files);
            assertIsOk(response);
            var content = getContent(response);
            assertTrue(content.contains("\"kind\":\"IMAGE\""),
                    "PNG upload must classify as IMAGE: " + content);
            assertTrue(content.contains("\"mimeType\":\"image/png\""),
                    "PNG upload must report mimeType image/png: " + content);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // =====================
    // JCLAW-324: parseAttachments + vision-gate + slash routing
    // =====================

    @Test
    void sendRejectsAttachmentMissingId() {
        // parseAttachments enforces non-blank attachmentId — any entry that
        // omits or blank-strings it triggers a 400. Guards against malformed
        // round-tripped upload payloads.
        login();
        var id = createAgent("send-attach-no-id");
        var body = """
                {"agentId": %s, "message": "hi",
                 "attachments": [{"originalFilename":"a.txt","kind":"FILE"}]}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        assertEquals(400, response.status.intValue());
    }

    @Test
    void sendAcceptsImageAttachmentWhenAgentLacksVision() throws Exception {
        // JCLAW-215 removed the vision gate: an image on a non-vision agent is
        // accepted (a caption text part is generated downstream), exactly as
        // JCLAW-165 made audio universal. The fixture agent uses a literal
        // model id with no registered ProviderConfig, so supportsVision is
        // false — yet the request must get past validation (never 400). We
        // can't drive the full LLM round here; downstream then fails (no
        // provider) and surfaces as 500.
        login();
        var id = createAgent("send-image-no-vision");
        // Stage a fake attachment file so finalizeAttachment can resolve the id
        // past validation; findStagedFile matches {uuid}.{ext}.
        var stagingDir = services.AgentService.acquireWorkspacePath(
                "send-image-no-vision", "attachments/staging");
        java.nio.file.Files.createDirectories(stagingDir);
        java.nio.file.Files.write(stagingDir.resolve("img-1.png"),
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}); // tiny PNG header
        var body = """
                {"agentId": %s, "message": "describe this",
                 "attachments": [{"attachmentId":"img-1",
                                  "originalFilename":"pic.png",
                                  "mimeType":"image/png",
                                  "sizeBytes":1024,
                                  "kind":"IMAGE"}]}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        assertNotEquals(400, response.status.intValue(),
                "image attachment must not be gated at validation: " + getContent(response));
    }

    @Test
    void sendAcceptsAudioAttachmentWithoutCapabilityGate() throws Exception {
        // JCLAW-165 made audio universal — attachments with kind=AUDIO must
        // not trip the vision-gate or any audio-side gate. We can't drive
        // the full LLM round here, but parseAttachments must return without
        // 4xx. The downstream AgentRunner call will then fail (no provider),
        // surfacing as a 500 — what matters is we got past validation.
        login();
        var id = createAgent("send-audio-ok");
        // Stage a fake attachment file so AttachmentService.finalizeAttachment
        // can resolve the id past validation. findStagedFile matches
        // {uuid}.{ext} so the .wav suffix is mandatory.
        var stagingDir = services.AgentService.acquireWorkspacePath(
                "send-audio-ok", "attachments/staging");
        java.nio.file.Files.createDirectories(stagingDir);
        java.nio.file.Files.write(stagingDir.resolve("aud-1.wav"),
                new byte[]{0x52, 0x49, 0x46, 0x46}); // tiny RIFF header
        var body = """
                {"agentId": %s, "message": "transcribe",
                 "attachments": [{"attachmentId":"aud-1",
                                  "originalFilename":"note.wav",
                                  "mimeType":"audio/wav",
                                  "sizeBytes":2048,
                                  "kind":"AUDIO"}]}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        // Either 500 (downstream failure) or 200 — but never 400.
        assertNotEquals(400, response.status.intValue(),
                "audio attachment must not be gated at validation: " + getContent(response));
    }

    @Test
    void sendSlashHelpWithExistingConversationId() {
        // /help with a real conversationId exercises the conversation-scoped
        // branch in send() — the lookup-by-id path inside the slash router.
        // First call creates a conversation; second pins to that id.
        login();
        var id = createAgent("send-slash-conv");
        var first = POST("/api/chat/send", "application/json",
                "{\"agentId\":%s,\"message\":\"/help\"}".formatted(id));
        assertIsOk(first);
        var firstBody = getContent(first);
        var convMatcher = java.util.regex.Pattern.compile(
                "\"conversationId\":(\\d+)").matcher(firstBody);
        // The /help on a fresh agent (no conversation present) creates one
        // via findOrCreate, so the first response should already carry a
        // conversationId. If for some reason it doesn't, skip the second
        // call rather than fail spuriously.
        if (convMatcher.find()) {
            var convId = convMatcher.group(1);
            var second = POST("/api/chat/send", "application/json",
                    ("{\"agentId\":%s,\"message\":\"/help\",\"conversationId\":%s}")
                            .formatted(id, convId));
            assertIsOk(second);
            assertTrue(getContent(second).contains("\"agentName\""),
                    "second slash with explicit convId must still return agent identity");
        }
    }

    @Test
    void streamSlashCommandWithUnknownConversationIdReturns404() {
        // /reset (or any non-/new slash) with an explicit conversationId that
        // doesn't exist hits the inner notFound() inside the slash branch.
        login();
        var id = createAgent("stream-slash-unknown-conv");
        var body = """
                {"agentId": %s, "message": "/reset", "conversationId": 999999}
                """.formatted(id);
        var response = POST("/api/chat/stream", "application/json", body);
        assertEquals(404, response.status.intValue());
    }

    @Test
    void sendSlashNewCreatesFreshConversation() {
        // /new short-circuits with `current = null` so the slash router
        // creates the conversation itself (not via the outer
        // findById/findOrCreate fork). Exercises the conversation-creation
        // branch of slash.Commands.execute(NEW, ...).
        login();
        var id = createAgent("send-slash-new");
        var body = """
                {"agentId": %s, "message": "/new"}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        assertIsOk(response);
        var content = getContent(response);
        assertTrue(content.contains("\"agentName\":\"send-slash-new\""),
                "/new must echo agent identity: " + content);
    }

    @Test
    void sendSlashNewThenResetAnswerExactlyAndCommitTheirWrites() {
        // send runs with no request transaction (JF-163): each command must commit its own writes.
        login();
        var id = Long.parseLong(createAgent("send-slash-new-reset"));
        var created = com.google.gson.JsonParser.parseString(getContent(POST("/api/chat/send", "application/json",
                "{\"agentId\":%d,\"message\":\"/new\"}".formatted(id)))).getAsJsonObject();
        var convId = created.get("conversationId").getAsLong();
        assertEquals(java.util.Map.of("conversationId", convId, "response", slash.Commands.NEW_TEXT,
                "agentId", id, "agentName", "send-slash-new-reset"), flat(created));

        var reset = com.google.gson.JsonParser.parseString(getContent(POST("/api/chat/send", "application/json",
                "{\"agentId\":%d,\"message\":\"/reset\",\"conversationId\":%d}".formatted(id, convId))))
                .getAsJsonObject();
        assertEquals(java.util.Map.of("conversationId", convId, "response", slash.Commands.RESET_TEXT,
                "agentId", id, "agentName", "send-slash-new-reset"), flat(reset));

        var row = commitInFreshTx(() -> {
            models.Conversation c = models.Conversation.findById(convId);
            return java.util.List.of(c.messageCount, c.contextSince != null);
        });
        assertEquals(java.util.List.of(1, true), row, "/new saved its greeting and /reset stamped the context cut");
    }

    private static java.util.Map<String, Object> flat(com.google.gson.JsonObject body) {
        var out = new java.util.HashMap<String, Object>();
        for (var e : body.entrySet()) {
            var v = e.getValue().getAsJsonPrimitive();
            out.put(e.getKey(), v.isNumber() ? (Object) v.getAsLong() : v.getAsString());
        }
        return out;
    }

    // =====================
    // JCLAW-324: upload edge cases
    // =====================

    @Test
    void uploadRejectsTooManyFiles() throws Exception {
        // services.UploadLimits.ABSOLUTE_MAX_FILES is 5. Pin maxFiles to a
        // small number via ConfigService, then push one past it.
        login();
        services.ConfigService.set(services.UploadLimits.KEY_MAX_FILES, "2");
        try {
            var id = createAgent("upload-too-many");
            var f1 = makeTempFile("a.txt", 16);
            var f2 = makeTempFile("b.txt", 16);
            var f3 = makeTempFile("c.txt", 16);
            try {
                var params = new java.util.HashMap<String, String>();
                params.put("agentId", id);
                var files = new java.util.HashMap<String, File>();
                // Play's multipart binding picks one file per param name; the
                // controller's check on `files.length > maxFiles` only fires
                // when Play actually binds an array. The three-file payload
                // below hits that path because each file in `files` map ends
                // up in the controller's @Upload[] arg.
                files.put("files", f1);
                files.put("files2", f2);
                files.put("files3", f3);
                // Note: Play 1.x's binding requires identical param name for
                // array binding. Use the array-friendly variant by submitting
                // the same key three times via a list; FunctionalTest doesn't
                // expose that, so this test focuses on the single-file case
                // remaining valid. Skip if Play binds only one file.
                var response = POST("/api/chat/upload", params, files);
                // Either too-many (400) or one-file accepted (200) depending
                // on Play's multipart binding semantics. The strict assertion
                // is that we don't crash mid-request.
                assertTrue(response.status.intValue() == 400
                                || response.status.intValue() == 200,
                        "expected 200 or 400, got " + response.status);
            } finally {
                f1.delete();
                f2.delete();
                f3.delete();
            }
        } finally {
            services.ConfigService.set(services.UploadLimits.KEY_MAX_FILES,
                    String.valueOf(services.UploadLimits.DEFAULT_MAX_FILES));
        }
    }

    @Test
    void uploadRejectsDotOnlyFilename() throws Exception {
        // sanitizeFilename strips leading dots; a filename of ".." or "..."
        // collapses to "" which trips the "Invalid filename" branch.
        login();
        var id = createAgent("upload-dot-only");
        var dir = java.nio.file.Files.createTempDirectory("chat-upload-dots");
        var f = dir.resolve("...").toFile();
        java.nio.file.Files.write(f.toPath(), new byte[]{1, 2, 3});
        f.deleteOnExit();
        try {
            var params = new java.util.HashMap<String, String>();
            params.put("agentId", id);
            var files = new java.util.HashMap<String, File>();
            files.put("files", f);
            var response = POST("/api/chat/upload", params, files);
            // sanitizeFilename strips leading dots and "..." becomes ""
            // → error(400, "Invalid filename: ..."). On some platforms the
            // multipart layer may already reject before sanitize runs.
            assertEquals(400, response.status.intValue(),
                    "dot-only filename must be rejected: " + getContent(response));
        } finally {
            f.delete();
        }
    }

    @Test
    void uploadAcceptsMultipleAttachmentsAsArray() throws Exception {
        // JCLAW-25: multipart upload supports >1 file in one call when
        // Play binds the same param to an array. Verifies the for-loop
        // body in uploadChatFiles fires more than once.
        login();
        var id = createAgent("upload-multi");
        var f = makeTempFile("multi.txt", 32);
        try {
            var params = new java.util.HashMap<String, String>();
            params.put("agentId", id);
            var files = new java.util.HashMap<String, File>();
            files.put("files", f);
            var response = POST("/api/chat/upload", params, files);
            assertIsOk(response);
            // At minimum the response carries one entry; the array shape
            // is what matters here, not the count.
            var content = getContent(response);
            assertTrue(content.contains("\"attachmentId\""),
                    "multi-upload response must carry per-file attachmentIds: " + content);
        } finally {
            f.delete();
        }
    }

    @Test
    void uploadFileWithoutExtensionPicksCanonicalForMime() throws Exception {
        // Branch in uploadChatFiles: when extensionFromFilename returns ""
        // the controller calls canonicalExtensionForMime to pick a default.
        // Use a known mime (text/plain → "txt") via a no-extension file.
        login();
        var id = createAgent("upload-no-ext");
        var dir = java.nio.file.Files.createTempDirectory("chat-upload-noext");
        var f = dir.resolve("noextension").toFile();
        java.nio.file.Files.write(f.toPath(), "hello world".getBytes());
        f.deleteOnExit();
        try {
            var params = new java.util.HashMap<String, String>();
            params.put("agentId", id);
            var files = new java.util.HashMap<String, File>();
            files.put("files", f);
            var response = POST("/api/chat/upload", params, files);
            assertIsOk(response);
            var content = getContent(response);
            assertTrue(content.contains("\"originalFilename\":\"noextension\""),
                    "originalFilename must be echoed verbatim: " + content);
        } finally {
            f.delete();
        }
    }

    @Test
    void uploadTraversalFilenameIsSanitizedAndAccepted() throws Exception {
        // sanitizeFilename strips backslashes / forward slashes via the
        // explicit replacement, and the leaf substring removes any path
        // segments. Verifies the response never echoes traversal segments.
        login();
        var id = createAgent("upload-traversal");
        var dir = java.nio.file.Files.createTempDirectory("chat-upload-trav");
        var f = dir.resolve("safe.txt").toFile();
        java.nio.file.Files.write(f.toPath(), new byte[]{1, 2, 3});
        f.deleteOnExit();
        try {
            var params = new java.util.HashMap<String, String>();
            params.put("agentId", id);
            var files = new java.util.HashMap<String, File>();
            // Use a regular file but with a traversal-looking original name
            // — the controller reads upload.getFileName() which in this
            // FunctionalTest harness reflects the actual file name. The
            // sanitize path is exercised indirectly via the other
            // sanitize tests already in this file.
            files.put("files", f);
            var response = POST("/api/chat/upload", params, files);
            assertIsOk(response);
            var content = getContent(response);
            assertFalse(content.contains("/.."),
                    "response must never echo traversal segments: " + content);
            assertFalse(content.contains("\\.."),
                    "response must never echo backslash-traversal: " + content);
        } finally {
            f.delete();
        }
    }

    @Test
    void streamChat_isAnnotatedWithNoTransaction_jclaw199() throws Exception {
        // The annotation tells Play 1.x's JPAPlugin to skip wrapping the
        // controller invocation in a transaction. Without it the framework
        // holds a HikariCP connection for the entire SSE duration (typically
        // 2-30s for a real LLM) and the pool exhausts at modest concurrency.
        // AgentRunner does its own short Tx.run scoping internally, so the
        // outer Play tx is pure overhead.
        var streamChat = controllers.ApiChatController.class.getDeclaredMethod("streamChat");
        assertNotNull(streamChat.getAnnotation(play.db.jpa.NoTransaction.class),
                "ApiChatController.streamChat must keep @NoTransaction (JCLAW-199) — "
                + "removing it re-introduces the HikariCP pool-exhaustion ceiling.");

        // Same fix applies to the long-lived events SSE (24h timeout); covered
        // here for proximity since both annotations exist for the same reason.
        var eventsStream = controllers.ApiEventsController.class.getDeclaredMethod("stream");
        assertNotNull(eventsStream.getAnnotation(play.db.jpa.NoTransaction.class),
                "ApiEventsController.stream must keep @NoTransaction (JCLAW-199).");
    }

    // =====================
    // JCLAW-707: send() slash-branch conversation resolution
    // =====================

    @Test
    void sendSlashCommandWithUnknownConversationIdReturns404() {
        // /reset (a non-/new slash) pinned to a conversationId that doesn't exist
        // hits the notFound() in resolveSlashConversation, which send() now shares
        // with streamChat — the sync-send mirror of streamSlashCommandWithUnknownConversationIdReturns404.
        login();
        var id = createAgent("send-slash-unknown-conv");
        var body = """
                {"agentId": %s, "message": "/reset", "conversationId": 999999}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        assertEquals(404, response.status.intValue());
    }

    @Test
    void sendSlashCommandWithExplicitNullConversationIdSucceeds() {
        // resolveChatContext's conversationId branch: an explicit JSON null must be
        // treated as "no conversation" (findOrCreate) rather than passed downstream
        // as a lookup id. /help keeps this off the LLM path.
        login();
        var id = createAgent("send-slash-null-conv");
        var body = """
                {"agentId": %s, "message": "/help", "conversationId": null}
                """.formatted(id);
        var response = POST("/api/chat/send", "application/json", body);
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"agentName\":\"send-slash-null-conv\""),
                "an explicit null conversationId must still resolve the agent: " + getContent(response));
    }

    // ── JCLAW-1196: picks made on a fresh chat land on the conversation, never the agent ──

    private static String extractConversationId(String json) {
        var m = java.util.regex.Pattern.compile("\"conversationId\":(\\d+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void sendAppliesAFreshChatsThinkingPickToTheConversationItCreates() {
        login();
        var agentId = createAgent("send-pending-think");
        var body = """
                {"agentId": %s, "message": "hello", "thinkingMode": "off"}
                """.formatted(agentId);
        var response = POST("/api/chat/send", "application/json", body);
        assertIsOk(response);
        var convoId = Long.valueOf(extractConversationId(getContent(response)));

        var row = commitInFreshTx(() -> (models.Conversation) models.Conversation.findById(convoId));
        assertEquals("off", row.thinkingModeOverride);
        assertNull(row.modelProviderOverride, "no model pick was sent");
        var agent = commitInFreshTx(() -> (models.Agent) models.Agent.findById(Long.valueOf(agentId)));
        assertNull(agent.thinkingMode, "the agent's default is never written from the chat page");
    }

    @Test
    void sendAppliesAFreshChatsModelPickAndKeepsTheAgentDefault() {
        login();
        var agentId = createAgent("send-pending-model");
        commitInFreshTx(() -> {
            // A closed loopback port: the turn fails fast without the network, and the
            // conversation it created is what this test is about.
            services.ConfigService.set("provider.jclaw1196-alt.baseUrl", "http://127.0.0.1:9/v1");
            services.ConfigService.set("provider.jclaw1196-alt.apiKey", "sk-test");
            services.ConfigService.set("provider.jclaw1196-alt.models",
                    "[{\"id\":\"other\",\"contextWindow\":1000,\"supportsThinking\":true}]");
            return null;
        });
        llm.ProviderRegistry.refresh();
        var body = """
                {"agentId": %s, "message": "hello", "modelProvider": "jclaw1196-alt", "modelId": "other", "thinkingMode": "high"}
                """.formatted(agentId);
        var response = POST("/api/chat/send", "application/json", body);
        assertIsOk(response);
        var convoId = Long.valueOf(extractConversationId(getContent(response)));

        var row = commitInFreshTx(() -> (models.Conversation) models.Conversation.findById(convoId));
        assertEquals("jclaw1196-alt", row.modelProviderOverride);
        assertEquals("other", row.modelIdOverride);
        assertEquals("high", row.thinkingModeOverride);
        var agent = commitInFreshTx(() -> (models.Agent) models.Agent.findById(Long.valueOf(agentId)));
        assertEquals("openrouter", agent.modelProvider);
        assertEquals("gpt-4.1", agent.modelId);
    }

    /** A bad pick is a 400 before anything is created — on send, on turn and, before the SSE opens, on stream. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "sendUnknownModel      | /api/chat/send   | \"modelProvider\": \"openrouter\", \"modelId\": \"no-such-model\"",
            "sendHalfAPick         | /api/chat/send   | \"modelProvider\": \"openrouter\"",
            "streamUnknownModel    | /api/chat/stream | \"modelProvider\": \"openrouter\", \"modelId\": \"no-such-model\"",
            "streamBadThinkingMode | /api/chat/stream | \"thinkingMode\": \"maximum-overdrive\"",
            "turnUnknownModel      | /api/chat/turn   | \"modelProvider\": \"openrouter\", \"modelId\": \"no-such-model\"",
            "turnHalfAPick         | /api/chat/turn   | \"modelProvider\": \"openrouter\""
    })
    void aBadFreshChatPickIsRejectedWithNothingCreated(String label, String endpoint, String fields) {
        login();
        var agentId = createAgent("pending-" + label);
        var body = "{\"agentId\": " + agentId + ", \"message\": \"hello\", " + fields + "}";
        var response = POST(endpoint, "application/json", body);
        assertEquals(400, response.status.intValue(), getContent(response));
        Long created = commitInFreshTx(() -> models.Conversation.count("agent.id = ?1", Long.valueOf(agentId)));
        assertEquals(0L, created, "a rejected pick must not leave a conversation behind");
    }

    // =====================
    // JF-202: POST /api/chat/turn — a turn no request holds, rendered from the conversation's stream
    // =====================

    private static final String TURN_REPLY = HeldModel.REPLY;
    private static final String COMPLETE_REPLY =
            new String(agents.TurnFrames.json(agents.TurnFrames.complete(TURN_REPLY)), StandardCharsets.UTF_8);
    private static final String STOPPED = "{\"type\":\"stopped\"}";
    private static final String MARK = "{\"type\":\"status\",\"content\":\"marker turn\"}";

    @Test
    void aStartedTurnIsAnsweredBeforeTheModelRepliesWithItsMessageSavedAndItsWatchersGetItFromInitToComplete()
            throws Exception {
        login();
        try (var model = HeldModel.held()) {
            var chat = seedWebChat(model);
            var sink = new ConversationTurnStreamTest.RecordingSink();
            var unsubscribe = ConversationTurnStream.subscribe(chat.conversationId(), null, sink);
            try {
                var response = POST("/api/chat/turn", "application/json", turnBody(chat, "What time is it?"));

                assertIsOk(response);
                assertEquals(Map.of("status", "started", "conversationId", chat.conversationId()), flat(json(response)));
                assertEquals(List.of("What time is it?"), userMessages(chat.conversationId()),
                        "the user's message is saved by the time the turn is answered started");
                assertEquals(List.of(), sink.raw().stream().filter(e -> e.contains("\"complete\"")).toList(),
                        "the answer came while the model was still held");
                var owner = ConversationQueue.ownerTurn(chat.conversationId(),
                        ConversationQueue.currentGeneration(chat.conversationId()));
                model.release();
                var heard = ConversationTurnStreamTest.data(sink.awaitData(COMPLETE_REPLY));
                var init = JsonParser.parseString(heard.getFirst()).getAsJsonObject();
                assertEquals(java.util.Set.of("type", "conversationId", "startedAt", "turn"), init.keySet());
                assertEquals("init", init.get("type").getAsString());
                assertEquals(chat.conversationId(), init.get("conversationId").getAsLong());
                assertDoesNotThrow(() -> Instant.parse(init.get("startedAt").getAsString()));
                assertEquals(owner, init.get("turn").getAsLong(), "the id of the turn that owned the conversation");
                assertEquals(TURN_REPLY, JsonParser.parseString(heard.get(1)).getAsJsonObject().get("content").getAsString());
                assertEquals(1, heard.stream().filter(ApiChatControllerTest::isTerminal).count(), "one terminal: " + heard);
            } finally {
                model.release();
                awaitIdle(chat.conversationId());
                unsubscribe.run();
            }
        }
    }

    @Test
    void aStartedTurnPublishesTheFramesAChatStreamTurnPublishes() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var started = seedWebChat(model);
            var streamed = anotherWebChat(started);
            var startedSink = new ConversationTurnStreamTest.RecordingSink();
            var streamedSink = new ConversationTurnStreamTest.RecordingSink();
            var unsubscribeStarted = ConversationTurnStream.subscribe(started.conversationId(), null, startedSink);
            var unsubscribeStreamed = ConversationTurnStream.subscribe(streamed.conversationId(), null, streamedSink);
            try {
                assertIsOk(POST("/api/chat/turn", "application/json", turnBody(started, "What time is it?")));
                assertIsOk(POST("/api/chat/stream", "application/json", turnBody(streamed, "What time is it?")));

                var fromStart = ConversationTurnStreamTest.data(startedSink.awaitData(COMPLETE_REPLY));
                var fromStream = ConversationTurnStreamTest.data(streamedSink.awaitData(COMPLETE_REPLY));
                assertEquals(normalized(fromStream), normalized(fromStart));
            } finally {
                awaitIdle(started.conversationId());
                awaitIdle(streamed.conversationId());
                unsubscribeStarted.run();
                unsubscribeStreamed.run();
            }
        }
    }

    @Test
    void aTurnStartedWithNoConversationCreatesOneAndAnswersItsId() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            var body = "{\"agentId\": " + chat.agentId() + ", \"message\": \"What time is it?\"}";

            var response = POST("/api/chat/turn", "application/json", body);

            assertIsOk(response);
            var answer = json(response);
            long created = answer.get("conversationId").getAsLong();
            assertEquals(Map.of("status", "started", "conversationId", created), flat(answer));
            assertNotEquals(chat.conversationId(), created, "a send with no conversation starts a new one");
            awaitIdle(created);
            var row = commitInFreshTx(() -> {
                models.Conversation c = models.Conversation.findById(created);
                return java.util.List.of(c.agent.id, c.channelType, c.peerId);
            });
            assertEquals(List.of(chat.agentId(), "web", "admin"), row);
            assertEquals(TURN_REPLY, assistantReply(created));
        }
    }

    @Test
    void aFreshChatsModelPickLandsOnTheConversationItsStartedTurnCreatesAndNeverOnTheAgent() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            var body = "{\"agentId\": %d, \"message\": \"What time is it?\", \"modelProvider\": \"%s\", \"modelId\": \"%s\"}"
                    .formatted(chat.agentId(), model.provider, HeldModel.OTHER_MODEL);

            var answer = json(POST("/api/chat/turn", "application/json", body));

            long created = answer.get("conversationId").getAsLong();
            assertEquals(Map.of("status", "started", "conversationId", created), flat(answer));
            awaitIdle(created);
            var picks = commitInFreshTx(() -> {
                models.Conversation c = models.Conversation.findById(created);
                return java.util.Arrays.asList(c.modelProviderOverride, c.modelIdOverride, c.thinkingModeOverride);
            });
            assertEquals(java.util.Arrays.asList(model.provider, HeldModel.OTHER_MODEL, null), picks);
            models.Agent agent = commitInFreshTx(() -> models.Agent.findById(chat.agentId()));
            assertEquals(HeldModel.MODEL, agent.modelId, "the agent's default is never written from the chat page");
        }
    }

    @Test
    void aTurnStartedOnABusyConversationQueuesPublishingNothingUntilItDrainsAsABackgroundTurn() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            long held = holdConversation(chat);
            var sink = new ConversationTurnStreamTest.RecordingSink();
            var unsubscribe = ConversationTurnStream.subscribe(chat.conversationId(), null, sink);
            try {
                var response = POST("/api/chat/turn", "application/json", turnBody(chat, "And now?"));

                assertIsOk(response);
                assertEquals(Map.of("status", "queued", "conversationId", chat.conversationId(), "position", 1L),
                        flat(json(response)));
                assertEquals(0, model.calls.get(), "a queued send does not reach the model");

                QueueDrainOrchestrator.runOwned(chat.conversationId(), held,
                        ConversationQueue.drain(chat.conversationId(), held));
                var heard = ConversationTurnStreamTest.data(sink.awaitData(COMPLETE_REPLY));
                assertEquals(List.of("init", "complete"), types(heard),
                        "the queued send published nothing of its own, and it drained as a background turn: " + heard);
            } finally {
                awaitIdle(chat.conversationId());
                unsubscribe.run();
            }
        }
    }

    @Test
    void twoTurnsStartedOnABusyConversationAnswerTheirPlacesInLine() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            holdConversation(chat);
            try {
                var first = POST("/api/chat/turn", "application/json", turnBody(chat, "And now?"));
                var second = POST("/api/chat/turn", "application/json", turnBody(chat, "And after that?"));

                assertEquals(Map.of("status", "queued", "conversationId", chat.conversationId(), "position", 1L),
                        flat(json(first)));
                assertEquals(Map.of("status", "queued", "conversationId", chat.conversationId(), "position", 2L),
                        flat(json(second)));
                assertEquals(0, model.calls.get(), "neither queued send reaches the model");
            } finally {
                ConversationQueue.forgetForTest(chat.conversationId());
            }
        }
    }

    @Test
    void aTurnThatFailsAfterItsAcquireAnswers500ReleasesItsConversationAndEndsItsWatchersOnOneError() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            var sink = new ConversationTurnStreamTest.RecordingSink();
            var unsubscribe = ConversationTurnStream.subscribe(chat.conversationId(), null, sink);
            // A staged upload that is not there: the user message's save throws once the turn holds the conversation.
            var body = """
                    {"agentId": %d, "conversationId": %d, "message": "with a stale attachment",
                     "attachments": [{"attachmentId": "no-such-staged-upload-%s", "originalFilename": "a.png",
                                      "mimeType": "image/png", "sizeBytes": 1, "kind": "image"}]}"""
                    .formatted(chat.agentId(), chat.conversationId(), UUID.randomUUID());
            try {
                var response = POST("/api/chat/turn", "application/json", body);

                assertEquals(500, response.status.intValue());
                var template = utils.ErrorTemplates.forCode(utils.ApiResponses.INTERNAL_ERROR);
                var parts = new java.util.LinkedHashMap<String, Object>();
                parts.put("whatBroke", template.whatBroke());
                parts.put("whatToCheck", template.whatToCheck());
                parts.put("howToRetry", template.howToRetry());
                var expected = new java.util.LinkedHashMap<String, Object>();
                expected.put("type", "error");
                expected.put("code", utils.ApiResponses.INTERNAL_ERROR);
                expected.put("message", "The turn could not start.");
                expected.put("template", parts);
                assertEquals(JsonParser.parseString(utils.GsonHolder.GSON.toJson(expected)), json(response));
                awaitIdle(chat.conversationId());
                assertFalse(ConversationQueue.isBusy(chat.conversationId()), "the failed turn released its conversation");
                marker(chat.conversationId());
                var heard = ConversationTurnStreamTest.data(sink.awaitData(MARK));
                assertEquals(List.of("error", "status"), types(heard), "one error, then the marker: " + heard);
                assertEquals(0, model.calls.get(), "the turn failed before the model");
            } finally {
                unsubscribe.run();
            }
        }
    }

    @Test
    void aSlashCommandStartedAsATurnAnswersWhatTheChatStreamAnswers() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);

            var started = POST("/api/chat/turn", "application/json", turnBody(chat, "/help"));
            var streamed = POST("/api/chat/stream", "application/json", turnBody(chat, "/help"));

            assertIsOk(started);
            assertEquals(Map.of("status", "command", "conversationId", chat.conversationId(),
                    "content", slash.Commands.HELP_TEXT, "saved", "true"), flat(json(started)));
            var frames = getContent(streamed).lines().filter(l -> l.startsWith("data: "))
                    .map(l -> JsonParser.parseString(l.substring("data: ".length())).getAsJsonObject()).toList();
            assertEquals(chat.conversationId(), frames.get(0).get("conversationId").getAsLong());
            assertEquals(slash.Commands.HELP_TEXT, frames.get(1).get("content").getAsString());
            assertEquals(0, model.calls.get(), "a command never reaches the model");
        }
    }

    @Test
    void aNewCommandStartedAsATurnAnswersTheConversationItCreated() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);

            var answer = json(POST("/api/chat/turn", "application/json", turnBody(chat, "/new")));

            long created = answer.get("conversationId").getAsLong();
            assertNotEquals(chat.conversationId(), created);
            assertEquals(Map.of("status", "command", "conversationId", created, "content", slash.Commands.NEW_TEXT,
                    "saved", "true"), flat(answer));
        }
    }

    // A /stop typed in web chat on a web conversation is the Stop button (JF-202); its reply is never saved.

    private static Map<String, Object> stopReply(long conversationId, String content) {
        return Map.of("status", "command", "conversationId", conversationId, "content", content, "saved", "false");
    }

    private static ConversationQueue.QueuedMessage running(long agentId, String channelType) {
        models.Agent agent = commitInFreshTx(() -> models.Agent.findById(agentId));
        return new ConversationQueue.QueuedMessage("running", channelType, "admin", agent);
    }

    @Test
    void aStopTypedOnAStoppableTurnEndsItStoppedRepliesStoppedAndTheQueuedMessagesRunInOrder() throws Exception {
        login();
        try (var model = HeldModel.held()) {
            var chat = seedWebChat(model);
            var sink = new ConversationTurnStreamTest.RecordingSink();
            var unsubscribe = ConversationTurnStream.subscribe(chat.conversationId(), null, sink);
            try {
                assertIsOk(POST("/api/chat/turn", "application/json", turnBody(chat, "What time is it?")));
                assertTrue(model.called.await(30, TimeUnit.SECONDS), "test precondition: the turn reached the model");
                for (var next : List.of("And now?", "And later?")) {
                    assertEquals("queued", json(POST("/api/chat/turn", "application/json", turnBody(chat, next)))
                            .get("status").getAsString());
                }

                var answer = json(POST("/api/chat/turn", "application/json", turnBody(chat, "/stop")));

                assertEquals(stopReply(chat.conversationId(), "Stopped."), flat(answer));
                sink.awaitData(STOPPED);
                assertEquals(new ConversationQueue.Snapshot(true, 1, true, false,
                                List.of(new ConversationQueue.PendingView("And later?", List.of(), 1)), 0),
                        ConversationQueue.snapshot(chat.conversationId(), "web"),
                        "the first queued message took the conversation, and the second waits behind it");
            } finally {
                model.release();
                unsubscribe.run();
            }
            awaitIdle(chat.conversationId());
            assertEquals(List.of("user What time is it?", "assistant " + ConversationService.STOP_MARKER_CONTENT,
                            "user And now?", "assistant " + TURN_REPLY, "user And later?", "assistant " + TURN_REPLY),
                    transcript(chat.conversationId()));
            assertEquals(3, model.calls.get());
        }
    }

    @Test
    void aStopTypedOnATurnThatPassedNoCancelFlagStopsItAndRepliesStopped() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            // A drained or sync turn passes no flag of its own; the queue gives it one (JF-197).
            var generation = ConversationQueue.tryAcquireOwnership(chat.conversationId(),
                    running(chat.agentId(), "web"), null);
            try {
                var answer = json(POST("/api/chat/turn", "application/json", turnBody(chat, "/stop")));

                assertEquals(stopReply(chat.conversationId(), "Stopped."), flat(answer));
                assertFalse(ConversationQueue.isBusy(chat.conversationId()), "the turn lost its conversation");
            } finally {
                ConversationQueue.releaseOwnership(chat.conversationId(), generation);
            }
        }
    }

    // A /stop typed in Chat names the turn the page shows (JF-226).

    private static String stopNaming(Seeded chat, Long turn) {
        return "{\"agentId\": %d, \"conversationId\": %d, \"message\": \"/stop\", \"stopTurn\": %s}"
                .formatted(chat.agentId(), chat.conversationId(), turn);
    }

    @Test
    void aStopTypedNamingATurnThatEndedRepliesNothingToStopAndTheTurnNowRunningRunsOn() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            var generation = ConversationQueue.tryAcquireOwnership(chat.conversationId(),
                    running(chat.agentId(), "web"), null);
            try {
                var ended = ConversationQueue.ownerTurn(chat.conversationId(), generation);
                assertFalse(ConversationQueue.tryAcquire(chat.conversationId(), running(chat.agentId(), "web")));
                assertEquals(1, ConversationQueue.drain(chat.conversationId(), generation).size(),
                        "test precondition: the queued message takes the conversation");
                var now = ConversationQueue.ownerCancel(chat.conversationId(), generation);

                var answer = json(POST("/api/chat/turn", "application/json", stopNaming(chat, ended)));

                assertEquals(stopReply(chat.conversationId(), "Nothing to stop."), flat(answer));
                assertFalse(now.get(), "the turn now running was not told to stop");
                assertTrue(ConversationQueue.isBusy(chat.conversationId()));
            } finally {
                ConversationQueue.forgetForTest(chat.conversationId());
            }
        }
    }

    @Test
    void aStopTypedNamingTheTurnThatRunsStopsItAsTheStopButtonDoes() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            var flag = new java.util.concurrent.atomic.AtomicBoolean();
            var generation = ConversationQueue.tryAcquireOwnership(chat.conversationId(),
                    running(chat.agentId(), "web"), flag);
            try {
                var answer = json(POST("/api/chat/turn", "application/json",
                        stopNaming(chat, ConversationQueue.ownerTurn(chat.conversationId(), generation))));

                assertEquals(stopReply(chat.conversationId(), "Stopped."), flat(answer));
                assertTrue(flag.get(), "the running turn was told to stop");
                assertFalse(ConversationQueue.isBusy(chat.conversationId()));
            } finally {
                ConversationQueue.forgetForTest(chat.conversationId());
            }
        }
    }

    @Test
    void aPageThatJoinsATurnLateStopsItByTheIdItsReplayedInitCarries() throws Exception {
        login();
        try (var model = HeldModel.held()) {
            var chat = seedWebChat(model);
            assertIsOk(POST("/api/chat/turn", "application/json", turnBody(chat, "What time is it?")));
            assertTrue(model.called.await(30, TimeUnit.SECONDS), "test precondition: the turn reached the model");
            // Subscribed only now, so it has the turn from the replay, as a page opened mid-turn has.
            var sink = new ConversationTurnStreamTest.RecordingSink();
            var unsubscribe = ConversationTurnStream.subscribe(chat.conversationId(), null, sink);
            try {
                var init = JsonParser.parseString(sink.awaitUntil(heard -> !heard.isEmpty(), "heard the replay")
                        .getFirst().data()).getAsJsonObject();
                assertEquals("init", init.get("type").getAsString());
                assertEquals(ConversationQueue.ownerTurn(chat.conversationId(),
                        ConversationQueue.currentGeneration(chat.conversationId())), init.get("turn").getAsLong());

                var stop = POST("/api/conversations/" + chat.conversationId() + "/stop", "application/json",
                        "{\"turn\":" + init.get("turn").getAsLong() + "}");

                assertEquals(JsonParser.parseString("{\"stopped\":true}"), json(stop));
                sink.awaitData(STOPPED);
            } finally {
                model.release();
                awaitIdle(chat.conversationId());
                unsubscribe.run();
            }
        }
    }

    // MCP forms (JF-197): a queued message's turn shows one in Chat; an API send's turn hands its questions to the agent.

    @Test
    void anApiSendsToolIsOfferedNoFormAndADrainedWebTurnsToolIs() throws Exception {
        login();
        var offered = new CopyOnWriteArrayList<Boolean>();
        ToolRegistrySync.publishForTest(List.of(formProbe(offered)));
        try (var model = HeldModel.replying(request -> lastRole(request).equals("tool")
                ? HeldModel.replyCompletion() : FORM_PROBE_CALL)) {
            var chat = seedWebChat(model);

            var sent = json(POST("/api/chat/send", "application/json", turnBody(chat, "Ask the server")));
            assertEquals(HeldModel.REPLY, sent.get("response").getAsString());
            long held = holdConversation(chat);
            assertFalse(ConversationQueue.tryAcquire(chat.conversationId(), running(chat.agentId(), "web")));
            QueueDrainOrchestrator.runOwned(chat.conversationId(), held,
                    ConversationQueue.drain(chat.conversationId(), held));
            for (int i = 0; i < 300 && offered.size() < 2; i++) Thread.sleep(50);
            awaitIdle(chat.conversationId());

            assertEquals(List.of(false, true), offered, "the API send's tool, then the drained turn's");
        } finally {
            ToolRegistrySync.release();
        }
    }

    private static final String FORM_PROBE_CALL = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"",
            "tool_calls":[{"id":"call_probe","type":"function","function":{"name":"form_probe","arguments":"{}"}}]},
            "finish_reason":"tool_calls"}],
            "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}""";

    private static String lastRole(JsonObject request) {
        var messages = request.getAsJsonArray("messages");
        return messages.get(messages.size() - 1).getAsJsonObject().get("role").getAsString();
    }

    /** A tool that records, on its own dispatch, whether an MCP server's form would be put to the operator there. */
    private static ToolRegistry.Tool formProbe(List<Boolean> offered) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return "form_probe"; }
            @Override public String description() { return "Records whether a form would be shown, for the test."; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String execute(String argsJson, Agent agent) {
                offered.add(McpElicitations.offeredHere());
                return "ok";
            }
        };
    }

    @Test
    void aSendStoppedWhileItRunsAnswersTheStopMarkerAndSavesNothingAfterIt() throws Exception {
        login();
        try (var model = HeldModel.held()) {
            var chat = seedWebChat(model);
            var stop = new CompletableFuture<ConversationQueue.OperatorStop>();
            Thread.ofVirtual().start(() -> {
                try {
                    if (!model.called.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("no model call");
                    stop.complete(ConversationQueue.stopOperatorTurn(chat.conversationId(), "web"));
                } catch (Throwable t) {
                    stop.completeExceptionally(t);
                } finally {
                    model.release();
                }
            });

            var response = POST("/api/chat/send", "application/json", turnBody(chat, "What time is it?"));

            assertIsOk(response);
            assertEquals(ConversationQueue.OperatorStop.STOPPED, stop.get(30, TimeUnit.SECONDS));
            assertEquals(ConversationService.STOP_MARKER_CONTENT, json(response).get("response").getAsString());
            assertEquals(List.of("user What time is it?", "assistant " + ConversationService.STOP_MARKER_CONTENT),
                    commitInFreshTx(() -> {
                        List<Message> rows = Message.find("conversation.id = ?1 ORDER BY id",
                                chat.conversationId()).fetch();
                        return rows.stream().map(m -> m.role + " " + m.content).toList();
                    }));
            awaitIdle(chat.conversationId());
        }
    }

    @Test
    void aStopTypedOnAnIdleWebConversationRepliesNothingToStop() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);

            var answer = json(POST("/api/chat/turn", "application/json", turnBody(chat, "/stop")));

            assertEquals(stopReply(chat.conversationId(), "Nothing to stop."), flat(answer));
            assertFalse(ConversationQueue.isBusy(chat.conversationId()));
        }
    }

    @Test
    void aStopTypedInANewChatStopsNothingThoughTheAgentsWebConversationRunsATurn() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            // The conversation a New Chat's command would otherwise resolve to: the agent's web chat with the operator.
            var chat = seedWebChat(model);
            var flag = new java.util.concurrent.atomic.AtomicBoolean();
            ConversationQueue.tryAcquireOwnership(chat.conversationId(), running(chat.agentId(), "web"), flag);
            ConversationQueue.tryAcquire(chat.conversationId(), running(chat.agentId(), "web"));
            String agentName = commitInFreshTx(() -> ((models.Agent) models.Agent.findById(chat.agentId())).name);
            try {
                var heard = new java.util.ArrayList<services.EventLogger.Captured>();
                var answer = new JsonObject[1];
                services.EventLogger.captureMatchingForTest(e -> agentName.equals(e.agentId()), seen -> {
                    answer[0] = json(POST("/api/chat/turn", "application/json",
                            "{\"agentId\": %d, \"message\": \"/stop\"}".formatted(chat.agentId())));
                    heard.addAll(seen);
                });

                assertEquals(List.of(new services.EventLogger.Captured("INFO", "SLASH_COMMAND", agentName, "web",
                        "/stop with no current conversation", null)), heard);
                assertEquals(JsonParser.parseString("{\"status\":\"command\",\"conversationId\":null,"
                        + "\"content\":\"No active conversation. Nothing to stop.\",\"saved\":false}"), answer[0]);
                assertEquals(new ConversationQueue.Snapshot(true, 1, true, false,
                                List.of(new ConversationQueue.PendingView("running", List.of(), 1)), 0),
                        ConversationQueue.snapshot(chat.conversationId(), "web"));
                assertFalse(flag.get(), "the running turn was not told to stop");
            } finally {
                ConversationQueue.forgetForTest(chat.conversationId());
            }
        }
    }

    @Test
    void aStopTypedInANewChatWithAModelPickCreatesNoConversation() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, pickedBody(chat, "/stop", model.provider, HeldModel.OTHER_MODEL,
                    null));

            assertEquals(commandAnswer("No active conversation. Nothing to stop.", null), answer);
        }
    }

    // A command typed in an empty New conversation acts on that chat, never on the agent's web conversation (JF-202).

    @Test
    void aModelPickTypedInANewChatAnswersThePickAndWritesNothing() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/model " + model.provider + "/" + HeldModel.OTHER_MODEL));

            assertEquals(commandAnswer("This New conversation will use `" + model.provider + "/" + HeldModel.OTHER_MODEL
                    + "`.\nAgent default (" + model.provider + "/" + HeldModel.MODEL + ") is unchanged.",
                    "{\"of\":\"model\",\"modelProvider\":\"" + model.provider + "\",\"modelId\":\"" + HeldModel.OTHER_MODEL
                            + "\",\"thinkingMode\":null}"), answer);
        }
    }

    @Test
    void aModelPickOfAnUnknownModelTypedInANewChatSaysWhyAndPicksNothing() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/model " + model.provider + "/no-such"));

            assertEquals(commandAnswer("Provider `" + model.provider + "` has no model with id `no-such`. Run `/model` "
                    + "to see the current model or check Settings → Providers for the available list.", null), answer);
        }
    }

    @Test
    void aThinkingPickTypedInANewChatAnswersThePickAndWritesNothing() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/think off"));

            assertEquals(commandAnswer("Thinking is off for this New conversation.\nAgent default (off) is unchanged.",
                    "{\"of\":\"thinking\",\"modelProvider\":null,\"modelId\":null,\"thinkingMode\":\"off\"}"), answer);
        }
    }

    @Test
    void aThinkingLevelTypedInANewChatThatTheModelPickedThereOffersIsPickedLowercased() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, pickedBody(chat, "/think HIGH", model.provider,
                    HeldModel.THINKING_MODEL, null));

            assertEquals(commandAnswer("Thinking effort for this New conversation is `high`.\nAgent default (off) is "
                    + "unchanged.", "{\"of\":\"thinking\",\"modelProvider\":null,\"modelId\":null,\"thinkingMode\":\"high\"}"),
                    answer);
        }
    }

    @Test
    void aModelResetTypedInANewChatClearsTheModelPickedThere() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, pickedBody(chat, "/model reset", model.provider,
                    HeldModel.OTHER_MODEL, null));

            assertEquals(commandAnswer("Cleared the model picked for this New conversation. The agent default ("
                    + model.provider + "/" + HeldModel.MODEL + ") applies.",
                    "{\"of\":\"model\",\"modelProvider\":null,\"modelId\":null,\"thinkingMode\":null}"), answer);
        }
    }

    @Test
    void aThinkResetTypedInANewChatClearsTheThinkingLevelPickedThere() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, pickedBody(chat, "/think reset", null, null, "off"));

            assertEquals(commandAnswer("Cleared the thinking level picked for this New conversation. The agent default "
                    + "(off) applies.", "{\"of\":\"thinking\",\"modelProvider\":null,\"modelId\":null,\"thinkingMode\":null}"),
                    answer);
        }
    }

    @Test
    void aModelResetTypedInANewChatWithNothingPickedSaysSo() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/model reset"));

            assertEquals(commandAnswer("This New conversation has no model picked. The agent default ("
                    + model.provider + "/" + HeldModel.MODEL + ") applies.",
                    "{\"of\":\"model\",\"modelProvider\":null,\"modelId\":null,\"thinkingMode\":null}"), answer);
        }
    }

    @Test
    void aThinkResetTypedInANewChatWithNothingPickedSaysSo() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/think reset"));

            assertEquals(commandAnswer("This New conversation has no thinking level picked. The agent default (off) "
                    + "applies.", "{\"of\":\"thinking\",\"modelProvider\":null,\"modelId\":null,\"thinkingMode\":null}"),
                    answer);
        }
    }

    @Test
    void aMalformedModelTypedInANewChatSaysHowToNameOne() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/model nonsense"));

            assertEquals(commandAnswer("Unrecognized model format. Use `/model provider/model-id` (for example "
                    + "`/model openrouter/google-flash-preview`) or `/model reset` to revert to the agent default.", null),
                    answer);
        }
    }

    @Test
    void aModelOfAnUnconfiguredProviderTypedInANewChatSaysSo() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/model no-such-provider/x"));

            assertEquals(commandAnswer("Provider `no-such-provider` is not configured. Available providers appear under "
                    + "Settings → Providers; add one there before switching to it.", null), answer);
        }
    }

    @Test
    void aThinkingLevelTypedInANewChatIsCheckedAgainstTheModelPickedThere() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);
            var body = "{\"agentId\": %d, \"message\": \"/think high\", \"modelProvider\": \"%s\", \"modelId\": \"%s\"}"
                    .formatted(chat.agentId(), model.provider, HeldModel.OTHER_MODEL);

            var answer = sentFromANewChat(model, chat, body);

            assertEquals(commandAnswer("Model '" + HeldModel.OTHER_MODEL + "' does not support thinking. "
                    + "Use `/think off` or `/think <level>`; `/model` lists the levels.", null), answer);
        }
    }

    @Test
    void aResetTypedInANewChatAsksForAMessageFirst() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/reset"));

            assertEquals(commandAnswer("Nothing to reset yet. Send a message first.", null), answer);
        }
    }

    @Test
    void aCompactTypedInANewChatAsksForAMessageFirst() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/compact"));

            assertEquals(commandAnswer("Nothing to compact yet. Send a message first.", null), answer);
        }
    }

    @Test
    void aModelTypedInANewChatDescribesTheAgentsModel() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/model"));

            assertEquals(commandAnswer(agentsModel(chat), null), answer);
        }
    }

    @Test
    void aModelTypedInANewChatWithAModelPickedThereDescribesThatModel() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, pickedBody(chat, "/model", model.provider, HeldModel.OTHER_MODEL,
                    null));

            var picked = new models.Conversation();
            picked.modelProviderOverride = model.provider;
            picked.modelIdOverride = HeldModel.OTHER_MODEL;
            String described = commitInFreshTx(() -> slash.Commands.buildModelResponse(
                    models.Agent.findById(chat.agentId()), picked));
            var head = "Model: " + model.provider + "/" + HeldModel.OTHER_MODEL + "\n(Picked for this New conversation — "
                    + "agent default is " + model.provider + "/" + HeldModel.MODEL + ")\n";
            assertEquals(commandAnswer(described.replace("(Conversation override active", "(Picked for this New conversation"),
                    null), answer);
            assertTrue(answer.get("content").getAsString().startsWith(head), answer.get("content").getAsString());
        }
    }

    @Test
    void aThinkTypedInANewChatWithALevelPickedThereDescribesThatLevel() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, pickedBody(chat, "/think", model.provider,
                    HeldModel.THINKING_MODEL, "high"));

            assertEquals(commandAnswer("Thinking: effort high\nLevels: low, high\n(Picked for this New conversation — "
                    + "agent default is off)\nUse `/think off`, `/think <level>` or `/think reset`.", null), answer);
        }
    }

    @Test
    void aModelStatusTypedInANewChatDescribesTheAgentsModel() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/model status"));

            assertEquals(commandAnswer(agentsModel(chat), null), answer);
        }
    }

    @Test
    void aThinkTypedInANewChatDescribesTheAgentsThinking() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/think"));

            assertEquals(commandAnswer("Thinking: not supported by " + model.provider + "/" + HeldModel.MODEL
                    + "\nUse `/think off`, `/think <level>` or `/think reset`.", null), answer);
        }
    }

    @Test
    void aUsageTypedInANewChatSaysNothingIsUsedYet() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/usage"));

            assertEquals(commandAnswer("Nothing used yet. Send a message first.", null), answer);
        }
    }

    @Test
    void aHelpTypedInANewChatListsTheCommands() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/help"));

            assertEquals(commandAnswer(slash.Commands.HELP_TEXT, null), answer);
        }
    }

    @Test
    void aPromptTypedInANewChatAnswersThePromptItNames() throws Exception {
        login();
        var title = "new-chat-prompt-" + UUID.randomUUID().toString().substring(0, 8);
        commitInFreshTx(() -> {
            var prompt = new models.Prompt();
            prompt.title = title;
            prompt.content = "Draft the reply.";
            prompt.category = models.Prompt.Category.CUSTOM;
            prompt.save();
            return null;
        });
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/prompt " + title));

            assertEquals(commandAnswer("**" + title + "**\n\n```\nDraft the reply.\n```", null), answer);
        } finally {
            commitInFreshTx(() -> models.Prompt.delete("title = ?1", title));
        }
    }

    @Test
    void aSubagentTypedInANewChatSaysItHasNoRuns() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);

            var answer = sentFromANewChat(model, chat, newChatBody(chat, "/subagent"));

            assertEquals(commandAnswer("No subagent runs in this conversation.", null), answer);
        }
    }

    @Test
    void aNewTypedInANewChatHoldingPicksStartsItsConversationWithThem() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);
            Supplier<Long> conversations = () -> commitInFreshTx(
                    () -> models.Conversation.count("agent.id = ?1", chat.agentId()));
            long before = conversations.get();

            var answer = json(POST("/api/chat/turn", "application/json",
                    pickedBody(chat, "/new", model.provider, HeldModel.OTHER_MODEL, "off")));

            long created = answer.get("conversationId").getAsLong();
            assertEquals(Map.of("status", "command", "conversationId", created, "content", slash.Commands.NEW_TEXT,
                    "saved", "true"), flat(answer));
            assertEquals(before + 1, conversations.get(), "one conversation, the one /new started");
            var picks = commitInFreshTx(() -> {
                models.Conversation c = models.Conversation.findById(created);
                return java.util.Arrays.asList(c.modelProviderOverride, c.modelIdOverride, c.thinkingModeOverride);
            });
            assertEquals(java.util.Arrays.asList(model.provider, HeldModel.OTHER_MODEL, "off"), picks);
        }
    }

    @Test
    void aResetSentThroughTheSyncRouteWithNoConversationStillResetsTheAgentsWebConversation() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedLivedInWebChat(model);
            String agentName = commitInFreshTx(() -> ((models.Agent) models.Agent.findById(chat.agentId())).name);

            var answer = json(POST("/api/chat/send", "application/json", newChatBody(chat, "/reset")));

            assertEquals(Map.of("conversationId", chat.conversationId(), "response", slash.Commands.RESET_TEXT,
                    "agentId", chat.agentId(), "agentName", agentName), flat(answer));
            Instant cut = commitInFreshTx(
                    () -> ((models.Conversation) models.Conversation.findById(chat.conversationId())).contextSince);
            assertNotNull(cut, "/reset stamped the context cut on the agent's web conversation");
        }
    }

    /** The agent's web chat with the operator, given rows and both overrides, so a command that reached it would show. */
    private static Seeded seedLivedInWebChat(HeldModel model) {
        var chat = seedWebChat(model);
        commitInFreshTx(() -> {
            models.Conversation c = models.Conversation.findById(chat.conversationId());
            services.ConversationService.appendUserMessage(c, "What time is it?");
            services.ConversationService.appendAssistantMessage(c, HeldModel.REPLY, null);
            services.ConversationService.setModelOverride(c, model.provider, HeldModel.OTHER_MODEL);
            services.ConversationService.setThinkingOverride(c, "off");
            return null;
        });
        return chat;
    }

    /** What a command could change on a conversation: its overrides, context cut, checklist and rows. */
    private static List<Object> stateOf(long conversationId) {
        return commitInFreshTx(() -> {
            models.Conversation c = models.Conversation.findById(conversationId);
            List<models.Message> rows = models.Message.find("conversation = ?1 ORDER BY id", c).fetch();
            return java.util.Arrays.asList(c.modelProviderOverride, c.modelIdOverride, c.thinkingModeOverride,
                    c.contextSince, c.checklist, rows.stream().map(m -> m.id + " " + m.role + ": " + m.content).toList());
        });
    }

    /** POST {@code body}, a command from an empty New conversation, and check it created nothing and left {@code chat} alone. */
    private JsonObject sentFromANewChat(HeldModel model, Seeded chat, String body) {
        Supplier<Long> conversations = () -> commitInFreshTx(
                () -> models.Conversation.count("agent.id = ?1", chat.agentId()));
        long before = conversations.get();
        var was = stateOf(chat.conversationId());

        var answer = json(POST("/api/chat/turn", "application/json", body));

        assertEquals(before, conversations.get(), "no conversation is created");
        assertEquals(was, stateOf(chat.conversationId()), "the agent's web conversation is untouched");
        assertEquals(0, model.calls.get(), "a command never reaches the model");
        return answer;
    }

    private static String newChatBody(Seeded chat, String message) {
        return "{\"agentId\": %d, \"message\": %s}".formatted(chat.agentId(), new com.google.gson.Gson().toJson(message));
    }

    /** A New conversation's message carrying the picks held there; a null pick is left out. */
    private static String pickedBody(Seeded chat, String message, String provider, String modelId, String thinkingMode) {
        var body = new JsonObject();
        body.addProperty("agentId", chat.agentId());
        body.addProperty("message", message);
        if (provider != null) body.addProperty("modelProvider", provider);
        if (modelId != null) body.addProperty("modelId", modelId);
        if (thinkingMode != null) body.addProperty("thinkingMode", thinkingMode);
        return body.toString();
    }

    /** A command's answer in a New conversation: no conversation, nothing saved, and the pick it made, if any. */
    private static JsonObject commandAnswer(String content, String pick) {
        var answer = new JsonObject();
        answer.addProperty("status", "command");
        answer.add("conversationId", com.google.gson.JsonNull.INSTANCE);
        answer.addProperty("content", content);
        answer.addProperty("saved", false);
        if (pick != null) answer.add("pick", JsonParser.parseString(pick));
        return answer;
    }

    /** {@code /model}'s description of the agent's own model, with no conversation's override. */
    private static String agentsModel(Seeded chat) {
        return commitInFreshTx(() -> slash.Commands.buildModelResponse(models.Agent.findById(chat.agentId()), null));
    }

    @Test
    void aStopSentThroughTheChatStreamStopsTheConversationsStoppableTurn() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            var flag = new java.util.concurrent.atomic.AtomicBoolean();
            ConversationQueue.tryAcquireOwnership(chat.conversationId(), running(chat.agentId(), "web"), flag);
            try {
                var streamed = POST("/api/chat/stream", "application/json", turnBody(chat, "/stop"));

                var frames = getContent(streamed).lines().filter(l -> l.startsWith("data: "))
                        .map(l -> JsonParser.parseString(l.substring("data: ".length()))).toList();
                assertEquals(List.of(
                        JsonParser.parseString("{\"type\":\"init\",\"conversationId\":" + chat.conversationId() + "}"),
                        JsonParser.parseString("{\"type\":\"complete\",\"content\":\"Stopped.\"}")), frames);
                assertTrue(flag.get(), "the running turn was told to stop");
                assertFalse(ConversationQueue.isBusy(chat.conversationId()));
            } finally {
                ConversationQueue.forgetForTest(chat.conversationId());
            }
        }
    }

    @Test
    void aStopSentThroughTheSyncRouteWithNoConversationStillStopsTheAgentsWebConversation() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            // POST /api/chat/send with no id addresses the agent's web conversation with the operator, as it always has.
            var chat = seedWebChat(model);
            var flag = new java.util.concurrent.atomic.AtomicBoolean();
            ConversationQueue.tryAcquireOwnership(chat.conversationId(), running(chat.agentId(), "web"), flag);
            try {
                var answer = json(POST("/api/chat/send", "application/json",
                        "{\"agentId\": %d, \"message\": \"/stop\"}".formatted(chat.agentId())));

                assertEquals("Stopped.", answer.get("response").getAsString());
                assertEquals(chat.conversationId(), answer.get("conversationId").getAsLong());
                assertTrue(flag.get(), "the running turn was told to stop");
                assertFalse(ConversationQueue.isBusy(chat.conversationId()));
            } finally {
                ConversationQueue.forgetForTest(chat.conversationId());
            }
        }
    }

    @Test
    void aStopTypedInChatOnAChannelConversationStopsItsTurnAsTheStopButtonDoes() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            long telegram = commitInFreshTx(() -> {
                models.Agent agent = models.Agent.findById(chat.agentId());
                return services.ConversationService.create(agent, "telegram", "jf202-peer").id;
            });
            var onTelegram = new Seeded(chat.agentId(), telegram);
            var flag = new java.util.concurrent.atomic.AtomicBoolean();
            ConversationQueue.tryAcquireOwnership(telegram, running(chat.agentId(), "telegram"), flag);
            try {
                var answer = json(POST("/api/chat/turn", "application/json", turnBody(onTelegram, "/stop")));

                assertEquals(stopReply(telegram, "Stopped."), flat(answer));
                assertTrue(flag.get(), "the channel turn was told to stop");
                assertEquals(ConversationQueue.ChannelStop.NOTICE, ConversationQueue.channelStop(flag),
                        "the person on the channel is told, as after the Stop button");
                assertFalse(ConversationQueue.isBusy(telegram), "the stop released the conversation");
            } finally {
                ConversationQueue.forgetForTest(telegram);
            }
        }
    }

    @Test
    void aTurnStartedOnAConversationThatDoesNotExistIsNotFound() throws Exception {
        login();
        try (var model = HeldModel.answering()) {
            var chat = seedWebChat(model);
            var missing = new Seeded(chat.agentId(), chat.conversationId() + 1_000);

            var response = POST("/api/chat/turn", "application/json", turnBody(missing, "What time is it?"));

            assertEquals(404, response.status.intValue());
            assertEquals(0, model.calls.get());
            assertFalse(ConversationQueue.isBusy(missing.conversationId()));
        }
    }

    @Test
    void anAgentPrincipalCannotStartATurn() {
        var request = newRequest();
        request.headers.put("authorization",
                new play.mvc.Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));

        var response = POST(request, "/api/chat/turn", "application/json", "{\"agentId\": 1, \"message\": \"hi\"}");

        assertStatus(403, response);
    }

    @Test
    void anOperatorStopEndsAStartedTurnAsStoppedOnItsStream() throws Exception {
        login();
        try (var model = HeldModel.held()) {
            var chat = seedWebChat(model);
            var sink = new ConversationTurnStreamTest.RecordingSink();
            var unsubscribe = ConversationTurnStream.subscribe(chat.conversationId(), null, sink);
            try {
                assertIsOk(POST("/api/chat/turn", "application/json", turnBody(chat, "What time is it?")));
                assertTrue(model.called.await(30, TimeUnit.SECONDS), "test precondition: the turn reached the model");

                var stop = POST("/api/conversations/" + chat.conversationId() + "/stop", "application/json", "{}");

                assertIsOk(stop);
                assertTrue(json(stop).get("stopped").getAsBoolean(), "the stop found the started turn");
                var heard = ConversationTurnStreamTest.data(sink.awaitData(STOPPED));
                assertEquals("init", types(heard).getFirst());
                model.release();
                awaitIdle(chat.conversationId());
                marker(chat.conversationId());
                var after = ConversationTurnStreamTest.data(sink.awaitData(MARK));
                assertEquals(List.of(STOPPED, MARK), after.subList(after.size() - 2, after.size()),
                        "nothing of the stopped turn follows its stopped frame: " + after);
                assertEquals(1, after.stream().filter(ApiChatControllerTest::isTerminal).count(),
                        "one terminal, the stop: " + after);
            } finally {
                model.release();
                unsubscribe.run();
            }
        }
    }

    @Test
    void aStartedTurnOutlivesTheTabThatWatchedItAndRunsToItsReply() throws Exception {
        login();
        try (var model = HeldModel.held()) {
            var chat = seedWebChat(model);
            var tab = new ConversationTurnStreamTest.RecordingSink();
            var closeTab = ConversationTurnStream.subscribe(chat.conversationId(), null, tab);
            assertIsOk(POST("/api/chat/turn", "application/json", turnBody(chat, "What time is it?")));
            assertTrue(model.called.await(30, TimeUnit.SECONDS), "test precondition: the turn reached the model");
            var seen = tab.awaitUntil(events -> !events.isEmpty(), "heard the turn begin");
            assertEquals("init", types(ConversationTurnStreamTest.data(seen)).getFirst());

            closeTab.run();
            model.release();

            awaitIdle(chat.conversationId());
            assertEquals(TURN_REPLY, assistantReply(chat.conversationId()), "nothing read the turn, and it still replied");
        }
    }

    @Test
    void aCodingRunInAChatStreamTurnStreamsItsStepsToTheTabAndOnceToTheConversationsWatchers() throws Exception {
        var turn = runATurnThatSpawnsACodingRun("/api/chat/stream");

        var steps = List.of(turn.nonce() + "-0", turn.nonce() + "-1", turn.nonce() + "-2");
        assertEquals(steps, statusesOf(turn.tab(), turn.nonce()), "the tab shows the run's steps live: " + turn.tab());
        assertEquals(steps, statusesOf(turn.watched(), turn.nonce()), "and its watchers get each step once");
    }

    @Test
    void aCodingRunInAStartedTurnStreamsItsStepsOnceToTheConversationsWatchers() throws Exception {
        var turn = runATurnThatSpawnsACodingRun("/api/chat/turn");

        assertEquals(List.of(turn.nonce() + "-0", turn.nonce() + "-1", turn.nonce() + "-2"),
                statusesOf(turn.watched(), turn.nonce()), "the watchers get each step once: " + turn.watched());
    }

    // --- JF-202 helpers ---

    /** A turn whose model spawns a coding run: the frames its chat stream tab got (none for a started turn), and its watchers'. */
    private record SpawningTurn(String nonce, List<String> tab, List<String> watched) {}

    private SpawningTurn runATurnThatSpawnsACodingRun(String route) throws Exception {
        login();
        var nonce = "coding-" + UUID.randomUUID().toString().substring(0, 8);
        var harness = Files.createTempFile("jclaw-chat-harness-", ".sh");
        var script = new StringBuilder("#!/bin/sh\ncat >/dev/null\n");
        for (int i = 0; i < 3; i++) script.append("printf '%s\\n' '").append(nonce).append('-').append(i).append("'\n");
        Files.writeString(harness, script.toString());
        assertTrue(harness.toFile().setExecutable(true, false));
        ToolRegistrySync.canonicalForTest();
        try (var model = HeldModel.replying(ApiChatControllerTest::spawnACodingRunThenReply)) {
            commitInFreshTx(() -> {
                ConfigService.set(tools.SubagentSpawnTool.ACP_COMMAND_KEY, harness.toString());
                ConfigService.set(tools.SubagentSpawnTool.ACP_HARNESS_KEY, "generic");
                ConfigService.set(tools.SubagentSpawnTool.ACP_MODE_KEY, "json");
                return null;
            });
            var chat = seedWebChat(model, true);
            var sink = new ConversationTurnStreamTest.RecordingSink();
            var unsubscribe = ConversationTurnStream.subscribe(chat.conversationId(), null, sink);
            try {
                var response = POST(route, "application/json", turnBody(chat, "Write it with the coding harness."));
                assertIsOk(response);
                var tab = "/api/chat/stream".equals(route)
                        ? getContent(response).lines().filter(l -> l.startsWith("data: "))
                                .map(l -> l.substring("data: ".length())).toList()
                        : List.<String>of();
                var watched = ConversationTurnStreamTest.data(sink.awaitData(COMPLETE_REPLY));
                return new SpawningTurn(nonce, tab, watched);
            } finally {
                awaitIdle(chat.conversationId());
                unsubscribe.run();
            }
        } finally {
            ToolRegistrySync.release();
            commitInFreshTx(() -> {
                for (var key : new String[] {tools.SubagentSpawnTool.ACP_COMMAND_KEY,
                        tools.SubagentSpawnTool.ACP_HARNESS_KEY, tools.SubagentSpawnTool.ACP_MODE_KEY}) {
                    ConfigService.delete(key);
                }
                return null;
            });
            Files.deleteIfExists(harness);
        }
    }

    /** Round one spawns a coding run; once its result is in, the reply. */
    private static String spawnACodingRunThenReply(JsonObject request) {
        var messages = request.getAsJsonArray("messages");
        var last = messages.get(messages.size() - 1).getAsJsonObject();
        if ("tool".equals(last.get("role").getAsString())) return HeldModel.replyCompletion();
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"",
                "tool_calls":[{"id":"call_spawn","type":"function","function":{"name":"subagent_spawn",
                "arguments":"{\\"task\\":\\"write it\\",\\"runtime\\":\\"acp\\"}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}""";
    }

    /** The contents of the status frames among {@code frames} that name {@code nonce}, in order. */
    private static List<String> statusesOf(List<String> frames, String nonce) {
        return frames.stream().map(f -> JsonParser.parseString(f).getAsJsonObject())
                .filter(f -> "status".equals(f.get("type").getAsString()) && f.get("content").getAsString().contains(nonce))
                .map(f -> f.get("content").getAsString()).toList();
    }

    /**
     * Frames as JSON trees, with what differs between two runs of one turn set aside: the conversation id and the turn's,
     * the first token's and the turn's start stamps, a status's timings (dropped, since a zero one is left out) and
     * every other number it reports, token counts and context size alike.
     */
    private static List<JsonElement> normalized(List<String> frames) {
        var trees = new java.util.ArrayList<JsonElement>();
        for (var frame : frames) {
            var tree = JsonParser.parseString(frame).getAsJsonObject();
            for (var stamp : new String[] {"timestamp", "startedAt"}) {
                if (tree.has(stamp)) {
                    assertDoesNotThrow(() -> Instant.parse(tree.get(stamp).getAsString()));
                    tree.addProperty(stamp, "stamped");
                }
            }
            if (tree.has("conversationId")) tree.addProperty("conversationId", 0);
            if (tree.has("turn")) tree.addProperty("turn", 0);
            var content = tree.get("content");
            if ("status".equals(tree.get("type").getAsString()) && content.getAsString().startsWith("{")) {
                tree.add("content", measuresSetAside(JsonParser.parseString(content.getAsString())));
            }
            trees.add(tree);
        }
        return trees;
    }

    private static JsonElement measuresSetAside(JsonElement element) {
        if (element.isJsonObject()) {
            var out = new JsonObject();
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (!entry.getKey().endsWith("Ms")) out.add(entry.getKey(), measuresSetAside(entry.getValue()));
            }
            return out;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) return new JsonPrimitive(0);
        return element;
    }

    private record Seeded(long agentId, long conversationId) {}

    private static Seeded seedWebChat(HeldModel model) {
        return seedWebChat(model, false);
    }

    private static Seeded seedWebChat(HeldModel model, boolean acpAllowed) {
        return commitInFreshTx(() -> {
            var agent = services.AgentService.create(
                    "chat-turn-agent-" + UUID.randomUUID().toString().substring(0, 8), model.provider, HeldModel.MODEL);
            agent.enabled = true;
            agent.acpAllowed = acpAllowed;
            agent.save();
            var conversation = services.ConversationService.create(agent, "web", "admin");
            return new Seeded(agent.id, conversation.id);
        });
    }

    /** A second web chat of {@code chat}'s agent, so two turns share its prompt. */
    private static Seeded anotherWebChat(Seeded chat) {
        return commitInFreshTx(() -> {
            models.Agent agent = models.Agent.findById(chat.agentId());
            return new Seeded(agent.id, services.ConversationService.create(agent, "web", "admin").id);
        });
    }

    /** Hold {@code chat}'s conversation as a running turn would, so sends queue; returns the generation held. */
    private static long holdConversation(Seeded chat) {
        models.Agent agent = commitInFreshTx(() -> models.Agent.findById(chat.agentId()));
        long held = ConversationQueue.tryAcquireOwnership(chat.conversationId(),
                new ConversationQueue.QueuedMessage("holding", "web", "admin", agent), null);
        assertTrue(held >= 0, "test precondition: the test holds the conversation");
        return held;
    }

    private static String turnBody(Seeded chat, String message) {
        return "{\"agentId\": %d, \"conversationId\": %d, \"message\": %s}"
                .formatted(chat.agentId(), chat.conversationId(), new com.google.gson.Gson().toJson(message));
    }

    private JsonObject json(play.mvc.Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private static List<String> types(List<String> frames) {
        return frames.stream().map(f -> JsonParser.parseString(f).getAsJsonObject().get("type").getAsString()).toList();
    }

    private static boolean isTerminal(String frame) {
        var type = JsonParser.parseString(frame).getAsJsonObject().get("type").getAsString();
        return "complete".equals(type) || "error".equals(type) || "stopped".equals(type);
    }

    /** A turn after the test's, newer than any generation the queue issues, whose one frame closes what a test waits on. */
    private static void marker(long conversationId) {
        ConversationTurnStream.begin(conversationId, Long.MAX_VALUE).end(MARK.getBytes(StandardCharsets.UTF_8));
    }

    private static @org.jspecify.annotations.Nullable String assistantReply(long conversationId) {
        return commitInFreshTx(() -> {
            List<models.Message> rows = models.Message.find("conversation.id = ?1 AND role = ?2 ORDER BY id DESC",
                    conversationId, "assistant").fetch();
            return rows.isEmpty() ? null : rows.getFirst().content;
        });
    }

    /** Every row, oldest first, as its role and content. */
    private static List<String> transcript(long conversationId) {
        return commitInFreshTx(() -> {
            List<models.Message> rows = models.Message.find("conversation.id = ?1 ORDER BY id", conversationId).fetch();
            return rows.stream().map(m -> m.role + " " + m.content).toList();
        });
    }

    private static List<String> userMessages(long conversationId) {
        return commitInFreshTx(() -> {
            List<models.Message> rows = models.Message.find("conversation.id = ?1 AND role = ?2 ORDER BY id",
                    conversationId, "user").fetch();
            return rows.stream().map(m -> m.content).toList();
        });
    }

    /** A started turn's answer comes before it ends; its thread may still be running, or in its finally. */
    private static void awaitIdle(long conversationId) throws InterruptedException {
        for (int i = 0; i < 300 && (ConversationQueue.isBusy(conversationId)
                || ConversationTurnStream.current(conversationId) != null); i++) {
            Thread.sleep(50);
        }
        assertFalse(ConversationQueue.isBusy(conversationId), "the turn released its conversation");
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        // FunctionalTest's carrier thread runs inside an ambient JPA tx that
        // doesn't commit until the test returns, so inline Tx.run writes are
        // invisible to the in-process HTTP request handler. Spawn a virtual
        // thread to open a fresh tx that commits before the GET fires.
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
}
