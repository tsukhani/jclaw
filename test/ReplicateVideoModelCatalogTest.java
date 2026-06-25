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
import services.videogen.ReplicateVideoModelCatalog;

/**
 * JCLAW-236 coverage for the Replicate text-to-video model discovery used by the Settings model
 * dropdown. Exercises {@link ReplicateVideoModelCatalog#fetch()} (the uncached, client-injectable
 * path) against a mocked {@code GET /v1/collections/text-to-video}: parses owner/name slugs, skips
 * malformed entries, and degrades to an empty list on a missing key or an HTTP error.
 */
class ReplicateVideoModelCatalogTest extends UnitTest {

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
                {"name":"Text to video","slug":"text-to-video","models":[
                  {"owner":"wan-video","name":"wan-2.2-t2v-fast","description":"Fast WAN 2.2 text-to-video"},
                  {"owner":"lightricks","name":"ltx-video","description":"LTX video"}
                ]}"""));

        var models = new ReplicateVideoModelCatalog(testClient).fetch();

        assertEquals(2, models.size());
        var first = models.getFirst();
        assertEquals("wan-video/wan-2.2-t2v-fast", first.slug());
        assertEquals("wan-2.2-t2v-fast", first.name());
        assertEquals("Fast WAN 2.2 text-to-video", first.description());
        assertEquals("lightricks/ltx-video", models.get(1).slug());
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

        var models = new ReplicateVideoModelCatalog(testClient).fetch();

        assertEquals(1, models.size());
        assertEquals("good/model-x", models.getFirst().slug());
        assertNull(models.getFirst().description(), "absent description stays null");
    }

    @Test
    void fetchReturnsEmptyWhenNoApiKey() {
        ConfigService.delete("provider.replicate.apiKey");
        var models = new ReplicateVideoModelCatalog(testClient).fetch();
        assertTrue(models.isEmpty(), "no API key → no discovery, no outbound call");
        assertEquals(0, server.getRequestCount(), "must not call Replicate without a key");
    }

    @Test
    void fetchReturnsEmptyOnHttpError() {
        server.enqueue(new MockResponse.Builder().code(401).body(buf("unauthorized")).build());
        var models = new ReplicateVideoModelCatalog(testClient).fetch();
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
