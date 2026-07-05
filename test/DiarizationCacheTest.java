import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizationCache;
import services.transcription.DiarizationRouter;
import services.transcription.SherpaDiarizer;

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
                List.of(new SherpaDiarizer.SpeakerSegment(0.5, 4.25, 0),
                        new SherpaDiarizer.SpeakerSegment(4.5, 9.0, 1)),
                List.of(new double[]{4.0, 4.6}),
                true);
    }

    @Test
    void roundTrip_preservesSegmentsOverlapsAndBackend() {
        DiarizationCache.write(audio, 0.3f, -1, result());

        var cached = DiarizationCache.read(audio, 0.3f, -1);

        assertNotNull(cached);
        assertEquals(2, cached.segments().size());
        assertEquals(0.5, cached.segments().get(0).start(), 1e-9);
        assertEquals(1, cached.segments().get(1).speaker());
        assertEquals(1, cached.overlaps().size());
        assertEquals(4.0, cached.overlaps().get(0)[0], 1e-9);
        assertTrue(cached.viaPyannote());
    }

    @Test
    void read_missesOnChangedInputs_absence_andCorruption() throws Exception {
        assertNull(DiarizationCache.read(audio, 0.3f, -1), "no cache file yet");

        DiarizationCache.write(audio, 0.3f, -1, result());
        assertNull(DiarizationCache.read(audio, 0.3f, 2),
                "a different requested speaker count must miss");
        assertNull(DiarizationCache.read(audio, 0.4f, -1),
                "a different cluster threshold must miss");

        Files.writeString(DiarizationCache.cacheFile(audio), "not json at all");
        assertNull(DiarizationCache.read(audio, 0.3f, -1), "corruption reads as a miss");
    }
}
