import org.junit.jupiter.api.*;
import play.test.*;

class ApiControllerTest extends FunctionalTest {

    @Test
    void statusReturnsOkAndApplicationMetadata() {
        var resp = GET("/api/status");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"status\":\"ok\""), "must report ok: " + body);
        assertTrue(body.contains("\"application\""), "must echo app name: " + body);
        assertTrue(body.contains("\"mode\""), "must echo play mode: " + body);
        assertTrue(body.contains("\"applicationVersion\""), "must echo version: " + body);
    }
}
