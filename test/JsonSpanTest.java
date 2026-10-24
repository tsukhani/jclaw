import com.google.gson.JsonParser;
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

    private static final String PLACEHOLDER_PREFIX =
            "[Rendered Prefab UI]\n\nStructured content (the data the tool's view draws):\n";

    /** {@code {"s":"…"}} of exactly {@code length} characters. */
    private static String jsonOfLength(int length) {
        return "{\"s\":\"" + "x".repeat(length - 8) + "\"}";
    }

    @Test
    void aBracketedPlaceholderBeforeABodyOnItsOwnLineStillFindsTheBody() {
        var body = jsonOfLength(200);
        var span = JsonSpan.find(PLACEHOLDER_PREFIX + body).orElseThrow();
        assertEquals(PLACEHOLDER_PREFIX, span.prefix());
        assertEquals(JsonParser.parseString(body), span.json());
    }

    @Test
    void theLineStartBodyKeepsTheLengthGuard() {
        int prefix = PLACEHOLDER_PREFIX.length();
        assertEquals(PLACEHOLDER_PREFIX, JsonSpan.find(PLACEHOLDER_PREFIX + jsonOfLength(prefix)).orElseThrow().prefix(),
                "a body as long as its prefix");
        assertTrue(JsonSpan.find(PLACEHOLDER_PREFIX + jsonOfLength(prefix - 1)).isEmpty(), "one character shorter");
    }

    @Test
    void theLineStartBodyMustStartWithinThePrefixCap() {
        var atCap = "[" + "y".repeat(510) + "\n";
        assertEquals(atCap, JsonSpan.find(atCap + jsonOfLength(600)).orElseThrow().prefix(), "starting at index 512");
        assertTrue(JsonSpan.find("[" + "y".repeat(511) + "\n" + jsonOfLength(600)).isEmpty(), "starting at 513");
    }

    @Test
    void aBodyThatDoesNotBeginALineIsNotTried() {
        assertTrue(JsonSpan.find("[a b] see " + jsonOfLength(200)).isEmpty());
    }

    @Test
    void aLogWithBracketedTimestampsIsNotJson() {
        assertTrue(JsonSpan.find("[10:00:01] INFO start\n[10:00:02] INFO done {\"ok\":true}\n[10:00:03] INFO exit")
                .isEmpty());
    }

    @Test
    void threeNdjsonRecordsAreNotJson() {
        assertTrue(JsonSpan.find("{\"e\":1}\n{\"e\":2}\n{\"e\":3}").isEmpty());
    }
}
