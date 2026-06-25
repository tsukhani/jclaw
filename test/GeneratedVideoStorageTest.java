import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.VideoGenerationJob;
import models.VideoGenerationJob.State;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.AttachmentService;
import services.ConfigService;
import services.videogen.VideoGenerationJobService;
import services.videogen.VideoGenerationService.VideoGenRequest;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Comparator;

/**
 * JCLAW-234 coverage for generated-video storage and the runner's fill-on-success path. A placeholder is
 * created at submit (zero bytes, kind VIDEO, linked to the job by generationJobId); the runner fills it
 * with the fetched bytes when the job succeeds and stamps {@code resultAttachmentId}. Uses MockWebServer
 * for submit/poll/fetch so the real client, state machine, and storage round-trip end to end.
 */
class GeneratedVideoStorageTest extends UnitTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        server = new MockWebServer();
        server.start();
        ConfigService.set("videogen.provider", "replicate");
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
        // High bound so a concurrent test's maxJobMinutes=0 can't time our fresh jobs out mid-run.
        ConfigService.set("videogen.maxJobMinutes", "120");
    }

    @AfterEach
    void tearDown() {
        server.close();
        ConfigService.delete("videogen.provider");
        // Remove any agent workspace the test planted (mirrors GeneratedImageStorageTest).
        try {
            var root = AgentService.workspaceRoot();
            if (Files.exists(root)) {
                try (var stream = Files.walk(root)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception _) { /* noise only */ } });
                }
            }
        } catch (Exception _) {
            // Best-effort — leftover dirs only add noise, not failures.
        }
    }

    private Message seedMessage(String agentName) {
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
        msg.role = "assistant";
        msg.content = "generating a video";
        msg.createdAt = Instant.now();
        msg.save();
        return msg;
    }

    @Test
    void placeholderIsZeroLengthVideoLinkedToJob() {
        var msg = seedMessage("vid-placeholder-test");
        var job = new VideoGenerationJob();
        job.provider = "replicate";
        job.state = State.RUNNING;
        job.providerJobId = "pred_x";
        job.save();

        var att = AttachmentService.createGeneratedVideoPlaceholder(
                msg.conversation.agent, msg, job.id, "{\"prompt\":\"a kite\"}");

        assertTrue(att.generated, "placeholder must be flagged generated");
        assertEquals(MessageAttachment.KIND_VIDEO, att.kind);
        assertEquals(0, att.sizeBytes, "placeholder carries no bytes yet");
        assertEquals(job.id, att.generationJobId);
        assertEquals("video/mp4", att.mimeType);
        assertTrue(att.originalFilename.endsWith(".mp4"), att.originalFilename);

        var found = MessageAttachment.findByGenerationJobId(job.id);
        assertNotNull(found, "placeholder must be findable by its job id");
        assertEquals(att.uuid, found.uuid);
    }

    @Test
    void fillWritesBytesAndUpdatesSize() {
        var msg = seedMessage("vid-fill-test");
        var job = new VideoGenerationJob();
        job.provider = "replicate";
        job.state = State.RUNNING;
        job.providerJobId = "pred_y";
        job.save();
        var att = AttachmentService.createGeneratedVideoPlaceholder(msg.conversation.agent, msg, job.id, null);

        var bytes = new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p'};
        AttachmentService.fillGeneratedVideo(att, bytes, "video/mp4");

        assertEquals(bytes.length, att.sizeBytes);
        assertArrayEquals(bytes, AttachmentService.readBytes(att), "filled bytes must read back from disk");
    }

    @Test
    void runnerSucceededFetchesAndFillsPlaceholder() {
        var msg = seedMessage("vid-lifecycle-test");

        server.enqueue(json("{\"id\":\"pred_z\",\"status\":\"starting\"}")); // submit
        var job = VideoGenerationJobService.submit(
                msg.conversation.agent.id, msg.conversation.id, new VideoGenRequest("a comet", null, 5, "16:9"));
        assertEquals(State.RUNNING, job.state);

        var att = AttachmentService.createGeneratedVideoPlaceholder(msg.conversation.agent, msg, job.id, null);

        var videoBytes = new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};
        server.enqueue(json("{\"status\":\"succeeded\",\"output\":[\"" + server.url("/video") + "\"]}")); // poll
        server.enqueue(new MockResponse.Builder().code(200).body(bytesBuf(videoBytes)).build());          // fetch

        VideoGenerationJobService.tickOnce();

        VideoGenerationJob reloadedJob = VideoGenerationJob.findById(job.id);
        assertEquals(State.SUCCEEDED, reloadedJob.state);
        assertEquals(att.id, reloadedJob.resultAttachmentId, "succeeded job must point at the filled placeholder");

        MessageAttachment reloadedAtt = MessageAttachment.findById(att.id);
        assertEquals(videoBytes.length, reloadedAtt.sizeBytes);
        assertArrayEquals(videoBytes, AttachmentService.readBytes(reloadedAtt), "fetched bytes must land on disk");
    }

    private static MockResponse json(String body) {
        return new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json").body(strBuf(body)).build();
    }

    private static Buffer strBuf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }

    private static Buffer bytesBuf(byte[] bytes) {
        var b = new Buffer();
        b.write(bytes);
        return b;
    }
}
