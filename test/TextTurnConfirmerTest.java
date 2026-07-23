import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.voice.TextTurnConfirmer;

import java.util.concurrent.atomic.AtomicReference;

/**
 * TextTurnConfirmer (JCLAW-845): the mid-clause / finished-thought heuristic that
 * decides whether a trailing pause should end the turn now (baseSilenceMs) or
 * hold it open (maxSilenceMs).
 */
class TextTurnConfirmerTest extends UnitTest {

    @Test
    void blankOrNullIsComplete() {
        // No transcript (audio-native path, or nothing spoken yet) → degrade to
        // fixed-silence endpointing.
        assertTrue(TextTurnConfirmer.looksComplete(null));
        assertTrue(TextTurnConfirmer.looksComplete(""));
        assertTrue(TextTurnConfirmer.looksComplete("   "));
    }

    @Test
    void sentencePunctuationIsComplete() {
        assertTrue(TextTurnConfirmer.looksComplete("What's the capital of France?"));
        assertTrue(TextTurnConfirmer.looksComplete("Set a reminder."));
        assertTrue(TextTurnConfirmer.looksComplete("Wow!"));
    }

    @Test
    void endingOnFunctionWordIsIncomplete() {
        assertFalse(TextTurnConfirmer.looksComplete("I was thinking about"));    // preposition
        assertFalse(TextTurnConfirmer.looksComplete("Can you remind me to"));     // to
        assertFalse(TextTurnConfirmer.looksComplete("I want to go to the"));      // article
        assertFalse(TextTurnConfirmer.looksComplete("Tell me a story because"));  // conjunction
        assertFalse(TextTurnConfirmer.looksComplete("um"));                       // filler
    }

    @Test
    void endingOnContentWordIsComplete() {
        assertTrue(TextTurnConfirmer.looksComplete("What is the weather today"));
        assertTrue(TextTurnConfirmer.looksComplete("Name three primary colors"));
        assertTrue(TextTurnConfirmer.looksComplete("How many continents are there"));
    }

    @Test
    void handlesTrailingPunctuationAndCase() {
        assertFalse(TextTurnConfirmer.looksComplete("I was thinking about,")); // comma after a preposition
        assertFalse(TextTurnConfirmer.looksComplete("I was thinking AND"));     // uppercase conjunction
    }

    @Test
    void instanceFormReadsTheSupplierLive() {
        var ref = new AtomicReference<String>("remind me to");
        var confirmer = new TextTurnConfirmer(ref::get);
        assertFalse(confirmer.looksComplete());  // ends on "to" → hold

        ref.set("remind me to call the dentist");
        assertTrue(confirmer.looksComplete());   // ends on "dentist" → complete

        ref.set(null);
        assertTrue(confirmer.looksComplete());   // no transcript → complete
    }
}
