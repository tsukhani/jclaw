import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.UvProbe;
import services.transcription.DiarizationRouter;
import services.transcription.FfmpegProbe;
import services.transcription.PyannoteDiarizationClient;
import services.transcription.SherpaDiarizer;
import services.transcription.TranscriptionException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JCLAW-565: backend selection + fallback. The pyannote side is a stub client
 * (test seam — no sidecar, no HTTP); the sherpa side is never allowed to run
 * natives: ffmpeg is forced unavailable, so any route into SherpaDiarizer
 * fails immediately with its distinctive "ffmpeg is not available" message —
 * which is exactly how these tests *prove* the sherpa path was taken.
 */
class DiarizationRouterTest extends UnitTest {

    private static final List<SherpaDiarizer.SpeakerSegment> STUB_SEGMENTS =
            List.of(new SherpaDiarizer.SpeakerSegment(0.0, 2.0, 0));

    private Path audio;

    /** Stub that returns fixed segments or throws, and records invocation. */
    private static final class StubClient extends PyannoteDiarizationClient {
        boolean called = false;
        final TranscriptionException failure;

        StubClient(TranscriptionException failure) {
            super("http://127.0.0.1:1", new OkHttpClient());
            this.failure = failure;
        }

        @Override
        public List<SherpaDiarizer.SpeakerSegment> diarize(Path audioFile, int numSpeakers) {
            called = true;
            if (failure != null) throw failure;
            return STUB_SEGMENTS;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        audio = Files.createTempFile("diarization-router-test-", ".wav");
        FfmpegProbe.setForTest(new FfmpegProbe.ProbeResult(false, "forced off in test"));
        UvProbe.setForTest(new UvProbe.ProbeResult(true, "forced on in test"));
    }

    @AfterEach
    void tearDown() throws Exception {
        DiarizationRouter.setClientForTest(null);
        FfmpegProbe.setForTest(null);
        UvProbe.setForTest(null);
        ConfigService.delete(DiarizationRouter.BACKEND_KEY);
        ConfigService.delete("transcription.diarization.local.hfToken");
        ConfigService.delete("imagegen.local.hfToken");
        Files.deleteIfExists(audio);
    }

    private void configure(String backend, String hfToken) {
        ConfigService.set(DiarizationRouter.BACKEND_KEY, backend);
        ConfigService.set("transcription.diarization.local.hfToken", hfToken);
        // Most cases isolate from the imagegen-token fallback; the fallback
        // has its own dedicated test below.
        ConfigService.set("imagegen.local.hfToken", "");
    }

    @Test
    void explicitSherpa_neverTouchesPyannote() {
        configure(DiarizationRouter.BACKEND_SHERPA, "hf_token-present");
        var stub = new StubClient(null);
        DiarizationRouter.setClientForTest(stub);

        var e = assertThrows(TranscriptionException.class,
                () -> DiarizationRouter.diarize(audio, 0.3f, -1));

        assertTrue(e.getMessage().contains("ffmpeg"), "sherpa path taken: " + e.getMessage());
        assertFalse(stub.called, "explicit sherpa must not consult the sidecar");
    }

    @Test
    void explicitPyannote_usesSidecar_andSurfacesItsFailures() {
        configure(DiarizationRouter.BACKEND_PYANNOTE, "");
        DiarizationRouter.setClientForTest(new StubClient(null));
        assertEquals(STUB_SEGMENTS, DiarizationRouter.diarize(audio, 0.3f, -1));

        // Explicit choice: failure surfaces, no silent sherpa downgrade.
        DiarizationRouter.setClientForTest(
                new StubClient(new TranscriptionException("sidecar exploded")));
        var e = assertThrows(TranscriptionException.class,
                () -> DiarizationRouter.diarize(audio, 0.3f, -1));
        assertTrue(e.getMessage().contains("sidecar exploded"), e.getMessage());
    }

    @Test
    void auto_usesPyannote_whenUvAndTokenPresent() {
        configure(DiarizationRouter.BACKEND_AUTO, "hf_token-present");
        var stub = new StubClient(null);
        DiarizationRouter.setClientForTest(stub);

        assertEquals(STUB_SEGMENTS, DiarizationRouter.diarize(audio, 0.3f, 2));
        assertTrue(stub.called);
    }

    @Test
    void auto_fallsBackToSherpa_whenSidecarFails() {
        configure(DiarizationRouter.BACKEND_AUTO, "hf_token-present");
        var stub = new StubClient(new TranscriptionException("boom"));
        DiarizationRouter.setClientForTest(stub);

        var e = assertThrows(TranscriptionException.class,
                () -> DiarizationRouter.diarize(audio, 0.3f, -1));

        assertTrue(stub.called, "auto must try the sidecar first");
        assertTrue(e.getMessage().contains("ffmpeg"),
                "fallback must land on the sherpa path: " + e.getMessage());
    }

    @Test
    void auto_reusesImagegenToken_whenDiarizationTokenBlank() {
        // JCLAW-565 follow-up: an operator who already pasted an HF token for
        // gated image models shouldn't have to paste it twice.
        configure(DiarizationRouter.BACKEND_AUTO, "");
        ConfigService.set("imagegen.local.hfToken", "hf_from-imagegen");
        var stub = new StubClient(null);
        DiarizationRouter.setClientForTest(stub);

        assertTrue(DiarizationRouter.pyannoteEligible());
        assertEquals(STUB_SEGMENTS, DiarizationRouter.diarize(audio, 0.3f, -1));
        assertTrue(stub.called, "imagegen token must make the sidecar eligible");
    }

    @Test
    void auto_skipsPyannote_withoutToken_orWithoutUv() {
        configure(DiarizationRouter.BACKEND_AUTO, "");
        var stub = new StubClient(null);
        DiarizationRouter.setClientForTest(stub);
        assertThrows(TranscriptionException.class,
                () -> DiarizationRouter.diarize(audio, 0.3f, -1));
        assertFalse(stub.called, "blank token must keep auto on sherpa");
        assertFalse(DiarizationRouter.pyannoteEligible());

        configure(DiarizationRouter.BACKEND_AUTO, "hf_token-present");
        UvProbe.setForTest(new UvProbe.ProbeResult(false, "uv missing"));
        assertThrows(TranscriptionException.class,
                () -> DiarizationRouter.diarize(audio, 0.3f, -1));
        assertFalse(stub.called, "missing uv must keep auto on sherpa");
        assertFalse(DiarizationRouter.pyannoteEligible());
    }
}
