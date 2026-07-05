import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.transcription.DiarizationCache;
import services.transcription.DiarizationPipeline;
import services.transcription.DiarizationRouter;
import services.transcription.FfmpegProbe;
import services.transcription.PyannoteDiarizationClient;
import services.transcription.SpeakerNamer;
import services.transcription.SpeakerSegment;
import services.transcription.TranscriptionException;
import services.transcription.WhisperJniTranscriber;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JCLAW-639: pins the JCLAW-628 stage contract that every stage test
 * individually misses — the identification-first halt, the allowAnonymous
 * waiver, the cache-hit paths and the cacheable=false REST posture
 * (JCLAW-636). Strategy: the router is stubbed (setClientForTest), ffmpeg is
 * forced OFF so any attempt to actually transcribe THROWS — meaning "the
 * pipeline returned instead of throwing" is itself proof no full-recording
 * inference ran — and where a transcript is needed it is pre-seeded into the
 * cache, which readTranscript serves without touching whisper.
 */
class DiarizationPipelineTest extends UnitTest {

    private static final List<SpeakerSegment> TWO_SPEAKERS = List.of(
            new SpeakerSegment(0.0, 2.0, 0), new SpeakerSegment(2.0, 4.0, 1));

    private Path audio;
    private Path voicesRoot;
    private StubClient stub;

    private static final class StubClient extends PyannoteDiarizationClient {
        int calls = 0;

        StubClient() {
            super("http://127.0.0.1:1", new OkHttpClient());
        }

        @Override
        public DiarizationOutput diarizeRich(Path audioFile, int numSpeakers) {
            calls++;
            return new DiarizationOutput(TWO_SPEAKERS, List.of());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        audio = Files.createTempDirectory("pipeline-test-").resolve("clip.wav");
        Files.write(audio, new byte[64]);
        voicesRoot = Files.createTempDirectory("pipeline-voices-");
        SpeakerNamer.setRootForTest(voicesRoot);
        FfmpegProbe.setForTest(new FfmpegProbe.ProbeResult(false, "forced-off: transcribing must not happen"));
        stub = new StubClient();
        DiarizationRouter.setClientForTest(stub);
        services.UvProbe.setForTest(new services.UvProbe.ProbeResult(true, "forced on"));
        ConfigService.set("transcription.diarization.local.hfToken", "hf_test");
        // Correction/emotion stages are seam-tested elsewhere; gate them off
        // so the contract test exercises sequencing, not their internals.
        ConfigService.set("transcription.diarization.overlapReattribution", "false");
        ConfigService.set("transcription.emotion.enabled", "false");
    }

    @AfterEach
    void tearDown() throws Exception {
        DiarizationRouter.setClientForTest(null);
        SpeakerNamer.resetForTest();
        FfmpegProbe.setForTest(null);
        services.UvProbe.setForTest(null);
        ConfigService.delete("transcription.diarization.local.hfToken");
        ConfigService.delete("transcription.diarization.overlapReattribution");
        ConfigService.delete("transcription.emotion.enabled");
        Files.deleteIfExists(DiarizationCache.cacheFile(audio));
        Files.deleteIfExists(audio);
    }

    private void seedTranscriptCache() {
        DiarizationCache.write(audio, -1, new DiarizationRouter.Result(TWO_SPEAKERS, List.of()));
        DiarizationCache.writeTranscript(audio, currentModelId(), null, List.of(
                new WhisperJniTranscriber.Segment(0, 2000, "Hello there."),
                new WhisperJniTranscriber.Segment(2000, 4000, "General Kenobi.")));
    }

    private static String currentModelId() {
        return services.transcription.WhisperModel
                .byId(ConfigService.get("transcription.localModel"))
                .orElse(services.transcription.WhisperModel.DEFAULT).id();
    }

    @Test
    void identificationFirst_unknownVoices_haltsBeforeAnyTranscription() {
        // ffmpeg is forced off: if the pipeline tried to transcribe it would
        // THROW. Returning IdentificationNeeded proves the halt fired first —
        // the "no full-recording inference before enrollment" promise.
        var outcome = DiarizationPipeline.run(audio,
                new DiarizationPipeline.Options(null, null, true, false));

        var need = assertInstanceOf(DiarizationPipeline.IdentificationNeeded.class, outcome);
        assertEquals(List.of(0, 1), need.unmatched(), "both anonymous clusters need naming");
        assertTrue(need.names().isEmpty());
        assertEquals(1, stub.calls, "diarization itself must have run (identification needs it)");
    }

    @Test
    void allowAnonymous_waivesTheHalt_andRendersAnonymousLabels() {
        seedTranscriptCache();

        var outcome = DiarizationPipeline.run(audio,
                new DiarizationPipeline.Options(null, null, true, true));

        var transcript = assertInstanceOf(DiarizationPipeline.Transcript.class, outcome);
        assertEquals(2, transcript.entries().size());
        assertEquals("SPEAKER_00", transcript.entries().get(0).speaker());
        assertEquals("Hello there.", transcript.entries().get(0).text());
        assertEquals("SPEAKER_01", transcript.entries().get(1).speaker());
    }

    @Test
    void secondRun_hitsBothCaches_noRouterOrTranscriberInvocation() {
        seedTranscriptCache();

        var outcome = DiarizationPipeline.run(audio,
                new DiarizationPipeline.Options(null, null, false, true));

        assertInstanceOf(DiarizationPipeline.Transcript.class, outcome);
        assertEquals(0, stub.calls,
                "the diarization cache must satisfy the run without touching the sidecar");
        // and the transcript came from the cache — ffmpeg-off would have
        // thrown had whisper been consulted.
    }

    @Test
    void cacheableFalse_neverCreatesTheSiblingCacheFile() {
        // JCLAW-636: the REST endpoint's temp uploads must not litter. The
        // run proceeds to transcription (ffmpeg-off throws there), by which
        // point a cacheable run would already have written the sibling.
        assertThrows(TranscriptionException.class, () -> DiarizationPipeline.run(audio,
                new DiarizationPipeline.Options(null, null, false, true, false)));

        assertEquals(1, stub.calls, "cacheable=false still diarizes — it just never persists");
        assertFalse(Files.exists(DiarizationCache.cacheFile(audio)),
                "no sibling .diarization.json may be created for transient inputs");
    }

    @Test
    void cacheableTrue_writesTheSibling_provingTheLitterContrast() {
        assertThrows(TranscriptionException.class, () -> DiarizationPipeline.run(audio,
                new DiarizationPipeline.Options(null, null, false, true, true)));

        assertTrue(Files.exists(DiarizationCache.cacheFile(audio)),
                "the cacheable path persists the diarization before transcription");
    }
}
