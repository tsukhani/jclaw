import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.AsrSidecarClient;
import services.transcription.TranscriptionException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JCLAW-650: the ASR sidecar HTTP protocol — request shape (path-based
 * audio handoff), segment parsing, and error mapping. MockWebServer + the
 * base-URL test ctor, mirroring {@link LocalVideoGenerationClientTest}; the
 * manager/daemon layer is never touched.
 */
class AsrSidecarClientTest extends UnitTest {

    private MockWebServer server;
    private Path audio;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        audio = Files.createTempFile("asr-client-test-", ".wav");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        Files.deleteIfExists(audio);
    }

    private AsrSidecarClient client() {
        return new AsrSidecarClient(
                server.url("/").toString().replaceAll("/$", ""), new OkHttpClient());
    }

    @Test
    void transcribe_parsesSegmentsInOrder() throws Exception {
        server.enqueue(json(200, """
                {"segments": [
                  {"startMs": 0, "endMs": 1200, "text": "hello there"},
                  {"startMs": 1200, "endMs": 2400, "text": "general"}
                ]}"""));

        var segments = client().transcribe(audio, "large", null);

        assertEquals(2, segments.size());
        assertEquals("hello there", segments.get(0).text());
        assertEquals(0, segments.get(0).startMs());
        assertEquals(1200, segments.get(0).endMs());
        assertEquals("general", segments.get(1).text());

        var recorded = server.takeRequest();
        assertEquals("/transcribe", recorded.getUrl().encodedPath());
        var body = recorded.getBody().utf8();
        assertTrue(body.contains(audio.toAbsolutePath().toString()),
                "request carries the audio path: " + body);
        assertTrue(body.contains("large"), "request carries the model id: " + body);
    }

    @Test
    void transcribe_httpErrorMapsToTranscriptionException() {
        server.enqueue(json(500, "{\"error\": \"engine exploded\"}"));

        var client = client();
        var e = assertThrows(TranscriptionException.class,
                () -> client.transcribe(audio, "large", null));
        assertTrue(e.getMessage().contains("ASR sidecar transcribe failed: HTTP 500"),
                "names the sidecar and status: " + e.getMessage());
    }

    @Test
    void transcribe_unparseableBodyMapsToTranscriptionException() {
        server.enqueue(json(200, "not json at all"));

        var client = client();
        var e = assertThrows(TranscriptionException.class,
                () -> client.transcribe(audio, "large", null));
        assertTrue(e.getMessage().contains("unparseable"),
                "names the parse failure: " + e.getMessage());
    }

    private static MockResponse json(int code, String body) {
        var buf = new Buffer();
        buf.writeUtf8(body);
        return new MockResponse.Builder().code(code)
                .addHeader("Content-Type", "application/json").body(buf).build();
    }
}
