import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;

/**
 * Functional HTTP tests for {@code ApiProvidersController.discoverModels}.
 * The endpoint surfaces three distinct error contracts before invoking the
 * remote model-discovery call:
 *
 * <ul>
 *   <li>401 — unauthenticated.</li>
 *   <li>400 — missing or blank {@code provider.{name}.baseUrl}.</li>
 *   <li>400 — missing or blank {@code provider.{name}.apiKey} (only checked
 *       once baseUrl passes its guard).</li>
 * </ul>
 *
 * <p>{@code ControllerApiTest.providersDiscoverModelsRequiresConfig} covers
 * the missing-baseUrl path against an unconfigured provider; this file adds
 * the remaining error contracts and guards the auth boundary directly.
 */
class ApiProvidersControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void teardown() {
        // Defensive cleanup — every test that pokes the config table also
        // cleans up explicitly, but a thrown assertion could leave keys
        // behind that would leak into the next test's view.
        ConfigService.delete("provider.test-provider.baseUrl");
        ConfigService.delete("provider.test-provider.apiKey");
        ConfigService.delete("provider.test-provider.models");
        ConfigService.clearCache();
    }

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    @Test
    void discoverModelsRequiresAuth() {
        var response = POST("/api/providers/openrouter/discover-models",
                "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void discoverModelsReturns400WhenBaseUrlMissing() {
        login();
        var response = POST("/api/providers/test-provider/discover-models",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).toLowerCase().contains("base url"),
                "error message should mention base URL: " + getContent(response));
    }

    @Test
    void discoverModelsReturns400WhenBaseUrlIsBlank() {
        login();
        ConfigService.set("provider.test-provider.baseUrl", "   ");
        var response = POST("/api/providers/test-provider/discover-models",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void discoverModelsReturns400WhenApiKeyMissing() {
        login();
        ConfigService.set("provider.test-provider.baseUrl", "https://example.invalid/v1");
        // No apiKey set → second guard fires.
        var response = POST("/api/providers/test-provider/discover-models",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).toLowerCase().contains("api key"),
                "error message should mention API key: " + getContent(response));
    }

    @Test
    void discoverModelsReturns400WhenApiKeyIsBlank() {
        login();
        ConfigService.set("provider.test-provider.baseUrl", "https://example.invalid/v1");
        ConfigService.set("provider.test-provider.apiKey", "");
        var response = POST("/api/providers/test-provider/discover-models",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    // --- reachable ---

    @Test
    void reachableRequiresAuth() {
        assertEquals(401, GET("/api/providers/vllm/reachable").status.intValue());
    }

    @Test
    void reachableReturnsNotConfiguredWhenBaseUrlMissing() {
        login();
        // No provider.vllm.baseUrl → 200 with reachable=false + "not configured" (never an error,
        // so the UI renders a hint rather than failing).
        var resp = GET("/api/providers/vllm/reachable");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"reachable\":false"), "expected reachable=false: " + body);
        assertTrue(body.contains("not configured"), "expected 'not configured' reason: " + body);
    }

    // --- list ---

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/providers").status.intValue());
    }

    @Test
    void listReturnsJsonArray() {
        login();
        var resp = GET("/api/providers");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        assertTrue(getContent(resp).startsWith("["));
    }

    @Test
    void listReflectsSeededProvider() {
        // Seed openrouter so ProviderRegistry.refresh() picks it up, then GET
        // /api/providers must surface it. Exercises the full list+map+toJson
        // path that the 0%-covered lambda$0 lives inside.
        login();
        ConfigService.set("provider.openrouter.baseUrl", "https://openrouter.ai/api/v1");
        ConfigService.set("provider.openrouter.apiKey", "sk-test");
        ConfigService.set("provider.openrouter.models", "[]");
        llm.ProviderRegistry.refresh();
        var resp = GET("/api/providers");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"openrouter\""),
                "seeded provider must appear in listing: " + body);
    }

    // --- models (GET) + addModel (POST) ---

    /** Mark test-provider configured so the {name} guard passes. */
    private void configureProvider() {
        ConfigService.set("provider.test-provider.baseUrl", "https://example.invalid/v1");
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
    }

    @Test
    void modelsRequiresAuth() {
        assertEquals(401, GET("/api/providers/test-provider/models").status.intValue());
    }

    @Test
    void addModelRequiresAuth() {
        var resp = POST("/api/providers/test-provider/models",
                "application/json", "{\"id\":\"gpt-x\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void modelsReturns404WhenProviderNotConfigured() {
        login();
        assertEquals(404, GET("/api/providers/test-provider/models").status.intValue());
    }

    @Test
    void addModelReturns404WhenProviderNotConfigured() {
        login();
        var resp = POST("/api/providers/test-provider/models",
                "application/json", "{\"id\":\"gpt-x\"}");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void modelsReturnsEmptyForConfiguredProviderWithNoModels() {
        login();
        configureProvider();
        var resp = GET("/api/providers/test-provider/models");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"count\":0"), "expected count 0: " + body);
        assertTrue(body.contains("\"models\":[]"), "expected empty models: " + body);
    }

    @Test
    void addModelThenModelsListsIt() {
        login();
        configureProvider();

        var add = POST("/api/providers/test-provider/models",
                "application/json",
                "{\"id\":\"vendor/gpt-x\",\"name\":\"GPT X\"}");
        assertIsOk(add);
        var addBody = getContent(add);
        assertTrue(addBody.contains("\"vendor/gpt-x\""), "add response missing id: " + addBody);
        assertTrue(addBody.contains("\"count\":1"), "add response missing count: " + addBody);

        var list = GET("/api/providers/test-provider/models");
        assertIsOk(list);
        var listBody = getContent(list);
        assertTrue(listBody.contains("\"id\":\"vendor/gpt-x\""), "list missing id: " + listBody);
        assertTrue(listBody.contains("\"name\":\"GPT X\""), "list missing name: " + listBody);
    }

    @Test
    void addModelDerivesNameFromIdWhenOmitted() {
        login();
        configureProvider();

        var add = POST("/api/providers/test-provider/models",
                "application/json", "{\"id\":\"vendor/gpt-x\"}");
        assertIsOk(add);
        // name defaults to the last path segment of the id.
        assertTrue(getContent(add).contains("\"name\":\"gpt-x\""),
                "expected derived name 'gpt-x': " + getContent(add));
    }

    @Test
    void addModelReturns400WhenIdMissing() {
        login();
        configureProvider();
        var resp = POST("/api/providers/test-provider/models",
                "application/json", "{\"name\":\"no id here\"}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void addModelReturns409OnDuplicateId() {
        login();
        configureProvider();
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"gpt-x\",\"name\":\"GPT X\"}]");

        var resp = POST("/api/providers/test-provider/models",
                "application/json", "{\"id\":\"gpt-x\"}");
        assertEquals(409, resp.status.intValue());
    }

    @Test
    void addModelPersistsOptionalFieldsAndStripsUnsetPrices() {
        login();
        configureProvider();

        var add = POST("/api/providers/test-provider/models",
                "application/json",
                "{\"id\":\"gpt-x\",\"contextWindow\":128000,\"supportsVision\":true,\"promptPrice\":1.5}");
        assertIsOk(add);

        // Verify the persisted JSON: contextWindow + supportsVision + the one set
        // price are present; the three unset prices are omitted entirely.
        var saved = ConfigService.get("provider.test-provider.models");
        assertTrue(saved.contains("\"contextWindow\":128000"), "missing contextWindow: " + saved);
        assertTrue(saved.contains("\"supportsVision\":true"), "missing supportsVision: " + saved);
        assertTrue(saved.contains("\"promptPrice\":1.5"), "missing promptPrice: " + saved);
        assertFalse(saved.contains("completionPrice"), "unset price should be stripped: " + saved);
    }

    // --- refreshPrices ---

    @Test
    void refreshPricesRequiresAuth() {
        assertEquals(401, POST("/api/providers/refresh-prices",
                "application/json", "{}").status.intValue());
    }

    @Test
    void refreshPricesReturnsEnvelopeShape() {
        login();
        var resp = POST("/api/providers/refresh-prices", "application/json", "{}");
        assertIsOk(resp);
        var body = getContent(resp);
        // Response shape: skipped + providersScanned + modelsUpdated + warnings.
        assertTrue(body.contains("\"skipped\""), "envelope missing skipped: " + body);
        assertTrue(body.contains("\"providersScanned\""), "envelope missing providersScanned: " + body);
        assertTrue(body.contains("\"modelsUpdated\""), "envelope missing modelsUpdated: " + body);
    }
}
