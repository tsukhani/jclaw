import org.junit.jupiter.api.Test;
import play.mvc.Http.Response;
import play.test.FunctionalTest;

/**
 * JCLAW-336: unmatched /api/* paths return a clean 404 JSON instead of falling
 * through to the {controller}/{action} catch-all and raising ActionNotFound.
 */
class ApiNotFoundControllerTest extends FunctionalTest {

    @Test
    void unknownApiPathReturns404Json() {
        Response resp = GET("/api/graphql");
        assertStatus(404, resp);
        assertContentType("application/json", resp);
        assertTrue(getContent(resp).contains("Not found"), getContent(resp));
    }

    @Test
    void unknownDeepApiPathReturns404() {
        assertStatus(404, GET("/api/nope/deeper/path"));
    }

    @Test
    void postToUnknownApiPathReturns404() {
        assertStatus(404, POST("/api/gql", "application/json", "{}"));
    }
}
