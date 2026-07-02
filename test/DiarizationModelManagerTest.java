import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizationModelManager;
import services.transcription.DiarizationModelManager.DiarizationModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * JCLAW-556: download/verify path for the sherpa diarization models —
 * mirrors {@link WhisperModelManagerTest}'s MockWebServer approach (same
 * HF X-Linked-Etag trust chain, same tempdir isolation), minus the
 * progress/status machinery this manager deliberately doesn't have.
 */
class DiarizationModelManagerTest extends UnitTest {

    private MockWebServer server;
    private Path tempRoot;
    private static final DiarizationModel MODEL = DiarizationModel.SEGMENTATION;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        tempRoot = Files.createTempDirectory("diarization-model-test-");
        DiarizationModelManager.setRootForTest(tempRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        DiarizationModelManager.setRootForTest(null);
        server.close();
        deleteRecursive(tempRoot);
    }

    @Test
    void download_succeeds_whenSha256MatchesHfEtag() throws Exception {
        var body = "fake-onnx-model-bytes".getBytes();
        enqueueHead(sha256Hex(body));
        enqueueGet(body);

        var path = DiarizationModelManager.doDownload(MODEL, server.url("/model.onnx").toString());

        assertEquals(DiarizationModelManager.localPath(MODEL), path);
        assertArrayEquals(body, Files.readAllBytes(path));
        assertTrue(DiarizationModelManager.availableLocally(MODEL));
    }

    @Test
    void download_throwsAndDeletesPartial_onSha256Mismatch() throws Exception {
        enqueueHead(sha256Hex("something-else-entirely".getBytes()));
        enqueueGet("the-actual-streamed-bytes".getBytes());

        var url = server.url("/model.onnx").toString();
        var io = assertThrows(IOException.class,
                () -> DiarizationModelManager.doDownload(MODEL, url));

        assertTrue(io.getMessage().contains("SHA256 mismatch"), io.getMessage());
        assertFalse(Files.exists(DiarizationModelManager.localPath(MODEL)),
                "final file must not exist on hash failure");
        assertFalse(Files.exists(tempRoot.resolve(MODEL.filename() + ".part")),
                "partial file must be cleaned up on hash failure");
    }

    @Test
    void download_throws_whenEtagHeaderMissing() {
        server.enqueue(new MockResponse.Builder().code(200).build()); // HEAD without X-Linked-Etag

        var url = server.url("/model.onnx").toString();
        var io = assertThrows(IOException.class,
                () -> DiarizationModelManager.doDownload(MODEL, url));

        assertTrue(io.getMessage().contains("X-Linked-Etag"), io.getMessage());
    }

    @Test
    void ensureAvailable_shortCircuits_whenFileAlreadyOnDisk() throws Exception {
        var path = DiarizationModelManager.localPath(MODEL);
        Files.createDirectories(tempRoot);
        Files.write(path, "already-here".getBytes());

        // No mock responses enqueued — a network hit would throw.
        assertEquals(path, DiarizationModelManager.ensureAvailable(MODEL));
    }

    private void enqueueHead(String sha256) {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("X-Linked-Etag", "\"" + sha256 + "\"")
                .build());
    }

    private void enqueueGet(byte[] body) {
        var buf = new Buffer();
        buf.write(ByteString.of(body));
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/octet-stream")
                .body(buf)
                .build());
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException _) {} });
        }
    }
}
