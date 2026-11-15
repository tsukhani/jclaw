import com.google.gson.JsonPrimitive;
import okhttp3.MediaType;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.secrets.BodyFields;
import services.secrets.BodyFields.RawField;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** JF-442: the body readers the secrets proxy judges a sign-in by, each read as a server reads it. */
class BodyFieldsTest extends UnitTest {

    private static final MediaType FORM_DATA = MediaType.get("multipart/form-data; boundary=B");

    private static byte[] latin1(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void eachNamedPartIsReadAndANamelessOneIsReadPast() {
        var body = """
                --B\r
                Content-Disposition: form-data; name="a"\r
                \r
                alpha\r
                --B\r
                Content-Type: text/plain\r
                \r
                no name\r
                --B\r
                content-disposition: form-data; NAME=b\r
                \r
                beta\r
                --B--\r
                """;
        assertEquals(List.of(Map.entry("a", "alpha"), Map.entry("b", "beta")),
                BodyFields.multipartParts(latin1(body), FORM_DATA));
    }

    @Test
    void aPreambleIsSkippedAndALastPartWithNoClosingDelimiterRunsToTheEnd() {
        var body = "preamble\r\n--B\r\nContent-Disposition: form-data; name=a\r\n\r\nalpha to the end";
        assertEquals(List.of(Map.entry("a", "alpha to the end")), BodyFields.multipartParts(latin1(body), FORM_DATA));
    }

    @Test
    void aHeadMayEndInABareBlankLineAndAPartMayHaveNoHead() {
        var bareBlank = "--B\r\nContent-Disposition: form-data; name=a\n\nalpha\r\n--B--";
        assertEquals(List.of(Map.entry("a", "alpha")), BodyFields.multipartParts(latin1(bareBlank), FORM_DATA));
        var noHead = "--B\r\n\r\nheadless\r\n--B\r\nContent-Disposition: form-data; name=a\r\n\r\nalpha\r\n--B--";
        assertEquals(List.of(Map.entry("a", "alpha")), BodyFields.multipartParts(latin1(noHead), FORM_DATA));
    }

    @Test
    void aPartsContentComesAsLatin1() {
        var body = "--B\r\nContent-Disposition: form-data; name=a\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        var content = "é".getBytes(StandardCharsets.UTF_8);
        var whole = new byte[body.length + content.length];
        System.arraycopy(body, 0, whole, 0, body.length);
        System.arraycopy(content, 0, whole, body.length, content.length);
        assertEquals(List.of(Map.entry("a", "\u00c3\u00a9")), BodyFields.multipartParts(whole, FORM_DATA));
    }

    @Test
    void aBodyWithNoBoundaryNoStartingDelimiterOrADelimiterAfterABareLineFeedCannotBeRead() {
        var part = latin1("--B\r\nContent-Disposition: form-data; name=a\r\n\r\nalpha\r\n--B--");
        assertNull(BodyFields.multipartParts(part, MediaType.get("multipart/form-data")));
        assertNull(BodyFields.multipartParts(part, MediaType.get("multipart/form-data; boundary=\"\"")));
        assertNull(BodyFields.multipartParts(latin1("no delimiter at all"), FORM_DATA));
        var bareLf = """
                --B\r
                Content-Disposition: form-data; name=a\r
                \r
                alpha
                --B\r
                Content-Disposition: form-data; name=b\r
                \r
                beta\r
                --B--""";
        assertNull(BodyFields.multipartParts(latin1(bareLf), FORM_DATA));
    }

    @Test
    void aHeadThatNeverEndsEndsTheReadingWithThePartsBeforeIt() {
        var body = "--B\r\nContent-Disposition: form-data; name=a\r\n\r\nalpha\r\n--B\r\nContent-Disposition: x";
        assertEquals(List.of(Map.entry("a", "alpha")), BodyFields.multipartParts(latin1(body), FORM_DATA));
    }

    @Test
    void multipartValuesAreThoseOfTheNamedPartsInOrder() {
        var body = latin1("""
                --B\r
                Content-Disposition: form-data; name=a\r
                \r
                one\r
                --B\r
                Content-Disposition: form-data; name=b\r
                \r
                other\r
                --B\r
                Content-Disposition: form-data; name=a\r
                \r
                two\r
                --B--""");
        assertEquals(List.of("one", "two"), BodyFields.multipartValues(body, FORM_DATA, "a"));
        assertEquals(List.of(), BodyFields.multipartValues(body, FORM_DATA, "c"));
        assertNull(BodyFields.multipartValues(body, MediaType.get("multipart/form-data"), "a"));
    }

    @Test
    void urlencodedValuesComeAsWrittenUnderANameMatchedDecoded() {
        assertEquals(List.of("1", "x%20y"), BodyFields.urlencodedValues(latin1("a=1&b=2&%61=x%20y"), "a"));
        assertEquals(List.of(""), BodyFields.urlencodedValues(latin1("a&=z&a="), "a"));
        assertEquals(List.of("v"), BodyFields.urlencodedValues(latin1("%zz=v"), "%zz"));
        assertEquals(List.of(), BodyFields.urlencodedValues(latin1(""), "a"));
    }

    @Test
    void aFormFieldIsDecodedAsAServerDecodesItOrKeptWhenItsEscapesAreMalformed() {
        assertEquals("a b!", BodyFields.formDecoded("a+b%21"));
        assertEquals("%z", BodyFields.formDecoded("%z"));
    }

    @Test
    void rawJsonFieldsGiveEachTopLevelStringAsWrittenAndAnyOtherValueAsNull() {
        var text = "{\"a\":\"x\\\"y\",\"b\":{\"c\":\"d\"},\"e\":1.5,\"f\":[1,\"g\"],\"h\":null,\"i\" : \"z\" ,\"j\":true}";
        assertEquals(List.of(new RawField("a", "x\\\"y"), new RawField("b", null), new RawField("e", null),
                new RawField("f", null), new RawField("h", null), new RawField("i", "z"), new RawField("j", null)),
                BodyFields.rawJsonFields(text));
    }

    @Test
    void aRawJsonFieldsNameIsDecodedAndItsValueIsNot() {
        assertEquals(List.of(new RawField("a", "\\u0076")), BodyFields.rawJsonFields("{\"\\u0061\":\"\\u0076\"}"));
    }

    @Test
    void jsonFieldsReadOneStrictObjectInOrderWithRepeatsKept() {
        assertEquals(List.of(Map.entry("a", new JsonPrimitive("1")), Map.entry("a", new JsonPrimitive(2))),
                BodyFields.jsonFields("{\"a\":\"1\",\"a\":2}"));
        assertEquals(List.of(), BodyFields.jsonFields("{}"));
    }

    @Test
    void textThatIsNoSingleStrictObjectHasNoJsonFields() {
        for (var text : List.of("[1]", "\"a\"", "{\"a\":1} {}", "{'a':1}", "{a:1}", "{\"a\":1,}", "", "{")) {
            assertNull(BodyFields.jsonFields(text), text);
        }
    }

    @Test
    void utf8ReadsValidTextAndRefusesMalformedBytes() {
        assertEquals("é ok", BodyFields.utf8("é ok".getBytes(StandardCharsets.UTF_8)));
        assertNull(BodyFields.utf8(new byte[] {(byte) 0xc3}));
        assertNull(BodyFields.utf8(new byte[] {(byte) 0xff, 'a'}));
    }

    @Test
    void onlyAFormDataTypeIsMultipartWhetherOrNotItParses() {
        assertTrue(BodyFields.multipart(MediaType.get("Multipart/Form-Data; boundary=B")));
        assertFalse(BodyFields.multipart(MediaType.get("multipart/mixed; boundary=B")));
        assertTrue(BodyFields.namesMultipart(" MULTIPART/form-data; boundary=\"unclosed"));
        assertFalse(BodyFields.namesMultipart("application/x-www-form-urlencoded"));
    }
}
