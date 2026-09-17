import agents.ConversationSink;
import agents.GeneratedAttachment;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import models.Agent;
import models.Conversation;
import models.MessageAttachment;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
        // Config reads are cached; without this a backend set by one test outlives its row.
        ConfigService.clearCache();
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

        assertEquals(1, result.attachments().size(),
                "a successful generation must carry the image on the result");
        var image = result.attachments().get(0);
        assertArrayEquals(imageBytes, image.bytes());
        assertEquals("image/png", image.mimeType());
        assertTrue(image.metadata().contains("a red bicycle"), image.metadata());
    }

    @Test
    void aspectRatioResolvesToTrueDimensions() {
        // The aspect_ratio enum must map to true-ratio pixels (16:9 = 1536x864, not the old 3:2 1536x1024),
        // recorded in the image metadata the chip and persistence read.
        var imageBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        ConfigService.set("provider.openai.baseUrl", server.url("/").toString());
        ConfigService.set("provider.openai.apiKey", "test-key");
        ConfigService.set("imagegen.provider", "openai");
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"data\":[{\"b64_json\":\""
                        + Base64.getEncoder().encodeToString(imageBytes) + "\"}]}")).build());

        var result = new GenerateImageTool().executeRich(
                "{\"prompt\":\"a kite\",\"aspect_ratio\":\"16:9\"}", new Agent());

        var meta = result.attachments().get(0).metadata();
        assertTrue(meta.contains("\"width\":1536"), meta);
        assertTrue(meta.contains("\"height\":864"), meta);
    }

    @Test
    void referenceLookupSurvivesNoAmbientTransaction() throws Exception {
        // JCLAW-694 regression (caught in UAT): the generate_image tool runs on the dispatcher's
        // virtual threads, which carry NO ambient EntityManager. A bare attachment lookup there
        // throws "No active EntityManager"; resolveReferenceImage must wrap it in Tx.run. Reproduce
        // on a fresh platform thread (no JPA tx) and assert the wrap makes the lookup runnable.
        var bareThrew = new java.util.concurrent.atomic.AtomicBoolean(false);
        var wrappedError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                MessageAttachment.findLatestUploadedImage(1L);
            } catch (Exception _) {
                bareThrew.set(true); // no tx on this thread → the raw finder can't run
            }
            try {
                services.Tx.run(() -> MessageAttachment.findLatestUploadedImage(1L));
            } catch (Throwable e) {
                wrappedError.set(e);
            }
        });
        t.join();
        assertTrue(bareThrew.get(), "a bare finder call on a no-transaction thread must fail (the bug)");
        assertNull(wrappedError.get(),
                () -> "Tx.run must let the finder run without an ambient transaction: " + wrappedError.get());
    }

    @Test
    void toolReportsWhenNotConfigured() {
        // No imagegen.provider → the router is empty; the tool reports, doesn't throw, no image.
        var result = new GenerateImageTool().executeRich("{\"prompt\":\"anything\"}", new Agent());
        assertTrue(result.attachments().isEmpty());
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

        var returned = new ConversationSink(conv).appendAssistantMessage(
                null, "[]", java.util.List.of(new GeneratedAttachment(bytes, "image/png", meta)));
        // JCLAW-228: the sink returns the persisted rows so the runner can ship them on the live SSE frame.
        assertEquals(1, returned.size(), "the sink must return the persisted attachment for the live tool_call frame");
        assertTrue(returned.get(0).generated);

        var att = (MessageAttachment) MessageAttachment.find("generated = ?1", true).first();
        assertNotNull(att, "a generated attachment must be persisted onto the assistant turn");
        assertTrue(att.generated);
        assertEquals(MessageAttachment.KIND_IMAGE, att.kind);
        assertEquals(meta, att.generationMetadata);
        assertEquals("assistant", att.message.role, "the image must hang off the assistant message");
        assertEquals(bytes.length, att.sizeBytes);
    }

    // ==================== JCLAW-1223: a per-call Replicate model ====================

    /** Curated Kontext slugs: always in the catalog once a Replicate key is set, whatever the cache holds. */
    private static final String CATALOGUED = "black-forest-labs/flux-kontext-pro";
    private static final String CONFIGURED = "black-forest-labs/flux-kontext-max";

    /** Paths the mock Replicate API was asked for, so a test can see which model ran, or that none did. */
    private final List<String> replicatePaths = new CopyOnWriteArrayList<>();

    /** Serve Replicate by path. The collection is empty, which the catalog never caches, so nothing
     *  leaks into its process-wide cache; the Kontext models are appended regardless. */
    private void replicateBackend(boolean withKey) {
        ConfigService.set("imagegen.provider", "replicate");
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        if (withKey) ConfigService.set("provider.replicate.apiKey", "test-key");
        ConfigService.set("imagegen.replicate.model", CONFIGURED);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                var path = request.getUrl().encodedPath();
                replicatePaths.add(path);
                if (path.endsWith("/collections/text-to-image")) {
                    return new MockResponse.Builder().code(200).body(jsonBuf("{\"models\":[]}")).build();
                }
                if (path.endsWith("/predictions")) {
                    return new MockResponse.Builder().code(200).body(jsonBuf(
                            "{\"status\":\"succeeded\",\"output\":[\"" + server.url("/img") + "\"],"
                                    + "\"urls\":{\"get\":\"" + server.url("/pred") + "\"}}")).build();
                }
                if (path.equals("/img")) {
                    var png = new Buffer();
                    png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
                    return new MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(png).build();
                }
                return new MockResponse.Builder().code(404).build();
            }
        });
    }

    private List<String> predictionPaths() {
        return replicatePaths.stream().filter(p -> p.endsWith("/predictions")).toList();
    }

    @Test
    void aCataloguedModelRunsForThatCallAndLeavesTheSettingAlone() {
        replicateBackend(true);

        var result = new GenerateImageTool().executeRich(
                "{\"prompt\":\"a lighthouse\",\"model\":\"" + CATALOGUED + "\"}", new Agent());

        assertEquals(1, result.attachments().size(), result.text());
        assertEquals(List.of("/models/" + CATALOGUED + "/predictions"), predictionPaths());
        var meta = result.attachments().get(0).metadata();
        assertTrue(meta.contains("replicate:" + CATALOGUED), meta);
        assertEquals(CONFIGURED, ConfigService.get("imagegen.replicate.model"),
                "a per-call model must never rewrite the instance-wide setting");
    }

    @Test
    void omittingModelUsesTheConfiguredOne() {
        replicateBackend(true);

        var result = new GenerateImageTool().executeRich("{\"prompt\":\"a lighthouse\"}", new Agent());

        assertEquals(1, result.attachments().size(), result.text());
        assertEquals(List.of("/models/" + CONFIGURED + "/predictions"), predictionPaths());
    }

    @Test
    void modelIsRefusedOnAnotherBackendWithoutCallingIt() {
        // The failure d5aaf3e7 fixed: a Replicate slug reaching OpenAI, which 400s on it.
        ConfigService.set("provider.openai.baseUrl", server.url("/").toString());
        ConfigService.set("provider.openai.apiKey", "test-key");
        ConfigService.set("imagegen.provider", "openai");

        var result = new GenerateImageTool().executeRich(
                "{\"prompt\":\"a lighthouse\",\"model\":\"" + CATALOGUED + "\"}", new Agent());

        assertTrue(result.attachments().isEmpty());
        assertTrue(result.text().contains("only be chosen on the Replicate image backend"), result.text());
        assertTrue(result.text().contains("'openai'"), result.text());
        assertEquals(0, server.getRequestCount(), "a refused model must not reach the provider");
    }

    @Test
    void anUncataloguedModelIsRefusedWithTheChoices() {
        replicateBackend(true);

        var result = new GenerateImageTool().executeRich(
                "{\"prompt\":\"a lighthouse\",\"model\":\"someone/not-offered\"}", new Agent());

        assertTrue(result.attachments().isEmpty());
        assertTrue(result.text().contains("'someone/not-offered' is not an available Replicate image model"), result.text());
        assertTrue(result.text().contains(CATALOGUED), "the refusal must list what can be chosen: " + result.text());
        assertTrue(predictionPaths().isEmpty(), "no prediction may run for a refused model: " + replicatePaths);
    }

    @Test
    void modelIsRefusedWhenTheCatalogIsEmpty() {
        // No Replicate key: the catalog is empty, so there is nothing to check the slug against.
        replicateBackend(false);

        var result = new GenerateImageTool().executeRich(
                "{\"prompt\":\"a lighthouse\",\"model\":\"" + CATALOGUED + "\"}", new Agent());

        assertTrue(result.attachments().isEmpty());
        assertTrue(result.text().contains("model list is unavailable"), result.text());
        assertTrue(predictionPaths().isEmpty(), "no prediction may run for a refused model: " + replicatePaths);
    }

    private static Buffer jsonBuf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
