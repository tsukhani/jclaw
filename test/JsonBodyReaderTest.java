import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.JsonBodyReader;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.mvc.results.RenderJson;
import play.test.UnitTest;

/**
 * JCLAW-823: {@link JsonBodyReader#requiredOr400} must return the documented 400
 * for a non-primitive value (an object or array under the key), not blow up with
 * an {@code UnsupportedOperationException} from {@code getAsString()} — which the
 * framework renders as a 500 with a stack trace. Five controller call sites
 * inherit this guard.
 *
 * <p>{@code requiredOr400} signals the failure via {@link utils.ApiResponses#error},
 * which sets {@code Http.Response.current().status} and throws a {@link RenderJson},
 * so each test seeds a thread-local {@link Http.Response} and inspects it.
 */
class JsonBodyReaderTest extends UnitTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void objectValueYields400NotAServerError() {
        Http.Response response = new Http.Response();
        Http.Response.current.set(response);
        try {
            // {"name":{}} — the value is a JSON object, not a primitive.
            RenderJson r = assertThrows(RenderJson.class,
                    () -> JsonBodyReader.requiredOr400(obj("{\"name\":{}}"), "name"));
            assertNotNull(r.getJson());
            assertEquals(Integer.valueOf(400), response.status);
        } finally {
            Http.Response.current.remove();
        }
    }

    @Test
    void arrayValueYields400() {
        Http.Response response = new Http.Response();
        Http.Response.current.set(response);
        try {
            assertThrows(RenderJson.class,
                    () -> JsonBodyReader.requiredOr400(obj("{\"name\":[1,2]}"), "name"));
            assertEquals(Integer.valueOf(400), response.status);
        } finally {
            Http.Response.current.remove();
        }
    }

    @Test
    void absentKeyYields400() {
        Http.Response response = new Http.Response();
        Http.Response.current.set(response);
        try {
            assertThrows(RenderJson.class,
                    () -> JsonBodyReader.requiredOr400(obj("{\"other\":\"x\"}"), "name"));
            assertEquals(Integer.valueOf(400), response.status);
        } finally {
            Http.Response.current.remove();
        }
    }

    @Test
    void nullValueYields400() {
        Http.Response response = new Http.Response();
        Http.Response.current.set(response);
        try {
            assertThrows(RenderJson.class,
                    () -> JsonBodyReader.requiredOr400(obj("{\"name\":null}"), "name"));
            assertEquals(Integer.valueOf(400), response.status);
        } finally {
            Http.Response.current.remove();
        }
    }

    @Test
    void primitiveValueIsReturned() {
        // A plain string primitive succeeds and returns the raw value.
        assertEquals("hello", JsonBodyReader.requiredOr400(obj("{\"name\":\"hello\"}"), "name"));
    }
}
