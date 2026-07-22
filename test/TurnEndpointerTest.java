import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.voice.TurnEndpointer;

import static services.voice.TurnEndpointer.Event.ENDPOINT;
import static services.voice.TurnEndpointer.Event.NONE;
import static services.voice.TurnEndpointer.Event.SPEECH_STARTED;

/**
 * Adaptive-silence turn endpointing (JCLAW-797). Pure state machine, so every
 * open/close/adaptive/drop path is asserted directly from a timestamped frame
 * stream.
 */
class TurnEndpointerTest extends UnitTest {

    // speechStart=100ms, baseSilence=500ms, maxSilence=1500ms, minUtterance=200ms
    private static TurnEndpointer complete() {
        return new TurnEndpointer(100, 500, 1500, 200, TurnEndpointer.ALWAYS_COMPLETE);
    }

    @Test
    void opensAfterContiguousSpeech() {
        var ep = complete();
        assertEquals(NONE, ep.accept(true, 0));
        assertEquals(NONE, ep.accept(true, 60));
        assertEquals(SPEECH_STARTED, ep.accept(true, 100));
        assertTrue(ep.isSpeaking());
    }

    @Test
    void gapBeforeThresholdResetsTheRun() {
        var ep = complete();
        ep.accept(true, 0);
        ep.accept(true, 60);
        assertEquals(NONE, ep.accept(false, 80)); // gap resets the pre-open run
        assertFalse(ep.isSpeaking());
        assertEquals(NONE, ep.accept(true, 90));  // new run starts at 90
        assertEquals(NONE, ep.accept(true, 150)); // only 60ms in — still below 100
        assertFalse(ep.isSpeaking());
    }

    @Test
    void holdsThroughShortPauseThenClosesAtBaseSilence() {
        var ep = complete();
        ep.accept(true, 0);
        ep.accept(true, 100); // opened
        ep.accept(true, 300); // speech continues; last speech = 300
        assertEquals(NONE, ep.accept(false, 700));   // 400ms pause < 500ms — held
        assertEquals(ENDPOINT, ep.accept(false, 810)); // 510ms pause >= 500ms — closed
        assertFalse(ep.isSpeaking());
    }

    @Test
    void incompleteTurnExtendsToMaxSilence() {
        var ep = new TurnEndpointer(100, 500, 1500, 200, () -> false); // never "complete"
        ep.accept(true, 0);
        ep.accept(true, 100);
        ep.accept(true, 300);
        assertEquals(NONE, ep.accept(false, 810));      // past base(500) but not max(1500)
        assertEquals(ENDPOINT, ep.accept(false, 1810)); // 1510ms pause >= 1500ms
    }

    @Test
    void dropsSubMinimumUtterance() {
        var ep = complete();
        ep.accept(true, 0);
        assertEquals(SPEECH_STARTED, ep.accept(true, 100)); // opens, but only 100ms of speech
        // 550ms of trailing silence exceeds base, but the 100ms span is below the
        // 200ms minimum, so the blip is dropped (no ENDPOINT emitted).
        assertEquals(NONE, ep.accept(false, 650));
        assertFalse(ep.isSpeaking());
    }

    @Test
    void resetAbandonsOpenUtterance() {
        var ep = complete();
        ep.accept(true, 0);
        ep.accept(true, 100);
        assertTrue(ep.isSpeaking());
        ep.reset();
        assertFalse(ep.isSpeaking());
    }

    @Test
    void reopensAfterAnEndpoint() {
        var ep = complete();
        ep.accept(true, 0);
        ep.accept(true, 100);
        ep.accept(true, 300);
        assertEquals(ENDPOINT, ep.accept(false, 810));
        // A fresh utterance opens cleanly on later timestamps.
        assertEquals(NONE, ep.accept(true, 1000));
        assertEquals(SPEECH_STARTED, ep.accept(true, 1100));
    }

    @Test
    void rejectsBaseSilenceGreaterThanMax() {
        assertThrows(IllegalArgumentException.class,
                () -> new TurnEndpointer(100, 1500, 500, 200, TurnEndpointer.ALWAYS_COMPLETE));
    }
}
