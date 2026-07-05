import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizationCache;
import services.transcription.DiarizationRouter;
import services.transcription.SpeakerSegment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** JCLAW-611: the per-attachment diarization cache round-trip and its
 *  staleness keys (speaker count, cluster threshold). */
class DiarizationCacheTest extends UnitTest {

    private Path audio;

    @BeforeEach
    void setUp() throws Exception {
        audio = Files.createTempFile("diarization-cache-test-", ".wav");
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(DiarizationCache.cacheFile(audio));
        Files.deleteIfExists(audio);
    }

    private static DiarizationRouter.Result result() {
        return new DiarizationRouter.Result(
                List.of(new SpeakerSegment(0.5, 4.25, 0),
                        new SpeakerSegment(4.5, 9.0, 1)),
                List.of(new double[]{4.0, 4.6}));
    }

    @Test
    void roundTrip_preservesSegmentsAndOverlaps() {
        DiarizationCache.write(audio, -1, result());

        var cached = DiarizationCache.read(audio, -1);

        assertNotNull(cached);
        assertEquals(2, cached.segments().size());
        assertEquals(0.5, cached.segments().get(0).start(), 1e-9);
        assertEquals(1, cached.segments().get(1).speaker());
        assertEquals(1, cached.overlaps().size());
        assertEquals(4.0, cached.overlaps().get(0)[0], 1e-9);
    }

    @Test
    void read_missesOnChangedInputs_absence_andCorruption() throws Exception {
        assertNull(DiarizationCache.read(audio, -1), "no cache file yet");

        DiarizationCache.write(audio, -1, result());
        assertNull(DiarizationCache.read(audio, 2),
                "a different requested speaker count must miss");

        Files.writeString(DiarizationCache.cacheFile(audio), "not json at all");
        assertNull(DiarizationCache.read(audio, -1), "corruption reads as a miss");
    }

    @Test
    void read_missesOnFingerprintMismatch_andPreFingerprintFiles() throws Exception {
        DiarizationCache.write(audio, -1, result());
        var file = DiarizationCache.cacheFile(audio);

        // Model upgrade: same shape, different model id -> miss (JCLAW-621).
        Files.writeString(file, Files.readString(file)
                .replace("speaker-diarization-community-1", "speaker-diarization-community-2"));
        assertNull(DiarizationCache.read(audio, -1), "a model change must invalidate the cache");

        // Pre-fingerprint file (no model/pipelineVersion keys) -> miss.
        Files.writeString(file,
                "{\"numSpeakers\":-1,\"segments\":[],\"overlaps\":[]}");
        assertNull(DiarizationCache.read(audio, -1), "legacy cache files read as misses");
    }

    @Test
    void transcriptSection_roundTrips_andKeysOnModelAndLanguage() {
        DiarizationCache.write(audio, -1, result());
        var segments = List.of(
                new services.transcription.WhisperJniTranscriber.Segment(0, 2000, "Hello."),
                new services.transcription.WhisperJniTranscriber.Segment(2500, 4000, "World."));
        DiarizationCache.writeTranscript(audio, "large", null, segments);

        var cached = DiarizationCache.readTranscript(audio, "large", null);
        assertNotNull(cached);
        assertEquals(2, cached.size());
        assertEquals("Hello.", cached.get(0).text());
        assertEquals(2500, cached.get(1).startMs());

        assertNull(DiarizationCache.readTranscript(audio, "small", null),
                "a different model must miss");
        assertNull(DiarizationCache.readTranscript(audio, "large", "ms"),
                "a different language must miss (null keys as auto)");
        assertNotNull(DiarizationCache.read(audio, -1),
                "the merge must not clobber the diarization section");
    }

    @Test
    void msddSection_roundTrips_andRequiresAnchorAndSpeakerCount() {
        assertNull(DiarizationCache.readMsdd(audio, 2), "no cache file yet");
        DiarizationCache.writeMsdd(audio, 2,
                List.of(new SpeakerSegment(1, 2, 0)));
        assertNull(DiarizationCache.readMsdd(audio, 2),
                "MSDD without a diarization anchor section is never written");

        DiarizationCache.write(audio, -1, result());
        DiarizationCache.writeMsdd(audio, 2, List.of(new SpeakerSegment(1.5, 3.5, 1)));

        var cached = DiarizationCache.readMsdd(audio, 2);
        assertNotNull(cached);
        assertEquals(1, cached.size());
        assertEquals(1.5, cached.get(0).start(), 1e-9);
        assertEquals(1, cached.get(0).speaker());
        assertNull(DiarizationCache.readMsdd(audio, 3),
                "a different speaker count must miss");
        assertNotNull(DiarizationCache.read(audio, -1),
                "the merge must not clobber the diarization section");
    }
}
