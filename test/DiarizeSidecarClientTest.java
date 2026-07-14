import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizeSidecarClient;
import services.transcription.TranscriptionException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JCLAW-565 revival: the diarization sidecar HTTP protocol — request shape
 * (path-based audio handoff, optional speaker-count hint), turn parsing, and
 * error mapping. MockWebServer + the base-URL test ctor, mirroring
 * {@link AsrSidecarClientTest}; the manager/daemon layer is never touched.
 */
class DiarizeSidecarClientTest extends UnitTest {

    private MockWebServer server;
    private Path audio;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        audio = Files.createTempFile("diarize-client-test-", ".mp3");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        Files.deleteIfExists(audio);
    }

    private DiarizeSidecarClient client() {
        return new DiarizeSidecarClient(
                server.url("/").toString().replaceAll("/$", ""), new OkHttpClient());
    }

    @Test
    void diarize_parsesTurnsInOrder() throws Exception {
        server.enqueue(json(200, """
                {"turns": [
                  {"startMs": 1769, "endMs": 2393, "speaker": "SPEAKER_00"},
                  {"startMs": 7070, "endMs": 7540, "speaker": "SPEAKER_01"}
                ]}"""));

        var turns = client().diarize(audio, null, false);

        assertEquals(2, turns.size());
        assertEquals(1769, turns.get(0).startMs());
        assertEquals(2393, turns.get(0).endMs());
        assertEquals("SPEAKER_00", turns.get(0).speaker());
        assertEquals("SPEAKER_01", turns.get(1).speaker());
        assertNull(turns.get(0).emotion(), "no emotion field → null");

        var recorded = server.takeRequest();
        assertEquals("/diarize", recorded.getUrl().encodedPath());
        var body = recorded.getBody().utf8();
        assertTrue(body.contains(audio.toAbsolutePath().toString()),
                "request carries the audio path: " + body);
        assertFalse(body.contains("num_speakers"),
                "no speaker hint when null: " + body);
    }

    @Test
    void diarize_includesSpeakerHintWhenGiven() throws Exception {
        server.enqueue(json(200, "{\"turns\": []}"));

        client().diarize(audio, 2, false);

        var body = server.takeRequest().getBody().utf8();
        assertTrue(body.contains("\"num_speakers\":2"),
                "request carries the speaker-count hint: " + body);
        assertFalse(body.contains("emotions"), "no emotions flag when false: " + body);
    }

    @Test
    void diarize_sendsEmotionsFlagAndParsesPerTurnEmotion() throws Exception {
        server.enqueue(json(200, """
                {"turns": [
                  {"startMs": 0, "endMs": 9000, "speaker": "SPEAKER_00",
                   "emotion": {"label": "angry", "confidence": 0.83,
                               "valence": 0.54, "arousal": 0.85, "dominance": 0.79}},
                  {"startMs": 9000, "endMs": 9200, "speaker": "SPEAKER_01"}
                ]}"""));

        var turns = client().diarize(audio, null, true);

        assertTrue(server.takeRequest().getBody().utf8().contains("\"emotions\":true"),
                "request carries the emotions flag");
        var emo = turns.get(0).emotion();
        assertNotNull(emo, "first turn parsed an emotion");
        assertEquals("angry", emo.label());
        assertEquals(0.85, emo.arousal(), 1e-9);
        assertNull(turns.get(1).emotion(), "turn without an emotion field → null");
    }

    @Test
    void diarize_httpErrorMapsToTranscriptionException() {
        server.enqueue(json(500, "{\"error\": \"pipeline exploded\"}"));

        var client = client();
        var e = assertThrows(TranscriptionException.class,
                () -> client.diarize(audio, null, false));
        assertTrue(e.getMessage().contains("diarize sidecar failed: HTTP 500"),
                "names the sidecar and status: " + e.getMessage());
    }

    @Test
    void diarize_unparseableBodyMapsToTranscriptionException() {
        server.enqueue(json(200, "not json at all"));

        var client = client();
        var e = assertThrows(TranscriptionException.class,
                () -> client.diarize(audio, null, false));
        assertTrue(e.getMessage().contains("unparseable"),
                "names the parse failure: " + e.getMessage());
    }

    private static MockResponse json(int code, String body) {
        var buf = new Buffer();
        buf.writeUtf8(body);
        return new MockResponse.Builder().code(code)
                .addHeader("Content-Type", "application/json").body(buf).build();
    }
}
