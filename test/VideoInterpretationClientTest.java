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
import services.video.FrameSampler;
import services.video.VideoAdapterException;
import services.video.VideoInterpretationClient;

import java.util.List;


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
    void multiImageModeSendsImageUrlPartsNotVideoUrl() throws Exception {
        var body = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"The camera pans left across a waterfall, then settles on a forest path.\"}}]}";
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body(jsonBuf(body)).build());

        var frames = List.of(
                new FrameSampler.Frame(new byte[]{1, 2, 3}, 0.0),
                new FrameSampler.Frame(new byte[]{4, 5, 6}, 6.0));
        var text = new VideoInterpretationClient(
                "openrouter", VideoInterpretationClient.WireMode.MULTI_IMAGE, testClient)
                .interpretFrames(frames, 12.0);
        assertEquals("The camera pans left across a waterfall, then settles on a forest path.", text);

        // MULTI_IMAGE must send image_url frame parts, never a native video_url part.
        var sent = server.takeRequest().getBody().utf8();
        assertTrue(sent.contains("\"image_url\""), "request must use image_url parts: " + sent);
        assertFalse(sent.contains("\"video_url\""), "request must NOT use a video_url part: " + sent);
        int imageParts = sent.split("data:image/jpeg;base64,", -1).length - 1;
        assertEquals(2, imageParts, "one image_url part per sampled frame");
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
