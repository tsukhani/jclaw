import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import llm.ReplyJson;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.Duration;
import java.util.Optional;

/** JCLAW-1281: the one extractor every caller reads a model reply's JSON through. */
class ReplyJsonTest extends UnitTest {

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    /** The object is found leniently, strictly, and by the object-shaped reader. */
    private static void findsObject(String expected, String reply) {
        assertEquals(Optional.of(json(expected)), ReplyJson.lenient(reply), "lenient: " + reply);
        assertEquals(Optional.of(json(expected)), ReplyJson.lenientObject(reply).map(o -> (JsonElement) o),
                "lenientObject: " + reply);
        assertEquals(json(expected), ReplyJson.strictObject(reply).orElseThrow().value(), "strict: " + reply);
    }

    private static void findsArray(String expected, String reply) {
        assertEquals(Optional.of(json(expected)), ReplyJson.lenient(reply), "lenient: " + reply);
        assertEquals(Optional.of(json(expected)), ReplyJson.lenientArray(reply).map(a -> (JsonElement) a),
                "lenientArray: " + reply);
    }

    private static void findsNothing(String reply) {
        assertTrue(ReplyJson.lenient(reply).isEmpty(), "lenient: " + reply);
        assertTrue(ReplyJson.lenientObject(reply).isEmpty(), "lenientObject: " + reply);
        assertTrue(ReplyJson.lenientArray(reply).isEmpty(), "lenientArray: " + reply);
        assertTrue(ReplyJson.strictObject(reply).isEmpty(), "strict: " + reply);
    }

    private static boolean endsReply(String reply) {
        return ReplyJson.strictObject(reply).orElseThrow().endsReply();
    }

    @Test
    void aBareValueIsTakenWhole() {
        findsObject("{\"a\":1}", "{\"a\":1}");
        findsArray("[1,2]", "[1,2]");
    }

    @Test
    void aFencedValueIsTakenFromInsideTheFence() {
        findsObject("{\"a\":1}", "```json\n{\"a\":1}\n```");
        findsArray("[1,2]", "```json\n[1,2]\n```");
    }

    @Test
    void leakedReasoningBeforeTheValueIsSkipped() {
        findsObject("{\"a\":1}", "thinking…</think>{\"a\":1}");
        findsArray("[1]", "…</think>[1]");
    }

    @Test
    void aBraceInTheReasoningNeverPairsWithTheAnswer() {
        findsObject("{\"a\":{\"b\":2}}", "if {x} then…</think>{\"a\":{\"b\":2}}");
        findsArray("[[1],[2]]", "if [x then…</think>[[1],[2]]");
        findsObject("{\"a\":1}", "I think { is odd…</think>{\"a\":1}");
    }

    @Test
    void anApostropheInTheReasoningDoesNotSwallowTheAnswer() {
        // Quotes count only inside brackets, so "the user's" cannot open a string that runs to the end.
        findsObject("{\"a\":1}", "The user's reply can't be \"parsed\" otherwise: {\"a\":1}");
    }

    @Test
    void theLastOfTwoValuesWins() {
        findsObject("{\"a\":2}", "{\"a\":1} on second thought {\"a\":2}");
        findsArray("[2]", "[1] on second thought [2]");
    }

    @Test
    void aTrailingValueOfTheWrongShapeIsSkipped() {
        // The footnote is what a caller reading "the last value of any shape" would answer with.
        assertEquals(Optional.of(json("{\"a\":1}")), ReplyJson.lenientObject("{\"a\":1}\nSee note [1] above."));
        assertEquals(Optional.of(json("[0,3,1]")), ReplyJson.lenientArray("[0,3,1] — note: {\"caveat\":true}"));
        assertEquals(Optional.of(json("{\"caveat\":true}")), ReplyJson.lenient("[0,3,1] — note: {\"caveat\":true}"),
                "the any-shape reader still answers with the last value, whatever its shape");
    }

    @Test
    void aValueNestedInALaterOneIsNeverTakenForTheAnswer() {
        findsObject("{\"answer\":{\"text\":\"x\"}}", "{\"answer\":{\"text\":\"x\"}}");
        findsArray("[[1],[2]]", "[[1],[2]]");
        assertTrue(ReplyJson.lenientArray("{\"order\":[1,2,0]}").isEmpty(), "the inner array is not top-level");
    }

    @Test
    void trailingProseIsIgnoredButDoesNotEndTheReply() {
        findsObject("{\"a\":1}", "{\"a\":1}\nHope that helps!");
        findsArray("[1]", "[1]\nHope that helps!");
        assertFalse(endsReply("{\"a\":1}\nHope that helps!"));
    }

    @Test
    void onlyWhitespaceAndOneClosingFenceMayFollowAValueThatEndsTheReply() {
        assertTrue(endsReply("{\"a\":1}"));
        assertTrue(endsReply("  {\"a\":1}\n"));
        assertTrue(endsReply("reasoning</think>{\"a\":1}"));
        assertTrue(endsReply("```json\n{\"a\":1}\n```\n"));
        assertFalse(endsReply("```json\n{\"a\":1}\n```\n```"));
        assertFalse(endsReply("```json\n{\"a\":1}\n```\nDone."));
    }

    @Test
    void aReplyWithoutAnObjectOrArrayYieldsNothing() {
        findsNothing("I can't do that.");
        findsNothing("");
        findsNothing("42");
        findsNothing("\"just a string\"");
        assertTrue(ReplyJson.lenient(null).isEmpty());
        assertTrue(ReplyJson.lenientObject(null).isEmpty());
        assertTrue(ReplyJson.lenientArray(null).isEmpty());
        assertTrue(ReplyJson.strictObject(null).isEmpty());
    }

    @Test
    void aValueCutOffBeforeItClosesIsNotFound() {
        findsNothing("reasoning…</think>{\"text\": \"Lis");
        findsNothing("[2, 0, ");
    }

    @Test
    void bracketsAndEscapedQuotesInsideAStringDoNotEndTheValue() {
        findsObject("{\"t\":\"x}]\\\"{\"}", "Answer: {\"t\":\"x}]\\\"{\"}");
    }

    @Test
    void onlyTheLenientParserTakesGsonsRelaxedSyntax() {
        assertEquals(Optional.of(json("{\"a\":\"b\"}")), ReplyJson.lenientObject("{a: 'b'}"));
        assertEquals(Optional.of(json("[\"x\"]")), ReplyJson.lenientArray("[x]"));
        assertTrue(ReplyJson.strictObject("{a: 'b'}").isEmpty());
    }

    @Test
    void aBracketInsideALenientSingleQuotedStringDoesNotEndTheValue() {
        // JsonParser.parseString took this before the extractor existed, so the lenient scan must too.
        assertEquals(Optional.of(json("{\"t\":\"a}b\"}")), ReplyJson.lenientObject("{'t':'a}b'}"));
    }

    /** The cases {@code MemoryAutoCapture.stripFences} was pinned by, now answered by the extractor. */
    @Test
    void fenceVariants() {
        findsObject("{}", "```json\n{}\n```");
        findsNothing("no fence");
        findsObject("{}", "```\n{}");
    }

    @Test
    void anOpeningThatNeverClosesCostsOnePassRatherThanOnePerBracket() {
        // A per-opening rescan made this quadratic: 1.6 s for an 80 KB reply, and a sanitizer batch is 100 KB.
        var reply = "{ unclosed reasoning ".repeat(20_000) + "{\"a\":1}";
        assertTimeout(Duration.ofSeconds(2), () ->
                assertEquals(Optional.of(json("{\"a\":1}")), ReplyJson.lenientObject(reply)));
    }
}
