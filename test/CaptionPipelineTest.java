import agents.ImageRetryStrategy;
import agents.VisionAudioAssembler;
import agents.VisionAudioAssembler.ImageBearer;
import llm.LlmTypes.ChatMessage;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JCLAW-215 coverage for the image-caption fallback — the vision analogue of
 * {@code TranscriptionPipelineTest}. Two layers:
 *
 * <ul>
 *   <li>{@code userMessageFor} capability routing: a vision-capable model gets
 *   an {@code image_url} part, a non-vision model gets the cached caption (or a
 *   "description unavailable" fallback) inside the text part.</li>
 *   <li>{@code applyCaptionsForCapability} orchestration: compute a missing
 *   caption via the configured backend (a mocked OpenAI-compat transport),
 *   persist it to {@link MessageAttachment#caption}, and rewrite the user
 *   message as text-with-caption.</li>
 * </ul>
 */
class CaptionPipelineTest extends UnitTest {

    private Agent agent;
    private Conversation conversation;
    private Message message;
    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        agent = new Agent();
        agent.name = "caption-agent-" + UUID.randomUUID().toString().substring(0, 6);
        agent.modelProvider = "openrouter";
        agent.modelId = "anthropic/claude-sonnet-4-5";
        agent.save();

        conversation = new Conversation();
        conversation.agent = agent;
        conversation.channelType = "web";
        conversation.peerId = "test-peer";
        conversation.save();

        message = new Message();
        message.conversation = conversation;
        message.role = MessageRole.USER.value;
        message.content = "what's in this?";
        message.save();

        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        // Leave no provider selection bleeding into other tests in the lane.
        ConfigService.set("caption.cloud.provider", "");
    }

    // ── userMessageFor capability routing ────────────────────────────

    @Test
    void supportsVisionTrueEmitsImageUrlPart() throws Exception {
        persistImageAttachment("shot.png", "image/png", new byte[]{1, 2, 3, 4}, null);
        var fresh = Message.<Message>findById(message.id);

        var parts = (List<?>) VisionAudioAssembler.userMessageFor(fresh, true, true).content();
        assertNotNull(firstPartOfType(parts, "image_url"),
                "supportsVision=true → image_url part: " + parts);
    }

    @Test
    void supportsVisionFalseEmitsCaptionInTextPart() throws Exception {
        persistImageAttachment("shot.png", "image/png", new byte[]{1, 2, 3, 4}, "a dog beside a red bicycle");
        var fresh = Message.<Message>findById(message.id);

        var parts = (List<?>) VisionAudioAssembler.userMessageFor(fresh, true, false).content();
        assertNull(firstPartOfType(parts, "image_url"),
                "supportsVision=false must drop image_url: " + parts);
        var textPart = firstPartOfType(parts, "text");
        assertNotNull(textPart, "text part must exist when the image is downgraded");
        var text = (String) textPart.get("text");
        assertTrue(text.contains("Image description") && text.contains("a dog beside a red bicycle"),
                "text must carry the caption block: " + text);
        assertTrue(text.contains("cannot open or read the image file"),
                "caption block must signal the image file isn't directly readable (discourage readDocument): " + text);
    }

    @Test
    void supportsVisionFalseFallsBackWhenCaptionMissing() throws Exception {
        // Caption backend failure / unconfigured = NULL caption → fallback note
        // carries the original filename so the LLM has SOME context.
        persistImageAttachment("diagram.png", "image/png", new byte[]{9, 8, 7}, null);
        var fresh = Message.<Message>findById(message.id);

        var parts = (List<?>) VisionAudioAssembler.userMessageFor(fresh, true, false).content();
        var text = (String) firstPartOfType(parts, "text").get("text");
        assertTrue(text.contains("diagram.png"),
                "fallback note must carry the original filename: " + text);
        assertTrue(text.contains("auto-description unavailable"),
                "fallback must signal no caption was produced: " + text);
        assertTrue(text.contains("cannot open or read the image file"),
                "fallback must still signal the image file isn't directly readable: " + text);
        assertNull(firstPartOfType(parts, "image_url"));
    }

    @Test
    void defaultOverloadStillEmitsImageUrl() throws Exception {
        // Back-compat: the no-supportsVision-arg overloads keep the pre-JCLAW-215
        // image_url behaviour so existing callers/tests don't change shape.
        persistImageAttachment("shot.png", "image/png", new byte[]{1, 2}, null);
        var fresh = Message.<Message>findById(message.id);

        assertNotNull(firstPartOfType((List<?>) VisionAudioAssembler.userMessageFor(fresh).content(), "image_url"),
                "single-arg overload must preserve image_url");
        assertNotNull(firstPartOfType((List<?>) VisionAudioAssembler.userMessageFor(fresh, false).content(), "image_url"),
                "two-arg overload must preserve image_url");
    }

    // ── applyCaptionsForCapability orchestration ─────────────────────

    @Test
    void applyCaptionsComputesPersistsAndRewrites() throws Exception {
        var att = persistImageAttachment("photo.jpg", "image/jpeg", new byte[]{1, 2, 3, 4, 5}, null);
        ConfigService.set("provider.openai.baseUrl", server.url("/").toString());
        ConfigService.set("provider.openai.apiKey", "test-key");
        ConfigService.set("caption.cloud.provider", "openai");
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"a scenic mountain lake\"}}]}"))
                .build());

        var fresh = Message.<Message>findById(message.id);
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(VisionAudioAssembler.userMessageFor(fresh, true, true)); // optimistic image_url build
        var bearers = List.of(new ImageBearer(1, message.id, List.of(att.id)));

        var rewritten = VisionAudioAssembler.applyCaptionsForCapability(messages, bearers, false, true);

        // (1) caption computed via the mock and persisted to the row.
        var reloaded = MessageAttachment.<MessageAttachment>findById(att.id);
        assertEquals("a scenic mountain lake", reloaded.caption,
                "the generated caption must be persisted");

        // (2) the user message is rewritten to text-with-caption, no image_url.
        var parts = (List<?>) rewritten.get(1).content();
        assertNull(firstPartOfType(parts, "image_url"),
                "rewrite must drop image_url for a non-vision model: " + parts);
        var text = (String) firstPartOfType(parts, "text").get("text");
        assertTrue(text.contains("Image description") && text.contains("a scenic mountain lake"),
                "rewrite must inject the caption block: " + text);
    }

    @Test
    void applyCaptionsNoOpWhenVisionSupported() throws Exception {
        var att = persistImageAttachment("photo.jpg", "image/jpeg", new byte[]{1, 2, 3}, null);
        ConfigService.set("caption.cloud.provider", "openai"); // configured, but must not be called

        var fresh = Message.<Message>findById(message.id);
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(VisionAudioAssembler.userMessageFor(fresh, true, true));
        var bearers = List.of(new ImageBearer(1, message.id, List.of(att.id)));

        var result = VisionAudioAssembler.applyCaptionsForCapability(messages, bearers, true, true);

        assertSame(messages, result, "supportsVision=true must short-circuit (same list)");
        assertNull(MessageAttachment.<MessageAttachment>findById(att.id).caption,
                "no captioning when the model supports vision");
        assertEquals(0, server.getRequestCount(), "the caption backend must not be called");
    }

    // ── isImageFormatRejection heuristic (JCLAW-216) ─────────────────

    @Test
    void isImageFormatRejectionDetectsKnownProviderShapes() {
        assertTrue(ImageRetryStrategy.isImageFormatRejection(
                new RuntimeException("HTTP 400 from openai: invalid_image_format")));
        assertTrue(ImageRetryStrategy.isImageFormatRejection(
                new RuntimeException("HTTP 400 from openrouter: image format not supported")));
        assertTrue(ImageRetryStrategy.isImageFormatRejection(
                new RuntimeException("HTTP 400 from acme: could not decode image")));
        assertTrue(ImageRetryStrategy.isImageFormatRejection(
                new RuntimeException("HTTP 400 from acme: image too large for this model")));
        assertTrue(ImageRetryStrategy.isImageFormatRejection(
                new RuntimeException("HTTP 422 from x: invalid image_url content part")));
    }

    @Test
    void isImageFormatRejectionIgnoresUnrelated400sAnd5xx() {
        // 400 with no image hints — unrelated client error.
        assertFalse(ImageRetryStrategy.isImageFormatRejection(
                new RuntimeException("HTTP 400 from openai: model not found")));
        // 5xx server error: not a client format rejection.
        assertFalse(ImageRetryStrategy.isImageFormatRejection(
                new RuntimeException("HTTP 500 from openai: internal error")));
        // Null / message-less.
        assertFalse(ImageRetryStrategy.isImageFormatRejection(null));
        assertFalse(ImageRetryStrategy.isImageFormatRejection(new RuntimeException()));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private MessageAttachment persistImageAttachment(String filename, String mimeType,
                                                     byte[] bytes, String caption) throws Exception {
        var uuid = UUID.randomUUID().toString();
        var ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1) : "";
        var relPath = "attachments/" + conversation.id + "/" + uuid + (ext.isEmpty() ? "" : "." + ext);
        var full = AgentService.acquireWorkspacePath(agent.name, relPath);
        Files.createDirectories(full.getParent());
        Files.write(full, bytes);

        var att = new MessageAttachment();
        att.message = message;
        att.uuid = uuid;
        att.originalFilename = filename;
        att.storagePath = agent.name + "/" + relPath;
        att.mimeType = mimeType;
        att.sizeBytes = (long) bytes.length;
        att.kind = MessageAttachment.KIND_IMAGE;
        att.caption = caption;
        att.save();
        return att;
    }

    private static Map<?, ?> firstPartOfType(List<?> parts, String type) {
        return parts.stream()
                .filter(p -> p instanceof Map && type.equals(((Map<?, ?>) p).get("type")))
                .map(p -> (Map<?, ?>) p)
                .findFirst().orElse(null);
    }

    private static Buffer jsonBuf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
