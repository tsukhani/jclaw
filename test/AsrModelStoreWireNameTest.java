import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.AsrModelStore.State;

/**
 * Guards the internal-state → wire-status projection the Settings frontend
 * consumes. JCLAW-650 renamed the internal enum (ABSENT→NOT_DOWNLOADED,
 * AVAILABLE→DOWNLOADED) but left {@code ApiTranscriptionController} emitting the
 * raw {@code name()}, so the Download button and Ready badge — which key off the
 * old ABSENT/AVAILABLE vocabulary — stopped rendering. {@link State#wireName()}
 * restores the contract; this pins it. Keep in lockstep with the
 * {@code TranscriptionModelStatus} union in SettingsTranscriptionPanel.vue.
 */
class AsrModelStoreWireNameTest extends UnitTest {

    @Test
    void projectsInternalStatesToFrontendVocabulary() {
        assertEquals("ABSENT", State.NOT_DOWNLOADED.wireName());
        assertEquals("AVAILABLE", State.DOWNLOADED.wireName());
        assertEquals("DOWNLOADING", State.DOWNLOADING.wireName());
        assertEquals("ERROR", State.ERROR.wireName());
        assertEquals("UNAVAILABLE", State.UNAVAILABLE.wireName());
    }

    @Test
    void everyStateHasANonBlankWireName() {
        for (var s : State.values()) {
            assertNotNull(s.wireName(), "wireName for " + s);
            assertFalse(s.wireName().isBlank(), "wireName blank for " + s);
        }
    }
}
