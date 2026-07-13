import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
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
        ConfigService.delete("provider.vllm.baseUrl");
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

    // --- video-models ---

    @Test
    void videoModelsRequiresAuth() {
        assertEquals(401, GET("/api/providers/openrouter/video-models").status.intValue());
    }

    @Test
    void videoModelsReturns400WhenBaseUrlMissing() {
        login();
        var resp = GET("/api/providers/test-provider/video-models");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).toLowerCase().contains("base url"),
                "error should mention base URL: " + getContent(resp));
    }

    @Test
    void videoModelsDoesNotRequireApiKey() {
        // Unlike discover-models, the video-models endpoint tolerates a missing API key (vLLM has
        // none): with a baseUrl set but no key it proceeds to the discovery call (which fails to
        // connect to the bogus host → 502), never a 400 "no API key".
        login();
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:1/v1");
        var resp = GET("/api/providers/test-provider/video-models");
        assertNotEquals(400, resp.status.intValue(),
                "missing API key must not 400 on video-models: " + resp.status);
    }

    @Test
    void videoModelsReturnsVideoCapableModelsOnly() throws Exception {
        // The picker offers any model whose input_modalities include "video" (the interpreter sends a
        // provider-agnostic video_url). Mock a mixed catalog: a video model (Gemini), an image-only
        // model (a Qwen-VL route, which on OpenRouter really is image-only), and a text model — only
        // the video one survives.
        login();
        try (var server = new mockwebserver3.MockWebServer()) {
            server.start();
            ConfigService.set("provider.test-provider.baseUrl", server.url("/").toString());
            ConfigService.set("provider.test-provider.apiKey", "sk-test");
            var catalog = "{\"data\":["
                    + "{\"id\":\"google/gemini-2.5-flash\",\"architecture\":{\"input_modalities\":[\"text\",\"image\",\"video\"]}},"
                    + "{\"id\":\"qwen/qwen3-vl-30b\",\"architecture\":{\"input_modalities\":[\"text\",\"image\"]}},"
                    + "{\"id\":\"qwen/qwen3-32b\",\"architecture\":{\"input_modalities\":[\"text\"]}}]}";
            var buf = new okio.Buffer();
            buf.writeUtf8(catalog);
            server.enqueue(new mockwebserver3.MockResponse.Builder()
                    .code(200).addHeader("Content-Type", "application/json").body(buf).build());

            var resp = GET("/api/providers/test-provider/video-models");
            assertIsOk(resp);
            var body = getContent(resp);
            assertTrue(body.contains("gemini-2.5-flash"), "video-capable model must be offered: " + body);
            assertFalse(body.contains("qwen3-vl-30b"), "image-only model must be excluded: " + body);
            assertFalse(body.contains("qwen3-32b"), "text-only model must be excluded: " + body);
            assertTrue(body.contains("\"count\":1"), "exactly one video-capable model expected: " + body);
        }
    }

    @Test
    void videoModelsForMultiImageProviderIncludesVisionModels() throws Exception {
        // A MULTI_IMAGE provider (vLLM/Ollama) interprets sampled frames, so the picker offers ANY
        // vision-capable model, not just video-capable ones. Mock a mixed catalog: a vision-only model,
        // a video model (also vision), and a text model — the first two survive, the text one is excluded.
        login();
        try (var server = new mockwebserver3.MockWebServer()) {
            server.start();
            ConfigService.set("provider.vllm.baseUrl", server.url("/").toString());
            var catalog = "{\"data\":["
                    + "{\"id\":\"vendor/vision-only\",\"architecture\":{\"input_modalities\":[\"text\",\"image\"]}},"
                    + "{\"id\":\"google/gemini-2.5-flash\",\"architecture\":{\"input_modalities\":[\"text\",\"image\",\"video\"]}},"
                    + "{\"id\":\"vendor/text-only\",\"architecture\":{\"input_modalities\":[\"text\"]}}]}";
            var buf = new okio.Buffer();
            buf.writeUtf8(catalog);
            server.enqueue(new mockwebserver3.MockResponse.Builder()
                    .code(200).addHeader("Content-Type", "application/json").body(buf).build());

            var resp = GET("/api/providers/vllm/video-models");
            assertIsOk(resp);
            var body = getContent(resp);
            assertTrue(body.contains("vision-only"), "vision-only model must be offered for MULTI_IMAGE: " + body);
            assertTrue(body.contains("gemini-2.5-flash"), "video model must also be offered: " + body);
            assertFalse(body.contains("text-only"), "text-only model must be excluded: " + body);
            assertTrue(body.contains("\"count\":2"), "two vision-capable models expected: " + body);
        } finally {
            ConfigService.delete("provider.vllm.baseUrl");
        }
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

    // --- discoverModels: the switch over DiscoveryResult (past both 400 guards) ---

    private static void enqueueJson(mockwebserver3.MockWebServer server, int code, String json) {
        var buf = new okio.Buffer();
        buf.writeUtf8(json);
        server.enqueue(new mockwebserver3.MockResponse.Builder()
                .code(code).addHeader("Content-Type", "application/json").body(buf).build());
    }

    @Test
    void discoverModelsReturnsOkCatalogFromLiveApi() throws Exception {
        // Past both the baseUrl and apiKey guards, discover() runs and the
        // DiscoveryResult.Ok switch arm renders the normalized catalog + count.
        // Every existing discoverModels test stops at a 400 guard, so this is
        // the only path that exercises the Ok arm.
        login();
        try (var server = new mockwebserver3.MockWebServer()) {
            server.start();
            ConfigService.set("provider.test-provider.baseUrl", server.url("/").toString());
            ConfigService.set("provider.test-provider.apiKey", "sk-test");
            enqueueJson(server, 200,
                    "{\"data\":[{\"id\":\"acme/talker-one\"},{\"id\":\"acme/talker-two\"}]}");

            var resp = POST("/api/providers/test-provider/discover-models",
                    "application/json", "{}");
            assertIsOk(resp);
            assertContentType("application/json", resp);
            var body = getContent(resp);
            assertTrue(body.contains("\"count\":2"), "both live models counted: " + body);
            assertTrue(body.contains("acme/talker-one"), "first model present: " + body);
            assertTrue(body.contains("acme/talker-two"), "second model present: " + body);
        }
    }

    @Test
    void discoverModelsReturns502WhenUpstreamErrors() throws Exception {
        // The DiscoveryResult.Error arm maps the service's status code straight
        // to the HTTP response (502 for a non-200 upstream) with code=upstream_error.
        login();
        try (var server = new mockwebserver3.MockWebServer()) {
            server.start();
            ConfigService.set("provider.test-provider.baseUrl", server.url("/").toString());
            ConfigService.set("provider.test-provider.apiKey", "sk-test");
            enqueueJson(server, 500, "{\"error\":\"boom\"}");

            var resp = POST("/api/providers/test-provider/discover-models",
                    "application/json", "{}");
            assertEquals(502, resp.status.intValue());
            assertTrue(getContent(resp).contains("\"code\":\"upstream_error\""),
                    "error body must carry the upstream_error code: " + getContent(resp));
        }
    }

    // --- reachable: the live-probe path (baseUrl configured, not the "not configured" short-circuit) ---

    @Test
    void reachableReportsReachableWhenModelsEndpointResponds() throws Exception {
        // baseUrl set → the not-configured short-circuit is skipped and the real
        // probe runs. A 200 /models with a data array yields reachable=true and a
        // model count equal to the array size.
        login();
        try (var server = new mockwebserver3.MockWebServer()) {
            server.start();
            ConfigService.set("provider.vllm.baseUrl", server.url("/").toString());
            enqueueJson(server, 200, "{\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}");

            var resp = GET("/api/providers/vllm/reachable");
            assertIsOk(resp);
            var body = getContent(resp);
            assertTrue(body.contains("\"reachable\":true"), "probe hit a live endpoint: " + body);
            assertTrue(body.contains("\"modelCount\":2"), "modelCount reflects the data array: " + body);
        }
    }

    @Test
    void reachableReportsUnreachableWithReasonWhenEndpointErrors() throws Exception {
        // A configured-but-erroring endpoint stays HTTP 200 (never an error) with
        // reachable=false and a reason naming the upstream status, so the UI can
        // render a hint. Distinct from the "not configured" reason.
        login();
        try (var server = new mockwebserver3.MockWebServer()) {
            server.start();
            ConfigService.set("provider.vllm.baseUrl", server.url("/").toString());
            enqueueJson(server, 503, "unavailable");

            var resp = GET("/api/providers/vllm/reachable");
            assertIsOk(resp);
            var body = getContent(resp);
            assertTrue(body.contains("\"reachable\":false"), "erroring endpoint is not reachable: " + body);
            assertTrue(body.contains("HTTP 503"), "reason names the upstream status: " + body);
            assertFalse(body.contains("not configured"),
                    "a configured endpoint must not report 'not configured': " + body);
        }
    }

    // --- models (GET): parse-array edge cases (non-object rows, blank ids, non-array config) ---

    @Test
    void modelsSkipsNonObjectAndBlankIdEntries() {
        login();
        configureProvider();
        // A number row (not an object), a blank-id object, and one real model.
        ConfigService.set("provider.test-provider.models",
                "[123, {\"id\":\"\"}, {\"id\":\"vendor/real\"}]");

        var resp = GET("/api/providers/test-provider/models");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"count\":1"), "only the valid row survives: " + body);
        assertTrue(body.contains("\"id\":\"vendor/real\""), "real model present: " + body);
        // No display name in the stored row → deriveName picks the last path segment.
        assertTrue(body.contains("\"name\":\"real\""), "name derived from id: " + body);
    }

    @Test
    void modelsReturnsEmptyWhenConfigIsNotJsonArray() {
        // parseModelsArray parses a valid-but-non-array config (a JSON object) and
        // returns an empty array rather than throwing.
        login();
        configureProvider();
        ConfigService.set("provider.test-provider.models", "{\"not\":\"an array\"}");

        var resp = GET("/api/providers/test-provider/models");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"count\":0"),
                "non-array config yields zero models: " + getContent(resp));
    }

    @Test
    void modelsReturnsEmptyWhenConfigIsMalformedJson() {
        // parseModelsArray's catch arm: unparseable JSON degrades to an empty list.
        login();
        configureProvider();
        ConfigService.set("provider.test-provider.models", "{oops not json");

        var resp = GET("/api/providers/test-provider/models");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"count\":0"),
                "malformed config yields zero models: " + getContent(resp));
    }

    // --- addModel: id-validation sub-branches + buildModelObject branches ---

    @Test
    void addModelReturns400WhenIdIsExplicitNull() {
        login();
        configureProvider();
        var resp = POST("/api/providers/test-provider/models",
                "application/json", "{\"id\":null}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void addModelReturns400WhenIdIsBlank() {
        login();
        configureProvider();
        var resp = POST("/api/providers/test-provider/models",
                "application/json", "{\"id\":\"   \"}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void addModelPersistsAlwaysThinksWhenThinkingEnabled() {
        // buildModelObject only writes alwaysThinks when supportsThinking is also
        // true (alwaysThinks ⇒ supportsThinking). Both-true is the only path that
        // emits the field.
        login();
        configureProvider();
        var add = POST("/api/providers/test-provider/models", "application/json",
                "{\"id\":\"reasoner\",\"supportsThinking\":true,\"alwaysThinks\":true}");
        assertIsOk(add);
        var saved = ConfigService.get("provider.test-provider.models");
        assertTrue(saved.contains("\"supportsThinking\":true"), "thinking persisted: " + saved);
        assertTrue(saved.contains("\"alwaysThinks\":true"), "alwaysThinks persisted: " + saved);
    }

    @Test
    void addModelOmitsAlwaysThinksWhenThinkingWithoutAlwaysThinks() {
        // supportsThinking=true but alwaysThinks unset → the field is omitted.
        login();
        configureProvider();
        var add = POST("/api/providers/test-provider/models", "application/json",
                "{\"id\":\"thinker\",\"supportsThinking\":true}");
        assertIsOk(add);
        var saved = ConfigService.get("provider.test-provider.models");
        assertTrue(saved.contains("\"supportsThinking\":true"), "thinking persisted: " + saved);
        assertFalse(saved.contains("alwaysThinks"),
                "alwaysThinks must be omitted when not always-thinking: " + saved);
    }

    @Test
    void addModelTreatsNonNumericPriceAsUnsetAndStripsIt() {
        // optPrice catches NumberFormatException on a non-numeric price and returns
        // -1, so addPriceIfSet omits the field (never poisoning the cost fallbacks).
        login();
        configureProvider();
        var add = POST("/api/providers/test-provider/models", "application/json",
                "{\"id\":\"m\",\"promptPrice\":\"abc\"}");
        assertIsOk(add);
        var saved = ConfigService.get("provider.test-provider.models");
        assertFalse(saved.contains("promptPrice"),
                "a non-numeric price must be stripped, not persisted: " + saved);
    }
}
