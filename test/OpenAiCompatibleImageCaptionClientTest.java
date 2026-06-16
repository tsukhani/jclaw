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
import services.caption.CaptionException;
import services.caption.OpenAiCompatibleImageCaptionClient;
import services.caption.OpenAiImageCaptionClient;

/**
 * JCLAW-212 coverage for the cloud image-caption clients. Both OpenAI and OpenRouter delegate to the
 * shared OpenAI-compat base (same /chat/completions multimodal shape, different provider config
 * namespace), so this exercises the HTTP core ({@code captionDataUrl}) against a mocked transport —
 * happy path (parse {@code choices[0].message.content}) and one failure (HTTP error → exception).
 */
class OpenAiCompatibleImageCaptionClientTest extends UnitTest {

    private MockWebServer server;
    private OkHttpClient testClient;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        server = new MockWebServer();
        server.start();
        // Plain default client — sidesteps the shared LLM dispatcher pool.
        testClient = new OkHttpClient.Builder().build();
        ConfigService.set("provider.openai.baseUrl", server.url("/").toString());
        ConfigService.set("provider.openai.apiKey", "test-key");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void parsesCaptionFromChatCompletionsResponse() {
        var body = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"a dog standing next to a red bicycle\"}}]}";
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body(jsonBuf(body)).build());

        var caption = new OpenAiImageCaptionClient(testClient)
                .captionDataUrl("data:image/png;base64,iVBORw0KGgo=");
        assertEquals("a dog standing next to a red bicycle", caption);
    }

    @Test
    void throwsCaptionExceptionOnHttpError() {
        server.enqueue(new MockResponse.Builder().code(500).body(jsonBuf("upstream boom")).build());

        var client = new OpenAiImageCaptionClient(testClient);
        var ex = assertThrows(CaptionException.class,
                () -> client.captionDataUrl("data:image/png;base64,AAAA"),
                "an HTTP error must surface as a CaptionException");
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
    }

    @Test
    void noModelConfiguredFailsFastWithoutHttpCall() {
        // ollama-local passes a null default; a blank caption.model means "no model" → CaptionException
        // BEFORE any request, so a misconfigured local backend is a clean no-op, not a doomed POST.
        var client = new OpenAiCompatibleImageCaptionClient("openai", null, testClient);
        var ex = assertThrows(CaptionException.class,
                () -> client.captionDataUrl("data:image/png;base64,AAAA"),
                "a blank model with no default must fail fast");
        assertTrue(ex.getMessage().contains("no model"), ex.getMessage());
        assertEquals(0, server.getRequestCount(), "no HTTP call should be made when there's no model");
    }

    private static Buffer jsonBuf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
