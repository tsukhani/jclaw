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
public class ApiProvidersControllerTest extends FunctionalTest {

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
    public void discoverModelsRequiresAuth() {
        var response = POST("/api/providers/openrouter/discover-models",
                "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void discoverModelsReturns400WhenBaseUrlMissing() {
        login();
        var response = POST("/api/providers/test-provider/discover-models",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).toLowerCase().contains("base url"),
                "error message should mention base URL: " + getContent(response));
    }

    @Test
    public void discoverModelsReturns400WhenBaseUrlIsBlank() {
        login();
        ConfigService.set("provider.test-provider.baseUrl", "   ");
        var response = POST("/api/providers/test-provider/discover-models",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void discoverModelsReturns400WhenApiKeyMissing() {
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
    public void discoverModelsReturns400WhenApiKeyIsBlank() {
        login();
        ConfigService.set("provider.test-provider.baseUrl", "https://example.invalid/v1");
        ConfigService.set("provider.test-provider.apiKey", "");
        var response = POST("/api/providers/test-provider/discover-models",
                "application/json", "{}");
        assertEquals(400, response.status.intValue());
    }
}
