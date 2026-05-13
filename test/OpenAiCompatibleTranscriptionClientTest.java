import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.transcription.OpenAiTranscriptionClient;
import services.transcription.OpenRouterTranscriptionClient;
import services.transcription.TranscriptionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JCLAW-162 coverage for the cloud transcription clients. Both
 * {@link OpenAiTranscriptionClient} and {@link OpenRouterTranscriptionClient}
 * delegate to the shared OpenAI-compat base — same wire shape, different
 * provider config namespace — so the tests exercise both subclasses
 * against a mocked HTTP transport (mockwebserver3) to confirm the
 * Bearer header, multipart shape, and JSON parse all hold.
 */
class OpenAiCompatibleTranscriptionClientTest extends UnitTest {

    private MockWebServer server;
    private OkHttpClient testClient;
    private models.MessageAttachment attachment;
    private Path tempAudio;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // Plain default OkHttpClient — sidesteps the shared LLM dispatcher
        // so tests don't pollute the production connection pool.
        testClient = new OkHttpClient.Builder().build();

        // Drop a real file under the test workspace root so the streaming
        // RequestBody.create(File) path actually opens an InputStream.
        var root = AgentService.workspaceRoot();
        Files.createDirectories(root.resolve("test-agent/attachments/1"));
        tempAudio = root.resolve("test-agent/attachments/1/clip.wav");
        Files.write(tempAudio, "FAKE-WAV-BYTES-FOR-TEST".getBytes());

        attachment = new models.MessageAttachment();
        attachment.uuid = "test-uuid";
        attachment.originalFilename = "clip.wav";
        attachment.storagePath = "test-agent/attachments/1/clip.wav";
        attachment.mimeType = "audio/wav";
        attachment.sizeBytes = Files.size(tempAudio);
        attachment.kind = models.MessageAttachment.KIND_AUDIO;
        attachment.createdAt = Instant.now();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        Files.deleteIfExists(tempAudio);
        ConfigService.delete("provider.openai.baseUrl");
        ConfigService.delete("provider.openai.apiKey");
        ConfigService.delete("provider.openrouter.baseUrl");
        ConfigService.delete("provider.openrouter.apiKey");
        ConfigService.delete("transcription.model");
    }

    @Test
    void openAi_happyPath_returnsTranscriptText() throws Exception {
        ConfigService.set("provider.openai.baseUrl", server.url("/v1").toString());
        ConfigService.set("provider.openai.apiKey", "sk-openai-test");
        server.enqueue(jsonResponse(200, "{\"text\":\"hello world\"}"));

        var transcript = new OpenAiTranscriptionClient(testClient).transcribe(attachment);

        assertEquals("hello world", transcript, "transcript text from JSON body");
        var recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertTrue(recorded.getTarget().endsWith("/audio/transcriptions"),
                "endpoint path: " + recorded.getTarget());
        assertEquals("Bearer sk-openai-test", recorded.getHeaders().get("Authorization"),
                "Bearer header set");
        var body = recorded.getBody().utf8();
        assertTrue(body.contains("name=\"model\""), "multipart includes model field: " + body);
        assertTrue(body.contains("whisper-1"),
                "default model id appears in body: " + body);
        assertTrue(body.contains("name=\"file\""), "multipart includes file field: " + body);
        assertTrue(body.contains("FAKE-WAV-BYTES-FOR-TEST"),
                "file bytes streamed into the body");
    }

    @Test
    void openAi_httpError_throwsWithStatusInMessage() {
        ConfigService.set("provider.openai.baseUrl", server.url("/v1").toString());
        ConfigService.set("provider.openai.apiKey", "sk-openai-test");
        server.enqueue(jsonResponse(500, "{\"error\":{\"message\":\"upstream lit on fire\"}}"));

        var client = new OpenAiTranscriptionClient(testClient);
        var ex = assertThrows(TranscriptionException.class,
                () -> client.transcribe(attachment));
        assertTrue(ex.getMessage().contains("HTTP 500"),
                "exception message includes status: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("openai"),
                "exception message names the provider: " + ex.getMessage());
    }

    @Test
    void openRouter_happyPath_usesRouterConfigNamespace() throws Exception {
        ConfigService.set("provider.openrouter.baseUrl", server.url("/v1").toString());
        ConfigService.set("provider.openrouter.apiKey", "sk-or-test");
        ConfigService.set("transcription.model", "whisper-large-v3");
        server.enqueue(jsonResponse(200, "{\"text\":\"routed transcript\"}"));

        var transcript = new OpenRouterTranscriptionClient(testClient).transcribe(attachment);

        assertEquals("routed transcript", transcript);
        var recorded = server.takeRequest();
        assertEquals("Bearer sk-or-test", recorded.getHeaders().get("Authorization"),
                "OpenRouter uses its own provider key");
        assertTrue(recorded.getBody().utf8().contains("whisper-large-v3"),
                "transcription.model override is respected");
    }

    @Test
    void openRouter_missingApiKey_throwsBeforeNetworkCall() {
        ConfigService.set("provider.openrouter.baseUrl", server.url("/v1").toString());
        ConfigService.delete("provider.openrouter.apiKey");

        var client = new OpenRouterTranscriptionClient(testClient);
        var ex = assertThrows(TranscriptionException.class,
                () -> client.transcribe(attachment));
        assertTrue(ex.getMessage().contains("apiKey"),
                "exception names the missing key: " + ex.getMessage());
        // Server must not have received a request — config validation runs
        // before any HTTP call so misconfigurations don't leak file bytes.
        assertEquals(0, server.getRequestCount(),
                "no request fires when apiKey is unset");
    }

    private static MockResponse jsonResponse(int code, String body) {
        return new MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    @SuppressWarnings("unused")
    private static final Class<?> _UNUSED_IO_HOLDER = IOException.class;
}
