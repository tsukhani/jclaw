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
import services.imagegen.ReplicateImageModelCatalog;

/**
 * Coverage for the Replicate text-to-image model discovery used by the Settings model dropdown — the
 * image-gen analogue of {@code ReplicateVideoModelCatalogTest}. Exercises
 * {@link ReplicateImageModelCatalog#fetch()} (the uncached, client-injectable path) against a mocked
 * {@code GET /v1/collections/text-to-image}: parses owner/name slugs, skips malformed entries, and
 * degrades to an empty list on a missing key or an HTTP error.
 */
class ReplicateImageModelCatalogTest extends UnitTest {

    private MockWebServer server;
    private OkHttpClient testClient;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        server = new MockWebServer();
        server.start();
        testClient = new OkHttpClient.Builder().build();
        ConfigService.set("provider.replicate.baseUrl", server.url("/").toString());
        ConfigService.set("provider.replicate.apiKey", "test-key");
    }

    @AfterEach
    void tearDown() {
        try {
            server.close();
        } catch (Exception _) { /* ignore */ }
        ConfigService.delete("provider.replicate.apiKey");
        ConfigService.delete("provider.replicate.baseUrl");
    }

    @Test
    void fetchParsesCollectionModelsIntoOwnerSlugs() {
        server.enqueue(json("""
                {"name":"Text to image","slug":"text-to-image","models":[
                  {"owner":"black-forest-labs","name":"flux-schnell","description":"Fast Flux text-to-image"},
                  {"owner":"stability-ai","name":"sdxl","description":"SDXL"}
                ]}"""));

        var models = new ReplicateImageModelCatalog(testClient).fetch();

        assertEquals(2, models.size());
        var first = models.getFirst();
        assertEquals("black-forest-labs/flux-schnell", first.slug());
        assertEquals("flux-schnell", first.name());
        assertEquals("Fast Flux text-to-image", first.description());
        assertFalse(first.imageToImage(), "JCLAW-700: discovered collection models are text-to-image");
        assertEquals("stability-ai/sdxl", models.get(1).slug());
    }

    @Test
    void availableModelsEmptyWithoutApiKey() {
        // JCLAW-700: every dropdown model (curated Kontext included) runs on Replicate, so with no
        // API key the list is empty — the key gate returns before any fetch, so the curated Kontext
        // set is not offered either.
        ConfigService.delete("provider.replicate.apiKey");
        assertTrue(ReplicateImageModelCatalog.availableModels().isEmpty(),
                "no API key → no selectable models (incl. curated Kontext)");
    }

    @Test
    void fetchSkipsEntriesMissingOwnerOrName() {
        server.enqueue(json("""
                {"models":[
                  {"owner":"good","name":"model-x"},
                  {"owner":"","name":"blank-owner"},
                  {"name":"no-owner-field"},
                  {"owner":"orphan"}
                ]}"""));

        var models = new ReplicateImageModelCatalog(testClient).fetch();

        assertEquals(1, models.size());
        assertEquals("good/model-x", models.getFirst().slug());
        assertNull(models.getFirst().description(), "absent description stays null");
    }

    @Test
    void fetchReturnsEmptyWhenNoApiKey() {
        ConfigService.delete("provider.replicate.apiKey");
        var models = new ReplicateImageModelCatalog(testClient).fetch();
        assertTrue(models.isEmpty(), "no API key → no discovery, no outbound call");
        assertEquals(0, server.getRequestCount(), "must not call Replicate without a key");
    }

    @Test
    void fetchReturnsEmptyOnHttpError() {
        server.enqueue(new MockResponse.Builder().code(401).body(buf("unauthorized")).build());
        var models = new ReplicateImageModelCatalog(testClient).fetch();
        assertTrue(models.isEmpty(), "an HTTP error degrades to an empty list, not an exception");
    }

    private static MockResponse json(String body) {
        return new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json").body(buf(body)).build();
    }

    private static Buffer buf(String s) {
        var b = new Buffer();
        b.writeUtf8(s);
        return b;
    }
}
