import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

class ApiChannelsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}");
        assertIsOk(resp);
    }

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/channels").status.intValue());
    }

    @Test
    void activeRequiresAuth() {
        assertEquals(401, GET("/api/channels/active").status.intValue());
    }

    @Test
    void getRequiresAuth() {
        assertEquals(401, GET("/api/channels/telegram").status.intValue());
    }

    @Test
    void saveRequiresAuth() {
        var resp = PUT("/api/channels/telegram", "application/json", "{\"enabled\":true}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void listReturnsJsonArray() {
        login();
        var resp = GET("/api/channels");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        assertTrue(getContent(resp).startsWith("["));
    }

    @Test
    void activeReturnsCountAndChannelTypes() {
        login();
        var resp = GET("/api/channels/active");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"count\""), "must carry count: " + body);
        assertTrue(body.contains("\"channelTypes\""), "must carry channelTypes: " + body);
    }

    @Test
    void getReturns404ForUnknownChannel() {
        login();
        assertEquals(404, GET("/api/channels/definitely-not-a-real-channel").status.intValue());
    }

    @Test
    void saveCreatesChannelConfigOnFirstWrite() {
        login();
        var resp = PUT("/api/channels/slack", "application/json",
                "{\"config\":{\"webhookSecret\":\"xyz\"},\"enabled\":false}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"channelType\":\"slack\""), "got: " + body);
        assertTrue(body.contains("\"enabled\":false"));

        // Subsequent GET surfaces the persisted row.
        var fetched = getContent(GET("/api/channels/slack"));
        assertTrue(fetched.contains("\"webhookSecret\""), "config persisted: " + fetched);
    }

    @Test
    void saveReturns400OnEmptyBody() {
        login();
        var resp = PUT("/api/channels/slack", "application/json", "");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void saveUpdatesExistingChannelConfig() {
        login();
        PUT("/api/channels/slack", "application/json",
                "{\"config\":{\"k\":\"v1\"},\"enabled\":true}");
        var resp = PUT("/api/channels/slack", "application/json",
                "{\"enabled\":false}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"enabled\":false"),
                "re-PUT must flip enabled: " + getContent(resp));
    }
}
