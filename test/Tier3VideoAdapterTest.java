import llm.ProviderRegistry;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import models.*;
import okio.Buffer;
import org.junit.jupiter.api.*;
import play.test.*;
import services.AgentService;
import services.ConfigService;
import services.transcription.FfmpegProbe;
import services.video.FrameSampler;
import services.video.Tier3VideoAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JCLAW-222: Tier-3 temporal-summary adapter. Cache reuse + the TIER3_MAX_FRAMES cap are
 * pure/DB unit tests; the happy path samples a real generated clip and mocks the caption +
 * overview HTTP (one MockWebServer serves both, since both are OpenAI-compatible
 * /chat/completions calls).
 */
class Tier3VideoAdapterTest extends UnitTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        Thread.sleep(200);
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
        ConfigService.clearCache();
    }

    // --- cache reuse: a persisted summary short-circuits before any sampling/captioning ---

    @Test
    void reusesPersistedSummary() {
        var att = persistVideoAttachment("Video summary (duration 00:00:10, 1 frames sampled):\n[00:00:05] a cat");
        // No ffmpeg, no caption provider configured: a cache MISS here would throw, so reaching
        // the cached value proves the short-circuit.
        var parts = Tier3VideoAdapter.contentParts(att, null);
        assertEquals(1, parts.size());
        assertEquals("text", parts.get(0).get("type"));
        assertTrue(((String) parts.get(0).get("text")).contains("[00:00:05] a cat"));
    }

    // --- cap binds: Tier-3 effective N is capped below the duration-aware count ---

    @Test
    void capBindsWhenDurationAwareCountExceedsIt() {
        ConfigService.set("video.sampleFrames", "20"); // raise the Tier-1/2 ceiling above the Tier-3 cap
        ConfigService.clearCache();
        // 300 s at default 10 s/frame → 30, ceilinged to 20 (the raised video.sampleFrames).
        assertEquals(20, FrameSampler.frameCountFor(300), "Tier-1/2 duration-aware count");
        // Tier-3 caps it at TIER3_MAX_FRAMES (default 8).
        assertEquals(8, Tier3VideoAdapter.effectiveFrameCount(300), "Tier-3 cap binds");
    }

    // --- happy path: real frames + mocked caption/overview → persisted [hh:mm:ss] timeline ---

    @Test
    void buildsAndPersistsTimelineSummary() throws Exception {
        assumeTrue(FfmpegProbe.isAvailable(), "ffmpeg required for Tier-3 happy path");

        server = new MockWebServer();
        server.start();
        for (int i = 0; i < 16; i++) {
            server.enqueue(new MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(jsonBuf("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                            + "\"message\":{\"role\":\"assistant\",\"content\":\"a frame caption\"}}]}"))
                    .build());
        }
        ConfigService.set("provider.openai.baseUrl", server.url("/").toString());
        ConfigService.set("provider.openai.apiKey", "test-key");
        ConfigService.set("caption.provider", "openai");
        ConfigService.set("caption.model", "gpt-4o-mini");
        ConfigService.clearCache();
        ProviderRegistry.refresh();

        var agent = newAgent("openai", "gpt-4o-mini");
        var att = persistVideoAttachmentWithFile(agent, 40);

        var parts = Tier3VideoAdapter.contentParts(att, agent);
        var text = (String) parts.get(0).get("text");

        // 40 s at default 10 s/frame → 4 frames (≤ TIER3_MAX_FRAMES 8); timestamps 5/15/25/35 s.
        assertTrue(text.contains("4 frames sampled"), "header states frame count: " + text);
        assertTrue(text.contains("[00:00:05] a frame caption"), "timeline line present: " + text);
        assertTrue(text.contains("Overview: a frame caption"), "overview present: " + text);

        var reloaded = (MessageAttachment) MessageAttachment.findById(att.id);
        assertNotNull(reloaded.videoSummary, "summary persisted for reuse");
        assertTrue(reloaded.videoSummary.contains("4 frames sampled"));
    }

    // --- helpers ---

    private MessageAttachment persistVideoAttachment(String summary) {
        var agent = newAgent("openai", "gpt-4o-mini");
        var att = attachmentFor(agent, UUID.randomUUID().toString());
        att.videoSummary = summary;
        att.save();
        return att;
    }

    private MessageAttachment persistVideoAttachmentWithFile(Agent agent, int seconds) throws Exception {
        var uuid = UUID.randomUUID().toString();
        var conv = newConversation(agent);
        var dir = AgentService.acquireWorkspacePath(agent.name, "attachments/" + conv.id);
        Files.createDirectories(dir);
        var videoPath = dir.resolve(uuid + ".mp4");
        generateTestVideo(videoPath, seconds);
        var att = new MessageAttachment();
        att.message = newMessage(conv);
        att.uuid = uuid;
        att.originalFilename = "clip.mp4";
        att.storagePath = agent.name + "/attachments/" + conv.id + "/" + uuid + ".mp4";
        att.mimeType = "video/mp4";
        att.sizeBytes = Files.size(videoPath);
        att.kind = MessageAttachment.KIND_VIDEO;
        att.save();
        return att;
    }

    private MessageAttachment attachmentFor(Agent agent, String uuid) {
        var conv = newConversation(agent);
        var att = new MessageAttachment();
        att.message = newMessage(conv);
        att.uuid = uuid;
        att.originalFilename = "clip.mp4";
        att.storagePath = agent.name + "/attachments/" + conv.id + "/" + uuid + ".mp4";
        att.mimeType = "video/mp4";
        att.sizeBytes = 1L;
        att.kind = MessageAttachment.KIND_VIDEO;
        return att;
    }

    private Agent newAgent(String provider, String model) {
        var agent = new Agent();
        agent.name = "video-agent-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = provider;
        agent.modelId = model;
        agent.save();
        return agent;
    }

    private Conversation newConversation(Agent agent) {
        var conv = new Conversation();
        conv.agent = agent;
        conv.channelType = "web";
        conv.peerId = "tester";
        conv.save();
        return conv;
    }

    private Message newMessage(Conversation conv) {
        var msg = new Message();
        msg.conversation = conv;
        msg.role = MessageRole.USER.value;
        msg.content = "describe this video";
        msg.save();
        return msg;
    }

    private void generateTestVideo(Path out, int seconds) throws Exception {
        var pb = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
                "-i", "testsrc=duration=" + seconds + ":size=128x128:rate=5", "-pix_fmt", "yuv420p", out.toString());
        pb.redirectErrorStream(true);
        var proc = pb.start();
        try (var s = proc.getInputStream()) { s.readAllBytes(); }
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "ffmpeg testsrc generation finished");
        assertEquals(0, proc.exitValue(), "ffmpeg testsrc generation succeeded");
    }

    private static Buffer jsonBuf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
