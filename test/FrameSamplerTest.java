import org.junit.jupiter.api.*;
import play.test.*;
import models.*;
import services.AgentService;
import services.ConfigService;
import services.transcription.FfmpegProbe;
import services.video.FrameSampler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JCLAW-219: duration-aware frame-count math (deterministic, default + clamped
 * config) plus a guarded end-to-end sample of a real clip generated with ffmpeg's
 * lavfi {@code testsrc} (skipped when ffmpeg isn't on PATH).
 */
class FrameSamplerTest extends UnitTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @AfterEach
    void tearDown() {
        ConfigService.clearCache();
    }

    // --- frameCountFor: duration-aware count with default config (10 s/frame, ceiling 8, floor 2) ---

    @Test
    void frameCountIsDurationAwareWithDefaults() {
        assertEquals(2, FrameSampler.frameCountFor(0),   "non-positive duration → floor");
        assertEquals(2, FrameSampler.frameCountFor(12),  "round(1.2)=1 → floored to 2");
        assertEquals(3, FrameSampler.frameCountFor(25),  "round(2.5)=3");
        assertEquals(5, FrameSampler.frameCountFor(45),  "round(4.5)=5");
        assertEquals(8, FrameSampler.frameCountFor(80),  "round(8.0)=8 → at ceiling");
        assertEquals(8, FrameSampler.frameCountFor(600), "round(60)=60 → capped at ceiling 8");
    }

    // --- config clamps: the operator can't drive N outside [floor, hard-max] ---

    @Test
    void sampleFramesCeilingIsClampedToBounds() {
        setConfig("video.sampleFrames", "100");                                  // above SAMPLE_FRAMES_MAX
        assertEquals(32, FrameSampler.frameCountFor(100_000), "ceiling clamps down to 32");

        setConfig("video.sampleFrames", "0");                                    // below SAMPLE_FRAMES_MIN
        assertEquals(2, FrameSampler.frameCountFor(100_000), "ceiling floored at 2");
    }

    @Test
    void secondsPerFrameIsClampedToBounds() {
        setConfig("video.secondsPerFrame", "0");                                 // below min → clamps to 1
        assertEquals(5, FrameSampler.frameCountFor(5),   "1 s/frame: 5 s → 5 frames (≤ ceiling 8)");

        setConfig("video.secondsPerFrame", "1000");                              // above max → clamps to 60
        assertEquals(2, FrameSampler.frameCountFor(120), "60 s/frame: 120 s → round(2)=2");
    }

    // --- end-to-end: real ffmpeg/ffprobe over a generated clip (guarded on ffmpeg present) ---

    @Test
    void samplesRealClipIntoValidJpegFrames() throws Exception {
        assumeTrue(FfmpegProbe.isAvailable(), "ffmpeg/ffprobe not on PATH — skipping integration sample");

        var agent = new Agent();
        agent.name = "video-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "qwen/qwen2.5-vl-72b-instruct";
        agent.save();

        var conversation = new Conversation();
        conversation.agent = agent;
        conversation.channelType = "web";
        conversation.peerId = "tester";
        conversation.save();

        var message = new Message();
        message.conversation = conversation;
        message.role = MessageRole.USER.value;
        message.content = "what happens in this video?";
        message.save();

        // Generate a 40 s test pattern directly under the agent workspace so the
        // attachment's storagePath resolves exactly as the send path would write it.
        var uuid = UUID.randomUUID().toString();
        var dir = AgentService.acquireWorkspacePath(agent.name, "attachments/" + conversation.id);
        Files.createDirectories(dir);
        var videoPath = dir.resolve(uuid + ".mp4");
        generateTestVideo(videoPath, 40);

        var att = new MessageAttachment();
        att.message = message;
        att.uuid = uuid;
        att.originalFilename = "clip.mp4";
        att.storagePath = agent.name + "/attachments/" + conversation.id + "/" + uuid + ".mp4";
        att.mimeType = "video/mp4";
        att.sizeBytes = Files.size(videoPath);
        att.kind = MessageAttachment.KIND_VIDEO;
        att.save();

        try {
            var result = FrameSampler.sample(att);
            assertEquals(40.0, result.durationSeconds(), 1.0, "ffprobe reads ~40 s duration");
            var frames = result.frames();

            // 40 s at the default 10 s/frame → round(4.0) = 4 frames.
            assertEquals(4, frames.size(), "duration-aware count for a 40 s clip at default density");
            double prev = -1;
            for (var f : frames) {
                assertNotNull(f.jpeg());
                assertTrue(f.jpeg().length > 0, "frame has bytes");
                // JPEG SOI magic: FF D8 FF.
                assertEquals((byte) 0xFF, f.jpeg()[0], "JPEG byte 0");
                assertEquals((byte) 0xD8, f.jpeg()[1], "JPEG byte 1");
                assertEquals((byte) 0xFF, f.jpeg()[2], "JPEG byte 2");
                assertTrue(f.timestampSeconds() > prev, "timestamps strictly ascending");
                assertTrue(f.timestampSeconds() > 0 && f.timestampSeconds() < 40, "timestamp within clip");
                prev = f.timestampSeconds();
            }
        } finally {
            Files.deleteIfExists(videoPath);
        }
    }

    private void generateTestVideo(Path out, int seconds) throws Exception {
        var pb = new ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi",
                "-i", "testsrc=duration=" + seconds + ":size=128x128:rate=5",
                "-pix_fmt", "yuv420p",
                out.toString());
        pb.redirectErrorStream(true);
        var proc = pb.start();
        try (var s = proc.getInputStream()) { s.readAllBytes(); }
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "ffmpeg testsrc generation finished");
        assertEquals(0, proc.exitValue(), "ffmpeg testsrc generation succeeded");
    }

    private void setConfig(String key, String value) {
        ConfigService.set(key, value);
        ConfigService.clearCache();
    }
}
