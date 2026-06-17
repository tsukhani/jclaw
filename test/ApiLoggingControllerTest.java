import org.junit.jupiter.api.*;
import play.test.*;

/**
 * Functional coverage for {@link controllers.ApiLoggingController}.
 *
 * <p>The round-trip test drives a UNIQUE dummy logger so the live
 * {@code Configurator.setLevel} side effect can't perturb the concurrent
 * functional-test lane.
 */
class ApiLoggingControllerTest extends FunctionalTest {

    private static final String DUMMY = "test.jclaw.functional.apilogging";

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
        assertEquals(401, GET("/api/logging/levels").status.intValue());
    }

    @Test
    void saveRequiresAuth() {
        var resp = POST("/api/logging/levels", "application/json",
                "{\"logger\":\"play\",\"level\":\"DEBUG\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void deleteRequiresAuth() {
        assertEquals(401, DELETE("/api/logging/levels/play").status.intValue());
    }

    @Test
    void listExposesValidLevels() {
        login();
        var body = getContent(GET("/api/logging/levels"));
        assertTrue(body.contains("\"validLevels\""), "must carry the valid-level set: " + body);
        assertTrue(body.contains("\"DEBUG\""), "valid levels must include DEBUG: " + body);
    }

    @Test
    void saveRejectsInvalidLevel() {
        login();
        var resp = POST("/api/logging/levels", "application/json",
                "{\"logger\":\"" + DUMMY + "\",\"level\":\"VERBOSE\"}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void saveRejectsBlankLogger() {
        login();
        var resp = POST("/api/logging/levels", "application/json",
                "{\"logger\":\"\",\"level\":\"DEBUG\"}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void saveThenListThenDeleteRoundTrip() {
        login();

        var saveResp = POST("/api/logging/levels", "application/json",
                "{\"logger\":\"" + DUMMY + "\",\"level\":\"DEBUG\"}");
        assertIsOk(saveResp);
        assertTrue(getContent(saveResp).contains("\"status\":\"ok\""));

        var listBody = getContent(GET("/api/logging/levels"));
        assertTrue(listBody.contains("\"" + DUMMY + "\""),
                "the saved override must appear in the list: " + listBody);

        var delResp = DELETE("/api/logging/levels/" + DUMMY);
        assertIsOk(delResp);
        assertTrue(getContent(delResp).contains("\"status\":\"ok\""));

        var afterDelete = getContent(GET("/api/logging/levels"));
        assertFalse(afterDelete.contains("\"" + DUMMY + "\""),
                "the deleted override must be gone: " + afterDelete);
    }
}
