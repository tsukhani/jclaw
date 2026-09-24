import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.browser.PlaywrightNode;

/** {@code /api/browser/setup}: the browser tool's first-use setup, as Settings and the chat bar read it. */
class ApiBrowserControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    @Test
    void bothRoutesRequireAuth() {
        assertEquals(401, GET("/api/browser/setup").status.intValue());
        assertEquals(401, POST("/api/browser/setup", "application/json", "{}").status.intValue());
    }

    @Test
    void statusNamesTheDriversSourceAndThePinnedNode() {
        login();
        var resp = GET("/api/browser/setup");
        assertIsOk(resp);
        var json = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        // The test JVM carries driver-bundle, so nothing needs downloading here.
        assertEquals("bundled", json.get("driverSource").getAsString(), json.toString());
        assertEquals(PlaywrightNode.hostPlatform(), json.get("platform").getAsString());
        assertEquals(PlaywrightNode.VERSION, json.get("nodeVersion").getAsString());
        assertTrue(json.has("chromiumInstalled"), json.toString());
        assertTrue(json.has("active"), json.toString());
    }
}
