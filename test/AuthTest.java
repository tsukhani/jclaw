import org.junit.jupiter.api.*;
import play.test.*;
import play.mvc.Http.*;

import java.util.HashMap;

public class AuthTest extends FunctionalTest {

    @Test
    public void loginWithValidCredentials() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
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
        // Login first
        var loginBody = """
                {"username": "admin", "password": "changeme"}
                """;
        var loginResponse = POST("/api/auth/login", "application/json", loginBody);
        assertIsOk(loginResponse);

        // Now access protected endpoint with the session cookie
        var response = GET("/api/config");
        assertIsOk(response);
    }

    @Test
    public void logoutClearsSession() {
        // Login
        var loginBody = """
                {"username": "admin", "password": "changeme"}
                """;
        POST("/api/auth/login", "application/json", loginBody);

        // Logout
        var logoutResponse = POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(logoutResponse);

        // Now protected endpoint should reject
        var response = GET("/api/config");
        assertEquals(401, response.status.intValue());
    }
}
