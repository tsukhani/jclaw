import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.compression.JsonSpan;

/**
 * JCLAW-468: JsonSpan — prefix-tolerant JSON detection. It locates a JSON body
 * after a short non-JSON prefix (a status line) but refuses to read trailing
 * braces in source code as JSON.
 */
class JsonSpanTest extends UnitTest {

    @Test
    void findsPureJsonWithNoPrefix() {
        var span = JsonSpan.find("[{\"id\":1},{\"id\":2}]");
        assertTrue(span.isPresent());
        assertEquals("", span.get().prefix(), "no prefix on pure JSON");
        assertTrue(span.get().json().isJsonArray());
    }

    @Test
    void findsJsonBehindAStatusLinePrefix() {
        // The jclaw_api shape: a status line then the JSON body.
        var span = JsonSpan.find("HTTP 200\n{\"ok\":true}");
        assertTrue(span.isPresent(), "JSON after a short status prefix is detected");
        assertEquals("HTTP 200\n", span.get().prefix());
        assertTrue(span.get().json().isJsonObject());
    }

    @Test
    void rejectsSourceCodeEndingInBraces() {
        // "class Bar {}" must stay CODE: the {} body is shorter than its prefix,
        // so it isn't a status-line-prefixed JSON payload.
        assertTrue(JsonSpan.find("class Bar {}").isEmpty());
    }

    @Test
    void rejectsPlainText() {
        assertTrue(JsonSpan.find("just some prose, nothing structured here").isEmpty());
    }
}
