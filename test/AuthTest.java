import org.junit.jupiter.api.*;
import play.test.*;

class AuthTest extends FunctionalTest {

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
    void loginWithValidCredentials() {
        var body = """
                {"username": "admin", "password": "%s"}
                """.formatted(TEST_PASSWORD);
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).contains("\"status\":\"ok\""));
    }

    @Test
    void loginWithInvalidCredentials() {
        var body = """
                {"username": "admin", "password": "wrong"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertEquals(401, response.status.intValue());
    }

    @Test
    void loginFailsWhenPasswordUnset() {
        AuthFixture.clearAdminPassword();
        var body = """
                {"username": "admin", "password": "anything"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertEquals(401, response.status.intValue());
    }

    @Test
    void statusReturnsPasswordSetWhenHashPresent() {
        var response = GET("/api/auth/status");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"passwordSet\":true"),
                "got: " + getContent(response));
    }

    @Test
    void statusReturnsPasswordUnsetWhenHashAbsent() {
        AuthFixture.clearAdminPassword();
        var response = GET("/api/auth/status");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"passwordSet\":false"),
                "got: " + getContent(response));
    }

    @Test
    void setupSucceedsWhenNoPasswordSet() {
        AuthFixture.clearAdminPassword();
        var body = "{\"password\":\"new-password-here\"}";
        var response = POST("/api/auth/setup", "application/json", body);
        assertIsOk(response);
        var loginBody = "{\"username\":\"admin\",\"password\":\"new-password-here\"}";
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);
    }

    @Test
    void setupRejectsShortPassword() {
        AuthFixture.clearAdminPassword();
        var body = "{\"password\":\"short\"}";
        var response = POST("/api/auth/setup", "application/json", body);
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).contains("password_too_short"),
                "got: " + getContent(response));
    }

    @Test
    void setupRejectedWhenPasswordAlreadySet() {
        var body = "{\"password\":\"new-password-here\"}";
        var response = POST("/api/auth/setup", "application/json", body);
        assertEquals(409, response.status.intValue());
        assertTrue(getContent(response).contains("already_set"),
                "got: " + getContent(response));
    }

    @Test
    void statusEndpointDoesNotRequireAuth() {
        var response = GET("/api/status");
        assertIsOk(response);
    }

    @Test
    void protectedEndpointRequiresAuth() {
        var response = GET("/api/config");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void protectedEndpointAccessibleAfterLogin() {
        var loginBody = """
                {"username": "admin", "password": "%s"}
                """.formatted(TEST_PASSWORD);
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);

        var response = GET("/api/config");
        assertIsOk(response);
    }

    @Test
    void resetPasswordRequiresAuth() {
        var response = POST("/api/auth/reset-password", "application/json", "{}");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void resetPasswordWipesHashAndClearsSession() {
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
    void logoutClearsSession() {
        var loginBody = """
                {"username": "admin", "password": "%s"}
                """.formatted(TEST_PASSWORD);
        POST("/api/auth/login", "application/json", loginBody);

        var logoutResponse = POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(logoutResponse);

        var response = GET("/api/config");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void staleSessionRejectedWhenPasswordWiped() {
        // Reproduce the fresh-install gotcha: a session cookie from before
        // the password row was wiped (or surviving a rm-and-reclone) is
        // still cryptographically valid because Play sessions are stateless
        // cookies signed with application.secret — which doesn't change
        // across DB mutations or reinstalls. Without AuthCheck's DB
        // cross-check, that cookie would still pass the session.get("authenticated")
        // == "true" gate and let the user into protected endpoints (and via
        // the frontend middleware, into the home page) on a fresh install.
        var loginBody = """
                {"username": "admin", "password": "%s"}
                """.formatted(TEST_PASSWORD);
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);

        // Sanity check: cookie works while password is set.
        var beforeWipe = GET("/api/config");
        assertIsOk(beforeWipe);

        // Wipe the password OUT-OF-BAND (simulating a fresh install or a
        // reset done from another tab/machine). The session cookie itself
        // is untouched — we still hold it.
        AuthFixture.clearAdminPassword();

        // Same cookie, different DB state. AuthCheck must reject.
        var afterWipe = GET("/api/config");
        assertEquals(401, afterWipe.status.intValue());
        assertTrue(getContent(afterWipe).contains("password_unset"),
                "expected password_unset code so the SPA can distinguish "
                        + "'DB has no admin' from generic 'needs login'; got: "
                        + getContent(afterWipe));
    }
}
