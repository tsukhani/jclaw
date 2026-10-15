import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;

/** JCLAW-1455: the operator's list of stored credentials that do not decrypt; what it lists is UnreadableCredentialsTest's. */
class ApiCredentialsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        AuthFixture.seedAdminPassword("changeme");
    }

    @Test
    void theOperatorReadsTheListAndNobodyElseDoes() {
        assertEquals(401, GET("/api/credentials/unreadable").status.intValue());

        assertIsOk(POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """));
        var response = GET("/api/credentials/unreadable");
        assertIsOk(response);
        assertTrue(JsonParser.parseString(getContent(response)).getAsJsonObject().get("unreadable").isJsonArray());
    }
}
