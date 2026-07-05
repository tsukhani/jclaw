import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.UvProbe;
import services.transcription.DiarizationRouter;
import services.transcription.PyannoteDiarizationClient;
import services.transcription.SpeakerSegment;
import services.transcription.TranscriptionException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JCLAW-614: sidecar-or-error. No fallback exists — prerequisites missing
 * yields an actionable configuration error before any sidecar contact, and
 * sidecar failures surface to the caller (matching the image/video sidecar
 * architecture).
 */
class DiarizationRouterTest extends UnitTest {

    private static final List<SpeakerSegment> STUB_SEGMENTS =
            List.of(new SpeakerSegment(0.0, 2.0, 0));

    private Path audio;

    private static final class StubClient extends PyannoteDiarizationClient {
        boolean called = false;
        final TranscriptionException failure;

        StubClient(TranscriptionException failure) {
            super("http://127.0.0.1:1", new OkHttpClient());
            this.failure = failure;
        }

        @Override
        public DiarizationOutput diarizeRich(Path audioFile, int numSpeakers) {
            called = true;
            if (failure != null) throw failure;
            return new DiarizationOutput(STUB_SEGMENTS, List.of());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        audio = Files.createTempFile("diarization-router-test-", ".wav");
        UvProbe.setForTest(new UvProbe.ProbeResult(true, "forced on in test"));
        ConfigService.set("transcription.diarization.local.hfToken", "hf_token-present");
        ConfigService.set("imagegen.local.hfToken", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        DiarizationRouter.setClientForTest(null);
        UvProbe.setForTest(null);
        ConfigService.delete("transcription.diarization.local.hfToken");
        ConfigService.delete("imagegen.local.hfToken");
        Files.deleteIfExists(audio);
    }

    @Test
    void diarize_usesTheSidecar_whenPrerequisitesAreMet() {
        var stub = new StubClient(null);
        DiarizationRouter.setClientForTest(stub);

        assertEquals(STUB_SEGMENTS, DiarizationRouter.diarize(audio, 2));
        assertTrue(stub.called);
    }

    @Test
    void diarize_reusesImagegenToken_whenDiarizationTokenBlank() {
        ConfigService.set("transcription.diarization.local.hfToken", "");
        ConfigService.set("imagegen.local.hfToken", "hf_from-imagegen");
        var stub = new StubClient(null);
        DiarizationRouter.setClientForTest(stub);

        assertEquals(STUB_SEGMENTS, DiarizationRouter.diarize(audio, -1));
        assertTrue(stub.called);
    }

    @Test
    void diarize_failsFastWithSetupInstructions_beforeAnySidecarContact() {
        var stub = new StubClient(null);
        DiarizationRouter.setClientForTest(stub);

        ConfigService.set("transcription.diarization.local.hfToken", "");
        var e = assertThrows(TranscriptionException.class,
                () -> DiarizationRouter.diarize(audio, -1));
        assertTrue(e.getMessage().contains("Hugging Face token"), e.getMessage());
        assertTrue(e.getMessage().contains("Settings"), e.getMessage());
        assertFalse(stub.called, "prerequisite failures must not touch the sidecar");

        ConfigService.set("transcription.diarization.local.hfToken", "hf_token-present");
        UvProbe.setForTest(new UvProbe.ProbeResult(false, "uv missing"));
        var e2 = assertThrows(TranscriptionException.class,
                () -> DiarizationRouter.diarize(audio, -1));
        assertTrue(e2.getMessage().contains("uv"), e2.getMessage());
        assertFalse(stub.called);
    }

    @Test
    void diarize_surfacesSidecarFailures_noFallback() {
        DiarizationRouter.setClientForTest(
                new StubClient(new TranscriptionException("sidecar exploded")));

        var e = assertThrows(TranscriptionException.class,
                () -> DiarizationRouter.diarize(audio, -1));
        assertTrue(e.getMessage().contains("sidecar exploded"), e.getMessage());
    }
}
