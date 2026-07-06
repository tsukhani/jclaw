import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.AsrSidecarClient;
import services.transcription.TranscriptionException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JCLAW-565: the pyannote sidecar HTTP protocol — request shape (path-based
 * audio handoff, optional num_speakers), response parsing into
 * error mapping. MockWebServer + the
 * base-URL test ctor, mirroring {@link LocalVideoGenerationClientTest}; the
 * manager/daemon layer is never touched.
 */
class AsrSidecarClientTest extends UnitTest {

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

    private AsrSidecarClient client() {
        return new AsrSidecarClient(
                server.url("/").toString().replaceAll("/$", ""), new OkHttpClient());
    }






}
