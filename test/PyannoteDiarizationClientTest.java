import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.PyannoteDiarizationClient;
import services.transcription.TranscriptionException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JCLAW-565: the pyannote sidecar HTTP protocol — request shape (path-based
 * audio handoff, optional num_speakers), response parsing into
 * SherpaDiarizer.SpeakerSegment, and error mapping. MockWebServer + the
 * base-URL test ctor, mirroring {@link LocalVideoGenerationClientTest}; the
 * manager/daemon layer is never touched.
 */
class PyannoteDiarizationClientTest extends UnitTest {

    private MockWebServer server;
    private Path audio;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        audio = Files.createTempFile("diarize-client-test-", ".wav");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        Files.deleteIfExists(audio);
    }

    private PyannoteDiarizationClient client() {
        return new PyannoteDiarizationClient(
                server.url("/").toString().replaceAll("/$", ""), new OkHttpClient());
    }

    @Test
    void diarize_parsesSegments_andSendsAbsolutePath() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("""
                {"segments": [
                   {"start": 0.5, "end": 4.25, "speaker": 0},
                   {"start": 4.5, "end": 9.0, "speaker": 1}],
                 "device": "mps", "seconds": 3.2}""").build());

        var segments = client().diarize(audio, -1);

        assertEquals(2, segments.size());
        assertEquals(0.5, segments.get(0).start(), 1e-9);
        assertEquals(4.25, segments.get(0).end(), 1e-9);
        assertEquals(0, segments.get(0).speaker());
        assertEquals(1, segments.get(1).speaker());

        var recorded = server.takeRequest();
        assertEquals("/diarize", recorded.getUrl().encodedPath());
        var body = recorded.getBody().utf8();
        assertTrue(body.contains(audio.toAbsolutePath().toString()), body);
        assertFalse(body.contains("num_speakers"),
                "below-2 speaker count must let the pipeline decide: " + body);
    }

    @Test
    void diarize_sendsNumSpeakers_whenTwoOrMore() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200)
                .body("{\"segments\": []}").build());

        client().diarize(audio, 3);

        var body = server.takeRequest().getBody().utf8();
        assertTrue(body.contains("\"num_speakers\":3"), body);
    }

    @Test
    void diarize_mapsHttpErrorsToTranscriptionException() {
        server.enqueue(new MockResponse.Builder().code(500)
                .body("{\"error\": \"failed to load pipeline: gated model\"}").build());

        var e = assertThrows(TranscriptionException.class, () -> client().diarize(audio, -1));

        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
        assertTrue(e.getMessage().contains("gated model"), e.getMessage());
    }

    @Test
    void parseSegments_rejectsGarbage() {
        var e = assertThrows(TranscriptionException.class,
                () -> PyannoteDiarizationClient.parseSegments("not json at all"));
        assertTrue(e.getMessage().contains("unparseable"), e.getMessage());
    }
}
