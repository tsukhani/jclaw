import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.tts.TtsEngine;
import services.tts.TtsModel;

/**
 * Pure enum-logic checks for the TTS engine/model routing (JCLAW-789/793):
 * engine resolution falls back safely, models stay tagged to their engine, and
 * each engine has a first-listed default. No framework/config touched.
 */
class TtsModelTest extends UnitTest {

    @Test
    void engineResolvesByIdAndFallsBackToDefault() {
        assertSame(TtsEngine.SIDECAR, TtsEngine.byId("sidecar").orElseThrow());
        assertSame(TtsEngine.JVM, TtsEngine.byId("jvm").orElseThrow());
        assertTrue(TtsEngine.byId("nope").isEmpty());
        // null / blank / unknown all coerce to DEFAULT so a stale key never breaks read-aloud.
        assertSame(TtsEngine.DEFAULT, TtsEngine.fromConfigOrDefault(null));
        assertSame(TtsEngine.DEFAULT, TtsEngine.fromConfigOrDefault(""));
        assertSame(TtsEngine.DEFAULT, TtsEngine.fromConfigOrDefault("bogus"));
        assertSame(TtsEngine.JVM, TtsEngine.fromConfigOrDefault("jvm"));
    }

    @Test
    void modelsAreTaggedToTheirEngine() {
        assertFalse(TtsModel.forEngine(TtsEngine.SIDECAR).isEmpty());
        assertFalse(TtsModel.forEngine(TtsEngine.JVM).isEmpty());
        for (var m : TtsModel.forEngine(TtsEngine.SIDECAR)) assertSame(TtsEngine.SIDECAR, m.engine());
        for (var m : TtsModel.forEngine(TtsEngine.JVM)) assertSame(TtsEngine.JVM, m.engine());
    }

    @Test
    void defaultForEngineIsTheFirstListed() {
        assertSame(TtsModel.forEngine(TtsEngine.SIDECAR).get(0), TtsModel.defaultFor(TtsEngine.SIDECAR));
        assertSame(TtsModel.forEngine(TtsEngine.JVM).get(0), TtsModel.defaultFor(TtsEngine.JVM));
    }

    @Test
    void byIdRoundTripsAndHandlesMisses() {
        assertEquals("qwen3-0.6b", TtsModel.byId("qwen3-0.6b").orElseThrow().id());
        assertEquals("piper-en_US-amy-low", TtsModel.byId("piper-en_US-amy-low").orElseThrow().id());
        assertTrue(TtsModel.byId("not-a-model").isEmpty());
        assertTrue(TtsModel.byId(null).isEmpty());
    }
}
