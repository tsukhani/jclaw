import agents.ConversationSink;
import agents.GeneratedAttachment;
import models.Agent;
import models.Conversation;
import models.MessageAttachment;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import tools.GenerateImageTool;

import java.nio.file.Files;
import java.util.Base64;

/**
 * JCLAW-228 coverage for the {@code generate_image} tool and its assistant-turn inlining.
 *
 * <ul>
 *   <li>The tool calls the configured ImageGenerationService and returns the produced bytes on the
 *       {@link agents.ToolRegistry.ToolResult} (happy path), or a typed text error when generation is
 *       unconfigured (no image, no throw).</li>
 *   <li>{@link ConversationSink#appendAssistantMessage(String, String, GeneratedAttachment)} inlines a
 *       produced image onto the assistant turn as a {@code generated=true} IMAGE attachment — the
 *       integration the tool-call commit path relies on.</li>
 * </ul>
 */
class GenerateImageToolTest extends UnitTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        try {
            server.close();
        } catch (Exception _) { /* ignore */ }
        try {
            var root = AgentService.workspaceRoot();
            if (Files.exists(root)) {
                try (var stream = Files.walk(root)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception _) {} });
                }
            }
        } catch (Exception _) { /* best-effort */ }
    }

    @Test
    void toolGeneratesImageAndCarriesItOnTheResult() {
        var imageBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        ConfigService.set("provider.openai.baseUrl", server.url("/").toString());
        ConfigService.set("provider.openai.apiKey", "test-key");
        ConfigService.set("imagegen.provider", "openai");
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"data\":[{\"b64_json\":\""
                        + Base64.getEncoder().encodeToString(imageBytes) + "\"}]}")).build());

        var result = new GenerateImageTool().executeRich("{\"prompt\":\"a red bicycle\"}", new Agent());

        assertNotNull(result.image(), "a successful generation must carry the image on the result");
        assertArrayEquals(imageBytes, result.image().bytes());
        assertEquals("image/png", result.image().mimeType());
        assertTrue(result.image().metadata().contains("a red bicycle"), result.image().metadata());
    }

    @Test
    void toolReportsWhenNotConfigured() {
        // No imagegen.provider → the router is empty; the tool reports, doesn't throw, no image.
        var result = new GenerateImageTool().executeRich("{\"prompt\":\"anything\"}", new Agent());
        assertNull(result.image());
        assertTrue(result.text().toLowerCase().contains("not configured"), result.text());
    }

    @Test
    void sinkInlinesGeneratedImageOntoAssistantTurn() {
        var agent = new Agent();
        agent.name = "imagegen-tool-test";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.save();
        var conv = new Conversation();
        conv.agent = agent;
        conv.channelType = "web";
        conv.peerId = "local";
        conv.save();

        var bytes = new byte[]{1, 2, 3, 4, 5, 6};
        var meta = "{\"prompt\":\"a cat\",\"generatedBy\":\"openai:gpt-image-1\"}";

        new ConversationSink(conv).appendAssistantMessage(
                null, "[]", new GeneratedAttachment(bytes, "image/png", meta));

        var att = (MessageAttachment) MessageAttachment.find("generated = ?1", true).first();
        assertNotNull(att, "a generated attachment must be persisted onto the assistant turn");
        assertTrue(att.generated);
        assertEquals(MessageAttachment.KIND_IMAGE, att.kind);
        assertEquals(meta, att.generationMetadata);
        assertEquals("assistant", att.message.role, "the image must hang off the assistant message");
        assertEquals(bytes.length, att.sizeBytes);
    }

    private static Buffer jsonBuf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
