import channels.QuotedReply;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/** JCLAW-1296: the quoted block a channel reply carries into the user turn. */
class QuotedReplyTest extends UnitTest {

    @Test
    void theBlockNamesItsSourceAndQuotesEveryLine() {
        assertEquals("[Replying to a reminder this bot sent]\n> line one\n>\n> line three",
                QuotedReply.block("a reminder this bot sent", "  line one\n\nline three\n", false));
    }

    @Test
    void aPartialQuoteSaysItIsOnlyPart() {
        assertEquals("[Quoting part of a message from Bob]\n> ship it",
                QuotedReply.block("a message from Bob", "ship it", true));
    }

    @Test
    void aBodyAtTheCapIsKeptWhole() {
        var body = "a".repeat(16_000);
        assertEquals("[Replying to x]\n> " + body, QuotedReply.block("x", body, false));
    }

    @Test
    void aLongerBodyKeepsItsBeginningAndSaysItWasTruncated() {
        var block = QuotedReply.block("x", "a".repeat(16_000) + "TAIL", false);

        assertTrue(block.startsWith("[Replying to x]\n> " + "a".repeat(16_000) + "\n"), block.substring(0, 40));
        assertFalse(block.contains("TAIL"));
        assertTrue(block.endsWith("\n> [truncated: the original is 16004 characters]"), block.substring(block.length() - 80));
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        var block = QuotedReply.block("x", "a".repeat(15_999) + "😀" + "b", false);

        var kept = block.substring("[Replying to x]\n> ".length(), block.indexOf("\n> [truncated"));
        assertEquals("a".repeat(15_999), kept);
    }

    @Test
    void foldPutsTheBlockAheadOfTheText() {
        assertEquals("[Replying to x]\n> q\n\nmy reply", QuotedReply.fold("my reply", "[Replying to x]\n> q"));
    }

    @Test
    void foldWithoutABlockLeavesTheTextAlone() {
        assertEquals("my reply", QuotedReply.fold("my reply", null));
    }

    @Test
    void aSlashCommandReplyIsNotFolded() {
        assertEquals("/model", QuotedReply.fold("/model", "[Replying to x]\n> q"));
    }

    @Test
    void aStartReplyIsNotFolded() {
        assertEquals("/start", QuotedReply.fold("/start", "[Replying to x]\n> q"));
    }

    @Test
    void aReplyWithNoWordsOfItsOwnIsJustTheBlock() {
        assertEquals("[Replying to x]\n> q", QuotedReply.fold("", "[Replying to x]\n> q"));
    }
}
