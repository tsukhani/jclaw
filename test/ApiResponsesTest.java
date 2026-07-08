import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.mvc.results.RenderJson;
import play.test.UnitTest;
import utils.ApiResponses;

import java.util.Map;


/**
 * JCLAW-155: locks the canonical JSON envelope {@link ApiResponses} emits —
 * success {@code {"status":"ok", ...}} and error
 * {@code {"type":"error","code":...,"message":...}} rendered with the supplied
 * HTTP status. Each helper throws a {@link RenderJson} (the same mechanism as
 * the framework's {@code renderJSON}), so we catch it and inspect the rendered
 * body plus the response status it set. This is the single wire-contract test
 * the migrated controllers all rely on.
 */
class ApiResponsesTest extends UnitTest {

    private static final Gson GSON = new Gson();

    private static Map<String, Object> body(RenderJson r) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = GSON.fromJson(r.getJson(), Map.class);
        return m;
    }

    @Test
    void okRendersBareStatusOk() {
        RenderJson r = assertThrows(RenderJson.class, ApiResponses::ok);
        assertEquals("{\"status\":\"ok\"}", r.getJson());
    }

    @Test
    void okAppendsExtraPairsAfterStatus() {
        RenderJson r = assertThrows(RenderJson.class,
                () -> ApiResponses.ok("deleted", true, "id", 42));
        Map<String, Object> b = body(r);
        assertEquals("ok", b.get("status"));
        assertEquals(Boolean.TRUE, b.get("deleted"));
        assertEquals(42.0, b.get("id")); // Gson deserializes bare numbers as Double
    }

    @Test
    void okRejectsOddArgumentCount() {
        assertThrows(IllegalArgumentException.class, () -> ApiResponses.ok("lonely"));
    }

    @Test
    void errorRendersCanonicalEnvelopeAndSetsStatus() {
        Http.Response response = new Http.Response();
        Http.Response.current.set(response);
        try {
            RenderJson r = assertThrows(RenderJson.class,
                    () -> ApiResponses.error(404, "not_found", "no such thing"));
            Map<String, Object> b = body(r);
            assertEquals("error", b.get("type"));
            assertEquals("not_found", b.get("code"));
            assertEquals("no such thing", b.get("message"));
            assertEquals(Integer.valueOf(404), response.status);
        } finally {
            Http.Response.current.remove();
        }
    }

    @Test
    void errorAndLogRendersCanonicalEnvelopeAndSetsStatus() {
        Http.Response response = new Http.Response();
        Http.Response.current.set(response);
        try {
            var boom = new RuntimeException("boom");
            RenderJson r = assertThrows(RenderJson.class,
                    () -> ApiResponses.errorAndLog(boom, 500, "internal_error", "kaboom"));
            Map<String, Object> b = body(r);
            assertEquals("error", b.get("type"));
            assertEquals("internal_error", b.get("code"));
            assertEquals("kaboom", b.get("message"));
            assertEquals(Integer.valueOf(500), response.status);
        } finally {
            Http.Response.current.remove();
        }
    }
}
