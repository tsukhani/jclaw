import agents.MessageHydrator;
import agents.VisionAudioAssembler;
import llm.ProviderRegistry;
import models.*;
import org.junit.jupiter.api.*;
import play.test.*;
import services.AgentService;
import services.ConfigService;
import services.transcription.FfmpegProbe;
import services.video.VideoAdapterException;
import services.video.VideoUnderstandingDispatcher;
import services.video.VideoUnderstandingDispatcher.Strategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JCLAW-224: strategy routing + the end-to-end splice into the assembled request. The
 * {@code strategyFor} branches are deterministic (capability flags only); the dispatch +
 * pipeline-splice tests sample a real generated clip (guarded on ffmpeg). The text-summary
 * strategy's caption/summarize HTTP is covered by {@code TextSummaryVideoAdapterTest}, so the
 * dispatch tests here exercise native-video / multi-image (no network).
 */
class VideoUnderstandingDispatcherTest extends UnitTest {

    @BeforeEach
    void setUp() throws Exception {
        Thread.sleep(200); // settle concurrent-lane VT tails before resetting shared DB/config
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        ConfigService.set("provider.test.baseUrl", "http://127.0.0.1:1");
        ConfigService.set("provider.test.apiKey", "sk-test");
        ConfigService.set("provider.test.models",
                // gemini-pro: any video-capable model (supportsVideo from input_modalities) → native
                // video_url. vis: vision-only → frames. txt: text-only → captioned summary.
                "[{\"id\":\"gemini-pro\",\"name\":\"Gemini Pro\",\"contextWindow\":1000000,\"maxTokens\":4096,\"supportsVision\":true,\"supportsVideo\":true},"
                        + "{\"id\":\"vis\",\"name\":\"Vis\",\"contextWindow\":128000,\"maxTokens\":4096,\"supportsVision\":true},"
                        + "{\"id\":\"txt\",\"name\":\"Txt\",\"contextWindow\":8000,\"maxTokens\":4096}]");
        ConfigService.clearCache();
        ProviderRegistry.refresh();
    }

    @AfterEach
    void tearDown() {
        ConfigService.clearCache();
    }

    // --- routing: capability flags pick the strategy ---

    @Test
    void routesToNativeVideoWhenSupportsVideo() {
        // Any video-capable model (here Gemini) → native video_url. No Qwen-family special-casing.
        assertEquals(Strategy.NATIVE_VIDEO, VideoUnderstandingDispatcher.strategyFor(agent("gemini-pro")));
    }

    @Test
    void routesToMultiImageWhenVisionOnly() {
        assertEquals(Strategy.MULTI_IMAGE, VideoUnderstandingDispatcher.strategyFor(agent("vis")));
    }

    @Test
    void routesToTextSummaryWhenTextOnly() {
        assertEquals(Strategy.TEXT_SUMMARY, VideoUnderstandingDispatcher.strategyFor(agent("txt")));
    }

    @Test
    void rejectsNonVideoAttachment() {
        var agent = agent("vis");
        var att = imageAttachment(agent);
        assertThrows(VideoAdapterException.class, () -> VideoUnderstandingDispatcher.dispatch(att, agent));
    }

    // --- dispatch end-to-end (ffmpeg, no network for native-video / multi-image) ---

    @Test
    void dispatchNativeVideoProducesVideoUrlPart() throws Exception {
        // A video-capable model + a clip within the inline cap → the whole video as a video_url part.
        assumeTrue(FfmpegProbe.isAvailable(), "ffmpeg required");
        var agent = agent("gemini-pro");
        var att = videoAttachmentWithFile(agent, "web", 40); // small generated clip, under the cap
        var parts = VideoUnderstandingDispatcher.dispatch(att, agent);
        assertEquals(1, parts.size(), "native-video is a single video_url part");
        assertEquals("video_url", parts.get(0).get("type"));
        @SuppressWarnings("unchecked")
        var url = (Map<String, Object>) parts.get(0).get("video_url");
        assertTrue(String.valueOf(url.get("url")).startsWith("data:video/mp4;base64,"),
                "video_url carries the whole clip as a data URL");
    }

    @Test
    void dispatchOversizeVideoFallsBackToImages() throws Exception {
        // A video-capable model but a clip over the inline cap → frames-as-images (it has vision too).
        assumeTrue(FfmpegProbe.isAvailable(), "ffmpeg required");
        ConfigService.set("video.maxInlineMb", "1"); // 1 MiB cap
        ConfigService.clearCache();
        try {
            var agent = agent("gemini-pro");
            var att = videoAttachmentWithFile(agent, "web", 40);
            att.sizeBytes = 2L * 1024 * 1024; // mark it over the cap (the real file stays small for sampling)
            att.save();
            var parts = VideoUnderstandingDispatcher.dispatch(att, agent);
            assertTrue(parts.size() >= 2, "over-cap clip degrades to leading text + image parts");
            assertEquals("image_url", parts.get(1).get("type"));
        } finally {
            ConfigService.set("video.maxInlineMb", "");
            ConfigService.clearCache();
        }
    }

    @Test
    void videoCapableChatModelBypassesDedicatedModel() throws Exception {
        // Rule 1 precedence: when the chat model is video-capable, IT handles the video — a dedicated
        // model configured in Settings is NOT consulted. Point a live dedicated model at a mock server
        // and assert the gemini-pro turn produces a native video_url part and never hits it.
        assumeTrue(FfmpegProbe.isAvailable(), "ffmpeg required");
        try (var server = new mockwebserver3.MockWebServer()) {
            server.start();
            ConfigService.set("provider.openrouter.baseUrl", server.url("/").toString());
            ConfigService.set("provider.openrouter.apiKey", "sk-test");
            ConfigService.set("video.provider", "openrouter");
            ConfigService.set("video.model", "google/gemini-2.5-flash");
            ConfigService.clearCache();

            var agent = agent("gemini-pro"); // video-capable + vision
            var att = videoAttachmentWithFile(agent, "web", 40);
            var parts = VideoUnderstandingDispatcher.dispatch(att, agent);

            assertEquals("video_url", parts.get(0).get("type"),
                    "video-capable chat model interprets natively, not via the dedicated model");
            assertEquals(0, server.getRequestCount(), "dedicated video model must not be called");
        } finally {
            ConfigService.set("video.provider", "");
            ConfigService.set("video.model", "");
            ConfigService.clearCache();
        }
    }

    @Test
    void dispatchMultiImageProducesImageParts() throws Exception {
        assumeTrue(FfmpegProbe.isAvailable(), "ffmpeg required");
        var agent = agent("vis");
        var att = videoAttachmentWithFile(agent, "web", 40);
        var parts = VideoUnderstandingDispatcher.dispatch(att, agent);
        assertTrue(parts.size() >= 2, "multi-image is a leading text + image parts");
        assertEquals("text", parts.get(0).get("type"));
        assertEquals("image_url", parts.get(1).get("type"));
    }

    // --- full pipeline: buildMessages collects the bearer, applyVideoForCapability splices ---

    @Test
    void pipelineCollectsBearerAndSplicesForWebConversation() throws Exception {
        assertVideoSplicedForChannel("web");
    }

    @Test
    void pipelineSplicesForTelegramConversation() throws Exception {
        assertVideoSplicedForChannel("telegram");
    }

    private void assertVideoSplicedForChannel(String channel) throws Exception {
        assumeTrue(FfmpegProbe.isAvailable(), "ffmpeg required");
        var agent = agent("vis"); // multi-image
        var att = videoAttachmentWithFile(agent, channel, 40);

        var audioBearers = new ArrayList<VisionAudioAssembler.AudioBearer>();
        var imageBearers = new ArrayList<VisionAudioAssembler.ImageBearer>();
        var videoBearers = new ArrayList<VisionAudioAssembler.VideoBearer>();
        var messages = MessageHydrator.buildMessages("sys", att.message.conversation,
                audioBearers, imageBearers, videoBearers);
        assertEquals(1, videoBearers.size(), "video bearer collected for the " + channel + " turn");

        var spliced = VisionAudioAssembler.applyVideoForCapability(messages, videoBearers, agent, false, true);
        var userMsg = spliced.get(videoBearers.get(0).chatMessageIndex());
        assertInstanceOf(List.class, userMsg.content(), "content is a parts array");
        @SuppressWarnings("unchecked")
        var parts = (List<Map<String, Object>>) userMsg.content();
        assertTrue(parts.stream().anyMatch(p -> "image_url".equals(p.get("type"))),
                "multi-image image_url parts spliced for " + channel + ": " + parts);
    }

    @Test
    void dedicatedTextInterpretationSplicesAsPlainStringForTextOnlyChatModel() throws Exception {
        // Regression: a text-only chat model (kimi via ollama-cloud) silently ignored an all-text
        // content-PARTS array, so the dedicated model's interpretation never reached it ("I don't see
        // a video"). The splice must keep a pure-text interpretation as a plain content STRING.
        assumeTrue(FfmpegProbe.isAvailable(), "ffmpeg required");
        try (var server = new mockwebserver3.MockWebServer()) {
            server.start();
            ConfigService.set("provider.openrouter.baseUrl", server.url("/").toString());
            ConfigService.set("provider.openrouter.apiKey", "sk-test");
            ConfigService.set("video.provider", "openrouter");
            ConfigService.set("video.model", "google/gemini-2.5-flash");
            ConfigService.clearCache();
            server.enqueue(new mockwebserver3.MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(new okio.Buffer().writeUtf8(
                            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"A waterfall cascades into a green pool.\"}}]}"))
                    .build());

            var agent = agent("txt"); // text-only chat model → dedicated video model path
            var att = videoAttachmentWithFile(agent, "web", 40);
            var videoBearers = new ArrayList<VisionAudioAssembler.VideoBearer>();
            var messages = MessageHydrator.buildMessages("sys", att.message.conversation,
                    new ArrayList<>(), new ArrayList<>(), videoBearers);

            var spliced = VisionAudioAssembler.applyVideoForCapability(messages, videoBearers, agent, false, false);
            var userMsg = spliced.get(videoBearers.get(0).chatMessageIndex());
            assertInstanceOf(String.class, userMsg.content(),
                    "a pure-text interpretation must splice as a plain string, not a parts array");
            var content = (String) userMsg.content();
            assertTrue(content.contains("Video interpretation"), content);
            assertTrue(content.contains("waterfall cascades"), content);
        } finally {
            ConfigService.set("video.provider", "");
            ConfigService.set("video.model", "");
            ConfigService.clearCache();
        }
    }

    // --- helpers ---

    private Agent agent(String modelId) {
        var agent = new Agent();
        agent.name = "video-agent-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = "test";
        agent.modelId = modelId;
        agent.save();
        return agent;
    }

    private Conversation newConversation(Agent agent, String channel) {
        var conv = new Conversation();
        conv.agent = agent;
        conv.channelType = channel;
        conv.peerId = "tester";
        conv.save();
        return conv;
    }

    private Message newMessage(Conversation conv) {
        var msg = new Message();
        msg.conversation = conv;
        msg.role = MessageRole.USER.value;
        msg.content = "what happens in this video?";
        msg.save();
        return msg;
    }

    private MessageAttachment imageAttachment(Agent agent) {
        var conv = newConversation(agent, "web");
        var att = new MessageAttachment();
        att.message = newMessage(conv);
        att.uuid = UUID.randomUUID().toString();
        att.originalFilename = "photo.png";
        att.storagePath = agent.name + "/attachments/" + conv.id + "/" + att.uuid + ".png";
        att.mimeType = "image/png";
        att.sizeBytes = 1L;
        att.kind = MessageAttachment.KIND_IMAGE;
        att.save();
        return att;
    }

    private MessageAttachment videoAttachmentWithFile(Agent agent, String channel, int seconds) throws Exception {
        var conv = newConversation(agent, channel);
        var uuid = UUID.randomUUID().toString();
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

    private void generateTestVideo(Path out, int seconds) throws Exception {
        var pb = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
                "-i", "testsrc=duration=" + seconds + ":size=128x128:rate=5", "-pix_fmt", "yuv420p", out.toString());
        pb.redirectErrorStream(true);
        var proc = pb.start();
        try (var s = proc.getInputStream()) { s.readAllBytes(); }
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "ffmpeg testsrc generation finished");
        assertEquals(0, proc.exitValue(), "ffmpeg testsrc generation succeeded");
    }
}
