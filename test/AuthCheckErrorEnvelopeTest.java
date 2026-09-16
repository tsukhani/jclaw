import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import utils.ApiResponses;

/**
 * JCLAW-1131: the interceptor's rejections use the canonical envelope.
 *
 * <p>{@link controllers.AuthCheck} hand-built {@code {"error":…,"code":…}} — a different field
 * name from every other error the API emits, and one path emitted no code at all. The SPA reads
 * {@code message}, so the failure an operator hits most often was the one that rendered as bare
 * HTTP status text.
 */
class AuthCheckErrorEnvelopeTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "envelope-test-pass";

    @BeforeEach
    void seedPassword() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
    }

    @AfterEach
    void clearPassword() {
        AuthFixture.clearAdminPassword();
    }

    @Test
    void aSessionlessRequestCarriesACodeAndAMessage() {
        var response = GET("/api/config");

        assertStatus(401, response);
        var body = getContent(response);
        assertTrue(body.contains("\"type\":\"error\""), body);
        assertTrue(body.contains(ApiResponses.AUTHENTICATION_REQUIRED), body);
        assertTrue(body.contains("\"message\""), body);
        assertTrue(body.contains("\"whatToCheck\""), body);
    }

    @Test
    void aRejectedBearerTokenCarriesACodeAndAMessage() {
        var request = newRequest();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer jcl_not-a-real-token"));

        var response = GET(request, "/api/config");

        assertStatus(401, response);
        var body = getContent(response);
        assertTrue(body.contains("\"type\":\"error\""), body);
        assertTrue(body.contains(ApiResponses.INVALID_TOKEN), body);
        assertTrue(body.contains("\"message\""), body);
    }

    @Test
    void anUnprovisionedInstanceCarriesACodeAndAMessage() {
        AuthFixture.clearAdminPassword();

        var response = GET("/api/config");

        assertStatus(401, response);
        var body = getContent(response);
        assertTrue(body.contains("\"type\":\"error\""), body);
        assertTrue(body.contains(ApiResponses.PASSWORD_UNSET), body);
        assertTrue(body.contains("\"message\""), body);
    }
}
