import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.AsrModelStore;
import services.transcription.DiarizeModelStore;
import services.transcription.ModelPrefetchStore;
import services.transcription.ModelPrefetchStore.State;

/**
 * Pins the consolidated "model status + prefetch" state machine (JCLAW DRY
 * remediation): the {@link State} enum + its {@link State#wireName()} projection
 * now live once on {@link ModelPrefetchStore}, so the ASR and diarize panels
 * can't drift apart. The wire-vocabulary assertions guard the frontend contract
 * at its single source; the same-class assertion guards against re-introducing
 * a per-store {@code State} (which would silently shadow the shared one and
 * revive the drift risk).
 */
class ModelPrefetchStoreTest extends UnitTest {

    @Test
    void projectsInternalStatesToFrontendVocabularyAtTheSingleSource() {
        assertEquals("ABSENT", State.NOT_DOWNLOADED.wireName());
        assertEquals("AVAILABLE", State.DOWNLOADED.wireName());
        assertEquals("DOWNLOADING", State.DOWNLOADING.wireName());
        assertEquals("ERROR", State.ERROR.wireName());
        assertEquals("UNAVAILABLE", State.UNAVAILABLE.wireName());
    }

    @Test
    void bothStoresShareTheOneBaseStateEnum() {
        assertSame(ModelPrefetchStore.State.class, AsrModelStore.State.class,
                "AsrModelStore.State must be the shared base enum, not a shadowing copy");
        assertSame(ModelPrefetchStore.State.class, DiarizeModelStore.State.class,
                "DiarizeModelStore.State must be the shared base enum, not a shadowing copy");
    }
}
