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
import services.imagegen.LocalImageGenerationClient;
import services.imagegen.ImageGenerationException;
import services.imagegen.ImageGenerationRouter;
import services.imagegen.OpenAiImageGenerationClient;
import services.imagegen.ReplicateImageGenerationClient;

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
    void replicateCreatesAndFetchesOutputUrl() {
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
        var imageBytes = new byte[]{5, 6, 7, 8};
        // Prefer:wait create-prediction returns succeeded with an output URL → no poll needed.
        server.enqueue(new MockResponse.Builder().code(200)
                .body(jsonBuf("{\"status\":\"succeeded\",\"output\":[\"" + server.url("/img") + "\"],"
                        + "\"urls\":{\"get\":\"" + server.url("/pred") + "\"}}")).build());
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "image/webp").body(bytesBuf(imageBytes)).build());

        var result = new ReplicateImageGenerationClient(testClient).generate("a cat", null, null, null);
        assertArrayEquals(imageBytes, result.bytes());
        assertEquals("image/webp", result.mimeType(), "Replicate's content type (webp) should be read from the fetch");
        assertTrue(result.generatedBy().startsWith("replicate:"), result.generatedBy());
    }

    @Test
    void fluxLocalReturnsRawImageBytes() {
        var imageBytes = new byte[]{4, 3, 2, 1};
        // The sidecar returns the PNG bytes directly with a Content-Type header — no submit/poll.
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "image/png").body(bytesBuf(imageBytes)).build());

        var result = new LocalImageGenerationClient(mockBase(), testClient).generate("a red bicycle", null, 1024, 1024);
        assertArrayEquals(imageBytes, result.bytes());
        assertEquals("image/png", result.mimeType());
        assertTrue(result.generatedBy().startsWith("flux-local:"), result.generatedBy());
    }

    @Test
    void fluxLocalThrowsNotDownloadedOn409() {
        // 409 from the sidecar means the weights aren't present yet.
        server.enqueue(new MockResponse.Builder().code(409)
                .body(jsonBuf("{\"error\":\"weights not present\"}")).build());

        var client = new LocalImageGenerationClient(mockBase(), testClient);
        var ex = assertThrows(ImageGenerationException.class,
                () -> client.generate("a cat", null, null, null),
                "a 409 must surface as a clear not-downloaded error");
        assertTrue(ex.getMessage().contains("not downloaded"), ex.getMessage());
    }

    @Test
    void fluxLocalThrowsOnServerError() {
        server.enqueue(new MockResponse.Builder().code(500)
                .body(jsonBuf("{\"error\":\"generation failed\"}")).build());

        var client = new LocalImageGenerationClient(mockBase(), testClient);
        var ex = assertThrows(ImageGenerationException.class,
                () -> client.generate("a cat", null, null, null),
                "a 5xx must surface as an ImageGenerationException");
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
    }

    @Test
    void fluxLocalRequiresPrompt() {
        var client = new LocalImageGenerationClient(mockBase(), testClient);
        var ex = assertThrows(ImageGenerationException.class,
                () -> client.generate("  ", null, null, null),
                "a blank prompt must be rejected before any HTTP call");
        assertTrue(ex.getMessage().contains("prompt is required"), ex.getMessage());
    }

    @Test
    void routerSelectsClientByProvider() {
        // ConfigService's static cache outlives Fixtures.deleteDatabase(), so a prior test's
        // ConfigService.set("imagegen.provider", ...) can leak into this "key absent → off"
        // assertion. Clear the one key this test owns so the assertion is hermetic regardless
        // of test ordering across the concurrent unit/functional lanes.
        ConfigService.delete("imagegen.provider");
        assertTrue(ImageGenerationRouter.configuredService().isEmpty(),
                "no imagegen.provider → empty (off)");

        ConfigService.set("imagegen.provider", "openai");
        assertTrue(ImageGenerationRouter.configuredService().orElseThrow() instanceof OpenAiImageGenerationClient);

        ConfigService.set("imagegen.provider", "bfl");
        assertTrue(ImageGenerationRouter.configuredService().orElseThrow() instanceof BflImageGenerationClient);

        ConfigService.set("imagegen.provider", "replicate");
        assertTrue(ImageGenerationRouter.configuredService().orElseThrow() instanceof ReplicateImageGenerationClient);

        ConfigService.set("imagegen.provider", "flux-local");
        assertTrue(ImageGenerationRouter.configuredService().orElseThrow() instanceof LocalImageGenerationClient);

        ConfigService.set("imagegen.provider", "nonsense");
        assertTrue(ImageGenerationRouter.configuredService().isEmpty(), "unknown provider → empty");
    }

    /** MockWebServer base with no trailing slash, mirroring LocalImageSidecarManager.baseUrl(). */
    private String mockBase() {
        var url = server.url("/").toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
