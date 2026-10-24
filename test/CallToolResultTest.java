import com.google.gson.JsonParser;
import mcp.CallToolResult;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/** What the model reads from an MCP {@code tools/call} result, and what an MCP Apps view receives. */
class CallToolResultTest extends UnitTest {

    private static CallToolResult parse(String json) {
        return CallToolResult.fromResultObject(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void aResultWithNoContentGivesTheModelItsStructuredContentAsJson() {
        assertEquals("{\"organic\":[{\"title\":\"Cats\",\"rank\":1}]}",
                parse("{\"content\":[],\"structuredContent\":{\"organic\":[{\"title\":\"Cats\",\"rank\":1}]}}").content());
        assertEquals("{\"rows\":[]}", parse("{\"structuredContent\":{\"rows\":[]}}").content(), "no content key at all");
    }

    @Test
    void blankTextCountsAsNoText() {
        assertEquals("{\"n\":1}", parse("{\"content\":[{\"type\":\"text\",\"text\":\" \\n\"},{\"type\":\"text\",\"text\":\"\"}],"
                + "\"structuredContent\":{\"n\":1}}").content());
    }

    @Test
    void aResultWithTextKeepsItsTextBesideStructuredContent() {
        assertEquals("3 results", parse("{\"content\":[{\"type\":\"text\",\"text\":\"3 results\"}],"
                + "\"structuredContent\":{\"rows\":[1,2,3]}}").content());
        assertEquals(" x", parse("{\"content\":[{\"type\":\"text\",\"text\":\" x\"}],\"structuredContent\":{}}").content(),
                "one non-blank character is text, kept as sent");
    }

    @Test
    void aNonTextPartIsNotBlankSoItsPlaceholderStays() {
        assertEquals("[image: image/png]", parse("{\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"AA==\"}],"
                + "\"structuredContent\":{\"n\":1}}").content());
    }

    @Test
    void withNeitherTextNorStructuredContentTheTextStaysEmpty() {
        assertEquals("", parse("{\"content\":[]}").content());
        assertEquals("", parse("{\"content\":[],\"structuredContent\":null}").content());
    }

    @Test
    void anErrorResultWithOnlyStructuredContentStillGivesItsJson() {
        var result = parse("{\"isError\":true,\"content\":[],\"structuredContent\":{\"error\":\"quota\"}}");
        assertEquals("{\"error\":\"quota\"}", result.content());
        assertTrue(result.isError());
    }

    @Test
    void theViewGetsContentStructuredContentMetaAndIsErrorAndNothingElse() {
        var result = parse("{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                + "\"structuredContent\":{\"rows\":[1]},\"_meta\":{\"k\":\"v\"},\"isError\":true,\"extra\":1}");
        assertEquals("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"structuredContent\":{\"rows\":[1]},"
                + "\"_meta\":{\"k\":\"v\"},\"isError\":true}", result.forView().toString());
    }

    @Test
    void binaryContentReachesTheViewAsATypeAndSizeWhileTextAndStructuredContentStayWhole() {
        var json = "{\"content\":[{\"type\":\"text\",\"text\":\"chart\"},"
                + "{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"iVBORw0KGgo=\"},"
                + "{\"type\":\"audio\",\"data\":\"QUJD\"},"
                + "{\"type\":\"resource\",\"resource\":{\"uri\":\"x://r.pdf\",\"mimeType\":\"application/pdf\",\"blob\":\"QUJDRA==\"}},"
                + "{\"type\":\"resource\",\"resource\":{\"uri\":\"x://t\",\"text\":\"kept\"}}],"
                + "\"structuredContent\":{\"png\":\"iVBORw0KGgo=\"},\"_meta\":{\"k\":\"v\"}}";
        var result = parse(json);
        assertEquals("{\"content\":[{\"type\":\"text\",\"text\":\"chart\"},"
                + "{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"[image/png, 8 bytes]\"},"
                + "{\"type\":\"audio\",\"data\":\"[audio, 3 bytes]\"},"
                + "{\"type\":\"resource\",\"resource\":{\"uri\":\"x://r.pdf\",\"mimeType\":\"application/pdf\",\"blob\":\"[application/pdf, 4 bytes]\"}},"
                + "{\"type\":\"resource\",\"resource\":{\"uri\":\"x://t\",\"text\":\"kept\"}}],"
                + "\"structuredContent\":{\"png\":\"iVBORw0KGgo=\"},\"_meta\":{\"k\":\"v\"},\"isError\":false}",
                result.forView().toString());
        assertEquals(JsonParser.parseString(json), result.raw(), "the result itself keeps its data");
    }

    @Test
    void aBareResultReachesTheViewWithEmptyContentAndIsErrorFalse() {
        assertEquals("{\"content\":[],\"isError\":false}", parse("{\"structuredContent\":null}").forView().toString());
        assertEquals("{\"content\":[],\"isError\":false}", new CallToolResult("ok", false).forView().toString());
    }
}
