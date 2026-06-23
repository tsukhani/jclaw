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
import services.imagegen.BflImageGenerationClient;
import services.imagegen.ImageGenerationException;
import services.imagegen.ImageGenerationRouter;
import services.imagegen.OpenAiImageGenerationClient;

import java.util.Base64;

/**
 * JCLAW-225 coverage for the cloud image-generation clients against a mocked transport.
 *
 * <ul>
 *   <li>OpenAI ({@code /images/generations}): happy path parses {@code data[0].b64_json} → bytes;
 *       an HTTP error surfaces as {@link ImageGenerationException}.</li>
 *   <li>BFL: the async submit → poll → fetch-signed-URL flow round-trips; a submit error throws.</li>
 *   <li>{@link ImageGenerationRouter} selects the client matching {@code imagegen.provider}.</li>
 * </ul>
 */
class ImageGenerationClientTest extends UnitTest {

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
        ConfigService.set("provider.bfl.baseUrl", server.url("/").toString());
        ConfigService.set("provider.bfl.apiKey", "test-key");
        ConfigService.set("imagegen.timeoutSeconds", "5");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void openAiParsesBase64Image() {
        var imageBytes = new byte[]{1, 2, 3, 4, 5};
        var b64 = Base64.getEncoder().encodeToString(imageBytes);
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")).build());

        var result = new OpenAiImageGenerationClient(testClient).generate("a red bicycle", null, 1024, 1024);
        assertArrayEquals(imageBytes, result.bytes());
        assertEquals("image/png", result.mimeType());
        assertEquals("openai:gpt-image-1", result.generatedBy());
    }

    @Test
    void openAiThrowsOnHttpError() {
        server.enqueue(new MockResponse.Builder().code(500).body(jsonBuf("upstream boom")).build());

        var client = new OpenAiImageGenerationClient(testClient);
        var ex = assertThrows(ImageGenerationException.class,
                () -> client.generate("anything", null, null, null),
                "an HTTP error must surface as an ImageGenerationException");
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
    }

    @Test
    void bflSubmitsPollsAndFetchesSignedUrl() {
        ConfigService.set("imagegen.cloud.model", "flux-test");
        var imageBytes = new byte[]{9, 8, 7, 6};
        // 1) submit → polling_url ; 2) poll → Ready + sample URL ; 3) the signed image bytes.
        server.enqueue(new MockResponse.Builder().code(200)
                .body(jsonBuf("{\"id\":\"abc\",\"polling_url\":\"" + server.url("/poll") + "\"}")).build());
        server.enqueue(new MockResponse.Builder().code(200)
                .body(jsonBuf("{\"status\":\"Ready\",\"result\":{\"sample\":\"" + server.url("/img") + "\"}}")).build());
        server.enqueue(new MockResponse.Builder().code(200).body(bytesBuf(imageBytes)).build());

        var result = new BflImageGenerationClient(testClient).generate("a cat", null, 512, 512);
        assertArrayEquals(imageBytes, result.bytes());
        assertEquals("bfl:flux-test", result.generatedBy());
    }

    @Test
    void bflThrowsOnSubmitError() {
        server.enqueue(new MockResponse.Builder().code(402).body(jsonBuf("insufficient credits")).build());

        var client = new BflImageGenerationClient(testClient);
        var ex = assertThrows(ImageGenerationException.class,
                () -> client.generate("a cat", null, null, null),
                "a BFL submit error must surface as an ImageGenerationException");
        assertTrue(ex.getMessage().contains("submit failed"), ex.getMessage());
    }

    @Test
    void routerSelectsClientByProvider() {
        assertTrue(ImageGenerationRouter.configuredService().isEmpty(),
                "no imagegen.provider → empty (off)");

        ConfigService.set("imagegen.provider", "openai");
        assertTrue(ImageGenerationRouter.configuredService().orElseThrow() instanceof OpenAiImageGenerationClient);

        ConfigService.set("imagegen.provider", "bfl");
        assertTrue(ImageGenerationRouter.configuredService().orElseThrow() instanceof BflImageGenerationClient);

        ConfigService.set("imagegen.provider", "nonsense");
        assertTrue(ImageGenerationRouter.configuredService().isEmpty(), "unknown provider → empty");
    }

    private static Buffer jsonBuf(String s) {
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
