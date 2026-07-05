import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.LocalSidecarDaemon;

/** JCLAW-637: the /health identity-parsing path. */
class LocalSidecarDaemonHealthTest extends UnitTest {

    @Test
    void healthModel_parsesTheServedModel() {
        assertEquals("pyannote/speaker-diarization-community-1",
                LocalSidecarDaemon.healthModel(
                        "{\"status\":\"ok\",\"device\":\"mps\","
                        + "\"model\":\"pyannote/speaker-diarization-community-1\",\"loaded\":true}"));
    }

    @Test
    void healthModel_toleratesMissingFieldAndGarbage() {
        assertNull(LocalSidecarDaemon.healthModel("{\"status\":\"ok\"}"),
                "older sidecars without the field must not be treated as mismatched");
        assertNull(LocalSidecarDaemon.healthModel("not json"),
                "garbage must parse to null, never throw");
    }
}
