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

    @Test
    void onlyABooleanTrueMarksAResultAsAnError() {
        assertTrue(parse("{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"bad\"}]}").isError());
        assertFalse(parse("{\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}").isError());
        for (var value : new String[] {"null", "{}", "[]", "\"true\"", "1"}) {
            var result = parse("{\"isError\":" + value + ",\"content\":[{\"type\":\"text\",\"text\":\"ran\"}]}");
            assertFalse(result.isError(), "isError " + value);
            assertEquals("ran", result.content(), "isError " + value);
        }
    }

    @Test
    void aPartWithANullOrNonStringFieldStillFlattensInsteadOfFailingTheCall() {
        assertEquals("[image: unknown][resource: ?]ok", parse("{\"content\":[{\"type\":\"text\",\"text\":null},"
                + "{\"type\":\"image\",\"mimeType\":null},{\"type\":\"resource\",\"resource\":{\"uri\":{}}},"
                + "{\"type\":\"text\",\"text\":\"ok\"}]}").content());
        assertEquals("{\"type\":null,\"n\":1}", parse("{\"content\":[{\"type\":null,\"n\":1}]}").content(),
                "a part of no known type is kept as its JSON");
    }

    @Test
    void aStructuredOnlyResultReachesTheModelWholeBeyondTheViewDataCap() {
        // Compression bounds it later and ccr_retrieve returns the original; a cut here would lose the rest.
        var json = "{\"s\":\"" + "x".repeat(CallToolResult.VIEW_DATA_MAX_CHARS) + "\"}";
        var result = parse("{\"content\":[],\"structuredContent\":" + json + "}");
        assertEquals(json, result.content());
        assertEquals(json, result.contentWithViewData(), "the view-data rule does not add it twice");
    }

    private static final String VIEW_DATA = "\n\nStructured content (the data the tool's view draws):\n";

    /** {@code {"s":"…"}} whose JSON is exactly {@code length} characters. */
    private static CallToolResult viewResult(String text, int length) {
        return parse("{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"structuredContent\":{\"s\":\""
                + "x".repeat(length - 8) + "\"}}");
    }

    @Test
    void aViewToolsPlaceholderTextIsFollowedByTheDataItsViewDraws() {
        assertEquals("[Rendered Prefab UI]" + VIEW_DATA + "{\"flights\":[{\"price\":271}]}",
                parse("{\"content\":[{\"type\":\"text\",\"text\":\"[Rendered Prefab UI]\"}],"
                        + "\"structuredContent\":{\"flights\":[{\"price\":271}]}}").contentWithViewData());
    }

    @Test
    void structuredContentTheSafetyNetAlreadyMadeTheTextIsNotRepeated() {
        assertEquals("{\"rows\":[1]}", parse("{\"content\":[],\"structuredContent\":{\"rows\":[1]}}").contentWithViewData());
    }

    @Test
    void withoutStructuredContentAViewToolKeepsItsText() {
        assertEquals("3 results", parse("{\"content\":[{\"type\":\"text\",\"text\":\"3 results\"}]}").contentWithViewData());
        assertEquals("3 results", parse("{\"content\":[{\"type\":\"text\",\"text\":\"3 results\"}],\"structuredContent\":null}")
                .contentWithViewData());
    }

    @Test
    void structuredContentOfExactlyTheCapIsWholeAndOneMoreIsCutAndSaysSo() {
        var whole = viewResult("ok", CallToolResult.VIEW_DATA_MAX_CHARS);
        assertEquals("ok" + VIEW_DATA + "{\"s\":\"" + "x".repeat(15_992) + "\"}", whole.contentWithViewData());
        var over = viewResult("ok", CallToolResult.VIEW_DATA_MAX_CHARS + 1);
        assertEquals("ok" + VIEW_DATA + "{\"s\":\"" + "x".repeat(15_993) + "\""
                + "\n[structuredContent truncated: showed the first 16,000 of 16,001 characters]", over.contentWithViewData());
    }

    @Test
    void textOfAtMostTheShortLimitGetsTheDataAndLongerTextDoesNot() {
        var limit = CallToolResult.VIEW_SHORT_TEXT_MAX_CHARS;
        var shortText = "y".repeat(limit);
        assertEquals(shortText + VIEW_DATA + "{\"n\":1}",
                parse("{\"content\":[{\"type\":\"text\",\"text\":\"" + shortText + "\"}],\"structuredContent\":{\"n\":1}}")
                        .contentWithViewData());
        var longText = "y".repeat(limit + 1);
        assertEquals(longText,
                parse("{\"content\":[{\"type\":\"text\",\"text\":\"" + longText + "\"}],\"structuredContent\":{\"n\":1}}")
                        .contentWithViewData(), "a server that sends full text pays nothing extra");
    }

    @Test
    void textThatIsTheSameJsonIsNotRepeatedHoweverItIsFormatted() {
        // The MCP tools spec asks a tool with structuredContent to send the same JSON as text too.
        assertEquals("{\n  \"rows\": [1, 2]\n}",
                parse("{\"content\":[{\"type\":\"text\",\"text\":\"{\\n  \\\"rows\\\": [1, 2]\\n}\"}],"
                        + "\"structuredContent\":{\"rows\":[1,2]}}").contentWithViewData());
        assertEquals("{\"rows\":[9]}" + VIEW_DATA + "{\"rows\":[1,2]}",
                parse("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"rows\\\":[9]}\"}],\"structuredContent\":{\"rows\":[1,2]}}")
                        .contentWithViewData(), "JSON text with other data still gets the view's data");
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        // The emoji's high surrogate sits at index 15,999, the last one the cap would keep.
        var result = parse("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"structuredContent\":{\"s\":\""
                + "x".repeat(15_993) + "😀" + "x".repeat(10) + "\"}}");
        assertEquals("ok" + VIEW_DATA + "{\"s\":\"" + "x".repeat(15_993)
                + "\n[structuredContent truncated: showed the first 15,999 of 16,013 characters]", result.contentWithViewData());
    }

    @Test
    void jclawsNoteComesAfterTheServersTextAndAfterAViewsData() {
        var result = CallToolResult.fromResultObject(JsonParser.parseString(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"structuredContent\":{\"rows\":[1]}}").getAsJsonObject());
        assertEquals("ok", result.noted(result.content()), "no note, nothing added");
        var noted = result.withNote("JClaw: one.").withNote("JClaw: two.");
        assertEquals("ok\n\nJClaw: one.\nJClaw: two.", noted.noted(noted.content()));
        assertEquals("ok" + VIEW_DATA + "{\"rows\":[1]}\n\nJClaw: one.\nJClaw: two.", noted.noted(noted.contentWithViewData()));
        assertEquals("JClaw: one.", new CallToolResult("", false).withNote("JClaw: one.").noted(""), "an empty result is the note alone");
        assertEquals(result.forView(), noted.forView(), "a view gets the server's result, never JClaw's note");
    }
}
