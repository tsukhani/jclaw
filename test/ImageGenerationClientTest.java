import com.google.gson.JsonParser;
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
import services.imagegen.ImageGenerationService;
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
        // ConfigService state persists across methods in this class (its cache
        // isn't wiped by deleteDatabase), so reset the per-provider model
        // overrides to a clean baseline — otherwise one test's model choice
        // leaks into another's default resolution.
        ConfigService.set("imagegen.openai.model", "");
        ConfigService.set("imagegen.bfl.model", "");
        ConfigService.set("imagegen.replicate.model", "");
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
    void openAiIgnoresAnotherProvidersModelKey() {
        // Regression: after switching the image-gen provider (e.g. Replicate → OpenAI),
        // a stale imagegen.replicate.model must NOT leak into OpenAI — that produced an
        // HTTP 400 "model does not exist". OpenAI resolves its own imagegen.openai.model
        // → gpt-image-1 default, ignoring other providers' model keys.
        ConfigService.set("imagegen.replicate.model", "black-forest-labs/flux-kontext-pro");
        var imageBytes = new byte[]{1, 2, 3};
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(imageBytes) + "\"}]}")).build());

        var result = new OpenAiImageGenerationClient(testClient).generate("a red bicycle", null, 1024, 1024);
        assertEquals("openai:gpt-image-1", result.generatedBy(),
                "OpenAI must use its own gpt-image-1 default, not another provider's model key");
    }

    @Test
    void openAiHonorsItsOwnModelKey() {
        // The provider-scoped override still works: imagegen.openai.model wins for OpenAI.
        ConfigService.set("imagegen.openai.model", "gpt-image-1-mini");
        var imageBytes = new byte[]{4, 5, 6};
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(imageBytes) + "\"}]}")).build());

        var result = new OpenAiImageGenerationClient(testClient).generate("a red bicycle", null, 1024, 1024);
        assertEquals("openai:gpt-image-1-mini", result.generatedBy());
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
    void openAiSendsReferenceToImagesEditsForImageToImage() throws Exception {
        // JCLAW-697: a reference image switches OpenAI from JSON /images/generations to the multipart
        // /images/edits endpoint — the reference rides as the image part, and gpt-image models get
        // input_fidelity. (The text-to-image path stays JSON — covered by openAiParsesBase64Image.)
        var imageBytes = new byte[]{7, 7, 7};
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(jsonBuf("{\"data\":[{\"b64_json\":\""
                        + Base64.getEncoder().encodeToString(imageBytes) + "\"}]}")).build());

        var ref = new ImageGenerationService.ReferenceImage(new byte[]{1, 2, 3, 4}, "image/png");
        var result = new OpenAiImageGenerationClient(testClient).generate("in this style", null, 1024, 1024, ref);
        assertArrayEquals(imageBytes, result.bytes());
        assertEquals("openai:gpt-image-1", result.generatedBy());

        // A multipart body proves the edits branch ran (generations is JSON). The reference is the
        // image part; gpt-image-1 carries input_fidelity.
        var body = server.takeRequest().getBody().utf8();
        assertTrue(body.contains("name=\"image\""),
                "reference must be sent as the multipart image part: "
                        + body.substring(0, Math.min(300, body.length())));
        assertTrue(body.contains("name=\"input_fidelity\""), "gpt-image edits must send input_fidelity");
        assertTrue(body.contains("name=\"prompt\""), "prompt must be a form field on the edits request");
    }

    @Test
    void bflSubmitsPollsAndFetchesSignedUrl() {
        ConfigService.set("imagegen.bfl.model", "flux-test");
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
    void bflSendsInputImageForImageToImage() throws Exception {
        // JCLAW-695: with a reference image, the FLUX.2 submit body carries input_image (base64)
        // on the same endpoint + poll path — turning the request into image-to-image / style transfer.
        var imageBytes = new byte[]{9, 8, 7};
        server.enqueue(new MockResponse.Builder().code(200)
                .body(jsonBuf("{\"id\":\"abc\",\"polling_url\":\"" + server.url("/poll") + "\"}")).build());
        server.enqueue(new MockResponse.Builder().code(200)
                .body(jsonBuf("{\"status\":\"Ready\",\"result\":{\"sample\":\"" + server.url("/img") + "\"}}")).build());
        server.enqueue(new MockResponse.Builder().code(200).body(bytesBuf(imageBytes)).build());

        var ref = new ImageGenerationService.ReferenceImage(new byte[]{1, 2, 3, 4}, "image/png");
        var result = new BflImageGenerationClient(testClient).generate("in this style", null, 512, 512, ref);
        assertArrayEquals(imageBytes, result.bytes());

        var submitBody = server.takeRequest().getBody().utf8();
        assertTrue(submitBody.contains("input_image"),
                "BFL submit must carry the reference as input_image: " + submitBody);
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
    void replicateSendsInputImageForImageToImage() throws Exception {
        // JCLAW-696: with a reference image, the prediction input carries input_image as a base64
        // data URI (Kontext image-to-image / style transfer). Only meaningful on an i2i model, but
        // the field must be present and correctly shaped whenever a reference is supplied.
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
        var imageBytes = new byte[]{5, 6, 7, 8};
        server.enqueue(new MockResponse.Builder().code(200)
                .body(jsonBuf("{\"status\":\"succeeded\",\"output\":[\"" + server.url("/img") + "\"],"
                        + "\"urls\":{\"get\":\"" + server.url("/pred") + "\"}}")).build());
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "image/webp").body(bytesBuf(imageBytes)).build());

        var ref = new ImageGenerationService.ReferenceImage(new byte[]{1, 2, 3}, "image/png");
        var result = new ReplicateImageGenerationClient(testClient)
                .generate("in this style", null, 1024, 1024, ref);
        assertArrayEquals(imageBytes, result.bytes());

        var createBody = server.takeRequest().getBody().utf8();
        assertTrue(createBody.contains("\"input_image\""),
                "Replicate create body must carry the reference as input_image: " + createBody);
        assertTrue(createBody.contains("data:image/png;base64,"),
                "reference sent as a base64 data URI: " + createBody);
    }

    @Test
    void replicateMapsDimsToAspectRatio() throws Exception {
        // The tool resolves aspect_ratio -> width/height; Flux on Replicate wants the label back, so the
        // client must derive aspect_ratio (landscape/portrait/square) and send no label when dims are unset.
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
        var client = new ReplicateImageGenerationClient(testClient);

        assertEquals("16:9", aspectSentOnCreate(client, 1536, 1024), "landscape dims -> 16:9");
        assertEquals("9:16", aspectSentOnCreate(client, 1024, 1536), "portrait dims -> 9:16");
        assertEquals("1:1", aspectSentOnCreate(client, 1024, 1024), "square dims -> 1:1");
        assertNull(aspectSentOnCreate(client, null, null), "no dims -> no aspect_ratio (model default)");
    }

    /** Run one Replicate generate() and return the {@code aspect_ratio} it put on the create-prediction
     *  (null if absent). The create-prediction is the first recorded request; the image fetch is the second. */
    private String aspectSentOnCreate(ReplicateImageGenerationClient client, Integer w, Integer h) throws Exception {
        server.enqueue(new MockResponse.Builder().code(200)
                .body(jsonBuf("{\"status\":\"succeeded\",\"output\":[\"" + server.url("/img") + "\"]}")).build());
        server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "image/webp").body(bytesBuf(new byte[]{1})).build());
        client.generate("a cat", null, w, h);
        var createBody = server.takeRequest().getBody().utf8();
        server.takeRequest(); // drain the image fetch so the next call's create is first again
        var input = JsonParser.parseString(createBody).getAsJsonObject().getAsJsonObject("input");
        return input.has("aspect_ratio") ? input.get("aspect_ratio").getAsString() : null;
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
