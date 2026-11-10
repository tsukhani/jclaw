import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.search.SearchSnippet;

/** {@link SearchSnippet#around}: the excerpt a transcript-search hit carries in place of the message (JF-141). */
class SearchSnippetTest extends UnitTest {

    private static final String GRIN = Character.toString(0x1F600);

    @Test
    void aMissingMessageIsAnEmptySnippet() {
        assertEquals("", SearchSnippet.around(null, "zebra"));
    }

    @Test
    void aMessageAtTheBoundComesBackWholeAndUnmarked() {
        var message = "zebra " + "B".repeat(SearchSnippet.WINDOW_CHARS - 6);
        assertEquals(message, SearchSnippet.around(message, "zebra"));
    }

    @Test
    void aMessageOneOverTheBoundIsCutAndMarked() {
        var message = "zebra " + "B".repeat(SearchSnippet.WINDOW_CHARS - 5);
        assertEquals("zebra " + "B".repeat(194) + "…", SearchSnippet.around(message, "zebra"));
    }

    @Test
    void whitespaceRunsCollapseToOneSpace() {
        assertEquals("a short zebra message",
                SearchSnippet.around("  a\n\n  short\tzebra\r\nmessage  ", "zebra"));
    }

    @Test
    void controlCharactersCollapseLikeWhitespace() {
        assertEquals("zebra tail", SearchSnippet.around("zebra" + (char) 1 + (char) 2 + "tail", "zebra"));
    }

    @Test
    void aPluralQueryCentresOnTheSingularItMatched() {
        var message = "A".repeat(500) + " zebra " + "B".repeat(500);
        assertEquals("…" + "A".repeat(96) + " zebra " + "B".repeat(97) + "…",
                SearchSnippet.around(message, "zebras"));
    }

    @Test
    void aMatchInsideAWordIsPassedOverForOneThatStartsAWord() {
        var message = "A".repeat(100) + " firefox " + "B".repeat(400) + " fox " + "C".repeat(400);
        assertEquals("…" + "B".repeat(97) + " fox " + "C".repeat(98) + "…", SearchSnippet.around(message, "fox"));
    }

    @Test
    void aOneLetterTermMatchesOnlyAWholeWord() {
        var message = "A".repeat(300) + " xylophone " + "B".repeat(300) + " x " + "C".repeat(300);
        assertEquals("…" + "B".repeat(98) + " x " + "C".repeat(99) + "…", SearchSnippet.around(message, "x"));
    }

    @Test
    void theEarliestOfSeveralQueryTermsIsTheCentre() {
        var message = "A".repeat(500) + " zebra " + "B".repeat(300) + " yak " + "C".repeat(300);
        assertEquals("…" + "A".repeat(96) + " zebra " + "B".repeat(97) + "…",
                SearchSnippet.around(message, "yak zebra"));
    }

    @Test
    void aMessageWithNoLiteralMatchShowsItsOpening() {
        assertEquals("A".repeat(200) + "…", SearchSnippet.around("A".repeat(300), "quokka"));
    }

    @Test
    void theClosingCutNeverSplitsASurrogatePair() {
        var message = "a" + GRIN.repeat(150);
        assertEquals("a" + GRIN.repeat(99) + "…", SearchSnippet.around(message, "quokka"));
    }

    @Test
    void theOpeningCutNeverSplitsASurrogatePair() {
        var message = GRIN.repeat(150) + "a";
        assertEquals("…" + GRIN.repeat(99) + "a", SearchSnippet.around(message, "a"));
    }
}
