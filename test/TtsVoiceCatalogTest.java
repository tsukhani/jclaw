import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.tts.TtsVoiceCatalog;

/**
 * TtsVoiceCatalog (JCLAW-846): curated per-model voice presets. Kokoro and Qwen3
 * have entries; models without a preset picker (Piper single-voice, Chatterbox
 * reference-clip cloning, unmapped sherpa speaker indices) return an empty list
 * so the Settings UI shows no voice dropdown.
 */
class TtsVoiceCatalogTest extends UnitTest {

    @Test
    void kokoroHasNamedVoicesWithNonBlankIdsAndLabels() {
        var voices = TtsVoiceCatalog.voicesFor("kokoro");
        assertFalse(voices.isEmpty());
        // ids are the raw Kokoro voice names passed straight to the engine (validated live).
        assertTrue(voices.stream().anyMatch(v -> v.id().equals("bm_george")));
        for (var v : voices) {
            assertFalse(v.id().isBlank());
            assertFalse(v.label().isBlank());
        }
    }

    @Test
    void qwen3VoicesAreNumericSeedsSharedAcrossVariants() {
        var voices = TtsVoiceCatalog.voicesFor("qwen3-0.6b");
        assertFalse(voices.isEmpty());
        for (var v : voices) assertTrue(v.id().chars().allMatch(Character::isDigit));
        // Both Qwen3 variants offer the same seed set.
        assertEquals(voices, TtsVoiceCatalog.voicesFor("qwen3-0.6b-4bit"));
    }

    @Test
    void modelsWithoutPresetsReturnEmpty() {
        assertTrue(TtsVoiceCatalog.voicesFor("piper-en_US-amy-low").isEmpty());
        assertTrue(TtsVoiceCatalog.voicesFor("chatterbox").isEmpty());
        assertTrue(TtsVoiceCatalog.voicesFor("kokoro-multi-lang-v1_0").isEmpty()); // sherpa indices unmapped
        assertTrue(TtsVoiceCatalog.voicesFor("not-a-model").isEmpty());
        assertTrue(TtsVoiceCatalog.voicesFor(null).isEmpty());
    }
}
