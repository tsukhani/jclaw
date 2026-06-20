import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.video.VideoAdapterException;
import services.video.VideoInterpretationClient;


/**
 * Coverage for the dedicated video-interpretation HTTP core ({@code interpret(frames, duration)}):
 * happy path (parse {@code choices[0].message.content}) and failures (HTTP error, missing config).
 * Exercises the OpenAI {@code /chat/completions} multimodal shape against a mocked transport with
 * synthetic frames — no ffmpeg, no on-disk attachment (mirrors OpenAiCompatibleImageCaptionClientTest).
 */
class VideoInterpretationClientTest extends UnitTest {

    private MockWebServer server;
    private OkHttpClient testClient;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        server = new MockWebServer();
        server.start();
        // Plain default client — sidesteps the shared LLM dispatcher pool.
        testClient = new OkHttpClient.Builder().build();
        ConfigService.set("provider.openrouter.baseUrl", server.url("/").toString());
        ConfigService.set("provider.openrouter.apiKey", "test-key");
        ConfigService.set("video.model", "qwen/qwen2.5-vl-72b-instruct");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static final String VIDEO_URL = "data:video/mp4;base64,AAAA";

    @Test
    void parsesInterpretationFromChatCompletionsResponse() throws Exception {
        var body = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"A cyclist rides down a hill, then waves at the camera.\"}}]}";
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body(jsonBuf(body)).build());

        var text = new VideoInterpretationClient("openrouter", testClient).interpretDataUrl(VIDEO_URL);
        assertEquals("A cyclist rides down a hill, then waves at the camera.", text);

        // The request must carry a video_url part (not the old frame-array shape).
        var sent = server.takeRequest().getBody().utf8();
        assertTrue(sent.contains("\"video_url\""), "request must use a video_url content part: " + sent);
        assertTrue(sent.contains(VIDEO_URL), "request must carry the video data URL");
    }

    @Test
    void throwsOnHttpError() {
        server.enqueue(new MockResponse.Builder().code(500).body(jsonBuf("upstream boom")).build());

        var client = new VideoInterpretationClient("openrouter", testClient);
        var ex = assertThrows(VideoAdapterException.class, () -> client.interpretDataUrl(VIDEO_URL),
                "an HTTP error must surface as a VideoAdapterException");
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
    }

    @Test
    void failsFastWithoutModel() {
        ConfigService.set("video.model", "");
        var client = new VideoInterpretationClient("openrouter", testClient);
        var ex = assertThrows(VideoAdapterException.class, () -> client.interpretDataUrl(VIDEO_URL),
                "a blank video.model must fail fast before any HTTP call");
        assertTrue(ex.getMessage().contains("video model"), ex.getMessage());
        assertEquals(0, server.getRequestCount(), "no HTTP call should be made when there's no model");
    }

    private static Buffer jsonBuf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
