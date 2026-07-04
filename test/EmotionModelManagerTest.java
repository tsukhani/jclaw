import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.EmotionModelManager;
import services.transcription.EmotionModelManager.EmotionModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * JCLAW-563/564: download/verify path for the two emotion2vec+ artifacts —
 * mirrors {@link DiarizationModelManagerTest}'s MockWebServer approach
 * (same HF X-Linked-Etag trust chain, same tempdir isolation).
 */
class EmotionModelManagerTest extends UnitTest {

    private MockWebServer server;
    private Path tempRoot;
    private static final EmotionModel MODEL = EmotionModel.EMBEDDING;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        tempRoot = Files.createTempDirectory("emotion-model-test-");
        EmotionModelManager.setRootForTest(tempRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        EmotionModelManager.setRootForTest(null);
        server.close();
        deleteRecursive(tempRoot);
    }

    @Test
    void download_succeeds_whenSha256MatchesHfEtag() throws Exception {
        var body = "fake-onnx-model-bytes".getBytes();
        enqueueHead(sha256Hex(body));
        enqueueGet(body);

        var path = EmotionModelManager.doDownload(MODEL, server.url("/model.onnx").toString());

        assertEquals(EmotionModelManager.localPath(MODEL), path);
        assertArrayEquals(body, Files.readAllBytes(path));
        assertTrue(EmotionModelManager.availableLocally(MODEL));
    }

    @Test
    void download_throwsAndDeletesPartial_onSha256Mismatch() throws Exception {
        enqueueHead(sha256Hex("something-else-entirely".getBytes()));
        enqueueGet("the-actual-streamed-bytes".getBytes());

        var url = server.url("/model.onnx").toString();
        var io = assertThrows(IOException.class, () -> EmotionModelManager.doDownload(MODEL, url));

        assertTrue(io.getMessage().contains("SHA256 mismatch"), io.getMessage());
        assertFalse(Files.exists(EmotionModelManager.localPath(MODEL)),
                "final file must not exist on hash failure");
        assertFalse(Files.exists(tempRoot.resolve(MODEL.filename() + ".part")),
                "partial file must be cleaned up on hash failure");
    }

    @Test
    void download_throws_whenEtagHeaderMissing() {
        server.enqueue(new MockResponse.Builder().code(200).build()); // HEAD without X-Linked-Etag

        var url = server.url("/model.onnx").toString();
        var io = assertThrows(IOException.class, () -> EmotionModelManager.doDownload(MODEL, url));

        assertTrue(io.getMessage().contains("X-Linked-Etag"), io.getMessage());
    }

    @Test
    void ensureAvailable_shortCircuits_whenFileAlreadyOnDisk() throws Exception {
        var path = EmotionModelManager.localPath(MODEL);
        Files.createDirectories(tempRoot);
        Files.write(path, "already-here".getBytes());

        // No mock responses enqueued — a network hit would throw.
        assertEquals(path, EmotionModelManager.ensureAvailable(MODEL));
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
