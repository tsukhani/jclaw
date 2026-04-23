import org.junit.jupiter.api.*;
import play.test.*;

public class AuthTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-123";

    @BeforeEach
    void seedPassword() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
    }

    @AfterEach
    void clearPassword() {
        AuthFixture.clearAdminPassword();
    }

    @Test
    public void loginWithValidCredentials() {
        var body = """
                {"username": "admin", "password": "%s"}
                """.formatted(TEST_PASSWORD);
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).contains("\"status\":\"ok\""));
    }

    @Test
    public void loginWithInvalidCredentials() {
        var body = """
                {"username": "admin", "password": "wrong"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void loginFailsWhenPasswordUnset() {
        AuthFixture.clearAdminPassword();
        var body = """
                {"username": "admin", "password": "anything"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void statusReturnsPasswordSetWhenHashPresent() {
        var response = GET("/api/auth/status");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"passwordSet\":true"),
                "got: " + getContent(response));
    }

    @Test
    public void statusReturnsPasswordUnsetWhenHashAbsent() {
        AuthFixture.clearAdminPassword();
        var response = GET("/api/auth/status");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"passwordSet\":false"),
                "got: " + getContent(response));
    }

    @Test
    public void setupSucceedsWhenNoPasswordSet() {
        AuthFixture.clearAdminPassword();
        var body = "{\"password\":\"new-password-here\"}";
        var response = POST("/api/auth/setup", "application/json", body);
        assertIsOk(response);
        var loginBody = "{\"username\":\"admin\",\"password\":\"new-password-here\"}";
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);
    }

    @Test
    public void setupRejectsShortPassword() {
        AuthFixture.clearAdminPassword();
        var body = "{\"password\":\"short\"}";
        var response = POST("/api/auth/setup", "application/json", body);
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).contains("password_too_short"),
                "got: " + getContent(response));
    }

    @Test
    public void setupRejectedWhenPasswordAlreadySet() {
        var body = "{\"password\":\"new-password-here\"}";
        var response = POST("/api/auth/setup", "application/json", body);
        assertEquals(409, response.status.intValue());
        assertTrue(getContent(response).contains("already_set"),
                "got: " + getContent(response));
    }

    @Test
    public void statusEndpointDoesNotRequireAuth() {
        var response = GET("/api/status");
        assertIsOk(response);
    }

    @Test
    public void protectedEndpointRequiresAuth() {
        var response = GET("/api/config");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void protectedEndpointAccessibleAfterLogin() {
        var loginBody = """
                {"username": "admin", "password": "%s"}
                """.formatted(TEST_PASSWORD);
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);

        var response = GET("/api/config");
        assertIsOk(response);
    }

    @Test
    public void resetPasswordRequiresAuth() {
        var response = POST("/api/auth/reset-password", "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void resetPasswordWipesHashAndClearsSession() {
        // Log in
        var loginBody = """
                {"username": "admin", "password": "%s"}
                """.formatted(TEST_PASSWORD);
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);

        // Reset
        var resetResponse = POST("/api/auth/reset-password", "application/json", "{}");
        assertIsOk(resetResponse);

        // Status now reports passwordSet=false (hash wiped)
        var statusResponse = GET("/api/auth/status");
        assertTrue(getContent(statusResponse).contains("\"passwordSet\":false"),
                "expected hash to be wiped; got: " + getContent(statusResponse));

        // Session was cleared — protected endpoint must reject
        var protectedResponse = GET("/api/config");
        assertEquals(401, protectedResponse.status.intValue());
    }

    @Test
    public void logoutClearsSession() {
        var loginBody = """
                {"username": "admin", "password": "%s"}
                """.formatted(TEST_PASSWORD);
        POST("/api/auth/login", "application/json", loginBody);

        var logoutResponse = POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(logoutResponse);

        var response = GET("/api/config");
        assertEquals(401, response.status.intValue());
    }
}
