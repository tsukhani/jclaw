import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import tools.HarnessSandbox;

/** {@code GET /api/sandbox}: the host's sandbox mechanism, as the Settings panels read it. */
class ApiSandboxControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-sandbox";

    @BeforeEach
    void seedAndLogin() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        var loginBody = """
                {"username":"admin","password":"%s"}
                """.formatted(TEST_PASSWORD);
        assertIsOk(POST("/api/auth/login", "application/json", loginBody));
    }

    @AfterEach
    void cleanup() {
        AuthFixture.clearAdminPassword();
    }

    @Test
    void reportsTheHostsMechanism() {
        var response = GET("/api/sandbox");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertTrue(body.get("available").getAsJsonPrimitive().isBoolean());
        assertEquals(HarnessSandbox.availability().mechanism(), body.get("mechanism").getAsString());
    }

    @Test
    void requiresAuth() {
        POST("/api/auth/logout", "application/json", "{}");
        assertEquals(401, GET("/api/sandbox").status.intValue());
    }
}
