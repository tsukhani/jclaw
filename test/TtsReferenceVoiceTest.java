import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.tts.TtsModel;

/**
 * Reference-clip voice cloning (JCLAW-865). Covers the two pure decisions —
 * which models clone, and which uploads are worth accepting. Storage itself is
 * filesystem work exercised through the controller.
 */
class TtsReferenceVoiceTest extends UnitTest {

    // ===== which models clone =====

    @Test
    void cloningModelsAreExactlyThoseWithoutNamedVoices() {
        // The correspondence is the point: a model either offers presets or clones.
        // Chatterbox and Qwen3-TTS render an empty Voice picker, which is why a clip
        // is their only means of choosing a speaker.
        assertTrue(TtsModel.CHATTERBOX.supportsCloning());
        assertTrue(TtsModel.QWEN3_06B.supportsCloning());
        assertTrue(TtsModel.QWEN3_06B_4BIT.supportsCloning());

        assertFalse(TtsModel.KOKORO_MLX.supportsCloning(), "Kokoro selects by name");
        assertFalse(TtsModel.PIPER_AMY.supportsCloning(), "Piper is single-voice");
        assertFalse(TtsModel.KOKORO_SHERPA.supportsCloning(), "sherpa Kokoro selects by name");
    }

    @Test
    void unknownModelIdDoesNotClone() {
        // Guards the router: an unrecognized id must not cause a clip to be sent to
        // an engine that cannot use it.
        assertFalse(TtsModel.cloningById("not-a-model"));
        assertFalse(TtsModel.cloningById(null));
        assertTrue(TtsModel.cloningById("chatterbox"));
    }

    // ===== upload validation =====

    @Test
    void acceptsTheAudioFormatsTheEnginesCanRead() {
        assertNull(services.tts.TtsReferenceVoice.validate("clip.wav", 1024));
        assertNull(services.tts.TtsReferenceVoice.validate("clip.mp3", 1024));
        assertNull(services.tts.TtsReferenceVoice.validate("CLIP.FLAC", 1024), "extension check is case-insensitive");
    }

    @Test
    void rejectsFormatsThatWouldFailInsidePython() {
        // Caught at upload, where the operator still has the file and can act, rather
        // than deep in the engine on some later read-aloud.
        assertNotNull(services.tts.TtsReferenceVoice.validate("clip.txt", 1024));
        assertNotNull(services.tts.TtsReferenceVoice.validate("clip", 1024), "no extension");
        assertNotNull(services.tts.TtsReferenceVoice.validate(null, 1024));
    }

    @Test
    void rejectsEmptyAndOversizedClips() {
        assertNotNull(services.tts.TtsReferenceVoice.validate("clip.wav", 0));
        // Cloning wants a few seconds of speech; a huge file is a mistake, not
        // better input.
        assertNotNull(services.tts.TtsReferenceVoice.validate("clip.wav", 50L * 1024 * 1024));
    }
}
