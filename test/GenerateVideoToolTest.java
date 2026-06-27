import agents.ConversationSink;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.MessageAttachment;
import models.VideoGenerationJob;
import models.VideoGenerationJob.State;
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
import services.Tx;
import tools.GenerateVideoTool;

import java.nio.file.Files;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-235 coverage for the {@code generate_video} tool and its placeholder linkage:
 *
 * <ul>
 *   <li>Unconfigured → a typed text error, no job submitted (no throw).</li>
 *   <li>Configured → submits an async job (RUNNING) and carries a {@link ToolRegistry.VideoJobRef} on
 *       the result; the model-visible text is an async confirmation.</li>
 *   <li>{@link ConversationSink#appendVideoPlaceholder} creates a zero-byte VIDEO placeholder linked to
 *       the job on the assistant turn — the integration the tool-call commit path relies on.</li>
 * </ul>
 */
class GenerateVideoToolTest extends UnitTest {

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
        ConfigService.delete("videogen.provider");
        try {
            var root = AgentService.workspaceRoot();
            if (Files.exists(root)) {
                try (var stream = Files.walk(root)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception _) { /* noise only */ } });
                }
            }
        } catch (Exception _) { /* best-effort */ }
    }

    private Agent savedAgent() {
        var agent = new Agent();
        agent.name = "videogen-tool-test";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.save();
        return agent;
    }

    @Test
    void toolReportsWhenNotConfigured() {
        // No videogen.provider → the router is empty; the tool reports, doesn't throw, submits nothing.
        var result = new GenerateVideoTool().executeRich("{\"prompt\":\"a comet\"}", new Agent());
        assertNull(result.videoJob(), "no job is submitted when video generation is unconfigured");
        assertTrue(result.text().toLowerCase().contains("not configured"), result.text());
    }

    @Test
    void toolSubmitsJobAndCarriesRefOnResult() {
        ConfigService.set("videogen.provider", "replicate");
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"id\":\"pred_v\",\"status\":\"starting\"}")).build());

        var agent = savedAgent();
        var result = new GenerateVideoTool().executeRich(
                "{\"prompt\":\"a comet over a city\",\"aspect_ratio\":\"16:9\"}", agent);

        assertNotNull(result.videoJob(), "a submitted job must be carried on the result");
        assertNotNull(result.videoJob().jobId());
        assertTrue(result.text().toLowerCase().contains("background"), result.text());

        VideoGenerationJob job = VideoGenerationJob.findById(result.videoJob().jobId());
        assertNotNull(job, "the tool must have created the job row");
        assertEquals(State.RUNNING, job.state);
        assertEquals("pred_v", job.providerJobId);
    }

    @Test
    void toolSubmitsFromNonRequestThreadWithoutAmbientTransaction() throws Exception {
        // Regression: the real generate_video path runs on the [agent-stream] tool-execution thread,
        // which Play's JPAPlugin never wraps in a JPA transaction. Before the Tx.run wrap, submit()'s
        // job.save() threw "No active EntityManager for name [default], transaction not started?" there.
        // The in-thread test above can't catch it (the test method body has tx management the raw stream
        // thread lacks), so reproduce on a separate platform thread with no ambient EntityManager.
        ConfigService.set("videogen.provider", "replicate");
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"id\":\"pred_off\",\"status\":\"starting\"}")).build());

        var agent = savedAgent();

        var resultRef = new AtomicReference<ToolRegistry.ToolResult>();
        var errorRef = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try {
                resultRef.set(new GenerateVideoTool().executeRich(
                        "{\"prompt\":\"a comet over a city\"}", agent));
            } catch (Throwable t) {
                errorRef.set(t);
            }
        }, "agent-stream-test");
        worker.start();
        worker.join(10_000);

        assertNull(errorRef.get(),
                "tool must open its own transaction off the request thread, not throw: " + errorRef.get());
        var result = resultRef.get();
        assertNotNull(result, "the worker thread must have produced a result");
        assertNotNull(result.videoJob(), "a submitted job must be carried on the result");

        // The worker committed in its own Tx.run; read back in a fresh transaction.
        VideoGenerationJob job = Tx.run(() -> VideoGenerationJob.findById(result.videoJob().jobId()));
        assertNotNull(job, "the tool must have persisted the job row from the non-request thread");
        assertEquals(State.RUNNING, job.state);
        assertEquals("pred_off", job.providerJobId);
    }

    @Test
    void metadataCarriesRequestedAspectFpsAndDuration() {
        // The chat's video chip reads size/aspect/fps/duration off the placeholder's generationMetadata
        // (JCLAW-234); the tool must persist the requested aspect/fps/duration there.
        ConfigService.set("videogen.provider", "replicate");
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"id\":\"pred_meta\",\"status\":\"starting\"}")).build());

        var agent = savedAgent();
        var result = new GenerateVideoTool().executeRich(
                "{\"prompt\":\"a comet\",\"aspect_ratio\":\"9:16\",\"fps\":30,\"duration_seconds\":4}", agent);

        var meta = JsonParser.parseString(result.videoJob().generationMetadata()).getAsJsonObject();
        assertEquals("a comet", meta.get("prompt").getAsString());
        assertEquals("9:16", meta.get("aspectRatio").getAsString());
        assertEquals(30, meta.get("fps").getAsInt());
        assertEquals(4, meta.get("durationSeconds").getAsInt());
    }

    @Test
    void metadataAppliesFpsAndAspectDefaultsWhenOmitted() {
        ConfigService.set("videogen.provider", "replicate");
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"id\":\"pred_def\",\"status\":\"starting\"}")).build());

        var agent = savedAgent();
        var result = new GenerateVideoTool().executeRich("{\"prompt\":\"a comet\"}", agent);

        var meta = JsonParser.parseString(result.videoJob().generationMetadata()).getAsJsonObject();
        assertEquals("16:9", meta.get("aspectRatio").getAsString(), "landscape default when aspect omitted");
        assertEquals(24, meta.get("fps").getAsInt(), "24fps default when fps omitted");
        assertFalse(meta.has("durationSeconds"), "no duration recorded when the caller omits it");
    }

    @Test
    void sinkCreatesVideoPlaceholderOnAssistantTurn() {
        var agent = savedAgent();
        var conv = new Conversation();
        conv.agent = agent;
        conv.channelType = "web";
        conv.peerId = "local";
        conv.save();

        // A pre-submitted job (the tool would have created this); the sink just links the placeholder.
        var job = new VideoGenerationJob();
        job.provider = "replicate";
        job.state = State.RUNNING;
        job.providerJobId = "pred_s";
        job.save();

        var returned = new ConversationSink(conv).appendVideoPlaceholder(
                null, "[]", new ToolRegistry.VideoJobRef(job.id, "{\"prompt\":\"a comet\"}"));

        assertNotNull(returned, "the sink must return the placeholder for the live tool_call frame");
        assertTrue(returned.generated);
        assertEquals(MessageAttachment.KIND_VIDEO, returned.kind);
        assertEquals(job.id, returned.generationJobId);
        assertEquals(0, returned.sizeBytes, "the placeholder carries no bytes until the runner fills it");
        assertEquals("assistant", returned.message.role, "the placeholder must hang off the assistant message");

        var found = MessageAttachment.findByGenerationJobId(job.id);
        assertNotNull(found, "the placeholder must be findable by its job id");
        assertEquals(returned.uuid, found.uuid);
    }

    private static Buffer jsonBuf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
