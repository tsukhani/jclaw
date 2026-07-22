import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.tts.TtsSentenceChunker;

import java.util.List;

/**
 * Chunking behaviour for streaming TTS (JCLAW-790): blank input is empty, short
 * fragments merge, run-on sentences are hard-wrapped under the cap, paragraphs
 * split, and words survive in reading order.
 */
class TtsSentenceChunkerTest extends UnitTest {

    @Test
    void blankInputYieldsNoChunks() {
        assertTrue(TtsSentenceChunker.chunk(null).isEmpty());
        assertTrue(TtsSentenceChunker.chunk("").isEmpty());
        assertTrue(TtsSentenceChunker.chunk("   \n  ").isEmpty());
    }

    @Test
    void shortTextIsASingleChunk() {
        assertEquals(List.of("Hello there."), TtsSentenceChunker.chunk("Hello there."));
    }

    @Test
    void shortSentencesMergeRatherThanOnePerSentence() {
        var text = "One. Two. Three. Four. Five. Six. Seven. Eight. Nine. Ten. Eleven. Twelve.";
        var chunks = TtsSentenceChunker.chunk(text);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() < 12, "short sentences should merge: " + chunks);
        for (var c : chunks) assertTrue(c.length() <= TtsSentenceChunker.MAX_CHARS, c);
    }

    @Test
    void runOnSentenceIsHardWrappedUnderTheCap() {
        var giant = ("A " + "word ".repeat(200)).trim() + ".";  // ~1000 chars, no sentence breaks
        var chunks = TtsSentenceChunker.chunk(giant);
        assertTrue(chunks.size() > 1, "a run-on sentence must be hard-wrapped");
        for (var c : chunks) assertTrue(c.length() <= TtsSentenceChunker.MAX_CHARS, "len=" + c.length());
    }

    @Test
    void paragraphsDoNotMergeAcrossTheBreak() {
        var chunks = TtsSentenceChunker.chunk("First paragraph text here.\n\nSecond paragraph text here.");
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).startsWith("First"));
        assertTrue(chunks.get(1).startsWith("Second"));
    }

    @Test
    void wordsSurviveInReadingOrder() {
        var text = "Alpha bravo charlie. Delta echo foxtrot. Golf hotel india juliet.";
        var joined = String.join(" ", TtsSentenceChunker.chunk(text));
        for (var w : List.of("Alpha", "bravo", "charlie", "Delta", "echo", "foxtrot",
                "Golf", "hotel", "india", "juliet")) {
            assertTrue(joined.contains(w), "missing '" + w + "' in: " + joined);
        }
    }
}
